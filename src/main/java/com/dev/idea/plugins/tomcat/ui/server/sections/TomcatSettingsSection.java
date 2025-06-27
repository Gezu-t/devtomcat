package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Tomcat Settings Section - Ultimate Match
 * Matches EXACTLY the Ultimate screenshot:
 * - HTTP port: 8080
 * - HTTPS port: (empty)
 * - JMX port: 1099
 * - AJP port: (empty)
 * - ☐ Deploy applications configured in Tomcat instance
 * - ☐ Preserve sessions across restarts and redeploys
 */
public class TomcatSettingsSection implements ConfigurationSection {

    private JBTextField httpPortField;
    private JBTextField httpsPortField;
    private JBTextField jmxPortField;
    private JBTextField ajpPortField;
    private JBCheckBox deployAppsCheckBox;
    private JBCheckBox preserveSessionsCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel();
            panel.setBorder(BorderFactory.createTitledBorder("Tomcat Server Settings"));
            panel.setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(5);

            // HTTP port row
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JBLabel("HTTP port:"), gbc);

            gbc.gridx = 1;
            httpPortField = new JBTextField("8080", 10); // Default as shown in Ultimate
            panel.add(httpPortField, gbc);

            // Deploy applications checkbox (right side)
            gbc.gridx = 2; gbc.gridwidth = 2;
            deployAppsCheckBox = new JBCheckBox("Deploy applications configured in Tomcat instance");
            deployAppsCheckBox.setSelected(false); // Unchecked in Ultimate screenshot
            panel.add(deployAppsCheckBox, gbc);

            // HTTPS port row
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
            panel.add(new JBLabel("HTTPS port:"), gbc);

            gbc.gridx = 1;
            httpsPortField = new JBTextField(10); // Empty as shown in Ultimate
            panel.add(httpsPortField, gbc);

            // Preserve sessions checkbox (right side)
            gbc.gridx = 2; gbc.gridwidth = 2;
            preserveSessionsCheckBox = new JBCheckBox("Preserve sessions across restarts and redeploys");
            preserveSessionsCheckBox.setSelected(false); // Unchecked in Ultimate screenshot
            panel.add(preserveSessionsCheckBox, gbc);

            // JMX port row
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
            panel.add(new JBLabel("JMX port:"), gbc);

            gbc.gridx = 1;
            jmxPortField = new JBTextField("1099", 10); // Default as shown in Ultimate
            panel.add(jmxPortField, gbc);

            // AJP port row
            gbc.gridx = 0; gbc.gridy = 3;
            panel.add(new JBLabel("AJP port:"), gbc);

            gbc.gridx = 1;
            ajpPortField = new JBTextField(10); // Empty as shown in Ultimate
            panel.add(ajpPortField, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        // Set defaults to match Ultimate screenshot
        httpPortField.setText("8080");
        httpsPortField.setText("");
        jmxPortField.setText("1099");
        ajpPortField.setText("");
        deployAppsCheckBox.setSelected(false);
        preserveSessionsCheckBox.setSelected(false);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Load from configuration or use Ultimate defaults
        Integer httpPort = configuration.getPort();
        httpPortField.setText(httpPort != null ? httpPort.toString() : "8080");

        Integer jmxPort = configuration.getJmxPort();
        jmxPortField.setText(jmxPort != null ? jmxPort.toString() : "1099");

        // HTTPS and AJP are typically empty by default
        httpsPortField.setText("");
        ajpPortField.setText("");

        // Checkboxes default to unchecked as in Ultimate
        deployAppsCheckBox.setSelected(false);
        preserveSessionsCheckBox.setSelected(false);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            // HTTP port
            String httpPortText = httpPortField.getText().trim();
            if (!httpPortText.isEmpty()) {
                int httpPort = Integer.parseInt(httpPortText);
                configuration.setPort(httpPort);
            }

            // JMX port
            String jmxPortText = jmxPortField.getText().trim();
            if (!jmxPortText.isEmpty()) {
                int jmxPort = Integer.parseInt(jmxPortText);
                configuration.setJmxPort(jmxPort);
                configuration.setJmxEnabled(true);
            } else {
                configuration.setJmxEnabled(false);
            }

            System.out.println("DevTomcat: Applied Tomcat settings - HTTP: " + httpPortText +
                    ", JMX: " + jmxPortText);

        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid port number: " + e.getMessage());
        }
    }

    @Override
    public boolean isValid() {
        try {
            // Validate HTTP port
            String httpPortText = httpPortField.getText().trim();
            if (!httpPortText.isEmpty()) {
                Integer.parseInt(httpPortText);
            }

            // Validate JMX port if provided
            String jmxPortText = jmxPortField.getText().trim();
            if (!jmxPortText.isEmpty()) {
                Integer.parseInt(jmxPortText);
            }

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public boolean shouldFillVertically() {
        return false; // Tomcat settings section doesn't need vertical space
    }

    // Getters for external access
    public int getHttpPort() {
        try {
            String text = httpPortField.getText().trim();
            return text.isEmpty() ? 8080 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    public int getJmxPort() {
        try {
            String text = jmxPortField.getText().trim();
            return text.isEmpty() ? 1099 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 1099;
        }
    }

    public boolean isJmxEnabled() {
        return !jmxPortField.getText().trim().isEmpty();
    }

    public boolean isDeployAppsEnabled() {
        return deployAppsCheckBox.isSelected();
    }

    public boolean isPreserveSessionsEnabled() {
        return preserveSessionsCheckBox.isSelected();
    }
}