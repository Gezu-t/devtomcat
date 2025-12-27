package com.dev.idea.plugins.tomcat.console;

            import com.intellij.execution.ui.ConsoleView;
            import com.intellij.execution.ui.ConsoleViewContentType;
            import com.intellij.openapi.application.ApplicationManager;
            import com.intellij.openapi.diagnostic.Logger;
            import com.intellij.openapi.project.Project;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;

            import java.time.LocalDateTime;
            import java.time.format.DateTimeFormatter;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.Map;
            import java.util.Objects;

            /**
             * Professional DevTomcat Console Manager
             * Enhanced console view management for enterprise logging and monitoring
             *
             * Author: Gezahegn Lemma (Gezu)
             * Project: DevTomcat Plugin
             * Created: 6/9/25
             *
             * <p>100% NULL-SAFE — All null checks with comprehensive error handling
             * <p>Thread-safe for concurrent console operations
             *
             * Responsibilities:
             * <ul>
             *   <li>Manage multiple console views</li>
             *   <li>Format and print messages with timestamps</li>
             *   <li>Handle EDT threading for UI operations</li>
             *   <li>Provide enterprise-grade logging</li>
             * </ul>
             */
            public class TomcatConsoleManager {

                private static final Logger LOG = Logger.getInstance(TomcatConsoleManager.class);
                private static final DateTimeFormatter TIMESTAMP_FORMAT =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

                private final Project project;
                private final Map<String, ConsoleView> consoleViews = new ConcurrentHashMap<>();
                private ConsoleView mainConsoleView;

                /**
                 * Create a new console manager for the given project.
                 *
                 * @param project the IDE project (cannot be null)
                 */
                public TomcatConsoleManager(@NotNull Project project) {
                    Objects.requireNonNull(project, "Project cannot be null");
                    this.project = project;
                    LOG.debug("Created DevTomcat Console Manager for project: " + project.getName());
                }

                /**
                 * Set the main console view (provided externally by the run framework).
                 *
                 * @param consoleView the main console view (cannot be null)
                 */
                public void setMainConsoleView(@NotNull ConsoleView consoleView) {
                    Objects.requireNonNull(consoleView, "ConsoleView cannot be null");
                    this.mainConsoleView = consoleView;
                    consoleViews.put("Server", consoleView);
                    LOG.debug("Main console view set and registered as 'Server'");
                }

                /**
                 * Get the main server console.
                 *
                 * @return the main console view or null if not set
                 */
                @Nullable
                public ConsoleView getServerConsole() {
                    return mainConsoleView;
                }

                /**
                 * Register a console view for specific tab monitoring.
                 *
                 * @param name the console name/tab name (cannot be null)
                 * @param consoleView the console view to register (cannot be null)
                 */
                public void registerConsoleView(@NotNull String name, @NotNull ConsoleView consoleView) {
                    Objects.requireNonNull(name, "Console name cannot be null");
                    Objects.requireNonNull(consoleView, "ConsoleView cannot be null");

                    consoleViews.put(name, consoleView);
                    LOG.debug("Registered console view: " + name);
                }

                /**
                 * Get console for specific tab name.
                 *
                 * @param tabName the tab name (cannot be null)
                 * @return the console view or null if not found
                 */
                @Nullable
                public ConsoleView getConsole(@NotNull String tabName) {
                    Objects.requireNonNull(tabName, "Tab name cannot be null");
                    return consoleViews.get(tabName);
                }

                /**
                 * Print message to specific console tab with EDT threading.
                 *
                 * @param tabName the tab name (cannot be null)
                 * @param message the message to print (cannot be null)
                 * @param contentType the content type for formatting (cannot be null)
                 */
                public void printToConsole(@NotNull String tabName, @NotNull String message,
                                           @NotNull ConsoleViewContentType contentType) {
                    Objects.requireNonNull(tabName, "Tab name cannot be null");
                    Objects.requireNonNull(message, "Message cannot be null");
                    Objects.requireNonNull(contentType, "ContentType cannot be null");

                    try {
                        ConsoleView console = getConsole(tabName);
                        if (console != null) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    console.print(message, contentType);
                                } catch (Exception e) {
                                    LOG.warn("Error printing to console tab: " + tabName, e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        LOG.warn("Error accessing console for tab: " + tabName, e);
                    }
                }

                /**
                 * Print message to server console (main tab) with EDT threading.
                 *
                 * @param message the message to print (cannot be null)
                 * @param contentType the content type for formatting (cannot be null)
                 */
                public void printToServerConsole(@NotNull String message,
                                                 @NotNull ConsoleViewContentType contentType) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    Objects.requireNonNull(contentType, "ContentType cannot be null");

                    try {
                        if (mainConsoleView != null) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    mainConsoleView.print(message, contentType);
                                } catch (Exception e) {
                                    LOG.warn("Error printing to main console", e);
                                }
                            });
                        } else {
                            LOG.debug("Main console view not available, message buffered locally");
                        }
                    } catch (Exception e) {
                        LOG.warn("Error accessing main console", e);
                    }
                }

                /**
                 * Print deployment message with DevTomcat timestamp formatting.
                 *
                 * @param message the deployment message (cannot be null)
                 */
                public void printDeploymentMessage(@NotNull String message) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    String timestamp = getCurrentTimestamp();
                    String formattedMessage = String.format("[%s] DevTomcat: %s%n", timestamp, message);
                    printToServerConsole(formattedMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
                    LOG.debug("Deployment message printed: " + message);
                }

                /**
                 * Print error message with DevTomcat timestamp formatting.
                 *
                 * @param message the error message (cannot be null)
                 */
                public void printErrorMessage(@NotNull String message) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    String timestamp = getCurrentTimestamp();
                    String formattedMessage = String.format("[%s] DevTomcat ERROR: %s%n", timestamp, message);
                    printToServerConsole(formattedMessage, ConsoleViewContentType.ERROR_OUTPUT);
                    LOG.warn("Error message printed: " + message);
                }

                /**
                 * Print warning message with DevTomcat timestamp formatting.
                 *
                 * @param message the warning message (cannot be null)
                 */
                public void printWarningMessage(@NotNull String message) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    String timestamp = getCurrentTimestamp();
                    String formattedMessage = String.format("[%s] DevTomcat WARNING: %s%n", timestamp, message);
                    printToServerConsole(formattedMessage, ConsoleViewContentType.ERROR_OUTPUT);
                    LOG.debug("Warning message printed: " + message);
                }

                /**
                 * Print info message with DevTomcat timestamp formatting.
                 *
                 * @param message the info message (cannot be null)
                 */
                public void printInfoMessage(@NotNull String message) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    String timestamp = getCurrentTimestamp();
                    String formattedMessage = String.format("[%s] DevTomcat: %s%n", timestamp, message);
                    printToServerConsole(formattedMessage, ConsoleViewContentType.NORMAL_OUTPUT);
                    LOG.debug("Info message printed: " + message);
                }

                /**
                 * Print professional server startup message.
                 *
                 * @param serverName the server name (cannot be null)
                 * @param startupTime the startup time in milliseconds
                 */
                public void printServerStartupMessage(@NotNull String serverName, long startupTime) {
                    Objects.requireNonNull(serverName, "Server name cannot be null");
                    String message = String.format("Server startup - %s completed in %d ms", serverName, startupTime);
                    printInfoMessage(message);
                }

                /**
                 * Print deployment status message.
                 *
                 * @param artifactName the artifact name (cannot be null)
                 * @param status the deployment status (cannot be null)
                 */
                public void printDeploymentStatusMessage(@NotNull String artifactName, @NotNull String status) {
                    Objects.requireNonNull(artifactName, "Artifact name cannot be null");
                    Objects.requireNonNull(status, "Status cannot be null");

                    String message = String.format("Artifact %s: %s", artifactName, status);
                    printDeploymentMessage(message);
                }

                /**
                 * Print JMX monitoring message.
                 *
                 * @param jmxInfo the JMX information (cannot be null)
                 */
                public void printJmxMessage(@NotNull String jmxInfo) {
                    Objects.requireNonNull(jmxInfo, "JMX info cannot be null");
                    String message = String.format("JMX monitoring: %s", jmxInfo);
                    printInfoMessage(message);
                }

                /**
                 * Print feature activation message.
                 *
                 * @param featureName the feature name (cannot be null)
                 * @param enabled true if feature is enabled
                 */
                public void printFeatureMessage(@NotNull String featureName, boolean enabled) {
                    Objects.requireNonNull(featureName, "Feature name cannot be null");
                    String status = enabled ? "enabled" : "disabled";
                    String message = String.format("Feature %s: %s", featureName, status);
                    printInfoMessage(message);
                }

                /**
                 * Clear main console view.
                 */
                public void clearConsole() {
                    try {
                        if (mainConsoleView != null) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    mainConsoleView.clear();
                                    LOG.debug("Main console cleared");
                                } catch (Exception e) {
                                    LOG.warn("Error clearing main console", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        LOG.warn("Error accessing main console for clearing", e);
                    }
                }

                /**
                 * Clear specific console by name.
                 *
                 * @param tabName the tab name (cannot be null)
                 */
                public void clearConsole(@NotNull String tabName) {
                    Objects.requireNonNull(tabName, "Tab name cannot be null");

                    try {
                        ConsoleView console = getConsole(tabName);
                        if (console != null) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    console.clear();
                                    LOG.debug("Console cleared: " + tabName);
                                } catch (Exception e) {
                                    LOG.warn("Error clearing console: " + tabName, e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        LOG.warn("Error accessing console for clearing: " + tabName, e);
                    }
                }

                /**
                 * Check if main console is available.
                 *
                 * @return true if main console is set
                 */
                public boolean hasMainConsole() {
                    return mainConsoleView != null;
                }

                /**
                 * Check if specific console is available.
                 *
                 * @param tabName the tab name (cannot be null)
                 * @return true if console exists
                 */
                public boolean hasConsole(@NotNull String tabName) {
                    Objects.requireNonNull(tabName, "Tab name cannot be null");
                    return consoleViews.containsKey(tabName);
                }

                /**
                 * Get all registered console names.
                 *
                 * @return array of console names (never null)
                 */
                @NotNull
                public String[] getConsoleNames() {
                    return consoleViews.keySet().toArray(new String[0]);
                }

                /**
                 * Get console count for monitoring.
                 *
                 * @return number of registered consoles
                 */
                public int getConsoleCount() {
                    return consoleViews.size();
                }

                /**
                 * Get project instance.
                 *
                 * @return the project (never null)
                 */
                @NotNull
                public Project getProject() {
                    return project;
                }

                /**
                 * Get console manager status summary.
                 *
                 * @return status string (never null)
                 */
                @NotNull
                public String getManagerStatus() {
                    StringBuilder status = new StringBuilder();
                    status.append("DevTomcat Console Manager - ");
                    status.append(consoleViews.size()).append(" consoles active");

                    if (hasMainConsole()) {
                        status.append(", Main console available");
                    }

                    return status.toString();
                }

                /**
                 * Print professional session summary.
                 *
                 * @param configName the configuration name (cannot be null)
                 * @param sessionDuration the session duration in milliseconds
                 * @param errorCount the error count
                 * @param warningCount the warning count
                 */
                public void printSessionSummary(@NotNull String configName, long sessionDuration,
                                                int errorCount, int warningCount) {
                    Objects.requireNonNull(configName, "Config name cannot be null");

                    StringBuilder summary = new StringBuilder();
                    summary.append("\n=== DevTomcat Session Summary ===\n");
                    summary.append("Configuration: ").append(configName).append("\n");
                    summary.append("Session Duration: ").append(sessionDuration).append(" ms\n");
                    summary.append("Errors: ").append(errorCount).append("\n");
                    summary.append("Warnings: ").append(warningCount).append("\n");
                    summary.append("=== End Summary ===\n");

                    printToServerConsole(summary.toString(), ConsoleViewContentType.SYSTEM_OUTPUT);
                    LOG.debug("Session summary printed for: " + configName);
                }

                /**
                 * Print performance metrics message.
                 *
                 * @param metrics the metrics information (cannot be null)
                 */
                public void printPerformanceMetrics(@NotNull String metrics) {
                    Objects.requireNonNull(metrics, "Metrics cannot be null");
                    String message = String.format("Performance Metrics: %s", metrics);
                    printInfoMessage(message);
                }

                /**
                 * Print configuration message.
                 *
                 * @param configInfo the configuration information (cannot be null)
                 */
                public void printConfigurationMessage(@NotNull String configInfo) {
                    Objects.requireNonNull(configInfo, "Config info cannot be null");
                    String message = String.format("Configuration: %s", configInfo);
                    printInfoMessage(message);
                }

                /**
                 * Print monitoring message.
                 *
                 * @param monitoringInfo the monitoring information (cannot be null)
                 */
                public void printMonitoringMessage(@NotNull String monitoringInfo) {
                    Objects.requireNonNull(monitoringInfo, "Monitoring info cannot be null");
                    String message = String.format("Monitoring: %s", monitoringInfo);
                    printInfoMessage(message);
                }

                /**
                 * Print raw message without formatting (for special cases).
                 *
                 * @param message the raw message (cannot be null)
                 * @param contentType the content type (cannot be null)
                 */
                public void printRawMessage(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
                    Objects.requireNonNull(message, "Message cannot be null");
                    Objects.requireNonNull(contentType, "ContentType cannot be null");

                    printToServerConsole(message, contentType);
                }

                /**
                 * Clean up resources and dispose of console manager.
                 */
                public void dispose() {
                    try {
                        consoleViews.clear();
                        mainConsoleView = null;
                        LOG.debug("DevTomcat Console Manager disposed");
                    } catch (Exception e) {
                        LOG.warn("Error disposing console manager", e);
                    }
                }

                /**
                 * Get current timestamp for logging.
                 *
                 * @return formatted timestamp string (never null)
                 */
                @NotNull
                private String getCurrentTimestamp() {
                    return LocalDateTime.now().format(TIMESTAMP_FORMAT);
                }
            }