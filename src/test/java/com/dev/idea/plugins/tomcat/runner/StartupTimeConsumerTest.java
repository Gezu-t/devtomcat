package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the trend-recording gate in
 * {@link TomcatLifecycleListener#startupTimeConsumer}: a startup-time sample is
 * recorded only for a <em>clean</em> start, so a start that reported a
 * deployment failure can never pollute the best/average/trend with an
 * unrepresentative (and often anomalously small) reading.
 *
 * <p>The failure callbacks ({@code onArtifactFailed},
 * {@code onDeploymentSummaryFailed}) always precede the final
 * {@code onServerStarted} on the output-reader thread, so driving them in that
 * order here mirrors the production sequence.
 */
class StartupTimeConsumerTest {

    private static final String CONFIG = "web-app";

    private StartupTimeTracker newTracker() {
        StartupTimeTracker tracker = new StartupTimeTracker();
        tracker.loadState(new StartupTimeTracker.State());
        return tracker;
    }

    private TomcatLifecycleListener consumerFor(@NotNull StartupTimeTracker tracker) {
        return TomcatLifecycleListener.startupTimeConsumer(tracker, NO_OP_LOGGER);
    }

    @Test
    @DisplayName("a clean start records the startup-time sample")
    void cleanStartRecords() {
        StartupTimeTracker tracker = newTracker();
        consumerFor(tracker).onServerStarted(CONFIG, 3000);
        assertEquals(1, tracker.getRunCount(CONFIG));
        assertEquals(3000, tracker.getLastStartupTime(CONFIG));
    }

    @Test
    @DisplayName("a per-artifact failure before start suppresses the sample")
    void perArtifactFailureSuppressesSample() {
        StartupTimeTracker tracker = newTracker();
        TomcatLifecycleListener consumer = consumerFor(tracker);
        consumer.onArtifactFailed(CONFIG, "web-module");
        consumer.onServerStarted(CONFIG, 3000);
        assertEquals(0, tracker.getRunCount(CONFIG),
                "a start with a failed artifact must not record a trend sample");
    }

    @Test
    @DisplayName("a server-level summary failure before start suppresses the sample")
    void summaryFailureSuppressesSample() {
        StartupTimeTracker tracker = newTracker();
        TomcatLifecycleListener consumer = consumerFor(tracker);
        consumer.onDeploymentSummaryFailed(CONFIG);
        consumer.onServerStarted(CONFIG, 3000);
        assertEquals(0, tracker.getRunCount(CONFIG),
                "a start flagged as a deployment-summary failure must not record a trend sample");
    }

    @Test
    @DisplayName("the failure gate is per-start: a fresh consumer records again")
    void freshConsumerIsNotPoisoned() {
        StartupTimeTracker tracker = newTracker();
        // A prior failed start (its own consumer) must not bleed into the next.
        TomcatLifecycleListener failed = consumerFor(tracker);
        failed.onDeploymentSummaryFailed(CONFIG);
        failed.onServerStarted(CONFIG, 3000);

        consumerFor(tracker).onServerStarted(CONFIG, 2500);
        assertEquals(1, tracker.getRunCount(CONFIG));
        assertEquals(2500, tracker.getLastStartupTime(CONFIG));
    }

    private static final TomcatOutputPipeline.PipelineLogger NO_OP_LOGGER =
            new TomcatOutputPipeline.PipelineLogger() {
                @Override public void logServerStartup(long durationMs) {}
                @Override public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {}
                @Override public void logServerInfo(@NotNull String message) {}
            };
}
