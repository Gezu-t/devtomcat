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
    /**
     * Last-known state captured from the persisted legacy record. Used only as a
     * fallback when the {@link ArtifactPointer} no longer resolves (artifact
     * removed, or project model not yet loaded): a live artifact always wins.
     * Keeps the legacy round trip lossless instead of blanking the stored path
     * and flipping the packaging type to war. Deliberately excluded from
     * equals/hashCode — identity is the pointer name + context path.
     */
    private final @Nullable String lastKnownPath;
    private final boolean lastKnownExploded;

    public ArtifactBackedDeployment(@NotNull ArtifactPointer artifactPointer,
                                    @NotNull String contextPath) {
        this(artifactPointer, contextPath, null, false);
    }

    public ArtifactBackedDeployment(@NotNull ArtifactPointer artifactPointer,
                                    @NotNull String contextPath,
                                    @Nullable String lastKnownPath,
                                    boolean lastKnownExploded) {
        this.artifactPointer = artifactPointer;
        this.contextPath = normaliseContextPath(contextPath);
        this.lastKnownPath = lastKnownPath;
        this.lastKnownExploded = lastKnownExploded;
    }

    /** Materialises a pointer from {@code artifactName} for deserialisation. */
    public static @NotNull ArtifactBackedDeployment ofName(@NotNull Project project,
                                                           @NotNull String artifactName,
                                                           @NotNull String contextPath) {
        return ofName(project, artifactName, contextPath, null, false);
    }

    /**
     * Materialises a pointer from {@code artifactName}, carrying the persisted
     * path / packaging as last-known fallbacks for when the pointer is unresolved.
     */
    public static @NotNull ArtifactBackedDeployment ofName(@NotNull Project project,
                                                           @NotNull String artifactName,
                                                           @NotNull String contextPath,
                                                           @Nullable String lastKnownPath,
                                                           boolean lastKnownExploded) {
        return new ArtifactBackedDeployment(
                ArtifactPointerManager.getInstance(project).createPointer(artifactName),
                contextPath, lastKnownPath, lastKnownExploded);
    }

    @Override public @NotNull DeploymentKind getKind() { return DeploymentKind.ARTIFACT; }

    @Override public @NotNull String getContextPath() { return contextPath; }

    @Override public @NotNull String getDisplayName() { return artifactPointer.getArtifactName(); }

    @Override
    public @Nullable Path getResolvedPath() {
        Artifact artifact = artifactPointer.getArtifact();
        if (artifact == null) {
            // Pointer unresolved: fall back to the last-known persisted path so
            // display / round-trip keep the stored value instead of blanking it.
            return lastKnownPath == null || lastKnownPath.isEmpty() ? null : Path.of(lastKnownPath);
        }
        String filePath = artifact.getOutputFilePath();
        return filePath == null || filePath.isEmpty() ? null : Path.of(filePath);
    }

    @Override
    public boolean isExploded() {
        Artifact artifact = artifactPointer.getArtifact();
        // Pointer unresolved: fall back to the last-known packaging so the round
        // trip does not silently flip an exploded deployment to war.
        if (artifact == null) return lastKnownExploded;
        // Exploded artifact types in IntelliJ all carry "exploded" in their type id
        // (e.g. "exploded-war", "exploded-jar").
        return artifact.getArtifactType().getId().contains("exploded");
    }

    @Override
    public boolean isValid() {
        // Check only that the Artifact is registered in the project model.
        // Do NOT also check Files.exists(getResolvedPath()): an Artifact's
        // configured output path is what IntelliJ Make populates, but it
        // commonly doesn't match where the user's actual build tool writes
        // (e.g. Artifact configured for out/artifacts/X/, but mvn/gradle
        // produces target/X/). A file-existence check here fires the
        // pre-launch "Artifact not ready" balloon on every launch for those
        // setups even after a fresh build. ModuleBackedDeployment and
        // ExternalFileDeployment can safely check file existence because
        // their paths are the actual build-tool output / user-supplied
        // absolute paths.
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
