package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared artifact matching contract used by configuration sync and UI sync.
 *
 * <p>Matching order is significant and must remain:
 * exact name, case-insensitive name, output path, then base module name.
 */
public final class ArtifactMatchingUtils {

    private ArtifactMatchingUtils() {
    }

    @Nullable
    public static Artifact findMatchingArtifact(@NotNull Artifact[] artifacts,
                                                @Nullable DeploymentArtifact deploymentArtifact,
                                                @Nullable Logger logger) {
        if (deploymentArtifact == null) {
            return null;
        }

        String deploymentName = deploymentArtifact.getName();
        if (deploymentName.isEmpty()) {
            return null;
        }

        for (Artifact artifact : artifacts) {
            if (deploymentName.equals(artifact.getName())) {
                return artifact;
            }
        }

        for (Artifact artifact : artifacts) {
            if (deploymentName.equalsIgnoreCase(artifact.getName())) {
                logMatch(logger, "case-insensitive name", artifact);
                return artifact;
            }
        }

        String deploymentPath = deploymentArtifact.getPath();
        if (deploymentPath != null && !deploymentPath.isEmpty()) {
            for (Artifact artifact : artifacts) {
                String outputPath = artifact.getOutputFilePath();
                if (outputPath != null && deploymentPath.equals(outputPath)) {
                    logMatch(logger, "output path", artifact);
                    return artifact;
                }
            }
        }

        String deploymentBaseName = ContextPathUtils.extractBaseModuleName(deploymentName);
        for (Artifact artifact : artifacts) {
            if (deploymentBaseName.equals(ContextPathUtils.extractBaseModuleName(artifact.getName()))) {
                logMatch(logger, "base module name", artifact);
                return artifact;
            }
        }

        return null;
    }

    private static void logMatch(@Nullable Logger logger,
                                 @NotNull String strategy,
                                 @NotNull Artifact artifact) {
        if (logger != null) {
            logger.info("DevTomcat: Matched artifact by " + strategy + ": " + artifact.getName());
        }
    }
}
