package com.dev.idea.plugins.tomcat.runner;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatOutputPipeline")
class TomcatOutputPipelineTest {

    private TomcatOutputPipeline.PipelineLogger logger;
    private AtomicBoolean startupDetected;
    private AtomicInteger deployedCount;
    private AtomicInteger errorCount;
    private AtomicInteger warningCount;
    private Map<String, String> contextToArtifact;
    private AtomicLong capturedStartupTime;
    private AtomicBoolean postStartupCalled;
    private AtomicReference<String> readyContext;
    private TomcatOutputPipeline.Context context;

    @BeforeEach
    void setUp() {
        logger = new TomcatOutputPipeline.PipelineLogger() {
            @Override public void logServerStartup(long durationMs) {}
            @Override public void logDeploymentSuccess(@NotNull String name, long ms) {}
            @Override public void logServerInfo(@NotNull String msg) {}
            @Override public void logServerError(@NotNull String msg) {}
            @Override public void logServerWarning(@NotNull String msg) {}
        };
        startupDetected = new AtomicBoolean(false);
        deployedCount = new AtomicInteger(0);
        errorCount = new AtomicInteger(0);
        warningCount = new AtomicInteger(0);
        contextToArtifact = new ConcurrentHashMap<>();
        capturedStartupTime = new AtomicLong(-1);
        postStartupCalled = new AtomicBoolean(false);
        readyContext = new AtomicReference<>();

        context = new TomcatOutputPipeline.Context(
                logger, new TomcatLifecycleListener() {}, "testConfig",
                contextToArtifact, startupDetected, deployedCount,
                errorCount, warningCount, true,
                duration -> capturedStartupTime.set(duration),
                () -> postStartupCalled.set(true),
                readyContext::set
        );
    }

    @Nested
    @DisplayName("StartupAnalyzer")
    class StartupAnalyzerTests {

        private final TomcatOutputPipeline.StartupAnalyzer analyzer = new TomcatOutputPipeline.StartupAnalyzer();

        @Test
        @DisplayName("detects standard startup message")
        void detectsStartup() {
            analyzer.analyze("Server startup in 1234 ms", context);

            assertTrue(startupDetected.get());
            assertEquals(1234, capturedStartupTime.get());
            assertTrue(postStartupCalled.get());
        }

        @Test
        @DisplayName("detects startup with milliseconds suffix")
        void detectsStartupMilliseconds() {
            analyzer.analyze("Server startup in 567 milliseconds", context);

            assertTrue(startupDetected.get());
            assertEquals(567, capturedStartupTime.get());
        }

        @Test
        @DisplayName("detects Tomcat 8+ bracketed format")
        void detectsBracketedFormat() {
            analyzer.analyze("INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [1456] milliseconds", context);

            assertTrue(startupDetected.get());
            assertEquals(1456, capturedStartupTime.get());
            assertTrue(postStartupCalled.get());
        }

        @Test
        @DisplayName("detects bracketed format with ms")
        void detectsBracketedMs() {
            analyzer.analyze("Server startup in [892] ms", context);

            assertTrue(startupDetected.get());
            assertEquals(892, capturedStartupTime.get());
        }

        @Test
        @DisplayName("fires only once")
        void firesOnlyOnce() {
            analyzer.analyze("Server startup in 100 ms", context);
            capturedStartupTime.set(-1);
            postStartupCalled.set(false);

            analyzer.analyze("Server startup in 200 ms", context);

            assertEquals(-1, capturedStartupTime.get(), "Should not fire again");
            assertFalse(postStartupCalled.get());
        }

        @Test
        @DisplayName("ignores non-startup lines")
        void ignoresOtherLines() {
            analyzer.analyze("INFO: Deploying web application", context);

            assertFalse(startupDetected.get());
            assertEquals(-1, capturedStartupTime.get());
        }

        @Test
        @DisplayName("marks unresolved artifacts failed when Tomcat reported a summary-level deployment failure")
        void startupAfterSummaryFailureMarksUnresolvedArtifactsFailed() {
            contextToArtifact.put("webapp-deploy", "webapp-deploy:war exploded");
            List<String> failedArtifacts = new ArrayList<>();
            List<String> deployedArtifacts = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                            failedArtifacts.add(artifactName);
                        }

