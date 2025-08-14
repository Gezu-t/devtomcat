package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Tomcat Server Information Component
 *
 * UI component that displays detailed information about a Tomcat server
 * configuration. Shows version, path, and validation status with a
 * professional layout.
 *
 * @author Dev Tomcat Team
 */
public class TomcatInfoComponent implements Disposable {

    private static final Logger LOG = Logger.getInstance(TomcatInfoComponent.class);

    private JPanel mainPanel;
    private final TomcatInfo tomcatInfo;

    // UI Components
    private JBLabel idLabel;
    private JBLabel versionLabel;
    private JBTextField locationField;
    private JBLabel statusLabel;
    private JBLabel validationLabel;

    /**
     * Create a new component for displaying Tomcat server information
     *
     * @param tomcatInfo The Tomcat server information to display
     */
    public TomcatInfoComponent(@NotNull TomcatInfo tomcatInfo) {
        this.tomcatInfo = tomcatInfo;
        initializeComponents();
        createUI();
        updateValidationStatus();
    }

    /**
     * Initialize UI components
     */
    private void initializeComponents() {
        // ID Label
        idLabel = new JBLabel(tomcatInfo.getId());
        idLabel.setForeground(UIUtil.getLabelDisabledForeground());
        idLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

        // Version Label
        versionLabel = new JBLabel(tomcatInfo.getVersion());
        versionLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));

        // Location Field (read-only)
        locationField = new JBTextField(tomcatInfo.getPath());
        locationField.setEditable(false);
        locationField.setBackground(UIUtil.getPanelBackground());

        // Status Label
        statusLabel = new JBLabel();
        updateStatusLabel();

        // Validation Label
        validationLabel = new JBLabel();
    }

    /**
     * Create the UI layout
     */
    private void createUI() {
        FormBuilder formBuilder = FormBuilder.createFormBuilder()
                .setVerticalGap(UIUtil.DEFAULT_VGAP)
                .addLabeledComponent("Server ID:", idLabel)
                .addLabeledComponent("Version:", versionLabel)
                .addLabeledComponent("Location:", locationField)
                .addLabeledComponent("Status:", statusLabel)
                .addSeparator(UIUtil.DEFAULT_VGAP * 2)
                .addLabeledComponent("Validation:", validationLabel);

        // Add server details section
        JPanel detailsPanel = createDetailsPanel();
        if (detailsPanel != null) {
            formBuilder.addComponent(detailsPanel);
        }

        // Add empty space at bottom
        formBuilder.addComponentFillVertically(new JPanel(), 0);

        mainPanel = formBuilder.getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(10));
    }

    /**
     * Create additional details panel
     */
    private JPanel createDetailsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2);

        // Add additional server information
        int row = 0;

        // Major version
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JBLabel("Major Version:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(new JBLabel(String.valueOf(tomcatInfo.getMajorVersion())), gbc);

        row++;

        // CATALINA_HOME
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JBLabel("CATALINA_HOME:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JBTextField homeField = new JBTextField(tomcatInfo.getCatalinaHome());
        homeField.setEditable(false);
        homeField.setBackground(UIUtil.getPanelBackground());
        panel.add(homeField, gbc);

        panel.setBorder(JBUI.Borders.emptyTop(10));
        return panel;
    }

    /**
     * Update the status label based on server validity
     */
    private void updateStatusLabel() {
        if (tomcatInfo.isValid()) {
            statusLabel.setText("Valid");
            statusLabel.setForeground(UIUtil.getLabelSuccessForeground());
            statusLabel.setIcon(UIUtil.getBalloonInformationIcon());
        } else {
            statusLabel.setText("Invalid");
            statusLabel.setForeground(UIUtil.getErrorForeground());
            statusLabel.setIcon(UIUtil.getBalloonErrorIcon());
        }
    }

    /**
     * Update validation status with detailed information
     */
    private void updateValidationStatus() {
        try {
            tomcatInfo.validate();
            validationLabel.setText("All checks passed");
            validationLabel.setForeground(UIUtil.getLabelSuccessForeground());
        } catch (IllegalStateException e) {
            validationLabel.setText(e.getMessage());
            validationLabel.setForeground(UIUtil.getErrorForeground());
        }
    }

    /**
     * Get the main panel component
     *
     * @return The main UI panel
     */
    @NotNull
    public JComponent getMainPanel() {
        return mainPanel;
    }

    /**
     * Refresh the display with current information
     */
    public void refresh() {
        versionLabel.setText(tomcatInfo.getVersion());
        locationField.setText(tomcatInfo.getPath());
        updateStatusLabel();
        updateValidationStatus();

        LOG.debug("Refreshed display for server: " + tomcatInfo.getName());
    }

    /**
     * Check if the displayed information has been modified
     *
     * @return Always false as this is a read-only display
     */
    public boolean isModified() {
        return false;
    }

    @Override
    public void dispose() {
        mainPanel = null;
        LOG.debug("Disposed TomcatInfoComponent for server: " + tomcatInfo.getName());
    }
}