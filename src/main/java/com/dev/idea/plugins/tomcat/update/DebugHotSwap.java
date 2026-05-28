package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.debugger.DebuggerManager;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.HotSwapStatusListener;
import com.intellij.debugger.ui.HotSwapUI;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Applies a code change to a Tomcat that is running under the IDE debugger by
 * redefining the changed classes in the live JVM (HotSwap), instead of forcing
 * a full context undeploy/redeploy.
 *
 * <h2>Why</h2>
 * DevTomcat launches Debug via {@code GenericDebuggerRunner.attachVirtualMachine},
 * so a Tomcat debug launch is a standard {@link DebuggerSession}. The JVM can
 * redefine the body of an existing method in place — no new classloader, no app
 * re-initialization, no lost HTTP sessions or in-memory caches, and it is
 * near-instant. The previous behaviour touched the context descriptor on every
 * "Update Classes and Resources", which threw all of that away even when a live
 * redefine would have sufficed.
 *
 * <h2>What can and cannot be redefined</h2>
 * The standard JVM redefines <em>method-body</em> changes only. A structural
 * change — adding/removing a method or field, changing a signature, the class
 * hierarchy, or modifiers — cannot be hot-redefined and needs a fresh
 * classloader (i.e. the context restart). This class detects that case
 * automatically via the platform's hot-swap result and runs the caller's
 * restart fallback, so a change is <strong>never silently dropped</strong>.
 * Enhanced runtimes (e.g. the JetBrains Runtime with extended redefinition)
 * transparently widen what redefines successfully; the same restart fallback
 * still covers whatever they cannot.
 *
 * <h2>Relationship to the file sync</h2>
 * Hot-swap operates on the classes the webapp has already <em>loaded</em>,
 * matching them by name to freshly-compiled bytecode in the module's compile
 * output. It is orthogonal to {@link DeployedClassesSync} (which keeps the
 * deployed {@code WEB-INF/classes/} consistent for not-yet-loaded classes and
 * for a future cold start): the caller still runs the sync, then redefines the
 * loaded classes on top.
 */
public final class DebugHotSwap {

    private static final Logger LOG = Logger.getInstance(DebugHotSwap.class);

    private DebugHotSwap() {}

    /** Verdict reported by the platform hot-swap, mapped from {@link HotSwapStatusListener}. */
    enum Outcome { SUCCESS, NOTHING_TO_RELOAD, FAILURE, CANCELLED }

    /** What the caller must do after the hot-swap attempt. */
    enum FollowUp { NONE, RESTART, WARN_NOT_APPLIED }

    /**
     * Pure decision — given the hot-swap {@code outcome} and whether the
     * deployed classpath actually changed this round, decide whether a full
     * context restart is still required. Package-visible and side-effect-free
     * so it can be unit-tested without a live JVM or debug session.
     *
     * <ul>
     *   <li>{@code SUCCESS} — the changed classes were redefined live; nothing
     *       more to do.</li>
     *   <li>{@code NOTHING_TO_RELOAD} — the VM had nothing newer to redefine.
     *       If we nonetheless copied fresh classpath files, the redefine scan
     *       didn't see them (e.g. the session's output roots don't cover this
     *       module), so a restart is the only way to apply them. If nothing was
     *       copied either, there is genuinely nothing to do.</li>
     *   <li>{@code FAILURE} — a structural change or another redefine error;
     *       only a fresh classloader can load the new shape, so restart.</li>
     *   <li>{@code CANCELLED} — the user declined the reload (e.g. the
     *       "JVM may hang" warning). Respect that: don't restart behind their
     *       back, just tell them the change isn't live yet.</li>
     * </ul>
     */
    @NotNull
    static FollowUp decideFollowUp(@NotNull Outcome outcome, boolean classpathChanged) {
        return switch (outcome) {
            case SUCCESS -> FollowUp.NONE;
            case NOTHING_TO_RELOAD -> classpathChanged ? FollowUp.RESTART : FollowUp.NONE;
            case FAILURE -> FollowUp.RESTART;
            case CANCELLED -> FollowUp.WARN_NOT_APPLIED;
        };
    }

