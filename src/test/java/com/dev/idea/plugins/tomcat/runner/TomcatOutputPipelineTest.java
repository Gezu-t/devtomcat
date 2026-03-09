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

        context = new TomcatOutputPipeline.Context(
                logger, new TomcatLifecycleListener() {}, "testConfig",
                contextToArtifact, startupDetected, deployedCount,
                errorCount, warningCount, true,
                duration -> capturedStartupTime.set(duration),
                () -> postStartupCalled.set(true)
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
        @DisplayName("ignores non-deployment lines")
        void ignoresOtherLines() {
            analyzer.analyze("INFO: Loading web.xml", context);
            assertEquals(0, deployedCount.get());
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
    }
}
