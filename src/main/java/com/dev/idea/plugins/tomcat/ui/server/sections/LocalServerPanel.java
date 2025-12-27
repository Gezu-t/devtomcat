package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData.PortConfig;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class LocalServerPanel extends JPanel implements ConfigurationSection {

    private final JBTextField tomcatHomeField = new JBTextField(40);
    private final JBIntSpinner httpPortField = new JBIntSpinner(8080, 1, 65535);
    private final JBIntSpinner shutdownPortField = new JBIntSpinner(8005, 1, 65535);

    private final JCheckBox httpsEnabled = new JCheckBox("Enable HTTPS");
    private final JBIntSpinner httpsPortField = new JBIntSpinner(8443, 1, 65535);

    private final JCheckBox jmxEnabled = new JCheckBox("Enable JMX");
    private final JBIntSpinner jmxPortField = new JBIntSpinner(1099, 1, 65535);

    public LocalServerPanel(@NotNull Project project) {
        setLayout(new GridBagLayout());
        initComponents();
        setupDependencies();
    }

    private void initComponents() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        // Tomcat Home
        addRow(gbc, y++, "Tomcat Home:", tomcatHomeField, true);

        // HTTP & Shutdown
        JPanel portPanel = new JPanel(new GridLayout(1, 4, 10, 0));
        portPanel.add(new JBLabel("HTTP Port:"));
        portPanel.add(httpPortField);
        portPanel.add(new JBLabel("Shutdown Port:"));
        portPanel.add(shutdownPortField);
        gbc.gridwidth = 3;
        add(portPanel, gbc);
        y++;

        // HTTPS
        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = y;
        add(httpsEnabled, gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        add(httpsPortField, gbc);
        y++;

        // JMX
        gbc.gridx = 0; gbc.gridy = y;
        add(jmxEnabled, gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        add(jmxPortField, gbc);
    }

    private void addRow(GridBagConstraints gbc, int y, String label, JComponent field, boolean fullWidth) {
        gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 1;
        add(new JBLabel(label), gbc);
        gbc.gridx = 1; gbc.gridwidth = fullWidth ? 2 : 1;
        add(field, gbc);
    }

    private void setupDependencies() {
        httpsEnabled.addActionListener(e -> httpsPortField.setEnabled(httpsEnabled.isSelected()));
        jmxEnabled.addActionListener(e -> jmxPortField.setEnabled(jmxEnabled.isSelected()));
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        PortConfig ports = config.getConfigData().getPortConfig();
        tomcatHomeField.setText(config.getTomcatInfo() != null ? config.getTomcatInfo().getPath() : "");
        httpPortField.setValue(config.getHttpPortSafe());
        shutdownPortField.setValue(config.getShutdownPortSafe());
        httpsEnabled.setSelected(ports.isHttpsEnabled());
        httpsPortField.setValue(ports.getHttpsPort() != null ? ports.getHttpsPort() : 8443);
        jmxEnabled.setSelected(ports.isJmxEnabled());
        jmxPortField.setValue(ports.getJmxPort() != null ? ports.getJmxPort() : 1099);

        updateEnabledState();
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration config) {
        PortConfig ports = config.getConfigData().getPortConfig();
        ports.setHttpPort(httpPortField.getValue());
        ports.setShutdownPort(shutdownPortField.getValue());
        ports.setHttpsEnabled(httpsEnabled.isSelected());
        ports.setHttpsPort(httpsEnabled.isSelected() ? httpsPortField.getValue() : null);
        ports.setJmxEnabled(jmxEnabled.isSelected());
        ports.setJmxPort(jmxEnabled.isSelected() ? jmxPortField.getValue() : null);
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        PortConfig ports = config.getConfigData().getPortConfig();
        return httpPortField.getValue() != config.getHttpPortSafe() ||
                shutdownPortField.getValue() != config.getShutdownPortSafe() ||
                httpsEnabled.isSelected() != ports.isHttpsEnabled() ||
                (httpsEnabled.isSelected() && httpsPortField.getValue() != (ports.getHttpsPort() != null ? ports.getHttpsPort() : 8443)) ||
                jmxEnabled.isSelected() != ports.isJmxEnabled() ||
                (jmxEnabled.isSelected() && jmxPortField.getValue() != (ports.getJmxPort() != null ? ports.getJmxPort() : 1099));
    }

    @Override
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();

        if (tomcatHomeField.getText().trim().isEmpty()) {
            errors.add(new ValidationInfo("Tomcat home directory is required", tomcatHomeField));
        }

        if (httpPortField.getValue() == shutdownPortField.getValue()) {
            errors.add(new ValidationInfo("HTTP and Shutdown ports must be different", httpPortField));
        }

        return errors;
    }

    private void updateEnabledState() {
        httpsPortField.setEnabled(httpsEnabled.isSelected());
        jmxPortField.setEnabled(jmxEnabled.isSelected());
    }

    @Override
    public JComponent createPanel() {
        return this;
    }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }
}