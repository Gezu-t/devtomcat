package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.options.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PortUtils")
class PortUtilsTest {

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("DEFAULT_HTTP is 8080")
        void defaultHttp() {
            assertEquals(8080, PortUtils.DEFAULT_HTTP);
        }

        @Test
        @DisplayName("DEFAULT_HTTPS is 8443")
        void defaultHttps() {
            assertEquals(8443, PortUtils.DEFAULT_HTTPS);
        }

        @Test
        @DisplayName("DEFAULT_SHUTDOWN is 8005")
        void defaultShutdown() {
            assertEquals(8005, PortUtils.DEFAULT_SHUTDOWN);
        }

        @Test
        @DisplayName("DEFAULT_JMX is 1099")
        void defaultJmx() {
            assertEquals(1099, PortUtils.DEFAULT_JMX);
        }

        @Test
        @DisplayName("MIN_PORT is 1")
        void minPort() {
            assertEquals(1, PortUtils.MIN_PORT);
        }

        @Test
        @DisplayName("MAX_PORT is 65535")
        void maxPort() {
            assertEquals(65535, PortUtils.MAX_PORT);
        }
    }

    @Nested
    @DisplayName("isValid(int)")
    class IsValidInt {

        @Test
        @DisplayName("port 0 is invalid")
        void zeroIsInvalid() {
            assertFalse(PortUtils.isValid(0));
        }

        @Test
        @DisplayName("port 1 is valid")
        void oneIsValid() {
            assertTrue(PortUtils.isValid(1));
        }

        @Test
        @DisplayName("port 65535 is valid")
        void maxIsValid() {
            assertTrue(PortUtils.isValid(65535));
        }

        @Test
        @DisplayName("port 65536 is invalid")
        void aboveMaxIsInvalid() {
            assertFalse(PortUtils.isValid(65536));
        }

        @Test
        @DisplayName("negative port is invalid")
        void negativeIsInvalid() {
            assertFalse(PortUtils.isValid(-1));
        }

        @Test
        @DisplayName("typical port 8080 is valid")
        void typicalPortIsValid() {
            assertTrue(PortUtils.isValid(8080));
        }
    }

    @Nested
    @DisplayName("isValid(String)")
    class IsValidString {

        @Test
        @DisplayName("null is invalid")
        void nullIsInvalid() {
            assertFalse(PortUtils.isValid((String) null));
        }

        @Test
        @DisplayName("empty string is invalid")
        void emptyIsInvalid() {
            assertFalse(PortUtils.isValid(""));
        }

        @Test
        @DisplayName("non-numeric string is invalid")
        void nonNumericIsInvalid() {
            assertFalse(PortUtils.isValid("abc"));
        }

        @Test
        @DisplayName("valid port string is valid")
        void validPortString() {
            assertTrue(PortUtils.isValid("8080"));
        }

        @Test
        @DisplayName("zero string is invalid")
        void zeroStringIsInvalid() {
            assertFalse(PortUtils.isValid("0"));
        }

        @Test
        @DisplayName("out of range port string is invalid")
        void outOfRangeString() {
            assertFalse(PortUtils.isValid("70000"));
        }
    }

    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("invalid port returns false")
        void invalidPortReturnsFalse() {
            assertFalse(PortUtils.isAvailable(0));
        }

        @Test
        @DisplayName("high ephemeral port is likely available")
        void highPortIsAvailable() {
            // Port 59999 is very unlikely to be in use
            assertTrue(PortUtils.isAvailable(59999));
        }
    }

    @Nested
    @DisplayName("findAvailable")
    class FindAvailable {

        @Test
        @DisplayName("finds available port in range")
        void findsPortInRange() {
            int port = PortUtils.findAvailable(59990, 59999);
            assertTrue(port >= 59990 && port <= 59999);
        }

        @Test
        @DisplayName("returns -1 for invalid range")
        void invalidRangeReturnsNegOne() {
            // Start > end
            int port = PortUtils.findAvailable(100, 99);
            assertEquals(-1, port);
        }
    }

    @Nested
    @DisplayName("findNextAvailable")
    class FindNextAvailable {

        @Test
        @DisplayName("returns preferred port when available")
        void returnsPreferredWhenAvailable() {
            // High ephemeral port likely available
            int port = PortUtils.findNextAvailable(59998);
            assertEquals(59998, port);
        }

        @Test
        @DisplayName("returns valid port even when preferred unavailable")
        void findsAlternativePort() {
            int port = PortUtils.findNextAvailable(1);
            assertTrue(PortUtils.isValid(port));
        }
    }

    @Nested
    @DisplayName("parsePort")
    class ParsePort {

        @Test
        @DisplayName("parses valid port string")
        void parsesValidPort() throws ConfigurationException {
            Integer port = PortUtils.parsePort("8080", "HTTP");
            assertEquals(8080, port);
        }

        @Test
        @DisplayName("empty string returns null")
        void emptyReturnsNull() throws ConfigurationException {
            assertNull(PortUtils.parsePort("", "HTTP"));
        }

        @Test
        @DisplayName("whitespace-only string returns null")
        void whitespaceReturnsNull() throws ConfigurationException {
            assertNull(PortUtils.parsePort("   ", "HTTP"));
        }

        @Test
        @DisplayName("non-numeric throws ConfigurationException")
        void nonNumericThrows() {
            assertThrows(ConfigurationException.class, () -> PortUtils.parsePort("abc", "HTTP"));
        }

        @Test
        @DisplayName("exception message contains port name")
        void exceptionMessageContainsPortName() {
            ConfigurationException ex = assertThrows(ConfigurationException.class,
                    () -> PortUtils.parsePort("xyz", "Shutdown"));
            assertTrue(ex.getMessage().contains("Shutdown"));
        }

        @Test
        @DisplayName("trims whitespace before parsing")
        void trimsWhitespace() throws ConfigurationException {
            Integer port = PortUtils.parsePort("  9090  ", "HTTP");
            assertEquals(9090, port);
        }
    }
}
