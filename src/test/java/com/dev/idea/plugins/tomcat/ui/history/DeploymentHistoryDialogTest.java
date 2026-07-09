package com.dev.idea.plugins.tomcat.ui.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DeploymentHistoryDialog")
class DeploymentHistoryDialogTest {

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
}
