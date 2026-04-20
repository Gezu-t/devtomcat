package com.dev.idea.plugins.tomcat.integration;

import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService.ArtifactState;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService.ServerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end integration tests that replay canned Tomcat output through the
 * full pipeline and assert the final {@link TomcatDeploymentStatusService}
 * state. The analyzer-level unit tests pin individual regex behaviour; these
 * tests pin what the user sees in the Services panel for each representative
 * class of Tomcat launch.
 *
 * <p>Adding a new launch class — custom localisation, non-English Tomcat, a
 * newly-observed failure mode — should mean dropping in a new fixture under
 * {@code src/test/resources/tomcat-output-fixtures/} and one test here, not
 * reverse-engineering which analyzer needs a new regex.
 */
@DisplayName("Tomcat output pipeline — fixture integration")
class TomcatPipelineFixtureTest {

    private static final Map<String, String> THREE_WEBAPPS = Map.of(
            "webapp-deploy", "webapp-deploy:war exploded",
            "webapp-features", "webapp-features:war exploded",
            "webapp-portal", "webapp-portal:war exploded");

    @Test
    @DisplayName("clean startup — server RUNNING, every artifact DEPLOYED")
    void cleanStartup() throws IOException {
        TomcatPipelineHarness harness = new TomcatPipelineHarness("testConfig", THREE_WEBAPPS);

        harness.replay("clean-startup.log");

        TomcatDeploymentStatusService.ConfigStatus status = harness.status();
        assertNotNull(status);
        assertEquals(ServerState.RUNNING, status.getServerState(),
                "Clean startup must end in RUNNING");
        for (String artifact : THREE_WEBAPPS.values()) {
            assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get(artifact),
                    "Artifact '" + artifact + "' must be DEPLOYED after a clean startup");
        }
        assertEquals(0, status.getErrorCount(),
                "Clean startup must not increment the error counter");
    }

    @Test
    @DisplayName("partial failure with per-artifact log match — server FAILED, one artifact FAILED, peers DEPLOYED")
    void partialFailurePerArtifactMatched() throws IOException {
        TomcatPipelineHarness harness = new TomcatPipelineHarness("testConfig", THREE_WEBAPPS);

        harness.replay("partial-failure-per-artifact.log");

        TomcatDeploymentStatusService.ConfigStatus status = harness.status();
        assertNotNull(status);
        assertEquals(ServerState.FAILED, status.getServerState(),
                "A per-artifact failure must leave the server state FAILED");
        assertEquals(ArtifactState.FAILED, status.getArtifactStates().get("webapp-deploy:war exploded"),
                "webapp-deploy reported failure via both per-artifact and summary patterns — must be FAILED");
        assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get("webapp-features:war exploded"),
                "webapp-features deployed cleanly — must be DEPLOYED");
        assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get("webapp-portal:war exploded"),
                "webapp-portal deployed cleanly — must be DEPLOYED");
    }

    @Test
    @DisplayName("partial failure caught only by summary analyzer — unresolved artifact flipped to FAILED")
    void partialFailureSummaryOnly() throws IOException {
        TomcatPipelineHarness harness = new TomcatPipelineHarness("testConfig", THREE_WEBAPPS);

        harness.replay("partial-failure-summary-only.log");

        TomcatDeploymentStatusService.ConfigStatus status = harness.status();
        assertNotNull(status);
        assertEquals(ServerState.FAILED, status.getServerState(),
                "A summary-only failure must still leave the server state FAILED — the 1.0.7 resilience fix");
        assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get("webapp-deploy:war exploded"),
                "webapp-deploy printed a clean completion log — must stay DEPLOYED");
        assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get("webapp-features:war exploded"),
                "webapp-features printed a clean completion log — must stay DEPLOYED");
        assertEquals(ArtifactState.FAILED, status.getArtifactStates().get("webapp-portal:war exploded"),
                "webapp-portal never printed a completion line; summary-failure fallback must flip it to FAILED");
    }

    @Test
    @DisplayName("healthy startup with non-fatal SEVERE noise — server RUNNING, no false positives")
    void healthyWithSevereNoise() throws IOException {
        TomcatPipelineHarness harness = new TomcatPipelineHarness("testConfig", THREE_WEBAPPS);

        harness.replay("healthy-with-severe-noise.log");

        TomcatDeploymentStatusService.ConfigStatus status = harness.status();
        assertNotNull(status);
        assertEquals(ServerState.RUNNING, status.getServerState(),
                "A synthetic SEVERE line on a healthy startup must not flip the server to FAILED");
        for (String artifact : THREE_WEBAPPS.values()) {
            assertEquals(ArtifactState.DEPLOYED, status.getArtifactStates().get(artifact),
                    "No summary-failure signal — artifact '" + artifact + "' must stay DEPLOYED");
        }
        assertEquals(1, status.getErrorCount(),
                "The SEVERE line is still counted towards the dashboard badge");
    }
}
