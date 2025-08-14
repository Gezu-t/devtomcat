package com.dev.idea.plugins.tomcat.logging;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat Deployment Logger
 *
 * Provides structured logging for Tomcat deployment operations with:
 * - Consistent message formatting
 * - Thread-safe console output
 * - Deployment timing and metrics
 * - Error tracking and reporting
 * - Debug mode support
 *
 * All messages are properly formatted and sent to the appropriate
 * console output type for better visibility and filtering.
 *
 * @author Dev Tomcat Team
 * @see ConsoleView
 * @see ConsoleViewContentType
 */
public class TomcatDeploymentLogger {

    private static final Logger LOG = Logger.getInstance(TomcatDeploymentLogger.class);

    // Formatting constants
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String PREFIX = "[Dev Tomcat]";
    private static final String DEPLOYMENT_PREFIX = "[DEPLOY]";
    private static final String ERROR_PREFIX = "[ERROR]";
    private static final String WARNING_PREFIX = "[WARN]";
    private static final String DEBUG_PREFIX = "[DEBUG]";
    private static final String INFO_PREFIX = "[INFO]";

    // Registry keys
    private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";
    private static final String REG_DEBUG_MODE = "devtomcat.debug.mode";

    // Instance fields
    @NotNull private final Project project;
    @Nullable private volatile ConsoleView consoleView;
    private final long startTime;
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final boolean showTimestamps;
    private final boolean debugMode;

    /**
     * Create a new deployment logger
     *
     * @param project The current project
     */
    public TomcatDeploymentLogger(@NotNull Project project) {
        this.project = project;
        this.startTime = System.currentTimeMillis();
        this.showTimestamps = Registry.is(REG_SHOW_TIMESTAMPS);
        this.debugMode = Registry.is(REG_DEBUG_MODE);
    }

    /**
     * Set the console view for output
     *
     * @param consoleView The console view to use
     */
    public void setConsoleView(@Nullable ConsoleView consoleView) {
        this.consoleView = consoleView;
        if (consoleView != null) {
            logDebug("Console view attached to deployment logger");
        }
    }

    // === DEPLOYMENT LIFECYCLE LOGGING ===

