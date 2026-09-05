package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.dev.idea.plugins.tomcat.setting.ProjectTomcatProfileScanner;
import com.intellij.openapi.diagnostic.Logger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Access-time resolution for stored {@link Deployment}s. Storage keeps
 * name-only pointers exactly as persisted; {@link #resolve} re-evaluates the
 * project model on every call and never mutates storage — the Community-first
 * artifact→module fold stays dynamic, so installing Ultimate (or re-adding a
 * deleted artifact) restores artifact-backed behaviour without a config edit.
 */
public final class DeploymentResolver {

    private static final Logger LOG = Logger.getInstance(DeploymentResolver.class);

    private DeploymentResolver() {}

    /**
     * Resolved view of one stored deployment:
     * <ul>
     *   <li>{@link ArtifactBackedDeployment} with no live artifact whose
     *       last-known path is owned by a project module folds to a
     *       {@link ModuleBackedDeployment} — DevTomcat assembles and syncs it
     *       (the Community-edition path). A live artifact keeps it
     *       artifact-backed; the platform builds that one.</li>
     *   <li>{@link ModuleBackedDeployment} whose pointer no longer resolves is
     *       rebound to the owning module recovered from its stored name and
     *       output path; if nothing resolves the input is returned unchanged.</li>
     *   <li>Everything else passes through unchanged.</li>
     * </ul>
     */
    public static @NotNull Deployment resolve(@NotNull Project project, @NotNull Deployment deployment) {
        if (deployment instanceof ArtifactBackedDeployment a) {
            String path = a.getLastKnownPath();
            if (path == null || path.isEmpty()) return a;
            Module owner = artifactBackedFallbackModule(project, a, a.getArtifactName(), Path.of(path));
            return owner != null
                    ? buildAutoDetectedDeployment(
                            project, a.getArtifactName(), Path.of(path), a.getContextPath(), a.isExploded())
                    : a;
        }
        if (deployment instanceof ModuleBackedDeployment m) {
            // Null covers both "pointer is live" and "nothing resolves" — the
            // deployment stays as stored either way; only a recovered owner rebinds.
            Module rebindTo = TomcatReadActions.compute(() -> {
                if (m.getModule() != null) return null;
                return resolveOwningModule(project, m.getLegacyName(), m.getOutputPath());
            });
            ModuleBackedDeployment bound = rebindTo == null ? m : new ModuleBackedDeployment(
                    ModuleRef.of(project, rebindTo),
                    m.getOutputPath(), m.getContextPath(), m.isExploded(), m.getLegacyName());
            return healSourceTreePath(project, bound, DeploymentResolver::mavenBuildOutput,
                    DeploymentResolver::isInsideModuleContent);
        }
        return deployment;
    }

    /**
     * Re-points an auto-detected deployment whose path is inside the project's
     * content — a web root such as {@code src/main/webapp}, which older versions'
     * detection fell back to — at the module's build output, when the build
     * model can determine one. Left alone otherwise. Runtime-only, like every
     * resolution here: the stored entry is untouched until the user saves the
     * configuration. Without this, the sync refuses (correctly) to write into
     * the sources and Tomcat serves the source tree with no {@code WEB-INF/classes}.
     *
     * @param buildOutput   the module's exploded build output, or {@code null} when
     *                      none is determinable
     * @param insideContent whether a path lies inside the module's non-excluded
     *                      content — {@link #isInsideModuleContent} in production;
     *                      both are injected so the rule is testable in isolation
     */
    @NotNull
    static ModuleBackedDeployment healSourceTreePath(@NotNull Project project,
                                                     @NotNull ModuleBackedDeployment m,
                                                     @NotNull Function<Module, Path> buildOutput,
                                                     @NotNull BiPredicate<Module, Path> insideContent) {
        return TomcatReadActions.compute(() -> {
            Module module = m.getModule();
            if (module == null) return m;
            if (!insideContent.test(module, m.getOutputPath())) return m;
            Path output = buildOutput.apply(module);
            if (output == null || output.equals(m.getOutputPath())) return m;
            // Never trade one source-tree path for another.
            if (insideContent.test(module, output)) return m;
            if (HEALED.add(m.getOutputPath() + " -> " + output)) {
                LOG.info("Deployment '" + m.getDisplayName() + "': stored path is inside the source tree ("
                        + m.getOutputPath() + "); using the build output instead (" + output + ")");
            }
            return new ModuleBackedDeployment(
                    ModuleRef.of(project, module), output, m.getContextPath(), m.isExploded(), m.getLegacyName());
        });
    }

    /** Once-per-session log guard: the resolved view is recomputed on every call. */
    private static final Set<String> HEALED = ConcurrentHashMap.newKeySet();

    /** The Maven-derived exploded output — the same derivation auto-detection uses, minus its web-root fallback. */
    @Nullable
    private static Path mavenBuildOutput(@NotNull Module module) {
        ProjectTomcatProfileScanner.DetectedWebappModule detected = ProjectTomcatProfileScanner.scanModule(module);
        return detected == null ? null : Path.of(detected.explodedPath());
    }

    /**
     * Whether {@code path} lies under one of the module's content roots and not
     * under any of its excluded roots — the importer excludes build outputs such
     * as {@code target/} and {@code build/}, so those read as "not content".
     *
     * <p>Pure root-model + path logic, deliberately <em>not</em> a
     * {@code ProjectFileIndex} query: the resolved view is computed wherever the
     * deployment list is read, including on the EDT (the process handler's
     * {@code startNotified}), where a workspace-index lookup is a prohibited slow
     * operation. Same containment shape as {@link #resolveOwningModule}'s third
     * strategy. Read action required.
     */
    static boolean isInsideModuleContent(@NotNull Module module, @NotNull Path path) {
        Path p = path.toAbsolutePath().normalize();
        ModuleRootManager roots = ModuleRootManager.getInstance(module);
        boolean inContent = false;
        for (VirtualFile root : roots.getContentRoots()) {
            if (p.startsWith(Path.of(root.getPath()).toAbsolutePath().normalize())) {
                inContent = true;
                break;
            }
        }
        if (!inContent) return false;
        for (VirtualFile excluded : roots.getExcludeRoots()) {
            if (p.startsWith(Path.of(excluded.getPath()).toAbsolutePath().normalize())) return false;
        }
        return true;
    }

    /**
     * The owning module for an artifact-backed deployment ONLY when it has no
     * live artifact behind it — so DevTomcat should treat it as module-backed
     * and build it. Returns {@code null} when a live artifact backs it (keep it
     * artifact-backed; the platform builds that one) or when no module owns the
     * path. Runs under a read action (artifact + module lookups).
     */
    @Nullable
    static Module artifactBackedFallbackModule(@NotNull Project project,
                                               @NotNull ArtifactBackedDeployment artifactBacked,
                                               @NotNull String storedName,
                                               @NotNull Path storedPath) {
        return TomcatReadActions.compute(() -> {
            if (artifactBacked.getArtifactPointer().getArtifact() != null) {
                return null; // live IntelliJ artifact — the platform build task produces it
            }
            return resolveOwningModule(project, storedName, storedPath);
        });
    }

    /**
     * Resolves the owning {@link Module} for an AUTO_DETECTED deployment, then
     * creates the {@link ModuleBackedDeployment} from a pointer bound to that
     * module. Falls back through three strategies because the stored deployment
     * name carries the artifact's display name (e.g. {@code webapp-deploy.war}),
     * not the IntelliJ module name (e.g. {@code webapp-deploy}) — a long-standing
     * data-model overload from when {@code ProjectArtifactDetector} created these
     * entries.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code ModuleManager.findModuleByName(storedName)} — works when the
     *       stored name accidentally matches a module name (rare).</li>
     *   <li>Same lookup with the {@code .war} / {@code .ear} / {@code .jar}
     *       suffix stripped — handles the common Maven/Gradle case where the
     *       artifact filename is {@code <moduleName>.war}.</li>
     *   <li>Content-root containment: walks every module and picks the one
     *       whose content root is the deepest prefix of {@code outputPath}.
     *       Handles Maven {@code <finalName>} with version suffixes and any
     *       other rename that breaks the name-derived path.</li>
     * </ol>
     *
     * <p>If all three fail (project not loaded, or the output path is outside
     * every module's content roots), creates a name-only pointer as the last
     * resort. That pointer's {@code getModule()} will return {@code null} and
     * the deployment will fail {@code isValid()} — but the deployment object
     * still exists, so callers can surface a clearer error.
     */
    @NotNull
    static ModuleBackedDeployment buildAutoDetectedDeployment(@NotNull Project project,
                                                              @NotNull String storedName,
                                                              @NotNull Path outputPath,
                                                              @NotNull String contextPath,
                                                              boolean exploded) {
        Module module = TomcatReadActions.compute(() -> resolveOwningModule(project, storedName, outputPath));
        // Carry storedName as the legacy name so the round trip echoes back the
        // persisted display name (which may differ from the resolved module name
        // when strategy 2/3 mapped an artifact filename to a differently-named module).
        if (module != null) {
            return new ModuleBackedDeployment(ModuleRef.of(project, module), outputPath, contextPath, exploded, storedName);
        }
        // Worst case — keep the deployment object alive so the run-config table
        // still shows it; isValid() will return false and the user can re-add it.
        return new ModuleBackedDeployment(ModuleRef.of(project, storedName), outputPath, contextPath, exploded, storedName);
    }

    /**
     * Public because path→module ownership is also the run-configuration
     * producer's fallback for existing-config matching: a deployment the typed
     * model can't resolve (dangling artifact pointer persisted before
     * AUTO_DETECTED provenance existed, or an in-project external file) still
     * identifies its webapp by path. <strong>Must be called under a read
     * action.</strong>
     */
    @Nullable
    public static Module resolveOwningModule(@NotNull Project project,
                                             @NotNull String storedName,
                                             @NotNull Path outputPath) {
        ModuleManager mm = ModuleManager.getInstance(project);

        Module direct = mm.findModuleByName(storedName);
        if (direct != null) return direct;

        String stripped = stripArtifactSuffix(storedName);
        if (!stripped.equals(storedName)) {
            Module withoutExtension = mm.findModuleByName(stripped);
            if (withoutExtension != null) return withoutExtension;
        }

        // Content-root containment — pick the deepest matching root so a nested
        // module wins over a parent project that also covers the path.
        // Relative paths get resolved against the project base rather than CWD:
        // CWD is whatever launched the IDE, not the user's project root.
        Path normalised;
        try {
            Path candidate = outputPath;
            if (!candidate.isAbsolute()) {
                String basePath = project.getBasePath();
                if (basePath != null) {
                    candidate = Path.of(basePath).resolve(candidate);
                }
            }
            normalised = candidate.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
        Module bestMatch = null;
        int bestMatchLen = -1;
        for (Module candidate : mm.getModules()) {
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
        return bestMatch;
    }

    @NotNull
    private static String stripArtifactSuffix(@NotNull String name) {
        if (name.endsWith(".war")) return name.substring(0, name.length() - 4);
        if (name.endsWith(".ear")) return name.substring(0, name.length() - 4);
        if (name.endsWith(".jar")) return name.substring(0, name.length() - 4);
        return name;
    }
}
