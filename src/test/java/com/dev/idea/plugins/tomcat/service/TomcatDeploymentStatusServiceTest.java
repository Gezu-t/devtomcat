package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatDeploymentStatusService")
class TomcatDeploymentStatusServiceTest {

    private TomcatDeploymentStatusService service;
    private AtomicInteger refreshCount;

    @BeforeEach
    void setUp() {
        refreshCount = new AtomicInteger(0);
        service = new TomcatDeploymentStatusService(refreshCount::incrementAndGet);
    }

    @Nested
    @DisplayName("ConfigStatus defaults")
    class ConfigStatusDefaults {

        @Test
        @DisplayName("initial state has zero counters")
        void initialCounters() {
            var status = new TomcatDeploymentStatusService.ConfigStatus();
            assertEquals(0, status.getErrorCount());
            assertEquals(0, status.getWarningCount());
            assertEquals(0, status.getStartupTimeMs());
        }

        @Test
        @DisplayName("initial server state is STOPPED")
        void initialServerState() {
            var status = new TomcatDeploymentStatusService.ConfigStatus();
            assertEquals(TomcatDeploymentStatusService.ServerState.STOPPED, status.getServerState());
        }

        @Test
        @DisplayName("initial artifact states map is empty")
        void initialArtifactStates() {
            var status = new TomcatDeploymentStatusService.ConfigStatus();
            assertTrue(status.getArtifactStates().isEmpty());
        }
    }

    @Nested
    @DisplayName("server lifecycle")
    class ServerLifecycle {

        @Test
        @DisplayName("onServerStarting sets state to STARTING and resets counters")
        void serverStarting() {
            // Pre-populate some state
            service.onError("cfg");
            service.onWarning("cfg");

            service.onServerStarting("cfg");

            var status = service.getStatus("cfg");
            assertNotNull(status);
            assertEquals(TomcatDeploymentStatusService.ServerState.STARTING, status.getServerState());
            assertEquals(0, status.getErrorCount(), "Error count should be reset");
            assertEquals(0, status.getWarningCount(), "Warning count should be reset");
        }

        @Test
        @DisplayName("onServerStarted sets state to RUNNING with startup time")
        void serverStarted() {
            service.onServerStarting("cfg");
            service.onServerStarted("cfg", 1234);

            var status = service.getStatus("cfg");
            assertNotNull(status);
            assertEquals(TomcatDeploymentStatusService.ServerState.RUNNING, status.getServerState());
            assertEquals(1234, status.getStartupTimeMs());
        }

        @Test
        @DisplayName("onServerStopped with exit 0 sets STOPPED")
        void serverStoppedNormally() {
            service.onServerStarting("cfg");
            service.onServerStarted("cfg", 100);
            service.onServerStopped("cfg", 0);

            assertEquals(TomcatDeploymentStatusService.ServerState.STOPPED,
                    service.getStatus("cfg").getServerState());
        }

        @Test
        @DisplayName("onServerStopped with non-zero exit sets FAILED")
        void serverStoppedWithError() {
            service.onServerStarting("cfg");
            service.onServerStopped("cfg", 1);

            assertEquals(TomcatDeploymentStatusService.ServerState.FAILED,
                    service.getStatus("cfg").getServerState());
        }
    }

    @Nested
    @DisplayName("artifact lifecycle")
    class ArtifactLifecycle {

        @Test
        @DisplayName("onArtifactDeploying sets server to DEPLOYING and artifact to DEPLOYING")
        void artifactDeploying() {
            service.onServerStarting("cfg");
            service.onArtifactDeploying("cfg", "myapp.war");

            var status = service.getStatus("cfg");
            assertEquals(TomcatDeploymentStatusService.ServerState.DEPLOYING, status.getServerState());
            assertEquals(TomcatDeploymentStatusService.ArtifactState.DEPLOYING,
                    status.getArtifactStates().get("myapp.war"));
        }

        @Test
        @DisplayName("onArtifactDeployed sets artifact to DEPLOYED")
        void artifactDeployed() {
            service.onServerStarting("cfg");
            service.onArtifactDeploying("cfg", "myapp.war");
            service.onArtifactDeployed("cfg", "myapp.war");

            assertEquals(TomcatDeploymentStatusService.ArtifactState.DEPLOYED,
                    service.getStatus("cfg").getArtifactStates().get("myapp.war"));
        }

        @Test
        @DisplayName("onArtifactFailed sets artifact to FAILED")
        void artifactFailed() {
            service.onServerStarting("cfg");
            service.onArtifactDeploying("cfg", "myapp.war");
            service.onArtifactFailed("cfg", "myapp.war");

            assertEquals(TomcatDeploymentStatusService.ArtifactState.FAILED,
                    service.getStatus("cfg").getArtifactStates().get("myapp.war"));
        }

        @Test
        @DisplayName("onArtifactReloading sets artifact to RELOADING")
        void artifactReloading() {
            service.onServerStarting("cfg");
            service.onArtifactDeployed("cfg", "myapp.war");
            service.onArtifactReloading("cfg", "myapp.war");

            assertEquals(TomcatDeploymentStatusService.ArtifactState.RELOADING,
                    service.getStatus("cfg").getArtifactStates().get("myapp.war"));
        }
    }

    @Nested
    @DisplayName("error and warning counters")
    class ErrorWarningCounters {

        @Test
        @DisplayName("onError increments error count")
        void errorIncrements() {
            service.onServerStarting("cfg");

            service.onError("cfg");
            assertEquals(1, service.getStatus("cfg").getErrorCount());

            service.onError("cfg");
            service.onError("cfg");
            assertEquals(3, service.getStatus("cfg").getErrorCount());
        }

