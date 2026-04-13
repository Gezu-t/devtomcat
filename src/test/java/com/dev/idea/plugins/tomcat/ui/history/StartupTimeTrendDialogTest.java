package com.dev.idea.plugins.tomcat.ui.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StartupTimeTrendDialog")
class StartupTimeTrendDialogTest {

    @Nested
    @DisplayName("scopeStartupTimes")
    class ScopeStartupTimesTests {

        @Test
        @DisplayName("returns all tracked configurations when no scope is selected")
        void globalScopeKeepsAllConfigurations() {
            Map<String, List<Long>> startupTimes = new LinkedHashMap<>();
            startupTimes.put("App A", List.of(1000L, 1200L));
            startupTimes.put("App B", List.of(800L));

            Map<String, List<Long>> scoped = StartupTimeTrendDialog.scopeStartupTimes(startupTimes, null);

            assertEquals(startupTimes, scoped);
        }

        @Test
        @DisplayName("keeps only the selected configuration")
        void scopedSelectionKeepsOnlyRequestedConfiguration() {
            Map<String, List<Long>> startupTimes = new LinkedHashMap<>();
            startupTimes.put("App A", List.of(1000L, 1200L));
            startupTimes.put("App B", List.of(800L));

            Map<String, List<Long>> scoped = StartupTimeTrendDialog.scopeStartupTimes(startupTimes, "App B");

            assertEquals(1, scoped.size());
            assertEquals(List.of(800L), scoped.get("App B"));
        }

        @Test
        @DisplayName("returns empty when the selected configuration has no startup data")
        void missingConfigurationReturnsEmptyMap() {
            Map<String, List<Long>> startupTimes = new LinkedHashMap<>();
            startupTimes.put("App A", List.of(1000L, 1200L));

            assertTrue(StartupTimeTrendDialog.scopeStartupTimes(startupTimes, "Missing").isEmpty());
        }
    }

    @Test
    @DisplayName("uses a configuration-specific title and empty message when scoped")
    void scopedStringsIncludeConfigurationName() {
        assertEquals(
                "DevTomcat — Startup Time Trends — Demo",
                StartupTimeTrendDialog.dialogTitle("Demo")
        );
        assertEquals(
                "No startup data recorded yet for 'Demo'. Run it to begin tracking.",
                StartupTimeTrendDialog.emptyStateMessage("Demo")
        );
    }
}
