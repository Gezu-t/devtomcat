package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.JREConfigurationDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * JRE Configuration Section - Ultimate Match
 * Matches EXACTLY the Ultimate screenshot: "JRE:" label + dropdown + "..." button
 */
public class JreConfigurationSection implements ConfigurationSection {

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
            panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(5);

            // JRE: label
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("JRE:"), gbc);

            // JRE combo box
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            jreComboBox = new JComboBox<>();
            jreComboBox.setPreferredSize(new Dimension(200, 25));
            panel.add(jreComboBox, gbc);

            // Configure button
            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
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
        // Default option shown in Ultimate screenshot
        jreComboBox.addItem("Project default");
        // Could add other JRE options here when configured
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Default to project default as shown in Ultimate
        jreComboBox.setSelectedItem("Project default");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // JRE configuration is typically handled at the IDE level
        String selectedJre = (String) jreComboBox.getSelectedItem();
        System.out.println("DevTomcat: JRE selection: " + selectedJre);
    }

    @Override
    public boolean isValid() {
        return true; // JRE configuration is always valid
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
                        jreComboBox.setSelectedItem("Project default");
                    }
                    System.out.println("DevTomcat: JRE configuration updated to: " + selectedJdk.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening JRE configuration: " + e.getMessage());
            Messages.showErrorDialog(project, "Failed to open JRE configuration: " + e.getMessage(), "Error");
        }
    }

    public String getSelectedJRE() {
        return (String) jreComboBox.getSelectedItem();
    }
}