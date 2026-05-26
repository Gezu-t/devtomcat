package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a {@link Deployment} to the IntelliJ-platform {@link Artifact} it
 * points at — used by the configuration-editor and run-configuration sync
 * paths to wire {@code BuildArtifactsBeforeRunTask}.
 *
 * <p>With the typed deployment model the resolution collapses to a single
 * pointer dereference: {@link ArtifactBackedDeployment} owns an
 * {@code ArtifactPointer} the platform updates on rename / delete; the other
 * two subtypes ({@code ModuleBackedDeployment}, {@code ExternalFileDeployment})
 * are not backed by project artifacts and resolve to {@code null}.
 *
 * <p>This replaces a 4-strategy string-based fuzzy matcher (exact name,
 * case-insensitive name, output-path, base-module-name) plus an
 * EXTERNAL-source provenance guard. All of those concerns are now structural
 * properties of the typed hierarchy and don't need runtime heuristics.
 */
public final class ArtifactMatchingUtils {

    private ArtifactMatchingUtils() {}

    /**
     * Returns the platform {@link Artifact} backing this deployment, or
     * {@code null} for module-backed / external deployments and for stale
     * artifact pointers (the artifact has been deleted in Project Structure).
     */
    @Nullable
    public static Artifact findMatching(@NotNull Deployment deployment) {
        return deployment instanceof ArtifactBackedDeployment a
                ? a.getArtifactPointer().getArtifact()
                : null;
    }
}
