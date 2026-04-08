package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatManagerDeployer;
import com.dev.idea.plugins.tomcat.ui.server.sections.ApplicationServerSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.BrowserLaunchSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.ConfigurationSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.JreConfigurationSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.TomcatSettingsSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.UpdateActionsSection;
import com.dev.idea.plugins.tomcat.ui.server.sections.VmOptionsSection;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import com.dev.idea.plugins.tomcat.TomcatConstants;

public class ServerConfigurationTab extends JBPanel<ServerConfigurationTab> {

    private final Project project;
    private TomcatRunConfiguration config;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private ApplicationServerSection applicationServerSection;
    private TomcatSettingsSection tomcatSettingsSection;
    private BrowserLaunchSection browserLaunchSection;
    private VmOptionsSection vmOptionsSection;
    private JreConfigurationSection jreConfigurationSection;
    private UpdateActionsSection updateActionsSection;
    private final List<ConfigurationSection> sharedSections = new ArrayList<>();
    private JPanel localContent;
    private JPanel remoteContent;

    private RemoteConnectionSection remoteConnectionSection;

    public ServerConfigurationTab(Project project, TomcatRunConfiguration config) {
        super(new BorderLayout());
        this.project = project;
        this.config = config;
        buildUI();
    }

    private void buildUI() {
        createSharedSections();

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        JPanel commonPanel = createCommonSections();
        mainPanel.add(commonPanel);

        localContent = createLocalContent();
        remoteContent = createRemoteContent();

        cards.add(localContent, TomcatConstants.MODE_LOCAL);
        cards.add(remoteContent, TomcatConstants.MODE_REMOTE);
        mainPanel.add(cards);

        JBScrollPane scrollPane = new JBScrollPane(mainPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        if (config != null) {
            resetFrom(config);
        }
    }

    private JPanel createCommonSections() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(2, 12, 0, 12));

