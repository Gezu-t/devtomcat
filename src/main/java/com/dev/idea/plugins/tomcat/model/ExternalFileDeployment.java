package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Deployment pointing at an absolute path the user picked manually.
 * No project-model link; sync is skipped silently by design.
 */
public final class ExternalFileDeployment implements Deployment {

    private final @NotNull Path externalPath;
    private final @NotNull String contextPath;
    private final boolean exploded;

    public ExternalFileDeployment(@NotNull Path externalPath,
                                  @NotNull String contextPath,
                                  boolean exploded) {
        this.externalPath = externalPath;
        this.contextPath = normaliseContextPath(contextPath);
        this.exploded = exploded;
    }

    @Override
    public @NotNull DeploymentKind getKind() {
        return DeploymentKind.EXTERNAL;
    }

    @Override
    public @NotNull String getContextPath() {
        return contextPath;
    }

    @Override
    public @NotNull String getDisplayName() {
        Path name = externalPath.getFileName();
        return name == null ? externalPath.toString() : name.toString();
    }

    @Override
    public @Nullable Path getResolvedPath() {
        return externalPath;
    }

    @Override
    public boolean isExploded() {
        return exploded;
    }

    @Override
    public boolean isValid() {
        return Files.exists(externalPath);
    }

    public @NotNull Path getExternalPath() {
        return externalPath;
    }

    /**
     * Folds "" / null / no-slash inputs to a leading-slash form; "" → "/".
     */
    private static String normaliseContextPath(@NotNull String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "/";
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExternalFileDeployment that)) return false;
        return exploded == that.exploded
                && externalPath.equals(that.externalPath)
                && contextPath.equals(that.contextPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(externalPath, contextPath, exploded);
    }

    @Override
    public String toString() {
        return "ExternalFileDeployment{path=" + externalPath
                + ", context=" + contextPath
                + ", exploded=" + exploded + '}';
    }
}
