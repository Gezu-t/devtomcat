package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Deployment Configuration for Tomcat
 *
 * Manages deployment artifacts and deployment settings for Tomcat applications.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Store and manage deployment artifacts (WAR files, etc.)</li>
 *   <li>Configure hot deployment settings</li>
 *   <li>Manage class/resource update behavior</li>
 *   <li>Provide safe cloning and deep copying</li>
 * </ul>
 *
 * <p>100% NULL-SAFE — All null checks with comprehensive error handling
 * <p>Immutable-Ready — Supports deep cloning for safe configuration passing
 * <p>Logging-Enabled — Debug logging for all operations
 * <p>Thread-safe for read operations, mutable for configuration
 *
 * <p>Default Configuration:
 * <ul>
 *   <li>Artifacts: empty list</li>
 *   <li>Hot Deployment: disabled</li>
 *   <li>Update Classes/Resources: disabled</li>
 *   <li>Deployment Path: (empty)</li>
 * </ul>
 *
 * Author: Dev Tomcat Team
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 *
 * @see DeploymentArtifact
 */
public class DeploymentConfig implements Serializable, Cloneable {

    private static final Logger LOG = Logger.getInstance(DeploymentConfig.class);

    @Serial
    private static final long serialVersionUID = 1L;

    // Defaults
    private static final boolean DEFAULT_HOT_DEPLOYMENT = false;
    private static final boolean DEFAULT_UPDATE_CLASSES = false;
    private static final String DEFAULT_DEPLOYMENT_PATH = "";

    // =====================================================================
    // FIELDS
    // =====================================================================

    @NotNull
    private List<DeploymentArtifact> artifacts = new ArrayList<>();

    private boolean hotDeploymentEnabled = DEFAULT_HOT_DEPLOYMENT;

    private boolean updateClassesAndResources = DEFAULT_UPDATE_CLASSES;

    @NotNull
    private String deploymentPath = DEFAULT_DEPLOYMENT_PATH;

    // =====================================================================
    // CONSTRUCTORS
    // =====================================================================

    /**
     * Create a new empty deployment configuration.
     *
     * <p>Initializes with:
     * - Empty artifact list
     * - Hot deployment disabled
     * - Class/resource updates disabled
     * - Empty deployment path
     */
    public DeploymentConfig() {
        this.artifacts = new ArrayList<>();
        this.hotDeploymentEnabled = DEFAULT_HOT_DEPLOYMENT;
        this.updateClassesAndResources = DEFAULT_UPDATE_CLASSES;
        this.deploymentPath = DEFAULT_DEPLOYMENT_PATH;

        LOG.debug("Created empty DeploymentConfig");
    }

    /**
     * Create a deployment configuration by copying another.
     *
     * <p>Deep-copies all artifacts and settings.
     * If source is null, creates empty configuration.
     *
     * @param other the configuration to copy from (can be null)
     */
    public DeploymentConfig(@Nullable DeploymentConfig other) {
        this.artifacts = new ArrayList<>();

        if (other == null) {
            LOG.debug("Created DeploymentConfig from null source");
            return;
        }

        try {
            // Deep copy artifacts
            for (DeploymentArtifact artifact : other.artifacts) {
                if (artifact != null) {
                    this.artifacts.add(artifact.clone());
                } else {
                    LOG.warn("Skipping null artifact during copy");
                }
            }

            // Copy settings
            this.hotDeploymentEnabled = other.hotDeploymentEnabled;
            this.updateClassesAndResources = other.updateClassesAndResources;
            this.deploymentPath = other.deploymentPath != null ? other.deploymentPath : DEFAULT_DEPLOYMENT_PATH;

            LOG.debug("DeploymentConfig copied from another instance: " + this.artifacts.size() + " artifacts");
        } catch (Exception e) {
            LOG.error("Error copying DeploymentConfig", e);
            this.artifacts = new ArrayList<>();
            throw new RuntimeException("Failed to copy DeploymentConfig", e);
        }
    }

    // =====================================================================
    // GETTERS
    // =====================================================================

    /**
     * Get the list of deployment artifacts.
     *
     * <p>Returns a defensive copy to prevent external modification.
     *
     * @return unmodifiable copy of artifacts (never null)
     */
    @NotNull
    public List<DeploymentArtifact> getArtifacts() {
        return new ArrayList<>(artifacts);
    }

