package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Deployment Artifact Model
 *
 * Represents a deployable artifact in the Dev Tomcat plugin. An artifact can be:
 * - An IntelliJ IDEA artifact (WAR, JAR, EAR, etc.)
 * - An external source (directory or archive file)
 *
 * This model supports various deployment configurations including:
 * - Custom application context paths
 * - Deploy/undeploy control
 * - Multiple artifact types (WAR, WAR exploded, JAR, EAR, directory)
 *
 * The model is serializable for persistence and cloneable for configuration copying.
 *
 * @author Dev Tomcat Team
 * @see Artifact
 */
public class DeploymentArtifact implements Serializable, Cloneable {

    private static final long serialVersionUID = 2L;

    // Constants for artifact types
    public static final String TYPE_WAR = "war";
    public static final String TYPE_WAR_EXPLODED = "war exploded";
    public static final String TYPE_JAR = "jar";
    public static final String TYPE_EAR = "ear";
    public static final String TYPE_EAR_EXPLODED = "ear exploded";
    public static final String TYPE_DIRECTORY = "directory";

    // Default values
    private static final String DEFAULT_NAME = "Unnamed Artifact";
    private static final String DEFAULT_TYPE = TYPE_WAR;
    private static final String DEFAULT_PATH = "/";

    // Core fields
    @NotNull private String name;
    @NotNull private String type;
    @NotNull private String serverPath;        // Where to deploy on server
    @NotNull private String localPath;         // Local file/directory path
    @NotNull private String applicationContext; // Application context path

    // Deployment state
    private boolean deployed = true;
    private boolean usingDefaultContext = true;

    // Reference to IntelliJ artifact (transient - not serialized)
    @Nullable private transient Artifact intellijArtifact;

    /**
     * Default constructor for serialization
     */
    public DeploymentArtifact() {
        this(DEFAULT_NAME, DEFAULT_TYPE, DEFAULT_PATH, "", DEFAULT_PATH);
    }

    /**
     * Constructor for external sources
     *
     * @param name The artifact name
     * @param type The artifact type
     * @param serverPath The deployment path on server
     * @param localPath The local file system path
     */
    public DeploymentArtifact(@NotNull String name,
                              @NotNull String type,
                              @NotNull String serverPath,
                              @NotNull String localPath) {
        this(name, type, serverPath, localPath, serverPath);
    }

    /**
     * Full constructor with custom application context
     *
     * @param name The artifact name
     * @param type The artifact type
     * @param serverPath The deployment path on server
     * @param localPath The local file system path
     * @param applicationContext The application context path
     */
    public DeploymentArtifact(@NotNull String name,
                              @NotNull String type,
                              @NotNull String serverPath,
                              @NotNull String localPath,
                              @NotNull String applicationContext) {
        this.name = validateName(name);
        this.type = normalizeType(type);
        this.serverPath = normalizePath(serverPath);
        this.localPath = StringUtil.notNullize(localPath).trim();
        this.applicationContext = normalizePath(applicationContext);
        this.deployed = true;
        this.usingDefaultContext = this.serverPath.equals(this.applicationContext);
    }

    /**
     * Create a deployment artifact from an IntelliJ IDEA artifact
     *
     * @param artifact The IntelliJ artifact
     * @param applicationContext The desired application context
     * @return A new DeploymentArtifact instance
     */
    @NotNull
    public static DeploymentArtifact fromIntellijArtifact(@NotNull Artifact artifact,
                                                          @NotNull String applicationContext) {
        String type = detectArtifactType(artifact);
        String localPath = StringUtil.notNullize(artifact.getOutputPath());

        DeploymentArtifact deploymentArtifact = new DeploymentArtifact(
                artifact.getName(),
                type,
                applicationContext,
                localPath,
                applicationContext
        );

        deploymentArtifact.intellijArtifact = artifact;
        return deploymentArtifact;
    }

