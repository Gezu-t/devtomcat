package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.TomcatErrorDiagnostics;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composable pipeline for analyzing Tomcat process output.
 *
 * <p>Each line of output passes through an ordered list of {@link Analyzer} instances.
 * Analyzers are independent — every analyzer sees every line, so concerns like startup
 * detection, deployment tracking, and error counting are cleanly separated.
 *
 * <p>Built via {@link #create(Context)} which assembles the standard set of analyzers.
 * Custom pipelines can be constructed for testing or specialized use cases.
 *
 * @see TomcatProcessHandler
 */
public final class TomcatOutputPipeline {

    private static final Logger LOG = Logger.getInstance(TomcatOutputPipeline.class);

    /**
     * Abstraction over logging so the pipeline can be tested without
     * requiring IntelliJ's {@code Project} or {@code ConsoleView}.
     */
    public interface PipelineLogger {
        void logServerStartup(long durationMs);
        void logDeploymentSuccess(@NotNull String artifactName, long durationMs);
        void logServerInfo(@NotNull String message);
        void logServerError(@NotNull String message);
        void logServerWarning(@NotNull String message);

        /**
         * Called by {@link DiagnosticsAnalyzer} when a Tomcat error pattern matches
         * and the diagnostic carries a non-null {@code quickFixId} — i.e. one of
         * the four classes of failure with a known navigation target
         * (Port Conflict, Missing Class, Out of Memory, Java Version Mismatch).
         *
         * <p>Default no-op so headless test fixtures and any callers that do not
         * want the IDE balloon UX can opt out by omitting the override. The
         * production {@link TomcatProcessHandler} routes this to a
         * {@code DiagnosticBalloonRouter} which dedupes and pops a balloon
         * notification with an "Open Run Configuration" action.
         */
        default void onActionableDiagnostic(@NotNull TomcatErrorDiagnostics.Diagnostic diagnostic) {}

        /**
         * Called by {@link ContextFailureRootCauseAnalyzer} when a Tomcat context
         * fails to start AND the analyzer captured the deepest exception class +
         * message from the preceding stack chain. Whatever the user's webapp
         * actually threw — any framework, any third-party library, any of their
         * own classes — is surfaced verbatim. We do not maintain a list of
         * libraries we "know about"; the contract is purely structural: any
         * exception chain that propagates up to a Tomcat context-failure line.
         *
         * <p>Default no-op. The production listener pops a balloon via
         * {@link com.dev.idea.plugins.tomcat.utils.TomcatNotifier} so the user
         * sees the real root cause without scrolling the catalina log.
         */
        default void onStartupRootCause(@NotNull String exceptionClass, @NotNull String message) {}
    }

    /**
     * Shared context for all analyzers in the pipeline.
     * Provides access to logging, status updates, and shared mutable state.
     */
    public static final class Context {
        private final PipelineLogger logger;
        private final TomcatLifecycleListener lifecycleListener;
        private final String configName;
        private final Map<String, String> contextToArtifactName;
        private final AtomicBoolean serverStartupDetected;
        private final AtomicInteger deployedArtifactCount;
        /**
         * Artifact display names already notified via {@link TomcatLifecycleListener#onArtifactDeployed}
         * by {@link DeploymentAnalyzer}. {@link StartupAnalyzer} skips these to avoid double-firing.
         */
        final Set<String> notifiedArtifacts = ConcurrentHashMap.newKeySet();
        /** Artifact display names already marked failed. */
        final Set<String> failedArtifacts = ConcurrentHashMap.newKeySet();
        private final AtomicInteger errorCount;
        private final AtomicInteger warningCount;
        /** Set when shutdown begins — suppresses error/warning counter increments
         *  so Tomcat's classloader cleanup noise doesn't inflate the dashboard badge. */
        private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        /**
         * Authoritative signal from Tomcat that at least one artifact failed to
         * deploy — set by {@link ServerDeploymentSummaryFailureAnalyzer} when it
         * matches Tomcat's server-level summary messages ("One or more Contexts
         * did not start successfully" and peers). Consumed by
         * {@link StartupAnalyzer}: when it's true at startup time, artifacts
         * with no per-artifact deploy/fail log are resolved as FAILED rather
         * than DEPLOYED, so the Services tree never renders green for a
         * partially-broken deployment.
         *
         * <p>Not gated on the generic error counter — Tomcat routinely logs
         * non-fatal SEVERE lines on healthy startups (JDBC driver registration
         * noise, JULI warnings), and using that as a proxy would flip clean
         * deploys to FAILED. Only Tomcat's own deployment-summary failure
         * messages set this flag.
         */
        final AtomicBoolean deploymentFailureDetected = new AtomicBoolean(false);
        private final boolean jmxEnabled;

        /** Called with startup duration when server startup is detected. */
        private final LongConsumer onStartupDetected;
        /** Called after startup detection for post-startup actions (remote deploy, browser). */
        private final Runnable onPostStartup;
        /** Called when Tomcat reports a specific context as deployed/initialized. */
        private final Consumer<String> onContextReady;

        public Context(@NotNull PipelineLogger logger,
                       @NotNull TomcatLifecycleListener lifecycleListener,
                       @NotNull String configName,
                       @NotNull Map<String, String> contextToArtifactName,
                       @NotNull AtomicBoolean serverStartupDetected,
                       @NotNull AtomicInteger deployedArtifactCount,
                       @NotNull AtomicInteger errorCount,
                       @NotNull AtomicInteger warningCount,
                       boolean jmxEnabled,
                       @NotNull LongConsumer onStartupDetected,
                       @NotNull Runnable onPostStartup,
                       @NotNull Consumer<String> onContextReady) {
            this.logger = logger;
            this.lifecycleListener = lifecycleListener;
            this.configName = configName;
            this.contextToArtifactName = contextToArtifactName;
            this.serverStartupDetected = serverStartupDetected;
            this.deployedArtifactCount = deployedArtifactCount;
            this.errorCount = errorCount;
            this.warningCount = warningCount;
            this.jmxEnabled = jmxEnabled;
            this.onStartupDetected = onStartupDetected;
            this.onPostStartup = onPostStartup;
            this.onContextReady = onContextReady;
        }

        /** Signals that shutdown has begun — error/warning counters freeze. */
        public void markShuttingDown() {
            shuttingDown.set(true);
        }
    }

    /** A single-concern output analyzer. */
    @FunctionalInterface
    public interface Analyzer {
        void analyze(@NotNull String text, @NotNull Context ctx);
    }

    private final List<Analyzer> analyzers;

    TomcatOutputPipeline(@NotNull List<Analyzer> analyzers) {
        this.analyzers = List.copyOf(analyzers);
    }

    /**
     * Processes a single line of output through all analyzers.
     */
    public void processLine(@NotNull String text, @NotNull Context context) {
        if (text.isEmpty()) return;
        for (Analyzer analyzer : analyzers) {
            try {
                analyzer.analyze(text, context);
            } catch (Throwable t) {
                // One analyzer must not silence the others — without isolation a
                // malformed line could derail startup detection, deployment
                // tracking, or error counting downstream of the failure.
                LOG.warn("Analyzer " + analyzer.getClass().getSimpleName() + " threw on line: " + text, t);
            }
        }
    }

    /**
     * Creates the standard Tomcat output pipeline with all built-in analyzers.
     */
    @NotNull
    public static TomcatOutputPipeline create(@NotNull Context context) {
        List<Analyzer> analyzers = new ArrayList<>();
        analyzers.add(new StartupAnalyzer());
        analyzers.add(new DeploymentAnalyzer());
        analyzers.add(new ContextAnalyzer());
        analyzers.add(new ReloadAnalyzer());
        analyzers.add(new ArtifactFailureAnalyzer());
        analyzers.add(new ServerDeploymentSummaryFailureAnalyzer());
        if (context.jmxEnabled) {
            analyzers.add(new JmxAnalyzer());
        }
        analyzers.add(new DiagnosticsAnalyzer());
        analyzers.add(new ContextFailureRootCauseAnalyzer());
        analyzers.add(new ErrorWarningAnalyzer());
        return new TomcatOutputPipeline(analyzers);
    }

    // --- Built-in Analyzers ---

    /**
     * Detects Tomcat's "Server startup in N ms" message.
     * Fires only once (guarded by AtomicBoolean).
     * Triggers startup time tracking and post-startup callbacks.
     */
    static final class StartupAnalyzer implements Analyzer {
        // Tomcat may locale-format large numbers: [12,345] milliseconds
        private static final Pattern STARTUP_PATTERN = Pattern.compile(
                "(?i).*server startup in \\[?([\\d,._]+)\\]?\\s*(?:ms|milliseconds).*");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            Matcher m = STARTUP_PATTERN.matcher(text);
            if (m.find() && ctx.serverStartupDetected.compareAndSet(false, true)) {
                try {
                    long duration = Long.parseLong(m.group(1).replaceAll("[,._]", ""));
                    ctx.logger.logServerStartup(duration);

                    // Tomcat does not log per-artifact completion consistently across
                    // versions. Clean startup → unresolved artifacts are treated as
                    // deployed. If Tomcat emitted a summary-failure message
                    // (deploymentFailureDetected), unresolved artifacts are treated
                    // as failed instead, so the Services tree does not render green
                    // for a broken deployment.
                    boolean deploymentFailed = ctx.deploymentFailureDetected.get();
                    for (String artifactName : new LinkedHashSet<>(ctx.contextToArtifactName.values())) {
                        boolean alreadyResolved = ctx.notifiedArtifacts.contains(artifactName)
                                || ctx.failedArtifacts.contains(artifactName);
                        if (alreadyResolved) {
                            continue;
                        }

                        if (deploymentFailed) {
                            if (ctx.failedArtifacts.add(artifactName)) {
                                ctx.lifecycleListener.onArtifactFailed(ctx.configName, artifactName);
                            }
                        } else if (ctx.notifiedArtifacts.add(artifactName)) {
                            ctx.lifecycleListener.onArtifactDeployed(ctx.configName, artifactName);
                        }
                    }

                    ctx.lifecycleListener.onServerStarted(ctx.configName, duration);
                    ctx.onStartupDetected.accept(duration);
                    ctx.onPostStartup.run();
                } catch (NumberFormatException e) {
                    LOG.debug("Could not parse startup time from: " + m.group(1));
                }
            }
        }
    }

    /**
     * Detects per-artifact deployment completion messages from Tomcat's deployer.
     * Maps Tomcat context names back to artifact display names.
     */
    static final class DeploymentAnalyzer implements Analyzer {
        // Tomcat uses locale-formatted numbers: [2,404] ms or [2.404] ms
        private static final Pattern DESCRIPTOR_DEPLOYED_PATTERN = Pattern.compile(
                "Deployment of (?:deployment descriptor|web application archive) " +
                "\\[.*?([^/\\\\]+)\\.(?:xml|war)\\] has finished in \\[([\\d,._]+)\\] ms");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            Matcher m = DESCRIPTOR_DEPLOYED_PATTERN.matcher(text);
            if (m.find()) {
                try {
                    String contextName = m.group(1);
                    // Strip locale separators (commas, periods, underscores) before parsing
                    long duration = Long.parseLong(m.group(2).replaceAll("[,._]", ""));
                    String artifactName = ctx.contextToArtifactName.getOrDefault(contextName, contextName);
                    // Guard with atomic add() — prevents duplicate notifications if two
                    // threads process the same context name concurrently
                    if (ctx.notifiedArtifacts.add(artifactName)) {
                        ctx.logger.logDeploymentSuccess(artifactName, duration);
                        ctx.deployedArtifactCount.incrementAndGet();
                        ctx.lifecycleListener.onArtifactDeployed(ctx.configName, artifactName);
                        ctx.onContextReady.accept(contextName);
                    }
                } catch (NumberFormatException e) {
                    LOG.debug("Could not parse deployment duration from: " + m.group(2));
                }
            }
        }
    }

    /**
     * Detects context initialization/deployment log messages.
     */
    static final class ContextAnalyzer implements Analyzer {
        private static final Pattern CONTEXT_PATTERN = Pattern.compile(
                "(?i).*context\\s+\\[([^\\]]+)\\].*(?:started|deployed|initialized).*");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            Matcher m = CONTEXT_PATTERN.matcher(text);
            if (m.find()) {
                String contextName = m.group(1);
                ctx.logger.logServerInfo("Context deployed: " + contextName);
                ctx.onContextReady.accept(contextName);
            }
        }
    }

    /**
     * Detects context reload events for hot-deployed artifacts.
     */
    static final class ReloadAnalyzer implements Analyzer {
        private static final Pattern RELOAD_PATTERN = Pattern.compile(
                "(?i)Reloading Context with name \\[([^\\]]+)\\] (?:is completed|has started)");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            Matcher m = RELOAD_PATTERN.matcher(text);
            if (m.find()) {
                String rawCtx = m.group(1);
                String normalizedCtx = rawCtx.startsWith("/") ? rawCtx.substring(1) : rawCtx;
                String artifactName = ctx.contextToArtifactName.getOrDefault(normalizedCtx, rawCtx);
                ctx.logger.logServerInfo("Auto-reloaded: " + artifactName);
                if (text.contains("has started")) {
                    ctx.lifecycleListener.onArtifactReloading(ctx.configName, artifactName);
                } else {
                    ctx.lifecycleListener.onArtifactDeployed(ctx.configName, artifactName);
                }
            }
        }
    }

    /**
     * Detects per-artifact deployment failures from Tomcat startup logs.
     */
    static final class ArtifactFailureAnalyzer implements Analyzer {
        private static final Pattern DEPLOY_FAILURE_PATTERN = Pattern.compile(
                "(?i)Error deploying (?:deployment descriptor|web application(?: archive| directory)?)\\s*\\[.*?([^/\\\\\\]]+?)(?:\\.(?:xml|war))?\\]");
        // "Context [/X] startup failed due to previous errors"
        private static final Pattern CONTEXT_STARTUP_FAILED_PATTERN = Pattern.compile(
                "(?i)Context \\[(/[^\\]]+)\\] startup failed due to previous errors");
        // "Failed to start component [...StandardContext[/X]]" — Tomcat sometimes
        // only logs this form, with no separate "Context [...] startup failed"
        // line, so a CONTEXT_STARTUP_FAILED-only pattern would miss the failure.
        private static final Pattern COMPONENT_FAILED_PATTERN = Pattern.compile(
                "(?i)Failed to start component \\[.*?StandardContext\\[(/[^\\]]+)\\]");
        // "LifecycleException ... StandardContext[/X]" — wrapping exception form.
        private static final Pattern LIFECYCLE_EXCEPTION_PATTERN = Pattern.compile(
                "(?i)LifecycleException[^\\n]*StandardContext\\[(/[^\\]]+)\\]");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            String artifactName = resolveFailedArtifactName(text, ctx);
            if (artifactName == null || artifactName.isBlank()) {
                return;
            }
            if (ctx.failedArtifacts.add(artifactName)) {
                ctx.lifecycleListener.onArtifactFailed(ctx.configName, artifactName);
            }
        }

        @Nullable
        private static String resolveFailedArtifactName(@NotNull String text, @NotNull Context ctx) {
            Matcher deployFailure = DEPLOY_FAILURE_PATTERN.matcher(text);
            if (deployFailure.find()) {
                String contextName = deployFailure.group(1);
                return ctx.contextToArtifactName.getOrDefault(contextName, contextName);
            }
            String resolved = matchContextName(CONTEXT_STARTUP_FAILED_PATTERN, text, ctx);
            if (resolved != null) return resolved;
            resolved = matchContextName(COMPONENT_FAILED_PATTERN, text, ctx);
            if (resolved != null) return resolved;
            resolved = matchContextName(LIFECYCLE_EXCEPTION_PATTERN, text, ctx);
            if (resolved != null) return resolved;

            return null;
        }

        @Nullable
        private static String matchContextName(@NotNull Pattern p, @NotNull String text, @NotNull Context ctx) {
            Matcher m = p.matcher(text);
            if (!m.find()) return null;
            String rawContext = m.group(1);
            String normalized = rawContext.startsWith("/") ? rawContext.substring(1) : rawContext;
            return ctx.contextToArtifactName.getOrDefault(normalized, rawContext);
        }
    }

    /**
     * Catches Tomcat's server-level deployment-summary failure signals —
     * messages like {@code "One or more Contexts did not start successfully"}
     * that Tomcat emits when at least one web application failed to start,
     * but whose per-context detail lines didn't match
     * {@link ArtifactFailureAnalyzer}. Without this fallback a partially-
     * failed deployment slipped past the status service as all-success and
     * the Services panel reported RUNNING for a broken config.
     *
     * <p>Fires at most once per launch: the first matching line sets the
     * sticky {@code reported} flag so repeated summary messages (Tomcat
     * sometimes logs it multiple times across threads) don't spam listeners.
     */
    static final class ServerDeploymentSummaryFailureAnalyzer implements Analyzer {
        private static final Pattern SUMMARY_FAILURE_PATTERN = Pattern.compile(
                "(?i)"
                        + "(?:one or more contexts did not start successfully"
                        + "|one or more listeners failed to start"
                        + "|full application server startup failed"
                        + "|server startup failed)");

        private final AtomicBoolean reported = new AtomicBoolean(false);

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            if (reported.get()) return;
            if (SUMMARY_FAILURE_PATTERN.matcher(text).find()
                    && reported.compareAndSet(false, true)) {
                // Flip the shared failure flag first so StartupAnalyzer sees it
                // if the summary message arrives on the same line-processing
                // pass or any subsequent one. Only after that do we notify
                // listeners — prevents a race where the lifecycle callback
                // refreshes UI before the analyzer knows a failure occurred.
                ctx.deploymentFailureDetected.set(true);
                ctx.lifecycleListener.onDeploymentSummaryFailed(ctx.configName);
            }
        }
    }

    /**
     * Detects JMX service activation messages.
     */
    static final class JmxAnalyzer implements Analyzer {
        private static final Pattern JMX_PATTERN = Pattern.compile(
                "(?i).*jmx.*(?:started|enabled|listening).*port\\s*(\\d+).*");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            Matcher m = JMX_PATTERN.matcher(text);
            if (m.find()) {
                ctx.logger.logServerInfo("JMX active on port " + m.group(1));
            }
        }
    }

    /**
     * Runs smart error diagnostics on every line, producing actionable hints
     * for common Tomcat errors (class version mismatches, missing TLDs, etc.).
     */
    static final class DiagnosticsAnalyzer implements Analyzer {
        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            List<TomcatErrorDiagnostics.Diagnostic> diagnostics = TomcatErrorDiagnostics.analyze(text);
            for (TomcatErrorDiagnostics.Diagnostic diag : diagnostics) {
                ctx.logger.logServerInfo(TomcatErrorDiagnostics.formatForConsole(diag));
                // Diagnostics carrying a quickFixId are the ones where we can
                // direct the user to a specific place to fix the problem (port,
                // classpath, memory, JRE). Promote those to a balloon so the
                // user does not have to spot the [CRITICAL] line in a flood of
                // console output. The PipelineLogger default is a no-op so
                // headless test fixtures stay quiet.
                if (diag.getQuickFixId() != null) {
                    ctx.logger.onActionableDiagnostic(diag);
                }
            }
        }
    }

    /**
     * Captures the deepest {@code Caused by:} root cause from any exception
     * chain that fires during startup, then surfaces it when a context-failure
     * signature appears. Purely structural — no list of known libraries, no
     * pattern-matching against specific package names. Whatever exception
     * propagated up to the failure trigger is what we surface.
     *
     * <p>The shape Tomcat emits on a failed context init looks like:
     * <pre>
     *   com.example.SomeException: top-level wrapper
     *           at com.example.Foo.bar(Foo.java:42)
     *           ... N more frames ...
     *   Caused by: com.example.AnotherException: middle of the chain
     *           ... more frames ...
     *   Caused by: java.io.IOException: the actual root cause
     *           ... more frames ...
     *   org.apache.catalina.LifecycleException: Failed to start component [...]
     *           ... more frames ...
     *   SEVERE: Context [...] startup failed due to previous errors
     * </pre>
     * The user has to scroll back through tens or hundreds of lines to find
     * the actual cause. This analyzer remembers the deepest {@code Caused by:}
     * exception header it has seen during the launch and surfaces it the
     * moment Tomcat reports the context failure — so the balloon shows the
     * real reason instead of the framework wrapper, regardless of which
     * framework, ORM, migration tool, JDBC driver, or app class threw.
     *
     * <p>State is per-analyzer-instance, which is per-launch (each pipeline
     * gets its own {@code create()}-built analyzer list). Buffer is capped at
     * {@link #BUFFER_CAPACITY} headers to avoid pathological growth on log
     * sprays.
     *
     * <p><b>Thread safety.</b> {@link com.intellij.execution.process.OSProcessHandler}
     * runs stdout and stderr on separate reader threads, and the pipeline does
     * not serialize {@link Analyzer#analyze} dispatch. The other analyzers in
     * this pipeline keep their mutable state on the shared {@link Context}
     * with thread-safe primitives ({@code AtomicBoolean}, {@code AtomicInteger},
     * {@code ConcurrentHashMap.newKeySet()}); this analyzer is the outlier
     * because its rolling exception-header buffer is genuinely per-launch
     * state with no shared-Context home. To stay correct under concurrent
     * stdout / stderr arrival, {@link #analyze} is {@code synchronized}.
     * The work inside the critical section is bounded ({@value #BUFFER_CAPACITY}
     * deque entries, one regex match per line) so the lock contention cost
     * is negligible.
     */
    static final class ContextFailureRootCauseAnalyzer implements Analyzer {
        // Match exception header lines. Accepts both top-level
        // ("foo.BarException: message") and chain links
        // ("Caused by: foo.BarException: message" / "Suppressed: foo.BarException").
        // Anchored to the start of the (trimmed) line so a passing mention
        // inside a regular message ("the Exception was ignored") never matches.
        private static final Pattern EXCEPTION_HEADER = Pattern.compile(
                "^(?:Caused by:\\s+|Suppressed:\\s+)?"
                        + "([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*"
                        + "(?:Exception|Error|Throwable))"
                        + "(?::\\s*(.+))?$");

        // Signatures Tomcat emits when a webapp context has failed to come up.
        // Matching any of these triggers the balloon — they all mean "the
        // user's webapp is the problem; look back for the actual cause".
        private static final Pattern CONTEXT_FAILURE = Pattern.compile(
                "LifecycleException:.*Failed to start component"
                        + "|Context\\s*\\[[^\\]]+\\]\\s+startup failed due to previous errors"
                        + "|One or more (?:Contexts did not start successfully|listeners failed to start"
                        + "|filters failed to start)");

        private static final int BUFFER_CAPACITY = 8;

        // Per-launch state — each pipeline owns its own analyzer instance.
        private final java.util.Deque<ExceptionHeader> recent = new java.util.ArrayDeque<>();
        private boolean balloonFired = false;

        private record ExceptionHeader(@NotNull String exceptionClass,
                                       @NotNull String message,
                                       boolean isCausedBy) {}

        @Override
        public synchronized void analyze(@NotNull String text, @NotNull Context ctx) {
            if (balloonFired) return;

            // Check the failure trigger FIRST. Tomcat's LifecycleException
            // wrapper line also matches the exception-header pattern (because
            // syntactically it is one), so checking it second would let it
            // get buffered without firing the balloon — the user would see
            // nothing despite Tomcat reporting an obvious failure. Order
            // matters: trigger first, then buffer fallback.
            if (CONTEXT_FAILURE.matcher(text).find()) {
                ExceptionHeader rootCause = pickRootCause();
                if (rootCause != null) {
                    ctx.logger.onStartupRootCause(rootCause.exceptionClass(), rootCause.message());
                }
                balloonFired = true;
                return;
            }

            // Otherwise, capture exception headers as they fly by so the
            // trigger has a stack to look back through.
            String trimmed = text.trim();
            Matcher h = EXCEPTION_HEADER.matcher(trimmed);
            if (h.matches()) {
                String exClass = h.group(1);
                String msg = h.group(2) != null ? h.group(2).trim() : "";
                boolean isCausedBy = trimmed.startsWith("Caused by:");
                recent.addLast(new ExceptionHeader(exClass, msg, isCausedBy));
                while (recent.size() > BUFFER_CAPACITY) {
                    recent.removeFirst();
                }
            }
        }

        /**
         * Prefer the last {@code Caused by:} we have seen — that is the deepest
         * link of the chain and almost always the real cause. Fall back to the
         * first exception header if no {@code Caused by:} appeared (single-level
         * exception, e.g. an OutOfMemoryError thrown directly from a listener).
         */
        @org.jetbrains.annotations.Nullable
        private ExceptionHeader pickRootCause() {
            ExceptionHeader lastCausedBy = null;
            ExceptionHeader firstAny = null;
            for (ExceptionHeader h : recent) {
                if (firstAny == null) firstAny = h;
                if (h.isCausedBy()) lastCausedBy = h;
            }
            return lastCausedBy != null ? lastCausedBy : firstAny;
        }
    }

    /**
     * Counts SEVERE/ERROR/FATAL and WARNING/WARN lines, updates the deployment
     * status service for dashboard refresh.
     */
    static final class ErrorWarningAnalyzer implements Analyzer {
        private static final Pattern ERROR_PATTERN = Pattern.compile(
                "\\b(?:SEVERE|ERROR|FATAL)\\b|" +
                "^\\s*Caused by:\\s|" +
                "^[a-zA-Z_$][a-zA-Z0-9_$.]*(?:Exception|Error)\\b");
        private static final Pattern WARNING_PATTERN = Pattern.compile(
                "\\b(?:WARNING|WARN)\\b");

        @Override
        public void analyze(@NotNull String text, @NotNull Context ctx) {
            if (ERROR_PATTERN.matcher(text).find()) {
                ctx.logger.logServerError(text);
                // Only increment counter while running — shutdown cleanup
                // errors (classloader, JDBC driver) are not actionable.
                if (!ctx.shuttingDown.get()) {
                    ctx.errorCount.incrementAndGet();
                    ctx.lifecycleListener.onError(ctx.configName);
                }
            } else if (WARNING_PATTERN.matcher(text).find()) {
                ctx.logger.logServerWarning(text);
                if (!ctx.shuttingDown.get()) {
                    ctx.warningCount.incrementAndGet();
                    ctx.lifecycleListener.onWarning(ctx.configName);
                }
            }
        }
    }
}
