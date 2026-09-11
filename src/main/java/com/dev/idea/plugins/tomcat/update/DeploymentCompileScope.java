package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds a {@link CompileScope} that covers exactly the modules whose output
 * feeds the configured deployments — the deployment modules plus their
 * transitive upstream module-dependency closure — instead of the whole project.
 *
 * <h2>Why</h2>
 * The update loop recompiles before it syncs. On a large multi-module project a
 * whole-project {@code make} re-checks every module on every Update, even when
 * the webapp depends on a small fraction of them. Scoping the compile to the
 * dependency closure of the deployed modules makes the loop cost scale with what
 * the webapp actually uses, not with the module count of the whole repository.
 *
 * <h2>Why the closure is computed here, explicitly</h2>
 * {@link CompilerManager#createModulesCompileScope(Module[], boolean)} can expand
 * a module set itself, but the boolean's exact semantics (dependencies vs.
 * dependents) are an implementation detail of the platform. Rather than depend on
 * that, this class walks the {@link ModuleOrderEntry} graph itself — the same
 * traversal the launch classpath builder uses — to collect every upstream module,
 * then passes {@code false}. The scope is therefore <em>complete</em> (every
 * module needed to produce the artifact's classpath is included) and
 * <em>minimal</em> (no unrelated dependents are dragged in), independent of how
 * the platform interprets the flag.
 *
 * <h2>Safety</h2>
 * When no deployment resolves to a project module (e.g. only external WAR files),
 * {@link #resolve} returns {@code null} so the caller falls back to a
 * whole-project build. Scoping never compiles <em>less</em> than a correct build
 * would: the fallback is always a full build, never a no-op.
 */
public final class DeploymentCompileScope {

    private static final Logger LOG = Logger.getInstance(DeploymentCompileScope.class);

    private DeploymentCompileScope() {}

    /**
     * Resolves the scoped compile set for {@code deployments}, or {@code null}
     * when scoping is not possible (no module-backed deployment) or fails. A
     * {@code null} result is the caller's signal to compile the whole project.
     *
     * <p>All project-model access runs inside a single read action.
     */
    @Nullable
    public static CompileScope resolve(@NotNull Project project,
                                       @NotNull List<Deployment> deployments,
                                       @NotNull TomcatDeploymentLogger logger) {
        if (project.isDisposed() || deployments.isEmpty()) return null;
        try {
            return TomcatReadActions.compute(() -> computeScope(project, deployments, logger));
        } catch (Throwable t) {
            TomcatProgress.rethrowIfControlFlow(t);
            // Never let scope resolution break Update — fall back to whole-project.
            LOG.warn("Scoped compile: could not resolve deployment module scope; compiling whole project", t);
            return null;
        }
    }

    @Nullable
    private static CompileScope computeScope(@NotNull Project project,
                                             @NotNull List<Deployment> deployments,
                                             @NotNull TomcatDeploymentLogger logger) {
        Set<Module> seeds = new LinkedHashSet<>();
        for (Deployment d : deployments) {
            if (!d.isValid()) continue;
            // resolveAll (not resolve): seed from EVERY module the artifact
            // packages so a packaged sibling that is not a production dependency
            // of the primary module still recompiles on hot-reload. The launch
            // classpath keeps using the single-module resolve.
            seeds.addAll(DeploymentModuleResolver.resolveAll(d, project));
        }
        if (seeds.isEmpty()) {
            logger.logServerInfo("Scoped compile: no module-backed deployment resolved — compiling whole project.");
            return null;
        }

        Set<Module> closure = transitiveClosure(seeds, DeploymentCompileScope::directModuleDependencies);
        Module[] modules = closure.toArray(new Module[0]);
        CompileScope scope = CompilerManager.getInstance(project).createModulesCompileScope(modules, false);

        List<String> names = new ArrayList<>(closure.size());
        for (Module m : closure) names.add(m.getName());
        String scoped = "Scoped compile: building " + closure.size()
                + " module(s) [" + String.join(", ", names) + "] instead of the whole project.";
        LOG.info(scoped);
        logger.logServerInfo(scoped);
        return scope;
    }

    /**
     * Direct (one-hop) module dependencies of {@code module}, via its
     * {@link ModuleOrderEntry} order entries. Must be called under a read action.
     * Mirrors the traversal used by the launch classpath builder so the two stay
     * consistent.
     */
    @NotNull
    private static List<Module> directModuleDependencies(@NotNull Module module) {
        List<Module> deps = new ArrayList<>();
        for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
            if (entry instanceof ModuleOrderEntry moduleEntry) {
                Module dep = moduleEntry.getModule();
                if (dep != null) deps.add(dep);
            }
        }
        return deps;
    }

    /**
     * Transitive closure of {@code seeds} under {@code edges}, returned in
     * deterministic discovery order. Pure and side-effect-free so the graph
     * walk — including its handling of cycles, diamonds, and self-edges, all of
     * which occur in real multi-module dependency graphs — is unit-testable
     * without a live {@code Project}.
     *
     * @param seeds the starting nodes (always included in the result)
     * @param edges maps a node to its direct successors
     */
    @NotNull
    static <T> Set<T> transitiveClosure(@NotNull Collection<? extends T> seeds,
                                        @NotNull Function<? super T, ? extends Collection<? extends T>> edges) {
        Set<T> visited = new LinkedHashSet<>();
        Deque<T> stack = new ArrayDeque<>(seeds);
        while (!stack.isEmpty()) {
            T node = stack.pop();
            if (!visited.add(node)) continue; // already expanded — breaks cycles
            for (T next : edges.apply(node)) {
                if (!visited.contains(next)) stack.push(next);
            }
        }
        return visited;
    }
}
