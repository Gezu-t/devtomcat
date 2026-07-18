package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-click migration that flips a WAR-packaged {@link Deployment} to point at
 * its sibling exploded directory <em>and</em> reclaims the deployment as
 * module-owned so hot class/resource sync can drive it.
 *
 * <p>Maven's {@code maven-war-plugin} produces both {@code target/<finalName>.war}
 * (the packaged archive) and {@code target/<finalName>/} (the exploded staging
 * directory it builds the archive from) during {@code mvn package}. Gradle's
 * {@code war} task likewise produces both forms. When the user picked the
 * {@code .war} file during auto-detection or via External Source, hot sync
 * skips the deployment for two reasons:
 * <ol>
 *   <li>A packaged deployment means Tomcat's classloader holds the sealed
 *       archive open and mutating it from outside is unsafe.</li>
 *   <li>An {@link ExternalFileDeployment} by design has no module link —
 *       {@link DeployedClassesSync} has nowhere to copy class output from.</li>
 * </ol>
 *
 * <p>This fix addresses both. For every WAR deployment whose sibling exploded
 * directory exists and lives under a project module's content roots, the fix
 * replaces the entry with a {@link ModuleBackedDeployment}:
 * <ul>
 *   <li>path: {@code .../target/foo.war} → {@code .../target/foo}</li>
 *   <li>packaging: war → exploded</li>
 *   <li>owner: the resolved IntelliJ module, held through a real
 *       {@code ModulePointer} so class sync has a module to pull from and the
 *       loader's direct-by-name lookup matches without falling through to the
 *       suffix-stripping tier</li>
 * </ul>
 *
 * <p>Candidates are only offered when:
 * <ol>
 *   <li>The deployment is currently WAR-packaged.</li>
 *   <li>The sibling exploded directory exists on disk AND has a {@code WEB-INF/}
 *       subdirectory (the canonical "real exploded webapp" marker).</li>
 *   <li>The exploded directory falls under a project module's content roots —
 *       otherwise no module owns it and class sync can't work after the flip.</li>
 * </ol>
 *
 * <p>After applying, hot sync via {@code Ctrl+F10} works for the deployment
 * with no further user action — Java edits land via {@link DeployedClassesSync}
 * and webapp resources via {@link WebResourcesSync}, both writing into the
 * directory the deployment now points at, both pulling from the module the
 * deployment now identifies.
 */
public final class WarToExplodedQuickFix {

    private static final Logger LOG = Logger.getInstance(WarToExplodedQuickFix.class);

    private static final String WAR_EXTENSION = ".war";
    private static final String WEB_INF_MARKER = "WEB-INF";

    private WarToExplodedQuickFix() {}

    /**
     * One candidate flip: the deployment that will be replaced, the directory
     * its replacement will point at, and the module name to claim as the new
     * owner. The module name is captured at scan time so apply doesn't need a
     * fresh read action.
     */
    public record FixCandidate(@NotNull Deployment deployment,
                               @NotNull Path explodedDirectory,
                               @NotNull String moduleName) {}

    /**
     * Looks up which project module owns a given path by content-root
     * containment. Split into an interface so tests can drive the candidate
     * detection without standing up a full IntelliJ {@link Project}.
     */
    @FunctionalInterface
    public interface ModuleOwnershipResolver {
        /**
         * Returns the name of the module whose content root is the deepest
         * prefix of {@code explodedPath}, or {@code null} if no module owns it.
         */
        @Nullable String resolveOwningModule(@NotNull Path explodedPath);
    }

    /**
     * Project-bound entry point. Wraps the module traversal in a read action
     * and delegates to the test-visible
     * {@link #findFixableArtifacts(ModuleOwnershipResolver, List)} overload.
     */
    @NotNull
    public static List<FixCandidate> findFixableArtifacts(@NotNull Project project,
                                                          @NotNull List<Deployment> deployments) {
        if (project.isDisposed()) return List.of();
        return TomcatReadActions.compute(() -> {
            ModuleOwnershipResolver resolver = buildResolverFor(project);
            return findFixableArtifacts(resolver, deployments);
        });
    }

