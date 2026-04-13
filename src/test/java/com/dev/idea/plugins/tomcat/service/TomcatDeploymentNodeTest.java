package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatDeploymentNode")
class TomcatDeploymentNodeTest {

    @Nested
    @DisplayName("formatUrl")
    class FormatUrlTests {

        @Test
        @DisplayName("builds URL with custom context path")
        void customContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("myapp", "/tmp/myapp", "exploded");
            artifact.setContextPath("/myapp");

            assertEquals("http://localhost:8080/myapp",
                    TomcatDeploymentNode.formatUrl(artifact, 8080));
        }

        @Test
        @DisplayName("builds URL with root context")
        void rootContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("root", "/tmp/root", "war");
            artifact.setContextPath("/");

            assertEquals("http://localhost:8080/",
                    TomcatDeploymentNode.formatUrl(artifact, 8080));
        }

        @Test
        @DisplayName("builds URL with custom port")
        void customPort() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("http://localhost:9090/app",
                    TomcatDeploymentNode.formatUrl(artifact, 9090));
        }

        @Test
        @DisplayName("empty context falls back to default /")
        void emptyContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("");

            assertEquals("http://localhost:8080/",
                    TomcatDeploymentNode.formatUrl(artifact, 8080));
        }

        @Test
        @DisplayName("nested context path")
        void nestedContextPath() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/api/v2");

            assertEquals("http://localhost:8080/api/v2",
                    TomcatDeploymentNode.formatUrl(artifact, 8080));
        }
    }

    @Nested
    @DisplayName("formatTooltip")
    class FormatTooltipTests {

        @Test
        @DisplayName("exploded artifact tooltip includes (Exploded) and URL")
        void explodedTooltip() {
            DeploymentArtifact artifact = new DeploymentArtifact("myapp", "/tmp/myapp", "exploded");
            artifact.setContextPath("/myapp");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, 8080);

            assertTrue(tooltip.contains("myapp"));
            assertTrue(tooltip.contains("(Exploded)"));
            assertTrue(tooltip.contains("http://localhost:8080/myapp"));
        }

        @Test
        @DisplayName("WAR artifact tooltip includes (WAR) and URL")
        void warTooltip() {
            DeploymentArtifact artifact = new DeploymentArtifact("myapp", "/tmp/myapp.war", "war");
            artifact.setContextPath("/myapp");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, 8080);

            assertTrue(tooltip.contains("myapp"));
            assertTrue(tooltip.contains("(WAR)"));
            assertFalse(tooltip.contains("(Exploded)"));
        }

        @Test
        @DisplayName("zero port omits URL from tooltip")
        void zeroPortNoUrl() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, 0);

            assertTrue(tooltip.contains("app"));
            assertTrue(tooltip.contains("(WAR)"));
            assertFalse(tooltip.contains("http://"), "Should not include URL when port is 0");
        }

        @Test
        @DisplayName("negative port omits URL from tooltip")
        void negativePortNoUrl() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "exploded");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, -1);

            assertFalse(tooltip.contains("http://"));
        }
    }

    @Nested
    @DisplayName("formatTypeBadge")
    class FormatTypeBadgeTests {

        @Test
        @DisplayName("exploded artifact shows [Exploded]")
        void explodedBadge() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "exploded");
            assertEquals(" [Exploded]", TomcatDeploymentNode.formatTypeBadge(artifact));
        }

        @Test
        @DisplayName("WAR artifact shows [WAR]")
        void warBadge() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app.war", "war");
            assertEquals(" [WAR]", TomcatDeploymentNode.formatTypeBadge(artifact));
        }

        @Test
        @DisplayName("external artifact shows [WAR]")
        void externalBadge() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app.war", "external");
            assertEquals(" [WAR]", TomcatDeploymentNode.formatTypeBadge(artifact));
        }
    }

    @Nested
    @DisplayName("canNavigate")
    class CanNavigateTests {

        @Test
        @DisplayName("allows navigation only for deployed artifacts with a valid port")
        void deployedWithPortCanNavigate() {
            assertTrue(TomcatDeploymentNode.canNavigate(
                    8080,
                    TomcatDeploymentStatusService.ArtifactState.DEPLOYED
            ));
        }

        @Test
        @DisplayName("blocks navigation for non-deployed states")
        void nonDeployedStatesCannotNavigate() {
            assertAll(
                    () -> assertFalse(TomcatDeploymentNode.canNavigate(
                            8080,
                            TomcatDeploymentStatusService.ArtifactState.DEPLOYING
                    )),
                    () -> assertFalse(TomcatDeploymentNode.canNavigate(
                            8080,
                            TomcatDeploymentStatusService.ArtifactState.RELOADING
                    )),
                    () -> assertFalse(TomcatDeploymentNode.canNavigate(
                            8080,
                            TomcatDeploymentStatusService.ArtifactState.FAILED
                    )),
                    () -> assertFalse(TomcatDeploymentNode.canNavigate(8080, null))
            );
        }

        @Test
        @DisplayName("blocks navigation when the port is missing")
        void missingPortCannotNavigate() {
            assertFalse(TomcatDeploymentNode.canNavigate(
                    0,
                    TomcatDeploymentStatusService.ArtifactState.DEPLOYED
            ));
        }
    }
}
