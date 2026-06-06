package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.utils.PortConflictDetector;
import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
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

    /** Seed-port settle poll: brief, bounded wait for a just-stopped JVM's socket to free. */
    private static final int SEED_SETTLE_ATTEMPTS = 3;
    private static final long SEED_SETTLE_INTERVAL_MS = 150L;

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
        PortConfig originalPorts = configuration.getConfigData().getPortConfig();

        // Single-instance only: drop any stale registry reservations left by THIS
        // config's prior (stopped / stopping) launch before we re-claim. The old
        // handler's processTerminated releases them too, but that can lag a fast
        // Stop→Run — and without this the new launch sees its own old reservation,
        // bumps the port, and the writeback persists the bump (the "port keeps
        // increasing" symptom). Safe because a single-instance config never has two
        // live instances at once. In parallel-run mode siblings legitimately hold
        // same-config ports, so we must NOT release there.
        if (!configuration.isParallelRunEffective()) {
            registry.releaseAllFor(configName);
        }

        // Carryover path: a prior process (stopped by stopAndRelaunch) handed
        // its resolved ports down via ExecutionEnvironment user data. Re-use
        // them atomically instead of re-running conflict detection — that
        // would see the OS's TIME_WAIT socket state on the just-released
        // port and bump it up needlessly.
        PortConfig carried = environment.getUserData(TomcatCommandLineState.CARRIED_PORTS_KEY);
        if (carried != null) {
            logActiveStrategy(originalPorts);
            List<String> changes = new ArrayList<>();
            claimAndTrack(carried, registry, configName, changes);
            logResolutionChanges(changes);
            // Strategy gate AFTER claimAndTrack so a registry-mediated bump
            // (another DevTomcat config holding the carried port) is caught.
            enforcePortStrategy(originalPorts, carried);
            writeBackResolvedPorts(configuration, carried);

            int debugPort = -1;
            Integer carriedDebug = environment.getUserData(TomcatCommandLineState.CARRIED_DEBUG_PORT_KEY);
            if (carriedDebug != null && carriedDebug > 0) {
                int claimed = registry.claimPort(carriedDebug, configName);
                if (claimed == -1) {
                    changes.add("Debug (JDWP) port " + carriedDebug
                            + ": all ports in search range exhausted; debugger may fail to attach");
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
            return new Resolution(carried, debugPort);
        }

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
        logActiveStrategy(originalPorts);
        DebugConfig debugConfig = configuration.getConfigData().getDebugConfig();
        int seedDebugPort = debugConfig != null ? debugConfig.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;

        // Seed from preferred intent (see claimForRun) and let a just-stopped JVM
        // settle so a fast Stop→Run reclaims the same ports instead of bumping.
        PortConfig seed = seedFromPreferred(originalPorts);
        awaitSeedPortsSettle(seed);
        PortConflictDetector.DebugPortResolution resolution =
                PortConflictDetector.resolveConflictsWithDebug(seed, seedDebugPort);

        PortConfig rp = resolution.getResolvedConfig();
        claimAndTrack(rp, registry, configName, resolution.getChanges());

        int preClaimDebug = resolution.getDebugPort();
        int resolvedDebugPort = registry.claimPort(preClaimDebug, configName);
        if (resolvedDebugPort == -1) {
            resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                    + ": all ports in search range exhausted; debugger may fail to attach");
            resolvedDebugPort = preClaimDebug; // keep original; JVM will fail with a clear error
        } else if (resolvedDebugPort != preClaimDebug) {
            resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                    + " claimed by a concurrent instance, resolved to " + resolvedDebugPort);
        }

        logResolutionChanges(resolution.getChanges());
        // Strategy gate AFTER claimAndTrack and after the JDWP claim so a
        // registry-mediated bump on HTTP/shutdown is caught. Compare against the
        // preferred seed (intent) so a heal back toward preferred isn't a "bump".
        // Debug-port bumps are accepted under STRICT (intent is HTTP/shutdown).
        enforcePortStrategy(seed, rp);
        writeBackResolvedPorts(configuration, rp);
        writeBackResolvedDebugPort(configuration, resolvedDebugPort);
        return new Resolution(rp, resolvedDebugPort);
    }

    @NotNull
    private Resolution claimForRun(@NotNull TomcatPortRegistry registry,
                                   @NotNull String configName,
                                   @NotNull PortConfig originalPorts) {
        logActiveStrategy(originalPorts);
        // Seed from the user's PREFERRED ports (intent), NOT the possibly-bumped
        // current values. Otherwise an earlier auto-bump that writeback persisted
        // (8080→8081) would seed the next launch from 8081 and climb again every
        // launch; seeding from preferred lets the port heal back to 8080 once the
        // conflict clears.
        PortConfig seed = seedFromPreferred(originalPorts);
        // Give this config's just-stopped JVM a brief moment to release its socket
        // so a fast Stop→Run reclaims the same port instead of bumping it once.
        awaitSeedPortsSettle(seed);
        PortConflictDetector.PortResolution resolution =
                PortConflictDetector.resolveConflicts(seed);

        PortConfig rp = resolution.getResolvedConfig();
        claimAndTrack(rp, registry, configName, resolution.getChanges());
        logResolutionChanges(resolution.getChanges());
        // Strategy gate AFTER claimAndTrack so a registry-mediated bump is caught.
        // Compare against the preferred seed (intent): a heal back toward preferred
        // is not a "bump", but a genuine move off the preferred port still is.
        enforcePortStrategy(seed, rp);
        writeBackResolvedPorts(configuration, rp);
        return new Resolution(rp, -1);
    }

    /**
     * Builds the resolution seed from the user's PREFERRED ports (their intent),
     * not the possibly-bumped current values. {@link PortConfig} snapshots intent
     * into {@code preferredHttp}/{@code preferredShutdown} the first time
     * {@code setHttpResolved}/{@code setShutdownResolved} bumps a port, and
     * {@code getPreferredHttp()}/{@code getPreferredShutdown()} fall back to the
     * current value when there is no snapshot. Seeding from these lets a port that
     * was bumped on a transient conflict heal back down once the conflict clears,
     * instead of ratcheting upward on every launch.
     */
    @NotNull
    static PortConfig seedFromPreferred(@NotNull PortConfig current) {
        PortConfig seed = current.clone();
        seed.setHttp(current.getPreferredHttp());
        seed.setShutdown(current.getPreferredShutdown());
        return seed;
    }

    /**
     * Briefly waits for the primary seed ports (HTTP + shutdown) to become
     * bindable before conflict detection runs, covering the Stop→fast-Run race
     * where this config's just-stopped JVM hasn't fully released its listen socket
     * yet. Without the wait, detection would see the port busy and bump it once
     * (it would heal on the next launch via {@link #seedFromPreferred}, but the
     * user would still see a transient port jump). Bounded
     * ({@value #SEED_SETTLE_ATTEMPTS}×{@value #SEED_SETTLE_INTERVAL_MS}ms), pays
     * only when a seed port actually probes busy, and never blocks the EDT.
     */
    private void awaitSeedPortsSettle(@NotNull PortConfig seed) {
        // Never sleep on the EDT — the launch path is off-EDT, but guard anyway.
        if (ApplicationManager.getApplication().isDispatchThread()) return;
        for (int attempt = 0; attempt < SEED_SETTLE_ATTEMPTS; attempt++) {
            if (PortConflictDetector.isPortAvailable(seed.getHttp())
                    && PortConflictDetector.isPortAvailable(seed.getShutdown())) {
                return;
            }
            try {
                Thread.sleep(SEED_SETTLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void logActiveStrategy(@NotNull PortConfig seed) {
        com.dev.idea.plugins.tomcat.model.PortStrategy s = seed.getStrategy();
        deploymentLogger.logServerInfo("Port strategy: " + s
                + " (preferred HTTP " + seed.getPreferredHttp()
                + ", current " + seed.getHttp() + ")");
    }

    // Strategy enforcement
    private void enforcePortStrategy(@NotNull PortConfig seed, @NotNull PortConfig resolved) {
        String refusal = evaluatePortStrategy(seed, resolved);
        if (refusal == null) {
            com.dev.idea.plugins.tomcat.model.PortStrategy s = seed.getStrategy();
            if (s != com.dev.idea.plugins.tomcat.model.PortStrategy.AUTO_BUMP) {
                deploymentLogger.logServerInfo("Port strategy " + s + ": preferred ports free, allowing launch.");
            }
            return;
        }
        // Surface to the run console AND throw — the console line is more
        // visible than the wrapped ExecutionException dialog alone.
        // Use the plugin-error channel so idea.log doesn't mislabel this as
        // "Tomcat error:" — the strategy refusal is a pre-launch decision we
        // made, not anything Tomcat reported.
        deploymentLogger.logPluginError(refusal);
        // Short balloon for STRICT refusal — the run console has the full message.
        com.dev.idea.plugins.tomcat.utils.TomcatNotifier.error(
                configuration.getProject(),
                "Port busy (" + seed.getStrategy() + ")",
                "See run console for details.");
        throw new IllegalStateException(refusal);
    }

    /**
     * Pure strategy logic — package-private for testability.
     * Returns {@code null} when launch should proceed, or the refusal message
     * when a non-AUTO_BUMP strategy detects an HTTP/shutdown bump between
     * {@code seed} (user's intent) and {@code resolved} (post-detector
     * post-registry final values).
     */
    @org.jetbrains.annotations.Nullable
    static String evaluatePortStrategy(@NotNull PortConfig seed, @NotNull PortConfig resolved) {
        com.dev.idea.plugins.tomcat.model.PortStrategy s = seed.getStrategy();
        if (s == com.dev.idea.plugins.tomcat.model.PortStrategy.AUTO_BUMP) return null;
        boolean httpBumped = seed.getHttp() != resolved.getHttp();
        boolean shutdownBumped = seed.getShutdown() != resolved.getShutdown();
        if (!httpBumped && !shutdownBumped) return null;
        StringBuilder msg = new StringBuilder("Port conflict refused by ").append(s).append(" strategy: ");
        if (httpBumped)     msg.append("HTTP ").append(seed.getHttp()).append(" is busy; ");
        if (shutdownBumped) msg.append("shutdown ").append(seed.getShutdown()).append(" is busy; ");
        msg.append("change ports in the Server tab, free the occupier, or switch to Auto-bump.");
        return msg.toString();
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
            changes.add(label + " port " + port + ": all ports in search range exhausted; Tomcat may fail to bind");
            return fallback;
        }
        if (claimed != port) {
            changes.add(label + " port " + port + " claimed by a concurrent instance, resolved to " + claimed);
        }
        return claimed;
    }

    private void logResolutionChanges(@NotNull List<String> changes) {
        if (changes.isEmpty()) return;
        // Console-only — a balloon used to fire here on every launch where a
        // port had to be bumped (very common during active development with
        // multiple Tomcats running). The deployment-logger lines stay so the
        // user can still see the resolution in the run console, but the
        // popup repetition is gone.
        deploymentLogger.logServerWarning("Port conflicts detected and auto-resolved:");
        for (String change : changes) {
            deploymentLogger.logServerWarning("  " + change);
        }
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
        // Capture previous values BEFORE assigning the new ones so the browser
        // URL rewrite can match the URL's stored port against the old value and
        // only mutate URLs that were genuinely pointing at this Tomcat. Without
        // the previous-port match, a https://localhost:8443/foo URL would be
        // rewritten the moment the HTTP port changed — wrong scheme, wrong
        // port. Mismatched scheme + previous-port check makes the rewrite safe
        // regardless of which connectors auto-resolved.
        int previousHttp = target.getHttp();
        int previousHttps = target.getHttps();
        boolean changed = false;
        if (previousHttp != resolved.getHttp()) {
            // setHttpResolved snapshots intent before overwriting
            target.setHttpResolved(resolved.getHttp());
            rewriteStoredBrowserUrlForPortChange(configuration, "http",
                    previousHttp, resolved.getHttp());
            changed = true;
        }
        if (target.getShutdown() != resolved.getShutdown()) {
            target.setShutdownResolved(resolved.getShutdown());
            changed = true;
        }
        if (target.isHttpsEnabled() && previousHttps != resolved.getHttps()) {
            target.setHttps(resolved.getHttps());
            rewriteStoredBrowserUrlForPortChange(configuration, "https",
                    previousHttps, resolved.getHttps());
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
     * Rewrites the port in the stored browser URL when ALL of the following hold:
     * the URL scheme matches {@code scheme} (case-insensitive), the host is a
     * loopback name (localhost, 127.0.0.1, ::1), and the URL's current port
     * equals {@code previousPort}. The match on previousPort is what keeps the
     * rewrite safe across mixed HTTP / HTTPS configurations — without it, a
     * stored {@code https://localhost:8443/foo} URL would be silently rewritten
     * the moment the HTTP port shifted.
     *
     * <p>A user-customised URL against a proxy / CDN / port-forward keeps its
     * deliberately-chosen port because either the scheme, host or port will
     * fail the gate. Empty stored URL (auto-managed) is a no-op — the
     * recomputation path already derives the URL from the live port.
     *
     * <p>Route through {@link TomcatRunConfiguration#setBrowserUrl} (not
     * {@code BrowserConfig.setBrowserUrl}) so a URL that now matches
     * {@code autoBrowserUrl()} after the rewrite is normalised back to the
     * empty stored form. Preserves the single-source-of-truth invariant —
     * later reads of {@code getBrowserUrl()} recompute from the live port.
     */
    static void rewriteStoredBrowserUrlForPortChange(@NotNull TomcatRunConfiguration configuration,
                                                     @NotNull String scheme,
                                                     int previousPort,
                                                     int newPort) {
        if (previousPort == newPort || previousPort <= 0 || newPort <= 0) return;
        String stored = configuration.getConfigData().getBrowserConfig().getUrl();
        if (stored == null || stored.isEmpty()) return;
        try {
            java.net.URI uri = java.net.URI.create(stored.trim());
            if (!scheme.equalsIgnoreCase(uri.getScheme())) return;
            if (!TomcatProcessHandler.isLoopbackHost(uri.getHost())) return;
            if (uri.getPort() != previousPort) return;
            java.net.URI rewritten = new java.net.URI(uri.getScheme(), uri.getUserInfo(),
                    uri.getHost(), newPort,
                    uri.getPath(), uri.getQuery(), uri.getFragment());
            configuration.setBrowserUrl(rewritten.toString());
        } catch (Throwable ignored) {
            // Malformed URL — leave it alone; the user can fix it manually.
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
