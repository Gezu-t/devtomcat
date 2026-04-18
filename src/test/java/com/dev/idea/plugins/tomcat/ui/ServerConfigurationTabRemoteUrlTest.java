package com.dev.idea.plugins.tomcat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract of the host normalisation that feeds
 * {@code RemoteConnectionSection.buildManagerUrl()}. A failing case here means
 * a manager URL could be malformed before it ever reaches Tomcat — high-value
 * to protect against regressions.
 *
 * <p>Uses reflection to reach the package-private helper because the class
 * under test is a private nested class of {@link ServerConfigurationTab}.
 */
class ServerConfigurationTabRemoteUrlTest {

    private static String normaliseHost(String raw) throws Exception {
        Class<?> cls = Class.forName(
                "com.dev.idea.plugins.tomcat.ui.ServerConfigurationTab$RemoteConnectionSection");
        Method m = cls.getDeclaredMethod("normaliseHost", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @Nested
    @DisplayName("normaliseHost")
    class NormaliseHost {

        @Test
        @DisplayName("passes through simple hostnames untouched")
        void simpleHost() throws Exception {
            assertEquals("localhost", normaliseHost("localhost"));
            assertEquals("example.com", normaliseHost("example.com"));
            assertEquals("10.0.0.1", normaliseHost("10.0.0.1"));
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void trimsWhitespace() throws Exception {
            assertEquals("localhost", normaliseHost("  localhost  "));
        }

        @Test
        @DisplayName("strips a user-pasted scheme prefix")
        void stripsScheme() throws Exception {
            assertEquals("example.com", normaliseHost("http://example.com"));
            assertEquals("example.com", normaliseHost("https://example.com"));
        }

        @Test
        @DisplayName("strips a trailing slash and path so the /manager suffix never doubles")
        void stripsTrailingPath() throws Exception {
            assertEquals("localhost", normaliseHost("localhost/"));
            assertEquals("localhost", normaliseHost("localhost/manager"));
            assertEquals("example.com", normaliseHost("http://example.com/"));
        }

        @Test
        @DisplayName("strips an inline :port so the Port field is authoritative")
        void stripsInlinePort() throws Exception {
            assertEquals("localhost", normaliseHost("localhost:8080"));
            assertEquals("example.com", normaliseHost("http://example.com:9090"));
        }

        @Test
        @DisplayName("wraps bare IPv6 literals in brackets per RFC 3986")
        void wrapsIpv6() throws Exception {
            assertEquals("[::1]", normaliseHost("::1"));
            assertEquals("[fe80::1]", normaliseHost("fe80::1"));
            assertEquals("[2001:db8::1]", normaliseHost("2001:db8::1"));
        }

        @Test
        @DisplayName("preserves already-bracketed IPv6 literals")
        void preservesBracketedIpv6() throws Exception {
            assertEquals("[::1]", normaliseHost("[::1]"));
            assertEquals("[2001:db8::1]", normaliseHost("[2001:db8::1]"));
        }

        @Test
        @DisplayName("scheme strip followed by bracket wrap works for IPv6 URLs")
        void schemeAndIpv6() throws Exception {
            // http://::1 — scheme strip leaves "::1", bracket wrap produces "[::1]"
            assertEquals("[::1]", normaliseHost("http://::1"));
        }

        @Test
        @DisplayName("bracketed IPv6 followed by :port has the port stripped so Port field wins")
        void bracketedIpv6WithInlinePort() throws Exception {
            // Previously this left ":9090" attached to "[::1]", producing
            // "http://[::1]:9090:8080/manager" when buildManagerUrl appended
            // the Port field. After the fix, anything past ']' is dropped.
            assertEquals("[::1]", normaliseHost("[::1]:9090"));
        }

        @Test
        @DisplayName("full pasted IPv6 URL with scheme, brackets, port, and path normalises to bare bracketed host")
        void fullPastedIpv6Url() throws Exception {
            // The regression case Codex flagged: http://[::1]:9090/manager
            assertEquals("[::1]", normaliseHost("http://[::1]:9090/manager"));
            assertEquals("[2001:db8::1]", normaliseHost("https://[2001:db8::1]:8443/manager/text"));
        }
    }
}