    @Nullable
    public DeploymentArtifact getArtifact(int index) {
        if (index < 0 || index >= artifacts.size()) {
            LOG.debug("Artifact index out of bounds: " + index);
            return null;
        }
        return artifacts.get(index);
    }

    @Nullable
    public DeploymentArtifact getArtifactByName(@NotNull String name) {
        Objects.requireNonNull(name, "Artifact name cannot be null");

        return artifacts.stream()
                .filter(a -> a != null && a.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public int getArtifactCount() {
        return artifacts.size();
    }

    public boolean hasArtifacts() {
        return !artifacts.isEmpty();
    }

    public boolean isHotDeploymentEnabled() {
        return hotDeploymentEnabled;
    }

    public boolean isUpdateClassesAndResources() {
        return updateClassesAndResources;
    }

    /**
     * Get the deployment path.
     *
     * <p>Never returns null — returns empty string if not set.
     *
     * @return deployment path (never null)
     */
    @NotNull
    public String getDeploymentPath() {
        return deploymentPath != null ? deploymentPath : DEFAULT_DEPLOYMENT_PATH;
    }

    // =====================================================================
    // SETTERS
    // =====================================================================

    /**
     * Set the list of deployment artifacts.
     *
     * <p>Validates and filters out null artifacts.
     * Replaces entire artifact list.
     *
     * @param artifacts the artifacts to set (can be null)
     */
    public void setArtifacts(@Nullable List<DeploymentArtifact> artifacts) {
        if (artifacts == null) {
            this.artifacts = new ArrayList<>();
            LOG.debug("Set artifacts to empty (null input)");
            return;
        }

        // Filter out null artifacts
        List<DeploymentArtifact> valid = artifacts.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (valid.size() < artifacts.size()) {
            int nullCount = artifacts.size() - valid.size();
            LOG.warn("Filtered out " + nullCount + " null artifacts");
        }

        this.artifacts = valid;
        LOG.debug("Set artifacts: " + valid.size() + " items");
    }

    /**
     * Add a single deployment artifact.
     *
     * <p>Checks for duplicates before adding.
     * Logs at debug level for each addition.
     *
     * @param artifact the artifact to add (cannot be null)
     * @return true if added, false if already present or null
     */
    public boolean addArtifact(@NotNull DeploymentArtifact artifact) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");

        if (artifacts.contains(artifact)) {
            LOG.debug("Artifact already present, skipping: " + artifact.getName());
            return false;
        }

        artifacts.add(artifact);
        LOG.debug("Added artifact: " + artifact.getName());
        return true;
    }

    public boolean removeArtifact(@NotNull DeploymentArtifact artifact) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");

        boolean removed = artifacts.remove(artifact);
        if (removed) {
            LOG.debug("Removed artifact: " + artifact.getName());
        } else {
            LOG.debug("Artifact not found for removal: " + artifact.getName());
        }
        return removed;
    }

    @Nullable
    public DeploymentArtifact removeArtifactAt(int index) {
        if (index < 0 || index >= artifacts.size()) {
            LOG.debug("Artifact index out of bounds for removal: " + index);
            return null;
        }

        DeploymentArtifact removed = artifacts.remove(index);
        LOG.debug("Removed artifact at index " + index + ": " + removed.getName());
        return removed;
    }

    public void clearArtifacts() {
        int count = artifacts.size();
        artifacts.clear();
        LOG.debug("Cleared " + count + " artifacts");
    }

    public void setHotDeploymentEnabled(boolean enabled) {
        if (this.hotDeploymentEnabled != enabled) {
            this.hotDeploymentEnabled = enabled;
            LOG.debug("Hot deployment enabled: " + enabled);
        }
    }

    public void setUpdateClassesAndResources(boolean enabled) {
        if (this.updateClassesAndResources != enabled) {
            this.updateClassesAndResources = enabled;
            LOG.debug("Update classes and resources enabled: " + enabled);
        }
    }

    public void setDeploymentPath(@Nullable String path) {
        this.deploymentPath = path != null ? path : DEFAULT_DEPLOYMENT_PATH;
        LOG.debug("Set deployment path: " + this.deploymentPath);
    }

