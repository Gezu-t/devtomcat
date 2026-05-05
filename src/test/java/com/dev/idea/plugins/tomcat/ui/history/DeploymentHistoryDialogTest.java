package com.dev.idea.plugins.tomcat.ui.history;

import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DeploymentHistoryDialog")
class DeploymentHistoryDialogTest {

    @Nested
    @DisplayName("scopeEntries")
    class ScopeEntriesTests {

        @Test
        @DisplayName("returns all entries when no configuration is selected")
        void globalScopeKeepsAllEntries() {
            List<TomcatDeploymentHistory.HistoryEntry> entries = List.of(
                    entry("App A", true),
                    entry("App B", false)
            );

            assertEquals(entries, DeploymentHistoryDialog.scopeEntries(entries, null));
        }

        @Test
        @DisplayName("filters entries to the selected configuration")
        void scopedSelectionFiltersEntries() {
            List<TomcatDeploymentHistory.HistoryEntry> entries = List.of(
                    entry("App A", true),
                    entry("App B", false),
                    entry("App A", false)
            );

            List<TomcatDeploymentHistory.HistoryEntry> scoped =
                    DeploymentHistoryDialog.scopeEntries(entries, "App A");

            assertEquals(2, scoped.size());
            assertEquals(List.of("App A", "App A"), scoped.stream().map(e -> e.configName).toList());
        }
    }

    @Test
    @DisplayName("uses a configuration-specific title and clear prompt when scoped")
    void scopedStringsIncludeConfigurationName() {
        assertEquals(
                "DevTomcat: Run History (Demo)",
                DeploymentHistoryDialog.dialogTitle("Demo")
        );
        assertEquals(
                "Clear run history for 'Demo'?",
                DeploymentHistoryDialog.clearConfirmationMessage("Demo")
        );
    }

    private static TomcatDeploymentHistory.HistoryEntry entry(String configName, boolean success) {
        TomcatDeploymentHistory.HistoryEntry entry = new TomcatDeploymentHistory.HistoryEntry();
        entry.configName = configName;
        entry.success = success;
        return entry;
    }
}
