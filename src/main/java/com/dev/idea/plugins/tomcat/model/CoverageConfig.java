package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Code coverage configuration for include/exclude package patterns.
 */
public class CoverageConfig implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull private List<String> includePatterns = new ArrayList<>();
    @NotNull private List<String> excludePatterns = new ArrayList<>();

    public CoverageConfig() {}

    @NotNull
    public List<String> getIncludePatterns() {
        return new ArrayList<>(includePatterns);
    }

    public void setIncludePatterns(@NotNull List<String> patterns) {
        this.includePatterns = new ArrayList<>(Objects.requireNonNull(patterns));
    }

    @NotNull
    public List<String> getExcludePatterns() {
        return new ArrayList<>(excludePatterns);
    }

    public void setExcludePatterns(@NotNull List<String> patterns) {
        this.excludePatterns = new ArrayList<>(Objects.requireNonNull(patterns));
    }

    @NotNull
    @Override
    public CoverageConfig clone() {
        try {
            CoverageConfig c = (CoverageConfig) super.clone();
            c.includePatterns = new ArrayList<>(this.includePatterns);
            c.excludePatterns = new ArrayList<>(this.excludePatterns);
            return c;
        } catch (CloneNotSupportedException e) {
            CoverageConfig c = new CoverageConfig();
            c.includePatterns = new ArrayList<>(this.includePatterns);
            c.excludePatterns = new ArrayList<>(this.excludePatterns);
            return c;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CoverageConfig that = (CoverageConfig) o;
        return Objects.equals(includePatterns, that.includePatterns)
                && Objects.equals(excludePatterns, that.excludePatterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includePatterns, excludePatterns);
    }

    @NotNull
    @Override
    public String toString() {
        return "CoverageConfig{include=" + includePatterns.size() + ", exclude=" + excludePatterns.size() + "}";
    }
}