    /**
     * Builder for creating DeploymentArtifact instances
     */
    public static class Builder {
        private String name = DEFAULT_NAME;
        private String type = DEFAULT_TYPE;
        private String serverPath = DEFAULT_PATH;
        private String localPath = "";
        private String applicationContext = DEFAULT_PATH;
        private boolean deployed = true;
        private Artifact intellijArtifact = null;

        public Builder name(@NotNull String name) {
            this.name = name;
            return this;
        }

        public Builder type(@NotNull String type) {
            this.type = type;
            return this;
        }

        public Builder serverPath(@NotNull String serverPath) {
            this.serverPath = serverPath;
            return this;
        }

        public Builder localPath(@NotNull String localPath) {
            this.localPath = localPath;
            return this;
        }

        public Builder applicationContext(@NotNull String applicationContext) {
            this.applicationContext = applicationContext;
            return this;
        }

        public Builder deployed(boolean deployed) {
            this.deployed = deployed;
            return this;
        }

        public Builder intellijArtifact(@Nullable Artifact artifact) {
            this.intellijArtifact = artifact;
            return this;
        }

        @NotNull
        public DeploymentArtifact build() {
            DeploymentArtifact artifact = new DeploymentArtifact(
                    name, type, serverPath, localPath, applicationContext
            );
            artifact.deployed = deployed;
            artifact.intellijArtifact = intellijArtifact;
            return artifact;
        }
    }

    // === VALIDATION AND NORMALIZATION ===

    @NotNull
    private static String validateName(@NotNull String name) {
        String trimmed = name.trim();
        return trimmed.isEmpty() ? DEFAULT_NAME : trimmed;
    }

    @NotNull
    private static String normalizeType(@NotNull String type) {
        String normalized = type.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return DEFAULT_TYPE;
        }

