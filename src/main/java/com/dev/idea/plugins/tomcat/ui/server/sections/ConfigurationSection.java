package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base interface for configuration sections
 * Follows Single Responsibility Principle
 */
public interface ConfigurationSection {

    /** Reference text used to size the label column. Widest label across all sections. */
    String LABEL_COLUMN_REFERENCE = "On frame deactivation:";

    /**
     * Lazy single-cell cache for the label-column width. Interface fields must be final,
     * so the mutable cell goes here. {@code -1} means "not yet computed".
     */
    AtomicInteger LABEL_COLUMN_WIDTH = new AtomicInteger(-1);

    /**
     * Returns the minimum width for the label column so that all sections align consistently.
     * Computed once from the reference label's preferred size; subsequent calls hit the cache.
     */
    static int getLabelColumnWidth() {
        int cached = LABEL_COLUMN_WIDTH.get();
        if (cached >= 0) return cached;
        int width = new JBLabel(LABEL_COLUMN_REFERENCE).getPreferredSize().width + JBUI.scale(12);
        LABEL_COLUMN_WIDTH.set(width);
        return width;
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
        // Associate the label with its field so screen readers announce the field's
        // name and Alt+mnemonic focus jumps land on the field, not the label.
        if (label instanceof JLabel jLabel) {
            jLabel.setLabelFor(field);
        }
    }

    /**
     * Adds a trailing button in column 2 of the current row (use after
     * {@link #addLabelAndField}). The button sizes naturally from the LAF, callers
     * must not apply {@code setPreferredSize}.
     *
     * @param panel    the target panel (must use {@link #createAlignedGridBagLayout()})
     * @param gbc      shared constraint object, mutated in place
     * @param label    button text
     * @param onClick  action listener invoked when the button is pressed
     * @return the created button so callers can store or further configure it
     */
    static JButton addTrailingButton(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                      @NotNull String label, @NotNull ActionListener onClick) {
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2, 0, 2, 0);
        JButton button = new JButton(label);
        button.addActionListener(onClick);
        panel.add(button, gbc);
        return button;
    }

    /** Backwards-compatible shorthand for the common "Configure..." button. */
    static JButton addConfigureButton(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                       @NotNull ActionListener onClick) {
        return addTrailingButton(panel, gbc, "Configure...", onClick);
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

    /** Releases listeners and other resources when the configuration editor is disposed. */
    default void dispose() {}
}