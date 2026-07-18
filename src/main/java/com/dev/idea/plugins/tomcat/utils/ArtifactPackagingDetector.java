package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Locale;

/**
 * Single packaging policy (exploded vs WAR) for a live IntelliJ {@link Artifact}.
 * The type ID alone is only authoritative when the platform provides a web-typed
 * ID ({@code "war"} / {@code "exploded-war"}); Community Edition projects carry
 * generic IDs like {@code "plain"} or {@code "jar"} that say nothing about
 * packaging, so the policy falls back to name hints and the output-path shape.
 *
 * <p>Every consumer of live-artifact packaging — add-time seeding, the
 * deployment model's {@code isExploded()}, and the reference refresher's drift
 * reconciliation — must go through this method. A weaker check in any one of
 * them would let a refresh overwrite the stronger persisted verdict.
 */
public final class ArtifactPackagingDetector {

    private static final Logger LOG = Logger.getInstance(ArtifactPackagingDetector.class);

    private ArtifactPackagingDetector() {}

    /**
     * Resolves the packaging (exploded vs WAR) for an IntelliJ Artifact.
     * Checks the artifact type ID first (authoritative when the platform provides
     * a web-typed ID), then falls back to checking the artifact name and output
     * path — needed when the project only carries generic type IDs like
     * {@code "plain"} or {@code "jar"}.
     */
    public static boolean resolveExplodedPackaging(@NotNull Artifact artifact) {
        // 1. Check IntelliJ artifact type ID (authoritative when web-typed)
        try {
            String typeId = artifact.getArtifactType().getId().toLowerCase(Locale.ROOT);
            if (typeId.contains("exploded")) return true;
            if (typeId.contains("war")) return false;
        } catch (RuntimeException e) {
            LOG.debug("Error resolving artifact type for deployment '" + artifact.getName() + "'", e);
        }

        // 2. Check artifact name for type hints (CE users often follow naming conventions)
        String name = artifact.getName().toLowerCase(Locale.ROOT);
        if (name.contains("exploded")) return true;
        if (name.endsWith("_war") || name.endsWith(".war") || name.endsWith(":war")) {
            return false;
        }

        // 3. Check output path — directory = exploded, file = packaged
        String outputPath = artifact.getOutputFilePath();
        if (outputPath != null) {
            File outputFile = new File(outputPath);
            if (outputFile.isDirectory()) return true;
            if (outputPath.toLowerCase(Locale.ROOT).endsWith(".war")) return false;
        }

        // Default: treat as exploded (better for local development — supports hot reload)
        return true;
    }
}
