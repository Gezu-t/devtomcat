package com.dev.idea.plugins.tomcat.environment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DynamicTomcatEnvironment")
class DynamicTomcatEnvironmentTest {

    @Nested
    @DisplayName("EnvironmentMode.fromString")
    class EnvironmentModeParsingTests {

        @Test
        @DisplayName("null returns DEVELOPMENT")
        void nullReturnsDevelopment() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.DEVELOPMENT,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString(null));
        }

        @Test
        @DisplayName("empty string returns DEVELOPMENT")
        void emptyReturnsDevelopment() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.DEVELOPMENT,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString(""));
        }

        @Test
        @DisplayName("'production' returns PRODUCTION")
        void productionString() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.PRODUCTION,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("production"));
        }

        @Test
        @DisplayName("'prod' substring returns PRODUCTION")
        void prodSubstring() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.PRODUCTION,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("prod"));
        }

        @Test
        @DisplayName("'PRODUCTION' case-insensitive returns PRODUCTION")
        void productionCaseInsensitive() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.PRODUCTION,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("PRODUCTION"));
        }

        @Test
        @DisplayName("'staging' returns STAGING")
        void stagingString() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.STAGING,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("staging"));
        }

        @Test
        @DisplayName("'stage' substring returns STAGING")
        void stageSubstring() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.STAGING,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("stage"));
        }

        @Test
        @DisplayName("'development' returns DEVELOPMENT")
        void developmentString() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.DEVELOPMENT,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("development"));
        }

        @Test
        @DisplayName("unknown string returns DEVELOPMENT")
        void unknownReturnsDevelopment() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.DEVELOPMENT,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("test"));
        }

        @Test
        @DisplayName("whitespace-padded string is trimmed")
        void whitespaceIsTrimmed() {
            assertEquals(DynamicTomcatEnvironment.EnvironmentMode.PRODUCTION,
                    DynamicTomcatEnvironment.EnvironmentMode.fromString("  production  "));
        }
    }

    @Nested
    @DisplayName("EnvironmentMode enum")
    class EnvironmentModeEnumTests {

        @Test
        @DisplayName("getValue returns lowercase name")
        void getValueReturnsLowercase() {
            assertEquals("development", DynamicTomcatEnvironment.EnvironmentMode.DEVELOPMENT.getValue());
            assertEquals("staging", DynamicTomcatEnvironment.EnvironmentMode.STAGING.getValue());
            assertEquals("production", DynamicTomcatEnvironment.EnvironmentMode.PRODUCTION.getValue());
        }

        @Test
        @DisplayName("all enum values are covered")
        void allValuesExist() {
            assertEquals(3, DynamicTomcatEnvironment.EnvironmentMode.values().length);
        }
    }

    @Nested
    @DisplayName("buildJavaOpts")
    class BuildJavaOptsTests {

        @Test
        @DisplayName("includes heap settings")
        void includesHeapSettings() {
            String opts = DynamicTomcatEnvironment.buildJavaOpts();
            assertTrue(opts.contains("-Xmx"), "Should contain -Xmx");
            assertTrue(opts.contains("-Xms"), "Should contain -Xms");
        }

        @Test
        @DisplayName("includes encoding")
        void includesEncoding() {
            String opts = DynamicTomcatEnvironment.buildJavaOpts();
            assertTrue(opts.contains("-Dfile.encoding=UTF-8"), "Should set UTF-8 encoding");
        }

        @Test
        @DisplayName("includes G1GC")
        void includesG1GC() {
            String opts = DynamicTomcatEnvironment.buildJavaOpts();
            assertTrue(opts.contains("-XX:+UseG1GC"), "Should use G1GC");
        }

        @Test
        @DisplayName("result is trimmed with no trailing whitespace")
        void resultIsTrimmed() {
            String opts = DynamicTomcatEnvironment.buildJavaOpts();
            assertEquals(opts.trim(), opts, "Should not have trailing whitespace");
        }
    }

    @Nested
    @DisplayName("buildCatalinaOpts")
    class BuildCatalinaOptsTests {

        @Test
        @DisplayName("includes encoding")
        void includesEncoding() {
            String opts = DynamicTomcatEnvironment.buildCatalinaOpts();
            assertTrue(opts.contains("-Dfile.encoding=UTF-8"));
        }

        @Test
        @DisplayName("includes spring profile")
        void includesSpringProfile() {
            String opts = DynamicTomcatEnvironment.buildCatalinaOpts();
            assertTrue(opts.contains("-Dspring.profiles.active="));
        }

        @Test
        @DisplayName("includes server port")
        void includesServerPort() {
            String opts = DynamicTomcatEnvironment.buildCatalinaOpts();
            assertTrue(opts.contains("-Dserver.port="));
        }

        @Test
        @DisplayName("result is trimmed")
        void resultIsTrimmed() {
            String opts = DynamicTomcatEnvironment.buildCatalinaOpts();
            assertEquals(opts.trim(), opts);
        }
    }

    @Nested
    @DisplayName("buildEnvironmentVariables")
    class BuildEnvironmentVariablesTests {

        @Test
        @DisplayName("contains all required keys")
        void containsRequiredKeys() {
            Map<String, String> env = DynamicTomcatEnvironment.buildEnvironmentVariables();
            assertTrue(env.containsKey("JAVA_OPTS"), "Should contain JAVA_OPTS");
            assertTrue(env.containsKey("CATALINA_OPTS"), "Should contain CATALINA_OPTS");
            assertTrue(env.containsKey("JDK_JAVA_OPTIONS"), "Should contain JDK_JAVA_OPTIONS");
            assertTrue(env.containsKey("TOMCAT_PLUGIN_ENV"), "Should contain TOMCAT_PLUGIN_ENV");
            assertTrue(env.containsKey("TZ"), "Should contain TZ");
        }

        @Test
        @DisplayName("TZ is UTC")
        void tzIsUtc() {
            Map<String, String> env = DynamicTomcatEnvironment.buildEnvironmentVariables();
            assertEquals("UTC", env.get("TZ"));
        }

        @Test
        @DisplayName("JDK_JAVA_OPTIONS contains add-opens")
        void jdkOptionsContainAddOpens() {
            Map<String, String> env = DynamicTomcatEnvironment.buildEnvironmentVariables();
            String jdkOpts = env.get("JDK_JAVA_OPTIONS");
            assertTrue(jdkOpts.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        }

        @Test
        @DisplayName("no values are null")
        void noNullValues() {
            Map<String, String> env = DynamicTomcatEnvironment.buildEnvironmentVariables();
            env.forEach((k, v) -> assertNotNull(v, "Value for " + k + " should not be null"));
        }
    }

    @Nested
    @DisplayName("Display helpers")
    class DisplayHelperTests {

        @Test
        @DisplayName("getEnvironmentName is never null")
        void environmentNameNeverNull() {
            assertNotNull(DynamicTomcatEnvironment.getEnvironmentName());
        }

        @Test
        @DisplayName("getEnvironmentDisplayName is capitalized")
        void displayNameIsCapitalized() {
            String name = DynamicTomcatEnvironment.getEnvironmentDisplayName();
            assertTrue(Character.isUpperCase(name.charAt(0)),
                    "Display name should start with uppercase: " + name);
        }

        @Test
        @DisplayName("getConfigurationSummary is never null")
        void configSummaryNeverNull() {
            String summary = DynamicTomcatEnvironment.getConfigurationSummary();
            assertNotNull(summary);
            assertFalse(summary.isEmpty());
        }

        @Test
        @DisplayName("configuration summary contains key fields")
        void configSummaryContainsFields() {
            String summary = DynamicTomcatEnvironment.getConfigurationSummary();
            assertTrue(summary.contains("Environment:"));
            assertTrue(summary.contains("HTTP:"));
            assertTrue(summary.contains("Shutdown:"));
        }
    }

    @Nested
    @DisplayName("JMX security")
    class JmxSecurityTests {

        @Test
        @DisplayName("shouldSecureJmx returns boolean without exception")
        void shouldSecureJmxNoCrash() {
            assertDoesNotThrow(DynamicTomcatEnvironment::shouldSecureJmx);
        }

        @Test
        @DisplayName("getJmxAuthenticationEnabled consistent with shouldSecureJmx")
        void jmxAuthConsistentWithSecure() {
            assertEquals(DynamicTomcatEnvironment.shouldSecureJmx(),
                    DynamicTomcatEnvironment.getJmxAuthenticationEnabled());
        }
    }

    @Nested
    @DisplayName("Default port values")
    class DefaultPortTests {

        @Test
        @DisplayName("HTTP port is valid (1-65535)")
        void httpPortValid() {
            int port = DynamicTomcatEnvironment.getHttpPort();
            assertTrue(port >= 1 && port <= 65535, "HTTP port should be valid: " + port);
        }

        @Test
        @DisplayName("shutdown port is valid")
        void shutdownPortValid() {
            int port = DynamicTomcatEnvironment.getShutdownPort();
            assertTrue(port >= 1 && port <= 65535, "Shutdown port should be valid: " + port);
        }

        @Test
        @DisplayName("HTTPS port is valid")
        void httpsPortValid() {
            int port = DynamicTomcatEnvironment.getHttpsPort();
            assertTrue(port >= 1 && port <= 65535, "HTTPS port should be valid: " + port);
        }

        @Test
        @DisplayName("JMX port is valid")
        void jmxPortValid() {
            int port = DynamicTomcatEnvironment.getJmxPort();
            assertTrue(port >= 1 && port <= 65535, "JMX port should be valid: " + port);
        }
    }

    @Nested
    @DisplayName("Memory defaults")
    class MemoryDefaultTests {

        @Test
        @DisplayName("Xmx is non-empty")
        void xmxNonEmpty() {
            String xmx = DynamicTomcatEnvironment.getXmxValue();
            assertFalse(xmx.isEmpty(), "Xmx should not be empty");
        }

        @Test
        @DisplayName("Xms is non-empty")
        void xmsNonEmpty() {
            String xms = DynamicTomcatEnvironment.getXmsValue();
            assertFalse(xms.isEmpty(), "Xms should not be empty");
        }
    }
}
