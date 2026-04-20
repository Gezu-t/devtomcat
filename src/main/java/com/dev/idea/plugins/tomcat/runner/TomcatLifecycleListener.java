package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

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

    /** Fans out every event to all listeners in order. */
    @NotNull
    static TomcatLifecycleListener composite(@NotNull List<TomcatLifecycleListener> listeners) {
        List<TomcatLifecycleListener> copy = List.copyOf(listeners);
        return new TomcatLifecycleListener() {
            @Override public void onServerStarting(@NotNull String c) { for (var l : copy) l.onServerStarting(c); }
            @Override public void onServerStarted(@NotNull String c, long t) { for (var l : copy) l.onServerStarted(c, t); }
            @Override public void onServerStopped(@NotNull String c, int ex, long d, int e, int w, long s) { for (var l : copy) l.onServerStopped(c, ex, d, e, w, s); }
            @Override public void onArtifactDeploying(@NotNull String c, @NotNull String a) { for (var l : copy) l.onArtifactDeploying(c, a); }
            @Override public void onArtifactDeployed(@NotNull String c, @NotNull String a) { for (var l : copy) l.onArtifactDeployed(c, a); }
            @Override public void onArtifactFailed(@NotNull String c, @NotNull String a) { for (var l : copy) l.onArtifactFailed(c, a); }
            @Override public void onDeploymentSummaryFailed(@NotNull String c) { for (var l : copy) l.onDeploymentSummaryFailed(c); }
            @Override public void onArtifactReloading(@NotNull String c, @NotNull String a) { for (var l : copy) l.onArtifactReloading(c, a); }
            @Override public void onError(@NotNull String c) { for (var l : copy) l.onError(c); }
            @Override public void onWarning(@NotNull String c) { for (var l : copy) l.onWarning(c); }
        };
    }

    // --- Built-in consumers ---

    /** Adapts {@link TomcatDeploymentStatusService} as a lifecycle consumer. */
    @NotNull
    static TomcatLifecycleListener statusConsumer(@NotNull TomcatDeploymentStatusService service) {
        return new TomcatLifecycleListener() {
            @Override public void onServerStarting(@NotNull String c) { service.onServerStarting(c); }
            @Override public void onServerStarted(@NotNull String c, long t) { service.onServerStarted(c, t); }
            @Override public void onServerStopped(@NotNull String c, int ex, long d, int e, int w, long s) { service.onServerStopped(c, ex); }
            @Override public void onArtifactDeploying(@NotNull String c, @NotNull String a) { service.onArtifactDeploying(c, a); }
            @Override public void onArtifactDeployed(@NotNull String c, @NotNull String a) { service.onArtifactDeployed(c, a); }
            @Override public void onArtifactFailed(@NotNull String c, @NotNull String a) { service.onArtifactFailed(c, a); }
            @Override public void onDeploymentSummaryFailed(@NotNull String c) { service.onDeploymentSummaryFailed(c); }
            @Override public void onArtifactReloading(@NotNull String c, @NotNull String a) { service.onArtifactReloading(c, a); }
            @Override public void onError(@NotNull String c) { service.onError(c); }
            @Override public void onWarning(@NotNull String c) { service.onWarning(c); }
        };
    }

    /** Adapts {@link TomcatDeploymentHistory} as a lifecycle consumer. */
    @NotNull
    static TomcatLifecycleListener historyConsumer(@NotNull TomcatDeploymentHistory service) {
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
                        service.recordCompleted(e);
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
            @Override
            public void onServerStarted(@NotNull String configName, long startupTimeMs) {
                try {
                    tracker.recordStartupTime(configName, startupTimeMs);
                    String comparison = tracker.formatComparison(configName, startupTimeMs);
                    if (!comparison.isEmpty()) {
                        logger.logServerInfo("Startup trend: " + comparison);
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
            TomcatDeploymentStatusService statusService =
                    TomcatDeploymentStatusService.getInstance(configuration.getProject());
            if (statusService != null) {
                consumers.add(statusConsumer(statusService));
            }

            TomcatDeploymentHistory historyService =
                    TomcatDeploymentHistory.getInstance(configuration.getProject());
            if (historyService != null) {
                consumers.add(historyConsumer(historyService));
            }

            StartupTimeTracker tracker = StartupTimeTracker.getInstance(configuration.getProject());
            if (tracker != null) {
                consumers.add(startupTimeConsumer(tracker, pipelineLogger));
            }
        }

        return composite(consumers);
    }
}