        @Test
        @DisplayName("onWarning increments warning count")
        void warningIncrements() {
            service.onServerStarting("cfg");

            service.onWarning("cfg");
            assertEquals(1, service.getStatus("cfg").getWarningCount());

            service.onWarning("cfg");
            assertEquals(2, service.getStatus("cfg").getWarningCount());
        }

        @Test
        @DisplayName("onError does not change server state")
        void errorDoesNotChangeState() {
            service.onServerStarting("cfg");
            service.onServerStarted("cfg", 100);

            service.onError("cfg");

            assertEquals(TomcatDeploymentStatusService.ServerState.RUNNING,
                    service.getStatus("cfg").getServerState(),
                    "Transient errors should not override RUNNING state");
        }

        @Test
        @DisplayName("onWarning does not change server state")
        void warningDoesNotChangeState() {
            service.onServerStarting("cfg");
            service.onServerStarted("cfg", 100);

            service.onWarning("cfg");

            assertEquals(TomcatDeploymentStatusService.ServerState.RUNNING,
                    service.getStatus("cfg").getServerState());
        }

        @Test
        @DisplayName("onError triggers refresh callback")
        void errorTriggersRefresh() {
            int before = refreshCount.get();
            service.onError("cfg");
            assertTrue(refreshCount.get() > before,
                    "onError should trigger a refresh callback");
        }

        @Test
        @DisplayName("onWarning triggers refresh callback")
        void warningTriggersRefresh() {
            int before = refreshCount.get();
            service.onWarning("cfg");
            assertTrue(refreshCount.get() > before,
                    "onWarning should trigger a refresh callback");
        }

        @Test
        @DisplayName("onServerStarting resets error and warning counts")
        void startingResetsCounters() {
            service.onError("cfg");
            service.onError("cfg");
            service.onWarning("cfg");

            service.onServerStarting("cfg");

            var status = service.getStatus("cfg");
            assertEquals(0, status.getErrorCount());
            assertEquals(0, status.getWarningCount());
        }
    }

    @Nested
    @DisplayName("status management")
    class StatusManagement {

        @Test
        @DisplayName("getStatus returns null for unknown config")
        void unknownConfigReturnsNull() {
            assertNull(service.getStatus("nonexistent"));
        }

        @Test
        @DisplayName("remove clears status")
        void removeClears() {
            service.onServerStarting("cfg");
            assertNotNull(service.getStatus("cfg"));

            service.remove("cfg");
            assertNull(service.getStatus("cfg"));
        }

        @Test
        @DisplayName("independent configs have independent state")
        void independentConfigs() {
            service.onServerStarting("cfg1");
            service.onServerStarted("cfg1", 100);
            service.onError("cfg1");

            service.onServerStarting("cfg2");

            assertEquals(TomcatDeploymentStatusService.ServerState.RUNNING,
                    service.getStatus("cfg1").getServerState());
            assertEquals(1, service.getStatus("cfg1").getErrorCount());

            assertEquals(TomcatDeploymentStatusService.ServerState.STARTING,
                    service.getStatus("cfg2").getServerState());
            assertEquals(0, service.getStatus("cfg2").getErrorCount());
        }
    }

    @Nested
    @DisplayName("refresh behavior")
    class RefreshBehavior {

        @Test
        @DisplayName("lifecycle transitions trigger refresh")
        void lifecycleTransitionsRefresh() {
            refreshCount.set(0);

            service.onServerStarting("cfg");
            int afterStarting = refreshCount.get();
            assertTrue(afterStarting > 0, "onServerStarting should refresh");

            service.onArtifactDeploying("cfg", "app");
            assertTrue(refreshCount.get() > afterStarting, "onArtifactDeploying should refresh");

            int beforeDeployed = refreshCount.get();
            service.onArtifactDeployed("cfg", "app");
            assertTrue(refreshCount.get() > beforeDeployed, "onArtifactDeployed should refresh");
        }

        @Test
        @DisplayName("onServerStopped triggers refresh")
        void stoppedRefreshes() {
            service.onServerStarting("cfg");
            int before = refreshCount.get();
            service.onServerStopped("cfg", 0);
            assertTrue(refreshCount.get() > before);
        }
    }

    @Nested
    @DisplayName("debounce constant")
    class DebounceConstant {

        @Test
        @DisplayName("debounce interval is 500ms")
        void debounceInterval() {
            assertEquals(500, TomcatDeploymentStatusService.COUNTER_REFRESH_DEBOUNCE_MS);
        }
    }

    @Nested
    @DisplayName("ServerState enum")
    class ServerStateTests {

        @Test
        @DisplayName("all states have non-empty labels")
        void allStatesHaveLabels() {
            for (var state : TomcatDeploymentStatusService.ServerState.values()) {
                assertNotNull(state.getLabel());
                assertFalse(state.getLabel().isEmpty(), "Label for " + state + " should not be empty");
            }
        }

        @Test
        @DisplayName("expected states exist")
        void expectedStatesExist() {
            assertEquals(5, TomcatDeploymentStatusService.ServerState.values().length);
        }
    }

    @Nested
    @DisplayName("ArtifactState enum")
    class ArtifactStateTests {

        @Test
        @DisplayName("all artifact states have non-empty labels")
        void allStatesHaveLabels() {
            for (var state : TomcatDeploymentStatusService.ArtifactState.values()) {
                assertNotNull(state.getLabel());
                assertFalse(state.getLabel().isEmpty(), "Label for " + state + " should not be empty");
            }
        }

        @Test
        @DisplayName("expected artifact states exist")
        void expectedStatesExist() {
            assertEquals(5, TomcatDeploymentStatusService.ArtifactState.values().length);
        }
    }
}
