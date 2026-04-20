package com.dev.idea.plugins.tomcat.integration;

import com.dev.idea.plugins.tomcat.runner.TomcatLifecycleListener;
import com.dev.idea.plugins.tomcat.runner.TomcatOutputPipeline;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusServiceTestSupport;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration harness for replaying canned Tomcat output through the full
 * pipeline → lifecycle listeners → {@link TomcatDeploymentStatusService}
 * and asserting the final {@code ConfigStatus}.
 *
 * <p>The unit-level analyzer tests in {@code TomcatOutputPipelineTest} verify
 * individual regexes; this harness verifies the integration — that the right
 * sequence of {@code lifecycleListener} fan-outs lands the status service in
 * the right final state for each class of Tomcat launch.
 *
 * <p>Fixtures live under {@code src/test/resources/tomcat-output-fixtures/}.
 * One fixture per representative class of Tomcat launch (happy, partial
 * failure matched by per-artifact regex, partial failure caught only by the
 * server-level summary, healthy startup with non-fatal SEVERE noise, etc).
 * Every fixture doubles as documentation of what real Tomcat output looks
 * like for that class of launch.
 */
public final class TomcatPipelineHarness {

    /** Namespace under src/test/resources. */
    private static final String FIXTURE_ROOT = "/tomcat-output-fixtures/";

    private final String configName;
    private final TomcatDeploymentStatusService statusService;
    private final TomcatOutputPipeline pipeline;
    private final TomcatOutputPipeline.Context context;
    private final AtomicInteger refreshCount = new AtomicInteger();

    /**
     * @param configName           run configuration name the pipeline is wired to
     * @param contextToArtifactName ordered context → artifact display name mapping;
     *                              the harness seeds the status service with a
     *                              {@code DEPLOYING} entry for every artifact so the
     *                              final snapshot carries a complete picture
     */
    public TomcatPipelineHarness(@NotNull String configName,
                                 @NotNull Map<String, String> contextToArtifactName) {
        this.configName = configName;
        this.statusService = TomcatDeploymentStatusServiceTestSupport.createForTest(refreshCount::incrementAndGet);

        TomcatLifecycleListener listener = TomcatLifecycleListener.statusConsumer(statusService);

        AtomicBoolean serverStartupDetected = new AtomicBoolean(false);
        AtomicInteger deployedArtifactCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger warningCount = new AtomicInteger(0);

        this.context = new TomcatOutputPipeline.Context(
                new NoOpPipelineLogger(),
                listener,
                configName,
                new ConcurrentHashMap<>(contextToArtifactName),
                serverStartupDetected,
                deployedArtifactCount,
                errorCount,
                warningCount,
                /* jmxEnabled = */ false,
                /* onStartupDetected = */ duration -> {},
                /* onPostStartup = */ () -> {},
                /* onContextReady = */ ctx -> {});
        this.pipeline = TomcatOutputPipeline.create(context);

        // Seed the status service so the harness mirrors the production launch
        // sequence: onServerStarting → onArtifactDeploying(*) → pipeline output.
        statusService.onServerStarting(configName);
        for (Map.Entry<String, String> entry : contextToArtifactName.entrySet()) {
            statusService.onArtifactDeploying(configName, entry.getValue());
        }
    }

    /**
     * Convenience overload for the common case where every artifact's context
     * name matches its display name.
     */
    public static TomcatPipelineHarness of(@NotNull String configName, @NotNull String... artifacts) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : artifacts) {
            map.put(name, name);
        }
        return new TomcatPipelineHarness(configName, map);
    }

    /** Replays a fixture file line by line through the pipeline. */
    public void replay(@NotNull String fixtureName) throws IOException {
        String resourcePath = FIXTURE_ROOT + fixtureName;
        InputStream stream = TomcatPipelineHarness.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Fixture not found on classpath: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                pipeline.processLine(line, context);
            }
        }
    }

    /** Feeds a single line through the pipeline — useful for ad-hoc assertions. */
    public void feed(@NotNull String line) {
        pipeline.processLine(line, context);
    }

    /** Completes a launch after fixture replay by marking the server stopped. */
    public void stopServer(int exitCode) {
        statusService.onServerStopped(configName, exitCode);
    }

    public TomcatDeploymentStatusService.ConfigStatus status() {
        return statusService.getStatus(configName);
    }

    public TomcatDeploymentStatusService service() {
        return statusService;
    }

    public int refreshCount() {
        return refreshCount.get();
    }

    /** No-op logger — fixture tests assert on status service state, not console output. */
    private static final class NoOpPipelineLogger implements TomcatOutputPipeline.PipelineLogger {
        @Override public void logServerStartup(long durationMs) {}
        @Override public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {}
        @Override public void logServerInfo(@NotNull String message) {}
        @Override public void logServerError(@NotNull String message) {}
        @Override public void logServerWarning(@NotNull String message) {}
    }
}
