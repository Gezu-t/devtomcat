package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.JREConfigurationDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;
import com.dev.idea.plugins.tomcat.TomcatConstants;

public class JreConfigurationSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(JreConfigurationSection.class);

    private final Project project;
    private JComboBox<String> jreComboBox;
    private JButton jreConfigureButton;
    private JPanel panel;

    public JreConfigurationSection(Project project) {
        this.project = project;
    }

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
            panel.add(new JLabel("JRE:"), gbc);

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(4, 4, 4, 4);
            jreComboBox = new JComboBox<>();
            panel.add(jreComboBox, gbc);

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(4, 0, 4, 0);
            jreConfigureButton = new JButton("...");
            jreConfigureButton.setPreferredSize(new Dimension(30, 25));
            jreConfigureButton.addActionListener(e -> configureJRE());
            panel.add(jreConfigureButton, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        jreComboBox.removeAllItems();
        jreComboBox.addItem(TomcatConstants.JRE_PROJECT_DEFAULT);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        jreComboBox.setSelectedItem(TomcatConstants.JRE_PROJECT_DEFAULT);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String selectedJre = (String) jreComboBox.getSelectedItem();
        LOG.debug("DevTomcat: JRE selection: " + selectedJre);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        return false;
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
                        jreComboBox.addItem(selectedJdk.toString());
                        jreComboBox.setSelectedItem(selectedJdk.toString());
                    } else {
                        jreComboBox.setSelectedItem(TomcatConstants.JRE_PROJECT_DEFAULT);
                    }
                    LOG.debug("DevTomcat: JRE configuration updated to: " + selectedJdk.getName());
                }
            }
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error opening JRE configuration: " + e.getMessage());
            Messages.showErrorDialog(project, "Failed to open JRE configuration: " + e.getMessage(), "Error");
        }
    }

    public String getSelectedJRE() {
        return (String) jreComboBox.getSelectedItem();
    }

}
