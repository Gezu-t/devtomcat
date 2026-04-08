package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
     * @param onSuccess     callback invoked only when the build completes with zero errors
     *                      and no abort; receives the compiler warning count so the caller
     *                      can include it in its own success message
     */
    public static void compileAndThen(@NotNull Project project,
                                      @NotNull TomcatDeploymentLogger logger,
                                      @NotNull String startMessage,
                                      @NotNull String abortMessage,
                                      @NotNull String errorMessage,
                                      @NotNull IntConsumer onSuccess) {
        logger.logServerInfo(startMessage);
        CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning(abortMessage);
                return;
            }
            if (errors > 0) {
                logger.logServerError(errorMessage + " with " + errors + " error(s)");
                return;
            }
            onSuccess.accept(warnings);
        });
    }
}