    /**
     * Scans the typed deployment list for entries that can be reclaimed as
     * module-owned exploded deployments. Two shapes qualify:
     *
     * <ol>
     *   <li><b>WAR deployment with a sibling exploded directory</b> — the
     *       primary case. Packaging is war; the resolved path ends with
     *       {@code .war} and the sibling {@code target/<name>/} directory
     *       exists with {@code WEB-INF/}. The fix replaces the entry with a
     *       module-backed exploded deployment.</li>
     *
     *   <li><b>Already-exploded {@link ExternalFileDeployment}</b> —
     *       the recovery case. The path already points at an exploded
     *       directory, but an external deployment has no module link, so
     *       class sync reports "could not resolve owning module — null". This
     *       happens when an earlier flip changed path/packaging but left the
     *       entry external — see commit {@code b4b1fd6}'s fix description for
     *       the regression this catches. The fix reclaims it as module-backed
     *       at the same path (so applying twice is idempotent).</li>
     * </ol>
     *
     * <p>Both shapes additionally require the exploded directory to fall
     * under a project module's content roots — otherwise no module owns the
     * path and class sync still wouldn't work after the flip.
     *
     * <p>Returns a fresh list — caller may freely mutate.
     */
    @NotNull
    static List<FixCandidate> findFixableArtifacts(@NotNull ModuleOwnershipResolver resolver,
                                                   @NotNull List<Deployment> deployments) {
        List<FixCandidate> out = new ArrayList<>();
        for (Deployment deployment : deployments) {
            if (deployment == null) continue;
            // Debug logging: each deployment rejection emits a structured line so a
            // user reporting "balloon didn't fire" can hand back a log fragment
            // that pinpoints which check failed without us having to instrument live.
            String label = "'" + deployment.getDisplayName() + "' (kind=" + deployment.getKind()
                    + ", exploded=" + deployment.isExploded()
                    + ", path=" + deployment.getResolvedPath() + ")";
            Path explodedPath = findCandidateExplodedPath(deployment);
            if (explodedPath == null) {
                LOG.debug("Reclaim scan: skipping " + label
                        + " — not a fixable shape (need WAR with sibling, or EXTERNAL+EXPLODED)");
                continue;
            }
            if (!isExplodedWebapp(explodedPath)) {
                LOG.debug("Reclaim scan: skipping " + label
                        + " — candidate path " + explodedPath
                        + " is not a directory with WEB-INF/");
                continue;
            }
            String moduleName = resolver.resolveOwningModule(explodedPath);
            if (moduleName == null) {
                LOG.debug("Reclaim scan: skipping " + label
                        + " — candidate path " + explodedPath
                        + " is not under any project module's content roots");
                continue;
            }
            LOG.info("Reclaim scan: candidate " + label
                    + " → module '" + moduleName + "' at " + explodedPath);
            out.add(new FixCandidate(deployment, explodedPath, moduleName));
        }
        return out;
    }

    /**
     * For a fixable deployment, returns the exploded directory the fix will
     * point it at. Returns {@code null} when the deployment doesn't match
     * either of the two fixable shapes (see
     * {@link #findFixableArtifacts(ModuleOwnershipResolver, List)}).
     */
    @Nullable
    private static Path findCandidateExplodedPath(@NotNull Deployment deployment) {
        if (!deployment.isExploded()) {
            // Primary case: derive sibling directory from the .war filename.
            Path resolved = deployment.getResolvedPath();
            return deriveExplodedPath(resolved == null ? null : resolved.toString());
        }
        if (deployment instanceof ExternalFileDeployment) {
            // Recovery case: path already points at the exploded directory but
            // an external deployment has no module link for class sync.
            return deployment.getResolvedPath();
        }
        return null;
    }

