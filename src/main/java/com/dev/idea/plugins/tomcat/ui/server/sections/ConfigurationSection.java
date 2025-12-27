package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

/**
 * Base interface for configuration sections
 * Follows Single Responsibility Principle
 */
public interface ConfigurationSection {

    boolean isModified(@NotNull TomcatRunConfiguration config);

    List<ValidationInfo> validateSettings();

    /**
     * Create the UI panel for this section
     */
    @NotNull
    JPanel createPanel();

    /**
     * Load initial configuration (dropdowns, etc.)
     */
    void loadConfiguration();

    /**
     * Reset UI from configuration object
     */
    void resetFrom(@NotNull TomcatRunConfiguration configuration);

    /**
     * Apply UI values to configuration object
     */
    void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException;

    /**
     * Validate this section
     */
    boolean isValid();

    /**
     * Whether this section should fill vertically
     */
    default boolean shouldFillVertically() {
        return false;
    }
}