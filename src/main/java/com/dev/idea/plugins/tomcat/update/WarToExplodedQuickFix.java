package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
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

/**
 * One-click migration that flips a WAR-typed {@link DeploymentArtifact} to
 * point at its sibling exploded directory <em>and</em> reclaims the deployment
 * as module-owned so hot class/resource sync can drive it.
 *
 * <p>Maven's {@code maven-war-plugin} produces both {@code target/<finalName>.war}
 * (the packaged archive) and {@code target/<finalName>/} (the exploded staging
 * directory it builds the archive from) during {@code mvn package}. Gradle's
 * {@code war} task likewise produces both forms. When the user picked the
 * {@code .war} file during auto-detection or via External Source, hot sync
 * skips the deployment for two reasons:
 * <ol>
 *   <li>{@code type=war} means Tomcat's classloader holds the sealed archive
 *       open and mutating it from outside is unsafe.</li>
 *   <li>{@code source=EXTERNAL} means {@link DeploymentArtifact} maps to
 *       {@code ExternalFileDeployment} which by design has no module link —
 *       {@link DeployedClassesSync} has nowhere to copy class output from.</li>
 * </ol>
 *
 * <p>This fix addresses both. For every WAR deployment whose sibling exploded
 * directory exists and lives under a project module's content roots, the fix
 * mutates:
 * <ul>
 *   <li>{@code path}: {@code .../target/foo.war} → {@code .../target/foo}</li>
 *   <li>{@code type}: {@link DeploymentArtifact#TYPE_WAR} →
 *       {@link DeploymentArtifact#TYPE_EXPLODED}</li>
 *   <li>{@code source}: → {@link DeploymentArtifact.Source#AUTO_DETECTED}
 *       (so the loader builds {@code ModuleBackedDeployment} with a real
 *       module pointer instead of {@code ExternalFileDeployment})</li>
 *   <li>{@code name}: → the IntelliJ module name (so the loader's
 *       direct-by-name lookup matches without falling through to the
 *       suffix-stripping tier)</li>
 * </ul>
 *
 * <p>Candidates are only offered when:
 * <ol>
 *   <li>The artifact is currently {@code type=war}.</li>
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
     * One candidate flip: the artifact that will be mutated, the directory its
     * path will move to, and the module name to claim as the new owner. The
     * module name is captured at scan time so apply doesn't need a fresh
     * read action.
     */
    public record FixCandidate(@NotNull DeploymentArtifact artifact,
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
                                                          @NotNull List<DeploymentArtifact> artifacts) {
        if (project.isDisposed()) return List.of();
        return TomcatReadActions.compute(() -> {
            ModuleOwnershipResolver resolver = buildResolverFor(project);
            return findFixableArtifacts(resolver, artifacts);
        });
    }

    /**
     * Scans the legacy artifact list for entries that can be reclaimed as
     * module-owned exploded deployments. Two shapes qualify:
     *
     * <ol>
     *   <li><b>WAR artifact with a sibling exploded directory</b> — the
     *       primary case. Stored {@code type=war}; path ends with {@code .war}
     *       and the sibling {@code target/<name>/} directory exists with
     *       {@code WEB-INF/}. The fix changes path, type, source, and name.</li>
     *
     *   <li><b>Already-exploded artifact whose source is still EXTERNAL</b> —
     *       the recovery case. {@code type=exploded}, path already points at
     *       a directory, but {@code source=EXTERNAL} means the loader builds
     *       {@code ExternalFileDeployment} (no module link), and class sync
     *       reports "could not resolve owning module — null". This happens
     *       when an earlier flip changed path/type but left source/name —
     *       see commit {@code b4b1fd6}'s fix description for the regression
     *       this catches. The fix changes source and name (path/type stay,
     *       so applying twice is idempotent).</li>
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
                                                   @NotNull List<DeploymentArtifact> artifacts) {
        List<FixCandidate> out = new ArrayList<>();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            // Debug logging: each artifact rejection emits a structured line so a
            // user reporting "balloon didn't fire" can hand back a log fragment
            // that pinpoints which check failed without us having to instrument live.
            String label = "'" + artifact.getName() + "' (type=" + artifact.getType()
                    + ", source=" + artifact.getSource() + ", path=" + artifact.getPath() + ")";
            Path explodedPath = findCandidateExplodedPath(artifact);
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
            out.add(new FixCandidate(artifact, explodedPath, moduleName));
        }
        return out;
    }

    /**
     * For a fixable artifact, returns the exploded directory the fix will
     * point it at. Returns {@code null} when the artifact doesn't match
     * either of the two fixable shapes (see
     * {@link #findFixableArtifacts(ModuleOwnershipResolver, List)}).
     */
    @Nullable
    private static Path findCandidateExplodedPath(@NotNull DeploymentArtifact artifact) {
        if (DeploymentArtifact.TYPE_WAR.equals(artifact.getType())) {
            // Primary case: derive sibling directory from the .war filename.
            return deriveExplodedPath(artifact.getPath());
        }
        if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                && artifact.getSource() == DeploymentArtifact.Source.EXTERNAL) {
            // Recovery case: path already points at the exploded directory
            // but source=EXTERNAL means the loader builds ExternalFileDeployment.
            try {
                return Path.of(artifact.getPath());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Applies the flip to every candidate. Mutates the {@link DeploymentArtifact}
     * instances in place; IntelliJ's run-config persistence picks up the
     * change on its next save tick.
     *
     * <p>Returns the number of artifacts actually mutated (some candidates may
     * have become stale between {@link #findFixableArtifacts} and this call,
     * e.g. if {@code mvn clean} ran in between).
     */
    public static int applyAll(@NotNull TomcatRunConfiguration config,
                               @NotNull List<FixCandidate> candidates) {
        int applied = 0;
        for (FixCandidate candidate : candidates) {
            if (!isExplodedWebapp(candidate.explodedDirectory())) {
                LOG.info("Skipping stale candidate (directory disappeared): "
                        + candidate.explodedDirectory());
                continue;
            }
            DeploymentArtifact artifact = candidate.artifact();
            artifact.setPath(candidate.explodedDirectory().toString());
            artifact.setType(DeploymentArtifact.TYPE_EXPLODED);
            // Source: claim the deployment as module-owned. Without this the
            // loader builds ExternalFileDeployment, which has no module link
            // and class sync silently skips it with diagnostic=null.
            artifact.setSource(DeploymentArtifact.Source.AUTO_DETECTED);
            // Name: use the resolved module name so the loader's tier-1
            // findModuleByName(...) lookup matches directly. Without this the
            // name might still carry the .war filename and would only resolve
            // via the suffix-stripping tier — works, but less direct and
            // surfaces as a confusing "module-direct" strategy label for what's
            // actually a derived match.
            artifact.setName(candidate.moduleName());
            applied++;
        }

        if (applied > 0) {
            // The artifact list is mutated in place. Run-config state is
            // persisted by IntelliJ on its next save tick (run-config editor
            // open/close, IDE shutdown, etc.) — no explicit RunManager nudge
            // needed. In-memory state is already correct for the next launch.
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
        if (!name.toLowerCase().endsWith(WAR_EXTENSION)) return null;
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
     * {@code DeploymentAdapter.resolveOwningModule}'s third tier.
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
