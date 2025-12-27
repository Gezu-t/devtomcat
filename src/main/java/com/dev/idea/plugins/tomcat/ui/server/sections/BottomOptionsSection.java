package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bottom Options Section
 * Matches Ultimate bottom checkboxes
 */
public class BottomOptionsSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(BottomOptionsSection.class);

    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;
    private JCheckBox focusToolWindowCheckBox;
    private JCheckBox allowMultipleCheckBox;
    private JCheckBox storeAsProjectFileCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));

            showThisPageCheckBox = new JCheckBox("Show this page");
            activateToolWindowCheckBox = new JCheckBox("Activate tool window");
            focusToolWindowCheckBox = new JCheckBox("Focus tool window");
            allowMultipleCheckBox = new JCheckBox("Allow multiple instances");
            storeAsProjectFileCheckBox = new JCheckBox("Store as project file");

            showThisPageCheckBox.setSelected(false);
            activateToolWindowCheckBox.setSelected(true);
            focusToolWindowCheckBox.setSelected(false);
            allowMultipleCheckBox.setSelected(false);
            storeAsProjectFileCheckBox.setSelected(false);

            panel.add(showThisPageCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(activateToolWindowCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(focusToolWindowCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(allowMultipleCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(storeAsProjectFileCheckBox);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        showThisPageCheckBox.setSelected(false);
        activateToolWindowCheckBox.setSelected(true);
        focusToolWindowCheckBox.setSelected(false);
        allowMultipleCheckBox.setSelected(false);
        storeAsProjectFileCheckBox.setSelected(false);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        showThisPageCheckBox.setSelected(false);
        activateToolWindowCheckBox.setSelected(configuration.isActivateToolWindow());
        focusToolWindowCheckBox.setSelected(configuration.isFocusToolWindow());
        allowMultipleCheckBox.setSelected(false);
        storeAsProjectFileCheckBox.setSelected(false);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        configuration.setActivateToolWindow(activateToolWindowCheckBox.isSelected());
        configuration.setFocusToolWindow(focusToolWindowCheckBox.isSelected());

        LOG.info("Bottom options - Show page: " + isShowThisPageEnabled() +
                ", Activate: " + isActivateToolWindowEnabled() +
                ", Focus: " + isFocusToolWindowEnabled());
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
        // Compare checkbox states with configuration values
        if (!Objects.equals(activateToolWindowCheckBox.isSelected(), config.isActivateToolWindow())) {
            return true;
        }
        if (!Objects.equals(focusToolWindowCheckBox.isSelected(), config.isFocusToolWindow())) {
            return true;
        }
        // Other checkboxes (showThisPage, allowMultiple, storeAsProjectFile) are not currently stored in configuration
        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        // Bottom options section has no validation requirements - checkboxes are always valid
        return Collections.emptyList();
    }

    public boolean isShowThisPageEnabled() {
        return showThisPageCheckBox.isSelected();
    }

    public boolean isActivateToolWindowEnabled() {
        return activateToolWindowCheckBox.isSelected();
    }

    public boolean isFocusToolWindowEnabled() {
        return focusToolWindowCheckBox.isSelected();
    }
}