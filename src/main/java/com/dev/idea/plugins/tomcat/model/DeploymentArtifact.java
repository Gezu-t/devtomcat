package com.dev.idea.plugins.tomcat.model;

import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Professional Deployment Artifact Model
 * Reusable across the entire DevTomcat plugin
 *
 * This model represents a deployment artifact configuration that can be:
 * - Used in UI components (DeploymentConfigurationTab)
 * - Stored in configuration (TomcatRunConfiguration)
 * - Serialized to XML
 * - Passed between different components
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentArtifact implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    // Core fields
    private String name;
    private String type;
    private String serverPath;
    private String localPath;

    // Transient reference to IntelliJ artifact (not serialized)
    @Nullable
    private transient Artifact intellijArtifact;

    /**
     * Default constructor for serialization
     */
    public DeploymentArtifact() {
        this("", "war", "/", "");
    }

    /**
     * Constructor for external sources (no IntelliJ artifact)
     */
    public DeploymentArtifact(@NotNull String name,
                              @NotNull String type,
                              @NotNull String serverPath,
                              @NotNull String localPath) {
        this(null, name, type, serverPath, localPath);
    }

    /**
     * Full constructor with IntelliJ artifact reference
     */
    public DeploymentArtifact(@Nullable Artifact intellijArtifact,
                              @NotNull String name,
                              @NotNull String type,
                              @NotNull String serverPath,
                              @NotNull String localPath) {
        this.intellijArtifact = intellijArtifact;
        this.name = validateName(name);
        this.type = validateType(type);
        this.serverPath = validateServerPath(serverPath);
        this.localPath = validateLocalPath(localPath);
    }

    /**
     * Create from IntelliJ artifact
     */
    public static DeploymentArtifact fromIntellijArtifact(@NotNull Artifact artifact,
                                                          @NotNull String serverPath) {
        String type = detectArtifactType(artifact);
        String localPath = artifact.getOutputPath() != null ? artifact.getOutputPath() : "";

        return new DeploymentArtifact(
                artifact,
                artifact.getName(),
                type,
                serverPath,
                localPath
        );
    }

    // === VALIDATION METHODS ===

    private String validateName(String name) {
        return (name == null || name.trim().isEmpty()) ? "Unnamed Artifact" : name.trim();
    }

    private String validateType(String type) {
        return (type == null || type.trim().isEmpty()) ? "war" : type.trim().toLowerCase();
    }

    private String validateServerPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String validateLocalPath(String path) {
        return path != null ? path.trim() : "";
    }

    private static String detectArtifactType(Artifact artifact) {
        String typeId = artifact.getArtifactType().getId().toLowerCase();

        if (typeId.contains("war")) {
            return typeId.contains("exploded") ? "war exploded" : "war";
        } else if (typeId.contains("jar")) {
            return "jar";
        } else if (typeId.contains("ear")) {
            return "ear";
        } else {
            return "directory";
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

    @Nullable
    public Artifact getIntellijArtifact() {
        return intellijArtifact;
    }

    /**
     * Get display name for UI
     */
    @NotNull
    public String getDisplayName() {
        return name;
    }

    // === SETTERS ===

    public void setName(@NotNull String name) {
        this.name = validateName(name);
    }

    public void setType(@NotNull String type) {
        this.type = validateType(type);
    }

    public void setServerPath(@NotNull String serverPath) {
        this.serverPath = validateServerPath(serverPath);
    }

    public void setLocalPath(@NotNull String localPath) {
        this.localPath = validateLocalPath(localPath);
    }

    public void setIntellijArtifact(@Nullable Artifact artifact) {
        this.intellijArtifact = artifact;
    }

    // === UTILITY METHODS ===

    /**
     * Check if this represents an IntelliJ artifact
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
        return type.contains("war");
    }

    /**
     * Check if this is an exploded artifact
     */
    public boolean isExploded() {
        return type.contains("exploded");
    }

    /**
     * Get deployment URL for this artifact
     */
    public String getDeploymentUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "http://localhost:8080";
        }

        // Remove trailing slash from base URL
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Handle root context
        if ("/".equals(serverPath)) {
            return baseUrl + "/";
        }

        // Normal context path
        return baseUrl + serverPath;
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
            throw new RuntimeException("Failed to clone DeploymentArtifact", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DeploymentArtifact that = (DeploymentArtifact) o;

        return name.equals(that.name) &&
                type.equals(that.type) &&
                serverPath.equals(that.serverPath) &&
                localPath.equals(that.localPath);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + serverPath.hashCode();
        result = 31 * result + localPath.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("DeploymentArtifact[name=%s, type=%s, server=%s, local=%s]",
                name, type, serverPath, localPath);
    }

    /**
     * Get display string for UI tables
     */
    public String toDisplayString() {
        return String.format("%s (%s) → %s", name, type, serverPath);
    }
}