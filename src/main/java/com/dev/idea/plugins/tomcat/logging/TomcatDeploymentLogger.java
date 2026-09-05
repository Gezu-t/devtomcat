package com.dev.idea.plugins.tomcat.logging;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.util.function.Consumer;

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

    /** Cap on pre-console buffering: a launch emits tens of lines, not thousands. */
    private static final int MAX_PENDING = 500;

    private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";
    private static final String REG_DEBUG_MODE = "devtomcat.debug.mode";

    // =====================================================================
    // INSTANCE FIELDS
    // =====================================================================

    @NotNull
    private final Project project;

    @Nullable
    private volatile ConsoleView consoleView;

    /**
     * Lines logged before a console exists. Launch preparation — port claim,
     * catalina.base assembly, artifact deployment, the preflight and launch-mode
     * notices — all runs from {@code createJavaParameters}, which the platform
     * calls before {@code createConsole}. Without this buffer those lines are
     * formatted, dropped on the floor, and survive only in {@code idea.log},
     * where the user has no reason to look. Replayed in order on attach.
     */
    private final List<PendingLine> pending = new ArrayList<>();

    /** Guards {@link #pending} together with {@link #consoleView} so replayed and
     *  live lines cannot interleave out of order. */
    private final Object consoleLock = new Object();

    /** Counted, not stored, once {@link #MAX_PENDING} is reached. */
    private int droppedBeforeConsole;

    private final long startTime;

    private final AtomicBoolean disposed;

    private final boolean showTimestamps;

    private final boolean debugMode;

    /**
     * How a console print reaches the UI thread. Production hands every print to
     * {@code Application.invokeLater}; tests substitute a direct dispatcher so
     * delivery is observable without an EDT. Injected rather than branched on
     * {@code ApplicationManager.getApplication() == null}, because whether an
     * Application exists depends on what else ran first in the test JVM — a
     * branch on it makes delivery assertions execution-order dependent.
     */
    private final Consumer<Runnable> uiDispatcher;

    /**
     * Console-only path abbreviation: the roots every launch path hangs off,
     * longest first, each rendered as the name a Tomcat user already knows
     * ({@code ${catalina.base}}, {@code ${catalina.home}}, {@code $PROJECT_DIR$},
     * {@code ~}). A prefix matches only at a path-separator boundary, so a sibling
     * such as {@code proj-other} is never cut into {@code $PROJECT_DIR$-other}.
     * {@code idea.log} keeps the full paths — only the console line is shortened.
     */
    private volatile List<PathRoot> pathRoots = List.of();

    private record PathRoot(@NotNull Pattern prefix, @NotNull String label) {}

    // =====================================================================
    // CONSTRUCTORS
    // =====================================================================

    public TomcatDeploymentLogger(@NotNull Project project) {
        this(project, TomcatDeploymentLogger::dispatchToUiThread);
    }

    /** Test seam — see {@link #uiDispatcher}. */
    TomcatDeploymentLogger(@NotNull Project project, @NotNull Consumer<Runnable> uiDispatcher) {
        this.uiDispatcher = uiDispatcher;
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
     * Declares the roots to abbreviate in console lines. Call as soon as the
     * run directory is known — before the first pre-launch line. Either path may
     * be {@code null}; the project directory and the user's home are always added.
     */
    public void setPathRoots(@Nullable Path catalinaBase, @Nullable Path catalinaHome) {
        List<String[]> raw = new ArrayList<>();
        if (catalinaBase != null) raw.add(new String[] {catalinaBase.toAbsolutePath().normalize().toString(), "${catalina.base}"});
        if (catalinaHome != null) raw.add(new String[] {catalinaHome.toAbsolutePath().normalize().toString(), "${catalina.home}"});
        String projectDir = project.getBasePath();
        if (projectDir != null && !projectDir.isEmpty()) raw.add(new String[] {projectDir, "$PROJECT_DIR$"});
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) raw.add(new String[] {home, "~"});
        // Longest prefix first, so a run directory under the home directory reads
        // as ${catalina.base}, not as ~/....
        raw.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
        List<PathRoot> roots = new ArrayList<>(raw.size());
        for (String[] r : raw) {
            String slash = r[0].replace('\\', '/');
            String alt = r[0].replace('/', '\\');
            String either = slash.equals(alt) ? Pattern.quote(slash)
                    : "(?:" + Pattern.quote(slash) + "|" + Pattern.quote(alt) + ")";
            roots.add(new PathRoot(Pattern.compile(either + "(?=[/\\\\]|$|[\\s'\"),;:])"), r[1]));
        }
        pathRoots = List.copyOf(roots);
    }

    /** Package-private for tests: the console form of {@code message}. */
    @NotNull
    String abbreviatePaths(@NotNull String message) {
        String out = message;
        for (PathRoot root : pathRoots) {
            out = root.prefix().matcher(out).replaceAll(Matcher.quoteReplacement(root.label()));
        }
        return out;
    }

    public void setConsoleView(@Nullable ConsoleView consoleView) {
        List<PendingLine> replay = List.of();
        int dropped = 0;
        synchronized (consoleLock) {
            this.consoleView = consoleView;
            if (consoleView != null && !pending.isEmpty()) {
                replay = new ArrayList<>(pending);
                pending.clear();
                dropped = droppedBeforeConsole;
                droppedBeforeConsole = 0;
            }
        }
        if (consoleView == null) return;

        LOG.debug("Console view attached to deployment logger");
        // Replayed first, and before this method returns, so anything logged
        // after the attach still lands after the launch-preparation lines.
        for (PendingLine line : replay) {
            printToConsole(consoleView, line.text(), line.type());
        }
        if (dropped > 0) {
            printToConsole(consoleView,
                    formatMessage(WARNING_PREFIX + " " + dropped
                            + " earlier message(s) exceeded the pre-console buffer and were dropped"),
                    ConsoleViewContentType.LOG_WARNING_OUTPUT);
        }
    }

    /** One buffered console line, already formatted at the time it was logged. */
    private record PendingLine(@NotNull String text, @NotNull ConsoleViewContentType type) {}

    /**
     * Formatted text of the lines still waiting for a console, oldest first.
     * Package-private for {@code TomcatDeploymentLoggerTest}: the replay itself
     * goes through {@code invokeLater}, so the buffer is what a test can observe
     * without pumping the EDT.
     */
    @NotNull
    List<String> pendingSnapshot() {
        synchronized (consoleLock) {
            return pending.stream().map(PendingLine::text).toList();
        }
    }

    /** Count of lines dropped after {@link #MAX_PENDING}. Package-private for tests. */
    int pendingOverflowCount() {
        synchronized (consoleLock) {
            return droppedBeforeConsole;
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

        // Read the console and decide buffer-vs-print under one lock: without it,
        // a line logged concurrently with setConsoleView can be printed before the
        // buffered lines that preceded it.
        ConsoleView cv;
        synchronized (consoleLock) {
            cv = consoleView;
            if (cv == null) {
                if (pending.size() < MAX_PENDING) {
                    pending.add(new PendingLine(formattedMessage, contentType));
                } else {
                    droppedBeforeConsole++;
                }
                return;
            }
        }
        printToConsole(cv, formattedMessage, contentType);
    }

    private void printToConsole(@NotNull ConsoleView cv, @NotNull String formattedMessage,
                                @NotNull ConsoleViewContentType contentType) {
        if (project.isDisposed()) return;
        uiDispatcher.accept(() -> {
            try {
                if (!disposed.get() && !project.isDisposed()) {
                    cv.print(formattedMessage + "\n", contentType);
                }
            } catch (Exception e) {
                LOG.warn("Failed to print to console", e);
            }
        });
    }

    /**
     * Console writes belong on the EDT. With no Application there is no EDT to
     * schedule onto — only reachable outside a running IDE — so the print runs on
     * the calling thread instead of throwing. Same rationale as
     * {@code TomcatReadActions.compute}.
     */
    private static void dispatchToUiThread(@NotNull Runnable print) {
        Application app = ApplicationManager.getApplication();
        if (app == null) {
            print.run();
        } else {
            app.invokeLater(print);
        }
    }

    @NotNull
    private String formatMessage(@NotNull String message) {
        message = abbreviatePaths(message);
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
            synchronized (consoleLock) {
                consoleView = null;
                pending.clear();
                droppedBeforeConsole = 0;
            }
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
