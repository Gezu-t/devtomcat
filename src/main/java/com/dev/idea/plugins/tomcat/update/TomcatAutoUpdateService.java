package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project-level coordinator for the <em>automatic</em> "Update Running
 * Application" triggers — frame deactivation and the opt-in save trigger —
 * routing both through one set of guards so the fast update loop can fire on its
 * own without ever degrading into a wasteful whole-project rebuild or stacking
 * on top of itself.
 *
 * <h2>Why a single service</h2>
 * Manual Update (Ctrl+F10) is a deliberate user gesture and runs unguarded
 * through {@link TomcatApplicationUpdater} directly. The automatic triggers are
 * different: they fire as a side effect of ordinary editing, so they need to be
 * debounced, prevented from stacking on an in-progress compile, and stopped from
 * triggering themselves. Centralising that here keeps the two trigger sites
 * ({@link TomcatFrameDeactivationListener}, {@link TomcatSaveListener}) thin and
 * guarantees they share identical behaviour.
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li><b>Debounce</b> — a save burst (autosave, a multi-file save, or a quick
 *       sequence of saves) collapses into a single sweep via {@link #saveAlarm}.</li>
 *   <li><b>Overlap</b> — {@link #runGuarded} skips when the platform reports an
 *       active compilation, so an automatic trigger never piles a second
 *       {@code make} onto a build already producing fresh output. The
 *       post-compile sync steps are idempotent file copies, so a rare race in
 *       that short tail is harmless.</li>
 *   <li><b>Module-backed</b> — for the hot-sync actions, a configuration whose
 *       deployments resolve to no project module gains nothing from a compile
 *       (the sync skips non-module artifacts), so an automatic trigger would burn
 *       a whole-project {@code make} for no deployable change. {@link #shouldRun}
 *       skips those; redeploy/restart still run because they re-deploy artifacts
 *       regardless of module backing.</li>
 *   <li><b>Save-suppression</b> — an update saves all documents as its first
 *       step; without a guard that save would re-fire {@link TomcatSaveListener}
 *       and schedule another update without end. {@link #isSuppressingSaveTriggers()}
 *       stays {@code true} across the call so the listener ignores the reentrant
 *       save.</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class TomcatAutoUpdateService {

    private static final Logger LOG = Logger.getInstance(TomcatAutoUpdateService.class);

    /** Debounce window for save-triggered sweeps (milliseconds). */
    static final int SAVE_DEBOUNCE_MS = 400;

    private final Project project;
    private final Alarm saveAlarm;

    /**
     * Held {@code true} only for the synchronous window in which an update saves
     * all documents, so the save listener can recognise and ignore that
     * reentrant save instead of scheduling yet another update.
     */
    private final AtomicBoolean suppressingSaves = new AtomicBoolean(false);

    public TomcatAutoUpdateService(@NotNull Project project) {
        this.project = project;
        // Parent the alarm to the project so its queue is disposed with it.
        this.saveAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
    }

    public static TomcatAutoUpdateService getInstance(@NotNull Project project) {
        return project.getService(TomcatAutoUpdateService.class);
    }

    /** @return whether an update is currently saving documents (see class javadoc). */
    public boolean isSuppressingSaveTriggers() {
        return suppressingSaves.get();
    }

    /**
     * Runs {@code action} for {@code handler}, applying every automatic-trigger
     * guard. Used by the frame-deactivation trigger. Always deferred to a later
     * EDT turn so it never blocks the caller (the frame event, or the
     * confirmation dialog's dismissal).
     */
    public void submit(@NotNull TomcatProcessHandler handler, @NotNull String action) {
        ApplicationManager.getApplication().invokeLater(() -> runGuarded(handler, action));
    }

    /**
     * Debounce-schedules a sweep that applies each running configuration's
     * configured Update action. Used by the save trigger; saves within
     * {@link #SAVE_DEBOUNCE_MS} of each other collapse into a single sweep.
     */
    public void scheduleSaveSweep() {
        if (project.isDisposed()) return;
        saveAlarm.cancelAllRequests();
        saveAlarm.addRequest(this::runSaveSweep, SAVE_DEBOUNCE_MS);
    }

    /**
     * Re-evaluates every running server at fire time rather than capturing one
     * handler, so saves spanning multiple servers coalesce into one sweep that
     * updates each server at most once.
     */
    private void runSaveSweep() {
        if (project.isDisposed()) return;
        for (ProcessHandler ph : ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (!(ph instanceof TomcatProcessHandler handler)) continue;
            UpdateConfig uc = handler.getConfiguration().getConfigData().getUpdateConfig();
            if (!uc.isUpdateOnSave()) continue;
            runGuarded(handler, uc.getOnUpdate());
        }
    }

    /**
     * Single choke point for every automatic update: applies the lifecycle,
     * overlap, and module-backed guards, then runs the update with save
     * suppression held across the call.
     *
     * <p>Runs on EDT — {@link #submit} and the alarm callback both arrive there,
     * and the suppression window relies on the update saving documents
     * synchronously on this thread.
     */
    private void runGuarded(@NotNull TomcatProcessHandler handler, @NotNull String action) {
        if (project.isDisposed()) return;
        if (handler.isProcessTerminated() || handler.isProcessTerminating()) return;
        // Passive trigger — honour the shared startup gate silently. The
        // user-gesture surfaces report the reason themselves.
        if (handler.getRestartBlockReason() != null) return;
        if (UpdateConfig.DO_NOTHING.equals(action)) return;

        // Overlap guard: a build is already producing fresh output.
        if (CompilerManager.getInstance(project).isCompilationActive()) return;

        TomcatDeploymentLogger logger = handler.getDeploymentLogger();
        if (!shouldRun(action, hasModuleBackedDeployment(handler))) {
            logger.logServerInfo("Auto-update skipped: no module-backed deployment to compile."
                    + " Use 'Reclaim' (or point the deployment at an exploded module directory)"
                    + " to enable automatic hot updates.");
            return;
        }

        TomcatRunConfiguration config = handler.getConfiguration();
        suppressingSaves.set(true);
        try {
            new TomcatApplicationUpdater(project, handler, config, action).executeUpdate(action);
        } catch (Throwable t) {
            LOG.warn("Automatic update failed for '" + config.getName() + "'", t);
        } finally {
            // executeUpdate has already saved documents synchronously on this EDT
            // turn, so the reentrant-save window is closed; the async compile that
            // follows does not save documents.
            suppressingSaves.set(false);
        }
    }

    /**
     * Whether any valid deployment in {@code handler}'s configuration resolves to
     * a project module. Runs under a read action. Returns {@code true} on any
     * failure so a resolution error never silently suppresses a wanted update.
     */
    private boolean hasModuleBackedDeployment(@NotNull TomcatProcessHandler handler) {
        List<Deployment> deployments = handler.getConfiguration().getDeployments();
        try {
            return ReadAction.compute(() -> {
                for (Deployment d : deployments) {
                    if (d.isValid() && DeploymentModuleResolver.resolve(d, project) != null) {
                        return true;
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            LOG.warn("Could not determine module-backed deployment; allowing update", t);
            return true;
        }
    }

    /** @return whether any running Tomcat in {@code project} has update-on-save enabled. */
    public static boolean hasRunningUpdateOnSaveServer(@NotNull Project project) {
        if (project.isDisposed()) return false;
        for (ProcessHandler ph : ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (ph instanceof TomcatProcessHandler handler
                    && !handler.isProcessTerminated()
                    && handler.getConfiguration().getConfigData().getUpdateConfig().isUpdateOnSave()) {
                return true;
            }
        }
        return false;
    }

    // ---- Pure decision helpers (no platform state — unit-testable) ----

    /**
     * Whether an automatic trigger should proceed for {@code action}, given
     * whether the configuration has a module-backed deployment.
     *
     * <ul>
     *   <li>{@code DO_NOTHING} → never.</li>
     *   <li>hot-sync (update classes / resources) → only when a module backs a
     *       deployment, since the compile + sync is the entire value and yields
     *       nothing otherwise.</li>
     *   <li>redeploy / restart → always, since they re-deploy artifacts
     *       irrespective of module backing.</li>
     * </ul>
     */
    static boolean shouldRun(@NotNull String action, boolean hasModuleBackedDeployment) {
        if (UpdateConfig.DO_NOTHING.equals(action)) return false;
        if (isHotSyncAction(action)) return hasModuleBackedDeployment;
        return true;
    }

    /** Whether {@code action}'s only effect is compile + sync into the deployed app. */
    static boolean isHotSyncAction(@NotNull String action) {
        return UpdateConfig.UPDATE_CLASSES_AND_RESOURCES.equals(action)
                || UpdateConfig.UPDATE_RESOURCES.equals(action);
    }
}
