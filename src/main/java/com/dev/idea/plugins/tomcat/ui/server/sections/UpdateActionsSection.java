package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import com.dev.idea.plugins.tomcat.TomcatConstants;

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
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(2, 0, 2, 0));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(4, 0, 4, 4);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("On 'Update' action:"), gbc);

            gbc.gridx = 1;
            gbc.insets = JBUI.insets(4, 4, 4, 8);
            updateActionCombo = new ComboBox<>();
            updateActionCombo.setPreferredSize(new Dimension(180, 25));
            panel.add(updateActionCombo, gbc);

            gbc.gridx = 2;
            gbc.insets = JBUI.insets(4, 4, 4, 4);
            showDialogCheckBox = new JCheckBox("Show dialog");
            showDialogCheckBox.setSelected(true);
            panel.add(showDialogCheckBox, gbc);

            // Spacer column pushes everything to the left
            gbc.gridx = 3;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(Box.createHorizontalGlue(), gbc);

            gbc.gridx = 0; gbc.gridy = 1;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(4, 0, 4, 4);
            panel.add(new JLabel("On frame deactivation:"), gbc);

            gbc.gridx = 1;
            gbc.insets = JBUI.insets(4, 4, 4, 8);
            frameDeactivationCombo = new ComboBox<>();
            frameDeactivationCombo.setPreferredSize(new Dimension(180, 25));
            panel.add(frameDeactivationCombo, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        String[] options = {TomcatConstants.ACTION_UPDATE_RESOURCES, TomcatConstants.ACTION_UPDATE_CLASSES_AND_RESOURCES, TomcatConstants.ACTION_REDEPLOY, TomcatConstants.ACTION_RESTART_SERVER};
        updateActionCombo.removeAllItems();
        for (String option : options) {
            updateActionCombo.addItem(option);
        }
        updateActionCombo.setSelectedItem(TomcatConstants.ACTION_RESTART_SERVER);
        showDialogCheckBox.setSelected(true);

        frameDeactivationCombo.removeAllItems();
        frameDeactivationCombo.addItem(TomcatConstants.ACTION_DO_NOTHING);
        for (String option : options) {
            frameDeactivationCombo.addItem(option);
        }
        frameDeactivationCombo.setSelectedItem(TomcatConstants.ACTION_DO_NOTHING);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        UpdateConfig uc = configuration.getConfigData().getUpdateConfig();
        updateActionCombo.setSelectedItem(mapInternalToDisplay(uc.getOnUpdate()));
        showDialogCheckBox.setSelected(uc.isShowUpdateDialog());
        frameDeactivationCombo.setSelectedItem(mapInternalToDisplay(uc.getOnFrameDeactivation()));
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        UpdateConfig uc = configuration.getConfigData().getUpdateConfig();
        uc.setOnUpdate(mapDisplayToInternal(getSelectedAction()));
        uc.setShowUpdateDialog(isShowDialogEnabled());
        String frameAction = (String) frameDeactivationCombo.getSelectedItem();
        uc.setOnFrameDeactivation(mapDisplayToInternal(frameAction));
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
        UpdateConfig uc = config.getConfigData().getUpdateConfig();
        if (!Objects.equals(mapInternalToDisplay(uc.getOnUpdate()), getSelectedAction())) return true;
        if (uc.isShowUpdateDialog() != isShowDialogEnabled()) return true;
        String frameAction = (String) frameDeactivationCombo.getSelectedItem();
        return !Objects.equals(mapInternalToDisplay(uc.getOnFrameDeactivation()), frameAction);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    public String getSelectedAction() {
        return (String) updateActionCombo.getSelectedItem();
    }

    public boolean isShowDialogEnabled() {
        return showDialogCheckBox.isSelected();
    }

    private static final Map<String, String> INTERNAL_TO_DISPLAY = Map.of(
            UpdateConfig.UPDATE_RESOURCES, TomcatConstants.ACTION_UPDATE_RESOURCES,
            UpdateConfig.UPDATE_CLASSES_AND_RESOURCES, TomcatConstants.ACTION_UPDATE_CLASSES_AND_RESOURCES,
            UpdateConfig.REDEPLOY, TomcatConstants.ACTION_REDEPLOY,
            UpdateConfig.RESTART_SERVER, TomcatConstants.ACTION_RESTART_SERVER,
            UpdateConfig.DO_NOTHING, TomcatConstants.ACTION_DO_NOTHING
    );

    private static final Map<String, String> DISPLAY_TO_INTERNAL;
    static {
        Map<String, String> m = new HashMap<>();
        INTERNAL_TO_DISPLAY.forEach((k, v) -> m.put(v, k));
        DISPLAY_TO_INTERNAL = Collections.unmodifiableMap(m);
    }

    private static String mapInternalToDisplay(String internal) {
        return INTERNAL_TO_DISPLAY.getOrDefault(internal, TomcatConstants.ACTION_UPDATE_CLASSES_AND_RESOURCES);
    }

    private static String mapDisplayToInternal(String display) {
        return DISPLAY_TO_INTERNAL.getOrDefault(display, UpdateConfig.DEFAULT_ON_UPDATE);
    }
}
