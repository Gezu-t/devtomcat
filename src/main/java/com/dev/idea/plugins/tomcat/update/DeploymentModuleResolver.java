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
        return dispatch(deployment, project, null, m -> m,
                DeployedClassesSync::walkPackagingTreeForModule, "module");
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
        Set<Module> result = dispatch(deployment, project, Set.of(), Set::of,
                DeployedClassesSync::collectPackagedModules, "packaged modules");
        return result != null ? result : Set.of();
    }

    /** The artifact-arm walk over a packaging tree, typed so both resolvers share one dispatcher. */
    @FunctionalInterface
    private interface PackagingTreeWalk<T> {
        T walk(@NotNull com.intellij.packaging.elements.PackagingElement<?> root,
               @NotNull com.intellij.packaging.elements.PackagingElementResolvingContext ctx);
    }

    /**
     * Single dispatch shared by {@link #resolve} and {@link #resolveAll}: the
     * typed-deployment arms, the artifact/ArtifactManager availability checks,
     * and the exception policy live here exactly once. {@code empty} is the
     * per-caller "nothing resolved" value ({@code null} / {@code Set.of()});
     * {@code fromModule} maps a non-null module-backed module to the result;
     * {@code artifactWalk} is the packaging-tree traversal for artifact-backed
     * deployments.
     */
    @Nullable
    private static <T> T dispatch(@NotNull Deployment deployment,
                                  @NotNull Project project,
                                  @Nullable T empty,
                                  @NotNull java.util.function.Function<Module, T> fromModule,
                                  @NotNull PackagingTreeWalk<T> artifactWalk,
                                  @NotNull String failureNoun) {
        try {
            if (deployment instanceof ArtifactBackedDeployment a) {
                Artifact artifact = a.getArtifactPointer().getArtifact();
                if (artifact == null) return empty;
                ArtifactManager mgr;
                try {
                    mgr = ArtifactManager.getInstance(project);
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    throw pce;
                } catch (NoClassDefFoundError | Exception ignored) {
                    // Packaging plugin unavailable on this IDE/edition.
                    return empty;
                }
                if (mgr == null) return empty;
                return artifactWalk.walk(artifact.getRootElement(), mgr.getResolvingContext());
            }
            if (deployment instanceof ModuleBackedDeployment m) {
                Module mod = m.getModule();
                return mod != null ? fromModule.apply(mod) : empty;
            }
            return empty; // ExternalFileDeployment — no project module
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            // Cancellation must propagate before the generic handler — the
            // packaging-tree walk / artifact resolution can hit
            // ProgressManager.checkCanceled() under the launch-prep indicator.
            throw pce;
        } catch (Exception e) {
            LOG.warn("Failed to resolve " + failureNoun + " for '" + deployment.getDisplayName()
                    + "': " + e.getMessage());
            return empty;
        }
    }
}
