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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes structured deployment / server-lifecycle messages to an IntelliJ
 * {@link ConsoleView} (the run console) and the platform's {@code idea.log}.
 *
 * <p><b>Threading.</b> The console-view reference is read off arbitrary
 * background threads (deploy / sync / update orchestrators), but the actual
 * {@link ConsoleView#print} call must run on EDT. {@link #logWithType}
 * captures the field into a local before submitting an {@code invokeLater}
 * — without that, a concurrent {@link #dispose} could null the field
 * between the null check and the lambda firing (TOCTOU → NPE).
 *
 * <p><b>idea.log severity.</b> Tomcat {@code ERROR_OUTPUT} lines reach
 * {@code idea.log} via {@code LOG.warn}, not {@code LOG.error}.
 * {@code LOG.error} causes IntelliJ to surface a SEVERE notification blamed
 * on this plugin — wrong attribution for downstream output we're only
 * relaying. {@link #logPluginError} exists for the "the plugin itself is
 * failing" case so post-mortem readers can grep the {@code DevTomcat:}
 * label without false positives from Tomcat-side output.
 *
 * <p><b>Registry-driven.</b> Timestamps and debug-mode stack traces are
 * controlled by the registry keys {@code devtomcat.log.show.timestamps}
 * and {@code devtomcat.debug.mode}.
 *
 * @author Gezahegn Lemma (Gezu)
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

    public TomcatDeploymentLogger(@NotNull Project project) {
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

    public void setConsoleView(@Nullable ConsoleView consoleView) {
        this.consoleView = consoleView;
        if (consoleView != null) {
            LOG.debug("Console view attached to deployment logger");
        }
    }

    // =====================================================================
    // DEPLOYMENT LIFECYCLE LOGGING
    // =====================================================================

    public void logDeploymentStart(@NotNull String artifactName) {
        String message = String.format("%s Deploying artifact '%s'...", DEPLOYMENT_PREFIX, artifactName);
        logWithType(message, ConsoleViewContentType.SYSTEM_OUTPUT);
        LOG.info("Deployment started for artifact: " + artifactName);
    }

    /**
     * @param durationMs deployment duration in milliseconds; negative values are clamped to 0
     *                   (a logging method must never fail the caller's flow over a measurement bug)
     */
    public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {
        long safeDuration = Math.max(0, durationMs);
        String message = String.format(
                "%s Artifact '%s' deployed successfully (took %d ms)",
                DEPLOYMENT_PREFIX, artifactName, safeDuration
        );
        logWithType(message, ConsoleViewContentType.NORMAL_OUTPUT);
        LOG.info("Deployment successful for artifact: " + artifactName + " (" + safeDuration + "ms)");
    }

    // =====================================================================
    // SERVER LIFECYCLE LOGGING
    // =====================================================================

    /**
     * @param startupTimeMs server startup duration in milliseconds; negative values clamped to 0
     */
    public void logServerStartup(long startupTimeMs) {
        long safeTime = Math.max(0, startupTimeMs);
        String message = String.format("Server started successfully in %d ms", safeTime);
        logWithType(message, ConsoleViewContentType.NORMAL_OUTPUT);
        LOG.info("Server startup completed in " + safeTime + "ms");
    }

    /**
     * Server-output category aliases for {@link #logInfo} / {@link #logWarning} /
     * {@link #logError}. The split is naming-only — call-site intent reads more
     * clearly when relaying Tomcat output specifically.
     */
    public void logServerInfo(@NotNull String message) {
        logInfo(message);
    }

    public void logServerWarning(@NotNull String message) {
        logWarning(message);
    }

    public void logServerError(@NotNull String message) {
        logError(message);
    }

    // =====================================================================
    // GENERAL MESSAGE LOGGING
    // =====================================================================

    public void logInfo(@NotNull String message) {
        logPrefixed(message, INFO_PREFIX, ConsoleViewContentType.NORMAL_OUTPUT);
        LOG.debug("Info: " + message);
    }

    public void logWarning(@NotNull String message) {
        logPrefixed(message, WARNING_PREFIX, ConsoleViewContentType.LOG_WARNING_OUTPUT);
        LOG.warn("Warning: " + message);
    }

    public void logError(@NotNull String message) {
        logPrefixed(message, ERROR_PREFIX, ConsoleViewContentType.ERROR_OUTPUT);
        // Use LOG.warn() — these are Tomcat output messages, not plugin errors.
        // LOG.error() causes IntelliJ to report SEVERE and blame the plugin.
        LOG.warn("Tomcat error: " + message);
    }

    /**
     * Plugin-side error (pre-launch check, STRICT port refusal, etc.) — distinct
     * from {@link #logError} so the {@code idea.log} label reads {@code DevTomcat:}
     * instead of {@code Tomcat error:}, and post-mortem readers can tell apart
     * "we failed" from "the server emitted an error line."
     */
    public void logPluginError(@NotNull String message) {
        logPrefixed(message, ERROR_PREFIX, ConsoleViewContentType.ERROR_OUTPUT);
        LOG.warn("DevTomcat: " + message);
    }

    /**
     * Adds the exception's class + message to the console line. In debug mode
     * also dumps the full stack (cycle-safe via identity-set in
     * {@link #appendThrowable}).
     */
    public void logError(@NotNull String message, @NotNull Throwable throwable) {
        String errorMessage = message + " - " + throwable.getClass().getSimpleName()
                + ": " + throwable.getMessage();

        logError(errorMessage);

        if (debugMode) {
            String stackTrace = getStackTraceString(throwable);
            logDebug("Stack trace:\n" + stackTrace);
            LOG.debug("Full stack trace:\n" + stackTrace);
        }

        LOG.warn("Tomcat exception: " + throwable.getMessage());
    }

    /** Silent no-op when debug mode is disabled — no console line, no idea.log entry. */
    public void logDebug(@NotNull String message) {
        if (debugMode) {
            logPrefixed(message, DEBUG_PREFIX, ConsoleViewContentType.LOG_DEBUG_OUTPUT);
            LOG.debug("Debug: " + message);
        }
    }

    // =====================================================================
    // PROGRESS LOGGING
    // =====================================================================

    // Package-private + static for TomcatDeploymentLoggerTest; uses no instance state.
    @NotNull
    static String createProgressBar(int progress) {
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
     * Prepends {@code prefix} to {@code message} unless {@code message} already
     * starts with the same prefix. The skip-on-duplicate path matters when a
     * caller (typically the Tomcat output relay) hands us a line that already
     * carries our own category bracket — without this check, {@code logInfo}
     * on a line like {@code "[INFO] foo"} produced {@code "[INFO] [INFO] foo"}
     * in the console.
     */
    private void logPrefixed(@NotNull String message, @NotNull String prefix,
                             @NotNull ConsoleViewContentType contentType) {
        logWithType(applyCategoryPrefix(message, prefix), contentType);
    }

    /**
     * Prepends {@code prefix} to {@code message} unless the message already
     * starts with that exact prefix. Guards the double-prefix bug: a relayed
     * Tomcat line that already carries our category bracket (e.g.
     * {@code "[INFO] foo"}) must not become {@code "[INFO] [INFO] foo"}. A
     * different leading bracket is not treated as a duplicate.
     * Package-private + static for {@code TomcatDeploymentLoggerTest}.
     */
    @NotNull
    static String applyCategoryPrefix(@NotNull String message, @NotNull String prefix) {
        return message.startsWith(prefix) ? message : prefix + " " + message;
    }

    private void logWithType(@NotNull String message, @NotNull ConsoleViewContentType contentType) {
        if (disposed.get()) {
            LOG.debug("Logger is disposed, ignoring message: " + message);
            return;
        }

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

    @NotNull
    private String formatMessage(@NotNull String message) {
        StringBuilder formatted = new StringBuilder();

        if (showTimestamps) {
            formatted.append("[")
                    .append(LocalDateTime.now().format(TIMESTAMP_FORMAT))
                    .append("] ");
        }

        // Skip the "[DevTomcat]" brand prefix when the message already carries a
        // category bracket (e.g. "[INFO] ..." from logPrefixed, or "[DEPLOY] ..."
        // from the deployment-lifecycle methods) — they're already self-identifying.
        if (!message.startsWith("[")) {
            formatted.append(PREFIX).append(" ");
        }

        formatted.append(message);

        return formatted.toString();
    }

    // Package-private + static for TomcatDeploymentLoggerTest; uses no instance state.
    @NotNull
    static String getStackTraceString(@NotNull Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        appendThrowable(sb, throwable);
        return sb.toString();
    }

    /** Cycle-safe: an exception whose {@code getCause()} chain revisits an earlier link in the chain stops at the second visit instead of looping forever. */
    private static void appendThrowable(@NotNull StringBuilder sb, @NotNull Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
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

    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    @NotNull
    public Project getProject() {
        return project;
    }

    // =====================================================================
    // LIFECYCLE MANAGEMENT
    // =====================================================================

    /** Idempotent. After disposal, all logging attempts are silently dropped. */
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            consoleView = null;
            LOG.debug("TomcatDeploymentLogger disposed");
        }
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    private static boolean getRegistryBoolean(@NotNull String key, boolean defaultValue) {
        try {
            return Registry.is(key);
        } catch (Exception e) {
            LOG.debug("Registry key not found: " + key + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /** Debugging helper — fields summary suitable for ad-hoc inspection. */
    @NotNull
    public String getStatus() {
        return String.format(
                "TomcatDeploymentLogger{project='%s', disposed=%s, debugMode=%s, timestamps=%s, elapsed=%dms}",
                project.getName(), disposed.get(), debugMode, showTimestamps, getElapsedTime()
        );
    }
}
