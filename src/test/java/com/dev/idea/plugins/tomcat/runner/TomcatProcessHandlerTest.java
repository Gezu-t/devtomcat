package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-logic static methods in TomcatProcessHandler.
 *
 * <p>Instantiating TomcatProcessHandler requires IntelliJ Platform infrastructure
 * (KillableColoredProcessHandler, a live process, etc.), so only the package-private
 * static helpers that have no platform dependencies are tested here.
 */
@DisplayName("TomcatProcessHandler")
class TomcatProcessHandlerTest {

    // -------------------------------------------------------------------------
    // computeRestartBlockReason — the pure predicate behind getRestartBlockReason,
    // the single gate used by all four restart/update surfaces.
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("computeRestartBlockReason")
    class ComputeRestartBlockReasonTests {

        @Test
        @DisplayName("returns shutdown reason when terminating (precedes terminated flag)")
        void terminating() {
            // terminating also reports terminated briefly; shutdown wins.
            assertEquals("Tomcat is shutting down",
                    TomcatProcessHandler.computeRestartBlockReason(true, true, false));
            assertEquals("Tomcat is shutting down",
                    TomcatProcessHandler.computeRestartBlockReason(true, false, true));
        }

        @Test
        @DisplayName("returns not-running reason when terminated but not terminating")
        void terminated() {
            assertEquals("Tomcat is not running",
                    TomcatProcessHandler.computeRestartBlockReason(false, true, false));
        }

        @Test
        @DisplayName("returns starting reason when live but startup not yet detected")
        void startingUp() {
            String reason = TomcatProcessHandler.computeRestartBlockReason(false, false, false);
            assertNotNull(reason);
            assertTrue(reason.startsWith("Tomcat is still starting"),
                    "expected a starting-up message, got: " + reason);
        }

        @Test
        @DisplayName("returns null when fully ready to accept a restart")
        void ready() {
            assertNull(TomcatProcessHandler.computeRestartBlockReason(false, false, true));
        }
    }

    // -------------------------------------------------------------------------
    // extractContextNameFromBrowserUrl
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("extractContextNameFromBrowserUrl")
    class ExtractContextNameTests {

        @Test
        @DisplayName("returns null for null URL")
        void nullUrl() {
            assertNull(TomcatProcessHandler.extractContextNameFromBrowserUrl(null));
        }

        @Test
        @DisplayName("returns null for empty URL")
        void emptyUrl() {
            assertNull(TomcatProcessHandler.extractContextNameFromBrowserUrl(""));
        }

        @Test
        @DisplayName("returns null for whitespace-only URL")
        void whitespaceUrl() {
            assertNull(TomcatProcessHandler.extractContextNameFromBrowserUrl("   "));
        }

        @Test
        @DisplayName("returns ROOT for root context path /")
        void rootContextPath() {
            assertEquals("ROOT",
                    TomcatProcessHandler.extractContextNameFromBrowserUrl("http://localhost:8080/"));
        }

        @Test
        @DisplayName("returns ROOT for URL with no path segment")
        void noPathSegment() {
            assertEquals("ROOT",
                    TomcatProcessHandler.extractContextNameFromBrowserUrl("http://localhost:8080"));
        }

        @Test
        @DisplayName("extracts first path segment as context name")
        void simpleContextPath() {
            assertEquals("portal",
                    TomcatProcessHandler.extractContextNameFromBrowserUrl("http://localhost:8080/portal"));
        }

        @Test
        @DisplayName("extracts first segment and ignores sub-paths")
        void subPath() {
            assertEquals("myapp",
                    TomcatProcessHandler.extractContextNameFromBrowserUrl("http://localhost:8080/myapp/login?user=x"));
        }

        @Test
        @DisplayName("handles HTTPS scheme")
        void httpsScheme() {
            assertEquals("secure",
                    TomcatProcessHandler.extractContextNameFromBrowserUrl("https://localhost:8443/secure"));
        }

        @Test
        @DisplayName("returns null for unparseable URL")
        void malformedUrl() {
            // Should not throw — returns null gracefully
            assertNull(TomcatProcessHandler.extractContextNameFromBrowserUrl("not a valid url ://"));
        }
    }

