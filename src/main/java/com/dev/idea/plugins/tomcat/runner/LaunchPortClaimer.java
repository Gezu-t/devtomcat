package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.utils.PortConflictDetector;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves and atomically claims all ports a Tomcat launch needs (HTTP, HTTPS,
 * AJP, JMX, shutdown, JDWP) before the JVM starts, then writes the resolved
 * values back to the configuration so every downstream reader (UI, dashboard,
 * Services panel, browser URL) sees runtime reality.
 *
 * <p>Extracted from {@link TomcatCommandLineState} so the launcher can stay
 * focused on lifecycle. The contract this class enforces:
 * <ol>
 *   <li><b>Atomic claim across launches.</b> Two configurations starting
 *       simultaneously cannot land on the same port — the
 *       {@link TomcatPortRegistry} mediates the claim, and any port the
 *       registry bumps further produces a user-visible warning.</li>
 *   <li><b>Carry-over fast path.</b> A relaunch (cross-executor, debug→run)
 *       reads the previous launch's resolved ports from
 *       {@link ExecutionEnvironment#getUserData} and reclaims them
 *       without re-running conflict detection — re-running would race
 *       the OS's {@code TIME_WAIT} state on the freshly-released port.</li>
 *   <li><b>Writeback in single-instance mode only.</b> Parallel-run mode
 *       skips writeback so a transient bump (8083→8090) doesn't ratchet
 *       the user's seed permanently away from intent.</li>
 * </ol>
 *
 * <p>The static {@link #writeBackResolvedPorts} and
 * {@link #writeBackResolvedDebugPort} methods are exposed package-privately
 * so {@code PortWritebackPlatformTest} can pin the writeback contract
 * without instantiating the full claimer (which needs an
 * {@link ExecutionEnvironment}).
 */
final class LaunchPortClaimer {

    private static final Logger LOG = Logger.getInstance(LaunchPortClaimer.class);

    /** Result of a {@link #claim()} call. */
    record Resolution(@NotNull PortConfig ports, int debugPort) {
        /** Returns {@code true} if a debug port was claimed (debug mode). */
        boolean hasDebugPort() {
            return debugPort > 0;
        }
    }

    private final TomcatRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private final TomcatDeploymentLogger deploymentLogger;

    LaunchPortClaimer(@NotNull TomcatRunConfiguration configuration,
                      @NotNull ExecutionEnvironment environment,
                      @NotNull TomcatDeploymentLogger deploymentLogger) {
        this.configuration = configuration;
        this.environment = environment;
        this.deploymentLogger = deploymentLogger;
    }

    /**
     * Detect port conflicts (or honor a carry-over from a prior launch),
     * atomically claim all needed ports, write resolved values back to the
     * config, and surface any user-visible changes.
     *
     * @return the resolved ports and (in debug mode) JDWP port. Debug port
     *         is {@code -1} when not launching under the Debug executor.
     */
    @NotNull
    Resolution claim() {
        String configName = configuration.getName();
        TomcatPortRegistry registry = TomcatPortRegistry.getInstance();

        // Carryover path: a prior process (stopped by stopAndRelaunch) handed
        // its resolved ports down via ExecutionEnvironment user data. Re-use
        // them atomically instead of re-running conflict detection — that
        // would see the OS's TIME_WAIT socket state on the just-released
        // port and bump it up needlessly.
        PortConfig carried = environment.getUserData(TomcatCommandLineState.CARRIED_PORTS_KEY);
        if (carried != null) {
            List<String> changes = new ArrayList<>();
            claimAndTrack(carried, registry, configName, changes);
            writeBackResolvedPorts(configuration, carried);

            int debugPort = -1;
            Integer carriedDebug = environment.getUserData(TomcatCommandLineState.CARRIED_DEBUG_PORT_KEY);
            if (carriedDebug != null && carriedDebug > 0) {
                int claimed = registry.claimPort(carriedDebug, configName);
                if (claimed == -1) {
                    changes.add("Debug (JDWP) port " + carriedDebug
                            + ": all ports in search range exhausted — debugger may fail to attach");
                    debugPort = carriedDebug;
                } else {
                    if (claimed != carriedDebug) {
                        changes.add("Debug (JDWP) port " + carriedDebug
                                + " claimed by a concurrent instance, resolved to " + claimed);
                    }
                    debugPort = claimed;
                }
                writeBackResolvedDebugPort(configuration, debugPort);
            }
            logResolutionChanges(changes);
            return new Resolution(carried, debugPort);
        }

        PortConfig originalPorts = configuration.getConfigData().getPortConfig();
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(environment.getExecutor().getId());

        if (isDebug) {
            return claimForDebug(registry, configName, originalPorts);
        }
        return claimForRun(registry, configName, originalPorts);
    }

    @NotNull
    private Resolution claimForDebug(@NotNull TomcatPortRegistry registry,
                                     @NotNull String configName,
                                     @NotNull PortConfig originalPorts) {
        DebugConfig debugConfig = configuration.getConfigData().getDebugConfig();
        int seedDebugPort = debugConfig != null ? debugConfig.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;

        PortConflictDetector.DebugPortResolution resolution =
                PortConflictDetector.resolveConflictsWithDebug(originalPorts, seedDebugPort);

        PortConfig rp = resolution.getResolvedConfig();
        claimAndTrack(rp, registry, configName, resolution.getChanges());

        int preClaimDebug = resolution.getDebugPort();
        int resolvedDebugPort = registry.claimPort(preClaimDebug, configName);
        if (resolvedDebugPort == -1) {
            resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                    + ": all ports in search range exhausted — debugger may fail to attach");
            resolvedDebugPort = preClaimDebug; // keep original; JVM will fail with a clear error
        } else if (resolvedDebugPort != preClaimDebug) {
            resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                    + " claimed by a concurrent instance, resolved to " + resolvedDebugPort);
        }

        writeBackResolvedPorts(configuration, rp);
        writeBackResolvedDebugPort(configuration, resolvedDebugPort);
        logResolutionChanges(resolution.getChanges());
        return new Resolution(rp, resolvedDebugPort);
    }

    @NotNull
    private Resolution claimForRun(@NotNull TomcatPortRegistry registry,
                                   @NotNull String configName,
                                   @NotNull PortConfig originalPorts) {
        PortConflictDetector.PortResolution resolution =
                PortConflictDetector.resolveConflicts(originalPorts);

        PortConfig rp = resolution.getResolvedConfig();
        claimAndTrack(rp, registry, configName, resolution.getChanges());
        writeBackResolvedPorts(configuration, rp);
        logResolutionChanges(resolution.getChanges());
        return new Resolution(rp, -1);
    }

    /**
     * Claim each port in the resolved config through the registry, recording
     * any further bump as a user-visible change entry. The registry mediates
     * across concurrent launchers — if another instance grabbed the port
     * after {@link PortConflictDetector} picked it, the registry returns the
     * next free slot and we record the bump.
     */
    private void claimAndTrack(@NotNull PortConfig rp,
                               @NotNull TomcatPortRegistry registry,
                               @NotNull String configName,
                               @NotNull List<String> changes) {
        rp.setHttp(claimOrRecord(rp.getHttp(), "HTTP", registry, configName, changes, rp.getHttp()));
        rp.setShutdown(claimOrRecord(rp.getShutdown(), "Shutdown", registry, configName, changes, rp.getShutdown()));

        if (rp.isHttpsEnabled()) {
            rp.setHttps(claimOrRecord(rp.getHttps(), "HTTPS", registry, configName, changes, rp.getHttps()));
        }
        if (rp.isJmxEnabled()) {
            rp.setJmx(claimOrRecord(rp.getJmx(), "JMX", registry, configName, changes, rp.getJmx()));
        }
        if (rp.isAjpEnabled()) {
            rp.setAjp(claimOrRecord(rp.getAjp(), "AJP", registry, configName, changes, rp.getAjp()));
        }
    }

    /**
     * Claim {@code port} through the registry; on bump, append a change entry
     * and return the new port. On exhaustion, append a warning and return the
     * original port (the JVM will then fail-fast with a clear bind error).
     */
    private static int claimOrRecord(int port,
                                     @NotNull String label,
                                     @NotNull TomcatPortRegistry registry,
                                     @NotNull String configName,
                                     @NotNull List<String> changes,
                                     int fallback) {
        int claimed = registry.claimPort(port, configName);
        if (claimed == -1) {
            changes.add(label + " port " + port + ": all ports in search range exhausted — Tomcat may fail to bind");
            return fallback;
        }
        if (claimed != port) {
            changes.add(label + " port " + port + " claimed by a concurrent instance, resolved to " + claimed);
        }
        return claimed;
    }

    private void logResolutionChanges(@NotNull List<String> changes) {
        if (changes.isEmpty()) return;
        deploymentLogger.logServerWarning("Port conflicts detected and auto-resolved:");
        for (String change : changes) {
            deploymentLogger.logServerWarning("  " + change);
        }
        TomcatNotifier.notify(configuration.getProject(),
                "DevTomcat: Port Auto-Resolved",
                String.join("\n", changes),
                NotificationType.WARNING);
    }

    // --- Static writeback API (preserved across the extraction) -----------

    /**
     * Writes resolved ports back to the configuration's {@link PortConfig}
     * so it becomes the single source of truth for every downstream reader.
     *
     * <p><b>No-op in effective parallel-run mode.</b> Writing back in parallel
     * mode would ratchet the user's seed away from intent after any transient
     * conflict ({@code 8083 → 8090} would survive even after 8083 frees up).
     * Per-launch ports stay on the handler via
     * {@link TomcatCommandLineState#CARRIED_PORTS_KEY}; the Services panel
     * and runtime consumers read from there.
     *
     * <p>After mutation in single-instance mode, publishes
     * {@link RunManagerListener#runConfigurationChanged} on the project
     * message bus so listeners (Run Dashboard, currently-open dialogs on
     * reload, icon caches) requery the configuration.
     */
    static void writeBackResolvedPorts(@NotNull TomcatRunConfiguration configuration,
                                       @NotNull PortConfig resolved) {
        if (configuration.isParallelRunEffective()) return;

        PortConfig target = configuration.getConfigData().getPortConfig();
        boolean changed = false;
        if (target.getHttp() != resolved.getHttp()) {
            target.setHttp(resolved.getHttp());
            changed = true;
        }
        if (target.getShutdown() != resolved.getShutdown()) {
            target.setShutdown(resolved.getShutdown());
            changed = true;
        }
        if (target.isHttpsEnabled() && target.getHttps() != resolved.getHttps()) {
            target.setHttps(resolved.getHttps());
            changed = true;
        }
        if (target.isJmxEnabled() && target.getJmx() != resolved.getJmx()) {
            target.setJmx(resolved.getJmx());
            changed = true;
        }
        if (target.isAjpEnabled() && target.getAjp() != resolved.getAjp()) {
            target.setAjp(resolved.getAjp());
            changed = true;
        }
        if (changed) {
            notifyConfigurationChanged(configuration);
        }
    }

    /**
     * Writes the resolved debug port back to {@code DebugConfig} so the
     * config dialog and serializer agree on the port the JVM actually bound.
     * Same parallel-run skip as {@link #writeBackResolvedPorts}.
     */
    static void writeBackResolvedDebugPort(@NotNull TomcatRunConfiguration configuration,
                                           int resolvedDebug) {
        if (configuration.isParallelRunEffective()) return;
        if (resolvedDebug <= 0) return;
        DebugConfig debugConfig = configuration.getConfigData().getDebugConfig();
        if (debugConfig != null && debugConfig.getPort() != resolvedDebug) {
            debugConfig.setPort(resolvedDebug);
            notifyConfigurationChanged(configuration);
        }
    }

    /**
     * Publish {@link RunManagerListener#runConfigurationChanged} on the
     * project's message bus so every interested listener re-reads the
     * now-resolved port values: the editor, the Run Dashboard, the
     * Services panel, and anything else subscribed to the standard IntelliJ
     * change channel. Also forces a dashboard rebuild as a belt-and-braces
     * — some listeners rely on it rather than subscribing to the topic.
     * All UI work runs on the EDT.
     */
    private static void notifyConfigurationChanged(@NotNull TomcatRunConfiguration configuration) {
        Project project = configuration.getProject();
        if (project == null || project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                RunnerAndConfigurationSettings settings =
                        RunManager.getInstance(project).findSettings(configuration);
                if (settings != null) {
                    project.getMessageBus()
                            .syncPublisher(RunManagerListener.TOPIC)
                            .runConfigurationChanged(settings);
                }
            } catch (Exception e) {
                LOG.debug("RunManager change notification after port writeback failed", e);
            }
            try {
                RunDashboardManager.getInstance(project).updateDashboard(true);
            } catch (Exception e) {
                LOG.debug("Dashboard refresh after port writeback failed", e);
            }
        });
    }
}