    /**
     * Log deployment process start
     */
    public void logDeploymentStarted() {
        log("Starting deployment process...", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log deployment start for a specific artifact
     *
     * @param artifactName The artifact being deployed
     */
    public void logDeploymentStart(@NotNull String artifactName) {
        String message = String.format("%s Deploying artifact '%s'...",
                DEPLOYMENT_PREFIX, artifactName);
        log(message, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log successful deployment
     *
     * @param artifactName The deployed artifact
     * @param durationMs Deployment duration in milliseconds
     */
    public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {
        String message = String.format("%s Artifact '%s' deployed successfully (took %d ms)",
                DEPLOYMENT_PREFIX, artifactName, durationMs);
        log(message, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Log deployment failure
     *
     * @param artifactName The artifact that failed to deploy
     * @param errorMessage The error message
     */
    public void logDeploymentError(@NotNull String artifactName, @NotNull String errorMessage) {
        String message = String.format("%s Failed to deploy artifact '%s': %s",
                ERROR_PREFIX, artifactName, errorMessage);
        log(message, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Log deployment completion with summary
     *
     * @param totalArtifacts Total number of artifacts
     * @param successCount Number of successfully deployed artifacts
     * @param totalDurationMs Total deployment time
     */
    public void logDeploymentComplete(int totalArtifacts, int successCount, long totalDurationMs) {
        String status = successCount == totalArtifacts ? "SUCCESS" : "PARTIAL";
        String message = String.format(
                "%s Deployment complete: %s (%d/%d artifacts deployed in %d ms)",
                DEPLOYMENT_PREFIX, status, successCount, totalArtifacts, totalDurationMs
        );

        ConsoleViewContentType contentType = successCount == totalArtifacts ?
                ConsoleViewContentType.NORMAL_OUTPUT : ConsoleViewContentType.LOG_WARNING_OUTPUT;

        log(message, contentType);
    }

    // === SERVER LIFECYCLE LOGGING ===

    /**
     * Log server connection established
     */
    public void logServerConnection() {
        log("Connected to Tomcat server", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Log server startup completion
     *
     * @param startupTimeMs Server startup time in milliseconds
     */
    public void logServerStartup(long startupTimeMs) {
        String message = String.format("Server started successfully in %d ms", startupTimeMs);
        log(message, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Log general server information
     *
     * @param message The information message
     */
    public void logServerInfo(@NotNull String message) {
        logInfo(message);
    }

    /**
     * Log server warnings
     *
     * @param message The warning message
     */
    public void logServerWarning(@NotNull String message) {
        logWarning(message);
    }

    /**
     * Log server errors
     *
     * @param message The error message
     */
    public void logServerError(@NotNull String message) {
        logError(message);
    }

    // === GENERAL LOGGING METHODS ===

    /**
     * Log informational message
     *
     * @param message The message to log
     */
    public void logInfo(@NotNull String message) {
        log(INFO_PREFIX + " " + message, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Log warning message
     *
     * @param message The warning message
     */
    public void logWarning(@NotNull String message) {
        log(WARNING_PREFIX + " " + message, ConsoleViewContentType.LOG_WARNING_OUTPUT);
    }

    /**
     * Log error message
     *
     * @param message The error message
     */
    public void logError(@NotNull String message) {
        log(ERROR_PREFIX + " " + message, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Log error with exception
     *
     * @param message The error message
     * @param throwable The exception
     */
    public void logError(@NotNull String message, @NotNull Throwable throwable) {
        log(ERROR_PREFIX + " " + message, ConsoleViewContentType.ERROR_OUTPUT);

        // Log stack trace in debug mode
        if (debugMode) {
            String stackTrace = getStackTraceString(throwable);
            log(stackTrace, ConsoleViewContentType.ERROR_OUTPUT);
        } else {
            log("  Caused by: " + throwable.getClass().getSimpleName() +
                    " - " + throwable.getMessage(), ConsoleViewContentType.ERROR_OUTPUT);
        }
    }

    /**
     * Log debug message (only if debug mode is enabled)
     *
     * @param message The debug message
     */
    public void logDebug(@NotNull String message) {
        if (debugMode) {
            log(DEBUG_PREFIX + " " + message, ConsoleViewContentType.LOG_DEBUG_OUTPUT);
        }
    }

    // === PROGRESS LOGGING ===

    /**
     * Log progress update
     *
     * @param operation Current operation
     * @param progress Progress percentage (0-100)
     */
    public void logProgress(@NotNull String operation, int progress) {
        String progressBar = createProgressBar(progress);
        String message = String.format("%s %s %s %d%%",
                INFO_PREFIX, operation, progressBar, progress);
        log(message, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Create a simple progress bar
     */
    private String createProgressBar(int progress) {
        int filled = Math.max(0, Math.min(10, progress / 10));
        StringBuilder bar = new StringBuilder("[");

        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? "=" : "-");
        }

        bar.append("]");
        return bar.toString();
    }

    // === CORE LOGGING ===

    /**
     * Core logging method
     *
     * @param message The message to log
     * @param contentType The console content type
     */
    private void log(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
        if (disposed.get()) {
            return;
        }

        // Format message
        String formattedMessage = formatMessage(message);

        // Log to console view if available
        if (consoleView != null && !project.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && !disposed.get() && consoleView != null) {
                    consoleView.print(formattedMessage + "\n", contentType);
                }
            });
        }

        // Also log to IDE log
        logToIdeLog(message, contentType);
    }

    /**
     * Format message with timestamp and prefix
     */
    private String formatMessage(@NotNull String message) {
        StringBuilder formatted = new StringBuilder();

        if (showTimestamps) {
            formatted.append("[")
                    .append(LocalDateTime.now().format(TIMESTAMP_FORMAT))
                    .append("] ");
        }

        // Add main prefix if not already present
        if (!message.startsWith("[")) {
            formatted.append(PREFIX).append(" ");
        }

        formatted.append(message);

        return formatted.toString();
    }

    /**
     * Log to IDE log file
     */
    private void logToIdeLog(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
        if (contentType == ConsoleViewContentType.ERROR_OUTPUT) {
            LOG.error(message);
        } else if (contentType == ConsoleViewContentType.LOG_WARNING_OUTPUT) {
            LOG.warn(message);
        } else if (contentType == ConsoleViewContentType.LOG_DEBUG_OUTPUT) {
            LOG.debug(message);
        } else {
            LOG.info(message);
        }
    }

    /**
     * Get stack trace as string
     */
    private String getStackTraceString(@NotNull Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.toString()).append("\n");

        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append("    at ").append(element.toString()).append("\n");
        }

        if (throwable.getCause() != null) {
            sb.append("Caused by: ");
            sb.append(getStackTraceString(throwable.getCause()));
        }

        return sb.toString();
    }

    // === UTILITY METHODS ===

    /**
     * Get elapsed time since logger creation
     *
     * @return Elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Get the console view
     *
     * @return The console view or null
     */
    @Nullable
    public ConsoleView getConsoleView() {
        return consoleView;
    }

    /**
     * Check if debug mode is enabled
     *
     * @return true if debug mode is enabled
     */
    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Dispose of this logger
     */
    public void dispose() {
        disposed.set(true);
        consoleView = null;
        logDebug("Deployment logger disposed");
    }

    /**
     * Check if this logger is disposed
     *
     * @return true if disposed
     */
    public boolean isDisposed() {
        return disposed.get();
    }
}