package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RemoteServerPanel extends JPanel implements ConfigurationSection {

    private final JBTextField managerUrlField = new JBTextField("http://localhost:8080/manager", 40);
    private final JBTextField usernameField = new JBTextField("admin", 20);
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JCheckBox useCredentials = new JCheckBox("Use Credentials");

    public RemoteServerPanel() {
        setLayout(new GridBagLayout());
        initComponents();
        setupListeners();
    }

    private void initComponents() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;
        addSectionLabel(gbc, y++, "Remote Server");
        addRow(gbc, y++, "Manager URL:", managerUrlField);
        addRow(gbc, y++, "Username:", usernameField);
        addRow(gbc, y++, "Password:", passwordField);

        gbc.gridx = 0; gbc.gridy = y++; gbc.gridwidth = 3;
        add(useCredentials, gbc);
    }

    private void addSectionLabel(GridBagConstraints gbc, int y, String text) {
        gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 3;
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        add(label, gbc);
    }

    private void addRow(GridBagConstraints gbc, int y, String label, JComponent field) {
        gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 1;
        add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        add(field, gbc);
    }

    private void setupListeners() {
        useCredentials.addActionListener(e -> {
            boolean enabled = useCredentials.isSelected();
            usernameField.setEnabled(enabled);
            passwordField.setEnabled(enabled);
        });
    }

    @Override
    public void loadConfiguration() {
        // Load default remote configuration if needed
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        RemoteConfig rc = config.getConfigData().getRemoteConfig();
        managerUrlField.setText(rc.getManagerUrl());
        usernameField.setText(rc.getUsername());
        passwordField.setText(rc.getPassword());
        useCredentials.setSelected(rc.isUseCredentials());
        updateEnabledState();
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration config) {
        RemoteConfig rc = config.getConfigData().getRemoteConfig();
        rc.setManagerUrl(managerUrlField.getText().trim());
        rc.setUsername(usernameField.getText().trim());
        rc.setPassword(passwordField.getText());
        rc.setUseCredentials(useCredentials.isSelected());
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        RemoteConfig rc = config.getConfigData().getRemoteConfig();
        return !managerUrlField.getText().trim().equals(rc.getManagerUrl()) ||
                !usernameField.getText().trim().equals(rc.getUsername()) ||
                !passwordField.getText().equals(rc.getPassword()) ||
                useCredentials.isSelected() != rc.isUseCredentials();
    }

    @Override
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();
        String url = managerUrlField.getText().trim();
        if (!RemoteConfig.isValidManagerUrl(url)) {
            errors.add(new ValidationInfo("Invalid Manager URL format", managerUrlField));
        }
        if (useCredentials.isSelected() && usernameField.getText().trim().isEmpty()) {
            errors.add(new ValidationInfo("Username required", usernameField));
        }
        return errors;
    }

    private void updateEnabledState() {
        boolean enabled = useCredentials.isSelected();
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
    }

 @Override
 public JPanel createPanel() {
     return this;
 }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }

    public void dispose() {
        // Clean up listeners and resources
    }
}
