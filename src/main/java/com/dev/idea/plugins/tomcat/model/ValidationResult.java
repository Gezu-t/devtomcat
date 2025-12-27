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

    /**
     * Add an error message.
     *
     * @param message the error message (cannot be null)
     */
    public void addError(@NotNull String message) {
        Objects.requireNonNull(message, "Error message cannot be null");
        errors.add(message);
    }

    /**
     * Add a warning message.
     *
     * @param message the warning message (cannot be null)
     */
    public void addWarning(@NotNull String message) {
        Objects.requireNonNull(message, "Warning message cannot be null");
        warnings.add(message);
    }

    /**
     * Add a suggestion message.
     *
     * @param message the suggestion message (cannot be null)
     */
    public void addSuggestion(@NotNull String message) {
        Objects.requireNonNull(message, "Suggestion message cannot be null");
        suggestions.add(message);
    }

    /**
     * Get all error messages.
     *
     * @return unmodifiable list of errors (never null)
     */
    @NotNull
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /**
     * Get all warning messages.
     *
     * @return unmodifiable list of warnings (never null)
     */
    @NotNull
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Get all suggestion messages.
     *
     * @return unmodifiable list of suggestions (never null)
     */
    @NotNull
    public List<String> getSuggestions() {
        return Collections.unmodifiableList(suggestions);
    }

    /**
     * Get count of errors.
     *
     * @return error count
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Get count of warnings.
     *
     * @return warning count
     */
    public int getWarningCount() {
        return warnings.size();
    }

    /**
     * Get count of suggestions.
     *
     * @return suggestion count
     */
    public int getSuggestionCount() {
        return suggestions.size();
    }

    /**
     * Check if validation has any errors.
     *
     * @return true if errors exist
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Check if validation has any warnings.
     *
     * @return true if warnings exist
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * Check if validation has any suggestions.
     *
     * @return true if suggestions exist
     */
    public boolean hasSuggestions() {
        return !suggestions.isEmpty();
    }

    /**
     * Check if validation is completely valid.
     *
     * @return true if no errors (warnings/suggestions allowed)
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Get detailed validation summary.
     *
     * @return formatted summary string (never null)
     */
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