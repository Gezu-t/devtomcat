package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ArtifactDeploymentEditDialog;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private DocumentListener contextDocListener;
    private ListSelectionListener listSelectionListener;

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

        contextDocListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateContext(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateContext(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateContext(); }

            private void updateContext() {
                if (isUpdatingContextField) return;
                String text = contextTextField.getText();
                boolean valid = tableManager.updateSelectedContext(text);
                contextTextField.setForeground(valid ? JBColor.foreground() : JBColor.RED);
                if (!valid && text != null && !text.isEmpty()) {
                    String normalized = ContextPathUtils.normalizeContextPath(text);
                    if (!ContextPathUtils.isValidContextPath(normalized)) {
                        contextTextField.setToolTipText("Invalid context path format");
                    } else {
                        contextTextField.setToolTipText("Context path already in use by another artifact");
                    }
                } else {
                    contextTextField.setToolTipText(null);
                }
            }
        };
        contextTextField.getDocument().addDocumentListener(contextDocListener);

        @SuppressWarnings("unchecked")
        JList<DeploymentArtifact> list = (JList<DeploymentArtifact>) tableManager.getComponent();
        listSelectionListener = e -> {
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
        };
        list.addListSelectionListener(listSelectionListener);
    }

    private void setupLayout() {
        setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        // Title at top
        TitledSeparator title = new TitledSeparator("Deploy at the server startup");
        add(title, BorderLayout.NORTH);

        // List with toolbar + context editor together in center
        JPanel centerPanel = new JPanel(new BorderLayout());

        JPanel listPanel = createListWithToolbar();
        centerPanel.add(listPanel, BorderLayout.CENTER);

        // Context path editor directly below the list toolbar
        JPanel contextPanel = new JPanel(new BorderLayout(8, 0));
        contextPanel.setBorder(JBUI.Borders.emptyTop(4));
        contextPanel.add(new com.intellij.ui.components.JBLabel("Application context:"), BorderLayout.WEST);
        contextPanel.add(contextTextField, BorderLayout.CENTER);
        centerPanel.add(contextPanel, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

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
            decoratedPanel.setMinimumSize(new Dimension(JBUI.scale(320), JBUI.scale(180)));
            decoratedPanel.setPreferredSize(new Dimension(JBUI.scale(600), JBUI.scale(220)));
            LOG.info("Toolbar created successfully with list");
            return decoratedPanel;

        } catch (Throwable e) {
            LOG.error("Error creating toolbar, using fallback", e);

            JPanel fallbackPanel = new JPanel(new BorderLayout());
            com.intellij.ui.components.JBScrollPane scrollPane = new com.intellij.ui.components.JBScrollPane(tableManager.getComponent());
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
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                            () -> selectionHandler.showArtifactSelectionDialog());
                } else if (TomcatConstants.DEPLOY_OPTION_EXTERNAL.equals(selectedValue)) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                            () -> selectionHandler.showExternalSourceDialog());
                }
                return FINAL_CHOICE;
            }
        };

        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        com.intellij.ui.awt.RelativePoint point = button.getPreferredPopupPoint();
        if (point != null) {
            popup.show(point);
        } else {
            popup.showUnderneathOf(button.getContextComponent());
        }
    }

    private void editSelectedArtifact() {
        DeploymentArtifact deployment = tableManager.getSelectedDeployment();
        if (deployment != null) {
            ArtifactDeploymentEditDialog dialog =
                    new ArtifactDeploymentEditDialog(
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

        List<DeploymentArtifact> artifacts = config.getConfigData().getDeploymentConfig().getArtifacts();
        if (artifacts != null) {
            for (DeploymentArtifact artifact : artifacts) {
                if (artifact != null) {
                    tableManager.addDeployment(artifact.clone());
                }
            }
        }

        if (tableManager.getDeploymentCount() > 0) {
            tableManager.setSelectedIndex(0);
        }

        updateEmptyState();
    }

    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        List<DeploymentArtifact> artifacts = tableManager.getDeployments();
        config.getConfigData().getDeploymentConfig().setArtifacts(artifacts);
    }

    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        List<DeploymentArtifact> currentArtifacts = tableManager.getDeployments();
        List<DeploymentArtifact> savedArtifacts = config.getConfigData().getDeploymentConfig().getArtifacts();

        if (currentArtifacts.size() != savedArtifacts.size()) return true;

        for (int i = 0; i < currentArtifacts.size(); i++) {
            DeploymentArtifact current = currentArtifacts.get(i);
            DeploymentArtifact saved = savedArtifacts.get(i);
            if (!Objects.equals(current.getName(), saved.getName())) return true;
            if (!Objects.equals(current.getPath(), saved.getPath())) return true;
            if (!Objects.equals(current.getContextPath(), saved.getContextPath())) return true;
            if (!Objects.equals(current.getType(), saved.getType())) return true;
        }
        return false;
    }

    public boolean isConfigurationValid() {
        Set<String> seenContextPaths = new HashSet<>();
        for (DeploymentArtifact d : tableManager.getDeployments()) {
            if (d.getPath() == null || d.getPath().trim().isEmpty()) {
                return false;
            }
            String ctx = d.getContextPath();
            if (ctx != null && !seenContextPaths.add(ctx)) {
                return false; // duplicate context path
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public void dispose() {
        if (contextTextField != null && contextDocListener != null) {
            contextTextField.getDocument().removeDocumentListener(contextDocListener);
            contextDocListener = null;
        }
        if (tableManager != null && listSelectionListener != null) {
            JList<DeploymentArtifact> list = (JList<DeploymentArtifact>) tableManager.getComponent();
            list.removeListSelectionListener(listSelectionListener);
            listSelectionListener = null;
        }
    }
}