        JPanel appServerPanel = applicationServerSection.createPanel();
        appServerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, appServerPanel.getPreferredSize().height));
        panel.add(appServerPanel);

        JPanel browserPanel = browserLaunchSection.createPanel();
        browserPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, browserPanel.getPreferredSize().height));
        panel.add(browserPanel);

        return panel;
    }

    private void createSharedSections() {
        applicationServerSection = new ApplicationServerSection(project);
        browserLaunchSection = new BrowserLaunchSection(project);
        vmOptionsSection = new VmOptionsSection();
        updateActionsSection = new UpdateActionsSection();
        jreConfigurationSection = new JreConfigurationSection(project);
        tomcatSettingsSection = new TomcatSettingsSection(project);

        // Wire HTTP port changes to auto-update the browser URL (only for auto-generated URLs)
        tomcatSettingsSection.setPortChangeListener(port -> {
            if (browserLaunchSection != null) {
                browserLaunchSection.updateUrlPort(port);
            }
        });

        sharedSections.clear();
        sharedSections.add(applicationServerSection);
        sharedSections.add(browserLaunchSection);
        sharedSections.add(vmOptionsSection);
        sharedSections.add(updateActionsSection);
        sharedSections.add(jreConfigurationSection);
        sharedSections.add(tomcatSettingsSection);
    }

    private JPanel createLocalContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(JBUI.Borders.empty(0, 12, 2, 12));

        addSection(content, vmOptionsSection);
        addSection(content, updateActionsSection);
        addSection(content, jreConfigurationSection);
        addSection(content, tomcatSettingsSection);

        return content;
    }

    private void addSection(JPanel container, ConfigurationSection section) {
        JPanel sectionPanel = section.createPanel();
        Dimension preferred = sectionPanel.getPreferredSize();
        sectionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        container.add(sectionPanel);
    }

    private JPanel createRemoteContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(JBUI.Borders.empty(4, 12, 4, 12));

        remoteConnectionSection = new RemoteConnectionSection();

        JPanel connectionPanel = remoteConnectionSection.getPanel();
        connectionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, connectionPanel.getPreferredSize().height));
        content.add(connectionPanel);

        return content;
    }

    public void resetFrom(TomcatRunConfiguration config) {
        this.config = config;

        String mode = config.getServerMode();
        if (TomcatConstants.MODE_REMOTE.equalsIgnoreCase(mode)) {
            cardLayout.show(cards, TomcatConstants.MODE_REMOTE);
            if (remoteConnectionSection != null) {
                remoteConnectionSection.resetFrom(config);
            }
        } else {
            cardLayout.show(cards, TomcatConstants.MODE_LOCAL);
        }

        for (ConfigurationSection section : sharedSections) {
            section.loadConfiguration();
            section.resetFrom(config);
        }
    }

    public void applyTo(TomcatRunConfiguration config) throws ConfigurationException {
        String mode = config.getServerMode();

        if (TomcatConstants.MODE_REMOTE.equalsIgnoreCase(mode)) {
            if (remoteConnectionSection != null) {
                remoteConnectionSection.applyTo(config);
            }
        }

        for (ConfigurationSection section : sharedSections) {
            section.applyTo(config);
        }
    }

    public boolean isModified(TomcatRunConfiguration config) {
        String mode = config.getServerMode();

        if (TomcatConstants.MODE_REMOTE.equalsIgnoreCase(mode)) {
            if (remoteConnectionSection != null && remoteConnectionSection.isModified(config)) {
                return true;
            }
        }

        for (ConfigurationSection section : sharedSections) {
            if (section.isModified(config)) {
                return true;
            }
        }
        return false;
    }

    public void validateSettings() throws ConfigurationException {
        if (config == null) {
            throw new ConfigurationException("Configuration is not initialized");
        }

        String mode = config.getServerMode();

        if (TomcatConstants.MODE_LOCAL.equals(mode)) {
            for (ConfigurationSection section : sharedSections) {
                List<ValidationInfo> errors = section.validateSettings();
                if (!errors.isEmpty()) {
                    throw new ConfigurationException(errors.get(0).message);
                }
            }

        } else { // Remote
            RemoteConfig rc = config.getConfigData().getRemoteConfig();
            if (rc == null) {
                throw new ConfigurationException("Remote configuration is not initialized.");
            }
            if (StringUtil.isEmpty(rc.getManagerUrl())) {
                throw new ConfigurationException("Manager URL is required for Remote mode.");
            }
            if (!rc.isValid()) {
                throw new ConfigurationException("Invalid Manager URL. Must be: http(s)://host:port/manager");
            }

            if (remoteConnectionSection != null) {
                List<ValidationInfo> remoteErrors = remoteConnectionSection.validateSettings();
                if (!remoteErrors.isEmpty()) {
                    throw new ConfigurationException(remoteErrors.get(0).message);
                }
            }

            for (ConfigurationSection section : sharedSections) {
                List<ValidationInfo> errors = section.validateSettings();
                if (!errors.isEmpty()) {
                    throw new ConfigurationException(errors.get(0).message);
                }
            }
        }
    }

    public void updateBrowserUrlContext(String contextPath) {
        if (browserLaunchSection != null) {
            browserLaunchSection.updateUrlContext(contextPath);
        }
    }

    public void dispose() {
        for (ConfigurationSection section : sharedSections) {
            section.dispose();
        }
        remoteConnectionSection = null;
    }

    private static class RemoteConnectionSection {
        private final JPanel panel;
        private final JBTextField hostField;
        private final JBTextField portField;
        private final JBCheckBox useHttpsCheck = new JBCheckBox("Use HTTPS");
        private final JBCheckBox useCredentialsCheck = new JBCheckBox("Use credentials");
        private final JBTextField usernameField;
        private final JPasswordField passwordField = new JPasswordField();
        private final JButton testButton = new JButton("Test Connection");
        private final JBLabel statusLabel = new JBLabel("");

        RemoteConnectionSection() {
            hostField = new JBTextField();
            hostField.setText(TomcatConstants.DEFAULT_HOST);
            hostField.setColumns(20);
            portField = new JBTextField();
            portField.setText(TomcatConstants.DEFAULT_PORT);
            portField.setColumns(10);
            usernameField = new JBTextField();
            usernameField.setText("admin");
            usernameField.setColumns(15);

            panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(4, 0, 4, 0));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(4, 0, 4, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int y = 0;
            gbc.gridx = 0; gbc.gridy = y++; gbc.gridwidth = 2;
            panel.add(new JBLabel("Remote Connection Settings"), gbc);
            gbc.gridwidth = 1;

            gbc.gridx = 0; gbc.gridy = y;
            panel.add(new JBLabel("Host:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0;
            panel.add(hostField, gbc);

            gbc.gridx = 0; gbc.gridy = ++y; gbc.weightx = 0;
            panel.add(new JBLabel("Port:"), gbc);
            gbc.gridx = 1;
            panel.add(portField, gbc);

            // HTTPS toggle
            gbc.gridx = 0; gbc.gridy = ++y; gbc.gridwidth = 2;
            panel.add(useHttpsCheck, gbc);
            gbc.gridwidth = 1;

            // Credentials
            gbc.gridx = 0; gbc.gridy = ++y; gbc.gridwidth = 2;
            panel.add(useCredentialsCheck, gbc);
            gbc.gridwidth = 1;

            gbc.gridx = 0; gbc.gridy = ++y;
            panel.add(new JBLabel("Username:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0;
            panel.add(usernameField, gbc);

            gbc.gridx = 0; gbc.gridy = ++y; gbc.weightx = 0;
            panel.add(new JBLabel("Password:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0;
            panel.add(passwordField, gbc);

            // Test Connection
            JPanel testPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            testPanel.add(testButton, BorderLayout.WEST);
            statusLabel.setToolTipText(null);
            testPanel.add(statusLabel, BorderLayout.CENTER);
            gbc.gridx = 0; gbc.gridy = ++y; gbc.gridwidth = 2; gbc.weightx = 1.0;
            panel.add(testPanel, gbc);

            useCredentialsCheck.addActionListener(e -> updateCredentialFieldsState());
            updateCredentialFieldsState();
            testButton.addActionListener(e -> testConnection());
        }

        private void updateCredentialFieldsState() {
            boolean enabled = useCredentialsCheck.isSelected();
            usernameField.setEnabled(enabled);
            passwordField.setEnabled(enabled);
        }

        private void testConnection() {
            statusLabel.setText("Testing...");
            statusLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
            testButton.setEnabled(false);

            RemoteConfig rc = buildCurrentRemoteConfig();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                TomcatManagerDeployer deployer = new TomcatManagerDeployer(rc);
                String error = deployer.testConnection();
                SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    if (error == null) {
                        setStatus("Connected successfully", null,
                                JBColor.namedColor(
                                        "DevTomcat.deployedForeground",
                                        new JBColor(0x008000, 0x6AAB73)));
                    } else {
                        setStatus(truncateStatus(error), error, JBColor.RED);
                    }
                });
            });
        }

        private String buildManagerUrl() {
            String protocol = useHttpsCheck.isSelected() ? "https" : "http";
            String host = hostField.getText().trim();
            String port = portField.getText().trim();
            return protocol + "://" + host + ":" + port + "/manager";
        }

        private RemoteConfig buildCurrentRemoteConfig() {
            String managerUrl = buildManagerUrl();
            return new RemoteConfig(
                    managerUrl,
                    usernameField.getText().trim(),
                    new String(passwordField.getPassword()),
                    useCredentialsCheck.isSelected()
            );
        }

        JPanel getPanel() { return panel; }

        void resetFrom(TomcatRunConfiguration config) {
            RemoteConfig rc = config.getConfigData().getRemoteConfig();
            if (rc != null) {
                String managerUrl = rc.getManagerUrl();
                if (managerUrl != null && !managerUrl.isEmpty()) {
                    try {
                        java.net.URL url = java.net.URI.create(managerUrl).toURL();
                        hostField.setText(url.getHost());
                        portField.setText(String.valueOf(url.getPort() > 0 ? url.getPort() : TomcatConstants.DEFAULT_PORT_NUMBER));
                        useHttpsCheck.setSelected("https".equalsIgnoreCase(url.getProtocol()));
                    } catch (Exception e) {
                        hostField.setText(TomcatConstants.DEFAULT_HOST);
                        portField.setText(TomcatConstants.DEFAULT_PORT);
                        useHttpsCheck.setSelected(false);
                    }
                }
                useCredentialsCheck.setSelected(rc.isUseCredentials());
                usernameField.setText(rc.getUsername());
                // Only populate the password field if PasswordSafe resolution has completed.
                // The deserializer loads passwords asynchronously from PasswordSafe on a pooled
                // thread (TomcatConfigurationSerializer, line 418). If we read rc.getPassword()
                // before that thread finishes, we'd get an empty string and the user would see
                // an empty field. On applyTo(), that empty string would overwrite the real
                // password in PasswordSafe. Instead, show the password only when it's resolved,
                // and leave the field empty with a placeholder hint otherwise.
                if (rc.isCredentialsResolved() || !rc.getPassword().isEmpty()) {
                    passwordField.setText(rc.getPassword());
                } else {
                    passwordField.setText("");
                    passwordField.setToolTipText("Password loading from secure storage...");
                }
                updateCredentialFieldsState();
            }
            statusLabel.setText("");
        }

        void applyTo(TomcatRunConfiguration config) {
            RemoteConfig rc = config.getConfigData().getRemoteConfig();
            if (rc != null) {
                rc.setManagerUrl(buildManagerUrl());
                rc.setUseCredentials(useCredentialsCheck.isSelected());
                rc.setUsername(usernameField.getText().trim());
                // Only overwrite the password if the user actually typed something, or if
                // PasswordSafe has resolved (meaning the field was populated from storage).
                // An empty field when credentials haven't resolved yet means PasswordSafe
                // is still loading — writing empty would destroy the stored password.
                String uiPassword = new String(passwordField.getPassword());
                if (!uiPassword.isEmpty() || rc.isCredentialsResolved()) {
                    rc.setPassword(uiPassword);
                }
            }
        }

        boolean isModified(TomcatRunConfiguration config) {
            RemoteConfig rc = config.getConfigData().getRemoteConfig();
            if (rc == null) return false;

            if (useCredentialsCheck.isSelected() != rc.isUseCredentials()) return true;
            if (!usernameField.getText().trim().equals(rc.getUsername())) return true;
            // Skip password comparison if PasswordSafe hasn't resolved yet — the UI field
            // is empty (loading), so it would falsely report "modified" against a stored password.
            if (rc.isCredentialsResolved()) {
                if (!new String(passwordField.getPassword()).equals(rc.getPassword())) return true;
            }

            String currentHost = hostField.getText().trim();
            String currentPort = portField.getText().trim();
            String managerUrl = rc.getManagerUrl();

            if (managerUrl == null || managerUrl.isEmpty()) {
                return !currentHost.equals(TomcatConstants.DEFAULT_HOST) || !currentPort.equals(TomcatConstants.DEFAULT_PORT);
            }

            try {
                java.net.URL url = java.net.URI.create(managerUrl).toURL();
                String savedHost = url.getHost();
                int savedPort = url.getPort() > 0 ? url.getPort() : TomcatConstants.DEFAULT_PORT_NUMBER;
                boolean savedHttps = "https".equalsIgnoreCase(url.getProtocol());
                if (useHttpsCheck.isSelected() != savedHttps) return true;
                return !currentHost.equals(savedHost) || !currentPort.equals(String.valueOf(savedPort));
            } catch (Exception e) {
                return true;
            }
        }

        List<ValidationInfo> validateSettings() {
            List<ValidationInfo> errors = new ArrayList<>();
            String host = hostField.getText().trim();
            String port = portField.getText().trim();

            if (host.isEmpty()) {
                errors.add(new ValidationInfo("Host is required", hostField));
            }

            if (port.isEmpty()) {
                errors.add(new ValidationInfo("Port is required", portField));
            } else {
                try {
                    int portNum = Integer.parseInt(port);
                    if (portNum < 1 || portNum > 65535) {
                        errors.add(new ValidationInfo("Port must be between 1 and 65535", portField));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ValidationInfo("Port must be a number", portField));
                }
            }

            if (useCredentialsCheck.isSelected() && usernameField.getText().trim().isEmpty()) {
                errors.add(new ValidationInfo("Username is required when credentials are enabled", usernameField));
            }

            return errors;
        }

        private void setStatus(@NotNull String text, @Nullable String tooltip, @NotNull java.awt.Color color) {
            statusLabel.setText(text);
            statusLabel.setToolTipText(tooltip);
            statusLabel.setForeground(color);
        }

        private static @NotNull String truncateStatus(@NotNull String msg) {
            int max = 100;
            if (msg.length() <= max) return msg;
            return msg.substring(0, max) + "...";
        }
    }
}
