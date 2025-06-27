package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Update Actions Section - Ultimate Match
 * Matches EXACTLY the Ultimate screenshot:
 * "On 'Update' action:" dropdown with "Restart server" + ☑ Show dialog checkbox
 */
public class UpdateActionsSection implements ConfigurationSection {

    private JComboBox<String> updateActionCombo;
    private JCheckBox showDialogCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(5);

            // "On 'Update' action:" label
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("On 'Update' action:"), gbc);

            // Update action dropdown
            gbc.gridx = 1;
            updateActionCombo = new JComboBox<>();
            updateActionCombo.setPreferredSize(new Dimension(150, 25));
            panel.add(updateActionCombo, gbc);

            // Show dialog checkbox
            gbc.gridx = 2;
            showDialogCheckBox = new JCheckBox("Show dialog");
            showDialogCheckBox.setSelected(true); // Checked in Ultimate screenshot
            panel.add(showDialogCheckBox, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        updateActionCombo.removeAllItems();
        // Options available in Ultimate
        updateActionCombo.addItem("Restart server");
        updateActionCombo.addItem("Redeploy");
        updateActionCombo.addItem("Update classes and resources");

        // Default selection matches Ultimate screenshot
        updateActionCombo.setSelectedItem("Restart server");
        showDialogCheckBox.setSelected(true);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Reset to defaults shown in Ultimate screenshot
        updateActionCombo.setSelectedItem("Restart server");
        showDialogCheckBox.setSelected(true);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String selectedAction = getSelectedAction();
        boolean showDialog = isShowDialogEnabled();

        // Apply update action configuration
        System.out.println("DevTomcat: Update action: " + selectedAction + ", Show dialog: " + showDialog);

        // These could be stored in configuration if needed
        // configuration.setUpdateAction(selectedAction);
        // configuration.setShowUpdateDialog(showDialog);
    }

    @Override
    public boolean isValid() {
        return true; // Update actions are always valid
    }

    @Override
    public boolean shouldFillVertically() {
        return false; // Update section doesn't need vertical space
    }

    // Getters for external access
    public String getSelectedAction() {
        return (String) updateActionCombo.getSelectedItem();
    }

    public boolean isShowDialogEnabled() {
        return showDialogCheckBox.isSelected();
    }
}