package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles persisted deployment entries against the current project model.
 * Walks the STORED deployment list — never the resolved view; resolution is a
 * read-only projection and must not leak into storage. Each
 * {@link ArtifactBackedDeployment} whose
 * {@link com.intellij.packaging.artifacts.ArtifactPointer} resolves is checked
 * for drift between the live artifact's output path / packaging and the
 * persisted last-known values; on drift, only that entry is replaced with a
 * fresh deployment carrying the live values and the stored list is written
 * back through {@link DeploymentConfig#setDeployments}.
 *
 * <p>Name refresh is obsolete: the pointer rename-tracks and the serializer
 * writes the live pointer name, so a rename persists with no reconciliation.
 * Packaging IS reconciled — the serializer persists {@code lastKnownExploded}
 * (pure data, no pointer deref), so a war↔exploded flip in Project Structure
 * only reaches the XML through this pass.
 *
 * <p>This replaces a 4-strategy fuzzy matcher (exact-name, output-path,
 * base-module-name, with a separate EXTERNAL provenance guard). All of
 * those concerns are now structural properties of the typed model rather
 * than runtime heuristics.
 *
 * <p>Module-backed and external deployments don't participate — neither has a
 * platform Artifact to track against.
 */
public final class ArtifactReferenceRefresher {

    private static final Logger LOG = Logger.getInstance(ArtifactReferenceRefresher.class);

    private ArtifactReferenceRefresher() {}

    /**
     * One deployment entry that was updated: path and/or packaging drift
     * against the live artifact.
     */
    public record RefreshAction(@NotNull String name,
                                @NotNull String oldPath, @NotNull String newPath,
                                boolean oldExploded, boolean newExploded) {

        @Override
        public String toString() {
            boolean pathChanged = !oldPath.equals(newPath);
            boolean packagingChanged = oldExploded != newExploded;
            StringBuilder sb = new StringBuilder("refreshed '").append(name).append("':");
            if (pathChanged) sb.append(" path ").append(oldPath).append(" → ").append(newPath);
            if (packagingChanged) {
                if (pathChanged) sb.append(',');
                sb.append(" packaging ").append(packaging(oldExploded))
                        .append(" → ").append(packaging(newExploded));
            }
            return sb.toString();
        }

        private static String packaging(boolean exploded) {
            return exploded ? "exploded" : "war";
        }
    }

    /**
     * Aggregate of {@link RefreshAction}s.
     */
    public static final class RefreshResult {
        public static final RefreshResult EMPTY = new RefreshResult(List.of());

        private final List<RefreshAction> actions;

        public RefreshResult(@NotNull List<RefreshAction> actions) {
            this.actions = List.copyOf(actions);
        }

        public boolean hasUpdates()              { return !actions.isEmpty(); }
        public int getUpdateCount()              { return actions.size(); }
        public @NotNull List<RefreshAction> getUpdatedActions() { return actions; }
    }

    @NotNull
    public static RefreshResult refresh(@NotNull TomcatRunConfiguration config) {
        return TomcatReadActions.compute(() ->
                refreshInternal(config.getConfigData().getDeploymentConfig()));
    }

    /**
     * Core walk over the stored list. For each artifact-backed deployment
     * whose pointer resolves, query the live artifact's output path and
     * packaging; if either differs from the persisted last-known values,
     * replace that entry (and only it) with a fresh
     * {@link ArtifactBackedDeployment} on the same pointer.
     *
     * <p><b>Must be called under a read action.</b> The list is only written
     * back through {@link DeploymentConfig#setDeployments} when at least one
     * entry drifted, so a clean pass never churns storage; untouched entries
     * persist unchanged.
     */
    @NotNull
    static RefreshResult refreshInternal(@NotNull DeploymentConfig config) {
        List<Deployment> deployments = config.getDeployments();
        if (deployments.isEmpty()) return RefreshResult.EMPTY;

        List<RefreshAction> actions = new ArrayList<>();
        for (int i = 0; i < deployments.size(); i++) {
            if (!(deployments.get(i) instanceof ArtifactBackedDeployment stale)) continue;

            Artifact platformArtifact = stale.getArtifactPointer().getArtifact();
            if (platformArtifact == null) continue; // dangling — leave the stored entry untouched

            String storedPath = stale.getLastKnownPath() == null ? "" : stale.getLastKnownPath();
            String currentPath = platformArtifact.getOutputFilePath();
            if (currentPath == null) currentPath = storedPath;
            boolean storedExploded = stale.getLastKnownExploded();
            boolean currentExploded = stale.isExploded(); // live — the pointer resolves

            boolean pathChanged = !currentPath.equals(storedPath);
            boolean packagingChanged = currentExploded != storedExploded;
            if (!pathChanged && !packagingChanged) continue;

            deployments.set(i, new ArtifactBackedDeployment(
                    stale.getArtifactPointer(), stale.getContextPath(), currentPath, currentExploded));
            actions.add(new RefreshAction(stale.getArtifactName(),
                    storedPath, currentPath, storedExploded, currentExploded));
        }

        if (!actions.isEmpty()) {
            config.setDeployments(deployments);
            LOG.info("ArtifactReferenceRefresher: " + actions.size() + " artifact reference(s) refreshed");
        }
        return new RefreshResult(actions);
    }
}
