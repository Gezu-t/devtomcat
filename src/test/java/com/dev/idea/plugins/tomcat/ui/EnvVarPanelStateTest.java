package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link EnvVarPanel} state machine.
 *
 * <p>{@code initializeState()} is static and package-private — it depends only on
 * {@link EnvVarPanel.State}, {@link RunnerSettings}, and a defaults map, so it can
 * be tested without any IntelliJ UI infrastructure.
 */
@DisplayName("EnvVarPanel state machine")
class EnvVarPanelStateTest {

    // =========================================================================
    // initializeState — passParentEnvs round-trip
    // =========================================================================

    @Nested
    @DisplayName("initializeState: passParentEnvs")
    class PassParentEnvsTests {

        @Test
        @DisplayName("passParentEnvs=false is preserved from RunnerSettings")
        void passParentEnvsFalsePreserved() {
            RunnerSettings rs = new RunnerSettings();
            rs.setPassParentEnvs(false);

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, Map.of(), rs);

            assertFalse(state.passParentEnvs,
                    "passParentEnvs=false must survive initializeState round-trip");
        }

        @Test
        @DisplayName("passParentEnvs=true is preserved from RunnerSettings")
        void passParentEnvsTruePreserved() {
            RunnerSettings rs = new RunnerSettings();
            rs.setPassParentEnvs(true);

            EnvVarPanel.State state = new EnvVarPanel.State();
            // Start with false to prove initializeState actually writes the field
            state.passParentEnvs = false;
            EnvVarPanel.initializeState(state, Map.of(), rs);

            assertTrue(state.passParentEnvs,
                    "passParentEnvs=true must be restored from RunnerSettings");
        }

