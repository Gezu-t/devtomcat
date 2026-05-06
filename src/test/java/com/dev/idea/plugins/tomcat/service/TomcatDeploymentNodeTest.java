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
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", false, 8080));
        }

        @Test
        @DisplayName("builds URL with root context")
        void rootContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("root", "/tmp/root", "war");
            artifact.setContextPath("/");

            assertEquals("http://localhost:8080/",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", false, 8080));
        }

        @Test
        @DisplayName("builds URL with custom port")
        void customPort() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("http://localhost:9090/app",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", false, 9090));
        }

        @Test
        @DisplayName("empty context falls back to default /")
        void emptyContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("");

            assertEquals("http://localhost:8080/",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", false, 8080));
        }

        @Test
        @DisplayName("nested context path")
        void nestedContextPath() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/api/v2");

            assertEquals("http://localhost:8080/api/v2",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", false, 8080));
        }

        @Test
        @DisplayName("HTTPS scheme and port are used when https=true")
        void httpsScheme() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("https://localhost:8443/app",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", true, 8443));
        }

        @Test
        @DisplayName("HTTPS with root context")
        void httpsRootContext() {
            DeploymentArtifact artifact = new DeploymentArtifact("root", "/tmp/root", "war");
            artifact.setContextPath("/");

            assertEquals("https://localhost:8443/",
                    TomcatDeploymentNode.formatUrl(artifact, "localhost", true, 8443));
        }

        @Test
        @DisplayName("remote-mode hostname (FQDN) is used verbatim — no localhost substitution")
        void remoteHostFqdn() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("http://prod.example.com:8080/app",
                    TomcatDeploymentNode.formatUrl(artifact, "prod.example.com", false, 8080));
        }

        @Test
        @DisplayName("remote-mode HTTPS hostname builds correct https URL")
        void remoteHostHttps() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("https://staging.example.com:8443/app",
                    TomcatDeploymentNode.formatUrl(artifact, "staging.example.com", true, 8443));
        }

        @Test
        @DisplayName("IPv4 literal host is used verbatim")
        void ipv4Host() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("http://10.0.0.5:8080/app",
                    TomcatDeploymentNode.formatUrl(artifact, "10.0.0.5", false, 8080));
        }

        @Test
        @DisplayName("unbracketed IPv6 host is re-bracketed in the URL")
        void ipv6HostRebracket() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            // URI.getHost() strips the brackets from "[::1]"; formatUrl must put
            // them back so the resulting URL is syntactically valid.
            assertEquals("http://[::1]:8080/app",
                    TomcatDeploymentNode.formatUrl(artifact, "::1", false, 8080));
        }

        @Test
        @DisplayName("already-bracketed IPv6 host is not double-bracketed")
        void ipv6HostAlreadyBracketed() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            assertEquals("http://[2001:db8::1]:8080/app",
                    TomcatDeploymentNode.formatUrl(artifact, "[2001:db8::1]", false, 8080));
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

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "localhost", false, 8080);

            assertTrue(tooltip.contains("myapp"));
            assertTrue(tooltip.contains("(Exploded)"));
            assertTrue(tooltip.contains("http://localhost:8080/myapp"));
        }

        @Test
        @DisplayName("WAR artifact tooltip includes (WAR) and URL")
        void warTooltip() {
            DeploymentArtifact artifact = new DeploymentArtifact("myapp", "/tmp/myapp.war", "war");
            artifact.setContextPath("/myapp");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "localhost", false, 8080);

            assertTrue(tooltip.contains("myapp"));
            assertTrue(tooltip.contains("(WAR)"));
            assertFalse(tooltip.contains("(Exploded)"));
        }

        @Test
        @DisplayName("zero port omits URL from tooltip")
        void zeroPortNoUrl() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "localhost", false, 0);

            assertTrue(tooltip.contains("app"));
            assertTrue(tooltip.contains("(WAR)"));
            assertFalse(tooltip.contains("http://"), "Should not include URL when port is 0");
        }

        @Test
        @DisplayName("negative port omits URL from tooltip")
        void negativePortNoUrl() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "exploded");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "localhost", false, -1);

            assertFalse(tooltip.contains("http://"));
        }

        @Test
        @DisplayName("HTTPS tooltip uses https scheme and port")
        void httpsTooltip() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "localhost", true, 8443);

            assertTrue(tooltip.contains("https://localhost:8443/app"));
            assertFalse(tooltip.contains("http://localhost"),
                    "Should not contain http:// when HTTPS is enabled");
        }

        @Test
        @DisplayName("remote-mode tooltip carries the remote host")
        void remoteHostTooltip() {
            DeploymentArtifact artifact = new DeploymentArtifact("app", "/tmp/app", "war");
            artifact.setContextPath("/app");

            String tooltip = TomcatDeploymentNode.formatTooltip(artifact, "prod.example.com", false, 8080);

            assertTrue(tooltip.contains("http://prod.example.com:8080/app"));
            assertFalse(tooltip.contains("localhost"),
                    "Tooltip must not show 'localhost' for a remote-mode deployment");
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
