package com.dev.idea.plugins.tomcat.model.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RemoteConfig")
class RemoteConfigTest {

    private RemoteConfig config;

    @BeforeEach
    void setUp() {
        config = new RemoteConfig();
    }

    // =========================================================================
    // URL validation
    // =========================================================================

    @Nested
    @DisplayName("Manager URL validation")
    class UrlValidation {

        @Test
        @DisplayName("default URL is valid")
        void defaultUrl() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://localhost:8080/manager"));
        }

        @Test
        @DisplayName("HTTPS URL is valid")
        void httpsUrl() {
            assertTrue(RemoteConfig.isValidManagerUrl("https://server.example.com:443/manager"));
        }

        @Test
        @DisplayName("URL without port is valid")
        void noPort() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://localhost/manager"));
        }

        @Test
        @DisplayName("URL with subpath is valid")
        void withSubpath() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://localhost:8080/manager/text"));
        }

        @Test
        @DisplayName("rejects URL without /manager")
        void noManagerPath() {
            assertFalse(RemoteConfig.isValidManagerUrl("http://localhost:8080/admin"));
        }

        @Test
        @DisplayName("rejects empty URL")
        void emptyUrl() {
            assertFalse(RemoteConfig.isValidManagerUrl(""));
        }

        @Test
        @DisplayName("rejects non-HTTP protocol")
        void nonHttpProtocol() {
            assertFalse(RemoteConfig.isValidManagerUrl("ftp://localhost/manager"));
        }

        @Test
        @DisplayName("rejects port out of range (0)")
        void portZero() {
            assertFalse(RemoteConfig.isValidManagerUrl("http://localhost:0/manager"));
        }

        @Test
        @DisplayName("rejects port out of range (99999)")
        void portTooHigh() {
            assertFalse(RemoteConfig.isValidManagerUrl("http://localhost:99999/manager"));
        }

        @Test
        @DisplayName("accepts max valid port 65535")
        void maxPort() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://localhost:65535/manager"));
        }

        @Test
        @DisplayName("accepts port 1")
        void minPort() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://localhost:1/manager"));
        }

        @Test
        @DisplayName("accepts bracketed IPv6 loopback")
        void ipv6Loopback() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://[::1]:8080/manager"));
        }

        @Test
        @DisplayName("accepts bracketed IPv6 link-local with port")
        void ipv6LinkLocal() {
            assertTrue(RemoteConfig.isValidManagerUrl("https://[fe80::1]:8443/manager"));
        }

        @Test
        @DisplayName("accepts bracketed IPv6 global address without port")
        void ipv6NoPort() {
            assertTrue(RemoteConfig.isValidManagerUrl("http://[2001:db8::1]/manager"));
        }

        @Test
        @DisplayName("rejects unbracketed IPv6 literal — port colon would be ambiguous")
        void ipv6Unbracketed() {
            assertFalse(RemoteConfig.isValidManagerUrl("http://::1:8080/manager"));
        }
    }

    // =========================================================================
    // Manager URL setter
    // =========================================================================

    @Nested
    @DisplayName("setManagerUrl")
    class SetManagerUrl {

        @Test
        @DisplayName("valid URL is accepted")
        void validUrl() {
            config.setManagerUrl("http://myserver:9090/manager");
            assertEquals("http://myserver:9090/manager", config.getManagerUrl());
        }

        @Test
        @DisplayName("invalid URL falls back to default")
        void invalidFallsBack() {
            config.setManagerUrl("not-a-url");
            assertEquals("http://localhost:8080/manager", config.getManagerUrl());
        }

        @Test
        @DisplayName("empty URL falls back to default")
        void emptyFallsBack() {
            config.setManagerUrl("");
            assertEquals("http://localhost:8080/manager", config.getManagerUrl());
        }

        @Test
        @DisplayName("bracketed IPv6 URL round-trips without silent fallback to localhost")
        void ipv6RoundTrip() {
            // The whole UI pipeline (buildManagerUrl → new RemoteConfig → setManagerUrl)
            // must preserve an IPv6 URL. Before the fix, MANAGER_URL_PATTERN rejected
            // bracketed hosts and setManagerUrl silently replaced the URL with
            // http://localhost:8080/manager — so a user configuring an IPv6 Tomcat
            // would unknowingly test/save against localhost.
            config.setManagerUrl("http://[::1]:8080/manager");
            assertEquals("http://[::1]:8080/manager", config.getManagerUrl());
        }

        @Test
        @DisplayName("bracketed IPv6 constructor variant round-trips")
        void ipv6Constructor() {
            RemoteConfig rc = new RemoteConfig(
                    "https://[2001:db8::1]:8443/manager", "admin", "pw", false);
            assertEquals("https://[2001:db8::1]:8443/manager", rc.getManagerUrl());
        }
    }

    // =========================================================================
    // Credentials
    // =========================================================================

    @Nested
    @DisplayName("Credentials handling")
    class Credentials {

        @Test
        @DisplayName("default username is admin")
        void defaultUsername() {
            assertEquals("admin", config.getUsername());
        }

        @Test
        @DisplayName("valid username is accepted")
        void validUsername() {
            config.setUsername("deployer");
            assertEquals("deployer", config.getUsername());
        }

        @Test
        @DisplayName("empty username is accepted for no-credentials mode")
        void emptyUsernameAccepted() {
            config.setUsername("");
            assertEquals("", config.getUsername());
        }

        @Test
        @DisplayName("too long username falls back to default")
        void longUsernameFallsBack() {
            config.setUsername("a".repeat(200));
            assertEquals("admin", config.getUsername());
        }

        @Test
        @DisplayName("password is stored and retrieved")
        void password() {
            config.setPassword("secret123");
            assertEquals("secret123", config.getPassword());
        }

        @Test
        @DisplayName("null password becomes empty")
        void nullPassword() {
            config.setPassword(null);
            assertEquals("", config.getPassword());
        }

        @Test
        @DisplayName("too long password is cleared")
        void longPasswordCleared() {
            config.setPassword("x".repeat(300));
            assertEquals("", config.getPassword());
        }

        @Test
        @DisplayName("useCredentials defaults to false")
        void credentialsDefault() {
            assertFalse(config.isUseCredentials());
        }

        @Test
        @DisplayName("hasValidCredentials requires useCredentials and non-empty username")
        void hasValidCredentials() {
            assertFalse(config.hasValidCredentials());

            config.setUseCredentials(true);
            config.setUsername("deploy");
            config.setPassword("pass");
            assertTrue(config.hasValidCredentials());
        }
    }

    // =========================================================================
    // Validation
    // =========================================================================

    @Nested
    @DisplayName("isValid")
    class Validation {

        @Test
        @DisplayName("default config is valid")
        void defaultIsValid() {
            assertTrue(config.isValid());
        }

        @Test
        @DisplayName("config with credentials is valid when credentials present")
        void withCredentials() {
            config.setUseCredentials(true);
            config.setUsername("admin");
            config.setPassword("pass");
            assertTrue(config.isValid());
        }
    }

    // =========================================================================
    // Clone and equals
    // =========================================================================

    @Nested
    @DisplayName("Clone and equals")
    class CloneEquals {

        @Test
        @DisplayName("clone produces equal copy")
        void cloneEquals() {
            config.setManagerUrl("http://prod:8080/manager");
            config.setUsername("deploy");
            config.setPassword("secret");
            config.setUseCredentials(true);

            RemoteConfig cloned = config.clone();
            assertEquals(config, cloned);
            assertNotSame(config, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            RemoteConfig cloned = config.clone();
            cloned.setUsername("other");
            assertNotEquals(config.getUsername(), cloned.getUsername());
        }

        @Test
        @DisplayName("equals is symmetric")
        void equalsSymmetric() {
            RemoteConfig a = new RemoteConfig("http://host:8080/manager", "user", "pass", true);
            RemoteConfig b = new RemoteConfig("http://host:8080/manager", "user", "pass", true);
            assertEquals(a, b);
            assertEquals(b, a);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different configs are not equal")
        void notEqual() {
            RemoteConfig a = new RemoteConfig();
            RemoteConfig b = new RemoteConfig();
            b.setUseCredentials(true);
            assertNotEquals(a, b);
        }
    }

    // =========================================================================
    // toString
    // =========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("toString masks password")
        void masksPassword() {
            config.setPassword("supersecret");
            String str = config.toString();
            assertFalse(str.contains("supersecret"), "Password should be masked");
            assertTrue(str.contains("RemoteConfig{"));
        }

        @Test
        @DisplayName("toString shows empty password indicator")
        void emptyPassword() {
            config.setPassword("");
            String str = config.toString();
            assertTrue(str.contains("RemoteConfig{"));
        }
    }
}
