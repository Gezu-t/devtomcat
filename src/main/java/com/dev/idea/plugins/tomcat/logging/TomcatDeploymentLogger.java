package com.dev.idea.plugins.tomcat.logging;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Professional DevTomcat Deployment Logger
 * Provides enterprise-level deployment status messages and monitoring
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class TomcatDeploymentLogger {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final Project project;
    private ConsoleView consoleView;
    private final long creationTime;

    public TomcatDeploymentLogger(@NotNull Project project) {
        this.project = project;
        this.creationTime = System.currentTimeMillis();
    }

    public void setConsoleView(@Nullable ConsoleView consoleView) {
        this.consoleView = consoleView;
    }

    /**
     * Log deployment started (no artifact name)
     */
    public void logDeploymentStarted() {
        String timestamp = getCurrentTimestamp();
        String message = String.format("[%s] DevTomcat: Starting deployment process...%n", timestamp);
        printToConsole(message, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log deployment start for specific artifact
     */
    public void logDeploymentStart(@NotNull String artifactName) {
        String timestamp = getCurrentTimestamp();
        String message = String.format("[%s] Artifact %s: Artifact is being deployed, please wait...%n", timestamp, artifactName);
        printToConsole(message, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log deployment success
     */
    public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {
        String timestamp = getCurrentTimestamp();
        String message = String.format("[%s] Artifact %s: Artifact is deployed successfully%n", timestamp, artifactName);
        printToConsole(message, ConsoleViewContentType.NORMAL_OUTPUT);

        // Professional timing information
        logServerInfo(String.format("Deployment completed in %d ms", durationMs));
    }

    /**
     * Log deployment error
     */
    public void logDeploymentError(@NotNull String artifactName, @NotNull String errorMessage) {
        String timestamp = getCurrentTimestamp();
        String message = String.format("[%s] Artifact %s: Error during artifact deployment. %s%n", timestamp, artifactName, errorMessage);
        printToConsole(message, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Log server connection status
     */
    public void logServerConnection() {
        printToConsole("Connected to server%n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log server startup completion
     */
    public void logServerStartup(long startupTimeMs) {
        String message = String.format("DevTomcat: Server startup in %d ms%n", startupTimeMs);
        printToConsole(message, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Log general server information
     */
    public void logServerInfo(@NotNull String message) {
        String formattedMessage = String.format("DevTomcat: %s%n", message);
        printToConsole(formattedMessage, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Log server warnings
     */
    public void logServerWarning(@NotNull String message) {
        String formattedMessage = String.format("DevTomcat: WARNING - %s%n", message);
        printToConsole(formattedMessage, ConsoleViewContentType.LOG_WARNING_OUTPUT);
    }

    /**
     * Log warnings (alias for logServerWarning)
     */
    public void logWarning(@NotNull String message) {
        logServerWarning(message);
    }

    /**
     * Log server errors
     */
    public void logServerError(@NotNull String message) {
        String formattedMessage = String.format("DevTomcat: ERROR - %s%n", message);
        printToConsole(formattedMessage, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Log debug information
     */
    public void logDebug(@NotNull String message) {
        if (isDebugEnabled()) {
            String timestamp = getCurrentTimestamp();
            String formattedMessage = String.format("[%s] DEBUG: %s%n", timestamp, message);
            printToConsole(formattedMessage, ConsoleViewContentType.LOG_DEBUG_OUTPUT);
        }
    }

    /**
     * Get current timestamp
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    /**
     * Print to console with proper thread handling
     */
    private void printToConsole(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
        if (consoleView != null && !project.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {if (!project.isDisposed() && consoleView != null) consoleView.print(message, contentType);});
        }
    }

    /**
     * Check if debug logging is enabled
     */
    private boolean isDebugEnabled() {
        // Could be configured via Registry or settings
        return System.getProperty("devtomcat.debug", "false").equals("true");
    }

    /**
     * Get uptime since logger creation
     */
    public long getUptime() {
        return System.currentTimeMillis() - creationTime;
    }
}