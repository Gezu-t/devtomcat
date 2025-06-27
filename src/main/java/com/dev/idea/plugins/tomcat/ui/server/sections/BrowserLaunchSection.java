package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.WebBrowsersDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Fixed Browser Launch Section - Ultimate-Aligned
 * Handles browser launch configuration with proper null safety
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class BrowserLaunchSection implements ConfigurationSection {

    private final Project project;
    private JCheckBox afterLaunchCheckBox;
    private JComboBox<String> browserComboBox;
    private JButton browserConfigButton;
    private JCheckBox withJavaScriptDebuggerCheckBox;
    private JTextField urlField;
    private JPanel panel;

    // Flag to track initialization
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
            // Load configuration AFTER components are created
            loadConfiguration();
        }
        return panel;
    }

    private void initializeComponents() {
        // Initialize all components first
        afterLaunchCheckBox = new JCheckBox("After launch");
        browserComboBox = new JComboBox<>();
        browserConfigButton = new JButton("...");
        withJavaScriptDebuggerCheckBox = new JCheckBox("with JavaScript debugger");
        urlField = new JTextField();

        // Set sizes
        browserComboBox.setPreferredSize(new Dimension(120, 25));
        browserConfigButton.setPreferredSize(new Dimension(30, 25));

        // Add listeners
        afterLaunchCheckBox.addActionListener(e -> updateBrowserControls());
        browserConfigButton.addActionListener(e -> configureBrowsers());
    }

    private void createLayout() {
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Open browser"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(8, 8, 8, 8);

        // First row: After launch checkbox, browser combo, config button, JavaScript debugger
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(afterLaunchCheckBox, gbc);

        gbc.gridx = 1;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        panel.add(browserComboBox, gbc);

        gbc.gridx = 2;
        gbc.insets = JBUI.insets(8, 5, 8, 8);
        panel.add(browserConfigButton, gbc);

        gbc.gridx = 3;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        panel.add(withJavaScriptDebuggerCheckBox, gbc);

        // Second row: URL label and field
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.insets = JBUI.insets(8, 8, 8, 8);
        panel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = JBUI.insets(8, 15, 8, 8);
        panel.add(urlField, gbc);
    }

    @Override
    public void loadConfiguration() {
        // CRITICAL: Only load if components are initialized
        if (!isInitialized || browserComboBox == null) {
            return;
        }

        try {
            // Safe removal of items
            browserComboBox.removeAllItems();
            browserComboBox.addItem("System Default");

            // Load browser configurations safely
            List<WebBrowsersDialog.BrowserInfo> browsers = WebBrowsersDialog.getBrowserConfigurations();
            for (WebBrowsersDialog.BrowserInfo browser : browsers) {
                if (browser.isActive()) {
                    browserComboBox.addItem(browser.getName());
                }
            }

            updateBrowserControls();

        } catch (Exception e) {
            System.err.println("DevTomcat: Error loading browsers: " + e.getMessage());
            // Add fallback if browser loading fails
            if (browserComboBox.getItemCount() == 0) {
                browserComboBox.addItem("System Default");
            }
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Ensure components are initialized
        if (!isInitialized) {
            return;
        }

        try {
            afterLaunchCheckBox.setSelected(false);
            withJavaScriptDebuggerCheckBox.setSelected(false);

            // Set URL if available
            String contextPath = configuration.getContextPath();
            Integer port = configuration.getPort();

            if (contextPath != null && port != null) {
                urlField.setText("http://localhost:" + port + contextPath);
            } else if (port != null) {
                urlField.setText("http://localhost:" + port + "/");
            } else {
                urlField.setText("http://localhost:8080/");
            }

            updateBrowserControls();

        } catch (Exception e) {
            System.err.println("DevTomcat: Error resetting browser configuration: " + e.getMessage());
        }
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            // Store browser launch settings in configuration
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
        return true; // Browser configuration is always valid
    }

    private void updateBrowserControls() {
        if (!isInitialized) {
            return;
        }

        try {
            boolean enabled = afterLaunchCheckBox.isSelected();

            if (browserComboBox != null) {
                browserComboBox.setEnabled(enabled);
            }
            if (browserConfigButton != null) {
                browserConfigButton.setEnabled(enabled);
            }
            if (withJavaScriptDebuggerCheckBox != null) {
                withJavaScriptDebuggerCheckBox.setEnabled(enabled);
            }
            if (urlField != null) {
                urlField.setEnabled(enabled);
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error updating browser controls: " + e.getMessage());
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
                System.out.println("DevTomcat: Browser configuration updated");
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening browser configuration: " + e.getMessage());
            Messages.showErrorDialog(project,
                    "Failed to open browser configuration: " + e.getMessage(),
                    "Browser Configuration Error");
        }
    }

    // === PUBLIC GETTERS ===

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

    // === PORT UPDATE METHOD ===

    public void updateUrlPort(Integer newPort) {
        if (isInitialized && urlField != null && newPort != null) {
            String currentUrl = urlField.getText();
            if (currentUrl.startsWith("http://localhost:")) {
                // Extract path part
                int portStart = "http://localhost:".length();
                int pathStart = currentUrl.indexOf('/', portStart);
                String path = (pathStart != -1) ? currentUrl.substring(pathStart) : "/";

                String newUrl = "http://localhost:" + newPort + path;
                urlField.setText(newUrl);
            }
        }
    }
}