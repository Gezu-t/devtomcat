package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.PluginUtils;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Module Selection Section – UI only.
 * No longer persists a module into {@link TomcatRunConfiguration},
 * because the run-configuration class no longer stores that field.
 */
public class ModuleSelectionSection implements ConfigurationSection {

    private final Project project;
    private JComboBox<Module> moduleCombo;
    private JPanel panel;

    public ModuleSelectionSection(Project project) {
        this.project = project;
    }

    // ------------------------------------------------------------------ UI

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel != null) return panel;

        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Module Configuration"));

        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(8);

        // label
        panel.add(new JLabel("Module:"), g);

        // combo box
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(8, 15, 8, 8);

        moduleCombo = new JComboBox<>();
        moduleCombo.setRenderer(new ModuleRenderer());
        panel.add(moduleCombo, g);

        // hint
        g.gridx = 0;
        g.gridy = 1;
        g.gridwidth = 2;
        g.insets = JBUI.insetsBottom(8);
        JLabel hint = new JLabel("<html><i>Select the module that contains your web application.</i></html>");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(hint, g);

        return panel;
    }

    // ------------------------------------------------------------------ data-binding

    @Override
    public void loadConfiguration() {
        moduleCombo.removeAllItems();
        moduleCombo.addItem(null); // “<none>”

        for (Module m : ModuleManager.getInstance(project).getModules()) {
            moduleCombo.addItem(m);
        }

        // Pre-select a sensible default
        Module guess = PluginUtils.guessModule(project);
        moduleCombo.setSelectedItem(guess);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // configuration no longer stores a module, so keep whatever is already chosen
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // nothing to persist – this section is UI-only now
    }

    @Override
    public boolean isValid() {
        return true; // module selection optional
    }

    // ------------------------------------------------------------------ helpers

    public Module getSelectedModule() {
        return (Module) moduleCombo.getSelectedItem();
    }

    private static class ModuleRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Module m) {
                setText(m.getName());
                setToolTipText("Module: " + m.getName());
            } else {
                setText("<No module>");
                setToolTipText("No module selected");
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }
    }
}
