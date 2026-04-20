package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatLifecycleListener")
class TomcatLifecycleListenerTest {

    @Nested
    @DisplayName("composite")
    class CompositeTests {

        @Test
        @DisplayName("fans out events to all listeners")
        void fansOutEvents() {
            AtomicInteger count = new AtomicInteger(0);
            TomcatLifecycleListener a = new TomcatLifecycleListener() {
                @Override public void onServerStarting(@NotNull String c) { count.incrementAndGet(); }
            };
            TomcatLifecycleListener b = new TomcatLifecycleListener() {
                @Override public void onServerStarting(@NotNull String c) { count.incrementAndGet(); }
            };

            TomcatLifecycleListener composite = TomcatLifecycleListener.composite(List.of(a, b));
            composite.onServerStarting("test");

            assertEquals(2, count.get());
        }

        @Test
        @DisplayName("empty composite is a no-op")
        void emptyComposite() {
            TomcatLifecycleListener composite = TomcatLifecycleListener.composite(List.of());
            // Should not throw
            composite.onServerStarting("test");
            composite.onServerStarted("test", 100);
            composite.onServerStopped("test", 0, 1000, 0, 0, 100);
        }

        @Test
        @DisplayName("all event types are forwarded")
        void allEventsForwarded() {
            List<String> events = new ArrayList<>();
            TomcatLifecycleListener recorder = new TomcatLifecycleListener() {
                @Override public void onServerStarting(@NotNull String c) { events.add("starting"); }
                @Override public void onServerStarted(@NotNull String c, long t) { events.add("started"); }
                @Override public void onServerStopped(@NotNull String c, int ex, long d, int e, int w, long s) { events.add("stopped"); }
                @Override public void onArtifactDeploying(@NotNull String c, @NotNull String a) { events.add("deploying"); }
                @Override public void onArtifactDeployed(@NotNull String c, @NotNull String a) { events.add("deployed"); }
                @Override public void onArtifactFailed(@NotNull String c, @NotNull String a) { events.add("failed"); }
                @Override public void onArtifactCancelled(@NotNull String c, @NotNull String a) { events.add("cancelled"); }
                @Override public void onDeploymentSummaryFailed(@NotNull String c) { events.add("summaryFailed"); }
                @Override public void onArtifactReloading(@NotNull String c, @NotNull String a) { events.add("reloading"); }
                @Override public void onError(@NotNull String c) { events.add("error"); }
                @Override public void onWarning(@NotNull String c) { events.add("warning"); }
            };

            TomcatLifecycleListener composite = TomcatLifecycleListener.composite(List.of(recorder));
            composite.onServerStarting("c");
            composite.onServerStarted("c", 100);
            composite.onArtifactDeploying("c", "a");
            composite.onArtifactDeployed("c", "a");
            composite.onArtifactFailed("c", "a");
            composite.onArtifactCancelled("c", "a");
            composite.onDeploymentSummaryFailed("c");
            composite.onArtifactReloading("c", "a");
            composite.onError("c");
            composite.onWarning("c");
            composite.onServerStopped("c", 0, 1000, 1, 2, 100);

            assertEquals(List.of("starting", "started", "deploying", "deployed",
                    "failed", "cancelled", "summaryFailed", "reloading",
                    "error", "warning", "stopped"), events);
        }
    }

    @Nested
    @DisplayName("historyConsumer")
    class HistoryConsumerTests {

        @Test
        @DisplayName("records complete deployment history entry")
        void recordsHistory() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStarting("myConfig");
            consumer.onArtifactDeploying("myConfig", "webapp.war");
            consumer.onServerStopped("myConfig", 0, 5000, 2, 3, 1200);

            List<TomcatDeploymentHistory.HistoryEntry> entries = history.getEntries();
            assertEquals(1, entries.size());

            TomcatDeploymentHistory.HistoryEntry entry = entries.get(0);
            assertEquals("myConfig", entry.configName);
            assertTrue(entry.success);
            assertEquals(0, entry.exitCode);
            assertEquals(5000, entry.durationMs);
            assertEquals(1200, entry.startupTimeMs);
            assertEquals(2, entry.errorCount);
            assertEquals(3, entry.warningCount);
            assertEquals(List.of("webapp.war"), entry.artifactNames);
        }

        @Test
        @DisplayName("records failure entry")
        void recordsFailure() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStarting("cfg");
            consumer.onServerStopped("cfg", 1, 2000, 5, 0, 0);

