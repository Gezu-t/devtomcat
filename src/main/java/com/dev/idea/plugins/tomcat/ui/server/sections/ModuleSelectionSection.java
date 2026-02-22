package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Module Selection Section – UI only.
 * No longer persists a module into {@link TomcatRunConfiguration},
 * because the run-configuration class no longer stores that field.
 */
public class ModuleSelectionSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(ModuleSelectionSection.class);

    private final Project project;
    private ComboBox<Module> moduleCombo;
    private JPanel panel;

    public ModuleSelectionSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel != null) return panel;

        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Module Configuration"));

        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(8);

        panel.add(new JLabel("Module:"), g);

        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(8, 15, 8, 8);

        moduleCombo = new ComboBox<>();
        moduleCombo.setRenderer(new ModuleRenderer());
        panel.add(moduleCombo, g);

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

    @Override
    public void loadConfiguration() {
        moduleCombo.removeAllItems();
        moduleCombo.addItem(null); // "<none>"

        Module selectedModule = null;

        for (Module m : ModuleManager.getInstance(project).getModules()) {
            moduleCombo.addItem(m);

            if (selectedModule == null && TomcatModuleUtils.isWebModule(m)) {
                selectedModule = m;
            }
        }

        if (selectedModule != null) {
            moduleCombo.setSelectedItem(selectedModule);
            LOG.debug("DevTomcat: Auto-selected web module: " + selectedModule.getName());
        } else {
            LOG.debug("DevTomcat: No web module found for auto-selection");
        }
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    public Module getSelectedModule() {
        return (Module) moduleCombo.getSelectedItem();
    }

    private static class ModuleRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Module) {
                Module module = (Module) value;
                setText(module.getName());
                setToolTipText("Module: " + module.getName());
            } else {
                setText("<No module>");
                setToolTipText("No module selected");
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }
    }
}