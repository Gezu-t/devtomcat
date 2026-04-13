package com.dev.idea.plugins.tomcat.service;

import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project-level service that tracks live deployment status for each Tomcat run configuration.
 * Updated by {@link com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler} as it detects
 * lifecycle events; read by {@link TomcatRunDashboardCustomizer} and {@link TomcatDeploymentNode}
 * to display real-time status in the Services tool window.
 */
@Service(Service.Level.PROJECT)
public final class TomcatDeploymentStatusService {

    public enum ServerState {
        STARTING("Starting…"),
        DEPLOYING("Deploying…"),
        RUNNING("Running"),
        FAILED("Failed"),
        STOPPED("Stopped");

        private final String label;

        ServerState(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    public enum ArtifactState {
        PENDING("Pending"),
        DEPLOYING("Deploying…"),
        DEPLOYED("Deployed"),
        FAILED("Failed"),
        RELOADING("Reloading…");

        private final String label;

        ArtifactState(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    /** Snapshot of a configuration's live status. */
    public static final class ConfigStatus {
        /** Synchronizes compound mutations (e.g., clearing all fields in onServerStarting). */
        final Object lock = new Object();
        private volatile ServerState serverState = ServerState.STOPPED;
        private final Map<String, ArtifactState> artifactStates = new ConcurrentHashMap<>();
        private final AtomicInteger errorCount = new AtomicInteger();
        private final AtomicInteger warningCount = new AtomicInteger();
        private volatile boolean startupComplete;
        private volatile long startupTimeMs;

        public ServerState getServerState() { return serverState; }
        public Map<String, ArtifactState> getArtifactStates() { return artifactStates; }
        public int getErrorCount() { return errorCount.get(); }
        public int getWarningCount() { return warningCount.get(); }
        public long getStartupTimeMs() { return startupTimeMs; }
    }

    /** Debounce interval for error/warning counter refreshes (milliseconds). */
    static final int COUNTER_REFRESH_DEBOUNCE_MS = 500;

    @Nullable private final Project project;
    private final Map<String, ConfigStatus> statuses = new ConcurrentHashMap<>();
    /** Debounces rapid error/warning counter updates into a single dashboard refresh. */
    @Nullable private final Alarm counterRefreshAlarm;
    /** Callback for dashboard refresh — injectable for testing. */
    private final Runnable refreshAction;

    public TomcatDeploymentStatusService(@NotNull Project project) {
        this.project = project;
        this.counterRefreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
        this.refreshAction = this::doRefreshDashboard;
    }

    /**
     * Package-private constructor for unit testing without IntelliJ platform dependencies.
     * The refresh callback replaces the real dashboard refresh.
     */
    TomcatDeploymentStatusService(@NotNull Runnable refreshAction) {
        this.project = null;
        this.counterRefreshAlarm = null;
        this.refreshAction = refreshAction;
    }

    public static TomcatDeploymentStatusService getInstance(@NotNull Project project) {
        return project.getService(TomcatDeploymentStatusService.class);
    }

    @NotNull
    private ConfigStatus getOrCreate(@NotNull String configName) {
        return statuses.computeIfAbsent(configName, k -> new ConfigStatus());
    }

    @Nullable
    public ConfigStatus getStatus(@NotNull String configName) {
        return statuses.get(configName);
    }

    // --- State transitions called by TomcatProcessHandler ---

    public void onServerStarting(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.serverState = ServerState.STARTING;
            s.errorCount.set(0);
            s.warningCount.set(0);
            s.startupComplete = false;
            s.startupTimeMs = 0;
            s.artifactStates.clear();
        }
        refreshDashboard();
    }

    public void onArtifactDeploying(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.serverState = ServerState.DEPLOYING;
            s.artifactStates.put(artifactName, ArtifactState.DEPLOYING);
        }
        refreshDashboard();
    }

    public void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            // Don't overwrite FAILED — an artifact that failed deployment stays failed
            s.artifactStates.merge(artifactName, ArtifactState.DEPLOYED,
                    (existing, incoming) -> existing == ArtifactState.FAILED ? existing : incoming);
            restoreRunningStateIfIdle(s);
        }
        refreshDashboard();
    }

    public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.artifactStates.put(artifactName, ArtifactState.FAILED);
            restoreRunningStateIfIdle(s);
        }
        refreshDashboard();
    }

    public void onArtifactReloading(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.serverState = ServerState.DEPLOYING;
            s.artifactStates.put(artifactName, ArtifactState.RELOADING);
        }
        refreshDashboard();
    }

    public void onServerStarted(@NotNull String configName, long startupTimeMs) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.startupComplete = true;
            s.serverState = ServerState.RUNNING;
            s.startupTimeMs = startupTimeMs;
        }
        refreshDashboard();
    }

    public void onError(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        s.errorCount.incrementAndGet();
        // Don't override RUNNING with FAILED for transient errors
        scheduleCounterRefresh();
    }

    public void onWarning(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        s.warningCount.incrementAndGet();
        scheduleCounterRefresh();
    }

    /**
     * Debounces rapid error/warning counter increments into a single dashboard refresh.
     * Resets the 500ms timer on each call so only the trailing edge fires.
     */
    private void scheduleCounterRefresh() {
        if (counterRefreshAlarm != null) {
            counterRefreshAlarm.cancelAllRequests();
            counterRefreshAlarm.addRequest(refreshAction, COUNTER_REFRESH_DEBOUNCE_MS);
        } else {
            // Test mode — invoke immediately
            refreshAction.run();
        }
    }

    public void onServerStopped(@NotNull String configName, int exitCode) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.startupComplete = false;
            s.serverState = exitCode == 0 ? ServerState.STOPPED : ServerState.FAILED;
            if (exitCode == 0) {
                s.artifactStates.clear();
            } else {
                s.artifactStates.entrySet().removeIf(entry -> entry.getValue() != ArtifactState.FAILED);
            }
        }
        refreshDashboard();
    }

    public void remove(@NotNull String configName) {
        statuses.remove(configName);
    }

    /**
     * Migrates live status data from one configuration name to another.
     * Called when a run configuration is renamed so that status data
     * follows the configuration instead of being orphaned.
     */
    public void renameConfiguration(@NotNull String oldName, @NotNull String newName) {
        ConfigStatus status = statuses.remove(oldName);
        if (status != null) {
            statuses.put(newName, status);
        }
    }

    /** Triggers an immediate refresh of the Services/Run Dashboard tree. */
    private void refreshDashboard() {
        refreshAction.run();
    }

    /** Real dashboard refresh — called via {@link #refreshAction} in production. */
    private void doRefreshDashboard() {
        if (project != null && !project.isDisposed()) {
            RunDashboardManager.getInstance(project).updateDashboard(true);
        }
    }

    private static void restoreRunningStateIfIdle(@NotNull ConfigStatus status) {
        if (!status.startupComplete) {
            return;
        }
        boolean deploymentInProgress = status.artifactStates.values().stream().anyMatch(
                state -> state == ArtifactState.DEPLOYING || state == ArtifactState.RELOADING);
        if (!deploymentInProgress) {
            status.serverState = ServerState.RUNNING;
        }
    }
}
