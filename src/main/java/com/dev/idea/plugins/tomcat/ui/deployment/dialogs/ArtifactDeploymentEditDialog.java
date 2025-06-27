package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Professional Artifact Deployment Edit Dialog
 * Allows editing deployment configuration for artifacts
 *
 * Provides clean UI for modifying deployment paths
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class ArtifactDeploymentEditDialog extends DialogWrapper {

    private final DeploymentArtifact deployment;
    private JTextField contextPathField;
    private JBLabel artifactNameLabel;
    private JBLabel artifactTypeLabel;

    public ArtifactDeploymentEditDialog(@NotNull Component parent,
                                        @NotNull DeploymentArtifact deployment) {
        super(parent, true);
        this.deployment = deployment;

        setTitle("Edit Artifact Deployment");
        setModal(true);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(450, 150));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Artifact name (read-only)
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JBLabel("Artifact:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        artifactNameLabel = new JBLabel(deployment.getDisplayName());
        artifactNameLabel.setFont(artifactNameLabel.getFont().deriveFont(Font.BOLD));
        panel.add(artifactNameLabel, gbc);

        // Artifact type (read-only)
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JBLabel("Type:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        artifactTypeLabel = new JBLabel(deployment.getType());
        panel.add(artifactTypeLabel, gbc);

        // Context path (editable)
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        panel.add(new JBLabel("Context Path:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        contextPathField = new JTextField(deployment.getServerPath());
        contextPathField.setToolTipText("The context path where the artifact will be deployed (e.g., /myapp)");
        panel.add(contextPathField, gbc);

        // Add some vertical space
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weighty = 1;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String contextPath = contextPathField.getText().trim();

        // Validate context path
        if (contextPath.isEmpty()) {
            return new ValidationInfo("Context path cannot be empty", contextPathField);
        }

        // Check for invalid characters
        if (contextPath.contains(" ")) {
            return new ValidationInfo("Context path cannot contain spaces", contextPathField);
        }

        // Warn about missing leading slash (but don't block)
        if (!contextPath.startsWith("/") && !contextPath.equals("/")) {
            contextPathField.setText("/" + contextPath);
        }

        return null;
    }

    @Override
    protected void doOKAction() {
        String contextPath = contextPathField.getText().trim();

        // Ensure context path starts with /
        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }

        // Update deployment
        deployment.setServerPath(contextPath);

        System.out.println("DevTomcat: Updated deployment context path to: " + contextPath);

        super.doOKAction();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return contextPathField;
    }
}