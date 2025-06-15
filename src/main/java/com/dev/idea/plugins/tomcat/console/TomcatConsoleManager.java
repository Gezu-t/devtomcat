package com.dev.idea.plugins.tomcat.console;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Professional DevTomcat Console Manager
 * Enhanced console view management for enterprise logging and monitoring
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatConsoleManager {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final Project project;
    private final Map<String, ConsoleView> consoleViews = new ConcurrentHashMap<>();
    private ConsoleView mainConsoleView;

    public TomcatConsoleManager(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Set the main console view (provided externally by the run framework)
     */
    public void setMainConsoleView(@NotNull ConsoleView consoleView) {
        this.mainConsoleView = consoleView;
        consoleViews.put("Server", consoleView);
        System.out.println("DevTomcat: Professional console manager initialized");
    }

    /**
     * Get main server console
     */
    @Nullable
    public ConsoleView getServerConsole() {
        return mainConsoleView;
    }

    /**
     * Register a console view for enterprise monitoring
     */
    public void registerConsoleView(@NotNull String name, @NotNull ConsoleView consoleView) {
        consoleViews.put(name, consoleView);
        System.out.println("DevTomcat: Registered console view - " + name);
    }

    /**
     * Get console for specific tab
     */
    @Nullable
    public ConsoleView getConsole(String tabName) {
        return consoleViews.get(tabName);
    }

    /**
     * Print message to specific console tab with professional threading
     */
    public void printToConsole(String tabName, String message, ConsoleViewContentType contentType) {
        ConsoleView console = getConsole(tabName);
        if (console != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                console.print(message, contentType);
            });
        }
    }

    /**
     * Print to server console (main tab) with professional threading
     */
    public void printToServerConsole(String message, ConsoleViewContentType contentType) {
        if (mainConsoleView != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                mainConsoleView.print(message, contentType);
            });
        }
    }

    /**
     * Print deployment message with professional DevTomcat formatting
     */
    public void printDeploymentMessage(String message) {
        String timestamp = getCurrentTimestamp();
        String formattedMessage = String.format("[%s] DevTomcat: %s%n", timestamp, message);
        printToServerConsole(formattedMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Print error message with professional DevTomcat formatting
     */
    public void printErrorMessage(String message) {
        String timestamp = getCurrentTimestamp();
        String formattedMessage = String.format("[%s] DevTomcat ERROR: %s%n", timestamp, message);
        printToServerConsole(formattedMessage, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Print warning message with professional DevTomcat formatting
     */
    public void printWarningMessage(String message) {
        String timestamp = getCurrentTimestamp();
        String formattedMessage = String.format("[%s] DevTomcat WARNING: %s%n", timestamp, message);
        printToServerConsole(formattedMessage, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Print info message with professional DevTomcat formatting
     */
    public void printInfoMessage(String message) {
        String timestamp = getCurrentTimestamp();
        String formattedMessage = String.format("[%s] DevTomcat: %s%n", timestamp, message);
        printToServerConsole(formattedMessage, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Print professional server startup message
     */
    public void printServerStartupMessage(String serverName, long startupTime) {
        String message = String.format("Professional server startup - %s completed in %d ms",
                serverName, startupTime);
        printInfoMessage(message);
    }

    /**
     * Print professional deployment status message
     */
    public void printDeploymentStatusMessage(String artifactName, String status) {
        String message = String.format("Artifact %s: %s", artifactName, status);
        printDeploymentMessage(message);
    }

    /**
     * Print professional JMX monitoring message
     */
    public void printJmxMessage(String jmxInfo) {
        String message = String.format("Professional JMX monitoring: %s", jmxInfo);
        printInfoMessage(message);
    }

    /**
     * Print professional feature activation message
     */
    public void printFeatureMessage(String featureName, boolean enabled) {
        String status = enabled ? "enabled" : "disabled";
        String message = String.format("Professional feature %s: %s", featureName, status);
        printInfoMessage(message);
    }

    /**
     * Clear main console
     */
    public void clearConsole() {
        if (mainConsoleView != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                mainConsoleView.clear();
            });
        }
    }

    /**
     * Clear specific console by name
     */
    public void clearConsole(String tabName) {
        ConsoleView console = getConsole(tabName);
        if (console != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                console.clear();
            });
        }
    }

    /**
     * Check if main console is available
     */
    public boolean hasMainConsole() {
        return mainConsoleView != null;
    }

    /**
     * Check if specific console is available
     */
    public boolean hasConsole(String tabName) {
        return consoleViews.containsKey(tabName);
    }

    /**
     * Get all registered console names
     */
    public String[] getConsoleNames() {
        return consoleViews.keySet().toArray(new String[0]);
    }

    /**
     * Get console count for monitoring
     */
    public int getConsoleCount() {
        return consoleViews.size();
    }

    /**
     * Get project instance
     */
    @NotNull
    public Project getProject() {
        return project;
    }

    /**
     * Get professional console manager status
     */
    public String getManagerStatus() {
        StringBuilder status = new StringBuilder();
        status.append("DevTomcat Console Manager Status: ");
        status.append(consoleViews.size()).append(" consoles active");

        if (hasMainConsole()) {
            status.append(", Main console available");
        }

        return status.toString();
    }

    /**
     * Print professional session summary
     */
    public void printSessionSummary(String configName, long sessionDuration,
                                    int errorCount, int warningCount) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n=== DevTomcat Professional Session Summary ===\n");
        summary.append("Configuration: ").append(configName).append("\n");
        summary.append("Session Duration: ").append(sessionDuration).append(" ms\n");
        summary.append("Errors: ").append(errorCount).append("\n");
        summary.append("Warnings: ").append(warningCount).append("\n");
        summary.append("=== End Summary ===\n");

        printToServerConsole(summary.toString(), ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Print professional performance metrics
     */
    public void printPerformanceMetrics(String metrics) {
        String message = String.format("Professional Performance Metrics: %s", metrics);
        printInfoMessage(message);
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        consoleViews.clear();
        mainConsoleView = null;
        System.out.println("DevTomcat: Console manager disposed");
    }

    /**
     * Get current timestamp for professional logging
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    /**
     * Print raw message without formatting (for special cases)
     */
    public void printRawMessage(String message, ConsoleViewContentType contentType) {
        printToServerConsole(message, contentType);
    }

    /**
     * Print professional configuration message
     */
    public void printConfigurationMessage(String configInfo) {
        String message = String.format("Professional configuration: %s", configInfo);
        printInfoMessage(message);
    }

    /**
     * Print professional monitoring message
     */
    public void printMonitoringMessage(String monitoringInfo) {
        String message = String.format("Professional monitoring: %s", monitoringInfo);
        printInfoMessage(message);
    }
}