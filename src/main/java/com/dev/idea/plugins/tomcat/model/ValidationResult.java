package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Validation Result Container
 *
 * Holds validation errors, warnings, and suggestions from configuration checks.
 *
 * @see PortConfig
 */
public class ValidationResult {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> suggestions = new ArrayList<>();

    public void addError(@NotNull String message) {
        Objects.requireNonNull(message, "Error message cannot be null");
        errors.add(message);
    }

    public void addWarning(@NotNull String message) {
        Objects.requireNonNull(message, "Warning message cannot be null");
        warnings.add(message);
    }

    public void addSuggestion(@NotNull String message) {
        Objects.requireNonNull(message, "Suggestion message cannot be null");
        suggestions.add(message);
    }

    @NotNull
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    @NotNull
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    @NotNull
    public String getErrorMessage() {
        return String.join("\n", errors);
    }

    @NotNull
    public String getWarningMessage() {
        return String.join("\n", warnings);
    }

    @NotNull
    @Override
    public String toString() {
        return "ValidationResult{" +
                "errors=" + errors.size() +
                ", warnings=" + warnings.size() +
                ", suggestions=" + suggestions.size() +
                '}';
    }
}