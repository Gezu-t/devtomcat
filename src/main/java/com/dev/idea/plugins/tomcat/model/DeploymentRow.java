package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable Deployment-tab row over the immutable typed {@link Deployment} core.
 * Context path (and packaging, for EXTERNAL rows) is editable in place; identity/path
 * reads delegate to the wrapped core so platform pointers keep rename-tracking.
 * Rows are compared by identity ({@code ==}) — duplicate-context checks and in-place
 * edits rely on that, so never deduplicate rows by value.
 */
public final class DeploymentRow {

    private final @NotNull Deployment source;
    private @NotNull String contextPath;
    /** Editable packaging — consulted only for {@link DeploymentKind#EXTERNAL} rows. */
    private boolean exploded;

    private DeploymentRow(@NotNull Deployment source) {
        this.source = Objects.requireNonNull(source);
        this.contextPath = source.getContextPath();
        this.exploded = source.isExploded();
    }

    public static @NotNull DeploymentRow of(@NotNull Deployment deployment) {
        return new DeploymentRow(deployment);
    }

    public @NotNull DeploymentKind getKind() {
        return source.getKind();
    }

    public @NotNull String getDisplayName() {
        return source.getDisplayName();
    }

    public @Nullable Path getResolvedPath() {
        return source.getResolvedPath();
    }

    public @NotNull String getContextPath() {
        return contextPath;
    }

    public boolean isExploded() {
        // Artifact/module packaging is dictated by the source; only external is edited here.
        return getKind() == DeploymentKind.EXTERNAL ? exploded : source.isExploded();
    }

    /** From the core; unaffected by the row's exploded edit — the two axes are independent. */
    public @NotNull DeploymentArchive getArchive() {
        return source.getArchive();
    }

    public boolean isValid() {
        return source.isValid();
    }

    public void setContextPath(@Nullable String contextPath) {
        this.contextPath = ContextPathUtils.normalizeContextPath(contextPath);
    }

    /** No-op for non-EXTERNAL rows — the edit dialog only enables packaging for external sources. */
    public void setExploded(boolean exploded) {
        if (getKind() == DeploymentKind.EXTERNAL) {
            this.exploded = exploded;
        }
    }

    /** Materializes a typed deployment with the row's edits applied. */
    public @NotNull Deployment toDeployment() {
        Deployment out = source.withContextPath(contextPath);
        if (out instanceof ExternalFileDeployment external) {
            return external.withExploded(exploded);
        }
        return out;
    }

    @Override
    public String toString() {
        return "DeploymentRow{" + source.getDisplayName() + ", context=" + contextPath + '}';
    }
}