    /**
     * Applies the flip to every candidate. Each fixable entry is replaced by a
     * {@link ModuleBackedDeployment} (same context path, exploded packaging, a
     * real module pointer) and the list is written back on the same
     * {@link DeploymentConfig} the config holds; IntelliJ's run-config
     * persistence picks up the change on its next save tick.
     *
     * <p>Returns the number of deployments actually replaced (some candidates
     * may have become stale between {@link #findFixableArtifacts} and this
     * call, e.g. if {@code mvn clean} ran in between).
     */
    public static int applyAll(@NotNull TomcatRunConfiguration config,
                               @NotNull List<FixCandidate> candidates) {
        Project project = config.getProject();
        DeploymentConfig deploymentConfig = config.getConfigData().getDeploymentConfig();
        // Candidates were detected on the RESOLVED view, but the written list
        // must start from STORAGE — persisting the resolved view would bake
        // the resolver's read-only folds (e.g. dangling artifact→module) into
        // storage for entries the user never asked to convert. The resolved
        // view maps storage 1:1 positionally, so an index found in it
        // addresses the same slot in the stored list.
        List<Deployment> resolved = deploymentConfig.getDeployments(project);
        List<Deployment> stored = deploymentConfig.getDeployments();
        int applied = 0;
        for (FixCandidate candidate : candidates) {
            if (!isExplodedWebapp(candidate.explodedDirectory())) {
                LOG.info("Skipping stale candidate (directory disappeared): "
                        + candidate.explodedDirectory());
                continue;
            }
            int index = resolved.indexOf(candidate.deployment());
            if (index < 0 || index >= stored.size()) {
                LOG.info("Skipping stale candidate (no longer in the deployment list): "
                        + candidate.deployment().getDisplayName());
                continue;
            }
            // Reclaim as module-owned: without a module pointer the loader has
            // no module link and class sync silently skips the deployment.
            ModuleBackedDeployment replacement = ModuleBackedDeployment.ofName(
                    project,
                    candidate.moduleName(),
                    candidate.explodedDirectory(),
                    candidate.deployment().getContextPath(),
                    true);
            stored.set(index, replacement);
            // Mirror into the resolved snapshot so a later candidate's indexOf
            // can't re-match this consumed slot.
            resolved.set(index, replacement);
            applied++;
        }

        if (applied > 0) {
            // Write back on the same DeploymentConfig the config holds. Run-config
            // state is persisted by IntelliJ on its next save tick (run-config
            // editor open/close, IDE shutdown, etc.) — no explicit RunManager
            // nudge needed. In-memory state is already correct for the next launch.
            deploymentConfig.setDeployments(stored);
            LOG.info("Flipped " + applied + " WAR deployment(s) to exploded type");
        }
        return applied;
    }

    /**
     * Maps a {@code .war} file path to the sibling exploded directory path.
     * Returns {@code null} when the input doesn't end with {@code .war} or
     * doesn't have a parent directory.
     *
     * <p>Path-shape only — does not touch the filesystem. Use
     * {@link #isExplodedWebapp} to confirm the directory actually exists.
     */
    @Nullable
    static Path deriveExplodedPath(@Nullable String warPath) {
        if (warPath == null || warPath.isEmpty()) return null;
        Path p;
        try {
            p = Path.of(warPath);
        } catch (Exception e) {
            return null;
        }
        Path filename = p.getFileName();
        if (filename == null) return null;
        String name = filename.toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(WAR_EXTENSION)) return null;
        String base = name.substring(0, name.length() - WAR_EXTENSION.length());
        if (base.isEmpty()) return null;
        Path parent = p.getParent();
        return parent == null ? Path.of(base) : parent.resolve(base);
    }

    /**
     * True when {@code dir} is an existing directory containing a
     * {@code WEB-INF/} subdirectory — the canonical exploded-webapp signature.
     * Used to distinguish a real Maven/Gradle exploded WAR staging directory
     * from any directory that happens to share the base name.
     */
    static boolean isExplodedWebapp(@NotNull Path dir) {
        try {
            if (!Files.isDirectory(dir)) return false;
            File webInf = new File(dir.toFile(), WEB_INF_MARKER);
            return webInf.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds a {@link ModuleOwnershipResolver} that walks the project's
     * modules and picks the one whose content root is the deepest prefix of
     * the candidate path. Same algorithm as
     * {@code DeploymentResolver.resolveOwningModule}'s third tier.
     *
     * <p>The caller is responsible for invoking this inside a read action —
     * {@link ModuleManager#getModules()} and
     * {@link ModuleRootManager#getContentRoots()} both require it.
     */
    private static ModuleOwnershipResolver buildResolverFor(@NotNull Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        return path -> findOwningModuleName(modules, path);
    }

    @Nullable
    private static String findOwningModuleName(@NotNull Module[] modules, @NotNull Path path) {
        Path normalised;
        try {
            normalised = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
        Module bestMatch = null;
        int bestMatchLen = -1;
        for (Module candidate : modules) {
            for (VirtualFile contentRoot : ModuleRootManager.getInstance(candidate).getContentRoots()) {
                Path rootPath;
                try {
                    rootPath = Path.of(contentRoot.getPath()).toAbsolutePath().normalize();
                } catch (Exception e) {
                    continue;
                }
                if (normalised.startsWith(rootPath)) {
                    int len = rootPath.toString().length();
                    if (len > bestMatchLen) {
                        bestMatch = candidate;
                        bestMatchLen = len;
                    }
                }
            }
        }
        return bestMatch == null ? null : bestMatch.getName();
    }
}
