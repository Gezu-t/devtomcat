package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog for entering Application Context after artifact selection
 * Matches IntelliJ Ultimate's context input dialog
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class ArtifactContextDialog extends DialogWrapper {

    private final String artifactName;
    private final String suggestedContext;
    private JTextField contextField;

    public ArtifactContextDialog(@NotNull Project project,
                                 @NotNull String artifactName,
                                 @NotNull String suggestedContext) {
        super(project);
        this.artifactName = artifactName;
        this.suggestedContext = suggestedContext;

        setTitle("Web Application Context");
        setModal(true);
        setResizable(false);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(400, 150));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Info label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JBLabel infoLabel = new JBLabel(
                "<html>Specify the application context for <b>" + artifactName + "</b>:</html>"
        );
        panel.add(infoLabel, gbc);

        // Context label and field
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JBLabel("Application context:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        contextField = new JTextField(suggestedContext);
        contextField.setPreferredSize(new Dimension(250, 25));
        contextField.selectAll(); // Select all text for easy replacement
        panel.add(contextField, gbc);

        // Help text
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.insets = JBUI.insets(10, 5, 5, 5);
        JBLabel helpLabel = new JBLabel(
                "<html><small>The context path where the application will be accessible<br>" +
                        "For example: /myapp will be accessible at http://localhost:8080/myapp</small></html>"
        );
        helpLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(helpLabel, gbc);

        // Add vertical glue to push content to top
        gbc.gridy = 3;
        gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String context = contextField.getText().trim();

        if (context.isEmpty()) {
            return new ValidationInfo("Application context cannot be empty", contextField);
        }

        // Check for spaces
        if (context.contains(" ")) {
            return new ValidationInfo("Application context cannot contain spaces", contextField);
        }

        // Check for invalid characters
        if (!context.matches("^/[a-zA-Z0-9\\-_/]*$") && !context.equals("/")) {
            return new ValidationInfo(
                    "Application context can only contain letters, numbers, hyphens, and underscores",
                    contextField
            );
        }

        return null;
    }

    @Override
    protected void doOKAction() {
        String context = contextField.getText().trim();

        // Ensure it starts with /
        if (!context.startsWith("/")) {
            context = "/" + context;
        }

        // Store the cleaned context
        contextField.setText(context);

        super.doOKAction();
    }

    /**
     * Get the entered application context
     */
    public String getApplicationContext() {
        return contextField.getText().trim();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return contextField;
    }
}