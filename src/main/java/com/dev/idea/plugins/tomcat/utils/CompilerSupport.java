package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntConsumer;

/**
 * Single source for the compile-and-then pattern used across update actions.
 *
 * <p>All four update actions (Update Resources, Update Classes and Resources,
 * Redeploy, Restart) follow the same structure:
 * <ol>
 *   <li>Trigger an incremental build via {@link CompilerManager#make}.</li>
 *   <li>On abort — log a warning and stop.</li>
 *   <li>On errors — log an error and stop.</li>
 *   <li>On success — run the action-specific callback with the warning count.</li>
 * </ol>
 *
 * <p>This class owns steps 1-3. The caller provides the messages and step 4.
 */
public final class CompilerSupport {

    private CompilerSupport() {}

    /**
     * Triggers an incremental build and, on success, runs {@code onSuccess}.
     *
     * @param project       the current project
     * @param logger        deployment logger for build feedback
     * @param startMessage  message logged before the build starts (e.g. "Compiling project...")
     * @param abortMessage  warning message logged when the build is aborted
     * @param errorMessage  error message logged when the build has errors (error count appended automatically)
     * @param scope         the modules to build; {@code null} builds the whole project. A scoped
     *                      build still compiles incrementally and produces the same outputs for the
     *                      modules it covers — it just skips re-checking modules the deployment
     *                      does not depend on (see {@code DeploymentCompileScope})
     * @param onSuccess     callback invoked only when the build completes with zero errors
     *                      and no abort; receives the compiler warning count so the caller
     *                      can include it in its own success message. <strong>Runs on a
     *                      background thread</strong>, not the EDT — see the threading note
     *                      below.
     *
     * <h2>Threading</h2>
     * The platform fires the compile callback on the EDT. The success step mirrors
     * freshly-compiled output into the deployed artifacts — a recursive file-tree
     * copy that runs for many seconds on a large webapp, and is markedly slower on
     * Windows where each {@code Files.copy} re-applies NTFS security attributes.
     * Running that on the EDT freezes the whole IDE. So {@code onSuccess} is handed
     * to a background {@link Task.Backgroundable} instead. Every success step is
     * background-safe: file work uses raw {@code java.nio.file} (no VFS write
     * action), project-model reads go through a blocking read action, and the
     * EDT-bound tails (hot-swap reload, process stop/relaunch) marshal themselves
     * back onto the EDT. Callers must keep {@code onSuccess} background-safe.
     */
    public static void compileAndThen(@NotNull Project project,
                                      @NotNull TomcatDeploymentLogger logger,
                                      @NotNull String startMessage,
                                      @NotNull String abortMessage,
                                      @NotNull String errorMessage,
                                      @Nullable CompileScope scope,
                                      @NotNull IntConsumer onSuccess) {
        logger.logServerInfo(startMessage);
        long started = System.nanoTime();
        CompileStatusNotification callback = (aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning(abortMessage);
                return;
            }
            if (errors > 0) {
                logger.logServerError(errorMessage + " with " + errors + " error(s)");
                return;
            }
            // The sync passes that follow log their own times; with this line the
            // whole update is accounted for.
            logger.logServerInfo("Compile finished in "
                    + String.format("%,d", (System.nanoTime() - started) / 1_000_000) + " ms");
            runSuccessOffEdt(project, warnings, onSuccess);
        };
        CompilerManager compiler = CompilerManager.getInstance(project);
        if (scope != null) {
            compiler.make(scope, callback);
        } else {
            compiler.make(callback);
        }
    }

    /**
     * Runs {@code onSuccess} on a background thread so the post-compile file
     * mirror never blocks the EDT (the thread the compile callback arrives on).
     * Under unit-test mode {@link Task.Backgroundable#queue()} runs synchronously,
     * preserving deterministic test behaviour.
     */
    private static void runSuccessOffEdt(@NotNull Project project,
                                         int warnings,
                                         @NotNull IntConsumer onSuccess) {
        new Task.Backgroundable(project, "DevTomcat: applying changes", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                onSuccess.accept(warnings);
            }
        }.queue();
    }
}
