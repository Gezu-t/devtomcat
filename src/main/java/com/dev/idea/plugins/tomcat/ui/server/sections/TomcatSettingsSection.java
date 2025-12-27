package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.utils.PortValidator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TomcatSettingsSection implements ConfigurationSection {
    private static final Logger LOG = Logger.getInstance(TomcatSettingsSection.class);
    private JBTextField httpPortField;
    private JBTextField httpsPortField;
    private JBTextField jmxPortField;
    private JBTextField ajpPortField;
    private Integer shutdownPortCached;
    private JBCheckBox deployAppsCheckBox;
    private JBCheckBox preserveSessionsCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel();
            panel.setBorder(BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1),
                    JBUI.Borders.empty(4, 4, 4, 4)
            ));
            panel.setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(4, 0, 4, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 3;
            JBLabel heading = new JBLabel("Tomcat Server Settings");
            heading.setFont(heading.getFont().deriveFont(Font.BOLD));
            panel.add(heading, gbc);
            gbc.gridwidth = 1;
            // HTTP
            gbc.gridx = 0; gbc.gridy = row;
            panel.add(new JBLabel("HTTP port:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0;
            httpPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpPort()), 8);
            panel.add(httpPortField, gbc);
            gbc.gridx = 2; gbc.weightx = 0;
            deployAppsCheckBox = new JBCheckBox("Deploy applications configured in Tomcat instance");
            deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
            panel.add(deployAppsCheckBox, gbc);

            // HTTPS port
            row++;
            gbc.gridx = 0; gbc.gridy = row;
            panel.add(new JBLabel("HTTPS port:"), gbc);
            gbc.gridx = 1;
            httpsPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()), 8);
            panel.add(httpsPortField, gbc);
            gbc.gridx = 2;
            preserveSessionsCheckBox = new JBCheckBox("Preserve sessions across restarts and redeploys");
            preserveSessionsCheckBox.setSelected(false);
            panel.add(preserveSessionsCheckBox, gbc);

            // JMX port
            row++;
            gbc.gridx = 0; gbc.gridy = row;
            panel.add(new JBLabel("JMX port:"), gbc);
            gbc.gridx = 1;
            jmxPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getJmxPort()), 8);
            panel.add(jmxPortField, gbc);

            // AJP port
            row++;
            gbc.gridx = 0; gbc.gridy = row;
            panel.add(new JBLabel("AJP port:"), gbc);
            gbc.gridx = 1;
            ajpPortField = new JBTextField("", 8);
            panel.add(ajpPortField, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        httpPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpPort()));
        httpsPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()));
        jmxPortField.setText(String.valueOf(DynamicTomcatEnvironment.getJmxPort()));
        ajpPortField.setText(""); // AJP port is optional, leave empty by default
        deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(false);
        shutdownPortCached = DynamicTomcatEnvironment.getShutdownPort();
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        Integer httpPort = configuration.getHttpPort();
        httpPortField.setText(httpPort != null ? httpPort.toString() : DynamicTomcatEnvironment.getHttpPort() + "");

        Integer httpsPort = configuration.getHttpsPort();
        httpsPortField.setText(httpsPort != null ? httpsPort.toString() : "");

        Integer jmxPort = configuration.getJmxPort();
        jmxPortField.setText(jmxPort != null ? jmxPort.toString() : DynamicTomcatEnvironment.getJmxPort() + "");

        // Get AJP port from configuration
        Integer ajpPort = configuration.getConfigData().getPortConfig().isAjpEnabled()
                ? configuration.getConfigData().getPortConfig().getAjp()
                : null;
        ajpPortField.setText(ajpPort != null ? ajpPort.toString() : "");

        deployAppsCheckBox.setSelected(configuration.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(configuration.isPreserveSessions());

        Integer shutdownPort = configuration.getShutdownPort();
        shutdownPortCached = shutdownPort != null ? shutdownPort : DynamicTomcatEnvironment.getShutdownPort();

        LOG.debug("Reset Tomcat settings: HTTP=" + httpPortField.getText() +
                ", Shutdown=" + shutdownPortCached +
                ", HTTPS=" + httpsPortField.getText() +
                ", JMX=" + jmxPortField.getText() +
                ", AJP=" + ajpPortField.getText());
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        Integer ajpPort = parsePort(ajpPortField.getText(), "AJP");

        PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                .httpPort(parsePort(httpPortField.getText(), "HTTP"))
                .shutdownPort(shutdownPortCached)
                .httpsPort(parsePort(httpsPortField.getText(), "HTTPS"))
                .httpsEnabled(parsePort(httpsPortField.getText(), "HTTPS") != null)
                .jmxPort(parsePort(jmxPortField.getText(), "JMX"))
                .jmxEnabled(parsePort(jmxPortField.getText(), "JMX") != null)
                .ajpPort(ajpPort)
                .ajpEnabled(ajpPort != null)
                .build();

        PortValidator.validateOrThrow(portConfig);

        // Set port values
        configuration.setHttpPort(portConfig.httpPort);
        configuration.setHttpsPort(portConfig.httpsPort);
        configuration.setJmxPort(portConfig.jmxPort);

        // Set enabled flags and AJP port directly on PortConfig
        configuration.getConfigData().getPortConfig().setHttpsEnabled(portConfig.httpsEnabled);
        configuration.getConfigData().getPortConfig().setJmxEnabled(portConfig.jmxEnabled);
        configuration.getConfigData().getPortConfig().setAjpEnabled(portConfig.ajpEnabled);
        if (portConfig.ajpEnabled && portConfig.ajpPort != null) {
            configuration.getConfigData().getPortConfig().setAjp(portConfig.ajpPort);
        }

        // Set deployment settings
        configuration.setHotDeploymentEnabled(deployAppsCheckBox.isSelected());
        configuration.setPreserveSessions(preserveSessionsCheckBox.isSelected());

        LOG.info("Applied Tomcat settings - HTTP: " + portConfig.httpPort +
                ", Shutdown: " + portConfig.shutdownPort +
                ", HTTPS: " + (portConfig.httpsEnabled ? portConfig.httpsPort : "disabled") +
                ", JMX: " + (portConfig.jmxEnabled ? portConfig.jmxPort : "disabled") +
                ", AJP: " + (portConfig.ajpEnabled ? portConfig.ajpPort : "disabled") +
                ", Hot Deployment: " + deployAppsCheckBox.isSelected() +
                ", Preserve Sessions: " + preserveSessionsCheckBox.isSelected());
    }

    @Override
    public boolean isValid() {
        try {
            Integer httpP = parsePort(httpPortField.getText(), "HTTP");
            Integer httpsP = parsePort(httpsPortField.getText(), "HTTPS");
            Integer jmxP = parsePort(jmxPortField.getText(), "JMX");
            Integer ajpP = parsePort(ajpPortField.getText(), "AJP");
            PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                    .httpPort(httpP)
                    .shutdownPort(shutdownPortCached)
                    .httpsPort(httpsP)
                    .httpsEnabled(httpsP != null)
                    .jmxPort(jmxP)
                    .jmxEnabled(jmxP != null)
                    .ajpPort(ajpP)
                    .ajpEnabled(ajpP != null)
                    .build();
            return PortValidator.validate(portConfig).isValid();
        } catch (ConfigurationException e) {
            return false;
        }
    }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        try {
            // Check if any port values changed
            Integer configHttpPort = config.getHttpPort();
            Integer currentHttpPort = parsePort(httpPortField.getText(), "HTTP");
            if (!Objects.equals(configHttpPort, currentHttpPort)) {
                return true;
            }

            Integer configShutdownPort = config.getShutdownPort();
            if (!Objects.equals(configShutdownPort, shutdownPortCached)) {
                return true;
            }

            Integer configHttpsPort = config.getHttpsPort();
            Integer currentHttpsPort = parsePort(httpsPortField.getText(), "HTTPS");
            if (!Objects.equals(configHttpsPort, currentHttpsPort)) {
                return true;
            }

            Integer configJmxPort = config.getJmxPort();
            Integer currentJmxPort = parsePort(jmxPortField.getText(), "JMX");
            if (!Objects.equals(configJmxPort, currentJmxPort)) {
                return true;
            }

            // Check AJP port
            Integer configAjpPort = config.getConfigData().getPortConfig().isAjpEnabled()
                    ? config.getConfigData().getPortConfig().getAjp()
                    : null;
            Integer currentAjpPort = parsePort(ajpPortField.getText(), "AJP");
            if (!Objects.equals(configAjpPort, currentAjpPort)) {
                return true;
            }

            // Check deployment settings
            if (config.isHotDeploymentEnabled() != deployAppsCheckBox.isSelected()) {
                return true;
            }
            if (config.isPreserveSessions() != preserveSessionsCheckBox.isSelected()) {
                return true;
            }

        } catch (ConfigurationException e) {
            LOG.warn("Error checking modifications", e);
            return true; // If we can't parse, assume modified
        }

        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();

        try {
            Integer httpP = parsePort(httpPortField.getText(), "HTTP");
            Integer httpsP = parsePort(httpsPortField.getText(), "HTTPS");
            Integer jmxP = parsePort(jmxPortField.getText(), "JMX");
            Integer ajpP = parsePort(ajpPortField.getText(), "AJP");
            PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                    .httpPort(httpP)
                    .shutdownPort(shutdownPortCached)
                    .httpsPort(httpsP)
                    .httpsEnabled(httpsP != null)
                    .jmxPort(jmxP)
                    .jmxEnabled(jmxP != null)
                    .ajpPort(ajpP)
                    .ajpEnabled(ajpP != null)
                    .build();

            PortValidator.ValidationResult result = PortValidator.validate(portConfig);

            if (!result.isValid()) {
                for (String error : result.getErrors()) {
                    errors.add(new ValidationInfo(error, httpPortField));
                }
            }

        } catch (ConfigurationException e) {
            errors.add(new ValidationInfo(e.getMessage(), httpPortField));
        }

        return errors;
    }

    public Integer getHttpPort() throws ConfigurationException {
        return parsePort(httpPortField.getText(), "HTTP");
    }

    public Integer getShutdownPort() throws ConfigurationException {
        return shutdownPortCached;
    }

    public boolean isHttpsEnabled() {
        try {
            return parsePort(httpsPortField.getText(), "HTTPS") != null;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    public Integer getHttpsPort() throws ConfigurationException {
        return parsePort(httpsPortField.getText(), "HTTPS");
    }

    public boolean isJmxEnabled() {
        try {
            return parsePort(jmxPortField.getText(), "JMX") != null;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    public Integer getJmxPort() throws ConfigurationException {
        return parsePort(jmxPortField.getText(), "JMX");
    }

    public boolean isDeployAppsEnabled() {
        return deployAppsCheckBox.isSelected();
    }

    public boolean isPreserveSessionsEnabled() {
        return preserveSessionsCheckBox.isSelected();
    }

    private Integer parsePort(String text, String portName) throws ConfigurationException {
        try {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(portName + " port must be a valid number");
        }
    }
}
