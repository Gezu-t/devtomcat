package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactModel;
import com.intellij.packaging.artifacts.ArtifactPointer;
import com.intellij.packaging.artifacts.ArtifactPointerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * By-name handle factories shared by the run-config serializer and config
 * export/import. A live, rename-tracked handle is created when a {@link Project}
 * is available; a detached, never-resolving one is used for project-free typed
 * views (data-level validation, config export).
 *
 * <p>Modules go through {@link ModuleRef} rather than the platform's
 * {@code ModulePointer}: that interface is {@code @ApiStatus.NonExtendable}, so a
 * project-free stand-in cannot implement it. {@code ArtifactPointer} carries no
 * such annotation, so the detached artifact handle below stays a plain
 * implementation of the platform interface.
 */
public final class DeploymentPointers {

    private DeploymentPointers() {}

    /** Live pointer when {@code project != null}, detached otherwise. */
    @NotNull
    public static ArtifactPointer artifactPointer(@Nullable Project project, @NotNull String name) {
        return project != null
                ? ArtifactPointerManager.getInstance(project).createPointer(name)
                : detachedArtifactPointer(name);
    }

    /** Live ref when {@code project != null}, detached otherwise. */
    @NotNull
    public static ModuleRef moduleRef(@Nullable Project project, @NotNull String name) {
        return project != null ? ModuleRef.of(project, name) : ModuleRef.detached(name);
    }

    /** Name-only ref that never resolves — for project-free typed views. */
    @NotNull
    public static ModuleRef detachedModuleRef(@NotNull String moduleName) {
        return ModuleRef.detached(moduleName);
    }

    /** Name-only pointer that never resolves — for project-free typed views. */
    @NotNull
    public static ArtifactPointer detachedArtifactPointer(@NotNull String artifactName) {
        return new ArtifactPointer() {
            @Override public @NotNull String getArtifactName() { return artifactName; }
            @Override public @Nullable Artifact getArtifact() { return null; }
            @Override public @NotNull String getArtifactName(@NotNull ArtifactModel model) { return artifactName; }
            @Override public @Nullable Artifact findArtifact(@NotNull ArtifactModel model) { return null; }
        };
    }
}