    /**
     * Resolves the hot-swappable {@link DebuggerSession} attached to
     * {@code handler}, or {@code null} when the process is not running under a
     * debugger or the VM cannot redefine classes. The {@code null} result is
     * the caller's signal to fall back to the normal context restart.
     */
    @Nullable
    static DebuggerSession findHotSwappableSession(@NotNull Project project,
                                                   @NotNull ProcessHandler handler) {
        try {
            DebugProcess process = DebuggerManager.getInstance(project).getDebugProcess(handler);
            if (process == null) return null;
            DebuggerSession session = DebuggerManagerEx.getInstanceEx(project).getSession(process);
            if (session == null) return null;
            // Mirror of HotSwapUIImpl.canHotSwap: the session must be attached
            // and the VM must support class redefinition (canRedefineClasses).
            if (!session.isAttached() || !session.getProcess().canRedefineClasses()) {
                return null;
            }
            return session;
        } catch (Throwable t) {
            // Debugger APIs live in the Java plugin; if anything about the
            // session lookup throws (disposed process, API drift across the
            // supported platform range), treat it as "no hot-swap" and let the
            // caller restart. Never let a hot-swap probe break Update.
            LOG.debug("Hot reload: could not resolve debug session", t);
            return null;
        }
    }

    /**
     * Redefines the already-compiled changed classes in {@code session}'s live
     * JVM, then applies the follow-up: runs {@code restart} when a full context
     * reload is still required, or logs when the change is live / not applied.
     *
     * <p>The reload is dispatched on the EDT (the platform's own hot-swap action
     * does the same). The status callbacks may arrive on a background thread;
     * the follow-up is file I/O only, so it is safe to run from there.
     *
     * @param classpathChanged whether the deployed classpath gained fresh files
     *                         this round (from {@link DeployedClassesSync}); used
     *                         only as the safety net for {@code NOTHING_TO_RELOAD}
     * @param restart          the caller's context-restart action, run only when
     *                         the decision is {@link FollowUp#RESTART}
     */
    static void reloadThenMaybeRestart(@NotNull Project project,
                                       @NotNull DebuggerSession session,
                                       boolean classpathChanged,
                                       @NotNull TomcatDeploymentLogger logger,
                                       @NotNull Runnable restart) {
        HotSwapStatusListener listener = new HotSwapStatusListener() {
            @Override public void onSuccess(List<DebuggerSession> sessions) { apply(Outcome.SUCCESS); }
            @Override public void onNothingToReload(List<DebuggerSession> sessions) { apply(Outcome.NOTHING_TO_RELOAD); }
            @Override public void onFailure(List<DebuggerSession> sessions) { apply(Outcome.FAILURE); }
            @Override public void onCancel(List<DebuggerSession> sessions) { apply(Outcome.CANCELLED); }

            private void apply(@NotNull Outcome outcome) {
                FollowUp followUp = decideFollowUp(outcome, classpathChanged);
                switch (followUp) {
                    case NONE -> logger.logServerInfo(outcome == Outcome.SUCCESS
                            ? "Hot reload: changed classes redefined live in the running JVM — no restart, session preserved."
                            : "Hot reload: nothing to reload — running JVM already current.");
                    case RESTART -> {
                        logger.logServerInfo(outcome == Outcome.FAILURE
                                ? "Hot reload: change needs a new classloader (structural change) — restarting context to apply it."
                                : "Hot reload: changes were not picked up live — restarting context to apply them.");
                        restart.run();
                    }
                    case WARN_NOT_APPLIED -> logger.logServerWarning(
                            "Hot reload cancelled — changes are NOT live. Use Redeploy or Restart to apply them.");
                }
            }
        };
        ApplicationManager.getApplication().invokeLater(
                () -> {
                    try {
                        HotSwapUI.getInstance(project).reloadChangedClasses(session, false, listener);
                    } catch (Throwable t) {
                        // If the reload trigger itself throws, fall back to a
                        // restart so the user's change is still applied.
                        LOG.debug("Hot reload: reloadChangedClasses threw — falling back to restart", t);
                        logger.logServerWarning("Hot reload unavailable — restarting context to apply changes.");
                        restart.run();
                    }
                },
                ModalityState.nonModal());
    }
}
