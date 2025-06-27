package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * Professional Deployment Configuration Panel
 * Manages the UI layout and components for deployment configuration
 *
 * Single Responsibility: UI layout and component management
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentConfigurationPanel extends JPanel {

    private final Project project;
    private final DeploymentTableManager tableManager;
    private final ArtifactSelectionHandler selectionHandler;

    // UI Components
    private JTextField applicationContextField;
    private JButton browseApplicationContextButton;
    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;
    private JCheckBox focusToolWindowCheckBox;

    // Parent coordination
    private TomcatConfigurationEditor parentEditor;
    private volatile boolean suppressEvents = false;

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
        // Application context field
        applicationContextField = new JTextField();
        applicationContextField.setPreferredSize(new Dimension(350, 25));
        applicationContextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { handleContextChange(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { handleContextChange(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { handleContextChange(); }

            private void handleContextChange() {
                if (!isEventsSuppressed()) {
                    System.out.println("DevTomcat: Application context changed: " + applicationContextField.getText());
                }
            }
        });

        // Browse button
        browseApplicationContextButton = new JButton("...");
        browseApplicationContextButton.setPreferredSize(new Dimension(25, 25));
        browseApplicationContextButton.addActionListener(e -> {
            if (!isEventsSuppressed()) {
                browseApplicationContext();
            }
        });

        // Checkboxes
        showThisPageCheckBox = new JCheckBox("Show this page", false);
        activateToolWindowCheckBox = new JCheckBox("Activate tool window", true);
        focusToolWindowCheckBox = new JCheckBox("Focus tool window", false);

        // Add listeners with event suppression
        addCheckBoxListeners();
    }

    /**
     * Add checkbox listeners with event suppression
     */
    private void addCheckBoxListeners() {
        showThisPageCheckBox.addActionListener(e -> {
            if (!isEventsSuppressed()) {
                System.out.println("DevTomcat: Show this page: " + showThisPageCheckBox.isSelected());
            }
        });

        activateToolWindowCheckBox.addActionListener(e -> {
            if (!isEventsSuppressed()) {
                System.out.println("DevTomcat: Activate tool window: " + activateToolWindowCheckBox.isSelected());
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

        // Deploy at server startup section
        mainPanel.add(createDeploymentSection(), BorderLayout.CENTER);

        // Application context section
        mainPanel.add(createApplicationContextSection(), BorderLayout.SOUTH);

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
        JPanel tablePanel = createTableWithToolbar();
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
                    tableManager.removeSelectedDeployment();
                }
            });

            // Edit button
            decorator.setEditAction(new AnActionButtonRunnable() {
                @Override
                public void run(AnActionButton button) {
                    editSelectedArtifact();
                }
            });

            // Disable move actions initially
            decorator.disableUpDownActions();

            JPanel decoratedPanel = decorator.createPanel();
            decoratedPanel.setPreferredSize(new Dimension(600, 200));
            decoratedPanel.setBorder(JBUI.Borders.empty(5));

            return decoratedPanel;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating toolbar: " + e.getMessage());
            e.printStackTrace();

            // Fallback to panel with scroll pane
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

        BaseListPopupStep<String> step = new BaseListPopupStep<String>("Add Deployment", options) {
            @Override
            public PopupStep onChosen(String selectedValue, boolean finalChoice) {
                if ("Artifact...".equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> selectionHandler.showArtifactSelectionDialog());
                } else if ("External Source...".equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> selectionHandler.showExternalSourceDialog());
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
            ArtifactDeploymentEditDialog dialog = new ArtifactDeploymentEditDialog(this, deployment);
            if (dialog.showAndGet()) {
                tableManager.updateSelectedDeployment(deployment);
                System.out.println("DevTomcat: Updated deployment: " + deployment.getDisplayName());
            }
        }
    }

    /**
     * Create application context section
     */
    private JPanel createApplicationContextSection() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.emptyTop(15));

        JPanel contextPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        JBLabel contextLabel = new JBLabel("Application context: ");
        contextLabel.setPreferredSize(new Dimension(120, 25));

        contextPanel.add(contextLabel);
        contextPanel.add(applicationContextField);
        contextPanel.add(Box.createHorizontalStrut(5));
        contextPanel.add(browseApplicationContextButton);

        panel.add(contextPanel, BorderLayout.WEST);

        return panel;
    }

    /**
     * Create bottom checkbox panel
     */
    private JPanel createBottomCheckboxPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        panel.setBorder(JBUI.Borders.emptyTop(20));

        panel.add(showThisPageCheckBox);
        panel.add(Box.createHorizontalStrut(20));
        panel.add(activateToolWindowCheckBox);
        panel.add(Box.createHorizontalStrut(20));
        panel.add(focusToolWindowCheckBox);

        return panel;
    }

    /**
     * Browse for application context directory
     */
    private void browseApplicationContext() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
        descriptor.setTitle("Select Application Context Directory");

        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            applicationContextField.setText(file.getPath());
        }
    }

    /**
     * Set application context
     */
    public void setApplicationContext(String contextPath) {
        if (applicationContextField != null) {
            applicationContextField.setText(contextPath);
        }
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        suppressEvents = true;
        try {
            // Reset application context
            String contextPath = config.getContextPath();
            applicationContextField.setText(contextPath != null ? contextPath : "");

            // Reset checkboxes to Ultimate defaults
            showThisPageCheckBox.setSelected(false);
            activateToolWindowCheckBox.setSelected(true);
            focusToolWindowCheckBox.setSelected(false);

        } finally {
            suppressEvents = false;
        }
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        // Apply application context
        String contextPath = applicationContextField.getText().trim();
        if (!contextPath.isEmpty() && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        config.setContextPath(contextPath);

        // Note: Checkbox states could be stored in enhanced options if needed
    }

    /**
     * Inner class for Edit Dialog
     * (Can be moved to separate file if preferred)
     */
    private static class ArtifactDeploymentEditDialog extends DialogWrapper {
        private final DeploymentArtifact deployment;
        private JTextField contextPathField;

        public ArtifactDeploymentEditDialog(Component parent, DeploymentArtifact deployment) {
            super(parent, true);
            this.deployment = deployment;
            setTitle("Edit Artifact Deployment");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(400, 120));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            contextPathField = new JTextField(deployment.getServerPath(), 25);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JBLabel("Artifact:"), gbc);
            gbc.gridx = 1;
            panel.add(new JBLabel(deployment.getDisplayName()), gbc);

            gbc.gridx = 0; gbc.gridy = 1;
            panel.add(new JBLabel("Context Path:"), gbc);
            gbc.gridx = 1;
            panel.add(contextPathField, gbc);

            return panel;
        }

        @Override
        protected void doOKAction() {
            deployment.setServerPath(contextPathField.getText().trim());
            super.doOKAction();
        }
    }
}