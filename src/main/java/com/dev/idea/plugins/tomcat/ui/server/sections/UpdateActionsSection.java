package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
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

    private ComboBox<String> updateActionCombo;
    private JBCheckBox showDialogCheckBox;
    private ComboBox<String> frameDeactivationCombo;
    private JBCheckBox updateOnSaveCheckBox;
    private JBCheckBox rebuildBeforeRedeployCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(0));
            GridBagConstraints gbc = new GridBagConstraints();

            updateActionCombo = new ComboBox<>();
            updateActionCombo.setToolTipText(
                    "'Redeploy' deploys artifacts as last built (packed WARs are rebuilt first "
                            + "only when the rebuild option below is enabled).");
            ConfigurationSection.addLabelAndField(panel, gbc, 0,
                    new JBLabel("On 'Update' action:"), updateActionCombo);
            gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 4, 2, 4);
            showDialogCheckBox = new JBCheckBox("Show dialog");
            showDialogCheckBox.setToolTipText(
                    "Show a confirmation dialog before running the update or frame-deactivation action");
            showDialogCheckBox.setSelected(true);
            panel.add(showDialogCheckBox, gbc);

            frameDeactivationCombo = new ComboBox<>();
            ConfigurationSection.addLabelAndField(panel, gbc, 1,
                    new JBLabel("On frame deactivation:"), frameDeactivationCombo);

            updateOnSaveCheckBox = new JBCheckBox("Update on save (while running)");
            updateOnSaveCheckBox.setToolTipText(
                    "While the server is running, saving a file automatically applies the "
                            + "'On Update' action. Debounced; off by default.");
            gbc.gridx = 1; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(2, 4, 2, 8);
            panel.add(updateOnSaveCheckBox, gbc);

            rebuildBeforeRedeployCheckBox = new JBCheckBox("Run build-tool package before redeploy");
            rebuildBeforeRedeployCheckBox.setToolTipText(
                    "On Redeploy, runs the Maven 'package' goal for each WAR deployment's module "
                            + "through the IDE's Maven integration (project Maven settings and profiles "
                            + "are respected) before copying the WAR. No effect when the Maven "
                            + "integration is unavailable.");
            gbc.gridx = 1; gbc.gridy = 3;
            panel.add(rebuildBeforeRedeployCheckBox, gbc);
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
        updateActionCombo.setSelectedItem(TomcatConstants.ACTION_UPDATE_CLASSES_AND_RESOURCES);
        showDialogCheckBox.setSelected(true);

        frameDeactivationCombo.removeAllItems();
        frameDeactivationCombo.addItem(TomcatConstants.ACTION_DO_NOTHING);
        for (String option : options) {
            frameDeactivationCombo.addItem(option);
        }
        frameDeactivationCombo.setSelectedItem(TomcatConstants.ACTION_DO_NOTHING);

        updateOnSaveCheckBox.setSelected(UpdateConfig.DEFAULT_UPDATE_ON_SAVE);
        rebuildBeforeRedeployCheckBox.setSelected(UpdateConfig.DEFAULT_REBUILD_BEFORE_REDEPLOY);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        UpdateConfig uc = configuration.getConfigData().getUpdateConfig();
        updateActionCombo.setSelectedItem(mapInternalToDisplay(uc.getOnUpdate()));
        frameDeactivationCombo.setSelectedItem(mapInternalToDisplay(uc.getOnFrameDeactivation()));
        // Consolidated checkbox governs both prompts. OR-read preserves the "ask before acting"
        // intent of any config (legacy or imported) that had either flag enabled.
        showDialogCheckBox.setSelected(uc.isShowUpdateDialog() || uc.isShowFrameDeactivationDialog());
        updateOnSaveCheckBox.setSelected(uc.isUpdateOnSave());
        rebuildBeforeRedeployCheckBox.setSelected(uc.isRebuildBeforeRedeploy());
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        UpdateConfig uc = configuration.getConfigData().getUpdateConfig();
        uc.setOnUpdate(mapDisplayToInternal(getSelectedAction()));
        String frameAction = (String) frameDeactivationCombo.getSelectedItem();
        uc.setOnFrameDeactivation(mapDisplayToInternal(frameAction));
        // Single UI switch drives both persisted flags so the runtime listeners
        // (TomcatApplicationUpdater, TomcatFrameDeactivationListener) stay in sync.
        boolean show = isShowDialogEnabled();
        uc.setShowUpdateDialog(show);
        uc.setShowFrameDeactivationDialog(show);
        uc.setUpdateOnSave(updateOnSaveCheckBox.isSelected());
        uc.setRebuildBeforeRedeploy(rebuildBeforeRedeployCheckBox.isSelected());
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        UpdateConfig uc = config.getConfigData().getUpdateConfig();
        if (!Objects.equals(mapInternalToDisplay(uc.getOnUpdate()), getSelectedAction())) return true;
        String frameAction = (String) frameDeactivationCombo.getSelectedItem();
        if (!Objects.equals(mapInternalToDisplay(uc.getOnFrameDeactivation()), frameAction)) return true;
        // Modified if the UI checkbox doesn't match BOTH persisted flags — forces an apply
        // that re-syncs them, even when they were out-of-sync in storage (legacy/imported).
        boolean show = isShowDialogEnabled();
        if (uc.isShowUpdateDialog() != show || uc.isShowFrameDeactivationDialog() != show) return true;
        if (uc.isUpdateOnSave() != updateOnSaveCheckBox.isSelected()) return true;
        return uc.isRebuildBeforeRedeploy() != rebuildBeforeRedeployCheckBox.isSelected();
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    private String getSelectedAction() {
        return (String) updateActionCombo.getSelectedItem();
    }

    private boolean isShowDialogEnabled() {
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
