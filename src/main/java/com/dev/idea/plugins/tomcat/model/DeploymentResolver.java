package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
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
            if (rebindTo == null) return m;
            return new ModuleBackedDeployment(
                    ModuleRef.of(project, rebindTo),
                    m.getOutputPath(), m.getContextPath(), m.isExploded(), m.getLegacyName());
        }
        return deployment;
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
