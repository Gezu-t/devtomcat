package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Resolves the owning IntelliJ {@link Module} behind a {@link Deployment} via the
 * typed deployment hierarchy — no string matching anywhere.
 *
 * <ul>
 *   <li>{@link ArtifactBackedDeployment} walks the artifact's packaging tree for
 *       its first {@code ModulePackagingElement}.</li>
 *   <li>{@link ModuleBackedDeployment} returns its pointer's module directly.</li>
 *   <li>External file deployments have no project module to resolve.</li>
 * </ul>
 *
 * <p>This is the single source of the deployment→module mapping, shared by the
 * launch classpath builder ({@code LocalDeploymentStrategy}) and the
 * scoped-compile module set ({@link DeploymentCompileScope}), so a deployment is
 * compiled with, and launched against, exactly the same module.
 *
 * <p><strong>Must be called under a read action.</strong>
 */
public final class DeploymentModuleResolver {

    private static final Logger LOG = Logger.getInstance(DeploymentModuleResolver.class);

    private DeploymentModuleResolver() {}

    /**
     * Returns the project module backing {@code deployment}, or {@code null} when
     * none can be resolved (external file deployment, missing artifact, or the
     * packaging plugin is unavailable). Never throws, except
     * {@link com.intellij.openapi.progress.ProcessCanceledException} which is
     * rethrown unchanged (the resolver runs on the cancelable launch path).
     */
    @Nullable
    public static Module resolve(@NotNull Deployment deployment, @NotNull Project project) {
        try {
            if (deployment instanceof ArtifactBackedDeployment a) {
                Artifact artifact = a.getArtifactPointer().getArtifact();
                if (artifact == null) return null;
                ArtifactManager mgr;
                try {
                    mgr = ArtifactManager.getInstance(project);
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    throw pce;
                } catch (NoClassDefFoundError | Exception ignored) {
                    return null;
                }
                if (mgr == null) return null;
                return DeployedClassesSync.walkPackagingTreeForModule(
                        artifact.getRootElement(), mgr.getResolvingContext());
            }
            if (deployment instanceof ModuleBackedDeployment m) {
                return m.getModule();
            }
            return null; // ExternalFileDeployment — no project module
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            // Cancellation must propagate before the generic handler — the
            // packaging-tree walk / artifact resolution can hit
            // ProgressManager.checkCanceled() under the launch-prep indicator.
            throw pce;
        } catch (Exception e) {
            LOG.warn("Failed to resolve module for '" + deployment.getDisplayName()
                    + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns EVERY project module a deployment packages — all
     * {@code ModulePackagingElement}s in an artifact's packaging tree, not just
     * the first {@link #resolve} returns. Two callers: {@link
     * DeploymentCompileScope} WIDENS the scoped hot-reload compile with it (a
     * packaged module that is not a production dependency of the primary module
     * still recompiles on "Update classes and resources"), and the
     * run-configuration producer uses it to recognize an existing configuration
     * that already deploys a context module (existing-config matching). The
     * launch classpath deliberately keeps using the single-module
     * {@link #resolve} (unchanged). Empty for external deployments or when the
     * artifact / packaging plugin is unavailable; never throws except
     * {@link com.intellij.openapi.progress.ProcessCanceledException}.
     *
     * <p><strong>Must be called under a read action.</strong>
     */
    @NotNull
    public static Set<Module> resolveAll(@NotNull Deployment deployment, @NotNull Project project) {
        try {
            if (deployment instanceof ArtifactBackedDeployment a) {
                Artifact artifact = a.getArtifactPointer().getArtifact();
                if (artifact == null) return Set.of();
                ArtifactManager mgr;
                try {
                    mgr = ArtifactManager.getInstance(project);
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    throw pce;
                } catch (NoClassDefFoundError | Exception ignored) {
                    return Set.of();
                }
                if (mgr == null) return Set.of();
                return DeployedClassesSync.collectPackagedModules(
                        artifact.getRootElement(), mgr.getResolvingContext());
            }
            if (deployment instanceof ModuleBackedDeployment m) {
                Module mod = m.getModule();
                return mod != null ? Set.of(mod) : Set.of();
            }
            return Set.of(); // ExternalFileDeployment — no project module
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.warn("Failed to resolve packaged modules for '" + deployment.getDisplayName()
                    + "': " + e.getMessage());
            return Set.of();
        }
    }
}
