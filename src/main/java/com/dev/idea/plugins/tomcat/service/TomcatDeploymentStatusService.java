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
import java.util.function.BiFunction;

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
        /**
         * Sticky flag for server-level deployment failures — Tomcat emitted
         * "One or more Contexts did not start successfully" or an equivalent
         * summary, but the per-artifact analyzer couldn't pin down which one.
         * Blocks {@link #restoreRunningStateIfIdle} from clearing FAILED back
         * to RUNNING even when every known artifact state is non-FAILED,
         * because we know something actually failed.
         */
        private volatile boolean deploymentSummaryFailed;

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
    //
    // Invariants enforced by this section:
    //   1. Server state is DERIVED, not SET, except for the terminal STOPPED
    //      state written by onServerStopped. Every handler updates the
    //      per-artifact state, then calls recomputeServerState(s) which is
    //      the only authority for STARTING / DEPLOYING / RUNNING / FAILED.
    //   2. onDeploymentSummaryFailed never touches artifact state. Tomcat
    //      emits this summary while other contexts are still starting up,
    //      so DEPLOYING is not implicit evidence of failure. Per-artifact
    //      analyzer signals and the StartupAnalyzer fallback in the output
    //      pipeline decide which artifacts actually failed.
    //   3. Per-artifact FAILED is sticky within a launch. Launch boundaries
    //      (onServerStarting) are the only place artifact state is reset.

    public void onServerStarting(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.errorCount.set(0);
            s.warningCount.set(0);
            s.startupComplete = false;
            s.startupTimeMs = 0;
            s.artifactStates.clear();
            s.deploymentSummaryFailed = false;
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onArtifactDeploying(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.artifactStates.merge(artifactName, ArtifactState.DEPLOYING, EXISTING_FAILED_WINS);
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            // An artifact that has already been marked FAILED within this launch
            // stays FAILED — a late "has finished" message cannot erase an
            // explicit per-artifact failure signal.
            s.artifactStates.merge(artifactName, ArtifactState.DEPLOYED, EXISTING_FAILED_WINS);
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.artifactStates.put(artifactName, ArtifactState.FAILED);
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onArtifactCancelled(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            // FAILED is sticky for the launch; a cancel does not undo an explicit failure.
            s.artifactStates.merge(artifactName, ArtifactState.PENDING, EXISTING_FAILED_WINS);
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    /**
     * Tomcat emitted a server-level deployment-summary failure
     * ("One or more Contexts did not start successfully" or similar) — at
     * least one artifact failed, possibly one the per-artifact regex did not
     * identify. Flips the server state to FAILED via the sticky
     * {@code deploymentSummaryFailed} flag.
     *
     * <p>This handler intentionally does NOT mutate per-artifact state.
     * Real Tomcat emits the summary line while other contexts are still
     * starting up, so DEPLOYING/RELOADING is not implicit evidence of
     * failure; those artifacts frequently deploy successfully afterward.
     * Per-artifact analyzer signals ({@link #onArtifactFailed}) and the
     * {@code StartupAnalyzer} unresolved-artifact fallback in the output
     * pipeline decide which artifacts actually failed, precisely.
     */
    public void onDeploymentSummaryFailed(@NotNull String configName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.deploymentSummaryFailed = true;
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onArtifactReloading(@NotNull String configName, @NotNull String artifactName) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.artifactStates.merge(artifactName, ArtifactState.RELOADING, EXISTING_FAILED_WINS);
            s.serverState = recomputeServerState(s);
        }
        refreshDashboard();
    }

    public void onServerStarted(@NotNull String configName, long startupTimeMs) {
        ConfigStatus s = getOrCreate(configName);
        synchronized (s.lock) {
            s.startupComplete = true;
            s.startupTimeMs = startupTimeMs;
            s.serverState = recomputeServerState(s);
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

    /**
     * Merge function used by {@link Map#merge} when an event handler wants to
     * update an artifact's state but must not erase an explicit per-artifact
     * failure that already occurred within the current launch.
     *
     * <p>Rule: if the artifact is already {@link ArtifactState#FAILED}, the
     * existing value wins; otherwise the incoming value is written.
     * {@link #onArtifactFailed} is the one handler that bypasses this — an
     * explicit failure signal is authoritative and always writes
     * {@code FAILED} directly via {@code put}.
     */
    private static final BiFunction<ArtifactState, ArtifactState, ArtifactState> EXISTING_FAILED_WINS =
            (existing, incoming) -> existing == ArtifactState.FAILED ? existing : incoming;

    /**
     * Single authority for deriving the {@link ServerState} from a
     * {@link ConfigStatus}'s current facts. Every mutating handler (except
     * {@link #onServerStopped}, which writes terminal states directly)
     * updates the per-artifact state and then calls this method to set
     * {@code serverState}, so the derivation logic lives in one place.
     *
     * <p>Priority order (highest first):
     * <ol>
     *   <li>{@link ServerState#FAILED} — any explicit per-artifact failure
     *       OR the sticky {@code deploymentSummaryFailed} flag is set.
     *       Failure dominates every other observation until
     *       {@link #onServerStarting} resets the launch.</li>
     *   <li>{@link ServerState#DEPLOYING} — at least one artifact is
     *       {@code DEPLOYING} or {@code RELOADING}. The server is mid-launch.</li>
     *   <li>{@link ServerState#RUNNING} — Tomcat reported "Server startup in
     *       N ms" and no artifact is still in-flight and no failure is known.</li>
     *   <li>{@link ServerState#STARTING} — default when a launch is in progress
     *       but no artifact-level work has begun yet.</li>
     * </ol>
     *
     * <p>Caller must hold {@code status.lock}.
     */
    private static @NotNull ServerState recomputeServerState(@NotNull ConfigStatus status) {
        if (status.deploymentSummaryFailed || hasFailedArtifacts(status)) {
            return ServerState.FAILED;
        }
        if (hasInFlightArtifacts(status)) {
            return ServerState.DEPLOYING;
        }
        if (status.startupComplete) {
            return ServerState.RUNNING;
        }
        // Launch still underway. If we've observed any artifact activity we
        // stay on {@code DEPLOYING} rather than regress to {@code STARTING}
        // during the gap between the last artifact's completion line and
        // Tomcat's final "Server startup in N ms" line — the user should
        // never see the label walk backwards.
        return status.artifactStates.isEmpty() ? ServerState.STARTING : ServerState.DEPLOYING;
    }

    private static boolean hasFailedArtifacts(@NotNull ConfigStatus status) {
        return status.artifactStates.values().stream().anyMatch(state -> state == ArtifactState.FAILED);
    }

    private static boolean hasInFlightArtifacts(@NotNull ConfigStatus status) {
        return status.artifactStates.values().stream().anyMatch(
                state -> state == ArtifactState.DEPLOYING || state == ArtifactState.RELOADING);
    }
}
