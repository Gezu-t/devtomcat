package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle events emitted during a Tomcat server session.
 *
 * <p>Consumers register as listeners so that concerns like dashboard status,
 * deployment history, and startup-time tracking are decoupled from
 * {@link TomcatProcessHandler} and {@link TomcatOutputPipeline}.
 *
 * @see #composite(List)
 * @see #statusConsumer(TomcatDeploymentStatusService)
 * @see #historyConsumer(TomcatDeploymentHistory)
 * @see #startupTimeConsumer(StartupTimeTracker, TomcatOutputPipeline.PipelineLogger)
 */
public interface TomcatLifecycleListener {

    default void onServerStarting(@NotNull String configName) {}

    default void onServerStarted(@NotNull String configName, long startupTimeMs) {}

    default void onServerStopped(@NotNull String configName, int exitCode,
                                  long durationMs, int errorCount,
                                  int warningCount, long startupTimeMs) {}

    default void onArtifactDeploying(@NotNull String configName, @NotNull String artifactName) {}

    default void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {}

    default void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {}

    /**
     * Deployment of a specific artifact was cancelled by the user.
     * The artifact should no longer appear in-progress, but this is not a
     * deployment failure and must not be counted as one.
     */
    default void onArtifactCancelled(@NotNull String configName, @NotNull String artifactName) {}

    /**
     * Tomcat emitted a server-level deployment-summary failure such as
     * "One or more Contexts did not start successfully" — something failed but
     * the per-artifact pattern didn't identify which one. Listeners should
     * treat this as "at least one artifact failed" and mark the server state
     * as FAILED so the Services panel doesn't render green when the deployment
     * is actually broken.
     */
    default void onDeploymentSummaryFailed(@NotNull String configName) {}

    default void onArtifactReloading(@NotNull String configName, @NotNull String artifactName) {}

    default void onError(@NotNull String configName) {}

    default void onWarning(@NotNull String configName) {}

    // --- Composite ---

    /**
     * Fans out every event to all listeners in order. A throwing listener is logged
     * and skipped so peer consumers (status, history, startup tracker) stay in sync.
     */
    @NotNull
    static TomcatLifecycleListener composite(@NotNull List<TomcatLifecycleListener> listeners) {
        List<TomcatLifecycleListener> copy = List.copyOf(listeners);
        Logger log = Logger.getInstance(TomcatLifecycleListener.class);
        return new TomcatLifecycleListener() {
            private void dispatch(@NotNull String event, @NotNull java.util.function.Consumer<TomcatLifecycleListener> call) {
                for (var l : copy) {
                    try { call.accept(l); }
                    catch (Throwable t) { log.warn("Lifecycle listener " + l.getClass().getName() + " threw on " + event, t); }
                }
            }
            @Override public void onServerStarting(@NotNull String c) { dispatch("onServerStarting", l -> l.onServerStarting(c)); }
            @Override public void onServerStarted(@NotNull String c, long t) { dispatch("onServerStarted", l -> l.onServerStarted(c, t)); }
            @Override public void onServerStopped(@NotNull String c, int ex, long d, int e, int w, long s) { dispatch("onServerStopped", l -> l.onServerStopped(c, ex, d, e, w, s)); }
            @Override public void onArtifactDeploying(@NotNull String c, @NotNull String a) { dispatch("onArtifactDeploying", l -> l.onArtifactDeploying(c, a)); }
            @Override public void onArtifactDeployed(@NotNull String c, @NotNull String a) { dispatch("onArtifactDeployed", l -> l.onArtifactDeployed(c, a)); }
            @Override public void onArtifactFailed(@NotNull String c, @NotNull String a) { dispatch("onArtifactFailed", l -> l.onArtifactFailed(c, a)); }
            @Override public void onArtifactCancelled(@NotNull String c, @NotNull String a) { dispatch("onArtifactCancelled", l -> l.onArtifactCancelled(c, a)); }
            @Override public void onDeploymentSummaryFailed(@NotNull String c) { dispatch("onDeploymentSummaryFailed", l -> l.onDeploymentSummaryFailed(c)); }
            @Override public void onArtifactReloading(@NotNull String c, @NotNull String a) { dispatch("onArtifactReloading", l -> l.onArtifactReloading(c, a)); }
            @Override public void onError(@NotNull String c) { dispatch("onError", l -> l.onError(c)); }
            @Override public void onWarning(@NotNull String c) { dispatch("onWarning", l -> l.onWarning(c)); }
        };
    }

    // --- Built-in consumers ---

