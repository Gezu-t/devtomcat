package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.JREConfigurationDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.intellij.openapi.diagnostic.Logger;

public class JreConfigurationSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(JreConfigurationSection.class);

    private final Project project;
    private ComboBox<JreEntry> jreComboBox;
    private JPanel panel;

    public JreConfigurationSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(0));
            GridBagConstraints gbc = new GridBagConstraints();
            jreComboBox = new ComboBox<>();
            jreComboBox.setRenderer(new JreEntryRenderer());
            ConfigurationSection.addLabelAndField(panel, gbc, 0,
                    new JBLabel("JRE:"), jreComboBox);

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 0);
            JButton configButton = new JButton("Configure...");
            configButton.addActionListener(e -> configureJRE());
            Dimension comboSize = jreComboBox.getPreferredSize();
            Dimension buttonSize = configButton.getPreferredSize();
            configButton.setPreferredSize(new Dimension(
                    Math.max(buttonSize.width, JBUI.scale(96)),
                    comboSize.height
            ));
            panel.add(configButton, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        jreComboBox.removeAllItems();

        // Add project default entry with actual SDK version info
        String projectSdkLabel = buildProjectSdkLabel();
        jreComboBox.addItem(new JreEntry(projectSdkLabel, null, true));

        // Add all configured Java SDKs
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (sdk.getSdkType() instanceof JavaSdk) {
                String version = sdk.getVersionString();
                String label = sdk.getName() + (version != null ? " (" + version + ")" : "");
                jreComboBox.addItem(new JreEntry(label, sdk.getName(), false));
            }
        }
    }

    private String buildProjectSdkLabel() {
        try {
            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk != null) {
                String version = projectSdk.getVersionString();
                String majorVersion = extractMajorVersion(version);
                if (majorVersion != null) {
                    return "Default (" + majorVersion + " - project SDK)";
                }
                return "Default (" + projectSdk.getName() + " - project SDK)";
            }
        } catch (Exception e) {
            LOG.debug("Could not detect project SDK", e);
        }
        return "Default (project SDK)";
    }

    private String extractMajorVersion(String versionString) {
        if (versionString == null) return null;
        // Version strings are like "17.0.2", "java version \"21.0.1\"", "1.8.0_351"
        String cleaned = versionString.replaceAll("[^0-9.]", "").trim();
        if (cleaned.isEmpty()) return null;

        String[] parts = cleaned.split("\\.");
        if (parts.length > 0) {
            String major = parts[0];
            // For old-style "1.8.x" versions, use the minor version
            if ("1".equals(major) && parts.length > 1) {
                return parts[1];
            }
            return major;
        }
        return null;
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        String saved = configuration.getConfigData().getJreSelection();
        if (saved == null || saved.isEmpty()
                || TomcatConstants.JRE_PROJECT_DEFAULT.equals(saved)) {
            if (jreComboBox.getItemCount() > 0) {
                jreComboBox.setSelectedIndex(0);
            }
        } else {
            boolean found = false;
            for (int i = 0; i < jreComboBox.getItemCount(); i++) {
                JreEntry entry = jreComboBox.getItemAt(i);
                if (saved.equals(entry.sdkName)) {
                    jreComboBox.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found && jreComboBox.getItemCount() > 0) {
                jreComboBox.setSelectedIndex(0);
            }
        }
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        JreEntry selected = (JreEntry) jreComboBox.getSelectedItem();
        if (selected != null) {
            String sdkName = selected.isDefault
                    ? TomcatConstants.JRE_PROJECT_DEFAULT
                    : selected.sdkName;
            configuration.getConfigData().setJreSelection(sdkName);
        }
    }

    @Override
    public boolean isConfigurationValid() {
        return true;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        JreEntry selected = (JreEntry) jreComboBox.getSelectedItem();
        if (selected == null) return false;
        String current = selected.isDefault
                ? TomcatConstants.JRE_PROJECT_DEFAULT
                : selected.sdkName;
        String saved = config.getConfigData().getJreSelection();
        return !Objects.equals(current, saved);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    private void configureJRE() {
        try {
            JREConfigurationDialog dialog = new JREConfigurationDialog(project);
            if (dialog.showAndGet()) {
                JREConfigurationDialog.JdkInfo selectedJdk = dialog.getSelectedJdk();
                if (selectedJdk != null) {
                    loadConfiguration();
                    if (!selectedJdk.isProjectSdk()) {
                        // Select the matching SDK in the combo
                        for (int i = 0; i < jreComboBox.getItemCount(); i++) {
                            JreEntry entry = jreComboBox.getItemAt(i);
                            if (selectedJdk.getName().equals(entry.sdkName)) {
                                jreComboBox.setSelectedIndex(i);
                                break;
                            }
                        }
                    } else {
                        jreComboBox.setSelectedIndex(0);
                    }
                    LOG.debug("JRE configuration updated to: " + selectedJdk.getName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Error opening JRE configuration", e);
            Messages.showErrorDialog(project, "Failed to open JRE configuration: " + e.getMessage(), "Error");
        }
    }

    public String getSelectedJRE() {
        JreEntry entry = (JreEntry) jreComboBox.getSelectedItem();
        return entry != null ? entry.label : null;
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    private static class JreEntry {
        final String label;
        final String sdkName;
        final boolean isDefault;

        JreEntry(String label, String sdkName, boolean isDefault) {
            this.label = label;
            this.sdkName = sdkName;
            this.isDefault = isDefault;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class JreEntryRenderer extends com.intellij.ui.SimpleListCellRenderer<JreEntry> {
        @Override
        public void customize(@NotNull JList<? extends JreEntry> list, JreEntry value, int index,
                              boolean selected, boolean hasFocus) {
            if (value != null) {
                setText(value.label);
                setToolTipText(value.isDefault ? "Uses the project's configured SDK" : value.sdkName);
            }
        }
    }
}