            var entry = history.getLastEntry("cfg");
            assertNotNull(entry);
            assertFalse(entry.success);
            assertEquals(1, entry.exitCode);
            assertEquals(5, entry.errorCount);
        }

        @Test
        @DisplayName("marks session failed when an artifact fails even if the process exits cleanly")
        void artifactFailureMarksHistoryFailed() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStarting("cfg");
            consumer.onArtifactDeploying("cfg", "app.war");
            consumer.onArtifactFailed("cfg", "app.war");
            consumer.onServerStopped("cfg", 0, 2000, 1, 0, 750);

            var entry = history.getLastEntry("cfg");
            assertNotNull(entry);
            assertFalse(entry.success);
            assertTrue(entry.artifactFailure);
            assertEquals(0, entry.exitCode);
        }

        @Test
        @DisplayName("deduplicates artifact names across multiple onArtifactDeploying calls")
        void deduplicatesArtifactNames() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStarting("cfg");
            // First round: startNotified emits deploying
            consumer.onArtifactDeploying("cfg", "app.war");
            // Second round: remote deployment emits deploying again
            consumer.onArtifactDeploying("cfg", "app.war");
            consumer.onServerStopped("cfg", 0, 1000, 0, 0, 500);

            var entry = history.getLastEntry("cfg");
            assertNotNull(entry);
            assertEquals(List.of("app.war"), entry.artifactNames,
                    "Artifact should appear only once despite duplicate onArtifactDeploying calls");
        }

        @Test
        @DisplayName("marks session failed on deployment summary failure even without named artifact")
        void summaryFailureMarksHistoryFailed() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStarting("cfg");
            consumer.onDeploymentSummaryFailed("cfg");
            consumer.onServerStopped("cfg", 0, 1000, 1, 0, 500);

            var entry = history.getLastEntry("cfg");
            assertNotNull(entry);
            assertFalse(entry.success);
            assertTrue(entry.artifactFailure);
            assertEquals(0, entry.exitCode);
        }

        @Test
        @DisplayName("no-op when stopped without starting")
        void noOpWithoutStart() {
            TomcatDeploymentHistory history = new TomcatDeploymentHistory();
            TomcatLifecycleListener consumer = TomcatLifecycleListener.historyConsumer(history);

            consumer.onServerStopped("cfg", 0, 1000, 0, 0, 0);

            assertTrue(history.getEntries().isEmpty());
        }
    }

    @Nested
    @DisplayName("startupTimeConsumer")
    class StartupTimeConsumerTests {

        @Test
        @DisplayName("records startup time and logs comparison")
        void recordsAndLogs() {
            StartupTimeTracker tracker = new StartupTimeTracker();
            List<String> logged = new ArrayList<>();
            TomcatOutputPipeline.PipelineLogger logger = new TomcatOutputPipeline.PipelineLogger() {
                @Override public void logServerStartup(long durationMs) {}
                @Override public void logDeploymentSuccess(@NotNull String name, long ms) {}
                @Override public void logServerInfo(@NotNull String msg) { logged.add(msg); }
                @Override public void logServerError(@NotNull String msg) {}
                @Override public void logServerWarning(@NotNull String msg) {}
            };

            TomcatLifecycleListener consumer = TomcatLifecycleListener.startupTimeConsumer(tracker, logger);

            consumer.onServerStarted("cfg", 1500);
            assertEquals(1500, tracker.getLastStartupTime("cfg"));
            // First run logs "First recorded startup"
            assertFalse(logged.isEmpty());
            assertTrue(logged.get(0).contains("Startup trend:"));

            consumer.onServerStarted("cfg", 1200);
            assertEquals(1200, tracker.getLastStartupTime("cfg"));
            assertEquals(2, tracker.getRunCount("cfg"));
        }

        @Test
        @DisplayName("ignores non-startup events")
        void ignoresOtherEvents() {
            StartupTimeTracker tracker = new StartupTimeTracker();
            TomcatOutputPipeline.PipelineLogger logger = new TomcatOutputPipeline.PipelineLogger() {
                @Override public void logServerStartup(long durationMs) {}
                @Override public void logDeploymentSuccess(@NotNull String name, long ms) {}
                @Override public void logServerInfo(@NotNull String msg) {}
                @Override public void logServerError(@NotNull String msg) {}
                @Override public void logServerWarning(@NotNull String msg) {}
            };

            TomcatLifecycleListener consumer = TomcatLifecycleListener.startupTimeConsumer(tracker, logger);
            consumer.onServerStarting("cfg");
            consumer.onArtifactDeployed("cfg", "app");

            assertEquals(-1, tracker.getLastStartupTime("cfg"));
        }
    }
}