    /** Adapts {@link TomcatDeploymentStatusService} as a lifecycle consumer. */
    @NotNull
    static TomcatLifecycleListener statusConsumer(@NotNull TomcatDeploymentStatusService service) {
        return statusConsumer(service, null);
    }

    /**
     * Adapts {@link TomcatDeploymentStatusService} as a lifecycle consumer.
     * When {@code project} is non-null and the configuration was deleted while
     * its server was still running, the stop event drops the live status
     * instead of resurrecting a {@link TomcatDeploymentStatusService.ConfigStatus}
     * for a name the cleanup listener already purged.
     */
    @NotNull
    static TomcatLifecycleListener statusConsumer(@NotNull TomcatDeploymentStatusService service,
                                                  @Nullable Project project) {
        return new TomcatLifecycleListener() {
            @Override public void onServerStarting(@NotNull String c) { service.onServerStarting(c); }
            @Override public void onServerStarted(@NotNull String c, long t) { service.onServerStarted(c, t); }
            @Override public void onServerStopped(@NotNull String c, int ex, long d, int e, int w, long s) {
                if (project != null && !configurationStillExists(project, c)) {
                    service.remove(c);
                } else {
                    service.onServerStopped(c, ex);
                }
            }
            @Override public void onArtifactDeploying(@NotNull String c, @NotNull String a) { service.onArtifactDeploying(c, a); }
            @Override public void onArtifactDeployed(@NotNull String c, @NotNull String a) { service.onArtifactDeployed(c, a); }
            @Override public void onArtifactFailed(@NotNull String c, @NotNull String a) { service.onArtifactFailed(c, a); }
            @Override public void onArtifactCancelled(@NotNull String c, @NotNull String a) { service.onArtifactCancelled(c, a); }
            @Override public void onDeploymentSummaryFailed(@NotNull String c) { service.onDeploymentSummaryFailed(c); }
            @Override public void onArtifactReloading(@NotNull String c, @NotNull String a) { service.onArtifactReloading(c, a); }
            @Override public void onError(@NotNull String c) { service.onError(c); }
            @Override public void onWarning(@NotNull String c) { service.onWarning(c); }
        };
    }

    /** Adapts {@link TomcatDeploymentHistory} as a lifecycle consumer. */
    @NotNull
    static TomcatLifecycleListener historyConsumer(@NotNull TomcatDeploymentHistory service) {
        return historyConsumer(service, null);
    }

