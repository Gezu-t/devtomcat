package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Base interface for configuration sections
 * Follows Single Responsibility Principle
 */
public interface ConfigurationSection {

    /**
     * Returns the minimum width for the label column so that all sections align consistently.
     * Computed from the widest label ("On frame deactivation:") plus padding.
     */
    static int getLabelColumnWidth() {
        JLabel measure = new JLabel("On frame deactivation:");
        return measure.getPreferredSize().width + JBUI.scale(12);
    }

    /**
     * Creates a GridBagLayout with a consistent label column width for use by sections.
     */
    static GridBagLayout createAlignedGridBagLayout() {
        GridBagLayout layout = new GridBagLayout();
        layout.columnWidths = new int[]{getLabelColumnWidth()};
        return layout;
    }

    boolean isModified(@NotNull TomcatRunConfiguration config);

    List<ValidationInfo> validateSettings();

    @NotNull
    JPanel createPanel();

    void loadConfiguration();

    void resetFrom(@NotNull TomcatRunConfiguration configuration);

    void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException;

    default boolean isConfigurationValid() {
        return validateSettings().isEmpty();
    }

    default boolean shouldFillVertically() {
        return false;
    }
}