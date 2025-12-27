package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;

/**
 * Update Actions Section
 */
public class UpdateActionsSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(UpdateActionsSection.class);

    private ComboBox<String> updateActionCombo;
    private JCheckBox showDialogCheckBox;
    private ComboBox<String> frameDeactivationCombo;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(2, 0, 0, 0));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(2, 0, 2, 4);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("On 'Update' action:"), gbc);

            gbc.gridx = 1;
            updateActionCombo = new ComboBox<>();
            updateActionCombo.setPreferredSize(new Dimension(180, 25));
            panel.add(updateActionCombo, gbc);

            gbc.gridx = 2;
            showDialogCheckBox = new JCheckBox("Show dialog");
            showDialogCheckBox.setSelected(true);
            panel.add(showDialogCheckBox, gbc);

            gbc.gridx = 0; gbc.gridy = 1;
            panel.add(new JLabel("On frame deactivation:"), gbc);

            gbc.gridx = 1;
            frameDeactivationCombo = new ComboBox<>();
            frameDeactivationCombo.setPreferredSize(new Dimension(180, 25));
            panel.add(frameDeactivationCombo, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        String[] options = {"Update resources", "Update classes and resources", "Redeploy", "Restart server"};
        updateActionCombo.removeAllItems();
        for (String option : options) {
            updateActionCombo.addItem(option);
        }
        updateActionCombo.setSelectedItem("Restart server");
        showDialogCheckBox.setSelected(true);

        frameDeactivationCombo.removeAllItems();
        frameDeactivationCombo.addItem("Do nothing");
        for (String option : options) {
            frameDeactivationCombo.addItem(option);
        }
        frameDeactivationCombo.setSelectedItem("Do nothing");
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        updateActionCombo.setSelectedItem("Restart server");
        showDialogCheckBox.setSelected(true);

        frameDeactivationCombo.setSelectedItem("Do nothing");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String selectedAction = getSelectedAction();
        boolean showDialog = isShowDialogEnabled();

        LOG.info("Update action: " + selectedAction + ", Show dialog: " + showDialog);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        // Update actions configuration is not currently stored in the configuration model
        // This is UI-only functionality that controls runtime behavior
        // For now, return false (no changes to persist)
        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        // Update action selections are always valid - they're predefined options
        // No validation required for combo box selections
        return Collections.emptyList();
    }

    public String getSelectedAction() {
        return (String) updateActionCombo.getSelectedItem();
    }

    public boolean isShowDialogEnabled() {
        return showDialogCheckBox.isSelected();
    }
}
