package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.TomcatErrorDiagnostics;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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
        private final AtomicInteger errorCount;
        private final AtomicInteger warningCount;
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
            analyzer.analyze(text, context);
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
        if (context.jmxEnabled) {
            analyzers.add(new JmxAnalyzer());
        }
        analyzers.add(new DiagnosticsAnalyzer());
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

                    // Mark remaining artifacts as deployed. DeploymentAnalyzer already fired
                    // onArtifactDeployed for each artifact whose individual completion message
                    // was logged; skip those to avoid double-firing the same notification.
                    // This is resilient to Tomcat version differences in deployment log format.
                    for (String artifactName : new java.util.LinkedHashSet<>(ctx.contextToArtifactName.values())) {
                        if (ctx.notifiedArtifacts.add(artifactName)) {
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
                    ctx.logger.logDeploymentSuccess(artifactName, duration);
                    ctx.deployedArtifactCount.incrementAndGet();
                    ctx.notifiedArtifacts.add(artifactName);
                    ctx.lifecycleListener.onArtifactDeployed(ctx.configName, artifactName);
                    ctx.onContextReady.accept(contextName);
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
            }
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
                ctx.errorCount.incrementAndGet();
                ctx.logger.logServerError(text);
                ctx.lifecycleListener.onError(ctx.configName);
            } else if (WARNING_PATTERN.matcher(text).find()) {
                ctx.warningCount.incrementAndGet();
                ctx.logger.logServerWarning(text);
                ctx.lifecycleListener.onWarning(ctx.configName);
            }
        }
    }
}
