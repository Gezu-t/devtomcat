package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuntimeEnvResolver")
class RuntimeEnvResolverTest {

    // =========================================================================
    // computeDefaults
    // =========================================================================

    @Nested
    @DisplayName("computeDefaults")
    class ComputeDefaults {

        @Test
        @DisplayName("returns JAVA_OPTS when VM options are set")
        void javaOptsFromVmOptions() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx512m -server");

            Map<String, String> defaults = RuntimeEnvResolver.computeDefaults(data);

            assertEquals(1, defaults.size());
            assertEquals("-Xmx512m -server", defaults.get("JAVA_OPTS"));
        }

        @Test
        @DisplayName("returns empty map when no VM options")
        void emptyWhenNoVmOptions() {
            TomcatConfigurationData data = new TomcatConfigurationData();

            Map<String, String> defaults = RuntimeEnvResolver.computeDefaults(data);

            assertTrue(defaults.isEmpty());
        }

        @Test
        @DisplayName("returns empty map when VM options is blank")
        void emptyWhenBlankVmOptions() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("   ");

            Map<String, String> defaults = RuntimeEnvResolver.computeDefaults(data);

            assertTrue(defaults.isEmpty());
        }
    }

    // =========================================================================
    // ensureComputedEnvVars
    // =========================================================================

    @Nested
    @DisplayName("ensureComputedEnvVars")
    class EnsureComputedEnvVars {

        @Test
        @DisplayName("injects JAVA_OPTS into runner settings when not present")
        void injectsJavaOpts() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx1g");

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            RunnerSettings rs = data.getRunnerSettings("Run");
            assertEquals("-Xmx1g", rs.getEnvironmentVariables().get("JAVA_OPTS"));
            assertTrue(rs.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("does not overwrite user-defined JAVA_OPTS")
        void preservesUserJavaOpts() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx1g");

            RunnerSettings rs = data.getRunnerSettings("Run");
            Map<String, String> userEnv = new LinkedHashMap<>();
            userEnv.put("JAVA_OPTS", "-Xmx2g -XX:+UseG1GC");
            rs.setEnvironmentVariables(userEnv);

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertEquals("-Xmx2g -XX:+UseG1GC", rs.getEnvironmentVariables().get("JAVA_OPTS"));
            assertFalse(rs.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("removes previously computed JAVA_OPTS when VM options are cleared")
        void removesPreviouslyComputedJavaOpts() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            RunnerSettings rs = data.getRunnerSettings("Run");
            rs.setEnvironmentVariables(Map.of("JAVA_OPTS", "-Xmx1g"));
            rs.setComputedEnvironmentKeys(Set.of("JAVA_OPTS"));
            data.getVmConfig().setVmOptions("");

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertFalse(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"));
            assertFalse(rs.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("per-mode isolation: Run and Debug get independent env vars")
        void perModeIsolation() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx512m");

            // Set user override only in Debug mode
            RunnerSettings debugRs = data.getRunnerSettings("Debug");
            Map<String, String> debugEnv = new LinkedHashMap<>();
            debugEnv.put("JAVA_OPTS", "-Xdebug -custom");
            debugRs.setEnvironmentVariables(debugEnv);

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");
            RuntimeEnvResolver.ensureComputedEnvVars(data, "Debug");

            // Run mode gets computed value
            assertEquals("-Xmx512m", data.getRunnerSettings("Run").getEnvironmentVariables().get("JAVA_OPTS"));
            // Debug mode keeps user override
            assertEquals("-Xdebug -custom", data.getRunnerSettings("Debug").getEnvironmentVariables().get("JAVA_OPTS"));
            assertTrue(data.getRunnerSettings("Run").getComputedEnvironmentKeys().contains("JAVA_OPTS"));
            assertFalse(data.getRunnerSettings("Debug").getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("preserves non-computed user env vars")
        void preservesUserVars() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx1g");

            RunnerSettings rs = data.getRunnerSettings("Run");
            Map<String, String> userEnv = new LinkedHashMap<>();
            userEnv.put("MY_VAR", "custom_value");
            userEnv.put("CATALINA_HOME", "/opt/tomcat");
            rs.setEnvironmentVariables(userEnv);

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            Map<String, String> result = rs.getEnvironmentVariables();
            assertEquals("custom_value", result.get("MY_VAR"));
            assertEquals("/opt/tomcat", result.get("CATALINA_HOME"));
            assertEquals("-Xmx1g", result.get("JAVA_OPTS"));
        }

        @Test
        @DisplayName("no-op when VM options empty and no JAVA_OPTS present")
        void noopWhenBothEmpty() {
            TomcatConfigurationData data = new TomcatConfigurationData();

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            RunnerSettings rs = data.getRunnerSettings("Run");
            assertFalse(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"));
        }

        @Test
        @DisplayName("does not restore explicitly deleted computed keys")
        void respectsDeletedMetadata() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx1g");

            RunnerSettings rs = data.getRunnerSettings("Run");
            rs.setDeletedComputedEnvironmentKeys(Set.of("JAVA_OPTS"));

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertFalse(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"));
        }
    }

    // =========================================================================
    // mergeIntoRunnerSettings
    // =========================================================================

    @Nested
    @DisplayName("mergeIntoRunnerSettings")
    class MergeIntoRunnerSettings {

        @Test
        @DisplayName("updates computed keys only")
        void updatesComputedOnly() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> env = new LinkedHashMap<>();
            env.put("JAVA_OPTS", "-old");
            env.put("USER_VAR", "keep");
            rs.setEnvironmentVariables(env);

            Map<String, String> defaults = Map.of("JAVA_OPTS", "-new");
            Set<String> computedKeys = new LinkedHashSet<>(Set.of("JAVA_OPTS"));
            Set<String> deletedKeys = new LinkedHashSet<>();

            RuntimeEnvResolver.mergeIntoRunnerSettings(rs, defaults, computedKeys, deletedKeys);

            assertEquals("-new", rs.getEnvironmentVariables().get("JAVA_OPTS"));
            assertEquals("keep", rs.getEnvironmentVariables().get("USER_VAR"));
        }

        @Test
        @DisplayName("does not restore deleted computed keys")
        void respectsDeletedKeys() {
            RunnerSettings rs = new RunnerSettings();

            Map<String, String> defaults = Map.of("JAVA_OPTS", "-Xmx1g");
            Set<String> computedKeys = new LinkedHashSet<>(Set.of("JAVA_OPTS"));
            Set<String> deletedKeys = new LinkedHashSet<>(Set.of("JAVA_OPTS"));

            RuntimeEnvResolver.mergeIntoRunnerSettings(rs, defaults, computedKeys, deletedKeys);

            assertFalse(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"),
                    "Deleted computed key should not be restored");
        }

        @Test
        @DisplayName("removes stale computed keys when defaults disappear")
        void removesStaleComputedKeys() {
            RunnerSettings rs = new RunnerSettings();
            rs.setEnvironmentVariables(Map.of("JAVA_OPTS", "-old"));

            Set<String> computedKeys = new LinkedHashSet<>(Set.of("JAVA_OPTS"));
            Set<String> deletedKeys = new LinkedHashSet<>();

            RuntimeEnvResolver.mergeIntoRunnerSettings(rs, Map.of(), computedKeys, deletedKeys);

            assertFalse(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"));
            assertFalse(rs.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        }
    }

    // =========================================================================
    // isComputedKey
    // =========================================================================

    @Nested
    @DisplayName("isComputedKey")
    class IsComputedKey {

        @Test
        @DisplayName("JAVA_OPTS is a computed key")
        void javaOptsIsComputed() {
            assertTrue(RuntimeEnvResolver.isComputedKey("JAVA_OPTS"));
        }

        @Test
        @DisplayName("arbitrary key is not computed")
        void userVarIsNotComputed() {
            assertFalse(RuntimeEnvResolver.isComputedKey("MY_CUSTOM_VAR"));
        }
    }

    // =========================================================================
    // End-to-end: VM options → runner settings → launch env
    // =========================================================================

    @Nested
    @DisplayName("end-to-end propagation")
    class EndToEnd {

        @Test
        @DisplayName("VM options set on config appear in all executor modes after ensure")
        void vmOptionsPropagatesToAllModes() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Dwcc.config.dir=/tmp/wcc -Xmx2g");

            for (String mode : new String[]{"Run", "Debug", "Coverage", "Profile"}) {
                RuntimeEnvResolver.ensureComputedEnvVars(data, mode);
            }

            for (String mode : new String[]{"Run", "Debug", "Coverage", "Profile"}) {
                String javaOpts = data.getRunnerSettings(mode).getEnvironmentVariables().get("JAVA_OPTS");
                assertEquals("-Dwcc.config.dir=/tmp/wcc -Xmx2g", javaOpts,
                        "JAVA_OPTS should be present in " + mode + " mode");
            }
        }

        @Test
        @DisplayName("changing VM options and re-ensuring updates JAVA_OPTS")
        void changingVmOptionsUpdates() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx512m");
            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertEquals("-Xmx512m", data.getRunnerSettings("Run").getEnvironmentVariables().get("JAVA_OPTS"));

            data.getVmConfig().setVmOptions("-Xmx1g -server");
            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertEquals("-Xmx1g -server", data.getRunnerSettings("Run").getEnvironmentVariables().get("JAVA_OPTS"));
        }

        @Test
        @DisplayName("clearing VM options does not remove user-owned JAVA_OPTS")
        void clearingVmOptionsDoesNotRemoveUserOwned() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            RunnerSettings rs = data.getRunnerSettings("Run");
            rs.setEnvironmentVariables(Map.of("JAVA_OPTS", "-Xmx512m"));

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            assertTrue(rs.getEnvironmentVariables().containsKey("JAVA_OPTS"));
        }
    }
}
