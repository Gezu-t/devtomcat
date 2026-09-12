package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.TomcatConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Archive form of a {@link Deployment}, orthogonal to {@link Deployment#isExploded()}.
 * {@link #UNKNOWN} when nothing established it; the display layer then names no archive.
 */
public enum DeploymentArchive {

    WAR,
    EAR,
    UNKNOWN;

    /** Colon-notation suffix, or null for UNKNOWN — callers then render the bare name. */
    public @Nullable String displaySuffix(boolean exploded) {
        return switch (this) {
            case WAR -> exploded
                    ? TomcatConstants.ARTIFACT_SUFFIX_WAR_EXPLODED
                    : TomcatConstants.ARTIFACT_SUFFIX_WAR;
            case EAR -> exploded
                    ? TomcatConstants.ARTIFACT_SUFFIX_EAR_EXPLODED
                    : TomcatConstants.ARTIFACT_SUFFIX_EAR;
            case UNKNOWN -> null;
        };
    }

    /**
     * The archive a name's extension spells out. An extension-less directory is UNKNOWN:
     * an exploded tree carries no marker saying which archive it came from.
     * Pure string work — safe on the EDT.
     */
    public static @NotNull DeploymentArchive ofFileName(@Nullable String fileName) {
        if (fileName == null) return UNKNOWN;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".war")) return WAR;
        if (lower.endsWith(".ear")) return EAR;
        return UNKNOWN;
    }

    /** {@link #ofFileName} over a path's last segment. */
    public static @NotNull DeploymentArchive ofPath(@Nullable Path path) {
        if (path == null) return UNKNOWN;
        Path name = path.getFileName();
        return ofFileName(name == null ? path.toString() : name.toString());
    }
}
