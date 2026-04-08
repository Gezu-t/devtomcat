package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.packaging.artifacts.ArtifactManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reconciles stored {@link DeploymentArtifact} references against the current state
 * of IntelliJ's {@link ArtifactManager}.
 *
 * <p>When a user renames a module or artifact in Project Structure, the plugin's
 * serialized name/path strings become stale. This refresher detects and repairs
 * such mismatches using a multi-strategy matching approach:
 *
 * <ol>
 *   <li><b>Exact name match</b> — artifact reference is current; output path is
 *       updated if it drifted (e.g. user changed the output directory).</li>
 *   <li><b>Output path match</b> — artifact was renamed but its output directory
 *       is unchanged. The stored name is updated to the current artifact name.</li>
 *   <li><b>Base module name match</b> — artifact was renamed in a way that preserves
 *       the module root identifier (e.g. {@code myapp:war exploded} renamed to
 *       {@code myapp:war}). Both name and path are updated. When multiple IntelliJ
 *       artifacts share the same base module name, the one whose deployment type
 *       (exploded vs. WAR) matches the stored artifact is preferred.</li>
 * </ol>
 *
 * <p>External artifacts ({@link DeploymentArtifact#TYPE_EXTERNAL}) are never modified
 * because they have no corresponding IntelliJ Artifact definition.
 *
 * <p>The core matching logic operates on {@link PlatformArtifactSnapshot} records,
 * decoupled from the IntelliJ {@link Artifact} API. This separation allows the
 * matching to be tested without IntelliJ platform infrastructure or mocking.
 *
 * <p>Called from:
 * <ul>
 *   <li>{@link TomcatConfigurationInitializer#refresh} — after deserialization</li>
 *   <li>{@link com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor} — before UI population</li>
 * </ul>
 */
public final class ArtifactReferenceRefresher {

    private static final Logger LOG = Logger.getInstance(ArtifactReferenceRefresher.class);

    private ArtifactReferenceRefresher() {}

    // =========================================================================
    // Platform adapter
    // =========================================================================

    /**
     * Lightweight snapshot of an IntelliJ {@link Artifact}, capturing only the fields
     * needed for matching. Decouples the core refresh logic from the platform API
     * so it can be tested with plain objects.
     *
     * @param name       the artifact name (e.g. "myapp:war exploded")
     * @param outputPath the artifact's output file/directory path (may be empty)
     * @param exploded   true if this is an exploded (directory-based) deployment
     */
    public record PlatformArtifactSnapshot(
            @NotNull String name,
            @NotNull String outputPath,
            boolean exploded
    ) {
        /** Creates a snapshot from an IntelliJ {@link Artifact}. */
        @NotNull
        static PlatformArtifactSnapshot fromPlatform(@NotNull Artifact artifact) {
            String path = artifact.getOutputFilePath();
            return new PlatformArtifactSnapshot(
                    artifact.getName(),
                    path != null ? path : "",
                    detectExploded(artifact)
            );
        }

        private static boolean detectExploded(@NotNull Artifact artifact) {
            try {
                String typeId = artifact.getArtifactType().getId();
                if (typeId != null && typeId.toLowerCase().contains("exploded")) {
                    return true;
                }
            } catch (Exception ignored) {
                // Defensive — type might not be resolvable in all environments
            }
            return artifact.getName().toLowerCase().contains("exploded");
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Refreshes all deployment artifact references in the given configuration.
     *
     * <p>Gracefully handles Community Edition where {@link ArtifactManager} may not
     * be loadable ({@link NoClassDefFoundError}), and projects where no IntelliJ
     * artifacts are configured (returns an empty result).
     *
     * @param config the run configuration whose deployment artifacts to refresh
     * @return a result documenting every action taken (never null)
     */
    @NotNull
    public static RefreshResult refresh(@NotNull TomcatRunConfiguration config) {
        Objects.requireNonNull(config, "Configuration cannot be null");

        // Collect all IntelliJ model data under a single read action and convert to
        // plain PlatformArtifactSnapshot records immediately. ArtifactManager.getArtifacts()
        // and ModuleManager.getModules() both require a read action; refresh() is called
        // from TomcatRunConfiguration.readExternal() on a background coroutine thread
        // (ProjectRunConfigurationInitializer) where no read action is held by default.
        PlatformArtifactSnapshot[] snapshots = ReadAction.compute(
                () -> collectArtifactSnapshots(config));

        if (snapshots == null || snapshots.length == 0) {
            return RefreshResult.EMPTY;
        }

        return refreshArtifacts(config.getConfigData().getDeploymentConfig(), snapshots);
    }

    /**
     * Collects the current IntelliJ artifact state into plain snapshots.
     * <strong>Must be called under a read action.</strong>
     *
     * @return filtered snapshot array, or {@code null} / empty if ArtifactManager is
     *         unavailable or no artifacts are configured
     */
    @Nullable
    private static PlatformArtifactSnapshot[] collectArtifactSnapshots(
            @NotNull TomcatRunConfiguration config) {
        try {
            ArtifactManager artifactManager = ArtifactManager.getInstance(config.getProject());
            Artifact[] platformArtifacts = artifactManager.getArtifacts();
            if (platformArtifacts.length == 0) return null;

            // Collect active module names to exclude orphaned IntelliJ artifacts.
            // When a user renames a module, IntelliJ may keep the OLD artifact definition
            // alongside the new one. Without this filter, Strategy 1 (exact name match)
            // would match the orphaned artifact, leaving deployment pointing at stale output.
            java.util.Set<String> activeModules = new java.util.HashSet<>();
            try {
                for (Module m :
                        ModuleManager.getInstance(config.getProject()).getModules()) {
                    activeModules.add(m.getName().toLowerCase());
                }
            } catch (Exception e) {
                LOG.debug("ArtifactReferenceRefresher: Could not read modules, skipping orphan filter");
            }

            List<PlatformArtifactSnapshot> filtered = new ArrayList<>();
            for (Artifact pa : platformArtifacts) {
                PlatformArtifactSnapshot snap = PlatformArtifactSnapshot.fromPlatform(pa);
                String baseName = ContextPathUtils.extractBaseModuleName(snap.name()).toLowerCase();
                if (activeModules.isEmpty() || baseName.isEmpty() || activeModules.contains(baseName)) {
                    filtered.add(snap);
                } else {
                    LOG.debug("ArtifactReferenceRefresher: Excluding orphaned artifact '" +
                            snap.name() + "' (module '" + baseName + "' no longer exists)");
                }
            }

            return filtered.isEmpty() ? null : filtered.toArray(new PlatformArtifactSnapshot[0]);
        } catch (NoClassDefFoundError | Exception e) {
            LOG.debug("ArtifactReferenceRefresher: ArtifactManager unavailable, skipping refresh");
            return null;
        }
    }

    // =========================================================================
    // Core matching logic (platform-independent)
    // =========================================================================

    /**
     * Core refresh logic, decoupled from IntelliJ platform APIs.
     *
     * <p>Iterates each {@link DeploymentArtifact} and attempts to match it against
     * the provided snapshots using the three-strategy cascade. Updates stale
     * name/path fields in place.
     *
     * @param deploymentConfig  the deployment config holding the artifacts to refresh
     * @param platformSnapshots current IntelliJ artifact snapshots
     * @return a result documenting every action taken
     */
    @NotNull
    static RefreshResult refreshArtifacts(@NotNull DeploymentConfig deploymentConfig,
                                          @NotNull PlatformArtifactSnapshot[] platformSnapshots) {
        // getArtifacts() returns a defensive copy — the DeploymentArtifact instances are
        // shared with the config's internal list, so field mutations (setName/setPath) on
        // these objects propagate back. We work on the copy for iteration safety, then
        // push it back via setArtifacts() to ensure the config's internal list reference
        // is also updated. This is safe even if getArtifacts() is later changed to
        // deep-copy, because we always call setArtifacts() at the end.
        List<DeploymentArtifact> artifacts = deploymentConfig.getArtifacts();
        if (artifacts.isEmpty()) {
            return RefreshResult.EMPTY;
        }

        List<RefreshAction> actions = new ArrayList<>();

        for (DeploymentArtifact deployment : artifacts) {
            if (deployment == null) continue;

            // External artifacts are user-provided paths — no IntelliJ Artifact backing
            if (DeploymentArtifact.TYPE_EXTERNAL.equals(deployment.getType())) {
                continue;
            }

            RefreshAction action = reconcileSingleArtifact(deployment, platformSnapshots);
            if (action != null) {
                actions.add(action);
            }
        }

        RefreshResult result = new RefreshResult(actions);

        if (result.hasUpdates()) {
            // Always push the list back unconditionally — if getArtifacts() ever changes
            // to deep-copy, the mutations above would be lost without this setArtifacts()
            // call. This makes the code robust regardless of DeploymentConfig internals.
            deploymentConfig.setArtifacts(artifacts);
            LOG.info("ArtifactReferenceRefresher: " + result.getUpdateCount() +
                    " artifact(s) updated, " + result.getUnresolvedCount() + " unresolved");
        }

        return result;
    }

    /**
     * Attempts to match a single {@link DeploymentArtifact} against platform snapshots
     * and updates it if stale.
     *
     * @return a {@link RefreshAction} if the artifact was updated or unresolved,
     *         {@code null} if it matched exactly with no changes needed
     */
    @Nullable
    private static RefreshAction reconcileSingleArtifact(
            @NotNull DeploymentArtifact deployment,
            @NotNull PlatformArtifactSnapshot[] snapshots) {

        String storedName = deployment.getName();
        String storedPath = deployment.getPath();

        // --- Strategy 1: Exact name match ---
        PlatformArtifactSnapshot exactMatch = findByExactName(storedName, snapshots);
        if (exactMatch != null) {
            if (!storedPath.equals(exactMatch.outputPath()) && !exactMatch.outputPath().isEmpty()) {
                // Name is current but output path drifted (user changed output dir)
                deployment.setPath(exactMatch.outputPath());
                LOG.info("ArtifactReferenceRefresher: Updated path for '" + storedName +
                        "': " + storedPath + " -> " + exactMatch.outputPath());
                return new RefreshAction(deployment, storedName, storedPath,
                        storedName, exactMatch.outputPath(), MatchStrategy.EXACT_NAME);
            }
            // Fully current — nothing to do
            return null;
        }

        // --- Strategy 2: Output path match ---
        PlatformArtifactSnapshot pathMatch = findByOutputPath(storedPath, snapshots);
        if (pathMatch != null) {
            String newPath = pathMatch.outputPath().isEmpty() ? storedPath : pathMatch.outputPath();
            deployment.setName(pathMatch.name());
            if (!pathMatch.outputPath().isEmpty()) {
                deployment.setPath(pathMatch.outputPath());
            }
            LOG.info("ArtifactReferenceRefresher: Resolved stale name '" + storedName +
                    "' -> '" + pathMatch.name() + "' (matched by output path)");
            return new RefreshAction(deployment, storedName, storedPath,
                    pathMatch.name(), newPath, MatchStrategy.OUTPUT_PATH);
        }

        // --- Strategy 3: Base module name match with type affinity ---
        PlatformArtifactSnapshot moduleMatch = findByBaseModuleName(
                storedName, deployment.getType(), snapshots);
        if (moduleMatch != null) {
            String newPath = moduleMatch.outputPath().isEmpty() ? storedPath : moduleMatch.outputPath();
            deployment.setName(moduleMatch.name());
            if (!moduleMatch.outputPath().isEmpty()) {
                deployment.setPath(moduleMatch.outputPath());
            }
            LOG.info("ArtifactReferenceRefresher: Resolved stale name '" + storedName +
                    "' -> '" + moduleMatch.name() + "' (matched by base module name)");
            return new RefreshAction(deployment, storedName, storedPath,
                    moduleMatch.name(), newPath, MatchStrategy.BASE_MODULE_NAME);
        }

        // --- No match found ---
        LOG.debug("ArtifactReferenceRefresher: No match for '" + storedName +
                "' (path: " + storedPath + ")");
        return new RefreshAction(deployment, storedName, storedPath,
                storedName, storedPath, MatchStrategy.UNRESOLVED);
    }

    // =========================================================================
    // Matching strategies
    // =========================================================================

    @Nullable
    private static PlatformArtifactSnapshot findByExactName(
            @NotNull String name, @NotNull PlatformArtifactSnapshot[] snapshots) {
        for (PlatformArtifactSnapshot s : snapshots) {
            if (name.equals(s.name())) return s;
        }
        return null;
    }

    @Nullable
    private static PlatformArtifactSnapshot findByOutputPath(
            @NotNull String path, @NotNull PlatformArtifactSnapshot[] snapshots) {
        if (path.isEmpty()) return null;
        for (PlatformArtifactSnapshot s : snapshots) {
            if (!s.outputPath().isEmpty() && path.equals(s.outputPath())) return s;
        }
        return null;
    }

    /**
     * Finds a snapshot whose base module name matches the stored artifact's base module
     * name. When multiple candidates exist, prefers the one whose deployment type
     * (exploded vs. WAR) matches the stored artifact's type.
     *
     * <p>Base module name is extracted by {@link ContextPathUtils#extractBaseModuleName},
     * which strips common suffixes like {@code _war_exploded}, {@code :war}, etc.
     */
    @Nullable
    private static PlatformArtifactSnapshot findByBaseModuleName(
            @NotNull String storedName,
            @NotNull String storedType,
            @NotNull PlatformArtifactSnapshot[] snapshots) {

        String storedBase = ContextPathUtils.extractBaseModuleName(storedName);
        if (storedBase.isEmpty()) return null;

        List<PlatformArtifactSnapshot> candidates = new ArrayList<>();
        for (PlatformArtifactSnapshot s : snapshots) {
            if (storedBase.equals(ContextPathUtils.extractBaseModuleName(s.name()))) {
                candidates.add(s);
            }
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        // Multiple candidates — prefer type affinity
        boolean wantExploded = DeploymentArtifact.TYPE_EXPLODED.equals(storedType);
        for (PlatformArtifactSnapshot c : candidates) {
            if (wantExploded == c.exploded()) return c;
        }
        return candidates.get(0);
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /** Describes how a deployment artifact was matched (or not) to an IntelliJ artifact. */
    public enum MatchStrategy {
        /** Artifact name matched exactly. Path may have been updated. */
        EXACT_NAME,
        /** Matched by output path — artifact was renamed, output directory unchanged. */
        OUTPUT_PATH,
        /** Matched by base module name — artifact renamed preserving module root. */
        BASE_MODULE_NAME,
        /** No matching IntelliJ artifact found. Artifact left unchanged. */
        UNRESOLVED
    }

    /**
     * Records a single refresh action: what was changed, from what to what, and why.
     * Immutable after construction.
     */
    public static final class RefreshAction {
        private final DeploymentArtifact artifact;
        private final String oldName;
        private final String oldPath;
        private final String newName;
        private final String newPath;
        private final MatchStrategy strategy;

        RefreshAction(@NotNull DeploymentArtifact artifact,
                      @NotNull String oldName, @NotNull String oldPath,
                      @NotNull String newName, @NotNull String newPath,
                      @NotNull MatchStrategy strategy) {
            this.artifact = artifact;
            this.oldName = oldName;
            this.oldPath = oldPath;
            this.newName = newName;
            this.newPath = newPath;
            this.strategy = strategy;
        }

        @NotNull public DeploymentArtifact getArtifact() { return artifact; }
        @NotNull public String getOldName() { return oldName; }
        @NotNull public String getOldPath() { return oldPath; }
        @NotNull public String getNewName() { return newName; }
        @NotNull public String getNewPath() { return newPath; }
        @NotNull public MatchStrategy getStrategy() { return strategy; }

        public boolean isUpdated() {
            return strategy != MatchStrategy.UNRESOLVED;
        }

        public boolean isNameChanged() {
            return !oldName.equals(newName);
        }

        public boolean isPathChanged() {
            return !oldPath.equals(newPath);
        }

        @Override
        public String toString() {
            if (strategy == MatchStrategy.UNRESOLVED) {
                return "UNRESOLVED: '" + oldName + "'";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(strategy).append(": '").append(oldName).append("'");
            if (isNameChanged()) {
                sb.append(" -> '").append(newName).append("'");
            }
            if (isPathChanged()) {
                sb.append(" [path updated]");
            }
            return sb.toString();
        }
    }

    /**
     * Aggregates all refresh actions for a single refresh pass.
     * Provides convenience queries for diagnostics and testing.
     */
    public static final class RefreshResult {
        static final RefreshResult EMPTY = new RefreshResult(Collections.emptyList());

        private final List<RefreshAction> actions;

        RefreshResult(@NotNull List<RefreshAction> actions) {
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }

        @NotNull
        public List<RefreshAction> getActions() { return actions; }

        public boolean hasUpdates() {
            return actions.stream().anyMatch(RefreshAction::isUpdated);
        }

        public boolean hasUnresolved() {
            return actions.stream().anyMatch(a -> a.getStrategy() == MatchStrategy.UNRESOLVED);
        }

        public int getUpdateCount() {
            return (int) actions.stream().filter(RefreshAction::isUpdated).count();
        }

        public int getUnresolvedCount() {
            return (int) actions.stream()
                    .filter(a -> a.getStrategy() == MatchStrategy.UNRESOLVED).count();
        }

        @NotNull
        public List<RefreshAction> getUpdatedActions() {
            return actions.stream().filter(RefreshAction::isUpdated).toList();
        }

        @NotNull
        public List<RefreshAction> getUnresolvedActions() {
            return actions.stream()
                    .filter(a -> a.getStrategy() == MatchStrategy.UNRESOLVED)
                    .toList();
        }

        @Override
        public String toString() {
            if (actions.isEmpty()) return "RefreshResult{no actions}";
            return "RefreshResult{" + actions.size() + " action(s): " +
                    getUpdateCount() + " updated, " +
                    getUnresolvedCount() + " unresolved}";
        }
    }
}
