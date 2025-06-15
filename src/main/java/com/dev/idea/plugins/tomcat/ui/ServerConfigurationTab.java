package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.ui.dialogs.JREConfigurationDialog;
import com.dev.idea.plugins.tomcat.ui.dialogs.TomcatServerConfigurationDialog;
import com.dev.idea.plugins.tomcat.ui.dialogs.WebBrowsersDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ServerConfigurationTab extends JPanel {

    private final Project project;

    // Application Server Section
    private JComboBox<TomcatInfo> applicationServerComboBox;
    private JButton configureButton;

    // Open Browser Section
    private JCheckBox afterLaunchCheckBox;
    private JComboBox<String> browserComboBox;
    private JButton browserConfigButton;
    private JCheckBox withJavaScriptDebuggerCheckBox;
    private JTextField urlField;

    // VM Options Section
    private JTextArea vmOptionsArea;

    // Update Actions Section
    private JComboBox<String> updateActionComboBox;
    private JCheckBox showDialogCheckBox;

    // JRE Section
    private JComboBox<String> jreComboBox;
    private JButton jreConfigureButton;

    // Tomcat Server Settings Section
    private JTextField httpPortField;
    private JTextField httpsPortField;
    private JTextField jmxPortField;
    private JTextField ajpPortField;
    private JCheckBox deployApplicationsCheckBox;
    private JCheckBox preserveSessionsCheckBox;

    // Bottom Options
    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;
    private JCheckBox focusToolWindowCheckBox;

    public ServerConfigurationTab(@NotNull Project project) {
        this.project = project;
        initializeUI();
        loadAvailableTomcatServers();
        loadAvailableBrowsers();
        loadUpdateActions();
        loadAvailableJREs();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(5, 0, 5, 0);

        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        mainPanel.add(createApplicationServerSection(), gbc);

        gbc.gridy = 1;
        mainPanel.add(createOpenBrowserSection(), gbc);

        gbc.gridy = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.3;
        mainPanel.add(createVmOptionsSection(), gbc);

        gbc.gridy = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0.0;
        mainPanel.add(createUpdateActionsSection(), gbc);

        gbc.gridy = 4;
        mainPanel.add(createJreSection(), gbc);

        gbc.gridy = 5;
        mainPanel.add(createTomcatServerSettingsSection(), gbc);

        gbc.gridy = 6;
        mainPanel.add(createBottomOptionsSection(), gbc);

        gbc.gridy = 7; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        mainPanel.add(Box.createVerticalGlue(), gbc);

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel createApplicationServerSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5, 0, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Application server:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5, 10, 5, 10);
        applicationServerComboBox = new JComboBox<>();
        applicationServerComboBox.setRenderer(new TomcatInfoRenderer());
        applicationServerComboBox.setPreferredSize(new Dimension(250, 25));
        panel.add(applicationServerComboBox, gbc);

        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0, 5, 0);
        configureButton = new JButton("Configure...");
        configureButton.addActionListener(e -> openTomcatServerConfiguration());
        panel.add(configureButton, gbc);

        return panel;
    }

    private JPanel createOpenBrowserSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Open browser"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(8, 8, 8, 8);

        gbc.gridx = 0; gbc.gridy = 0;
        afterLaunchCheckBox = new JCheckBox("After launch");
        panel.add(afterLaunchCheckBox, gbc);

        gbc.gridx = 1;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        browserComboBox = new JComboBox<>();
        browserComboBox.setPreferredSize(new Dimension(120, 25));
        panel.add(browserComboBox, gbc);

        gbc.gridx = 2;
        gbc.insets = JBUI.insets(8, 5, 8, 8);
        browserConfigButton = new JButton("...");
        browserConfigButton.setPreferredSize(new Dimension(30, 25));
        browserConfigButton.addActionListener(e -> configureBrowsers());
        panel.add(browserConfigButton, gbc);

        gbc.gridx = 3;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        withJavaScriptDebuggerCheckBox = new JCheckBox("with JavaScript debugger");
        panel.add(withJavaScriptDebuggerCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.insets = JBUI.insets(8, 8, 8, 8);
        panel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        urlField = new JTextField();
        panel.add(urlField, gbc);

        return panel;
    }

    private JPanel createVmOptionsSection() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("VM options"));

        vmOptionsArea = new JTextArea(3, 50);
        vmOptionsArea.setLineWrap(true);
        vmOptionsArea.setWrapStyleWord(true);
        vmOptionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane vmScrollPane = new JScrollPane(vmOptionsArea);
        vmScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        vmScrollPane.setPreferredSize(new Dimension(0, 70));
        vmScrollPane.setBorder(JBUI.Borders.empty(5));
        panel.add(vmScrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createUpdateActionsSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5, 0, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("On 'Update' action:"), gbc);

        gbc.gridx = 1;
        gbc.insets = JBUI.insets(5, 15, 5, 15);
        updateActionComboBox = new JComboBox<>();
        updateActionComboBox.setPreferredSize(new Dimension(200, 25));
        panel.add(updateActionComboBox, gbc);

        gbc.gridx = 2;
        gbc.insets = JBUI.insets(5, 0, 5, 0);
        showDialogCheckBox = new JCheckBox("Show dialog");
        panel.add(showDialogCheckBox, gbc);

        return panel;
    }

    private JPanel createJreSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5, 0, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("JRE:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5, 15, 5, 10);
        jreComboBox = new JComboBox<>();
        jreComboBox.setPreferredSize(new Dimension(200, 25));
        panel.add(jreComboBox, gbc);

        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(5, 0, 5, 0);
        jreConfigureButton = new JButton("...");
        jreConfigureButton.setPreferredSize(new Dimension(30, 25));
        jreConfigureButton.addActionListener(e -> configureJRE());
        panel.add(jreConfigureButton, gbc);

        return panel;
    }

    private JPanel createTomcatServerSettingsSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Tomcat Server Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(8, 8, 3, 20);

        // HTTP port
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("HTTP port:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(8, 5, 3, 30);
        httpPortField = new JTextField(8);
        panel.add(httpPortField, gbc);

        // HTTPS port
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(3, 8, 3, 20);
        panel.add(new JLabel("HTTPS port:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(3, 5, 3, 30);
        httpsPortField = new JTextField(8);
        panel.add(httpsPortField, gbc);

        // JMX port
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(3, 8, 3, 20);
        panel.add(new JLabel("JMX port:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(3, 5, 3, 30);
        jmxPortField = new JTextField(8);
        panel.add(jmxPortField, gbc);

        // AJP port
        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(3, 8, 8, 20);
        panel.add(new JLabel("AJP port:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(3, 5, 8, 30);
        ajpPortField = new JTextField(8);
        panel.add(ajpPortField, gbc);

        // Right column - Checkboxes
        gbc.gridx = 2; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(8, 0, 3, 8);
        deployApplicationsCheckBox = new JCheckBox("Deploy applications configured in Tomcat instance");
        panel.add(deployApplicationsCheckBox, gbc);

        gbc.gridy = 1;
        gbc.insets = JBUI.insets(3, 0, 8, 8);
        preserveSessionsCheckBox = new JCheckBox("Preserve sessions across restarts and redeploys");
        panel.add(preserveSessionsCheckBox, gbc);

        return panel;
    }

    private JPanel createBottomOptionsSection() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));

        showThisPageCheckBox = new JCheckBox("Show this page");
        activateToolWindowCheckBox = new JCheckBox("Activate tool window");
        focusToolWindowCheckBox = new JCheckBox("Focus tool window");

        panel.add(showThisPageCheckBox);
        panel.add(Box.createHorizontalStrut(25));
        panel.add(activateToolWindowCheckBox);
        panel.add(Box.createHorizontalStrut(25));
        panel.add(focusToolWindowCheckBox);

        return panel;
    }

    private void loadAvailableTomcatServers() {
        applicationServerComboBox.removeAllItems();

        TomcatServerManagerState serverManager = TomcatServerManagerState.getInstance();
        List<TomcatInfo> tomcatServers = serverManager.getTomcatInfos();

        for (TomcatInfo tomcatInfo : tomcatServers) {
            applicationServerComboBox.addItem(tomcatInfo);
        }
    }

    private void loadAvailableBrowsers() {
        browserComboBox.removeAllItems();

        // Add system default
        browserComboBox.addItem("System Default");

        // Add browsers from WebBrowsersDialog configuration
        List<WebBrowsersDialog.BrowserInfo> browsers = WebBrowsersDialog.getBrowserConfigurations();
        for (WebBrowsersDialog.BrowserInfo browser : browsers) {
            if (browser.isActive()) {
                browserComboBox.addItem(browser.getName());
            }
        }
    }

    private void loadUpdateActions() {
        updateActionComboBox.removeAllItems();
        updateActionComboBox.addItem("Restart server");
        updateActionComboBox.addItem("Redeploy");
        updateActionComboBox.addItem("Update classes and resources");
    }

    private void loadAvailableJREs() {
        jreComboBox.removeAllItems();
        jreComboBox.addItem("Project SDK");
        // Additional JREs will be added when JRE configuration is implemented
    }

    private void openTomcatServerConfiguration() {
        try {
            TomcatServerConfigurationDialog dialog = new TomcatServerConfigurationDialog(project);
            if (dialog.showAndGet()) {
                // Refresh the dropdown after configuration
                loadAvailableTomcatServers();
                System.out.println("DevTomcat: Tomcat server configuration updated");
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening server configuration: " + e.getMessage());
            Messages.showErrorDialog(project, "Failed to open server configuration: " + e.getMessage(), "Error");
        }
    }

    private void configureBrowsers() {
        try {
            WebBrowsersDialog dialog = new WebBrowsersDialog(project);
            if (dialog.showAndGet()) {
                // Refresh browsers after configuration
                loadAvailableBrowsers();

                // Set the selected default browser
                String defaultBrowser = dialog.getDefaultBrowser();
                if (defaultBrowser != null && !defaultBrowser.equals("System default")) {
                    browserComboBox.setSelectedItem(defaultBrowser);
                } else {
                    browserComboBox.setSelectedItem("System Default");
                }

                System.out.println("DevTomcat: Browser configuration updated - Default: " + defaultBrowser);
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening browser configuration: " + e.getMessage());
            Messages.showErrorDialog(project, "Failed to open browser configuration: " + e.getMessage(), "Error");
        }
    }

    private void configureJRE() {
        try {
            JREConfigurationDialog dialog = new JREConfigurationDialog(project);
            if (dialog.showAndGet()) {
                JREConfigurationDialog.JdkInfo selectedJdk = dialog.getSelectedJdk();
                if (selectedJdk != null) {
                    // Refresh JREs and update selection
                    loadAvailableJREs();

                    // Add the selected JDK to combo box if not already there
                    if (!selectedJdk.isProjectSdk()) {
                        jreComboBox.addItem(selectedJdk.toString());
                        jreComboBox.setSelectedItem(selectedJdk.toString());
                    } else {
                        jreComboBox.setSelectedItem("Project SDK");
                    }

                    System.out.println("DevTomcat: JRE configuration updated to: " + selectedJdk.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening JRE configuration: " + e.getMessage());
            Messages.showErrorDialog(project, "Failed to open JRE configuration: " + e.getMessage(), "Error");
        }
    }

    private void updateUrlFromConfiguration() {
        String contextPath = "";
        String port = httpPortField.getText().trim();

        if (!port.isEmpty() && !contextPath.isEmpty()) {
            urlField.setText("http://localhost:" + port + contextPath);
        } else if (!port.isEmpty()) {
            urlField.setText("http://localhost:" + port + "/");
        } else {
            urlField.setText("");
        }
    }

    private void validatePortField(JTextField portField, String portName) throws ConfigurationException {
        String portText = portField.getText().trim();
        if (!portText.isEmpty()) {
            try {
                int port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    throw new ConfigurationException(portName + " must be between 1 and 65535");
                }
            } catch (NumberFormatException e) {
                throw new ConfigurationException("Invalid " + portName.toLowerCase() + " number");
            }
        }
    }

    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Load Tomcat server
        TomcatInfo configTomcatInfo = configuration.getTomcatInfo();
        if (configTomcatInfo != null) {
            applicationServerComboBox.setSelectedItem(configTomcatInfo);
        }

        // Open browser settings
        afterLaunchCheckBox.setSelected(false);
        withJavaScriptDebuggerCheckBox.setSelected(false);

        // URL field
        String contextPath = configuration.getContextPath();
        Integer port = configuration.getPort();
        if (contextPath != null && port != null) {
            urlField.setText("http://localhost:" + port + contextPath);
        } else if (port != null) {
            urlField.setText("http://localhost:" + port + "/");
        } else {
            urlField.setText("");
        }

        // VM options
        String vmOptions = configuration.getVmOptions();
        vmOptionsArea.setText(vmOptions != null ? vmOptions : "");

        // Update actions
        if (configuration.isUpdateClassesAndResources()) {
            updateActionComboBox.setSelectedItem("Update classes and resources");
        } else {
            updateActionComboBox.setSelectedIndex(0); // Default to first item
        }
        showDialogCheckBox.setSelected(false);

        // JRE selection
        jreComboBox.setSelectedItem("Project SDK");

        // Tomcat Server Settings
        if (port != null) {
            httpPortField.setText(String.valueOf(port));
        } else {
            httpPortField.setText("");
        }

        httpsPortField.setText("");

        // JMX port
        if (configuration.isJmxEnabled()) {
            jmxPortField.setText(String.valueOf(configuration.getJmxPort()));
        } else {
            jmxPortField.setText("");
        }

        ajpPortField.setText("");
        deployApplicationsCheckBox.setSelected(false);
        preserveSessionsCheckBox.setSelected(false);

        // Bottom options
        showThisPageCheckBox.setSelected(false);
        activateToolWindowCheckBox.setSelected(false);
        focusToolWindowCheckBox.setSelected(false);
    }

    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // Apply Tomcat server
        TomcatInfo selectedTomcat = (TomcatInfo) applicationServerComboBox.getSelectedItem();
        if (selectedTomcat != null) {
            configuration.setTomcatInfo(selectedTomcat);
        }

        // Validate and apply HTTP port
        validatePortField(httpPortField, "HTTP port");
        String httpPortText = httpPortField.getText().trim();
        if (!httpPortText.isEmpty()) {
            configuration.setPort(Integer.parseInt(httpPortText));
        }

        // Validate and apply HTTPS port
        validatePortField(httpsPortField, "HTTPS port");
        String httpsPortText = httpsPortField.getText().trim();
        if (!httpsPortText.isEmpty()) {
            configuration.setSslPort(Integer.parseInt(httpsPortText));
        }

        // Apply VM options
        String vmOptions = vmOptionsArea.getText().trim();
        configuration.setVmOptions(vmOptions.isEmpty() ? null : vmOptions);

        // Validate and apply JMX configuration
        validatePortField(jmxPortField, "JMX port");
        String jmxPortText = jmxPortField.getText().trim();
        if (!jmxPortText.isEmpty()) {
            configuration.setJmxPort(Integer.parseInt(jmxPortText));
            configuration.setJmxEnabled(true);
        } else {
            configuration.setJmxEnabled(false);
        }

        // Validate and apply AJP port
        validatePortField(ajpPortField, "AJP port");

        // Apply update actions
        String selectedAction = (String) updateActionComboBox.getSelectedItem();
        if ("Update classes and resources".equals(selectedAction)) {
            configuration.setUpdateClassesAndResources(true);
            configuration.setHotDeploymentEnabled(true);
        } else {
            configuration.setUpdateClassesAndResources(false);
            configuration.setHotDeploymentEnabled(false);
        }
    }

    private static class TomcatInfoRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof TomcatInfo) {
                TomcatInfo tomcatInfo = (TomcatInfo) value;
                setText(tomcatInfo.getName());
            }

            return this;
        }
    }

    public boolean isConfigurationValid() {
        try {
            // Validate Tomcat server selection
            TomcatInfo selectedTomcat = (TomcatInfo) applicationServerComboBox.getSelectedItem();
            if (selectedTomcat == null) {
                return false;
            }

            // Validate all port fields
            validatePortField(httpPortField, "HTTP port");
            validatePortField(httpsPortField, "HTTPS port");
            validatePortField(jmxPortField, "JMX port");
            validatePortField(ajpPortField, "AJP port");

            return true;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    public TomcatInfo getSelectedTomcatServer() {
        return (TomcatInfo) applicationServerComboBox.getSelectedItem();
    }

    public String getVmOptions() {
        return vmOptionsArea.getText().trim();
    }

    public int getHttpPort() {
        String portText = httpPortField.getText().trim();
        if (portText.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int getJmxPort() {
        String portText = jmxPortField.getText().trim();
        if (portText.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isJmxEnabled() {
        return !jmxPortField.getText().trim().isEmpty();
    }

    public String getSelectedUpdateAction() {
        return (String) updateActionComboBox.getSelectedItem();
    }

    public String getSelectedJRE() {
        return (String) jreComboBox.getSelectedItem();
    }

    public String getUrl() {
        return urlField.getText().trim();
    }

    public boolean isAfterLaunchEnabled() {
        return afterLaunchCheckBox.isSelected();
    }

    public boolean isJavaScriptDebuggerEnabled() {
        return withJavaScriptDebuggerCheckBox.isSelected();
    }
}