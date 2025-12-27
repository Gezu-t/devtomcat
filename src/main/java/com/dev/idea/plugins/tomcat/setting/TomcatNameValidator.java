package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;

/**
 * Functional interface for validating Tomcat server names
 */
@FunctionalInterface
public interface TomcatNameValidator {
    /**
     * Validate the given value
     *
     * @param name The name to validate
     * @throws ConfigurationException if validation fails
     */
    void validate(@NotNull String name) throws ConfigurationException;
}