        // Ensure consistent type names
        switch (normalized) {
            case "war":
            case "war exploded":
            case "jar":
            case "ear":
            case "ear exploded":
            case "directory":
                return normalized;
            default:
                return DEFAULT_TYPE;
        }
    }

    @NotNull
    private static String normalizePath(@NotNull String path) {
        String trimmed = path.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) {
            return "/";
        }

        // Ensure path starts with /
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }

        // Remove trailing / except for root
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    /**
     * Detect artifact type from IntelliJ artifact
     */
    @NotNull
    private static String detectArtifactType(@NotNull Artifact artifact) {
        String typeId = artifact.getArtifactType().getId().toLowerCase();

        if (typeId.contains("war")) {
            return typeId.contains("exploded") ? TYPE_WAR_EXPLODED : TYPE_WAR;
        } else if (typeId.contains("jar")) {
            return TYPE_JAR;
        } else if (typeId.contains("ear")) {
            return typeId.contains("exploded") ? TYPE_EAR_EXPLODED : TYPE_EAR;
        } else {
            return TYPE_DIRECTORY;
        }
    }

    // === GETTERS ===

    @NotNull
    public String getName() {
        return name;
    }

    @NotNull
    public String getType() {
        return type;
    }

    @NotNull
    public String getServerPath() {
        return serverPath;
    }

    @NotNull
    public String getLocalPath() {
        return localPath;
    }

    @NotNull
    public String getApplicationContext() {
        return applicationContext;
    }

    public boolean isDeployed() {
        return deployed;
    }

    public boolean isUsingDefaultContext() {
        return usingDefaultContext;
    }

    @Nullable
    public Artifact getIntellijArtifact() {
        return intellijArtifact;
    }

    // === SETTERS ===

    public void setName(@NotNull String name) {
        this.name = validateName(name);
    }

    public void setType(@NotNull String type) {
        this.type = normalizeType(type);
    }

    public void setServerPath(@NotNull String serverPath) {
        this.serverPath = normalizePath(serverPath);
        if (usingDefaultContext) {
            this.applicationContext = this.serverPath;
        }
    }

    public void setLocalPath(@NotNull String localPath) {
        this.localPath = StringUtil.notNullize(localPath).trim();
    }

    public void setApplicationContext(@NotNull String applicationContext) {
        this.applicationContext = normalizePath(applicationContext);
        this.usingDefaultContext = this.serverPath.equals(this.applicationContext);
    }

    public void setDeployed(boolean deployed) {
        this.deployed = deployed;
    }

    public void setUsingDefaultContext(boolean usingDefaultContext) {
        this.usingDefaultContext = usingDefaultContext;
        if (usingDefaultContext) {
            this.applicationContext = this.serverPath;
        }
    }

    public void setIntellijArtifact(@Nullable Artifact artifact) {
        this.intellijArtifact = artifact;
    }

    // === UTILITY METHODS ===

    /**
     * Check if this represents an IntelliJ IDEA artifact
     */
    public boolean isIntellijArtifact() {
        return intellijArtifact != null;
    }

    /**
     * Check if this is an external source
     */
    public boolean isExternalSource() {
        return intellijArtifact == null;
    }

    /**
     * Check if this is a WAR artifact
     */
    public boolean isWarArtifact() {
        return TYPE_WAR.equals(type) || TYPE_WAR_EXPLODED.equals(type);
    }

    /**
     * Check if this is an exploded artifact
     */
    public boolean isExploded() {
        return type.contains("exploded") || TYPE_DIRECTORY.equals(type);
    }

    /**
     * Get the deployment URL for this artifact
     *
     * @param baseUrl The base server URL (e.g., "http://localhost:8080")
     * @return The full URL to access this deployed artifact
     */
    @NotNull
    public String getDeploymentUrl(@NotNull String baseUrl) {
        // Normalize base URL
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Handle root context
        if ("/".equals(applicationContext)) {
            return baseUrl + "/";
        }

        // Normal context path
        return baseUrl + applicationContext;
    }

    /**
     * Get a user-friendly display name
     */
    @NotNull
    public String getDisplayName() {
        if (isIntellijArtifact() && intellijArtifact != null) {
            return intellijArtifact.getName();
        }
        return name;
    }

    /**
     * Get display string for UI tables
     */
    @NotNull
    public String toDisplayString() {
        return String.format("%s (%s) → %s [%s]",
                name,
                type,
                applicationContext,
                deployed ? "deployed" : "not deployed"
        );
    }

    /**
     * Create a Tomcat context configuration string
     */
    @NotNull
    public String toContextXml() {
        return String.format("<Context path=\"%s\" docBase=\"%s\" reloadable=\"true\" />",
                applicationContext,
                localPath
        );
    }

    /**
     * Validate this artifact's configuration
     *
     * @throws IllegalStateException if validation fails
     */
    public void validate() {
        if (name.isEmpty() || DEFAULT_NAME.equals(name)) {
            throw new IllegalStateException("Artifact name must be specified");
        }

        if (localPath.isEmpty()) {
            throw new IllegalStateException("Local path must be specified");
        }

        if (!isIntellijArtifact() && !new java.io.File(localPath).exists()) {
            throw new IllegalStateException("Local path does not exist: " + localPath);
        }
    }

    // === OBJECT METHODS ===

    @Override
    public DeploymentArtifact clone() {
        try {
            DeploymentArtifact cloned = (DeploymentArtifact) super.clone();
            // Note: intellijArtifact reference is not cloned (transient)
            return cloned;
        } catch (CloneNotSupportedException e) {
            // Should never happen since we implement Cloneable
            throw new AssertionError("Clone not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeploymentArtifact)) return false;

        DeploymentArtifact that = (DeploymentArtifact) o;

        return deployed == that.deployed &&
                usingDefaultContext == that.usingDefaultContext &&
                name.equals(that.name) &&
                type.equals(that.type) &&
                serverPath.equals(that.serverPath) &&
                localPath.equals(that.localPath) &&
                applicationContext.equals(that.applicationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, serverPath, localPath,
                applicationContext, deployed, usingDefaultContext);
    }

    @Override
    public String toString() {
        return String.format(
                "DeploymentArtifact{name='%s', type='%s', serverPath='%s', " +
                        "localPath='%s', context='%s', deployed=%s}",
                name, type, serverPath, localPath, applicationContext, deployed
        );
    }
}