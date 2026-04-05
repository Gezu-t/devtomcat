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
         import java.util.Objects;
         import java.util.concurrent.atomic.AtomicBoolean;

         /**
          * Tomcat Deployment Logger
          *
          * Provides structured, enterprise-grade logging for Tomcat deployment operations with:
          * - Consistent message formatting and prefixing
          * - Thread-safe EDT-compliant console output
          * - Deployment lifecycle tracking (start, success, failure, complete)
          * - Performance metrics and timing
          * - Debug mode support with stack traces
          * - Registry-driven configuration (timestamps, debug mode)
          *
          * <p>100% NULL-SAFE — All parameters validated, no silent failures
          * <p>EDT-Compliant — All UI operations wrapped in invokeLater
          * <p>Resource-Safe — Proper disposal and state tracking
          * <p>Thread-Safe — Uses AtomicBoolean for disposed state, volatile for consoleView
          *
          * <p>Responsibilities:
          * <ul>
          *   <li>Format and route deployment messages to console</li>
          *   <li>Track deployment timing and metrics</li>
          *   <li>Handle exceptions with optional stack traces</li>
          *   <li>Provide progress visualization</li>
          *   <li>Manage lifecycle and resource cleanup</li>
          * </ul>
          *
          * <p>Example Usage:
          * <pre>
          *   TomcatDeploymentLogger logger = new TomcatDeploymentLogger(project);
          *   logger.setConsoleView(consoleView);
          *   logger.logDeploymentStart("myapp.war");
          *   logger.logProgress("Deploying", 50);
          *   logger.logDeploymentSuccess("myapp.war", 1500);
          *   logger.dispose();
          * </pre>
          *
          * Author: Gezahegn Lemma (Gezu)
          * Project: DevTomcat Plugin
          * Created: 6/9/25
          */
         public class TomcatDeploymentLogger {

             private static final Logger LOG = Logger.getInstance(TomcatDeploymentLogger.class);

             // =====================================================================
             // FORMATTING CONSTANTS
             // =====================================================================

             private static final DateTimeFormatter TIMESTAMP_FORMAT =
                     DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

             private static final String PREFIX = "[DevTomcat]";

             // Message prefixes for categorization
             private static final String DEPLOYMENT_PREFIX = "[DEPLOY]";
             private static final String ERROR_PREFIX = "[ERROR]";
             private static final String WARNING_PREFIX = "[WARN]";
             private static final String DEBUG_PREFIX = "[DEBUG]";
             private static final String INFO_PREFIX = "[INFO]";

             // =====================================================================
             // REGISTRY KEYS
             // =====================================================================

             private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";
             private static final String REG_DEBUG_MODE = "devtomcat.debug.mode";

             // =====================================================================
             // PROGRESS BAR CONFIGURATION
             // =====================================================================

             private static final int PROGRESS_BAR_LENGTH = 10;
             private static final char PROGRESS_FILLED = '=';
             private static final char PROGRESS_EMPTY = '-';

             // =====================================================================
             // INSTANCE FIELDS
             // =====================================================================

             @NotNull
             private final Project project;

             @Nullable
             private volatile ConsoleView consoleView;

             private final long startTime;

             private final AtomicBoolean disposed;

             private final boolean showTimestamps;

             private final boolean debugMode;

             // =====================================================================
             // CONSTRUCTORS
             // =====================================================================

             /**
              * Create a new deployment logger instance.
              *
              * <p>Initializes with project context and loads configuration from Registry.
              *
              * @param project the current project (cannot be null)
              * @throws NullPointerException if project is null
              */
             public TomcatDeploymentLogger(@NotNull Project project) {
                 Objects.requireNonNull(project, "Project cannot be null");

                 this.project = project;
                 this.startTime = System.currentTimeMillis();
                 this.disposed = new AtomicBoolean(false);
                 this.showTimestamps = getRegistryBoolean(REG_SHOW_TIMESTAMPS, true);
                 this.debugMode = getRegistryBoolean(REG_DEBUG_MODE, false);

                 LOG.debug("TomcatDeploymentLogger created for project: " + project.getName());
             }

             // =====================================================================
             // CONSOLE VIEW MANAGEMENT
             // =====================================================================

             /**
              * Set the console view for message output.
              *
              * @param consoleView the console view (can be null to unset)
              */
             public void setConsoleView(@Nullable ConsoleView consoleView) {
                 this.consoleView = consoleView;
                 if (consoleView != null) {
                     LOG.debug("Console view attached to deployment logger");
                 }
             }

             /**
              * Get the current console view.
              *
              * @return the console view or null if not set
              */
             @Nullable
             public ConsoleView getConsoleView() {
                 return consoleView;
             }

             // =====================================================================
             // DEPLOYMENT LIFECYCLE LOGGING
             // =====================================================================

             /**
              * Log deployment process initialization.
              */
             public void logDeploymentStarted() {
                 logWithType("Starting deployment process...", ConsoleViewContentType.SYSTEM_OUTPUT);
                 LOG.info("Deployment process started");
             }

             /**
              * Log deployment start for a specific artifact.
              *
              * @param artifactName the artifact name (cannot be null)
              * @throws NullPointerException if artifactName is null
              */
             public void logDeploymentStart(@NotNull String artifactName) {
                 Objects.requireNonNull(artifactName, "Artifact name cannot be null");

                 String message = String.format("%s Deploying artifact '%s'...", DEPLOYMENT_PREFIX, artifactName);
                 logWithType(message, ConsoleViewContentType.SYSTEM_OUTPUT);
                 LOG.info("Deployment started for artifact: " + artifactName);
             }

             /**
              * Log successful artifact deployment.
              *
              * @param artifactName the deployed artifact name (cannot be null)
              * @param durationMs deployment duration in milliseconds (must be non-negative)
              * @throws NullPointerException if artifactName is null
              * @throws IllegalArgumentException if durationMs is negative
              */
             public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {
                 Objects.requireNonNull(artifactName, "Artifact name cannot be null");
                 if (durationMs < 0) {
                     throw new IllegalArgumentException("Duration cannot be negative: " + durationMs);
                 }

                 String message = String.format(
                         "%s Artifact '%s' deployed successfully (took %d ms)",
                         DEPLOYMENT_PREFIX, artifactName, durationMs
                 );
                 logWithType(message, ConsoleViewContentType.NORMAL_OUTPUT);
                 LOG.info("Deployment successful for artifact: " + artifactName + " (" + durationMs + "ms)");
             }

             /**
              * Log deployment failure with error details.
              *
              * @param artifactName the artifact name (cannot be null)
              * @param errorMessage the error message (cannot be null)
              * @throws NullPointerException if parameters are null
              */
             public void logDeploymentError(@NotNull String artifactName, @NotNull String errorMessage) {
                 Objects.requireNonNull(artifactName, "Artifact name cannot be null");
                 Objects.requireNonNull(errorMessage, "Error message cannot be null");

                 String message = String.format(
                         "%s Failed to deploy artifact '%s': %s",
                         ERROR_PREFIX, artifactName, errorMessage
                 );
                 logWithType(message, ConsoleViewContentType.ERROR_OUTPUT);
                 LOG.warn("Deployment failed for artifact: " + artifactName + " - " + errorMessage);
             }

             /**
              * Log deployment completion with summary metrics.
              *
              * @param totalArtifacts total number of artifacts processed (must be non-negative)
              * @param successCount number of successfully deployed artifacts (must be non-negative and &lt;= totalArtifacts)
              * @param totalDurationMs total deployment duration in milliseconds (must be non-negative)
              * @throws IllegalArgumentException if parameters are invalid
              */
             public void logDeploymentComplete(int totalArtifacts, int successCount, long totalDurationMs) {
                 if (totalArtifacts < 0) {
                     throw new IllegalArgumentException("Total artifacts cannot be negative: " + totalArtifacts);
                 }
                 if (successCount < 0 || successCount > totalArtifacts) {
                     throw new IllegalArgumentException("Success count must be 0-" + totalArtifacts + ", got: " + successCount);
                 }
                 if (totalDurationMs < 0) {
                     throw new IllegalArgumentException("Duration cannot be negative: " + totalDurationMs);
                 }

                 boolean allSuccessful = successCount == totalArtifacts;
                 String status = allSuccessful ? "SUCCESS" : "PARTIAL";

                 String message = String.format(
                         "%s Deployment complete: %s (%d/%d artifacts deployed in %d ms)",
                         DEPLOYMENT_PREFIX, status, successCount, totalArtifacts, totalDurationMs
                 );

                 ConsoleViewContentType contentType = allSuccessful ?
                         ConsoleViewContentType.NORMAL_OUTPUT : ConsoleViewContentType.LOG_WARNING_OUTPUT;

                 logWithType(message, contentType);

                 int successRate = totalArtifacts > 0 ? (successCount * 100 / totalArtifacts) : 0;
                 LOG.info("Deployment completed - Status: " + status + ", Success rate: " + successRate + "%");
             }

             // =====================================================================
             // SERVER LIFECYCLE LOGGING
             // =====================================================================

             /**
              * Log successful server connection establishment.
              */
             public void logServerConnection() {
                 logWithType("Connected to Tomcat server", ConsoleViewContentType.SYSTEM_OUTPUT);
                 LOG.info("Server connection established");
             }

             /**
              * Log server startup completion.
              *
              * @param startupTimeMs server startup duration in milliseconds (must be non-negative)
              * @throws IllegalArgumentException if startupTimeMs is negative
              */
             public void logServerStartup(long startupTimeMs) {
                 if (startupTimeMs < 0) {
                     throw new IllegalArgumentException("Startup time cannot be negative: " + startupTimeMs);
                 }

                 String message = String.format("Server started successfully in %d ms", startupTimeMs);
                 logWithType(message, ConsoleViewContentType.NORMAL_OUTPUT);
                 LOG.info("Server startup completed in " + startupTimeMs + "ms");
             }

             /**
              * Log informational server message.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logServerInfo(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logInfo(message);
             }

             /**
              * Log warning-level server message.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logServerWarning(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logWarning(message);
             }

             /**
              * Log error-level server message.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logServerError(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logError(message);
             }

             // =====================================================================
             // GENERAL MESSAGE LOGGING
             // =====================================================================

             /**
              * Log informational message with INFO prefix.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logInfo(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logPrefixed(message, INFO_PREFIX, ConsoleViewContentType.NORMAL_OUTPUT);
                 LOG.debug("Info: " + message);
             }

             /**
              * Log warning message with WARN prefix.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logWarning(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logPrefixed(message, WARNING_PREFIX, ConsoleViewContentType.LOG_WARNING_OUTPUT);
                 LOG.warn("Warning: " + message);
             }

             /**
              * Log error message with ERROR prefix.
              *
              * @param message the message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logError(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 logPrefixed(message, ERROR_PREFIX, ConsoleViewContentType.ERROR_OUTPUT);
                 // Use LOG.warn() — these are Tomcat output messages, not plugin errors.
                 // LOG.error() causes IntelliJ to report SEVERE and blame the plugin.
                 LOG.warn("Tomcat error: " + message);
             }

             /**
              * Log error message with exception details.
              *
              * <p>In debug mode, includes full stack trace. Otherwise, only exception class and message.
              *
              * @param message the error message (cannot be null)
              * @param throwable the exception (cannot be null)
              * @throws NullPointerException if parameters are null
              */
             public void logError(@NotNull String message, @NotNull Throwable throwable) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 Objects.requireNonNull(throwable, "Throwable cannot be null");

                 String errorMessage = message + " - " + throwable.getClass().getSimpleName() +
                         ": " + throwable.getMessage();

                 logError(errorMessage);

                 if (debugMode) {
                     String stackTrace = getStackTraceString(throwable);
                     logDebug("Stack trace:\n" + stackTrace);
                     LOG.debug("Full stack trace:\n" + stackTrace);
                 }

                 LOG.warn("Tomcat exception: " + throwable.getMessage());
             }

             /**
              * Log debug message (only if debug mode is enabled).
              *
              * <p>Silent no-op if debug mode is disabled.
              *
              * @param message the debug message (cannot be null)
              * @throws NullPointerException if message is null
              */
             public void logDebug(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");

                 if (debugMode) {
                     logPrefixed(message, DEBUG_PREFIX, ConsoleViewContentType.LOG_DEBUG_OUTPUT);
                     LOG.debug("Debug: " + message);
                 }
             }

             // =====================================================================
             // PROGRESS LOGGING
             // =====================================================================

             /**
              * Log progress update with visual progress bar.
              *
              * <p>Example output: `[INFO] Deploying [=====-----] 50%`
              *
              * @param operation current operation description (cannot be null)
              * @param progress progress percentage (0-100, will be clamped)
              * @throws NullPointerException if operation is null
              */
             public void logProgress(@NotNull String operation, int progress) {
                 Objects.requireNonNull(operation, "Operation cannot be null");

                 progress = Math.max(0, Math.min(100, progress));
                 String progressBar = createProgressBar(progress);
                 String message = String.format("%s %s %s %d%%", INFO_PREFIX, operation, progressBar, progress);

                 logWithType(message, ConsoleViewContentType.NORMAL_OUTPUT);
                 LOG.debug("Progress: " + operation + " " + progress + "%");
             }

             /**
              * Create a visual progress bar.
              *
              * <p>Example: `[=====-----] 50%`
              *
              * @param progress progress percentage (0-100)
              * @return the progress bar string (never null)
              */
             @NotNull
             private String createProgressBar(int progress) {
                 int filled = Math.max(0, Math.min(PROGRESS_BAR_LENGTH, progress / 10));
                 StringBuilder bar = new StringBuilder("[");

                 for (int i = 0; i < PROGRESS_BAR_LENGTH; i++) {
                     bar.append(i < filled ? PROGRESS_FILLED : PROGRESS_EMPTY);
                 }

                 bar.append("]");
                 return bar.toString();
             }

             // =====================================================================
             // CORE LOGGING METHODS
             // =====================================================================

             /**
              * Log message with prefix and content type.
              *
              * @param message the message (cannot be null)
              * @param prefix the prefix (cannot be null)
              * @param contentType the console content type (cannot be null)
              */
             private void logPrefixed(@NotNull String message, @NotNull String prefix,
                                      @NotNull ConsoleViewContentType contentType) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 Objects.requireNonNull(prefix, "Prefix cannot be null");
                 Objects.requireNonNull(contentType, "ContentType cannot be null");

                 String formatted = prefix + " " + message;
                 logWithType(formatted, contentType);
             }

             /**
              * Log formatted message with content type (core method).
              *
              * <p>Routes to console view if available, with EDT synchronization.
              * Silently ignores logging if logger is disposed.
              *
              * @param message the formatted message (cannot be null)
              * @param contentType the console content type (cannot be null)
              */
             private void logWithType(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
                 Objects.requireNonNull(message, "Message cannot be null");
                 Objects.requireNonNull(contentType, "ContentType cannot be null");

                 if (disposed.get()) {
                     LOG.debug("Logger is disposed, ignoring message: " + message);
                     return;
                 }

                 // Format message with timestamp if enabled
                 String formattedMessage = formatMessage(message);

                 // Capture consoleView into a local before the null check so the lambda holds
                 // a stable reference. Without this, another thread can null the field between
                 // the outer check and the actual print call (TOCTOU race -> NPE).
                 ConsoleView cv = consoleView;
                 if (cv != null && !project.isDisposed()) {
                     ApplicationManager.getApplication().invokeLater(() -> {
                         try {
                             if (!disposed.get() && !project.isDisposed()) {
                                 cv.print(formattedMessage + "\n", contentType);
                             }
                         } catch (Exception e) {
                             LOG.warn("Failed to print to console", e);
                         }
                     });
                 }
             }

             /**
              * Format message with optional timestamp.
              *
              * @param message the raw message (cannot be null)
              * @return the formatted message (never null)
              */
             @NotNull
             private String formatMessage(@NotNull String message) {
                 Objects.requireNonNull(message, "Message cannot be null");

                 StringBuilder formatted = new StringBuilder();

                 // Add timestamp if enabled in Registry
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
              * Get stack trace as formatted string.
              *
              * <p>Includes exception message and all stack trace elements recursively for causes.
              *
              * @param throwable the exception (cannot be null)
              * @return the formatted stack trace (never null)
              */
             @NotNull
             private String getStackTraceString(@NotNull Throwable throwable) {
                 Objects.requireNonNull(throwable, "Throwable cannot be null");

                 StringBuilder sb = new StringBuilder();
                 appendThrowable(sb, throwable);
                 return sb.toString();
             }

             /**
              * Recursively append exception and its causes to string builder.
              *
              * @param sb the string builder (cannot be null)
              * @param throwable the exception (cannot be null)
              */
             private void appendThrowable(@NotNull StringBuilder sb, @NotNull Throwable throwable) {
                 Objects.requireNonNull(sb, "StringBuilder cannot be null");
                 Objects.requireNonNull(throwable, "Throwable cannot be null");

                 java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                 Throwable current = throwable;
                 while (current != null && seen.add(current)) {
                     if (current != throwable) {
                         sb.append("Caused by: ");
                     }
                     sb.append(current.toString()).append("\n");
                     for (StackTraceElement element : current.getStackTrace()) {
                         sb.append("  at ").append(element.toString()).append("\n");
                     }
                     current = current.getCause();
                 }
             }

             // =====================================================================
             // UTILITY & STATUS METHODS
             // =====================================================================

             /**
              * Get elapsed time since logger creation.
              *
              * @return elapsed time in milliseconds
              */
             public long getElapsedTime() {
                 return System.currentTimeMillis() - startTime;
             }

             /**
              * Check if debug mode is enabled.
              *
              * @return true if debug mode is enabled
              */
             public boolean isDebugMode() {
                 return debugMode;
             }

             /**
              * Check if timestamps are shown in logs.
              *
              * @return true if timestamps are enabled
              */
             public boolean isShowTimestamps() {
                 return showTimestamps;
             }

             /**
              * Get the project instance.
              *
              * @return the project (never null)
              */
             @NotNull
             public Project getProject() {
                 return project;
             }

             // =====================================================================
             // LIFECYCLE MANAGEMENT
             // =====================================================================

             /**
              * Dispose of this logger and clean up resources.
              *
              * <p>After disposal, all logging attempts are silently ignored.
              * Safe to call multiple times.
              */
             public void dispose() {
                 if (disposed.compareAndSet(false, true)) {
                     consoleView = null;
                     LOG.debug("TomcatDeploymentLogger disposed");
                 }
             }

             /**
              * Check if this logger has been disposed.
              *
              * @return true if disposed
              */
             public boolean isDisposed() {
                 return disposed.get();
             }

             /**
              * Get registry boolean value with fallback.
              *
              * @param key the registry key (cannot be null)
              * @param defaultValue the default value if key not found
              * @return the registry value or default
              */
             private static boolean getRegistryBoolean(@NotNull String key, boolean defaultValue) {
                 Objects.requireNonNull(key, "Registry key cannot be null");

                 try {
                     return Registry.is(key);
                 } catch (Exception e) {
                     LOG.debug("Registry key not found: " + key + ", using default: " + defaultValue);
                     return defaultValue;
                 }
             }

             /**
              * Get logger status summary for debugging.
              *
              * @return the status string (never null)
              */
             @NotNull
             public String getStatus() {
                 return String.format(
                         "TomcatDeploymentLogger{project='%s', disposed=%s, debugMode=%s, timestamps=%s, elapsed=%dms}",
                         project.getName(), disposed.get(), debugMode, showTimestamps, getElapsedTime()
                 );
             }
         }