package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Deployment Artifact for Tomcat.
 *
 * Represents a deployable artifact (WAR, exploded directory, etc.)
 * with path, name, type, and context path information.
 *
 * Author: Dev Tomcat Team
 * Project: DevTomcat Plugin
 */
public class DeploymentArtifact implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TYPE_WAR = "war";
    public static final String TYPE_EXPLODED = "exploded";
    /**
     * @deprecated Packaging type and source provenance are orthogonal.
     * Retained as a legacy input only — {@link #setType(String)} silently maps
     * this value to {@link Source#EXTERNAL} + {@link #TYPE_WAR} for backward
     * compatibility with configs written before the {@code source} field existed.
     * New code should set {@code type} to {@link #TYPE_WAR} / {@link #TYPE_EXPLODED}
     * and {@code source} to {@link Source#EXTERNAL} explicitly.
     */
    @Deprecated
    public static final String TYPE_EXTERNAL = "external";

    /**
     * Where a deployment artifact came from. Orthogonal to {@link #type} (packaging).
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
    public enum Source {
        INTELLIJ_ARTIFACT,
        AUTO_DETECTED,
        EXTERNAL;

        /**
         * Resolves a serialized source name to a {@link Source}. Returns
         * {@link #INTELLIJ_ARTIFACT} for {@code null}, unknown, or absent values
         * so legacy configs (written before the source field existed) default to
         * the pre-existing behaviour.
         */
        @NotNull
        public static Source fromSerialized(@Nullable String name) {
            if (name == null) return INTELLIJ_ARTIFACT;
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return INTELLIJ_ARTIFACT;
            }
        }
    }

    @NotNull private String name = "";
    @NotNull private String path = "";
    @NotNull private String type = TYPE_WAR;
    @NotNull private String contextPath = "/";
    @NotNull private Source source = Source.INTELLIJ_ARTIFACT;

    public DeploymentArtifact() {}

    public DeploymentArtifact(@NotNull String name, @NotNull String path, @NotNull String type) {
        this.name = Objects.requireNonNull(name);
        this.path = Objects.requireNonNull(path);
        setType(type);
    }

    @NotNull
    public String getName() { return name; }

    public void setName(@Nullable String name) {
        this.name = StringUtil.notNullize(name);
    }

    @NotNull
    public String getPath() { return path; }

    public void setPath(@Nullable String path) {
        this.path = StringUtil.notNullize(path);
    }

    @NotNull
    public String getType() { return type; }

    public void setType(@Nullable String type) {
        String normalized = StringUtil.notNullize(type, TYPE_WAR);
        // Backward-compat: legacy configs wrote type="external" to mark a user-
        // picked file/directory. The packaging (war vs exploded) was lost on disk
        // so we can't reconstruct it here — callers deserializing a legacy config
        // are expected to post-process (see TomcatConfigurationSerializer). When
        // such a value sneaks through a direct setter call we still route it to
        // the source flag so the artifact is treated as external downstream.
        if (TYPE_EXTERNAL.equalsIgnoreCase(normalized)) {
            this.type = TYPE_WAR;
            this.source = Source.EXTERNAL;
            return;
        }
        this.type = normalized;
    }

    @NotNull
    public Source getSource() { return source; }

    public void setSource(@Nullable Source source) {
        this.source = source != null ? source : Source.INTELLIJ_ARTIFACT;
    }

    @NotNull
    public String getContextPath() { return contextPath; }

    public void setContextPath(@Nullable String contextPath) {
        // Canonicalize at the model boundary — guarantees slash-prefix, no double slashes,
        // no trailing slash (except root). Defends against non-UI callers (XML deserializer,
        // imported configs) that would otherwise produce broken URLs in formatUrl.
        this.contextPath = ContextPathUtils.normalizeContextPath(contextPath);
    }

    /**
     * Alias for getContextPath() - used by UI components.
     */
    @NotNull
    public String getApplicationContext() {
        return getContextPath();
    }

    /**
     * Alias for setContextPath() - used by UI components.
     */
    public void setApplicationContext(@Nullable String context) {
        setContextPath(context);
    }

    /**
     * Gets the display name for this artifact.
     * Uses the name field, or derives it from the path if name is empty.
     */
    @NotNull
    public String getDisplayName() {
        if (!name.isEmpty()) {
            return name;
        }
        // Derive name from path
        File file = new File(path);
        return file.getName();
    }

    /**
     * Gets the server path for deployment.
     * This is an alias for contextPath.
     */
    @NotNull
    public String getServerPath() {
        return contextPath;
    }

    /**
     * Sets the server path for deployment.
     * This is an alias for setContextPath.
     */
    public void setServerPath(@Nullable String serverPath) {
        setContextPath(serverPath);
    }

    public boolean isUsingDefaultContext() {
        // contextPath is canonicalised by the setter to a non-empty, slash-prefixed
        // form, so the default-context check is just an equality test against "/".
        return contextPath.equals("/");
    }

    public boolean isValid() {
        if (name.isEmpty() || path.isEmpty()) return false;
        File file = new File(path);
        return file.exists();
    }

    @NotNull
    @Override
    public DeploymentArtifact clone() {
        try {
            return (DeploymentArtifact) super.clone();
        } catch (CloneNotSupportedException e) {
            DeploymentArtifact copy = new DeploymentArtifact();
            copy.name = this.name;
            copy.path = this.path;
            copy.type = this.type;
            copy.contextPath = this.contextPath;
            copy.source = this.source;
            return copy;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeploymentArtifact that)) return false;
        return name.equals(that.name) && path.equals(that.path) &&
                type.equals(that.type) && contextPath.equals(that.contextPath) &&
                source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path, type, contextPath, source);
    }

    @NotNull
    @Override
    public String toString() {
        return "DeploymentArtifact{name='" + name + "', type='" + type
                + "', source=" + source + ", path='" + path + "'}";
    }
}