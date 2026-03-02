package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/**
 * Manages the UI layout and components for deployment configuration.
 */
public class DeploymentConfigurationPanel extends JBPanel<DeploymentConfigurationPanel> {

    private static final Logger LOG = Logger.getInstance(DeploymentConfigurationPanel.class);

    private final DeploymentTableManager tableManager;
    private final ArtifactSelectionHandler selectionHandler;

    private JBTextField contextTextField;
    private boolean isUpdatingContextField = false;

    public DeploymentConfigurationPanel(@NotNull Project project,
                                        @NotNull DeploymentTableManager tableManager,
                                        @NotNull ArtifactSelectionHandler selectionHandler) {
        super(new BorderLayout());
        this.tableManager = tableManager;
        this.selectionHandler = selectionHandler;

        initializeComponents();
        setupLayout();

        LOG.info("DeploymentConfigurationPanel initialized with " + getComponentCount() + " components");
    }

    private void initializeComponents() {
        contextTextField = new JBTextField();
        contextTextField.setEnabled(false);

        contextTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateContext(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateContext(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateContext(); }

            private void updateContext() {
                if (isUpdatingContextField) return;
                String text = contextTextField.getText();
                boolean valid = tableManager.updateSelectedContext(text);
                contextTextField.setForeground(valid ? JBColor.foreground() : JBColor.RED);
            }
        });

        @SuppressWarnings("unchecked")
        JList<DeploymentArtifact> list = (JList<DeploymentArtifact>) tableManager.getComponent();
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DeploymentArtifact selected = tableManager.getSelectedDeployment();
                isUpdatingContextField = true;
                if (selected != null) {
                    contextTextField.setText(selected.getApplicationContext());
                    contextTextField.setEnabled(true);
                } else {
                    contextTextField.setText("");
                    contextTextField.setEnabled(false);
                }
                contextTextField.setForeground(JBColor.foreground());
                isUpdatingContextField = false;
            }
        });
    }

    private void setupLayout() {
        setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        // Title at top
        TitledSeparator title = new TitledSeparator("Deploy at the server startup");
        add(title, BorderLayout.NORTH);

        // List with toolbar in center
        JPanel listPanel = createListWithToolbar();
        add(listPanel, BorderLayout.CENTER);

        // Context path editor at bottom
        JPanel contextPanel = new JPanel(new BorderLayout(8, 0));
        contextPanel.setBorder(JBUI.Borders.emptyTop(6));
        contextPanel.add(new JLabel("Application context:"), BorderLayout.WEST);
        contextPanel.add(contextTextField, BorderLayout.CENTER);
        add(contextPanel, BorderLayout.SOUTH);

        LOG.info("DeploymentConfigurationPanel layout setup complete");
    }

    @SuppressWarnings("unchecked")
    private JPanel createListWithToolbar() {
        try {
            ToolbarDecorator decorator = ToolbarDecorator.createDecorator((JBList) tableManager.getComponent());
            decorator.setAddAction(button -> showAddArtifactPopup(button));
            decorator.setRemoveAction(button -> tableManager.removeSelectedDeployment());
            decorator.setEditAction(button -> editSelectedArtifact());
            decorator.setMoveUpAction(button -> tableManager.moveSelectedUp());
            decorator.setMoveDownAction(button -> tableManager.moveSelectedDown());

            JPanel decoratedPanel = decorator.createPanel();
            decoratedPanel.setMinimumSize(new Dimension(320, 180));
            decoratedPanel.setPreferredSize(new Dimension(600, 220));
            LOG.info("Toolbar created successfully with list");
            return decoratedPanel;

        } catch (Throwable e) {
            LOG.error("Error creating toolbar, using fallback", e);

            JPanel fallbackPanel = new JPanel(new BorderLayout());
            JScrollPane scrollPane = new JScrollPane(tableManager.getComponent());
            fallbackPanel.add(scrollPane, BorderLayout.CENTER);
            return fallbackPanel;
        }
    }

    private void showAddArtifactPopup(AnActionButton button) {
        List<String> options = Arrays.asList(TomcatConstants.DEPLOY_OPTION_ARTIFACT, TomcatConstants.DEPLOY_OPTION_EXTERNAL);

        BaseListPopupStep<String> step = new BaseListPopupStep<String>("Deploy", options) {
            @Override
            public PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                if (TomcatConstants.DEPLOY_OPTION_ARTIFACT.equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> selectionHandler.showArtifactSelectionDialog());
                } else if (TomcatConstants.DEPLOY_OPTION_EXTERNAL.equals(selectedValue)) {
                    SwingUtilities.invokeLater(() -> selectionHandler.showExternalSourceDialog());
                }
                return FINAL_CHOICE;
            }
        };

        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.showUnderneathOf(button.getContextComponent());
    }

    private void editSelectedArtifact() {
        DeploymentArtifact deployment = tableManager.getSelectedDeployment();
        if (deployment != null) {
            com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ArtifactDeploymentEditDialog dialog =
                    new com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ArtifactDeploymentEditDialog(
                            this, deployment
                    );

            if (dialog.showAndGet()) {
                tableManager.updateSelectedDeployment(deployment);
                LOG.debug("Updated deployment: " + deployment.getDisplayName());
            }
        }
    }

    private void updateEmptyState() {
        tableManager.refreshList();
    }

    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        tableManager.clearAll();

        if (config.getConfigData().getDeploymentConfig() != null &&
                config.getConfigData().getDeploymentConfig().getArtifacts() != null) {
            for (DeploymentArtifact artifact : config.getConfigData().getDeploymentConfig().getArtifacts()) {
                if (artifact != null) {
                    tableManager.addDeployment(artifact.clone());
                }
            }
        }

        updateEmptyState();
    }

    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        List<DeploymentArtifact> artifacts = tableManager.getDeployments();
        config.getConfigData().getDeploymentConfig().setArtifacts(artifacts);
    }

    public boolean isConfigurationValid() {
        // Deployment configuration is always valid (artifacts are optional)
        return true;
    }
}