                        @Override
                        public void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {
                            deployedArtifacts.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    false,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            new TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer().analyze(
                    "SEVERE One or more Contexts did not start successfully", testContext);
            analyzer.analyze("Server startup in 1234 ms", testContext);

            assertEquals(List.of("webapp-deploy:war exploded"), failedArtifacts);
            assertTrue(deployedArtifacts.isEmpty(), "Startup fallback must not mark failed deployments as deployed");
        }

        @Test
        @DisplayName("non-fatal SEVERE noise on a clean startup does not flip unresolved artifacts to failed")
        void nonFatalErrorsDoNotPoisonCleanStartup() {
            contextToArtifact.put("webapp-deploy", "webapp-deploy:war exploded");
            List<String> failedArtifacts = new ArrayList<>();
            List<String> deployedArtifacts = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                            failedArtifacts.add(artifactName);
                        }

                        @Override
                        public void onArtifactDeployed(@NotNull String configName, @NotNull String artifactName) {
                            deployedArtifacts.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    false,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            // Simulate the kind of non-fatal SEVERE noise Tomcat logs on a
            // healthy startup (JDBC driver de-registration warnings etc.).
            errorCount.set(3);

            analyzer.analyze("Server startup in 1234 ms", testContext);

            assertEquals(List.of("webapp-deploy:war exploded"), deployedArtifacts,
                    "Without a summary-failure signal, unresolved artifacts must still be treated as deployed.");
            assertTrue(failedArtifacts.isEmpty(),
                    "Generic error counter must not be used as a proxy for deployment failure.");
        }
    }

    @Nested
    @DisplayName("DeploymentAnalyzer")
    class DeploymentAnalyzerTests {

        private final TomcatOutputPipeline.DeploymentAnalyzer analyzer = new TomcatOutputPipeline.DeploymentAnalyzer();

        @Test
        @DisplayName("detects descriptor deployment")
        void detectsDescriptorDeployment() {
            contextToArtifact.put("myapp", "My Application");

            analyzer.analyze(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/myapp.xml] has finished in [456] ms",
                    context);

            assertEquals(1, deployedCount.get());
        }

        @Test
        @DisplayName("detects WAR deployment")
        void detectsWarDeployment() {
            analyzer.analyze(
                    "Deployment of web application archive [/webapps/ROOT.war] has finished in [1200] ms",
                    context);

            assertEquals(1, deployedCount.get());
        }

        @Test
        @DisplayName("maps context name to artifact display name")
        void mapsContextToArtifact() {
            contextToArtifact.put("myapp", "My Web App");

            analyzer.analyze(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/myapp.xml] has finished in [100] ms",
                    context);

