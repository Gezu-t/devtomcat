package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Bottom Options Section - Ultimate Match
 * Matches EXACTLY the Ultimate screenshot bottom checkboxes:
 * ☐ Show this page  ☑ Activate tool window  ☐ Focus tool window
 */
public class BottomOptionsSection implements ConfigurationSection {

    private JCheckBox showThisPageCheckBox;
    private JCheckBox activateToolWindowCheckBox;
    private JCheckBox focusToolWindowCheckBox;
    private JPanel panel;

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));

            // Exact checkboxes from Ultimate screenshot
            showThisPageCheckBox = new JCheckBox("Show this page");
            activateToolWindowCheckBox = new JCheckBox("Activate tool window");
            focusToolWindowCheckBox = new JCheckBox("Focus tool window");

            // Set default states to match Ultimate screenshot
            showThisPageCheckBox.setSelected(false);
            activateToolWindowCheckBox.setSelected(true); // Checked in screenshot
            focusToolWindowCheckBox.setSelected(false);

            // Add with proper spacing
            panel.add(showThisPageCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(activateToolWindowCheckBox);
            panel.add(Box.createHorizontalStrut(25));
            panel.add(focusToolWindowCheckBox);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        // Set defaults to match Ultimate screenshot
        showThisPageCheckBox.setSelected(false);
        activateToolWindowCheckBox.setSelected(true);
        focusToolWindowCheckBox.setSelected(false);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        // Reset to Ultimate defaults
        showThisPageCheckBox.setSelected(false);
        activateToolWindowCheckBox.setSelected(true);
        focusToolWindowCheckBox.setSelected(false);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // These options are typically handled at the IDE level for tool window behavior
        System.out.println("DevTomcat: Bottom options - Show page: " + isShowThisPageEnabled() +
                ", Activate: " + isActivateToolWindowEnabled() +
                ", Focus: " + isFocusToolWindowEnabled());
    }

    @Override
    public boolean isValid() {
        return true; // Bottom options are always valid
    }

    @Override
    public boolean shouldFillVertically() {
        return false; // Bottom section doesn't need vertical space
    }

    // Getters for external access
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