    // -------------------------------------------------------------------------
    // rewritePortIfNeeded
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("rewritePortIfNeeded")
    class RewritePortTests {

        @Test
        @DisplayName("returns URL unchanged when port already matches")
        void portAlreadyMatches() {
            String url = "http://localhost:8080/app";
            assertEquals(url, TomcatProcessHandler.rewritePortIfNeeded(url, 8080));
        }

        @Test
        @DisplayName("rewrites port when auto-resolved port differs from configured")
        void rewritesDifferentPort() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://localhost:8080/app", 8090);
            assertTrue(result.contains(":8090"), "Port should be rewritten to 8090");
            assertTrue(result.contains("/app"), "Path should be preserved");
        }

        @Test
        @DisplayName("preserves URL without explicit port")
        void noExplicitPort() {
            String url = "http://localhost/app";
            // URI.getPort() returns -1 when no port → no rewrite
            assertEquals(url, TomcatProcessHandler.rewritePortIfNeeded(url, 8090));
        }

        @Test
        @DisplayName("preserves query string and fragment after port rewrite")
        void preservesQueryAndFragment() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://localhost:8080/app?tab=1#section", 9090);
            assertTrue(result.contains(":9090"));
            assertTrue(result.contains("?tab=1") || result.contains("%3Ftab%3D1") || result.contains("tab=1"));
            assertTrue(result.contains("section"));
        }

        @Test
        @DisplayName("returns URL unchanged for malformed input")
        void malformedUrl() {
            String url = "not a url";
            assertEquals(url, TomcatProcessHandler.rewritePortIfNeeded(url, 8080));
        }

        @Test
        @DisplayName("handles HTTPS URL port rewrite")
        void httpsPortRewrite() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "https://localhost:8443/secure", 9443);
            assertTrue(result.contains(":9443"));
            assertTrue(result.startsWith("https://"));
        }

        // Host-gated rewrite — see rewritePortIfNeeded javadoc. The safety net
        // is strictly for loopback URLs where the port refers to THIS Tomcat.
        // A custom URL aimed at a proxy, CDN, or port-forward has its port
        // chosen deliberately and must not be silently mutated.

        @Test
        @DisplayName("leaves proxy URL unchanged (non-loopback host)")
        void nonLoopbackProxyUrlIsUnchanged() {
            String url = "http://proxy.example.com:9090/app";
            assertEquals(url, TomcatProcessHandler.rewritePortIfNeeded(url, 8087));
        }

        @Test
        @DisplayName("leaves CDN URL unchanged even with localhost-ish name")
        void nonLoopbackLocalhostishIsUnchanged() {
            // "localhost.example.com" is NOT localhost — it resolves to a
            // remote host despite the prefix. The loopback test must match the
            // exact host, not a prefix.
            String url = "http://localhost.example.com:9090/app";
            assertEquals(url, TomcatProcessHandler.rewritePortIfNeeded(url, 8087));
        }

        @Test
        @DisplayName("rewrites 127.0.0.1 URL")
        void ipv4LoopbackIsRewritten() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://127.0.0.1:8080/app", 8087);
            assertTrue(result.contains(":8087"));
            assertTrue(result.contains("127.0.0.1"));
        }

        @Test
        @DisplayName("rewrites IPv6 loopback [::1]")
        void ipv6LoopbackShortFormIsRewritten() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://[::1]:8080/app", 8087);
            assertTrue(result.contains(":8087"), "port should be rewritten, got: " + result);
            assertTrue(result.contains("[::1]") || result.contains("::1"),
                    "host should still be IPv6 loopback, got: " + result);
        }

        @Test
        @DisplayName("rewrites expanded IPv6 loopback [0:0:0:0:0:0:0:1]")
        void ipv6LoopbackExpandedFormIsRewritten() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://[0:0:0:0:0:0:0:1]:8080/app", 8087);
            assertTrue(result.contains(":8087"), "port should be rewritten, got: " + result);
        }

        @Test
        @DisplayName("localhost host-matching is case-insensitive")
        void localhostIsCaseInsensitive() {
            String result = TomcatProcessHandler.rewritePortIfNeeded(
                    "http://LOCALHOST:8080/app", 8087);
            assertTrue(result.contains(":8087"), "port should be rewritten, got: " + result);
        }
    }
}
