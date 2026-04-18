package com.dev.idea.plugins.tomcat.ui.server.sections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BrowserLaunchSection")
class BrowserLaunchSectionTest {

    @Nested
    @DisplayName("replacePortInUrl")
    class ReplacePortInUrl {

        @Test
        @DisplayName("replaces port on localhost URL")
        void localhost() {
            assertEquals("http://localhost:9090/myapp",
                    BrowserLaunchSection.replacePortInUrl("http://localhost:8080/myapp", "9090"));
        }

        @Test
        @DisplayName("replaces port on custom host")
        void customHost() {
            assertEquals("http://127.0.0.1:9090/api",
                    BrowserLaunchSection.replacePortInUrl("http://127.0.0.1:8080/api", "9090"));
        }

        @Test
        @DisplayName("replaces port on https URL")
        void https() {
            assertEquals("https://example.com:9443/app",
                    BrowserLaunchSection.replacePortInUrl("https://example.com:8443/app", "9443"));
        }

        @Test
        @DisplayName("handles URL without path")
        void noPath() {
            assertEquals("http://localhost:9090",
                    BrowserLaunchSection.replacePortInUrl("http://localhost:8080", "9090"));
        }

        @Test
        @DisplayName("preserves query string")
        void withQueryString() {
            assertEquals("http://localhost:9090/app?foo=bar",
                    BrowserLaunchSection.replacePortInUrl("http://localhost:8080/app?foo=bar", "9090"));
        }

        @Test
        @DisplayName("returns null for URL without scheme")
        void noScheme() {
            assertNull(BrowserLaunchSection.replacePortInUrl("localhost:8080/app", "9090"));
        }

        @Test
        @DisplayName("returns null for URL without port")
        void noPort() {
            assertNull(BrowserLaunchSection.replacePortInUrl("http://localhost/app", "9090"));
        }

        @Test
        @DisplayName("returns null when no digits follow colon")
        void noDigitsAfterColon() {
            assertNull(BrowserLaunchSection.replacePortInUrl("http://localhost:/app", "9090"));
        }
    }

    @Nested
    @DisplayName("looksLikeLegacyAutoUrl")
    class LooksLikeLegacyAutoUrl {

        @Test
        @DisplayName("matches stale localhost URL with same context")
        void localhostSameContext() {
            assertTrue(BrowserLaunchSection.looksLikeLegacyAutoUrl(
                    "http://localhost:8083/myapp", "/myapp"));
        }

        @Test
        @DisplayName("matches root context")
        void rootContext() {
            assertTrue(BrowserLaunchSection.looksLikeLegacyAutoUrl(
                    "http://localhost:8083/", "/"));
        }

        @Test
        @DisplayName("rejects custom host")
        void customHost() {
            assertFalse(BrowserLaunchSection.looksLikeLegacyAutoUrl(
                    "http://127.0.0.1:8083/myapp", "/myapp"));
        }

        @Test
        @DisplayName("rejects different context path")
        void differentContext() {
            assertFalse(BrowserLaunchSection.looksLikeLegacyAutoUrl(
                    "http://localhost:8083/other", "/myapp"));
        }

        @Test
        @DisplayName("rejects query string")
        void queryString() {
            assertFalse(BrowserLaunchSection.looksLikeLegacyAutoUrl(
                    "http://localhost:8083/myapp?x=1", "/myapp"));
        }
    }
}
