package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.PortValidator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TomcatSettingsSection implements ConfigurationSection {
    private static final Logger LOG = Logger.getInstance(TomcatSettingsSection.class);
    private final @Nullable Project project;
    private JBTextField httpPortField;
    private JBTextField httpsPortField;
    private JBTextField jmxPortField;
    private JBTextField ajpPortField;
    private JBTextField shutdownPortField;
    private TextFieldWithBrowseButton catalinaBaseField;
    private JBCheckBox deployAppsCheckBox;
    private JBCheckBox preserveSessionsCheckBox;
    private JPanel panel;

    public TomcatSettingsSection() {
        this(null);
    }

    public TomcatSettingsSection(@Nullable Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(0));

            TitledSeparator separator = new TitledSeparator("Tomcat Server Settings");
            separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, separator.getPreferredSize().height));
            panel.add(separator);

            JPanel formPanel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;

            int row = 0;

            // Row 0: HTTP port + Deploy checkbox
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("HTTP port:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
            httpPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpPort()), 8);
            formPanel.add(httpPortField, gbc);

            gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, JBUI.scale(20), 2, 4);
            deployAppsCheckBox = new JBCheckBox("Deploy applications configured in Tomcat instance");
            deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
            formPanel.add(deployAppsCheckBox, gbc);

            // Row 1: HTTPS port + Preserve sessions checkbox
            row++;
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("HTTPs port:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
            httpsPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()), 8);
            formPanel.add(httpsPortField, gbc);

            gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, JBUI.scale(20), 2, 4);
            preserveSessionsCheckBox = new JBCheckBox("Preserve sessions across restarts and redeploys");
            preserveSessionsCheckBox.setSelected(false);
            formPanel.add(preserveSessionsCheckBox, gbc);

            // Row 2: JMX port
            row++;
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("JMX port:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
            jmxPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getJmxPort()), 8);
            formPanel.add(jmxPortField, gbc);

            // Row 3: AJP port
            row++;
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("AJP port:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
            ajpPortField = new JBTextField("", 8);
            formPanel.add(ajpPortField, gbc);

            // Row 4: Shutdown port
            row++;
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("Shutdown port:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
            shutdownPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getShutdownPort()), 8);
            formPanel.add(shutdownPortField, gbc);

            // Row 5: CATALINA_BASE
            row++;
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("CATALINA_BASE:"), gbc);

            gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            catalinaBaseField = new TextFieldWithBrowseButton();
            com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                    catalinaBaseField, "Select CATALINA_BASE Directory", "Choose the base directory for this Tomcat instance",
                    project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
            catalinaBaseField.getTextField().setToolTipText("Leave empty to use default (auto-generated per configuration)");
            formPanel.add(catalinaBaseField, gbc);
            gbc.gridwidth = 1;

            formPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, formPanel.getPreferredSize().height));
            panel.add(formPanel);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        httpPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpPort()));
        httpsPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()));
        jmxPortField.setText(String.valueOf(DynamicTomcatEnvironment.getJmxPort()));
        ajpPortField.setText("");
        shutdownPortField.setText(String.valueOf(DynamicTomcatEnvironment.getShutdownPort()));
        catalinaBaseField.setText("");
        deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(false);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        Integer httpPort = configuration.getHttpPort();
        httpPortField.setText(httpPort != null ? httpPort.toString() : DynamicTomcatEnvironment.getHttpPort() + "");

        Integer httpsPort = configuration.getHttpsPort();
        httpsPortField.setText(httpsPort != null ? httpsPort.toString() : "");

        Integer jmxPort = configuration.getJmxPort();
        jmxPortField.setText(jmxPort != null ? jmxPort.toString() : DynamicTomcatEnvironment.getJmxPort() + "");

        Integer ajpPort = configuration.getConfigData().getPortConfig().isAjpEnabled()
                ? configuration.getConfigData().getPortConfig().getAjp()
                : null;
        ajpPortField.setText(ajpPort != null ? ajpPort.toString() : "");

        Integer shutdownPort = configuration.getShutdownPort();
        shutdownPortField.setText(shutdownPort != null ? shutdownPort.toString()
                : String.valueOf(DynamicTomcatEnvironment.getShutdownPort()));

        String catalinaBase = configuration.getConfigData().getCatalinaBase();
        catalinaBaseField.setText(catalinaBase != null ? catalinaBase : "");

        deployAppsCheckBox.setSelected(configuration.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(configuration.isPreserveSessions());

        LOG.debug("Reset Tomcat settings: HTTP=" + httpPortField.getText() +
                ", Shutdown=" + shutdownPortField.getText() +
                ", HTTPS=" + httpsPortField.getText() +
                ", JMX=" + jmxPortField.getText() +
                ", AJP=" + ajpPortField.getText() +
                ", CATALINA_BASE=" + catalinaBaseField.getText());
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        Integer ajpPort = PortUtils.parsePort(ajpPortField.getText(), "AJP");
        Integer shutdownPort = PortUtils.parsePort(shutdownPortField.getText(), "Shutdown");

        PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                .httpPort(PortUtils.parsePort(httpPortField.getText(), "HTTP"))
                .shutdownPort(shutdownPort)
                .httpsPort(PortUtils.parsePort(httpsPortField.getText(), "HTTPS"))
                .httpsEnabled(PortUtils.parsePort(httpsPortField.getText(), "HTTPS") != null)
                .jmxPort(PortUtils.parsePort(jmxPortField.getText(), "JMX"))
                .jmxEnabled(PortUtils.parsePort(jmxPortField.getText(), "JMX") != null)
                .ajpPort(ajpPort)
                .ajpEnabled(ajpPort != null)
                .build();

        PortValidator.validateOrThrow(portConfig);

        configuration.setHttpPort(portConfig.httpPort);
        configuration.setShutdownPort(portConfig.shutdownPort);
        configuration.setHttpsPort(portConfig.httpsPort);
        configuration.setJmxPort(portConfig.jmxPort);

        configuration.getConfigData().getPortConfig().setHttpsEnabled(portConfig.httpsEnabled);
        configuration.getConfigData().getPortConfig().setJmxEnabled(portConfig.jmxEnabled);
        configuration.getConfigData().getPortConfig().setAjpEnabled(portConfig.ajpEnabled);
        if (portConfig.ajpEnabled && portConfig.ajpPort != null) {
            configuration.getConfigData().getPortConfig().setAjp(portConfig.ajpPort);
        }

        String catalinaBase = catalinaBaseField.getText().trim();
        configuration.getConfigData().setCatalinaBase(catalinaBase.isEmpty() ? null : catalinaBase);

        configuration.setHotDeploymentEnabled(deployAppsCheckBox.isSelected());
        configuration.setPreserveSessions(preserveSessionsCheckBox.isSelected());

        LOG.debug("Applied Tomcat settings - HTTP: " + portConfig.httpPort +
                ", Shutdown: " + portConfig.shutdownPort +
                ", HTTPS: " + (portConfig.httpsEnabled ? portConfig.httpsPort : "disabled") +
                ", JMX: " + (portConfig.jmxEnabled ? portConfig.jmxPort : "disabled") +
                ", AJP: " + (portConfig.ajpEnabled ? portConfig.ajpPort : "disabled") +
                ", Hot Deployment: " + deployAppsCheckBox.isSelected() +
                ", Preserve Sessions: " + preserveSessionsCheckBox.isSelected());
    }

    private Integer parseShutdownPort() {
        try {
            return PortUtils.parsePort(shutdownPortField.getText(), "Shutdown");
        } catch (ConfigurationException e) {
            return null;
        }
    }

    @Override
    public boolean isConfigurationValid() {
        try {
            Integer httpP = PortUtils.parsePort(httpPortField.getText(), "HTTP");
            Integer httpsP = PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
            Integer jmxP = PortUtils.parsePort(jmxPortField.getText(), "JMX");
            Integer ajpP = PortUtils.parsePort(ajpPortField.getText(), "AJP");
            Integer shutdownP = parseShutdownPort();
            PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                    .httpPort(httpP)
                    .shutdownPort(shutdownP)
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
            Integer configHttpPort = config.getHttpPort();
            Integer currentHttpPort = PortUtils.parsePort(httpPortField.getText(), "HTTP");
            if (!Objects.equals(configHttpPort, currentHttpPort)) {
                return true;
            }

            Integer configShutdownPort = config.getShutdownPort();
            Integer currentShutdownPort = parseShutdownPort();
            if (!Objects.equals(configShutdownPort, currentShutdownPort)) {
                return true;
            }

            Integer configHttpsPort = config.getHttpsPort();
            Integer currentHttpsPort = PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
            if (!Objects.equals(configHttpsPort, currentHttpsPort)) {
                return true;
            }

            Integer configJmxPort = config.getJmxPort();
            Integer currentJmxPort = PortUtils.parsePort(jmxPortField.getText(), "JMX");
            if (!Objects.equals(configJmxPort, currentJmxPort)) {
                return true;
            }

            Integer configAjpPort = config.getConfigData().getPortConfig().isAjpEnabled()
                    ? config.getConfigData().getPortConfig().getAjp()
                    : null;
            Integer currentAjpPort = PortUtils.parsePort(ajpPortField.getText(), "AJP");
            if (!Objects.equals(configAjpPort, currentAjpPort)) {
                return true;
            }

            if (config.isHotDeploymentEnabled() != deployAppsCheckBox.isSelected()) {
                return true;
            }
            if (config.isPreserveSessions() != preserveSessionsCheckBox.isSelected()) {
                return true;
            }

            String configCatalinaBase = config.getConfigData().getCatalinaBase();
            String currentCatalinaBase = catalinaBaseField.getText().trim();
            if (!Objects.equals(configCatalinaBase != null ? configCatalinaBase : "", currentCatalinaBase)) {
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
            Integer httpP = PortUtils.parsePort(httpPortField.getText(), "HTTP");
            Integer httpsP = PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
            Integer jmxP = PortUtils.parsePort(jmxPortField.getText(), "JMX");
            Integer ajpP = PortUtils.parsePort(ajpPortField.getText(), "AJP");
            Integer shutdownP = parseShutdownPort();
            PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                    .httpPort(httpP)
                    .shutdownPort(shutdownP)
                    .httpsPort(httpsP)
                    .httpsEnabled(httpsP != null)
                    .jmxPort(jmxP)
                    .jmxEnabled(jmxP != null)
                    .ajpPort(ajpP)
                    .ajpEnabled(ajpP != null)
                    .build();

            ValidationResult result = PortValidator.validate(portConfig);

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
        return PortUtils.parsePort(httpPortField.getText(), "HTTP");
    }

    public Integer getShutdownPort() throws ConfigurationException {
        return PortUtils.parsePort(shutdownPortField.getText(), "Shutdown");
    }

    public boolean isHttpsEnabled() {
        try {
            return PortUtils.parsePort(httpsPortField.getText(), "HTTPS") != null;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    public Integer getHttpsPort() throws ConfigurationException {
        return PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
    }

    public boolean isJmxEnabled() {
        try {
            return PortUtils.parsePort(jmxPortField.getText(), "JMX") != null;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    public Integer getJmxPort() throws ConfigurationException {
        return PortUtils.parsePort(jmxPortField.getText(), "JMX");
    }

    public boolean isDeployAppsEnabled() {
        return deployAppsCheckBox.isSelected();
    }

    public boolean isPreserveSessionsEnabled() {
        return preserveSessionsCheckBox.isSelected();
    }

}
