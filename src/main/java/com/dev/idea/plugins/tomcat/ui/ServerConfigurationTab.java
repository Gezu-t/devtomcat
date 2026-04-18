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
import com.intellij.ui.components.panels.VerticalLayout;
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

        JPanel mainPanel = new JPanel(new VerticalLayout(0));

        mainPanel.add(createCommonSections());

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
        JPanel panel = new JPanel(new VerticalLayout(0));
        panel.setBorder(JBUI.Borders.empty(2, 12, 0, 12));
        panel.add(applicationServerSection.createPanel());
        panel.add(browserLaunchSection.createPanel());
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
        JPanel content = new JPanel(new VerticalLayout(0));
        content.setBorder(JBUI.Borders.empty(0, 12, 2, 12));
        content.add(vmOptionsSection.createPanel());
        content.add(updateActionsSection.createPanel());
        content.add(jreConfigurationSection.createPanel());
        content.add(tomcatSettingsSection.createPanel());
        return content;
    }

    private JPanel createRemoteContent() {
        JPanel content = new JPanel(new VerticalLayout(0));
        content.setBorder(JBUI.Borders.empty(4, 12));
        remoteConnectionSection = new RemoteConnectionSection();
        content.add(remoteConnectionSection.getPanel());
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
        /**
         * Monotonic counter that tags each in-flight Test Connection so a stale async
         * response (user kept editing Host/Port while the test was running) can't
         * overwrite the status label with a result for a URL that no longer reflects
         * the UI state.
         */
        private final java.util.concurrent.atomic.AtomicInteger testGeneration =
                new java.util.concurrent.atomic.AtomicInteger(0);

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

            // Clear the Test Connection status whenever a field that feeds into the
            // manager URL changes, so a stale "Connected successfully" doesn't
            // mislead the user after they retarget the URL.
            javax.swing.event.DocumentListener urlFieldListener = new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { invalidateTestStatus(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { invalidateTestStatus(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { invalidateTestStatus(); }
            };
            hostField.getDocument().addDocumentListener(urlFieldListener);
            portField.getDocument().addDocumentListener(urlFieldListener);
            useHttpsCheck.addActionListener(e -> invalidateTestStatus());
        }

        /**
         * Marks any pending Test Connection result as stale (by bumping the
         * generation counter) and wipes the visible status. Called whenever a
         * URL-component field changes so the user never sees a green
         * "Connected" tick against a URL they've since edited.
         */
        private void invalidateTestStatus() {
            testGeneration.incrementAndGet();
            if (!statusLabel.getText().isEmpty()) {
                statusLabel.setText("");
                statusLabel.setToolTipText(null);
            }
        }

        private void updateCredentialFieldsState() {
            boolean enabled = useCredentialsCheck.isSelected();
            usernameField.setEnabled(enabled);
            passwordField.setEnabled(enabled);
        }

        private void testConnection() {
            statusLabel.setText("Testing...");
            statusLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
            statusLabel.setToolTipText(null);
            testButton.setEnabled(false);

            // Claim this test's generation. A later field edit bumps the counter,
            // so when this async call completes we can detect that the URL has
            // moved on and suppress our result rather than overwriting whatever
            // the user is now seeing.
            final int myGeneration = testGeneration.incrementAndGet();
            RemoteConfig rc = buildCurrentRemoteConfig();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                TomcatManagerDeployer deployer = new TomcatManagerDeployer(rc);
                String error = deployer.testConnection();
                SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    if (myGeneration != testGeneration.get()) {
                        // A field changed since this test started; the result no
                        // longer reflects the URL the user is looking at. Drop it.
                        return;
                    }
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

        /**
         * Builds the Tomcat Manager URL from the current field state.
         *
         * <p>Sanitises the inputs so IPv6 literals and typo'd hosts don't produce
         * malformed URLs:
         * <ul>
         *   <li>Trailing slashes and leading/trailing whitespace on the host are stripped.</li>
         *   <li>IPv6 literals that contain a {@code :} (and aren't already bracketed)
         *       are wrapped in {@code [...]} per RFC 3986 so the port separator
         *       isn't ambiguous.</li>
         *   <li>If the user accidentally pasted a scheme into the host field, it is
         *       stripped — the Use HTTPS checkbox is authoritative.</li>
         * </ul>
         */
        @NotNull
        String buildManagerUrl() {
            String protocol = useHttpsCheck.isSelected() ? "https" : "http";
            String rawHost = hostField.getText().trim();
            String port = portField.getText().trim();
            String host = normaliseHost(rawHost);
            return protocol + "://" + host + ":" + port + "/manager";
        }

        @NotNull
        static String normaliseHost(@NotNull String raw) {
            String host = raw.trim();
            // Strip accidental scheme prefix.
            int schemeEnd = host.indexOf("://");
            if (schemeEnd > 0) {
                host = host.substring(schemeEnd + 3);
            }
            // Strip trailing path / slash — the Manager endpoint is appended explicitly.
            int firstSlash = host.indexOf('/');
            if (firstSlash >= 0) {
                host = host.substring(0, firstSlash);
            }
            // Strip a user-supplied :port suffix so it can't double up with portField.
            // Detect by "more than one colon and not already bracketed" → IPv6;
            // otherwise "exactly one colon" → user typed host:port.
            if (!host.startsWith("[")) {
                int colons = 0;
                for (int i = 0; i < host.length(); i++) if (host.charAt(i) == ':') colons++;
                if (colons == 1) {
                    host = host.substring(0, host.indexOf(':'));
                } else if (colons > 1) {
                    // IPv6 literal — wrap so the outer port separator is unambiguous.
                    host = "[" + host + "]";
                }
            }
            return host;
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
                        java.net.URI uri = java.net.URI.create(managerUrl);
                        // URI.getHost() returns "::1" for http://[::1]:... — no brackets.
                        // That's exactly what we want in the field; buildManagerUrl()
                        // re-wraps it on apply so the round-trip is stable.
                        String uriHost = uri.getHost();
                        hostField.setText(uriHost != null ? uriHost : TomcatConstants.DEFAULT_HOST);
                        int uriPort = uri.getPort();
                        portField.setText(String.valueOf(uriPort > 0 ? uriPort : TomcatConstants.DEFAULT_PORT_NUMBER));
                        useHttpsCheck.setSelected("https".equalsIgnoreCase(uri.getScheme()));
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
                // Use URI.getHost() (returns "::1" for bracketed IPv6) so comparison
                // is symmetric with resetFrom(), which populates the UI from the same
                // method. Using URL.getHost() here returned "[::1]" and falsely
                // reported modified on every open for IPv6 configs.
                java.net.URI uri = java.net.URI.create(managerUrl);
                String savedHost = uri.getHost() != null ? uri.getHost() : "";
                int savedPort = uri.getPort() > 0 ? uri.getPort() : TomcatConstants.DEFAULT_PORT_NUMBER;
                boolean savedHttps = "https".equalsIgnoreCase(uri.getScheme());
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
