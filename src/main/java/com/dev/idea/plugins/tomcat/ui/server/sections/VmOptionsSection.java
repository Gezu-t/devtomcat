package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VM Options Section
 * Uses IntelliJ's {@link RawCommandLineEditor} for proper handling of long
 * command-line text (built-in expand button, no horizontal overflow).
 */
public class VmOptionsSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(VmOptionsSection.class);

    private RawCommandLineEditor vmOptionsEditor;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(0));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
            panel.add(new JBLabel("VM options:"), gbc);

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(2, 4, 2, 8);
            vmOptionsEditor = new RawCommandLineEditor();
            vmOptionsEditor.setDialogCaption("VM Options");
            panel.add(vmOptionsEditor, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        String vmOptions = configuration.getVmOptions();
        vmOptionsEditor.setText(vmOptions != null ? vmOptions : "");
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        String vmOptions = vmOptionsEditor.getText().trim();
        configuration.setVmOptions(vmOptions.isEmpty() ? null : vmOptions);
    }

    @Override
    public boolean isConfigurationValid() {
        return true;
    }

    @Override
    public boolean shouldFillVertically() {
        return false;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        return !Objects.equals(config.getVmOptions(), vmOptionsEditor.getText().trim());
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    public String getVmOptions() {
        return vmOptionsEditor.getText().trim();
    }
}
