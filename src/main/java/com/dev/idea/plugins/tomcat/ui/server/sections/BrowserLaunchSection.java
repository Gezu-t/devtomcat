package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.WebBrowsersDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Browser Launch Section
 * Handles browser launch configuration
 */
public class BrowserLaunchSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(BrowserLaunchSection.class);

    private final Project project;
    private JCheckBox afterLaunchCheckBox;
    private JComboBox<String> browserComboBox;
    private JButton browserConfigButton;
    private JCheckBox withJavaScriptDebuggerCheckBox;
    private JTextField urlField;
    private JPanel panel;

    private boolean isInitialized = false;

    public BrowserLaunchSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            initializeComponents();
            createLayout();
            isInitialized = true;
            loadConfiguration();
        }
        return panel;
    }

    private void initializeComponents() {
        afterLaunchCheckBox = new JCheckBox("After launch");
        browserComboBox = new JComboBox<>();
        browserConfigButton = new JButton("...");
        withJavaScriptDebuggerCheckBox = new JCheckBox("with JavaScript debugger");
        urlField = new JTextField();

        browserComboBox.setPreferredSize(new Dimension(120, 25));
        browserConfigButton.setPreferredSize(new Dimension(30, 25));

        afterLaunchCheckBox.addActionListener(e -> updateBrowserControls());
        browserConfigButton.addActionListener(e -> configureBrowsers());
    }

    private void createLayout() {
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1),
                JBUI.Borders.empty(4, 4, 4, 4)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(4, 0, 4, 6);

        // Heading
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4;
        panel.add(new JBLabel("Open browser"), gbc);
        gbc.gridwidth = 1;

        // Row 0: After launch, browser, configure, JS debugger
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(afterLaunchCheckBox, gbc);

        gbc.gridx = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(browserComboBox, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(browserConfigButton, gbc);

        gbc.gridx = 3;
        panel.add(withJavaScriptDebuggerCheckBox, gbc);

        // Row 1: URL
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.insets = JBUI.insets(4, 0, 4, 4);
        panel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(urlField, gbc);
    }

    @Override
    public void loadConfiguration() {
        if (!isInitialized || browserComboBox == null) {
            return;
        }

        try {
            browserComboBox.removeAllItems();
            browserComboBox.addItem("System Default");

            List<WebBrowsersDialog.BrowserInfo> browsers = WebBrowsersDialog.getBrowserConfigurations();
            for (WebBrowsersDialog.BrowserInfo browser : browsers) {
                if (browser.isActive()) {
                    browserComboBox.addItem(browser.getName());
                }
            }

            updateBrowserControls();

        } catch (Exception e) {
            LOG.error("Error loading browsers", e);
            if (browserComboBox.getItemCount() == 0) {
                browserComboBox.addItem("System Default");
            }
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        if (!isInitialized) {
            return;
        }

        try {
            afterLaunchCheckBox.setSelected(true);
            withJavaScriptDebuggerCheckBox.setSelected(false);

            String contextPath = configuration.getContextPath();
            Integer port = configuration.getHttpPort();

            if (contextPath != null && port != null) {
                urlField.setText("http://localhost:" + port + contextPath);
            } else if (port != null) {
                urlField.setText("http://localhost:" + port + "/");
            } else {
                urlField.setText("http://localhost:8080/");
            }

            updateBrowserControls();

        } catch (Exception e) {
            LOG.error("Error resetting browser configuration", e);
        }
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            if (isInitialized && afterLaunchCheckBox != null) {
                configuration.setAfterLaunchEnabled(afterLaunchCheckBox.isSelected());

                if (urlField != null) {
                    configuration.setBrowserUrl(urlField.getText().trim());
                }

                if (browserComboBox != null && browserComboBox.getSelectedItem() != null) {
                    configuration.setBrowserName(browserComboBox.getSelectedItem().toString());
                }
            }
        } catch (Exception e) {
            throw new ConfigurationException("Failed to apply browser configuration: " + e.getMessage());
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        if (!isInitialized) {
            return false;
        }

        try {
            // Check if "After launch" checkbox state changed
            if (!Objects.equals(afterLaunchCheckBox.isSelected(), config.isAfterLaunchEnabled())) {
                return true;
            }

            // Check if URL changed
            String configUrl = config.getBrowserUrl();
            String currentUrl = urlField != null ? urlField.getText().trim() : "";
            if (!Objects.equals(currentUrl, configUrl != null ? configUrl : "")) {
                return true;
            }

            // Check if browser name changed
            String configBrowser = config.getBrowserName();
            String currentBrowser = getSelectedBrowser();
            if (!Objects.equals(currentBrowser, configBrowser != null ? configBrowser : "System Default")) {
                return true;
            }

        } catch (Exception e) {
            LOG.warn("Error checking browser configuration modifications", e);
        }

        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        // Browser launch section has no validation requirements
        // URL and browser selection are optional
        return Collections.emptyList();
    }

    private void updateBrowserControls() {
        if (!isInitialized) {
            return;
        }

        try {
            boolean enabled = afterLaunchCheckBox.isSelected();

            browserComboBox.setEnabled(enabled);
            browserConfigButton.setEnabled(enabled);
            withJavaScriptDebuggerCheckBox.setEnabled(enabled);
            urlField.setEnabled(enabled);
        } catch (Exception e) {
            LOG.error("Error updating browser controls", e);
        }
    }

    private void configureBrowsers() {
        try {
            WebBrowsersDialog dialog = new WebBrowsersDialog(project);
            if (dialog.showAndGet()) {
                loadConfiguration();
                String defaultBrowser = dialog.getDefaultBrowser();
                if (defaultBrowser != null && !defaultBrowser.equals("System default")) {
                    browserComboBox.setSelectedItem(defaultBrowser);
                } else {
                    browserComboBox.setSelectedItem("System Default");
                }
                LOG.info("Browser configuration updated");
            }
        } catch (Exception e) {
            LOG.error("Error opening browser configuration", e);
            Messages.showErrorDialog(project,
                    "Failed to open browser configuration: " + e.getMessage(),
                    "Browser Configuration Error");
        }
    }

    public boolean isAfterLaunchEnabled() {
        return isInitialized && afterLaunchCheckBox != null && afterLaunchCheckBox.isSelected();
    }

    public String getUrl() {
        return isInitialized && urlField != null ? urlField.getText().trim() : "";
    }

    public String getSelectedBrowser() {
        if (isInitialized && browserComboBox != null && browserComboBox.getSelectedItem() != null) {
            return browserComboBox.getSelectedItem().toString();
        }
        return "System Default";
    }

    public boolean isJavaScriptDebuggerEnabled() {
        return isInitialized && withJavaScriptDebuggerCheckBox != null &&
                withJavaScriptDebuggerCheckBox.isSelected();
    }

    public void updateUrlPort(Integer newPort) {
        if (isInitialized && urlField != null && newPort != null) {
            String currentUrl = urlField.getText();
            if (currentUrl.startsWith("http://localhost:")) {
                int portStart = "http://localhost:".length();
                int pathStart = currentUrl.indexOf('/', portStart);
                String path = (pathStart != -1) ? currentUrl.substring(pathStart) : "/";

                String newUrl = "http://localhost:" + newPort + path;
                urlField.setText(newUrl);
            }
        }
    }
}
