package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a deployment came from. The permanent provenance vocabulary used by the
 * serializer to read/write the legacy {@code source} attribute. Orthogonal to
 * packaging (war vs exploded).
 *
 * <ul>
 *   <li>{@link #INTELLIJ_ARTIFACT}: backed by an entry in IntelliJ's Artifacts
 *       configuration. The artifact's output path is produced by Before Launch
 *       compilation; rename tracking updates stale references via
 *       {@code ArtifactReferenceRefresher}.</li>
 *   <li>{@link #AUTO_DETECTED}: derived by {@code ProjectArtifactDetector}
 *       from web-module layout or build output scanning. Treated like an
 *       IntelliJ artifact for rename tracking because the source module is
 *       still part of the project.</li>
 *   <li>{@link #EXTERNAL}: a file or directory the user chose explicitly via
 *       "External Source...". Lives outside the project artifact model —
 *       must NOT be rename-tracked against IntelliJ's ArtifactManager, and
 *       must NOT be flagged as orphaned when it doesn't match a platform
 *       artifact name.</li>
 * </ul>
 */
public enum DeploymentSource {
    INTELLIJ_ARTIFACT,
    AUTO_DETECTED,
    EXTERNAL;

    /**
     * Resolves a serialized source name to a {@link DeploymentSource}. Returns
     * {@link #INTELLIJ_ARTIFACT} for {@code null}, unknown, or absent values
     * so legacy configs (written before the source field existed) default to
     * the pre-existing behaviour.
     */
    @NotNull
    public static DeploymentSource fromSerialized(@Nullable String name) {
        if (name == null) return INTELLIJ_ARTIFACT;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return INTELLIJ_ARTIFACT;
        }
    }
}
