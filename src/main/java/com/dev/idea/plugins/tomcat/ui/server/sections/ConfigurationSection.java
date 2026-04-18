package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
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

    /**
     * Adds a standard label+field row to {@code panel}: label fixed at column 0,
     * field expanding horizontally at column 1.
     *
     * <p>Fully resets all GBC state before each column so callers never need to
     * track prior constraint values.
     *
     * @param panel  the target panel (must use {@link #createAlignedGridBagLayout()})
     * @param gbc    shared constraint object — mutated in place
     * @param row    {@code gridy} for this row
     * @param label  label component (typically a {@link com.intellij.ui.components.JBLabel})
     * @param field  field component (ComboBox, JBTextField, etc.)
     */
    static void addLabelAndField(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                  int row, @NotNull JComponent label, @NotNull JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2, 0, 2, 4);
        panel.add(label, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 4, 2, 8);
        panel.add(field, gbc);
    }

    /**
     * Adds a trailing "Configure..." button in column 2 of the current row (use after
     * {@link #addLabelAndField}). The button sizes naturally from the LAF — callers
     * must not apply {@code setPreferredSize}.
     *
     * @param panel    the target panel (must use {@link #createAlignedGridBagLayout()})
     * @param gbc      shared constraint object — mutated in place
     * @param onClick  action listener invoked when the button is pressed
     * @return the created button so callers can store or further configure it
     */
    static JButton addConfigureButton(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                       @NotNull ActionListener onClick) {
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2, 0, 2, 0);
        JButton button = new JButton("Configure...");
        button.addActionListener(onClick);
        panel.add(button, gbc);
        return button;
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

    /** Releases listeners and other resources when the configuration editor is disposed. */
    default void dispose() {}
}