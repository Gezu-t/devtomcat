package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Console Logging Utility
 *
 * Shared utility for thread-safe EDT-compliant console output across the plugin.
 * Eliminates duplication across TomcatConsoleManager and TomcatDeploymentLogger.
 */
public final class ConsoleLoggerUtil {

    private static final Logger LOG = Logger.getInstance(ConsoleLoggerUtil.class);

    private ConsoleLoggerUtil() {}

    /**
     * Print message to console view safely.
     *
     * @param consoleView the console view (can be null)
     * @param message the message (cannot be null)
     * @param contentType the content type (cannot be null)
     * @param project the project (cannot be null)
     */
    public static void printToConsole(@Nullable ConsoleView consoleView,
                                      @NotNull String message,
                                      @NotNull ConsoleViewContentType contentType,
                                      @NotNull Project project) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(contentType, "ContentType cannot be null");
        Objects.requireNonNull(project, "Project cannot be null");

        if (consoleView == null || project.isDisposed()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (consoleView != null && !project.isDisposed()) {
                    consoleView.print(message + "\n", contentType);
                }
            } catch (Exception e) {
                LOG.warn("Failed to print to console", e);
            }
        });
    }
}