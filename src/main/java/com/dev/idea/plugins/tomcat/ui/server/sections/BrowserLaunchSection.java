package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.WebBrowsersDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.TitledSeparator;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.dev.idea.plugins.tomcat.TomcatConstants;

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
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(2, 0, 2, 0));

        TitledSeparator separator = new TitledSeparator("Open browser");
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, separator.getPreferredSize().height));
        panel.add(separator);

        JPanel formPanel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(4, 0, 4, 6);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(afterLaunchCheckBox, gbc);

        gbc.gridx = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(browserComboBox, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(browserConfigButton, gbc);

        gbc.gridx = 3;
        formPanel.add(withJavaScriptDebuggerCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.insets = JBUI.insets(4, 0, 4, 4);
        formPanel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(urlField, gbc);

        formPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, formPanel.getPreferredSize().height));
        panel.add(formPanel);
    }

    @Override
    public void loadConfiguration() {
        if (!isInitialized || browserComboBox == null) {
            return;
        }

        try {
            browserComboBox.removeAllItems();
            browserComboBox.addItem(TomcatConstants.BROWSER_SYSTEM_DEFAULT);

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
                browserComboBox.addItem(TomcatConstants.BROWSER_SYSTEM_DEFAULT);
            }
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        if (!isInitialized) {
            return;
        }

        try {
            afterLaunchCheckBox.setSelected(configuration.isAfterLaunchEnabled());
            withJavaScriptDebuggerCheckBox.setSelected(configuration.isWithJsDebugger());

            // Use saved URL if available, otherwise generate from port/context
            String savedUrl = configuration.getBrowserUrl();
            if (savedUrl != null && !savedUrl.isEmpty()) {
                urlField.setText(savedUrl);
            } else {
                String contextPath = configuration.getContextPath();
                Integer port = configuration.getHttpPort();
                if (contextPath != null && port != null) {
                    urlField.setText("http://localhost:" + port + contextPath);
                } else if (port != null) {
                    urlField.setText("http://localhost:" + port + "/");
                } else {
                    urlField.setText("http://localhost:8080/");
                }
            }

            // Restore saved browser selection
            String savedBrowser = configuration.getBrowserName();
            if (savedBrowser != null && !savedBrowser.isEmpty()) {
                browserComboBox.setSelectedItem(savedBrowser);
            } else {
                browserComboBox.setSelectedItem(TomcatConstants.BROWSER_SYSTEM_DEFAULT);
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

                if (withJavaScriptDebuggerCheckBox != null) {
                    configuration.setWithJsDebugger(withJavaScriptDebuggerCheckBox.isSelected());
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
            if (!Objects.equals(afterLaunchCheckBox.isSelected(), config.isAfterLaunchEnabled())) {
                return true;
            }

            String configUrl = config.getBrowserUrl();
            String currentUrl = urlField != null ? urlField.getText().trim() : "";
            if (!Objects.equals(currentUrl, configUrl != null ? configUrl : "")) {
                return true;
            }

            String configBrowser = config.getBrowserName();
            String currentBrowser = getSelectedBrowser();
            if (!Objects.equals(currentBrowser, configBrowser != null ? configBrowser : TomcatConstants.BROWSER_SYSTEM_DEFAULT)) {
                return true;
            }

            if (withJavaScriptDebuggerCheckBox != null &&
                    withJavaScriptDebuggerCheckBox.isSelected() != config.isWithJsDebugger()) {
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
                if (defaultBrowser != null && !defaultBrowser.equals(TomcatConstants.BROWSER_SYSTEM_DEFAULT)) {
                    browserComboBox.setSelectedItem(defaultBrowser);
                } else {
                    browserComboBox.setSelectedItem(TomcatConstants.BROWSER_SYSTEM_DEFAULT);
                }
                LOG.debug("Browser configuration updated");
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
        return TomcatConstants.BROWSER_SYSTEM_DEFAULT;
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

    public void updateUrlContext(String contextPath) {
        if (!isInitialized || urlField == null || contextPath == null) return;

        String currentUrl = urlField.getText();
        if (currentUrl.startsWith("http://localhost:")) {
            int portStart = "http://localhost:".length();
            int pathStart = currentUrl.indexOf('/', portStart);
            String portPart = (pathStart != -1) ? currentUrl.substring(0, pathStart) : currentUrl;
            urlField.setText(portPart + contextPath);
        }
    }
}
