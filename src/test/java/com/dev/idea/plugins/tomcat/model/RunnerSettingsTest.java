package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunnerSettings")
class RunnerSettingsTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("default startup is use default")
        void defaultStartup() {
            RunnerSettings rs = new RunnerSettings();
            assertTrue(rs.isUseDefaultStartup());
        }

        @Test
        @DisplayName("default shutdown is use default")
        void defaultShutdown() {
            RunnerSettings rs = new RunnerSettings();
            assertTrue(rs.isUseDefaultShutdown());
        }

        @Test
        @DisplayName("default startup script is empty")
        void defaultStartupScript() {
            RunnerSettings rs = new RunnerSettings();
            assertEquals("", rs.getStartupScript());
        }

        @Test
        @DisplayName("default shutdown script is empty")
        void defaultShutdownScript() {
            RunnerSettings rs = new RunnerSettings();
            assertEquals("", rs.getShutdownScript());
        }

        @Test
        @DisplayName("default env vars is empty map")
        void defaultEnvVars() {
            RunnerSettings rs = new RunnerSettings();
            assertTrue(rs.getEnvironmentVariables().isEmpty());
        }

        @Test
        @DisplayName("default pass parent envs is true")
        void defaultPassParentEnvs() {
            RunnerSettings rs = new RunnerSettings();
            assertTrue(rs.isPassParentEnvs());
        }

        @Test
        @DisplayName("default computed env metadata is empty")
        void defaultComputedEnvMetadata() {
            RunnerSettings rs = new RunnerSettings();
            assertTrue(rs.getComputedEnvironmentKeys().isEmpty());
            assertTrue(rs.getDeletedComputedEnvironmentKeys().isEmpty());
        }
    }

    @Nested
    @DisplayName("setters and getters")
    class SettersGetters {

        @Test
        @DisplayName("set and get startup script")
        void startupScript() {
            RunnerSettings rs = new RunnerSettings();
            rs.setStartupScript("/bin/start.sh");
            assertEquals("/bin/start.sh", rs.getStartupScript());
        }

        @Test
        @DisplayName("null startup script becomes empty")
        void nullStartupScript() {
            RunnerSettings rs = new RunnerSettings();
            rs.setStartupScript(null);
            assertEquals("", rs.getStartupScript());
        }

        @Test
        @DisplayName("set and get shutdown script")
        void shutdownScript() {
            RunnerSettings rs = new RunnerSettings();
            rs.setShutdownScript("/bin/stop.sh");
            assertEquals("/bin/stop.sh", rs.getShutdownScript());
        }

        @Test
        @DisplayName("null shutdown script becomes empty")
        void nullShutdownScript() {
            RunnerSettings rs = new RunnerSettings();
            rs.setShutdownScript(null);
            assertEquals("", rs.getShutdownScript());
        }

        @Test
        @DisplayName("set and get environment variables")
        void envVars() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("FOO", "bar");
            env.put("BAZ", "qux");
            rs.setEnvironmentVariables(env);
            assertEquals(2, rs.getEnvironmentVariables().size());
            assertEquals("bar", rs.getEnvironmentVariables().get("FOO"));
        }

        @Test
        @DisplayName("null env vars becomes empty map")
        void nullEnvVars() {
            RunnerSettings rs = new RunnerSettings();
            rs.setEnvironmentVariables(null);
            assertNotNull(rs.getEnvironmentVariables());
            assertTrue(rs.getEnvironmentVariables().isEmpty());
        }

        @Test
        @DisplayName("env vars returns defensive copy")
        void envVarsDefensiveCopy() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("KEY", "val");
            rs.setEnvironmentVariables(env);

            Map<String, String> returned = rs.getEnvironmentVariables();
            returned.put("NEW", "entry");
            // Original should not be affected
            assertFalse(rs.getEnvironmentVariables().containsKey("NEW"));
        }

        @Test
        @DisplayName("set pass parent envs")
        void passParentEnvs() {
            RunnerSettings rs = new RunnerSettings();
            rs.setPassParentEnvs(false);
            assertFalse(rs.isPassParentEnvs());
        }

        @Test
        @DisplayName("computed env metadata returns defensive copies")
        void computedEnvMetadataDefensiveCopies() {
            RunnerSettings rs = new RunnerSettings();
            rs.setComputedEnvironmentKeys(java.util.Set.of("JAVA_OPTS"));
            rs.setDeletedComputedEnvironmentKeys(java.util.Set.of("CATALINA_OPTS"));

            var computed = rs.getComputedEnvironmentKeys();
            var deleted = rs.getDeletedComputedEnvironmentKeys();
            computed.add("NEW");
            deleted.add("OTHER");

            assertFalse(rs.getComputedEnvironmentKeys().contains("NEW"));
            assertFalse(rs.getDeletedComputedEnvironmentKeys().contains("OTHER"));
        }

        @Test
        @DisplayName("set use default startup")
        void useDefaultStartup() {
            RunnerSettings rs = new RunnerSettings();
            rs.setUseDefaultStartup(false);
            assertFalse(rs.isUseDefaultStartup());
        }

        @Test
        @DisplayName("set use default shutdown")
        void useDefaultShutdown() {
            RunnerSettings rs = new RunnerSettings();
            rs.setUseDefaultShutdown(false);
            assertFalse(rs.isUseDefaultShutdown());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone preserves all fields")
        void clonePreservesAll() {
            RunnerSettings original = new RunnerSettings();
            original.setUseDefaultStartup(false);
            original.setStartupScript("/start.sh");
            original.setUseDefaultShutdown(false);
            original.setShutdownScript("/stop.sh");
            original.setPassParentEnvs(false);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("A", "1");
            original.setEnvironmentVariables(env);
            original.setComputedEnvironmentKeys(java.util.Set.of("JAVA_OPTS"));
            original.setDeletedComputedEnvironmentKeys(java.util.Set.of("CATALINA_OPTS"));

            RunnerSettings cloned = original.clone();

            assertFalse(cloned.isUseDefaultStartup());
            assertEquals("/start.sh", cloned.getStartupScript());
            assertFalse(cloned.isUseDefaultShutdown());
            assertEquals("/stop.sh", cloned.getShutdownScript());
            assertFalse(cloned.isPassParentEnvs());
            assertEquals("1", cloned.getEnvironmentVariables().get("A"));
            assertTrue(cloned.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
            assertTrue(cloned.getDeletedComputedEnvironmentKeys().contains("CATALINA_OPTS"));
        }

        @Test
        @DisplayName("clone is independent (deep copy)")
        void cloneIsIndependent() {
            RunnerSettings original = new RunnerSettings();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("K", "V");
            original.setEnvironmentVariables(env);

            RunnerSettings cloned = original.clone();
            cloned.getEnvironmentVariables(); // get copy
            cloned.setStartupScript("changed");

            assertEquals("", original.getStartupScript());
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copy constructor copies all fields")
        void copyAll() {
            RunnerSettings original = new RunnerSettings();
            original.setStartupScript("start");
            original.setShutdownScript("stop");
            original.setPassParentEnvs(false);
            original.setComputedEnvironmentKeys(java.util.Set.of("JAVA_OPTS"));
            original.setDeletedComputedEnvironmentKeys(java.util.Set.of("CATALINA_OPTS"));

            RunnerSettings copy = new RunnerSettings(original);

            assertEquals("start", copy.getStartupScript());
            assertEquals("stop", copy.getShutdownScript());
            assertFalse(copy.isPassParentEnvs());
            assertTrue(copy.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
            assertTrue(copy.getDeletedComputedEnvironmentKeys().contains("CATALINA_OPTS"));
        }

        @Test
        @DisplayName("copy constructor null throws exception")
        void copyNull() {
            // IntelliJ @NotNull instrumentation throws IllegalArgumentException, not NPE
            assertThrows(Exception.class, () -> new RunnerSettings(null));
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("equal objects are equal")
        void equalObjects() {
            RunnerSettings a = new RunnerSettings();
            RunnerSettings b = new RunnerSettings();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different startup script not equal")
        void differentStartup() {
            RunnerSettings a = new RunnerSettings();
            RunnerSettings b = new RunnerSettings();
            b.setStartupScript("custom");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different env vars not equal")
        void differentEnvVars() {
            RunnerSettings a = new RunnerSettings();
            RunnerSettings b = new RunnerSettings();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("X", "Y");
            b.setEnvironmentVariables(env);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different passParentEnvs not equal")
        void differentPassParent() {
            RunnerSettings a = new RunnerSettings();
            RunnerSettings b = new RunnerSettings();
            b.setPassParentEnvs(false);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("reflexive")
        void reflexive() {
            RunnerSettings rs = new RunnerSettings();
            assertEquals(rs, rs);
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            RunnerSettings rs = new RunnerSettings();
            assertNotEquals(null, rs);
        }

        @Test
        @DisplayName("not equal to different type")
        void notEqualToDifferentType() {
            RunnerSettings rs = new RunnerSettings();
            assertNotEquals("string", rs);
        }
    }
}
