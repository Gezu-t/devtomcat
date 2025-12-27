package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * Professional Deployment Configuration Panel - Corrected Version
 * Manages the UI layout and components for deployment configuration
 *
 * Works with existing TomcatRunConfiguration UI settings
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentConfigurationPanel extends JPanel {

    private final DeploymentTableManager tableManager;
    private final ArtifactSelectionHandler selectionHandler;

    // UI components
    private JPanel tablePanel;
    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;

    public DeploymentConfigurationPanel(@NotNull Project project,
                                        @NotNull DeploymentTableManager tableManager,
                                        @NotNull ArtifactSelectionHandler selectionHandler) {
        this.tableManager = tableManager;
        this.selectionHandler = selectionHandler;

        initializeComponents();
        setupLayout();

        System.out.println("DevTomcat: DeploymentConfigurationPanel initialized");
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(720, 320);
    }

    /**
     * Set parent editor for event coordination (kept for API compatibility)
     */
    public void setParentEditor(TomcatConfigurationEditor parentEditor) {
        // No-op: event coordination not needed in simplified implementation
    }

    /**
     * Initialize UI components
     */
    private void initializeComponents() {
        showThisPageCheckBox = new JCheckBox("Show this page");
        activateToolWindowCheckBox = new JCheckBox("Activate tool window");
        activateToolWindowCheckBox.setSelected(true);
    }

    /**
     * Setup panel layout
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        // Main content panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(720, 320));
        mainPanel.setMinimumSize(new Dimension(640, 260));
        mainPanel.setOpaque(true);

        // Deploy at server startup section (table with toolbar) wrapped in scroll for safety
        JPanel deploymentSection = createDeploymentSection();
        JScrollPane scrollPane = new JScrollPane(deploymentSection);
        scrollPane.setBorder(null);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);
        add(createBottomOptions(), BorderLayout.SOUTH);
    }

    /**
     * Create deployment section with table and toolbar
     */
    private JPanel createDeploymentSection() {
        JPanel panel = new JPanel(new BorderLayout());

        // Title label
        JBLabel titleLabel = new JBLabel("Deploy at the server startup");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(8));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Table with toolbar
        tablePanel = createTableWithToolbar();
        panel.add(tablePanel, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(640, 260));
        panel.setMinimumSize(new Dimension(640, 220));

        return panel;
    }

    private JPanel createBottomOptions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));
        panel.add(showThisPageCheckBox);
        panel.add(activateToolWindowCheckBox);
        return panel;
    }

    /**
     * Create table with toolbar
     * Toolbar is ALWAYS visible, table shows empty state when needed
     */
    private JPanel createTableWithToolbar() {
        try {
            // Create the toolbar decorator - ALWAYS VISIBLE
            ToolbarDecorator decorator = ToolbarDecorator.createDecorator(tableManager.getTable());

            // Add button with dropdown
            decorator.setAddAction(button -> showAddArtifactPopup(button));

            // Remove button
            decorator.setRemoveAction(button -> tableManager.removeSelectedDeployment());

            // Edit button
            decorator.setEditAction(button -> editSelectedArtifact());

            // Move up/down actions
            decorator.setMoveUpAction(button -> tableManager.moveSelectedUp());
            decorator.setMoveDownAction(button -> tableManager.moveSelectedDown());

            // Create the decorated panel with toolbar (no empty overlay)
            JPanel decoratedPanel = decorator.createPanel();
            decoratedPanel.setPreferredSize(new Dimension(620, 240));
            decoratedPanel.setMinimumSize(new Dimension(620, 200));
            decoratedPanel.setBorder(JBUI.Borders.empty(4));

            System.out.println("DevTomcat: Toolbar created successfully with table");
            return decoratedPanel;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating toolbar: " + e.getMessage());
            e.printStackTrace();

            // Fallback to simple scroll pane
            JPanel fallbackPanel = new JPanel(new BorderLayout());
            JScrollPane scrollPane = new JScrollPane(tableManager.getTable());
            scrollPane.setPreferredSize(new Dimension(600, 200));
            fallbackPanel.add(scrollPane, BorderLayout.CENTER);
            fallbackPanel.setBorder(JBUI.Borders.empty(5));
            return fallbackPanel;
        }
    }

    /**
     * Show add artifact popup menu
     */
    private void showAddArtifactPopup(AnActionButton button) {
        List<String> options = Arrays.asList("Artifact...", "External Source...");

        BaseListPopupStep<String> step = new BaseListPopupStep<String>("Deploy", options) {
            @Override
            public PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                if ("Artifact...".equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> {
                        selectionHandler.showArtifactSelectionDialog();
                        updateEmptyState();
                    });
                } else if ("External Source...".equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> {
                        selectionHandler.showExternalSourceDialog();
                        updateEmptyState();
                    });
                }
                return FINAL_CHOICE;
            }
        };

        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.showUnderneathOf(button.getContextComponent());
    }

    /**
     * Edit selected artifact
     */
    private void editSelectedArtifact() {
        DeploymentArtifact deployment = tableManager.getSelectedDeployment();
        if (deployment != null) {
            // Use the improved edit dialog
            com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ArtifactDeploymentEditDialog dialog =
                    new com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ArtifactDeploymentEditDialog(
                            this, deployment
                    );

            if (dialog.showAndGet()) {
                tableManager.updateSelectedDeployment(deployment);
                System.out.println("DevTomcat: Updated deployment: " + deployment.getDisplayName());
            }
        }
    }

    /**
     * Update table display (just refresh the table)
     */
    private void updateEmptyState() {
        tableManager.refreshTable();
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        tableManager.clearAll();

        // Load artifacts from configuration
        if (config.getConfigData().getDeploymentConfig() != null &&
                config.getConfigData().getDeploymentConfig().getArtifacts() != null) {
            for (DeploymentArtifact artifact : config.getConfigData().getDeploymentConfig().getArtifacts()) {
                if (artifact != null) {
                    tableManager.addDeployment(artifact.clone());
                }
            }
        }

        updateEmptyState();

        // UI options
        activateToolWindowCheckBox.setSelected(config.getConfigData().getUiConfig().isActivateToolWindow());
        showThisPageCheckBox.setSelected(false);
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        List<DeploymentArtifact> artifacts = tableManager.getDeployments();
        config.getConfigData().getDeploymentConfig().setArtifacts(artifacts);
        config.getConfigData().getUiConfig().setActivateToolWindow(activateToolWindowCheckBox.isSelected());
    }

    /**
     * Check if configuration is valid
     */
    public boolean isValid() {
        // Deployment configuration is always valid (artifacts are optional)
        return true;
    }
}
