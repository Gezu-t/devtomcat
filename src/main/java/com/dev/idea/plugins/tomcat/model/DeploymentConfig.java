package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Deployment configuration: typed {@link Deployment} list + hot-deploy /
 * update-classes / sessions flags.
 *
 * <p>Storage is natively typed — the list holds {@link Deployment} values
 * directly; persistence goes through the XML serializer.
 *
 * <p>No longer {@code Serializable}: nothing java-serializes this class, and
 * the platform pointers inside typed deployments aren't serializable anyway —
 * persistence goes through the XML serializer.
 */
public class DeploymentConfig implements Cloneable {

    private static final Logger LOG = Logger.getInstance(DeploymentConfig.class);

    @NotNull
    private List<Deployment> deployments = new ArrayList<>();

    private boolean hotDeploymentEnabled = false;
    private boolean updateClassesAndResources = false;
    private boolean preserveSessions = false;

    public DeploymentConfig() {
    }

    // =====================================================================
    // Typed accessors
    // =====================================================================

    /**
     * Stored deployment list, as persisted — no project resolution.
     * Snapshot copy: avoids CME when a concurrent UI Apply / setDeployments
     * replaces or mutates the storage list while background update/sync
     * threads are iterating.
     */
    @NotNull
    public List<Deployment> getDeployments() {
        return new ArrayList<>(deployments);
    }

    /**
     * Resolved view for launch/UI consumers — each stored entry re-evaluated
     * against the live project model via {@link DeploymentResolver#resolve}
     * (Community artifact→module fold, stale module-pointer rebind). Dynamic
     * per call; never mutates storage.
     */
    @NotNull
    public List<Deployment> getDeployments(@NotNull Project project) {
        List<Deployment> snapshot = new ArrayList<>(deployments);
        List<Deployment> out = new ArrayList<>(snapshot.size());
        for (Deployment d : snapshot) {
            if (d != null) out.add(DeploymentResolver.resolve(project, d));
        }
        return out;
    }

    /** Resolved-view lookup by display name (legacy name-match semantics). */
    @Nullable
    public Deployment getDeploymentByName(@NotNull Project project, @NotNull String name) {
        Objects.requireNonNull(name, "Deployment name cannot be null");
        return getDeployments(project).stream()
                .filter(d -> d.getDisplayName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public boolean hasArtifacts() {
        return !deployments.isEmpty();
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
    // Typed mutators
    // =====================================================================

    public void setDeployments(@Nullable List<? extends Deployment> deployments) {
        if (deployments == null) {
            this.deployments = new ArrayList<>();
            return;
        }
        List<Deployment> valid = deployments.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        if (valid.size() < deployments.size()) {
            LOG.warn("Filtered out " + (deployments.size() - valid.size()) + " null deployments");
        }
        this.deployments = valid;
    }

    /**
     * Returns {@code true} if added, {@code false} if already present
     * (value equality on the typed classes).
     */
    public boolean addDeployment(@NotNull Deployment deployment) {
        Objects.requireNonNull(deployment, "Deployment cannot be null");
        if (deployments.contains(deployment)) return false;
        deployments.add(deployment);
        return true;
    }

    public boolean removeDeployment(@NotNull Deployment deployment) {
        Objects.requireNonNull(deployment, "Deployment cannot be null");
        return deployments.remove(deployment);
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

    /**
     * Data-level validity mirroring the legacy artifact check: nonempty
     * display name, nonempty stored path, and the path exists on disk.
     * No pointer resolution — safe before the project model loads.
     */
    public boolean isValid() {
        if (deployments.isEmpty()) return false;
        for (Deployment d : deployments) {
            if (d == null || d.getDisplayName().isEmpty()) return false;
            String path = storedPathOf(d);
            if (path.isEmpty() || !new File(path).exists()) return false;
        }
        return true;
    }

    /** Stored data path — never dereferences pointers. */
    @NotNull
    private static String storedPathOf(@NotNull Deployment d) {
        if (d instanceof ArtifactBackedDeployment a) {
            return a.getLastKnownPath() == null ? "" : a.getLastKnownPath();
        }
        if (d instanceof ModuleBackedDeployment m) {
            return m.getOutputPath().toString();
        }
        return ((ExternalFileDeployment) d).getExternalPath().toString();
    }

    // =====================================================================
    // Cloning & object methods
    // =====================================================================

    @NotNull
    @Override
    public DeploymentConfig clone() {
        try {
            DeploymentConfig clone = (DeploymentConfig) super.clone();
            // Typed deployments are immutable — a fresh list of the same elements is a deep copy.
            clone.deployments = new ArrayList<>(this.deployments);
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
                && deployments.equals(that.deployments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deployments, hotDeploymentEnabled,
                updateClassesAndResources, preserveSessions);
    }

    @NotNull
    public String getSummary() {
        return String.format("DeploymentConfig{artifacts=%d, hotDeploy=%s, updateClasses=%s}",
                deployments.size(), hotDeploymentEnabled, updateClassesAndResources);
    }

    @NotNull
    @Override
    public String toString() {
        return getSummary();
    }
}
