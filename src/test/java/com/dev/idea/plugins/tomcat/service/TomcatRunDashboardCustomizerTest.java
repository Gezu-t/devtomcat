package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TomcatRunDashboardCustomizer")
class TomcatRunDashboardCustomizerTest {

    @Nested
    @DisplayName("formatDuration")
    class FormatDurationTests {

        @Test
        @DisplayName("keeps sub-second values in milliseconds")
        void milliseconds() {
            assertEquals("850ms", TomcatRunDashboardCustomizer.formatDuration(850));
        }

        @Test
        @DisplayName("formats seconds with one decimal place")
        void seconds() {
            assertEquals("12.3s", TomcatRunDashboardCustomizer.formatDuration(12_345));
        }

        @Test
        @DisplayName("formats exact minute boundaries cleanly")
        void exactMinute() {
            assertEquals("1m 0s", TomcatRunDashboardCustomizer.formatDuration(60_000));
        }

        @Test
        @DisplayName("formats multi-minute durations")
        void minutes() {
            assertEquals("1m 23s", TomcatRunDashboardCustomizer.formatDuration(83_000));
        }
    }

    @Nested
    @DisplayName("formatIssueSummary")
    class FormatIssueSummaryTests {

        @Test
        @DisplayName("formats both errors and warnings")
        void errorsAndWarnings() {
            assertEquals("2 errors · 1 warning",
                    TomcatRunDashboardCustomizer.formatIssueSummary(2, 1));
        }

        @Test
        @DisplayName("formats empty string when there are no issues")
        void emptyWhenNoIssues() {
            assertTrue(TomcatRunDashboardCustomizer.formatIssueSummary(0, 0).isEmpty());
        }
    }

    @Nested
    @DisplayName("endpointFromManagerUrl — remote-mode Services tree URL parsing")
    class EndpointFromManagerUrlTests {

        @Test
        @DisplayName("HTTP manager URL with explicit port")
        void httpExplicitPort() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("http://prod.example.com:8080/manager");
            assertEquals("prod.example.com", ep.host());
            assertFalse(ep.https());
            assertEquals(8080, ep.port());
        }

        @Test
        @DisplayName("HTTPS manager URL with explicit port")
        void httpsExplicitPort() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("https://staging:8443/manager");
            assertEquals("staging", ep.host());
            assertTrue(ep.https());
            assertEquals(8443, ep.port());
        }

        @Test
        @DisplayName("HTTP without explicit port defaults to 80")
        void httpDefaultPort() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("http://prod.example.com/manager");
            assertEquals("prod.example.com", ep.host());
            assertFalse(ep.https());
            assertEquals(80, ep.port());
        }

        @Test
        @DisplayName("HTTPS without explicit port defaults to 443")
        void httpsDefaultPort() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("https://prod.example.com/manager");
            assertEquals("prod.example.com", ep.host());
            assertTrue(ep.https());
            assertEquals(443, ep.port());
        }

        @Test
        @DisplayName("IPv6 manager URL preserves brackets in host (URI.getHost behaviour)")
        void ipv6Host() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("http://[2001:db8::1]:8080/manager");
            // URI.getHost() returns the IP-literal with surrounding brackets for
            // IPv6 hosts, so the value flows through to formatUrl already
            // bracketed. The TomcatDeploymentNode.bracketIpv6 helper is a
            // defensive no-op in this case (it skips already-bracketed input).
            assertEquals("[2001:db8::1]", ep.host());
            assertEquals(8080, ep.port());
        }

        @Test
        @DisplayName("IPv4 literal host")
        void ipv4Host() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("http://10.0.0.5:8080/manager");
            assertEquals("10.0.0.5", ep.host());
            assertEquals(8080, ep.port());
        }

        @Test
        @DisplayName("uppercase HTTPS scheme is recognised case-insensitively")
        void uppercaseScheme() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("HTTPS://prod:8443/manager");
            assertTrue(ep.https());
        }

        @Test
        @DisplayName("null URL falls back to (localhost, false, 0) so the URL is hidden")
        void nullUrlFallback() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl(null);
            assertEquals("localhost", ep.host());
            assertFalse(ep.https());
            assertEquals(0, ep.port(), "Port 0 hides the URL in the Services tree");
        }

        @Test
        @DisplayName("blank URL falls back to localhost/0")
        void blankUrlFallback() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("   ");
            assertEquals("localhost", ep.host());
            assertEquals(0, ep.port());
        }

        @Test
        @DisplayName("malformed URL with invalid characters falls back instead of throwing")
        void malformedUrlFallback() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("not a valid url");
            // URI.create may or may not throw on this; either way, the parser
            // must not throw — it must fall back. The assertion exercises the
            // "host == null after parse" branch and the catch branch uniformly.
            assertEquals("localhost", ep.host());
            assertEquals(0, ep.port());
        }

        @Test
        @DisplayName("scheme without host (e.g. file:) falls back to localhost/0")
        void schemeWithoutHostFallback() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("file:///etc/passwd");
            assertEquals("localhost", ep.host());
            assertEquals(0, ep.port());
        }

        @Test
        @DisplayName("trailing whitespace is trimmed before parsing")
        void trimmedUrl() {
            TomcatRunDashboardCustomizer.Endpoint ep =
                    TomcatRunDashboardCustomizer.endpointFromManagerUrl("  http://prod:8080/manager  ");
            assertEquals("prod", ep.host());
            assertEquals(8080, ep.port());
        }
    }
}
