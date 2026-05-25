package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * One deployment line in a Tomcat run configuration. Three concrete shapes — sealed so dispatch is exhaustive.
 *
 * <p>Built from {@code ArtifactPointer} / {@code ModulePointer} primitives — the artifact↔module link is platform-maintained instead of resolved by string matching.
 */
public sealed interface Deployment
        permits ArtifactBackedDeployment, ModuleBackedDeployment, ExternalFileDeployment {

    @NotNull DeploymentKind getKind();

    @NotNull String getContextPath();

    @NotNull String getDisplayName();

    @Nullable Path getResolvedPath();

    boolean isExploded();

    boolean isValid();
}
