package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactPointer;
import com.intellij.packaging.artifacts.ArtifactPointerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/** Deployment backed by an IntelliJ Artifact. Path / packaging / display name are read off the live {@link Artifact} via the pointer — context path is the only user override we persist. */
public final class ArtifactBackedDeployment implements Deployment {

    private final @NotNull ArtifactPointer artifactPointer;
    private final @NotNull String contextPath;

    public ArtifactBackedDeployment(@NotNull ArtifactPointer artifactPointer,
                                    @NotNull String contextPath) {
        this.artifactPointer = artifactPointer;
        this.contextPath = normaliseContextPath(contextPath);
    }

    /** Materialises a pointer from {@code artifactName} for deserialisation. */
    public static @NotNull ArtifactBackedDeployment ofName(@NotNull Project project,
                                                           @NotNull String artifactName,
                                                           @NotNull String contextPath) {
        return new ArtifactBackedDeployment(
                ArtifactPointerManager.getInstance(project).createPointer(artifactName),
                contextPath);
    }

    @Override public @NotNull DeploymentKind getKind() { return DeploymentKind.ARTIFACT; }

    @Override public @NotNull String getContextPath() { return contextPath; }

    @Override public @NotNull String getDisplayName() { return artifactPointer.getArtifactName(); }

    @Override
    public @Nullable Path getResolvedPath(@NotNull Project project) {
        Artifact artifact = artifactPointer.getArtifact();
        if (artifact == null) return null;
        String filePath = artifact.getOutputFilePath();
        return filePath == null || filePath.isEmpty() ? null : Path.of(filePath);
    }

    @Override
    public boolean isExploded() {
        Artifact artifact = artifactPointer.getArtifact();
        if (artifact == null) return false;
        // Exploded artifact types in IntelliJ all carry "exploded" in
        // their type id (e.g. "exploded-war", "exploded-jar").
        return artifact.getArtifactType().getId().contains("exploded");
    }

    @Override
    public boolean isValid(@NotNull Project project) {
        return artifactPointer.getArtifact() != null;
    }

    public @NotNull ArtifactPointer getArtifactPointer() { return artifactPointer; }

    public @NotNull String getArtifactName() { return artifactPointer.getArtifactName(); }

    private static String normaliseContextPath(@NotNull String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "/";
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArtifactBackedDeployment that)) return false;
        return artifactPointer.getArtifactName().equals(that.artifactPointer.getArtifactName())
                && contextPath.equals(that.contextPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactPointer.getArtifactName(), contextPath);
    }

    @Override
    public String toString() {
        return "ArtifactBackedDeployment{artifact=" + artifactPointer.getArtifactName()
                + ", context=" + contextPath + '}';
    }
}