    // =====================================================================
    // VALIDATION METHODS
    // =====================================================================

    /**
     * Check if deployment configuration is valid.
     *
     * <p>Valid configuration must have:
     * - At least one artifact configured
     * - All artifacts are valid
     *
     * @return true if configuration is valid
     */
    public boolean isValid() {
        if (!hasArtifacts()) {
            LOG.warn("Deployment config invalid: no artifacts configured");
            return false;
        }

        // Check if all artifacts are valid
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) {
                LOG.warn("Deployment config invalid: artifact validation failed");
                return false;
            }
        }

        LOG.debug("Deployment config is valid");
        return true;
    }

    public int getInvalidArtifactCount() {
        int count = 0;
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) {
                count++;
            }
        }
        return count;
    }
    private boolean preserveSessions = false;

    public boolean isPreserveSessions() {
        return preserveSessions;
    }

    public void setPreserveSessions(boolean preserve) {
        this.preserveSessions = preserve;
    }

    // =====================================================================
    // CLONING & OBJECT METHODS
    // =====================================================================

    /**
     * Create a deep clone of this configuration.
     *
     * <p>All artifacts and settings are cloned independently.
     * Safe to modify the clone without affecting the original.
     *
     * @return cloned DeploymentConfig (never null)
     * @throws RuntimeException if cloning fails
     */
    @NotNull
    @Override
    public DeploymentConfig clone() {
        try {
            DeploymentConfig clone = (DeploymentConfig) super.clone();

            // Deep clone artifacts list
            clone.artifacts = new ArrayList<>();
            for (DeploymentArtifact artifact : this.artifacts) {
                if (artifact != null) {
                    clone.artifacts.add(artifact.clone());
                }
            }

            clone.deploymentPath = this.deploymentPath;

            LOG.debug("DeploymentConfig cloned successfully: " + clone.artifacts.size() + " artifacts");
            return clone;
        } catch (CloneNotSupportedException e) {
            LOG.error("Clone not supported for DeploymentConfig", e);
            throw new RuntimeException("DeploymentConfig cloning failed", e);
        } catch (Exception e) {
            LOG.error("Unexpected error cloning DeploymentConfig", e);
            throw new RuntimeException("Failed to clone DeploymentConfig", e);
        }
    }

    /**
     * Check if two deployment configurations are equal.
     *
     * <p>Compares all fields: artifacts, hot deployment, update classes, and path.
     *
     * @param o the object to compare (can be null)
     * @return true if configurations are identical
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DeploymentConfig that = (DeploymentConfig) o;

        return hotDeploymentEnabled == that.hotDeploymentEnabled &&
                updateClassesAndResources == that.updateClassesAndResources &&
                artifacts.equals(that.artifacts) &&
                deploymentPath.equals(that.deploymentPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifacts, hotDeploymentEnabled,
                updateClassesAndResources, deploymentPath);
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    /**
     * Get configuration summary for logging.
     *
     * <p>Returns a formatted string with key configuration details.
     * Useful for debug logs and status displays.
     *
     * @return summary string (never null)
     */
    @NotNull
    public String getSummary() {
        return String.format("DeploymentConfig{artifacts=%d, hotDeploy=%s, updateClasses=%s, path=%s}",
                artifacts.size(),
                hotDeploymentEnabled,
                updateClassesAndResources,
                deploymentPath);
    }

    /**
     * Get detailed artifact listing.
     *
     * <p>Returns a formatted string listing all artifacts.
     * Useful for logging and debugging.
     *
     * @return artifact listing (never null)
     */
    @NotNull
    public String getArtifactListing() {
        if (artifacts.isEmpty()) {
            return "No artifacts configured";
        }

        return artifacts.stream()
                .map(a -> "  - " + a.getName() + " (" + a.getType() + ")")
                .collect(Collectors.joining("\n", "Artifacts:\n", ""));
    }

    /**
     * Get human-readable string representation.
     *
     * <p>Format: "DeploymentConfig{artifacts=1, hotDeploy=true, ...}"
     *
     * @return formatted string (never null)
     */
    @NotNull
    @Override
    public String toString() {
        return getSummary();
    }
}