package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Deployment configuration: artifact list + hot-deploy / update-classes / sessions flags.
 *
 * <p>Storage is still legacy {@link DeploymentArtifact} during the typed-Deployment
 * migration — see LOCAL_NOTES.md "Phase 4d". Typed accessors / mutators convert
 * on the boundary; once every caller is on the typed API, the field flips to
 * {@code List<Deployment>} and legacy getters become {@link DeploymentAdapter}
 * views.
 */
public class DeploymentConfig implements Serializable, Cloneable {

    private static final Logger LOG = Logger.getInstance(DeploymentConfig.class);

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull
    private List<DeploymentArtifact> artifacts = new ArrayList<>();

    private boolean hotDeploymentEnabled = false;
    private boolean updateClassesAndResources = false;
    private boolean preserveSessions = false;

    public DeploymentConfig() {
    }

    // =====================================================================
    // Persistence-layer accessors
    //
    // DeploymentArtifact is the XML serialization shape — the typed
    // Deployment hierarchy lives on top of it through DeploymentAdapter.
    // These methods are the table-and-serializer boundary, not deprecated.
    // =====================================================================

    /**
     * Returns a defensive copy of the persistence-layer artifact list.
     */
    @NotNull
    public List<DeploymentArtifact> getArtifacts() {
        return new ArrayList<>(artifacts);
    }

    // =====================================================================
    // Typed accessors
    // =====================================================================

    /**
     * Typed view of the deployment list. Each legacy entry is adapted via {@link DeploymentAdapter#toTyped}.
     */
    @NotNull
    public List<Deployment> getDeployments(@NotNull Project project) {
        // Snapshot first — mirrors getArtifacts(); avoids CME when a concurrent
        // UI Apply / setArtifacts replaces or mutates the storage list while
        // background update/sync threads are iterating.
        List<DeploymentArtifact> snapshot = new ArrayList<>(artifacts);
        List<Deployment> out = new ArrayList<>(snapshot.size());
        for (DeploymentArtifact a : snapshot) {
            if (a != null) out.add(DeploymentAdapter.toTyped(project, a));
        }
        return out;
    }

    @Nullable
    public Deployment getDeploymentByName(@NotNull Project project, @NotNull String name) {
        DeploymentArtifact a = getArtifactByName(name);
        return a == null ? null : DeploymentAdapter.toTyped(project, a);
    }

    @Nullable
    public DeploymentArtifact getArtifact(int index) {
        if (index < 0 || index >= artifacts.size()) return null;
        return artifacts.get(index);
    }

    @Nullable
    public DeploymentArtifact getArtifactByName(@NotNull String name) {
        Objects.requireNonNull(name, "Artifact name cannot be null");
        // Snapshot to match the getArtifacts() / getDeployments() defensive-copy
        // contract — concurrent setArtifacts could otherwise CME mid-stream.
        return new ArrayList<>(artifacts).stream()
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

    public boolean isPreserveSessions() {
        return preserveSessions;
    }

    // =====================================================================
    // Setters
    // =====================================================================

    /**
     * Legacy setter — called by the XML serializer and a handful of in-flight
     * call sites. Filters out nulls. Will be replaced by {@link #setDeployments}
     * once consumers stop producing {@link DeploymentArtifact} directly.
     */
    public void setArtifacts(@Nullable List<DeploymentArtifact> artifacts) {
        if (artifacts == null) {
            this.artifacts = new ArrayList<>();
            return;
        }
        List<DeploymentArtifact> valid = artifacts.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        if (valid.size() < artifacts.size()) {
            LOG.warn("Filtered out " + (artifacts.size() - valid.size()) + " null artifacts");
        }
        this.artifacts = valid;
    }

    /**
     * Typed setter — converts each {@link Deployment} back to legacy via {@link DeploymentAdapter}.
     */
    public void setDeployments(@Nullable List<? extends Deployment> deployments) {
        if (deployments == null) {
            this.artifacts = new ArrayList<>();
            return;
        }
        List<DeploymentArtifact> out = new ArrayList<>(deployments.size());
        for (Deployment d : deployments) {
            if (d != null) out.add(DeploymentAdapter.toLegacy(d));
        }
        this.artifacts = out;
    }

    /**
     * Returns {@code true} if added, {@code false} if already present.
     */
    public boolean addArtifact(@NotNull DeploymentArtifact artifact) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");
        if (artifacts.contains(artifact)) return false;
        artifacts.add(artifact);
        return true;
    }

    /**
     * Typed add — converts via {@link DeploymentAdapter#toLegacy}.
     */
    public boolean addDeployment(@NotNull Deployment deployment) {
        Objects.requireNonNull(deployment, "Deployment cannot be null");
        return addArtifact(DeploymentAdapter.toLegacy(deployment));
    }

    public boolean removeArtifact(@NotNull DeploymentArtifact artifact) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");
        return artifacts.remove(artifact);
    }

    /**
     * Typed remove — converts via {@link DeploymentAdapter#toLegacy}.
     */
    public boolean removeDeployment(@NotNull Deployment deployment) {
        Objects.requireNonNull(deployment, "Deployment cannot be null");
        return removeArtifact(DeploymentAdapter.toLegacy(deployment));
    }

    @Nullable
    public DeploymentArtifact removeArtifactAt(int index) {
        if (index < 0 || index >= artifacts.size()) return null;
        return artifacts.remove(index);
    }

    public void clearArtifacts() {
        artifacts.clear();
    }

    public void setHotDeploymentEnabled(boolean enabled) {
        this.hotDeploymentEnabled = enabled;
    }

    public void setUpdateClassesAndResources(boolean enabled) {
        this.updateClassesAndResources = enabled;
    }

    public void setPreserveSessions(boolean preserve) {
        this.preserveSessions = preserve;
    }

    // =====================================================================
    // Validation
    // =====================================================================

    public boolean isValid() {
        if (!hasArtifacts()) return false;
        for (DeploymentArtifact a : artifacts) {
            if (a == null || !a.isValid()) return false;
        }
        return true;
    }

    // =====================================================================
    // Cloning & object methods
    // =====================================================================

    @NotNull
    @Override
    public DeploymentConfig clone() {
        try {
            DeploymentConfig clone = (DeploymentConfig) super.clone();
            clone.artifacts = new ArrayList<>();
            for (DeploymentArtifact a : this.artifacts) {
                if (a != null) clone.artifacts.add(a.clone());
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("DeploymentConfig cloning failed", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeploymentConfig that)) return false;
        return hotDeploymentEnabled == that.hotDeploymentEnabled
                && updateClassesAndResources == that.updateClassesAndResources
                && preserveSessions == that.preserveSessions
                && artifacts.equals(that.artifacts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifacts, hotDeploymentEnabled,
                updateClassesAndResources, preserveSessions);
    }

    @NotNull
    public String getSummary() {
        return String.format("DeploymentConfig{artifacts=%d, hotDeploy=%s, updateClasses=%s}",
                artifacts.size(), hotDeploymentEnabled, updateClassesAndResources);
    }

    @NotNull
    @Override
    public String toString() {
        return getSummary();
    }
}
