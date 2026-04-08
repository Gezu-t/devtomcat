package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.ui.components.JBTextField;

import javax.swing.*;
import java.awt.*;

public class ArtifactDeploymentEditDialog extends DialogWrapper {
    private final DeploymentArtifact deployment;
    private JBTextField contextField;

    public ArtifactDeploymentEditDialog(JComponent parent, @NotNull DeploymentArtifact deployment) {
        super(SwingUtilities.getWindowAncestor(parent), true);
        this.deployment = deployment;
        setTitle("Edit Deployment");
        setModal(true);
        setResizable(false);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(400), JBUI.scale(150)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JBLabel infoLabel = new JBLabel("<html>Edit context for <b>" + deployment.getDisplayName() + "</b>:</html>");
        panel.add(infoLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        panel.add(new JBLabel("Application context:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        contextField = new JBTextField();
        contextField.setText(deployment.getApplicationContext());
        contextField.setPreferredSize(new Dimension(JBUI.scale(250), JBUI.scale(25)));
        contextField.selectAll();
        panel.add(contextField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weightx = 0;
        JBLabel helpLabel = new JBLabel(
                "<html><small>The context path where the application will be accessible<br>" +
                        "For example: /myapp will be accessible at http://localhost:8080/myapp</small></html>");
        helpLabel.setForeground(NamedColorUtil.getInactiveTextColor());
        panel.add(helpLabel, gbc);

        gbc.gridy = 3; gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String context = contextField.getText().trim();
        if (context.isEmpty()) {
            return new ValidationInfo("Application context cannot be empty", contextField);
        }
        String normalized = ContextPathUtils.normalizeContextPath(context);
        if (!ContextPathUtils.isValidContextPath(normalized)) {
            return new ValidationInfo(
                    "Invalid context path. Must start with '/' and contain only valid URL characters.",
                    contextField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String context = ContextPathUtils.normalizeContextPath(contextField.getText().trim());
        deployment.setApplicationContext(context);
        super.doOKAction();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return contextField;
    }
}