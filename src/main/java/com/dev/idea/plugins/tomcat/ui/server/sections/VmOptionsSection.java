package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VM Options Section
 * Handles VM options textarea
 */
public class VmOptionsSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(VmOptionsSection.class);

    private JTextField vmOptionsField;
    private JButton expandButton;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(2, 0, 2, 0));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(4, 0, 4, 4);
            gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel("VM options:"), gbc);

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(4, 4, 4, 4);
            vmOptionsField = new JTextField();
            vmOptionsField.setToolTipText("e.g., -Xmx512m -Xms256m -XX:+UseG1GC");
            panel.add(vmOptionsField, gbc);

            gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(4, 0, 4, 0);
            expandButton = new JButton("...");
            expandButton.setPreferredSize(new Dimension(28, 26));
            expandButton.addActionListener(e -> showExpandedEditor());
            panel.add(expandButton, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        String vmOptions = configuration.getVmOptions();
        vmOptionsField.setText(vmOptions != null ? vmOptions : "");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String vmOptions = vmOptionsField.getText().trim();
        configuration.setVmOptions(vmOptions.isEmpty() ? null : vmOptions);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        // Compare the current VM options text with the configuration value
        String configVmOptions = config.getVmOptions();
        String currentVmOptions = vmOptionsField.getText().trim();

        // If both are empty/null, they're equal
        if ((configVmOptions == null || configVmOptions.isEmpty()) &&
            currentVmOptions.isEmpty()) {
            return false;
        }

        // Compare the actual values
        return !Objects.equals(
            configVmOptions != null ? configVmOptions : "",
            currentVmOptions
        );
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        // VM options are free-form text - no validation required
        // Invalid VM options will be caught by the JVM at runtime
        return Collections.emptyList();
    }

    public String getVmOptions() {
        return vmOptionsField.getText().trim();
    }

    private void showExpandedEditor() {
        JTextArea area = new JTextArea(10, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText(vmOptionsField.getText());

        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setPreferredSize(new Dimension(500, 200));

        int result = JOptionPane.showConfirmDialog(
                panel,
                scrollPane,
                "Edit VM Options",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            vmOptionsField.setText(area.getText().trim());
        }
    }
}
