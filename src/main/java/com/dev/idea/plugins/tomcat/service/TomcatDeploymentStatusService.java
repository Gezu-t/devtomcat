package com.dev.idea.plugins.tomcat.service;

import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-level service that tracks live deployment status for each Tomcat run configuration.
 * Updated by {@link com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler} as it detects
 * lifecycle events; read by {@link TomcatRunDashboardCustomizer} and {@link TomcatDeploymentNode}
 * to display real-time status in the Services tool window.
 */
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
        private volatile ServerState serverState = ServerState.STOPPED;
        private final Map<String, ArtifactState> artifactStates = new ConcurrentHashMap<>();
        private volatile int errorCount;
        private volatile int warningCount;
        private volatile long startupTimeMs;

        public ServerState getServerState() { return serverState; }
        public Map<String, ArtifactState> getArtifactStates() { return artifactStates; }
        public int getErrorCount() { return errorCount; }
        public int getWarningCount() { return warningCount; }
        public long getStartupTimeMs() { return startupTimeMs; }
    }

    private final Project project;
    private final Map<String, ConfigStatus> statuses = new ConcurrentHashMap<>();

    public TomcatDeploymentStatusService(@NotNull Project project) {
        this.project = project;
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
        s.serverState = ServerState.STARTING;
        s.errorCount = 0;
        s.warningCount = 0;
        s.startupTimeMs = 0;
        s.artifactStates.clear();
        refreshDashboard();
    }

    public void onArtifactDeploying(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        s.serverState = ServerState.DEPLOYING;
        s.artifactStates.put(artifactName, ArtifactState.DEPLOYING);
        refreshDashboard();
    }

    public void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        s.artifactStates.put(artifactName, ArtifactState.DEPLOYED);
        refreshDashboard();
    }

    public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        s.artifactStates.put(artifactName, ArtifactState.FAILED);
        refreshDashboard();
    }

    public void onArtifactReloading(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        s.artifactStates.put(artifactName, ArtifactState.RELOADING);
        refreshDashboard();
    }

    public void onServerStarted(@NotNull String configName, long startupTimeMs) {
        ConfigStatus s = getOrCreate(configName);
        s.serverState = ServerState.RUNNING;
        s.startupTimeMs = startupTimeMs;
        refreshDashboard();
    }

    public void onError(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        s.errorCount++;
        // Don't override RUNNING with FAILED for transient errors
    }

    public void onWarning(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        s.warningCount++;
    }

    public void onServerStopped(@NotNull String configName, int exitCode) {
        ConfigStatus s = getOrCreate(configName);
        s.serverState = exitCode == 0 ? ServerState.STOPPED : ServerState.FAILED;
        refreshDashboard();
    }

    public void remove(@NotNull String configName) {
        statuses.remove(configName);
    }

    /** Triggers a refresh of the Services/Run Dashboard tree so status changes appear. */
    private void refreshDashboard() {
        if (!project.isDisposed()) {
            RunDashboardManager.getInstance(project).updateDashboard(true);
        }
    }
}