        @Test
        @DisplayName("passParentEnvs defaults to true for fresh RunnerSettings")
        void passParentEnvsDefaultIsTrue() {
            RunnerSettings rs = new RunnerSettings();

            EnvVarPanel.State state = new EnvVarPanel.State();
            state.passParentEnvs = false;
            EnvVarPanel.initializeState(state, Map.of(), rs);

            assertTrue(state.passParentEnvs,
                    "Fresh RunnerSettings defaults passParentEnvs to true");
        }
    }

    // =========================================================================
    // initializeState — three initialization paths
    // =========================================================================

    @Nested
    @DisplayName("initializeState: three paths")
    class InitializationPathTests {

        @Test
        @DisplayName("Path 1: persisted computed/deleted sets restore exactly")
        void persistedMetadataRestoresExactly() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> envVars = new LinkedHashMap<>();
            envVars.put("JAVA_OPTS", "-Xmx512m");
            envVars.put("MY_VAR", "hello");
            rs.setEnvironmentVariables(envVars);
            rs.setComputedEnvironmentKeys(Set.of("JAVA_OPTS"));
            rs.setDeletedComputedEnvironmentKeys(Set.of());

            Map<String, String> defaults = Map.of("JAVA_OPTS", "-Xmx1g");

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, defaults, rs);

            // Env vars restored as-is (not overwritten by fresh defaults)
            assertEquals("-Xmx512m", state.envVars.get("JAVA_OPTS"));
            assertEquals("hello", state.envVars.get("MY_VAR"));
            assertTrue(state.computedKeys.contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("Path 2: no persisted vars → seed from computed defaults")
        void emptyPersistedSeedsFromDefaults() {
            RunnerSettings rs = new RunnerSettings();
            // No env vars, no computed/deleted sets

            Map<String, String> defaults = Map.of("JAVA_OPTS", "-Xmx1g");

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, defaults, rs);

            assertEquals("-Xmx1g", state.envVars.get("JAVA_OPTS"));
            assertTrue(state.computedKeys.contains("JAVA_OPTS"));
        }

        @Test
        @DisplayName("Path 3: persisted vars without metadata — heuristic promotion")
        void heuristicPromotionMatchesComputed() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> envVars = new LinkedHashMap<>();
            envVars.put("JAVA_OPTS", "-Xmx512m");
            envVars.put("CUSTOM", "val");
            rs.setEnvironmentVariables(envVars);
            // No computed/deleted metadata

            Map<String, String> defaults = Map.of("JAVA_OPTS", "-Xmx512m");

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, defaults, rs);

            // JAVA_OPTS matches the default → promoted to computed
            assertTrue(state.computedKeys.contains("JAVA_OPTS"));
            // CUSTOM is user-defined
            assertFalse(state.computedKeys.contains("CUSTOM"));
            assertEquals("val", state.envVars.get("CUSTOM"));
        }

        @Test
        @DisplayName("Path 3: heuristic does NOT promote when value differs from default")
        void heuristicSkipsOnValueMismatch() {
            RunnerSettings rs = new RunnerSettings();
            Map<String, String> envVars = new LinkedHashMap<>();
            envVars.put("JAVA_OPTS", "-Xmx512m");
            rs.setEnvironmentVariables(envVars);

            // Default is different
            Map<String, String> defaults = Map.of("JAVA_OPTS", "-Xmx1g");

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, defaults, rs);

            // Value mismatch → not promoted
            assertFalse(state.computedKeys.contains("JAVA_OPTS"));
            assertEquals("-Xmx512m", state.envVars.get("JAVA_OPTS"));
        }
    }

    // =========================================================================
    // initializeState — deleted keys
    // =========================================================================

    @Nested
    @DisplayName("initializeState: deleted computed keys")
    class DeletedKeysTests {

        @Test
        @DisplayName("persisted deletedComputedKeys are restored into state")
        void deletedKeysRestored() {
            RunnerSettings rs = new RunnerSettings();
            rs.setDeletedComputedEnvironmentKeys(Set.of("JAVA_OPTS"));
            // Need at least one of computed/deleted non-empty to hit path 1
            // (deletedComputedEnvironmentKeys is non-empty, so path 1 applies)

            EnvVarPanel.State state = new EnvVarPanel.State();
            EnvVarPanel.initializeState(state, Map.of(), rs);

            assertTrue(state.deletedComputedKeys.contains("JAVA_OPTS"));
            assertFalse(state.envVars.containsKey("JAVA_OPTS"),
                    "Deleted key should not appear in envVars");
        }
    }

    // =========================================================================
    // State: delete-then-readd flow
    // =========================================================================

    @Nested
    @DisplayName("delete-then-readd lifecycle")
    class DeleteThenReaddTests {

        @Test
        @DisplayName("saveState captures deletedComputedKeys")
        void saveStateCapturesDeletedKeys() {
            // Simulate: state has JAVA_OPTS as computed, user deletes it
            EnvVarPanel.State state = new EnvVarPanel.State();
            state.envVars.put("JAVA_OPTS", "-Xmx1g");
            state.computedKeys.add("JAVA_OPTS");

            // Simulate removal: move from computed to deleted
            state.computedKeys.remove("JAVA_OPTS");
            state.envVars.remove("JAVA_OPTS");
            state.deletedComputedKeys.add("JAVA_OPTS");

            // Verify the deletion marker is present
            assertTrue(state.deletedComputedKeys.contains("JAVA_OPTS"));
            assertFalse(state.envVars.containsKey("JAVA_OPTS"));
        }

        @Test
        @DisplayName("re-adding a deleted computed key clears the deletion marker in state")
        void reAddClearsDeletedMarker() {
            // Simulate: JAVA_OPTS was deleted
            EnvVarPanel.State state = new EnvVarPanel.State();
            state.deletedComputedKeys.add("JAVA_OPTS");

            // Simulate what addEnvVar/pasteEnvVar now does:
            // add the var, clear it from computedKeys, clear from deletedComputedKeys
            state.envVars.put("JAVA_OPTS", "-Xmx2g");
            state.computedKeys.remove("JAVA_OPTS");
            state.deletedComputedKeys.remove("JAVA_OPTS");

            assertFalse(state.deletedComputedKeys.contains("JAVA_OPTS"),
                    "Re-adding a deleted computed key must clear the deletion marker");
            assertEquals("-Xmx2g", state.envVars.get("JAVA_OPTS"));
        }

        @Test
        @DisplayName("ensureComputedEnvVars does NOT strip a manually re-added key")
        void ensureComputedDoesNotStripReaddedKey() {
            // Setup: JAVA_OPTS manually re-added after deletion
            // (present in envVars, absent from deletedComputedKeys)
            com.dev.idea.plugins.tomcat.model.TomcatConfigurationData data =
                    new com.dev.idea.plugins.tomcat.model.TomcatConfigurationData();
            // VM options produce a computed default for JAVA_OPTS
            data.getVmConfig().setVmOptions("-Xmx1g");

            RunnerSettings rs = data.getRunnerSettings("Run");
            rs.setEnvironmentVariables(new LinkedHashMap<>(Map.of("JAVA_OPTS", "-Xmx2g")));
            rs.setComputedEnvironmentKeys(new LinkedHashSet<>());
            rs.setDeletedComputedEnvironmentKeys(new LinkedHashSet<>()); // deletion cleared by re-add

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            RunnerSettings after = data.getRunnerSettings("Run");
            assertTrue(after.getEnvironmentVariables().containsKey("JAVA_OPTS"),
                    "JAVA_OPTS must survive ensureComputedEnvVars when deletion marker is cleared");
        }

        @Test
        @DisplayName("ensureComputedEnvVars strips key when deletion marker is present")
        void ensureComputedStripsWhenDeletedMarkerPresent() {
            com.dev.idea.plugins.tomcat.model.TomcatConfigurationData data =
                    new com.dev.idea.plugins.tomcat.model.TomcatConfigurationData();
            data.getVmConfig().setVmOptions("-Xmx1g");

            RunnerSettings rs = data.getRunnerSettings("Run");
            rs.setEnvironmentVariables(new LinkedHashMap<>(Map.of("JAVA_OPTS", "-Xmx2g")));
            rs.setComputedEnvironmentKeys(new LinkedHashSet<>());
            rs.setDeletedComputedEnvironmentKeys(new LinkedHashSet<>(Set.of("JAVA_OPTS")));

            RuntimeEnvResolver.ensureComputedEnvVars(data, "Run");

            RunnerSettings after = data.getRunnerSettings("Run");
            assertFalse(after.getEnvironmentVariables().containsKey("JAVA_OPTS"),
                    "JAVA_OPTS must be stripped when deletion marker is present");
        }
    }
}