    /**
     * Adapts {@link TomcatDeploymentHistory} as a lifecycle consumer. When
     * {@code project} is non-null and the configuration was deleted while its
     * server was still running, the completed in-flight entry is dropped
     * instead of being recorded — otherwise {@code recordCompleted} would
     * resurrect a persisted history entry for a configuration that
     * {@code removeEntriesFor} already purged.
     */
    @NotNull
    static TomcatLifecycleListener historyConsumer(@NotNull TomcatDeploymentHistory service,
                                                   @Nullable Project project) {
        return new TomcatLifecycleListener() {
            private volatile TomcatDeploymentHistory.HistoryEntry entry;
            private final Object entryLock = new Object();

            @Override
            public void onServerStarting(@NotNull String configName) {
                synchronized (entryLock) {
                    entry = service.startEntry(configName);
                }
            }

            @Override
            public void onArtifactDeploying(@NotNull String configName, @NotNull String artifactName) {
                synchronized (entryLock) {
                    TomcatDeploymentHistory.HistoryEntry e = entry;
                    if (e != null && !e.artifactNames.contains(artifactName)) {
                        e.artifactNames.add(artifactName);
                    }
                }
            }

            @Override
            public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                synchronized (entryLock) {
                    TomcatDeploymentHistory.HistoryEntry e = entry;
                    if (e != null) {
                        if (!e.artifactNames.contains(artifactName)) {
                            e.artifactNames.add(artifactName);
                        }
                        e.artifactFailure = true;
                    }
                }
            }

            @Override
            public void onDeploymentSummaryFailed(@NotNull String configName) {
                synchronized (entryLock) {
                    TomcatDeploymentHistory.HistoryEntry e = entry;
                    if (e != null) {
                        e.artifactFailure = true;
                    }
                }
            }

            @Override
            public void onServerStopped(@NotNull String configName, int exitCode,
                                         long durationMs, int errorCount,
                                         int warningCount, long startupTimeMs) {
                synchronized (entryLock) {
                    TomcatDeploymentHistory.HistoryEntry e = entry;
                    if (e != null) {
                        e.durationMs = durationMs;
                        e.exitCode = exitCode;
                        e.success = exitCode == 0 && !e.artifactFailure;
                        e.errorCount = errorCount;
                        e.warningCount = warningCount;
                        e.startupTimeMs = startupTimeMs;
                        // Skip recording if the configuration was deleted while
                        // this server was still running — removeEntriesFor
                        // already purged its history and recording now would
                        // resurrect a stale entry for a config that is gone.
                        if (project == null || configurationStillExists(project, configName)) {
                            service.recordCompleted(e);
                        }
                        entry = null;
                    }
                }
            }
        };
    }

    /**
     * Adapts {@link StartupTimeTracker} as a lifecycle consumer.
     * Records startup time and logs a trend comparison.
     */
    @NotNull
    static TomcatLifecycleListener startupTimeConsumer(@NotNull StartupTimeTracker tracker,
                                                       @NotNull TomcatOutputPipeline.PipelineLogger logger) {
        Logger log = Logger.getInstance(TomcatLifecycleListener.class);
        return new TomcatLifecycleListener() {
            // A startup-time sample is only representative when the server came up
            // clean. Any failure — a per-artifact failure or Tomcat's server-level
            // deployment-summary failure — means the start may have short-circuited
            // part of its sequence, so its self-reported "Server startup in N ms"
            // no longer describes a real cold boot and must not pollute the trend
            // (best/average/comparison). These callbacks and onServerStarted all
            // arrive on the single output-reader thread in stream order, with
            // failures logged before the final startup line, so the flag is always
            // set before the start is observed; volatile guards the rare
            // cross-thread read.
            private volatile boolean startupHadFailure = false;

            @Override
            public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                startupHadFailure = true;
            }

            @Override
            public void onDeploymentSummaryFailed(@NotNull String configName) {
                startupHadFailure = true;
            }

            @Override
            public void onServerStarted(@NotNull String configName, long startupTimeMs) {
                if (startupHadFailure) {
                    log.debug("Not recording startup-time sample for '" + configName
                            + "': startup reported one or more deployment failures.");
                    return;
                }
                try {
                    // Only surface a trend comparison when the sample was actually
                    // recorded — a value the tracker rejects as implausible must not
                    // drive a misleading "faster/slower than last run" line.
                    if (tracker.recordStartupTime(configName, startupTimeMs)) {
                        String comparison = tracker.formatComparison(configName, startupTimeMs);
                        if (!comparison.isEmpty()) {
                            logger.logServerInfo("Startup trend: " + comparison);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to track startup time: " + e.getMessage());
                }
            }
        };
    }

    // --- Factory ---

    /**
     * Assembles the composite lifecycle listener from available project services.
     * Each consumer is independent — if a service is unavailable, it is simply omitted.
     */
    @NotNull
    static TomcatLifecycleListener forConfiguration(@NotNull TomcatRunConfiguration configuration,
                                                     @NotNull TomcatOutputPipeline.PipelineLogger pipelineLogger) {
        boolean projectAvailable = !configuration.getProject().isDisposed();
        List<TomcatLifecycleListener> consumers = new ArrayList<>();

        if (projectAvailable) {
            Project project = configuration.getProject();

            TomcatDeploymentStatusService statusService =
                    TomcatDeploymentStatusService.getInstance(project);
            if (statusService != null) {
                consumers.add(statusConsumer(statusService, project));
            }

            TomcatDeploymentHistory historyService =
                    TomcatDeploymentHistory.getInstance(project);
            if (historyService != null) {
                consumers.add(historyConsumer(historyService, project));
            }

            StartupTimeTracker tracker = StartupTimeTracker.getInstance(configuration.getProject());
            if (tracker != null) {
                consumers.add(startupTimeConsumer(tracker, pipelineLogger));
            }
        }

        return composite(consumers);
    }

    /**
     * Whether a Tomcat run configuration with the given name still exists in the
     * project. Used by the status/history consumers to avoid re-recording state
     * for a configuration that was deleted while its server was still running.
     * Errs toward {@code true} if RunManager cannot be queried, so a transient
     * lookup failure never silently drops a legitimate record.
     */
    static boolean configurationStillExists(@NotNull Project project, @NotNull String configName) {
        if (project.isDisposed()) {
            return false;
        }
        try {
            for (RunnerAndConfigurationSettings settings : RunManager.getInstance(project).getAllSettings()) {
                if (configName.equals(settings.getName())
                        && settings.getConfiguration() instanceof TomcatRunConfiguration) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }
}