            assertEquals(1, deployedCount.get());
        }

        @Test
        @DisplayName("reports ready context name")
        void reportsReadyContext() {
            analyzer.analyze(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/myapp.xml] has finished in [100] ms",
                    context);

            assertEquals("myapp", readyContext.get());
        }

        @Test
        @DisplayName("ignores non-deployment lines")
        void ignoresOtherLines() {
            analyzer.analyze("INFO: Loading web.xml", context);
            assertEquals(0, deployedCount.get());
        }
    }

    @Nested
    @DisplayName("ContextAnalyzer")
    class ContextAnalyzerTests {

        private final TomcatOutputPipeline.ContextAnalyzer analyzer = new TomcatOutputPipeline.ContextAnalyzer();

        @Test
        @DisplayName("reports initialized context name")
        void reportsContextReady() {
            analyzer.analyze("Context [/webapp-eight] initialized", context);

            assertEquals("/webapp-eight", readyContext.get());
        }
    }

    @Nested
    @DisplayName("ErrorWarningAnalyzer")
    class ErrorWarningAnalyzerTests {

        private final TomcatOutputPipeline.ErrorWarningAnalyzer analyzer = new TomcatOutputPipeline.ErrorWarningAnalyzer();

        @Test
        @DisplayName("counts SEVERE lines as errors")
        void countsSevere() {
            analyzer.analyze("SEVERE: Something failed", context);
            assertEquals(1, errorCount.get());
            assertEquals(0, warningCount.get());
        }

        @Test
        @DisplayName("counts ERROR lines as errors")
        void countsError() {
            analyzer.analyze("ERROR: Connection refused", context);
            assertEquals(1, errorCount.get());
        }

        @Test
        @DisplayName("counts FATAL lines as errors")
        void countsFatal() {
            analyzer.analyze("FATAL: Out of memory", context);
            assertEquals(1, errorCount.get());
        }

        @Test
        @DisplayName("counts Caused by lines as errors")
        void countsCausedBy() {
            analyzer.analyze("Caused by: java.lang.NullPointerException", context);
            assertEquals(1, errorCount.get());
        }

        @Test
        @DisplayName("counts exception class names as errors")
        void countsExceptionClassName() {
            analyzer.analyze("java.lang.IllegalStateException: already initialized", context);
            assertEquals(1, errorCount.get());
        }

        @Test
        @DisplayName("counts WARNING lines as warnings")
        void countsWarning() {
            analyzer.analyze("WARNING: Resource not found", context);
            assertEquals(0, errorCount.get());
            assertEquals(1, warningCount.get());
        }

        @Test
        @DisplayName("counts WARN lines as warnings")
        void countsWarn() {
            analyzer.analyze("WARN: Deprecated API used", context);
            assertEquals(0, errorCount.get());
            assertEquals(1, warningCount.get());
        }

        @Test
        @DisplayName("error takes precedence over warning on same line")
        void errorPrecedesWarning() {
            analyzer.analyze("SEVERE WARNING: critical issue", context);
            assertEquals(1, errorCount.get());
            assertEquals(0, warningCount.get(), "Warning should not also increment when error matches");
        }

        @Test
        @DisplayName("ignores info lines")
        void ignoresInfo() {
            analyzer.analyze("INFO: Server started", context);
            assertEquals(0, errorCount.get());
            assertEquals(0, warningCount.get());
        }

        @Test
        @DisplayName("shutdown suppresses error counter updates but still logs")
        void shutdownSuppressesErrors() {
            AtomicInteger loggedErrors = new AtomicInteger(0);
            AtomicInteger lifecycleErrors = new AtomicInteger(0);
            TomcatOutputPipeline.Context shutdownContext = new TomcatOutputPipeline.Context(
                    new TomcatOutputPipeline.PipelineLogger() {
                        @Override public void logServerStartup(long durationMs) {}
                        @Override public void logDeploymentSuccess(@NotNull String name, long ms) {}
                        @Override public void logServerInfo(@NotNull String msg) {}
                        @Override public void logServerError(@NotNull String msg) { loggedErrors.incrementAndGet(); }
                        @Override public void logServerWarning(@NotNull String msg) {}
                    },
                    new TomcatLifecycleListener() {
                        @Override public void onError(@NotNull String configName) {
                            lifecycleErrors.incrementAndGet();
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            shutdownContext.markShuttingDown();
            analyzer.analyze("SEVERE: cleanup noise", shutdownContext);

            assertEquals(0, errorCount.get());
            assertEquals(0, lifecycleErrors.get());
            assertEquals(1, loggedErrors.get());
        }

        @Test
        @DisplayName("shutdown suppresses warning counter updates but still logs")
        void shutdownSuppressesWarnings() {
            AtomicInteger loggedWarnings = new AtomicInteger(0);
            AtomicInteger lifecycleWarnings = new AtomicInteger(0);
            TomcatOutputPipeline.Context shutdownContext = new TomcatOutputPipeline.Context(
                    new TomcatOutputPipeline.PipelineLogger() {
                        @Override public void logServerStartup(long durationMs) {}
                        @Override public void logDeploymentSuccess(@NotNull String name, long ms) {}
                        @Override public void logServerInfo(@NotNull String msg) {}
                        @Override public void logServerError(@NotNull String msg) {}
                        @Override public void logServerWarning(@NotNull String msg) { loggedWarnings.incrementAndGet(); }
                    },
                    new TomcatLifecycleListener() {
                        @Override public void onWarning(@NotNull String configName) {
                            lifecycleWarnings.incrementAndGet();
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            shutdownContext.markShuttingDown();
            analyzer.analyze("WARNING: cleanup noise", shutdownContext);

            assertEquals(0, warningCount.get());
            assertEquals(0, lifecycleWarnings.get());
            assertEquals(1, loggedWarnings.get());
        }
    }

    @Nested
    @DisplayName("ReloadAnalyzer")
    class ReloadAnalyzerTests {

        private final TomcatOutputPipeline.ReloadAnalyzer analyzer = new TomcatOutputPipeline.ReloadAnalyzer();

        @Test
        @DisplayName("detects reload started")
        void detectsReloadStarted() {
            contextToArtifact.put("myapp", "My App");
            analyzer.analyze("Reloading Context with name [/myapp] has started", context);
            // No crash — just exercises the code path
        }

        @Test
        @DisplayName("detects reload completed")
        void detectsReloadCompleted() {
            analyzer.analyze("Reloading Context with name [/myapp] is completed", context);
            // No crash
        }
    }

    @Nested
    @DisplayName("ArtifactFailureAnalyzer")
    class ArtifactFailureAnalyzerTests {

        private final TomcatOutputPipeline.ArtifactFailureAnalyzer analyzer =
                new TomcatOutputPipeline.ArtifactFailureAnalyzer();

        @Test
        @DisplayName("maps deployment descriptor failure to artifact display name")
        void mapsDescriptorFailureToArtifact() {
            contextToArtifact.put("myapp", "My Web App");
            List<String> failedArtifacts = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                            failedArtifacts.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            analyzer.analyze(
                    "SEVERE [main] org.apache.catalina.startup.HostConfig.deployDescriptor Error deploying deployment descriptor [/conf/Catalina/localhost/myapp.xml]",
                    testContext);

            assertEquals(List.of("My Web App"), failedArtifacts);
        }

        @Test
        @DisplayName("detects context startup failure")
        void detectsContextStartupFailure() {
            contextToArtifact.put("myapp", "My Web App");
            List<String> failedArtifacts = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                            failedArtifacts.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            analyzer.analyze(
                    "10-Mar-2026 08:16:54.579 SEVERE [main] org.apache.catalina.core.StandardContext.startInternal Context [/myapp] startup failed due to previous errors",
                    testContext);

            assertEquals(List.of("My Web App"), failedArtifacts);
        }

        @Test
        @DisplayName("deduplicates repeated failure lines for the same artifact")
        void deduplicatesRepeatedFailures() {
            contextToArtifact.put("myapp", "My Web App");
            List<String> failedArtifacts = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactFailed(@NotNull String configName, @NotNull String artifactName) {
                            failedArtifacts.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            String failure =
                    "SEVERE [main] org.apache.catalina.startup.HostConfig.deployDescriptor Error deploying deployment descriptor [/conf/Catalina/localhost/myapp.xml]";
            analyzer.analyze(failure, testContext);
            analyzer.analyze(failure, testContext);

            assertEquals(List.of("My Web App"), failedArtifacts);
        }
    }

    @Nested
    @DisplayName("ServerDeploymentSummaryFailureAnalyzer")
    class ServerDeploymentSummaryFailureTests {

        private TomcatOutputPipeline.Context summaryContext(
                AtomicInteger summaryFailedCount) {
            return new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onDeploymentSummaryFailed(@NotNull String configName) {
                            summaryFailedCount.incrementAndGet();
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    true,
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set);
        }

        @Test
        @DisplayName("fires on \"One or more Contexts did not start successfully\"")
        void firesOnContextSummary() {
            AtomicInteger count = new AtomicInteger();
            TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer analyzer =
                    new TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer();

            analyzer.analyze(
                    "19-Apr-2026 20:00:00.000 SEVERE [main] org.apache.catalina.startup.Catalina.start "
                            + "One or more Contexts did not start successfully",
                    summaryContext(count));

            assertEquals(1, count.get(),
                    "Tomcat's summary message must trigger onDeploymentSummaryFailed so the "
                            + "Services panel shows the server as FAILED even when the per-artifact "
                            + "pattern matcher couldn't identify which artifact failed.");
        }

        @Test
        @DisplayName("fires at most once per launch")
        void firesAtMostOncePerLaunch() {
            AtomicInteger count = new AtomicInteger();
            TomcatOutputPipeline.Context ctx = summaryContext(count);
            TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer analyzer =
                    new TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer();

            String line = "SEVERE One or more Contexts did not start successfully";
            analyzer.analyze(line, ctx);
            analyzer.analyze(line, ctx);
            analyzer.analyze(line, ctx);

            assertEquals(1, count.get(),
                    "Tomcat sometimes logs the summary repeatedly across threads; the analyzer "
                            + "must suppress duplicates so listeners aren't spammed.");
        }

        @Test
        @DisplayName("doesn't fire on unrelated SEVERE lines")
        void ignoresUnrelatedSevereLines() {
            AtomicInteger count = new AtomicInteger();
            TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer analyzer =
                    new TomcatOutputPipeline.ServerDeploymentSummaryFailureAnalyzer();

            analyzer.analyze("SEVERE some unrelated error about JDBC pool", summaryContext(count));

            assertEquals(0, count.get());
        }
    }

    @Nested
    @DisplayName("full pipeline")
    class FullPipeline {

        @Test
        @DisplayName("processes startup and error on different lines")
        void processesMultipleAnalyzers() {
            TomcatOutputPipeline pipeline = TomcatOutputPipeline.create(context);

            pipeline.processLine("Server startup in 999 ms", context);
            pipeline.processLine("SEVERE: Something went wrong", context);

            assertTrue(startupDetected.get());
            assertEquals(999, capturedStartupTime.get());
            assertEquals(1, errorCount.get());
        }

        @Test
        @DisplayName("empty lines are skipped")
        void emptyLinesSkipped() {
            TomcatOutputPipeline pipeline = TomcatOutputPipeline.create(context);

            pipeline.processLine("", context);

            assertFalse(startupDetected.get());
            assertEquals(0, errorCount.get());
        }

        @Test
        @DisplayName("multiple deployments increment counter")
        void multipleDeployments() {
            TomcatOutputPipeline pipeline = TomcatOutputPipeline.create(context);

            pipeline.processLine(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/app1.xml] has finished in [100] ms",
                    context);
            pipeline.processLine(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/app2.xml] has finished in [200] ms",
                    context);

            assertEquals(2, deployedCount.get());
        }

        @Test
        @DisplayName("custom analyzer can be added")
        void customAnalyzer() {
            List<String> captured = new ArrayList<>();
            TomcatOutputPipeline.Analyzer custom = (text, ctx) -> captured.add(text);

            TomcatOutputPipeline pipeline = new TomcatOutputPipeline(List.of(custom));
            pipeline.processLine("hello", context);

            assertEquals(1, captured.size());
            assertEquals("hello", captured.get(0));
        }

        /**
         * Regression test for the double onArtifactDeployed bug.
         *
         * <p>Bug: when Tomcat logs an individual deployment-completion message (matched by
         * DeploymentAnalyzer) AND subsequently logs the global "Server startup in N ms"
         * message (matched by StartupAnalyzer), StartupAnalyzer iterated all artifacts in
         * contextToArtifactName and fired onArtifactDeployed for each — including those
         * already notified by DeploymentAnalyzer — producing a duplicate notification.
         *
         * <p>Fix: DeploymentAnalyzer adds each notified artifact to {@code notifiedArtifacts};
         * StartupAnalyzer skips any artifact already present in that set.
         */
        @Test
        @DisplayName("onArtifactDeployed fires exactly once per artifact when deployment line precedes startup line")
        void noDoubleFireOnArtifactDeployed() {
            // Set up a single artifact mapping
            contextToArtifact.put("myapp", "My Web App");

            // Track every onArtifactDeployed invocation
            List<String> deployedNotifications = new ArrayList<>();
            TomcatOutputPipeline.Context testContext = new TomcatOutputPipeline.Context(
                    logger,
                    new TomcatLifecycleListener() {
                        @Override
                        public void onArtifactDeployed(@NotNull String configName,
                                                       @NotNull String artifactName) {
                            deployedNotifications.add(artifactName);
                        }
                    },
                    "testConfig",
                    contextToArtifact,
                    startupDetected,
                    deployedCount,
                    errorCount,
                    warningCount,
                    false, // jmxEnabled
                    duration -> capturedStartupTime.set(duration),
                    () -> postStartupCalled.set(true),
                    readyContext::set
            );

            TomcatOutputPipeline pipeline = TomcatOutputPipeline.create(testContext);

            // Step 1 — DeploymentAnalyzer fires and adds "My Web App" to notifiedArtifacts
            pipeline.processLine(
                    "Deployment of deployment descriptor [/conf/Catalina/localhost/myapp.xml]" +
                    " has finished in [456] ms",
                    testContext);

            // Step 2 — StartupAnalyzer fires; must skip "My Web App" (already notified)
            pipeline.processLine("Server startup in 1234 ms", testContext);

            assertEquals(1, deployedNotifications.size(),
                    "onArtifactDeployed must fire exactly once per artifact; " +
                    "got: " + deployedNotifications);
            assertEquals("My Web App", deployedNotifications.get(0));
        }
    }
}
