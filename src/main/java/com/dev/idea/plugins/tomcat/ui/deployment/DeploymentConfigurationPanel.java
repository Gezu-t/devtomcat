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
import com.intellij.ui.AnActionButtonRunnable;
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

    private final Project project;
    private final DeploymentTableManager tableManager;
    private final ArtifactSelectionHandler selectionHandler;

    // UI Components - Options only
    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;
    private JCheckBox focusToolWindowCheckBox;

    // Parent coordination
    private TomcatConfigurationEditor parentEditor;
    private volatile boolean suppressEvents = false;

    // UI components for empty state
    private JPanel tablePanel;
    private JLabel emptyStateLabel;

    public DeploymentConfigurationPanel(@NotNull Project project,
                                        @NotNull DeploymentTableManager tableManager,
                                        @NotNull ArtifactSelectionHandler selectionHandler) {
        this.project = project;
        this.tableManager = tableManager;
        this.selectionHandler = selectionHandler;

        initializeComponents();
        setupLayout();

        System.out.println("DevTomcat: DeploymentConfigurationPanel initialized");
    }

    /**
     * Set parent editor for event coordination
     */
    public void setParentEditor(TomcatConfigurationEditor parentEditor) {
        this.parentEditor = parentEditor;
    }

    /**
     * Check if events should be suppressed
     */
    private boolean isEventsSuppressed() {
        return suppressEvents || (parentEditor != null && parentEditor.isEventsSuppressed());
    }

    /**
     * Initialize UI components
     */
    private void initializeComponents() {
        // Checkboxes for tool window behavior
        showThisPageCheckBox = new JCheckBox("Show this page");
        showThisPageCheckBox.setToolTipText("Show this configuration page when creating new run configurations");

        activateToolWindowCheckBox = new JCheckBox("Activate tool window");
        activateToolWindowCheckBox.setToolTipText("Activate the Run tool window when deployment starts");

        focusToolWindowCheckBox = new JCheckBox("Focus tool window");
        focusToolWindowCheckBox.setToolTipText("Focus the Run tool window when deployment starts");

        // Empty state label
        emptyStateLabel = new JLabel("Nothing to deploy", SwingConstants.CENTER);
        emptyStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        emptyStateLabel.setFont(emptyStateLabel.getFont().deriveFont(14f));

        // Add listeners with event suppression
        addCheckBoxListeners();
    }

    /**
     * Add checkbox listeners with event suppression
     */
    private void addCheckBoxListeners() {
        activateToolWindowCheckBox.addActionListener(e -> {
            if (!isEventsSuppressed()) {
                System.out.println("DevTomcat: Activate tool window: " + activateToolWindowCheckBox.isSelected());
                // If not activating, can't focus either
                if (!activateToolWindowCheckBox.isSelected()) {
                    focusToolWindowCheckBox.setSelected(false);
                    focusToolWindowCheckBox.setEnabled(false);
                } else {
                    focusToolWindowCheckBox.setEnabled(true);
                }
            }
        });

        focusToolWindowCheckBox.addActionListener(e -> {
            if (!isEventsSuppressed()) {
                System.out.println("DevTomcat: Focus tool window: " + focusToolWindowCheckBox.isSelected());
            }
        });
    }

    /**
     * Setup panel layout
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        // Main content panel
        JPanel mainPanel = new JPanel(new BorderLayout());

        // Deploy at server startup section (table with toolbar)
        mainPanel.add(createDeploymentSection(), BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);

        // Bottom checkboxes
        add(createBottomCheckboxPanel(), BorderLayout.SOUTH);
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

        return panel;
    }

    /**
     * Create table with toolbar
     */
    private JPanel createTableWithToolbar() {
        try {
            ToolbarDecorator decorator = ToolbarDecorator.createDecorator(tableManager.getTable());

            // Add button with dropdown
            decorator.setAddAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    showAddArtifactPopup(button);
                }
            });

            // Remove button
            decorator.setRemoveAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    if (tableManager.hasSelection()) {
                        tableManager.removeSelectedDeployment();
                        updateEmptyState();
                    }
                }
            });

            // Edit button
            decorator.setEditAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    editSelectedArtifact();
                }
            });

            // Move up/down actions
            decorator.setMoveUpAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    tableManager.moveSelectedUp();
                }
            });

            decorator.setMoveDownAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    tableManager.moveSelectedDown();
                }
            });

            JPanel decoratedPanel = decorator.createPanel();
            decoratedPanel.setPreferredSize(new Dimension(600, 250));
            decoratedPanel.setBorder(JBUI.Borders.empty(5));

            // Create layered panel for empty state
            JPanel layeredPanel = new JPanel(new CardLayout());
            layeredPanel.add(decoratedPanel, "table");

            JPanel emptyPanel = new JPanel(new BorderLayout());
            emptyPanel.add(emptyStateLabel, BorderLayout.CENTER);
            emptyPanel.setBackground(decoratedPanel.getBackground());
            layeredPanel.add(emptyPanel, "empty");

            updateEmptyState();
            return layeredPanel;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating toolbar: " + e.getMessage());
            e.printStackTrace();

            // Fallback to simple scroll pane
            JPanel fallbackPanel = new JPanel(new BorderLayout());
            JScrollPane scrollPane = new JScrollPane(tableManager.getTable());
            scrollPane.setPreferredSize(new Dimension(600, 250));
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
            public PopupStep onChosen(String selectedValue, boolean finalChoice) {
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
     * Update empty state display
     */
    private void updateEmptyState() {
        if (tablePanel != null && tablePanel.getLayout() instanceof CardLayout) {
            CardLayout layout = (CardLayout) tablePanel.getLayout();
            if (tableManager.getDeploymentCount() == 0) {
                layout.show(tablePanel, "empty");
            } else {
                layout.show(tablePanel, "table");
            }
        }
        tableManager.refreshTable();
    }

    /**
     * Create bottom checkbox panel
     */
    private JPanel createBottomCheckboxPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        panel.setBorder(JBUI.Borders.emptyTop(10));

        panel.add(showThisPageCheckBox);
        panel.add(Box.createHorizontalStrut(20));
        panel.add(activateToolWindowCheckBox);
        panel.add(Box.createHorizontalStrut(20));
        panel.add(focusToolWindowCheckBox);

        return panel;
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        suppressEvents = true;
        try {
            // Reset checkboxes from configuration
            // Note: TomcatRunConfiguration doesn't have getShowSettings() method
            // so we'll use defaults for now
            showThisPageCheckBox.setSelected(false);
            activateToolWindowCheckBox.setSelected(config.isActivateToolWindow());
            focusToolWindowCheckBox.setSelected(config.isFocusToolWindow());

            // Update focus checkbox state
            focusToolWindowCheckBox.setEnabled(activateToolWindowCheckBox.isSelected());

            // Update empty state
            updateEmptyState();

        } finally {
            suppressEvents = false;
        }
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        // Apply checkbox states that the configuration supports
        config.setActivateToolWindow(activateToolWindowCheckBox.isSelected());
        config.setFocusToolWindow(focusToolWindowCheckBox.isSelected());

        // Note: showThisPageCheckBox state would need to be stored elsewhere
        // as TomcatRunConfiguration doesn't have a setShowSettings method
    }
}