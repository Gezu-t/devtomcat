package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the exploded webapp for a module-backed deployment whose build output
 * does not exist yet — the case where <em>nothing else</em> produces it: IntelliJ
 * Community (no web artifacts), or a fresh checkout that was never packaged.
 *
 * <p>Normally the exploded {@code target/<finalName>/} directory is produced by an
 * IntelliJ artifact build (Ultimate) or by {@code mvn package}. When it is absent,
 * the "Verify" before-launch task cancels the launch and the user is dead-ended on
 * an "Artifacts not ready" warning with no in-IDE way forward. DevTomcat already has
 * every ingredient to build that webapp itself, so it does:
 *
 * <ol>
 *   <li>create the {@code WEB-INF/classes/} skeleton (which also creates the docBase
 *       root and {@code WEB-INF/}),</li>
 *   <li>mirror compiled module output into {@code WEB-INF/classes/}
 *       ({@link DeployedClassesSync}) — the Make step ran first, so classes are fresh,</li>
 *   <li>copy the module's web resources into the docBase ({@link WebResourcesSync}).</li>
 * </ol>
 *
 * Dependency JARs are not copied — the context descriptor mounts them via
 * {@code <PostResources>}. The result is a complete, servable exploded webapp for a
 * plain servlet/JSP module with no build tool involved. Build-tool-only outputs
 * (filtered {@code <webResources>}, WAR overlays, frontend bundles, package-time
 * generated descriptors) are out of scope by design — those still require a real build.
 *
 * <p>Only module-backed, exploded deployments are assembled. Artifact-backed
 * deployments (built by the platform on Ultimate) and WAR deployments (which require
 * real packaging) are left untouched, and a deployment path inside the project source
 * tree is never assembled into ({@link DeploymentSafety}).
 */
public final class ExplodedWebappAssembler {

    private static final Logger LOG = Logger.getInstance(ExplodedWebappAssembler.class);

    private ExplodedWebappAssembler() {}

    /**
     * Assembles every deployment in {@code deployments} that needs it (see
     * {@link #isAssemblable}). No-op for deployments whose output already exists,
     * non-module or non-exploded deployments, and source-tree paths.
     *
     * <p>Must be called off the EDT — it does file I/O and runs the mirror pipeline.
     */
    public static void assembleMissing(@NotNull Project project,
                                       @NotNull List<Deployment> deployments,
                                       @NotNull TomcatDeploymentLogger logger) {
        List<Deployment> toAssemble = TomcatReadActions.compute(() -> {
            List<Deployment> out = new ArrayList<>();
            for (Deployment d : deployments) {
                if (isAssemblable(project, d)) out.add(d);
            }
            return out;
        });
        if (toAssemble.isEmpty()) return;

        List<Deployment> created = new ArrayList<>();
        for (Deployment d : toAssemble) {
            Path root = d.getResolvedPath();
            if (root == null) continue;
            try {
                Files.createDirectories(root.resolve(TomcatConstants.WEB_INF_CLASSES_PATH));
                logger.logServerInfo("Assembling exploded webapp for '" + d.getDisplayName()
                        + "' at " + root + " — no build output found, DevTomcat is building it"
                        + " from the module's sources and compiled classes.");
                created.add(d);
            } catch (IOException e) {
                LOG.warn("DevTomcat: could not create exploded webapp dir " + root + ": " + e.getMessage());
                logger.logServerWarning("Could not create exploded webapp directory for '"
                        + d.getDisplayName() + "': " + e.getMessage());
            }
        }
        if (created.isEmpty()) return;

        // Populate the freshly-created skeleton with the standard mirror pipeline.
        // These now proceed (they no longer hit the isValid()/path-missing skip)
        // because the directory exists. Both are mtime-gated, so the identical
        // second pass at launch prep is a cheap no-op.
        DeployedClassesSync.syncDeployments(project, created, logger);
        WebResourcesSync.syncDeployments(project, created, logger);
    }

    /**
     * Whether DevTomcat should assemble {@code deployment}: it is a module-backed,
     * exploded deployment whose owning module resolves, whose output directory does
     * not exist yet, and whose path is a safe build-output location (not inside the
     * project source tree). Must be called under a read action.
     */
    static boolean isAssemblable(@NotNull Project project, @NotNull Deployment deployment) {
        if (!(deployment instanceof ModuleBackedDeployment)) return false;
        if (!deployment.isExploded()) return false;
        if (DeploymentModuleResolver.resolve(deployment, project) == null) return false;
        Path path = deployment.getResolvedPath();
        if (path == null) return false;
        if (Files.exists(path)) return false;                                    // already built — normal flow
        // The path does NOT exist yet, so classify by its nearest existing
        // ancestor — never assemble a webapp into the project source tree.
        return !DeploymentSafety.wouldCreateInsideProjectContent(project, path);
    }
}
