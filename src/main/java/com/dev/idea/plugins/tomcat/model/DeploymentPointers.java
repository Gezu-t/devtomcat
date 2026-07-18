package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModulePointer;
import com.intellij.openapi.module.ModulePointerManager;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactModel;
import com.intellij.packaging.artifacts.ArtifactPointer;
import com.intellij.packaging.artifacts.ArtifactPointerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * By-name pointer factories shared by the run-config serializer and config
 * export/import. A live pointer (rename-tracked by the platform) is created
 * when a {@link Project} is available; a detached, never-resolving pointer is
 * used for project-free typed views (data-level validation, config export).
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

    /** Live pointer when {@code project != null}, detached otherwise. */
    @NotNull
    public static ModulePointer modulePointer(@Nullable Project project, @NotNull String name) {
        return project != null
                ? ModulePointerManager.getInstance(project).create(name)
                : detachedModulePointer(name);
    }

    /** Name-only pointer that never resolves — for project-free typed views. */
    @NotNull
    public static ModulePointer detachedModulePointer(@NotNull String moduleName) {
        return new ModulePointer() {
            @Override public @Nullable Module getModule() { return null; }
            @Override public @NotNull String getModuleName() { return moduleName; }
        };
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
