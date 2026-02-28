package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.intellij.openapi.options.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PortValidator")
class PortValidatorTest {

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("valid config produces no errors")
        void validConfigNoErrors() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .httpsPort(8443)
                    .httpsEnabled(false)
                    .jmxPort(9010)
                    .jmxEnabled(false)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertFalse(result.hasErrors());
        }

        @Test
        @DisplayName("null required HTTP port produces error")
        void nullRequiredHttpPortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(null)
                    .shutdownPort(8005)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
            assertTrue(result.getErrorMessage().contains("HTTP"));
        }

        @Test
        @DisplayName("null required shutdown port produces error")
        void nullRequiredShutdownPortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(null)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
            assertTrue(result.getErrorMessage().contains("Shutdown"));
        }

        @Test
        @DisplayName("out-of-range port produces error")
        void outOfRangePortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(70000)
                    .shutdownPort(8005)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
            assertTrue(result.getErrorMessage().contains("HTTP"));
        }

        @Test
        @DisplayName("zero port produces error")
        void zeroPortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(0)
                    .shutdownPort(8005)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
        }

        @Test
        @DisplayName("negative port produces error")
        void negativePortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(-1)
                    .shutdownPort(8005)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
        }

        @Test
        @DisplayName("duplicate ports produce error")
        void duplicatePortsError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8080)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
        }

        @Test
        @DisplayName("disabled HTTPS port not validated for range")
        void disabledHttpsPortSkipped() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .httpsPort(null)
                    .httpsEnabled(false)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertFalse(result.getErrorMessage().contains("HTTPS"));
        }

        @Test
        @DisplayName("enabled HTTPS with null port produces error")
        void enabledHttpsNullPortError() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .httpsPort(null)
                    .httpsEnabled(true)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
            assertTrue(result.getErrorMessage().contains("HTTPS"));
        }

        @Test
        @DisplayName("disabled JMX port not validated")
        void disabledJmxPortSkipped() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .jmxPort(null)
                    .jmxEnabled(false)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertFalse(result.getErrorMessage().contains("JMX"));
        }

        @Test
        @DisplayName("enabled JMX with valid port and conflict detected")
        void enabledJmxConflict() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .jmxPort(8080)
                    .jmxEnabled(true)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertTrue(result.hasErrors());
        }

        @Test
        @DisplayName("AJP disabled port is not checked")
        void disabledAjpSkipped() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .ajpPort(null)
                    .ajpEnabled(false)
                    .build();

            ValidationResult result = PortValidator.validate(ports);
            assertFalse(result.getErrorMessage().contains("AJP"));
        }
    }

    @Nested
    @DisplayName("validateOrThrow")
    class ValidateOrThrow {

        @Test
        @DisplayName("valid config does not throw")
        void validConfigNoException() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .build();

            assertDoesNotThrow(() -> PortValidator.validateOrThrow(ports));
        }

        @Test
        @DisplayName("errors throw ConfigurationException")
        void errorsThrowException() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(null)
                    .shutdownPort(null)
                    .build();

            assertThrows(ConfigurationException.class, () -> PortValidator.validateOrThrow(ports));
        }

        @Test
        @DisplayName("warnings alone do not throw")
        void warningsDoNotThrow() {
            // Use valid ports that are likely in use to generate warnings
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(8080)
                    .shutdownPort(8005)
                    .build();

            assertDoesNotThrow(() -> PortValidator.validateOrThrow(ports));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("builder produces correct PortConfiguration")
        void builderProducesCorrectConfig() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder()
                    .httpPort(9090)
                    .shutdownPort(9005)
                    .httpsPort(9443)
                    .httpsEnabled(true)
                    .jmxPort(9010)
                    .jmxEnabled(true)
                    .ajpPort(9009)
                    .ajpEnabled(true)
                    .build();

            assertEquals(9090, ports.httpPort);
            assertEquals(9005, ports.shutdownPort);
            assertEquals(9443, ports.httpsPort);
            assertTrue(ports.httpsEnabled);
            assertEquals(9010, ports.jmxPort);
            assertTrue(ports.jmxEnabled);
            assertEquals(9009, ports.ajpPort);
            assertTrue(ports.ajpEnabled);
        }

        @Test
        @DisplayName("builder defaults are null/false")
        void builderDefaults() {
            PortValidator.PortConfiguration ports = PortValidator.PortConfiguration.builder().build();

            assertNull(ports.httpPort);
            assertNull(ports.shutdownPort);
            assertNull(ports.httpsPort);
            assertFalse(ports.httpsEnabled);
            assertNull(ports.jmxPort);
            assertFalse(ports.jmxEnabled);
            assertNull(ports.ajpPort);
            assertFalse(ports.ajpEnabled);
        }

        @Test
        @DisplayName("fluent API allows chaining")
        void fluentChaining() {
            PortValidator.PortConfiguration.Builder builder = PortValidator.PortConfiguration.builder();
            PortValidator.PortConfiguration.Builder result = builder
                    .httpPort(8080)
                    .shutdownPort(8005);

            assertSame(builder, result);
        }
    }

    @Nested
    @DisplayName("PortConfiguration constructor")
    class PortConfigurationConstructor {

        @Test
        @DisplayName("constructor sets all fields")
        void constructorSetsFields() {
            PortValidator.PortConfiguration ports = new PortValidator.PortConfiguration(
                    8080, 8005, 8443, true, 9010, true, 8009, true);

            assertEquals(8080, ports.httpPort);
            assertEquals(8005, ports.shutdownPort);
            assertEquals(8443, ports.httpsPort);
            assertTrue(ports.httpsEnabled);
            assertEquals(9010, ports.jmxPort);
            assertTrue(ports.jmxEnabled);
            assertEquals(8009, ports.ajpPort);
            assertTrue(ports.ajpEnabled);
        }

        @Test
        @DisplayName("constructor allows null port values")
        void constructorAllowsNulls() {
            PortValidator.PortConfiguration ports = new PortValidator.PortConfiguration(
                    null, null, null, false, null, false, null, false);

            assertNull(ports.httpPort);
            assertNull(ports.shutdownPort);
        }
    }
}
