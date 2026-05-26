package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentAdapter;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles legacy {@link DeploymentArtifact} storage against the current
 * project model. The reconciliation is a one-pass walk: each stored entry is
 * built into a typed {@link ArtifactBackedDeployment} whose
 * {@link com.intellij.packaging.artifacts.ArtifactPointer} the platform
 * already updates on rename / delete; if the pointer reports a different
 * name or output path than what's stored, the legacy entry is patched in
 * place.
 *
 * <p>This replaces a 4-strategy fuzzy matcher (exact-name, output-path,
 * base-module-name, with a separate EXTERNAL provenance guard). All of
 * those concerns are now structural properties of the typed model rather
 * than runtime heuristics.
 *
 * <p>Module-backed and external deployments don't participate — neither has a
 * platform Artifact to rename-track against.
 */
public final class ArtifactReferenceRefresher {

    private static final Logger LOG = Logger.getInstance(ArtifactReferenceRefresher.class);

    private ArtifactReferenceRefresher() {}

    /**
     * One {@link DeploymentArtifact} that was updated. {@code newPath} matches
     * {@code oldPath} when only the name drifted, and vice versa.
     */
    public record RefreshAction(@NotNull String oldName, @NotNull String oldPath,
                                @NotNull String newName, @NotNull String newPath) {

        @Override
        public String toString() {
            boolean nameChanged = !oldName.equals(newName);
            boolean pathChanged = !oldPath.equals(newPath);
            if (nameChanged && pathChanged) {
                return "renamed '" + oldName + "' (" + oldPath + ") → '"
                        + newName + "' (" + newPath + ")";
            }
            if (nameChanged) return "renamed '" + oldName + "' → '" + newName + "'";
            return "path drift '" + oldName + "': " + oldPath + " → " + newPath;
        }
    }

    /**
     * Aggregate of {@link RefreshAction}s. Same accessors the previous
     * snapshot-based implementation exposed (callers compile unchanged).
     */
    public static final class RefreshResult {
        public static final RefreshResult EMPTY = new RefreshResult(List.of());

        private final List<RefreshAction> actions;

        public RefreshResult(@NotNull List<RefreshAction> actions) {
            this.actions = List.copyOf(actions);
        }

        public boolean hasUpdates()              { return !actions.isEmpty(); }
        public int getUpdateCount()              { return actions.size(); }
        public int getUnresolvedCount()          { return 0; }
        public @NotNull List<RefreshAction> getUpdatedActions() { return actions; }
    }

    @NotNull
    public static RefreshResult refresh(@NotNull TomcatRunConfiguration config) {
        return TomcatReadActions.compute(() ->
                refreshInternal(config.getProject(),
                        config.getConfigData().getDeploymentConfig().getArtifacts(),
                        config.getConfigData().getDeploymentConfig()));
    }

    /**
     * Refreshes a live deployment-artifact list against the current model.
     * The list elements are mutated in place — same object references as the
     * UI table holds, so the caller doesn't need to reload.
     */
    @NotNull
    public static RefreshResult refreshInPlace(@NotNull Project project,
                                               @NotNull List<DeploymentArtifact> artifacts) {
        if (artifacts.isEmpty()) return RefreshResult.EMPTY;
        return TomcatReadActions.compute(() -> refreshInternal(project, artifacts, null));
    }

    /**
     * Core walk. For each stored entry whose source is INTELLIJ_ARTIFACT, build
     * the typed pointer, query its current name/path, and patch the legacy
     * entry if they differ.
     *
     * <p><b>Must be called under a read action.</b>
     *
     * @param config when non-null, the result list is pushed back via
     *               {@link DeploymentConfig#setArtifacts} so a future change
     *               to legacy storage semantics (e.g. defensive-copy on read)
     *               doesn't lose the mutations.
     */
    @NotNull
    private static RefreshResult refreshInternal(@NotNull Project project,
                                                 @NotNull List<DeploymentArtifact> artifacts,
                                                 @Nullable DeploymentConfig config) {
        List<RefreshAction> actions = new ArrayList<>();
        for (DeploymentArtifact stale : artifacts) {
            if (stale == null) continue;
            if (stale.getSource() != DeploymentArtifact.Source.INTELLIJ_ARTIFACT) continue;

            Deployment typed;
            try {
                typed = DeploymentAdapter.toTyped(project, stale);
            } catch (Throwable t) {
                LOG.debug("ArtifactReferenceRefresher: toTyped failed for '"
                        + stale.getName() + "': " + t.getMessage());
                continue;
            }
            if (!(typed instanceof ArtifactBackedDeployment a)) continue;

            Artifact platformArtifact = a.getArtifactPointer().getArtifact();
            if (platformArtifact == null) continue; // truly orphaned — leave the stale entry

            String storedName = stale.getName();
            String storedPath = stale.getPath();
            String currentName = a.getArtifactName();
            String currentPath = platformArtifact.getOutputFilePath();
            if (currentPath == null) currentPath = storedPath;

            // Sync name and path only. The 'type' field (exploded vs. WAR) is
            // deliberately NOT re-synced here — DeploymentAdapter.toTyped() reads
            // it live from the current Artifact on every conversion, so a type
            // change in Project Structure takes effect on the next typed read
            // without mutating the stored DeploymentArtifact.
            boolean nameChanged = !currentName.equals(storedName);
            boolean pathChanged = !currentPath.equals(storedPath);
            if (!nameChanged && !pathChanged) continue;

            if (nameChanged) stale.setName(currentName);
            if (pathChanged) stale.setPath(currentPath);
            actions.add(new RefreshAction(storedName, storedPath, currentName, currentPath));
        }

        if (!actions.isEmpty() && config != null) {
            // Push back so callers see the mutations even if getArtifacts() ever
            // starts returning deep copies.
            config.setArtifacts(artifacts);
            LOG.info("ArtifactReferenceRefresher: " + actions.size() + " artifact reference(s) refreshed");
        }
        return new RefreshResult(actions);
    }
}
