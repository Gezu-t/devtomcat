package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.fields.ExpandableTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;

/**
 * VM Options Section
 * Uses IntelliJ's {@link ExpandableTextField} for proper handling of long
 * command-line text (built-in expand button, no horizontal overflow).
 */
public class VmOptionsSection implements ConfigurationSection {

    private ExpandableTextField vmOptionsEditor;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(0));

            GridBagConstraints gbc = new GridBagConstraints();
            vmOptionsEditor = new ExpandableTextField();
            vmOptionsEditor.setTitle("VM Options");
            ConfigurationSection.addLabelAndField(panel, gbc, 0, new JBLabel("VM options:"), vmOptionsEditor);
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
        String vmOptions = vmOptionsEditor.getText().trim().replaceAll("\\s+", " ");
        configuration.setVmOptions(vmOptions.isEmpty() ? null : vmOptions);
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        // applyTo() saves null when the field is empty, so treat null and "" as equivalent.
        String saved  = config.getVmOptions() != null ? config.getVmOptions() : "";
        String current = vmOptionsEditor.getText().trim().replaceAll("\\s+", " ");
        return !saved.equals(current);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }
}
