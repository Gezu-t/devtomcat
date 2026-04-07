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
    }
}
