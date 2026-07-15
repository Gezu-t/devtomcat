package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModulePointerManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Bridges between the {@link DeploymentArtifact} persistence shape and the
 * typed {@link Deployment} hierarchy. Two callers own each direction:
 *
 * <ul>
 *   <li>{@code toTyped}: invoked when reading a {@code DeploymentConfig}
 *       through {@link DeploymentConfig#getDeployments(Project)} — the
 *       runtime hot path consumes only the typed view.</li>
 *   <li>{@code toLegacy}: invoked when writing back to the storage list
 *       (UI dialogs, programmatic edits) and when {@link DeploymentConfig}
 *       exposes its persistence list via {@link DeploymentConfig#getArtifacts()}.</li>
 * </ul>
 */
public final class DeploymentAdapter {

    private DeploymentAdapter() {}

    /** Map a legacy artifact to the appropriate typed subclass. */
    public static @NotNull Deployment toTyped(@NotNull Project project,
                                              @NotNull DeploymentArtifact legacy) {
        boolean exploded = DeploymentArtifact.TYPE_EXPLODED.equals(legacy.getType());
        String context = legacy.getContextPath();

        return switch (legacy.getSource()) {
            case INTELLIJ_ARTIFACT ->
                    ArtifactBackedDeployment.ofName(project, legacy.getName(), context,
                            legacy.getPath(), exploded);

            case AUTO_DETECTED -> {
                Path outputPath = Path.of(legacy.getPath());
                yield buildAutoDetectedDeployment(
                        project, legacy.getName(), outputPath, context, exploded);
            }

            case EXTERNAL ->
                    new ExternalFileDeployment(
                            Path.of(legacy.getPath()), context, exploded);
        };
    }

    /**
     * Resolves the owning {@link Module} for an AUTO_DETECTED deployment, then
     * creates the {@link ModuleBackedDeployment} from a pointer bound to that
     * module. Falls back through three strategies because the stored "name"
     * field on {@link DeploymentArtifact} carries the artifact's display name
     * (e.g. {@code webapp-deploy.war}), not the IntelliJ module name (e.g.
     * {@code webapp-deploy}) — a long-standing data-model overload from when
     * {@code ProjectArtifactDetector} created these entries.
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
    private static ModuleBackedDeployment buildAutoDetectedDeployment(@NotNull Project project,
                                                                      @NotNull String storedName,
                                                                      @NotNull Path outputPath,
                                                                      @NotNull String contextPath,
                                                                      boolean exploded) {
        Module module = TomcatReadActions.compute(() -> resolveOwningModule(project, storedName, outputPath));
        ModulePointerManager pm = ModulePointerManager.getInstance(project);
        // Carry storedName as the legacy name so the round trip echoes back the
        // persisted display name (which may differ from the resolved module name
        // when strategy 2/3 mapped an artifact filename to a differently-named module).
        if (module != null) {
            return new ModuleBackedDeployment(pm.create(module), outputPath, contextPath, exploded, storedName);
        }
        // Worst case — keep the deployment object alive so the run-config table
        // still shows it; isValid() will return false and the user can re-add it.
        return new ModuleBackedDeployment(pm.create(storedName), outputPath, contextPath, exploded, storedName);
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

    /** Map a typed deployment back to legacy form for serialization. */
    public static @NotNull DeploymentArtifact toLegacy(@NotNull Deployment typed) {
        DeploymentArtifact out = new DeploymentArtifact();
        out.setContextPath(typed.getContextPath());
        out.setType(typed.isExploded()
                ? DeploymentArtifact.TYPE_EXPLODED
                : DeploymentArtifact.TYPE_WAR);

        if (typed instanceof ArtifactBackedDeployment a) {
            out.setName(a.getArtifactName());
            Path resolved = a.getResolvedPath();
            out.setPath(resolved == null ? "" : resolved.toString());
            out.setSource(DeploymentArtifact.Source.INTELLIJ_ARTIFACT);
        } else if (typed instanceof ModuleBackedDeployment m) {
            out.setName(m.getLegacyName());
            out.setPath(m.getOutputPath().toString());
            out.setSource(DeploymentArtifact.Source.AUTO_DETECTED);
        } else if (typed instanceof ExternalFileDeployment e) {
            out.setName(e.getDisplayName());
            out.setPath(e.getExternalPath().toString());
            out.setSource(DeploymentArtifact.Source.EXTERNAL);
        } else {
            throw new IllegalStateException(
                    "Unhandled Deployment subtype: " + typed.getClass());
        }
        return out;
    }
}
