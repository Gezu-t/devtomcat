package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Unified dialog for adding or editing a JDK entry.
 * Used by {@link JREConfigurationDialog} for both Add and Edit operations,
 * eliminating the code duplication between the former AddJdkDialog and EditJdkDialog.
 */
class JdkEditorDialog extends DialogWrapper {

    private final Project project;
    private JBTextField nameField;
    private TextFieldWithBrowseButton pathField;
    private JBTextField versionField;
    private JREConfigurationDialog.JdkInfo result;

    JdkEditorDialog(@NotNull Project project, @Nullable JREConfigurationDialog.JdkInfo existing) {
        super(project);
        this.project = project;
        setTitle(existing != null ? "Edit JDK" : "Add JDK");
        init();

        if (existing != null) {
            nameField.setText(existing.getName());
            pathField.setText(existing.getPath());
            versionField.setText(existing.getVersion());
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(450), JBUI.scale(200)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;

        // Name
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JBLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        nameField = new JBTextField();
        nameField.setText("JDK");
        nameField.setColumns(20);
        panel.add(nameField, gbc);

        // Path
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JBLabel("JDK Home:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        pathField = new TextFieldWithBrowseButton();
        SafeBrowseUtil.addBrowseFolderListener(
                pathField, "Select JDK Installation Directory",
                "Choose a JDK installation directory", project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor());
        pathField.getTextField().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
            private void onPathChanged() {
                String path = pathField.getText().trim();
                if (!path.isEmpty()) detectVersion(path);
            }
        });
        panel.add(pathField, gbc);

        // Version (auto-detected)
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JBLabel("Version:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        versionField = new JBTextField();
        versionField.setEditable(false);
        panel.add(versionField, gbc);

        return panel;
    }

    private void detectVersion(String path) {
        File releaseFile = new File(path, "release");
        if (releaseFile.exists()) {
            versionField.setText("JDK detected");
            if ("JDK".equals(nameField.getText())) {
                nameField.setText("JDK detected");
            }
        } else {
            versionField.setText("Unknown - Check JDK installation");
        }
    }

    @Override
    protected void doOKAction() {
        String name = nameField.getText().trim();
        String path = pathField.getText().trim();
        String version = versionField.getText().trim();

        if (name.isEmpty() || path.isEmpty()) {
            Messages.showErrorDialog("Please fill in all required fields", "Invalid Input");
            return;
        }

        File jdkDir = new File(path);
        if (!jdkDir.exists()) {
            Messages.showErrorDialog("JDK installation directory does not exist", "Invalid Path");
            return;
        }
        if (!new File(jdkDir, "bin").exists()) {
            Messages.showErrorDialog("Invalid JDK installation - missing bin directory", "Invalid Installation");
            return;
        }

        result = new JREConfigurationDialog.JdkInfo(name, version, path, false);
        super.doOKAction();
    }

    @Nullable
    JREConfigurationDialog.JdkInfo getJdkInfo() {
        return result;
    }
}
