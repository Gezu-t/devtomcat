package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentAdapter;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.MavenModelProvider;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.elements.CompositePackagingElement;
import com.intellij.packaging.elements.PackagingElement;
import com.intellij.packaging.elements.PackagingElementResolvingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_CLASS;
import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_JAR;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_CLASSES_PATH;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_LIB_PATH;

/**
 * Mirrors freshly-compiled module output into each exploded deployment's
 * {@code WEB-INF/classes/} directory before <strong>every</strong> Tomcat
 * launch — initial Run, Stop-then-Run, cross-executor switch, and every
 * leg of Restart Server / Update Classes / Redeploy.
 *
 * <h2>Why this exists</h2>
 * The platform's "Make" task — fired automatically as a Before Launch
 * step on every Tomcat configuration — compiles {@code .java} into
 * {@code out/production/<module>/} (or {@code target/classes/} under
 * Maven delegate build, or {@code build/classes/java/main/} under Gradle
 * delegate build). It never touches an exploded deployment directory.
 *
 * <p>That gap shows up most painfully on Maven web modules that deploy
 * {@code target/<warname>/} (the directory the {@code maven-war-plugin}
 * explodes during the {@code prepare-package} phase). The user edits a
 * Java file, clicks Restart, Tomcat boots happily — and serves the class
 * files from the previous {@code mvn package} run, because nothing copied
 * the freshly-compiled bytes from {@code target/classes/} to
 * {@code target/<warname>/WEB-INF/classes/}. The visible symptom: "I have
 * to run {@code mvn clean install} every time".
 *
 * <p>DevTomcat closes this gap automatically as part of every launch and
 * Update, so the user never has to remember it.
 *
 * <h2>What this sync covers — and what it doesn't</h2>
 * <ul>
 *   <li>Exploded deployments (TYPE_EXPLODED): mirrored. Both Maven
 *       {@code target/<warname>/} layouts and IntelliJ's own
 *       {@code out/artifacts/<name>_war_exploded/} layouts work — the rule
 *       is the same (presence of a {@code WEB-INF/classes/} child).</li>
 *   <li>WAR deployments (TYPE_WAR): NOT covered. Repackaging the WAR
 *       requires the build tool (Maven {@code package}, Gradle
 *       {@code war}); we can't reach inside the WAR safely. Callers should
 *       surface a balloon for stale WARs separately
 *       (see {@code TomcatBuildArtifactsTaskProvider}).</li>
 *   <li>Multi-module dependency edits: <em>now covered</em>. The web
 *       module's compiled output is mirrored AND every dependency module
 *       it transitively pulls in. Tomcat's classloader searches
 *       {@code WEB-INF/classes/} before {@code WEB-INF/lib/*.jar}, so a
 *       freshly-compiled dependency class wins over its stale-JAR copy
 *       from the last {@code mvn package} run. A dependency's resource policy
 *       is decided per root against the deployed {@code WEB-INF/lib/}: when the
 *       dependency is packaged there as a JAR, the root contributes its
 *       {@code .class} files only (its resources reach Tomcat through the JAR
 *       and must not be duplicated into {@code WEB-INF/classes/}); when the
 *       dependency is NOT packaged there, the root is mirrored full-content so
 *       its resources still reach Tomcat. The web module's own root is always
 *       mirrored full-content. See {@link #collectProductionRoots} and
 *       {@link #shouldMirrorClassesOnly} for the rationale.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Source-driven mirror: only files present in the module output are
 *       considered. Destination-only files (e.g. {@code WEB-INF/lib/*.jar},
 *       webapp resources outside {@code WEB-INF/classes/}) are left
 *       untouched.</li>
 *   <li>Mtime gate: a file is copied only when the source is strictly newer
 *       than the destination (or the destination is absent). This keeps
 *       repeated restarts cheap and avoids touching files that match.</li>
 *   <li>Never deletes: even if the user removed a class from sources, the
 *       stale {@code .class} stays in the deployed dir until a clean
 *       build runs. Wiping is the build tool's job.</li>
 *   <li>Read action: module / classpath traversal runs inside
 *       {@link TomcatReadActions#compute} — required by IntelliJ's
 *       PSI/index APIs.</li>
 * </ul>
 */
public final class DeployedClassesSync {

    private static final Logger LOG = Logger.getInstance(DeployedClassesSync.class);

    /**
     * {@code com.intellij.packaging.impl.elements.ModulePackagingElement}, loaded
     * once at class init. {@code null} if the class moves or disappears in a
     * future IntelliJ release — in which case {@link #walkPackagingTreeForModule}
     * quietly returns {@code null} and the caller falls through to its
     * downstream resolution branch.
     *
     * <p>Reflection on purpose: {@code ModulePackagingElement} lives in
     * {@code com.intellij.packaging.impl.elements} — an {@code impl} package the
     * plugin verifier flags — so it is reached reflectively rather than by a
     * direct compile-time reference.
     */
    @Nullable
    private static final Class<?> MODULE_PACKAGING_ELEMENT_CLASS =
            loadClass("com.intellij.packaging.impl.elements.ModulePackagingElement");

    /** {@code findModule(PackagingElementResolvingContext)} on the cached interface above. Cached at class init for the same reason. */
    @Nullable
    private static final Method MODULE_FIND_MODULE_METHOD =
            findMethod(MODULE_PACKAGING_ELEMENT_CLASS, "findModule",
                       PackagingElementResolvingContext.class);

    @Nullable
    private static Class<?> loadClass(@NotNull String fqn) {
        try { return Class.forName(fqn); }
        catch (ClassNotFoundException e) { return null; }
    }

    @Nullable
    private static Method findMethod(@Nullable Class<?> cls,
                                     @NotNull String name,
                                     @NotNull Class<?>... params) {
        if (cls == null) return null;
        try { return cls.getMethod(name, params); }
        catch (NoSuchMethodException e) { return null; }
    }

    private DeployedClassesSync() {}

    /**
     * Outcome of one sync pass. Mainly useful for tests and the deployment
     * log line ("X file(s) synced to Y artifact(s)"); callers that don't
     * care can ignore the return value.
     */
    public record SyncReport(int artifactsSynced, int filesCopied, int artifactsSkipped) {
        public boolean didAnything() { return filesCopied > 0; }
    }

    /**
     * Mirrors module output into each exploded deployment's
     * {@code WEB-INF/classes/}. Safe to call from a background thread
     * (the compiler callback thread is the intended caller).
     */
    @NotNull
    public static SyncReport syncDeployments(@NotNull Project project,
                                             @NotNull List<Deployment> deployments,
                                             @NotNull TomcatDeploymentLogger logger) {
        if (project.isDisposed() || deployments.isEmpty()) {
            return new SyncReport(0, 0, 0);
        }

        // Header line so the user can SEE the feature engaged at all. Without
        // this, a project where every deployment gets skipped looks identical
        // to one where the sync was never wired in — exactly the "is anything
        // even happening?" failure mode the diagnostics here are designed for.
        logger.logServerInfo("Class sync: scanning " + deployments.size() + " deployment(s)...");

        long passStart = System.nanoTime();
        int syncedArtifacts = 0;
        int totalCopied = 0;
        int skipped = 0;

        for (Deployment deployment : deployments) {
            String name = deployment.getDisplayName();
            TomcatProgress.setDetail("Syncing classes: " + name);
            long artifactStart = System.nanoTime();
            Path artifactRoot = deployment.getResolvedPath();

            if (artifactRoot == null || !deployment.isValid()) {
                logger.logServerInfo("Class sync skipped '" + name
                        + "': deployment path missing or invalid"
                        + (artifactRoot != null ? " (" + artifactRoot + ")" : ""));
                skipped++;
                continue;
            }
            // WAR files cannot be hot-mirrored — repackaging is a build-tool concern.
            if (!deployment.isExploded()) {
                logger.logServerInfo("Class sync skipped '" + name
                        + "': type is war"
                        + " (only exploded deployments can be hot-mirrored; run mvn package or gradle war for WAR types)");
                skipped++;
                continue;
            }

            Path webInfClasses = artifactRoot.resolve(WEB_INF_CLASSES_PATH);
            // Some exploded layouts don't have a WEB-INF/classes/ yet (e.g. a
            // build that never produced bytecode). Create it on demand so the
            // first sync after a fresh checkout still works — Tomcat itself
            // requires this directory to exist for class loading anyway.
            try {
                Files.createDirectories(webInfClasses);
            } catch (IOException e) {
                LOG.debug("Could not create " + webInfClasses + " for " + name + ": " + e.getMessage());
                logger.logServerWarning("Class sync skipped '" + name
                        + "': cannot create WEB-INF/classes (" + e.getMessage() + ")");
                skipped++;
                continue;
            }

            // Module resolution runs under a read action because it touches
            // ModuleManager / ModuleRootManager / OrderEnumerator. We return
            // a ResolutionReport so we can tell the user which strategy
            // matched (or why none did) — this is the most common silent-fail
            // axis: artifact's module name doesn't match the IntelliJ module
            // name verbatim, content roots don't contain the deploy path,
            // and there are multiple web modules so the fallback won't pick.
            ResolutionReport resolution;
            try {
                resolution = TomcatReadActions.compute(() ->
                        resolveTyped(project, deployment));
            } catch (Throwable t) {
                LOG.debug("Could not resolve module output for '" + name + "': " + t.getMessage());
                logger.logServerWarning("Class sync skipped '" + name
                        + "': module resolution threw (" + t.getMessage() + ")");
                skipped++;
                continue;
            }

            if (resolution.moduleName() == null) {
                logger.logServerWarning("Class sync skipped '" + name
                        + "': could not resolve owning module — " + resolution.diagnostic());
                skipped++;
                continue;
            }
            if (resolution.sourceRoots().isEmpty()) {
                logger.logServerWarning("Class sync skipped '" + name
                        + "': module '" + resolution.moduleName()
                        + "' resolved but no production class output found (Make may not have run yet,"
                        + " or the module has no compilation output — check Build > Build Project first)");
                skipped++;
                continue;
            }

            // Log what we're about to do so a stale-classes failure points at
            // either the source paths (Make didn't write here) or the target
            // (Tomcat's not serving from here).
            logger.logServerInfo("Class sync: '" + name + "' -> module '" + resolution.moduleName()
                    + "' (" + resolution.strategy() + "), "
                    + resolution.sourceRoots().size() + " source root(s) -> " + webInfClasses);

            int copiedForThisArtifact = 0;
            int brokenForThisArtifact = 0;
            // Library JARs actually packaged into this deployment's WEB-INF/lib/.
            // A dependency module's resource policy is decided against this:
            // jarred dependency → .class-only (its resources come from the JAR);
            // dependency NOT packaged here → full content (so its resources
            // still reach Tomcat). Scanned once per artifact off the model.
            Set<String> deployedLibraryKeys = scanDeployedLibraryKeys(artifactRoot);
            // Union of every source root's contributed paths. Anything in the
            // deployed WEB-INF/classes/ NOT in this set is an orphan that
            // source no longer claims. With PreResources gone, the deployed
            // copy is the sole authority for what Tomcat loads, so stale
            // orphans aren't shadowed by anything — they keep getting
            // resolved by the classloader and the user sees "I deleted that
            // class, why is it still here" behaviour.
            java.util.Set<String> contributedPaths = new java.util.HashSet<>();
            for (SourceRoot src : resolution.sourceRoots()) {
                boolean classesOnly = shouldMirrorClassesOnly(src, deployedLibraryKeys);
                MirrorResult mr = mirrorTree(src.path(), webInfClasses, classesOnly);
                copiedForThisArtifact += mr.copied();
                brokenForThisArtifact += mr.brokenSkipped();
                contributedPaths.addAll(mr.contributedPaths());
                if (mr.copied() > 0) {
                    logger.logServerInfo("Class sync:     " + mr.copied() + " file(s) from " + src.path()
                            + (classesOnly ? " (.class only)" : ""));
                }
            }
            // Orphan-reconcile only when at least one source root actually
            // contributed (the EMPTY MirrorResult from an unreadable / non-
            // existent src returns no paths). Empty contributedPaths could
            // also mean "every source root was unreadable today"; deleting
            // everything in WEB-INF/classes/ on that failure mode would
            // destroy a working deployment. The empty-set guard is the
            // safety net.
            int orphansRemovedForThisArtifact = 0;
            if (!contributedPaths.isEmpty()) {
                orphansRemovedForThisArtifact = removeOrphans(webInfClasses, contributedPaths);
                if (orphansRemovedForThisArtifact > 0) {
                    logger.logServerInfo("Class sync: removed " + orphansRemovedForThisArtifact
                            + " orphan file(s) from '" + name
                            + "' (no longer in source — would otherwise stay loadable by Tomcat)");
                }
            }
            if (brokenForThisArtifact > 0) {
                // CRITICAL warning — this is the symptom that caused the user-
                // reported Tomcat startup failure with "Unresolved compilation
                // problems" Errors. Don't bury it. Point the user at the
                // actionable root cause (IDE project model out of sync with
                // Maven). Without this they'd be staring at a Tomcat stack
                // trace and have no idea their IDE's compile is the source.
                logger.logServerWarning("Class sync: " + brokenForThisArtifact
                        + " file(s) in '" + name + "' were NOT mirrored because the IDE compiled them"
                        + " with unresolved imports (ECJ proceed-on-error). Tomcat would fail at"
                        + " class init if these replaced the working deployed copies. To fix the"
                        + " compile: File > Invalidate Caches, or in the Maven tool window click"
                        + " Reload All Maven Projects, or run 'mvn install' on the command line.");
            }
            long artifactMs = (System.nanoTime() - artifactStart) / 1_000_000;
            if (copiedForThisArtifact > 0) {
                logger.logServerInfo("Class sync: " + copiedForThisArtifact +
                        " file(s) refreshed in '" + name + "' (" + contributedPaths.size()
                        + " source path(s) scanned, " + artifactMs + " ms)");
                syncedArtifacts++;
                totalCopied += copiedForThisArtifact;
            } else if (brokenForThisArtifact == 0) {
                logger.logServerInfo("Class sync: '" + name
                        + "' already up to date (source files match deployed WEB-INF/classes mtime/size; "
                        + contributedPaths.size() + " source path(s) scanned, " + artifactMs + " ms)");
            }
        }

        logger.logServerInfo("Class sync: scan complete — " + totalCopied
                + " file(s) refreshed across " + syncedArtifacts + " artifact(s), "
                + skipped + " skipped (" + (System.nanoTime() - passStart) / 1_000_000 + " ms)");
        return new SyncReport(syncedArtifacts, totalCopied, skipped);
    }

    /**
     * Resolves the owning module via the typed {@link Deployment}
     * hierarchy — no string matching anywhere. Three dispatch arms cover
     * the three deployment shapes — IntelliJ-registered artifact,
     * project module, and external file — each backed by a stable platform
     * pointer ({@code ArtifactPointer} / {@code ModulePointer}) so renames
     * and reloads are tracked automatically.
     *
     * <p><b>Must be called inside a read action.</b>
     */
    @NotNull
    static ResolutionReport resolveModuleOutputRootsVerbose(@NotNull Project project,
                                                            @NotNull DeploymentArtifact artifact) {
        Deployment typed = DeploymentAdapter.toTyped(project, artifact);
        return resolveTyped(project, typed);
    }

    @NotNull
    static ResolutionReport resolveTyped(@NotNull Project project, @NotNull Deployment deployment) {
        if (deployment instanceof ArtifactBackedDeployment a) {
            return resolveArtifactBacked(project, a);
        }
        if (deployment instanceof ModuleBackedDeployment m) {
            return resolveModuleBacked(project, m);
        }
        if (deployment instanceof ExternalFileDeployment) {
            return new ResolutionReport(null, "external-source-skipped", List.of(), null);
        }
        throw new IllegalStateException("Unhandled Deployment subtype: " + deployment.getClass());
    }

    @NotNull
    private static ResolutionReport resolveArtifactBacked(@NotNull Project project,
                                                          @NotNull ArtifactBackedDeployment d) {
        Artifact artifact = d.getArtifactPointer().getArtifact();
        if (artifact == null) {
            return new ResolutionReport(null, "artifact-missing", List.of(),
                    "IntelliJ Artifact '" + d.getArtifactName() + "' is no longer registered"
                    + " in Project Structure → Artifacts");
        }
        Module module = walkPackagingTreeForModule(
                artifact.getRootElement(),
                ArtifactManager.getInstance(project).getResolvingContext());
        if (module == null) {
            return new ResolutionReport(null, "artifact-has-no-module", List.of(),
                    "IntelliJ Artifact '" + d.getArtifactName() + "' contains no module-output"
                    + " element (built from files / libraries only)");
        }
        return new ResolutionReport(module.getName(),
                "artifact-tree: '" + d.getArtifactName() + "'",
                collectProductionRoots(project, module), null);
    }

    @NotNull
    private static ResolutionReport resolveModuleBacked(@NotNull Project project,
                                                        @NotNull ModuleBackedDeployment d) {
        Module module = d.getModule();
        if (module == null) {
            return new ResolutionReport(null, "module-missing", List.of(),
                    "Module '" + d.getModuleName() + "' no longer exists in the project");
        }
        return new ResolutionReport(module.getName(),
                "module-direct: '" + d.getModuleName() + "'",
                collectProductionRoots(project, module), null);
    }

    /**
     * One production class-output root plus the content policy the mirror
     * applies to it. {@code classesOnly == true} marks a <em>dependency</em>
     * module root (full-content mirroring it could duplicate resources that
     * also ship in the dependency's {@code WEB-INF/lib/} JAR);
     * {@code classesOnly == false} marks the web module's own root (always
     * full-content — its resources belong directly in {@code WEB-INF/classes/}
     * and are duplicated nowhere). See {@link #collectProductionRoots}.
     *
     * <p>{@code artifactName} is the dependency module's artifact identity
     * (Maven artifactId, or the module-name stem when Maven is unavailable),
     * or {@code null} for the own root and for any dependency whose identity
     * could not be resolved. The sync uses it to decide, per dependency root,
     * whether that dependency is actually packaged as a JAR in the deployed
     * {@code WEB-INF/lib/}: if it is, the resources come from the JAR and the
     * root stays {@code .class}-only; if it is not, the root is mirrored
     * full-content so its resources still reach Tomcat. A {@code null}
     * identity falls back to {@code .class}-only — the duplicate-safe default.
     * See {@link #shouldMirrorClassesOnly}.
     */
    record SourceRoot(@NotNull Path path, boolean classesOnly, @Nullable String artifactName) {}

    /**
     * Verbose-resolution result. {@code moduleName} is {@code null} only when
     * no strategy matched; in that case {@code diagnostic} carries the
     * explanation for the user-facing log line.
     */
    record ResolutionReport(@Nullable String moduleName,
                            @NotNull String strategy,
                            @NotNull List<SourceRoot> sourceRoots,
                            @Nullable String diagnostic) {}

    /**
     * Depth-first walk over a packaging-element subtree. Returns the first
     * module reached via the platform's {@code ModulePackagingElement}.
     * Reflection-based on purpose: {@code ModulePackagingElement} lives in
     * {@code com.intellij.packaging.impl.elements} — an {@code impl}
     * package that the plugin verifier may flag and that JetBrains does
     * not guarantee binary compatibility for — hence reflective access.
     *
     * <p>{@code PackagingElement}-typed so tests can drive it with mocked
     * trees. Public so {@link com.dev.idea.plugins.tomcat.runner.LocalDeploymentStrategy}
     * can reuse the same walk for its typed-deployment resolver.
     */
    @Nullable
    public static Module walkPackagingTreeForModule(@NotNull PackagingElement<?> element,
                                                    @NotNull PackagingElementResolvingContext ctx) {
        return walkPackagingTreeForModule(element, ctx,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Recursive helper with cycle protection. IntelliJ's packaging-element API
     * doesn't guarantee an acyclic tree shape — a composite element transitively
     * containing itself would loop forever without the visited set. The set
     * uses identity equality because two distinct element instances with the
     * same logical content are still different nodes worth re-visiting.
     */
    @Nullable
    private static Module walkPackagingTreeForModule(@NotNull PackagingElement<?> element,
                                                     @NotNull PackagingElementResolvingContext ctx,
                                                     @NotNull Set<PackagingElement<?>> visited) {
        if (!visited.add(element)) return null;
        Module direct = tryFindModuleOnElement(element, ctx);
        if (direct != null) return direct;
        if (element instanceof CompositePackagingElement<?> composite) {
            for (PackagingElement<?> child : composite.getChildren()) {
                Module m = walkPackagingTreeForModule(child, ctx, visited);
                if (m != null) return m;
            }
        }
        return null;
    }

    /**
     * Calls {@code ModulePackagingElement.findModule(ctx)} on {@code element}
     * via reflection if {@code element} happens to implement that interface.
     * Returns {@code null} if it doesn't, or if the reflective call fails.
     */
    @Nullable
    private static Module tryFindModuleOnElement(@NotNull PackagingElement<?> element,
                                                 @NotNull PackagingElementResolvingContext ctx) {
        Class<?> iface = MODULE_PACKAGING_ELEMENT_CLASS;
        Method method = MODULE_FIND_MODULE_METHOD;
        if (iface == null || method == null || !iface.isInstance(element)) return null;
        try {
            Object result = method.invoke(element, ctx);
            return result instanceof Module m ? m : null;
        } catch (ReflectiveOperationException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ModulePackagingElement.findModule failed reflectively", e);
            }
            return null;
        }
    }

    /**
     * Collects production class-output roots for the resolved web module
     * <strong>and every dependency module it transitively pulls in</strong>,
     * tagging each with the content policy the mirror should apply.
     *
     * <p><b>Why dependency modules are included at all.</b> In a multi-module
     * build the web module depends on library modules whose compiled classes
     * live in two places — the library module's fresh compile output (after
     * Make) and a stale copy inside {@code WEB-INF/lib/<lib>.jar} (frozen at
     * the last {@code package} run). Mirroring only the web module's own
     * output never refreshes the library classes, so Tomcat loads the stale
     * ones from the JAR and the user sees old behaviour after a restart.
     * Bringing the fresh dependency {@code .class} files into
     * {@code WEB-INF/classes/} makes them win via Tomcat's standard
     * classloader precedence ({@code WEB-INF/classes/} is searched before
     * {@code WEB-INF/lib/*.jar}).
     *
     * <p><b>Why a dependency root's resource policy is conditional.</b> A
     * module's compile-output root holds more than bytecode — under Maven
     * {@code target/classes/} also contains everything copied from
     * {@code src/main/resources/}. When the build packages that dependency as a
     * {@code WEB-INF/lib/<lib>.jar}, its resources already reach Tomcat through
     * the JAR; copying them into {@code WEB-INF/classes/} too would put the
     * same resource on the classpath twice, and any framework that discovers
     * resources by enumerating the classpath (service registrations,
     * descriptor lookups, factory files) fails when it finds two copies of a
     * path it expects to find once. But when a dependency is <em>not</em>
     * packaged as a JAR — e.g. its compile-output directory sits directly on
     * the runtime classpath — the JAR can't carry its resources, so a
     * {@code .class}-only mirror would silently drop them. The right policy
     * therefore depends on the actual deployed {@code WEB-INF/lib/} contents,
     * which only the caller knows; this method does not decide it. It tags each
     * dependency root with {@code classesOnly == true} (the candidate policy)
     * and resolves the dependency's <em>artifact identity</em> so the caller
     * can check coverage and refine the decision per root (see
     * {@link #shouldMirrorClassesOnly}). Bytecode duplication, when a root does
     * mirror {@code .class}-only alongside its JAR, is harmless — a single
     * fully-qualified name resolves to the first hit ({@code WEB-INF/classes/}
     * wins) and Java forbids the same name in two compile units.
     *
     * <p><b>Why the web module's OWN root is mirrored full-content.</b> The
     * web module is not packaged as a library of itself, so its resources are
     * not duplicated in any {@code WEB-INF/lib/} JAR — they belong directly in
     * {@code WEB-INF/classes/}, which is exactly where the WAR plugin places
     * them. Full-content mirroring there creates no duplication and keeps
     * non-class resources authored in the web module fresh.
     *
     * <p>Own vs dependency is distinguished structurally, with no name
     * matching: {@code withoutDepModules()} yields the module's own output;
     * the recursive enumeration yields own + transitive dependencies; any
     * root in the recursive set but not the own set is a dependency root.
     * Artifact identity, used only to match a dependency root against deployed
     * library JARs, comes from the Maven artifactId (or the module-name stem
     * when Maven is unavailable) and is {@code null} for the own root.
     *
     * <p>Excluded entirely: SDK roots (JRE classes mustn't go into the webapp)
     * and library JARs (those stay in {@code WEB-INF/lib/} via the build
     * tool). The mtime/size gate in {@link #shouldCopy} keeps repeated
     * restarts cheap even on large dependency graphs (~50 µs per stat on SSD).
     *
     * <p><b>Must be called inside a read action.</b>
     */
    @NotNull
    private static List<SourceRoot> collectProductionRoots(@NotNull Project project,
                                                           @NotNull Module module) {
        // The module's OWN production output (dependency modules excluded).
        // These paths get full-content mirroring; every other root the
        // recursive enumeration returns is a dependency root and is a
        // .class-only candidate, refined later against WEB-INF/lib.
        Set<Path> ownRoots = collectRootPaths(
                OrderEnumerator.orderEntries(module)
                        .withoutDepModules()
                        .productionOnly()
                        .withoutSdk()
                        .withoutLibraries()
                        .classes()
                        .getRoots());

        // Maps each dependency module's output-root path to its artifact
        // identity, so a dependency root can be matched against the JARs the
        // build packaged into the deployed WEB-INF/lib/.
        Map<String, String> dependencyArtifactNames =
                collectDependencyArtifactNames(project, module);

        List<SourceRoot> result = new ArrayList<>();
        for (VirtualFile root : OrderEnumerator.orderEntries(module)
                .recursively()
                .productionOnly()
                .withoutSdk()
                .withoutLibraries()
                .classes()
                .getRoots()) {
            try {
                Path p = Path.of(root.getPath());
                if (Files.isDirectory(p)) {
                    boolean dependency = !ownRoots.contains(p);
                    String artifactName = dependency
                            ? dependencyArtifactNames.get(root.getPath())
                            : null;
                    result.add(new SourceRoot(p, dependency, artifactName));
                }
            } catch (Exception e) {
                LOG.debug("Class sync: ignored non-filesystem output root " + root);
            }
        }
        return result;
    }

    /**
     * Maps an {@link OrderEnumerator} root array to a set of filesystem paths
     * for membership testing. Non-filesystem roots (e.g. in-memory test
     * fixtures) are skipped — they can never match a real dependency root on
     * disk, and the only use of this set is to recognise the module's own
     * output among the recursive roots.
     */
    @NotNull
    private static Set<Path> collectRootPaths(@NotNull VirtualFile[] roots) {
        Set<Path> paths = new HashSet<>();
        for (VirtualFile root : roots) {
            try {
                paths.add(Path.of(root.getPath()));
            } catch (Exception e) {
                LOG.debug("Class sync: ignored non-filesystem own output root " + root);
            }
        }
        return paths;
    }

    /**
     * Maps each transitive dependency module's production output-root path to
     * its artifact identity — the Maven artifactId when available, otherwise
     * the IntelliJ module name stripped of any compound project prefix
     * ({@code "myapp.common"} → {@code "common"}) so it lines up with the
     * {@code <artifactId>} stem of the JAR the build packages into
     * {@code WEB-INF/lib/}. Keyed by the same {@link VirtualFile#getPath()}
     * string {@link #collectProductionRoots} iterates, so a recursive root can
     * look up its owning dependency's identity directly.
     *
     * <p>Walks {@code ModuleOrderEntry} edges depth-first with a visited guard;
     * the web module's own output is intentionally absent (only dependencies
     * are mapped). <b>Must be called inside a read action.</b>
     */
    @NotNull
    private static Map<String, String> collectDependencyArtifactNames(@NotNull Project project,
                                                                      @NotNull Module module) {
        Map<String, String> result = new HashMap<>();
        collectDependencyArtifactNames(project, module, result, new HashSet<>());
        return result;
    }

    private static void collectDependencyArtifactNames(@NotNull Project project,
                                                       @NotNull Module module,
                                                       @NotNull Map<String, String> result,
                                                       @NotNull Set<String> visited) {
        if (!visited.add(module.getName())) return;
        for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
            if (!(entry instanceof ModuleOrderEntry moduleEntry)) continue;
            Module dep = moduleEntry.getModule();
            if (dep == null) continue;

            // Build-agnostic dependency identity, in order of precision:
            //   1. Maven artifactId — exact when the module is a Maven project.
            //   2. External-system (Gradle) project name — the build's OWN name for
            //      the module, which lines up with the JAR it packages far better
            //      than the IntelliJ module name does. A Gradle subproject's module
            //      is "app.sub.main", not "sub", so the old module-name-stem
            //      fallback mis-keyed it and mirrored its resources full-content
            //      even when they already shipped in sub.jar (duplicate classpath).
            //   3. Module-name stem — final fallback for plain / JPS projects.
            // All three are reduced to a version-independent key downstream.
            String artifactName = MavenModelProvider.artifactId(dep);
            if (artifactName == null) {
                artifactName = externalSystemArtifactName(dep);
            }
            if (artifactName == null) {
                String moduleName = dep.getName();
                int dot = moduleName.lastIndexOf('.');
                artifactName = dot >= 0 ? moduleName.substring(dot + 1) : moduleName;
            }

            for (VirtualFile outputRoot : OrderEnumerator.orderEntries(dep)
                    .productionOnly()
                    .withoutSdk()
                    .withoutLibraries()
                    .classes()
                    .getRoots()) {
                result.put(outputRoot.getPath(), artifactName);
            }

            collectDependencyArtifactNames(project, dep, result, visited);
        }
    }

    /**
     * Build-agnostic dependency name from the external-system model — gated to
     * Gradle, where the linked project id is a {@code ':'}-separated project path
     * whose leaf is the subproject (its archive baseName). Maven is intentionally
     * excluded: its coordinate-shaped id would mis-leaf to the version, and its
     * artifactId is read directly via {@link MavenModelProvider}. Returns
     * {@code null} for any other / no build system so the caller falls back to
     * the module-name stem. Uses only core platform API — no reflection.
     */
    @Nullable
    private static String externalSystemArtifactName(@NotNull Module dep) {
        ExternalSystemModulePropertyManager props = ExternalSystemModulePropertyManager.getInstance(dep);
        String systemId = props.getExternalSystemId();
        if (systemId == null || !systemId.equalsIgnoreCase("GRADLE")) return null;
        return gradleArtifactNameFromLinkedId(props.getLinkedProjectId());
    }

    /**
     * Extracts the subproject name from a Gradle linked-project id: the leaf
     * segment of the {@code ':'}- (or {@code '/'}-) separated path, with a
     * trailing source-set segment ({@code main}/{@code test}) dropped — so
     * {@code :app:sub} and {@code :app:sub:main} both yield {@code "sub"}.
     * Returns {@code null} when blank. Pure and package-visible for testing.
     */
    @Nullable
    static String gradleArtifactNameFromLinkedId(@Nullable String linkedProjectId) {
        if (linkedProjectId == null || linkedProjectId.isBlank()) return null;
        String[] segments = linkedProjectId.split("[:/]");
        int last = segments.length - 1;
        while (last > 0 && segments[last].isBlank()) last--;
        if (last > 0 && ("main".equals(segments[last]) || "test".equals(segments[last]))) {
            last--;
        }
        String leaf = last >= 0 ? segments[last].trim() : "";
        return leaf.isBlank() ? null : leaf;
    }

    /**
     * Decides whether a source root should mirror {@code .class} files only,
     * given the artifact keys actually present in the deployed
     * {@code WEB-INF/lib/}.
     *
     * <ul>
     *   <li>The web module's own root ({@code classesOnly == false}) always
     *       mirrors full content — never returns {@code true} here.</li>
     *   <li>A dependency whose artifact is packaged as a JAR in
     *       {@code WEB-INF/lib/} stays {@code .class}-only: its resources reach
     *       Tomcat through that JAR, so copying them into {@code WEB-INF/classes/}
     *       too would duplicate every shared path on the classpath.</li>
     *   <li>A dependency confirmed <em>absent</em> from {@code WEB-INF/lib/}
     *       mirrors full content, so its resources still reach Tomcat — nothing
     *       else carries them, so no duplication is possible.</li>
     *   <li>A dependency whose identity could not be resolved
     *       ({@code artifactName == null}) falls back to {@code .class}-only,
     *       the duplicate-safe default: copying resources a JAR also holds is
     *       the fatal failure, whereas a missed resource is not.</li>
     * </ul>
     *
     * <p>Both sides are normalized through
     * {@link LibraryArtifactNames#libraryArtifactKey} so the dependency's
     * identity and the deployed JAR's identity are compared on the same
     * version-independent basis.
     */
    static boolean shouldMirrorClassesOnly(@NotNull SourceRoot root,
                                           @NotNull Set<String> deployedLibraryKeys) {
        if (!root.classesOnly()) return false;
        String name = root.artifactName();
        if (name == null) return true;
        return deployedLibraryKeys.contains(
                LibraryArtifactNames.libraryArtifactKey(name + EXT_JAR));
    }

    /**
     * Reads the deployed {@code WEB-INF/lib/} and returns the version-independent
     * artifact key (via {@link LibraryArtifactNames#libraryArtifactKey}) of
     * every JAR present. Empty when the directory is absent or unreadable.
     * Plain file I/O — no read action or project-model access, safe on the
     * background sync thread.
     */
    @NotNull
    static Set<String> scanDeployedLibraryKeys(@NotNull Path artifactRoot) {
        Set<String> keys = new HashSet<>();
        Path webInfLib = artifactRoot.resolve(WEB_INF_LIB_PATH);
        if (!Files.isDirectory(webInfLib)) return keys;
        try (var stream = Files.list(webInfLib)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXT_JAR))
                  .forEach(p -> keys.add(
                          LibraryArtifactNames.libraryArtifactKey(p.getFileName().toString())));
        } catch (IOException e) {
            LOG.debug("Class sync: could not list WEB-INF/lib at " + webInfLib
                    + ": " + e.getMessage());
        }
        return keys;
    }

    /**
     * Resolves the deployment artifact to its owning module and returns the
     * full production classpath roots — the owning module plus every
     * dependency module it transitively pulls in. SDK and library-JAR
     * roots are excluded (those belong in {@code WEB-INF/lib/}, not
     * {@code WEB-INF/classes/}). Empty list when no module resolves or
     * none of its output dirs exist on disk.
     *
     * <p><b>Must be called inside a read action.</b>
     *
     * <p>Thin facade over {@link #resolveModuleOutputRootsVerbose} — kept so
     * existing test fixtures (and any future internal caller that only
     * cares about the roots) don't need to unpack the ResolutionReport.
     */
    @NotNull
    static List<Path> resolveModuleOutputRoots(@NotNull Project project,
                                               @NotNull DeploymentArtifact artifact) {
        return resolveModuleOutputRootsVerbose(project, artifact).sourceRoots()
                .stream().map(SourceRoot::path).toList();
    }

    /**
     * Result of mirroring one source root.
     *
     * <p>{@code contributedPaths} is the set of forward-slash-normalized
     * relative paths the source walk visited (regardless of whether each was
     * actually copied). These are the paths the source root claims authority
     * over — anything in the destination tree NOT in the union of every
     * source root's {@code contributedPaths} is an orphan that the caller
     * may safely delete.
     */
    record MirrorResult(int copied,
                        int brokenSkipped,
                        @NotNull java.util.Set<String> contributedPaths) {
        static final MirrorResult EMPTY = new MirrorResult(
                0, 0, java.util.Collections.emptySet());
    }

    /**
     * Full-content mirror — copies every file (the web module's own output
     * root). Convenience overload of {@link #mirrorTree(Path, Path, boolean)}
     * with {@code classesOnly == false}.
     */
    // Package-visible so DeployedClassesSyncScenariosTest can drive end-to-end
    // mirror behaviour without standing up a Project/ModuleManager fixture.
    static MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst) {
        return mirrorTree(src, dst, false);
    }

    /**
     * Walks {@code src} and copies every file that is missing in {@code dst}
     * or older than its {@code src} counterpart. Returns counts for files
     * copied and files skipped because they were detected as ECJ
     * "compile-with-errors" stubs (see {@link #isBrokenEcjClass}).
     *
     * <p>When {@code classesOnly} is {@code true}, only {@code .class} files
     * are considered — every other file is skipped and, crucially, left OUT
     * of {@code contributedPaths} so the caller's orphan pass removes any copy
     * an earlier full-content sync left behind. This is the dependency-module
     * policy: a dependency's non-class resources already ship inside its
     * {@code WEB-INF/lib/} JAR, so duplicating them into {@code WEB-INF/classes/}
     * would break classpath-enumeration frameworks (see
     * {@link #collectProductionRoots}). The web module's own root passes
     * {@code false} and mirrors full content.
     *
     * <p>The walker swallows per-file IOExceptions to avoid aborting a sync
     * mid-way when one file is briefly locked (Windows file-handles, IDE
     * indexing). Each failure is debug-logged with the source path so the
     * issue is visible in {@code idea.log} without polluting the run
     * console.
     */
    static MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst, boolean classesOnly) {
        if (!Files.isDirectory(src)) return MirrorResult.EMPTY;

        // Pre-flight nesting guard: refuse to recurse when src and dst nest
        // inside each other. Two failure modes this catches:
        //   1. src contains dst — the walker would re-enter dst as it copies
        //      files INTO dst, doubling/looping every file.
        //   2. dst contains src — copying src/x → dst/x where dst is src's
        //      parent ends up overwriting the source. Less harmful but still
        //      nonsense.
        // Both cases happen in misconfigured setups where the user pointed
        // the deployment at the wrong directory. Better to bail loudly than
        // produce a runaway sync that fills the disk.
        Path srcNorm = src.toAbsolutePath().normalize();
        Path dstNorm = dst.toAbsolutePath().normalize();
        if (dstNorm.startsWith(srcNorm) || srcNorm.startsWith(dstNorm)) {
            LOG.warn("Class sync: refusing nested src/dst paths: src=" + srcNorm
                    + " dst=" + dstNorm);
            return MirrorResult.EMPTY;
        }

        final int[] copied = {0};
        final int[] brokenSkipped = {0};
        // Forward-slash-normalized relative paths the walker visited, regardless
        // of copy outcome. Used by the caller to compute orphan candidates in
        // the destination tree.
        final java.util.Set<String> contributedPaths = new java.util.HashSet<>();
        try {
            // Default FileVisitOption set = do NOT follow symlinks. We don't
            // pass FOLLOW_LINKS: a symlinked subdirectory could point outside
            // the project, into the destination tree, or form a loop. Java's
            // walker honours this and just visits the link as a regular file
            // (which we then skip via the explicit isSymbolicLink check below
            // so it never even gets to the copy path).
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult preVisitDirectory(Path dir, @NotNull BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        // Symlinked directory inside the source tree — skip.
                        // We don't trust where it points; could be an infinite
                        // loop or an external dir we shouldn't copy from.
                        LOG.debug("Class sync: skipping symlinked directory " + dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
                    // Cooperative cancellation: this walk runs under the
                    // launch-prep modal and the update task's indicator.
                    TomcatProgress.checkCanceled();
                    try {
                        // Skip symlinks. The mirror's contract is "copy
                        // source-of-truth class files"; a symlink doesn't
                        // qualify, and following one could land outside the
                        // project's compile output.
                        if (attrs.isSymbolicLink()) {
                            LOG.debug("Class sync: skipping symlink " + file);
                            return FileVisitResult.CONTINUE;
                        }

                        // Dependency-module roots mirror .class files ONLY.
                        // A dependency's non-class resources already live in
                        // its WEB-INF/lib/<lib>.jar; copying them into
                        // WEB-INF/classes/ would put the same resource on the
                        // classpath twice and break frameworks that enumerate
                        // classpath resources by name. Skip BEFORE recording
                        // the path so the skipped file is absent from
                        // contributedPaths and the caller's orphan pass deletes
                        // any copy an earlier full-content sync left behind
                        // (self-healing a deployment broken by the old policy).
                        if (classesOnly
                                && !file.getFileName().toString().endsWith(EXT_CLASS)) {
                            return FileVisitResult.CONTINUE;
                        }

                        Path rel = src.relativize(file);
                        // Record the relative path BEFORE any gate. The
                        // orphan-reconcile contract is "anything in dst that
                        // isn't in src is an orphan" — every source file the
                        // user authored (even one we skip because it is already
                        // up to date, or refuse because it is a broken stub)
                        // must be in contributedPaths, or the orphan pass would
                        // delete the matching deployed copy.
                        contributedPaths.add(rel.toString().replace('\\', '/'));
                        Path target = dst.resolve(rel.toString());

                        // Cheap mtime/size gate FIRST. When the deployed copy is
                        // already current we return before the broken-class scan
                        // below — that scan reads the whole file, so running it
                        // for every up-to-date class on every sync would read
                        // the entire deployed classpath off disk each
                        // launch/update (the dominant cost on large multi-module
                        // projects). Gating it behind the copy decision keeps a
                        // no-op sync at stat-only cost; only copy candidates are
                        // ever read.
                        if (!shouldCopy(file, attrs, target)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // CRITICAL gate: if the source is a broken ECJ
                        // "compile-with-errors" class file, refuse to copy.
                        // Overwriting a working deployed copy with a stub
                        // that throws
                        //   java.lang.Error("Unresolved compilation problems")
                        // at class init time would fail Tomcat's webapp startup
                        // with no obvious connection to the IDE's compile state.
                        // Leave the working copy in place and surface the count
                        // via the per-artifact summary in syncDeployments so the
                        // user knows what happened.
                        if (isBrokenEcjClass(file)) {
                            brokenSkipped[0]++;
                            LOG.warn("Class sync: refusing to copy ECJ broken-class stub: "
                                    + file);
                            return FileVisitResult.CONTINUE;
                        }

                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try {
                            // Copy WITHOUT COPY_ATTRIBUTES on every platform (Windows,
                            // Linux, macOS) — the deployed copy needs none of the
                            // source's permissions/ACLs anywhere. The speedup is
                            // largest on Windows, where COPY_ATTRIBUTES additionally
                            // re-applies NTFS security attributes (ACLs) per file — the
                            // dominant cost on large multi-module syncs (the
                            // copySecurityAttributes frames in the EDT-freeze report);
                            // on Linux/macOS it just avoids a cheaper permission copy.
                            // We still mirror just the source mtime onto the copy so
                            // shouldCopy's gate stays exact (dst mtime == src mtime),
                            // keeping an unchanged file a no-op on the next sync — same
                            // behaviour on every OS.
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                            try {
                                Files.setLastModifiedTime(target, attrs.lastModifiedTime());
                            } catch (IOException ignoreMtime) {
                                // mtime is a gate optimization, not correctness — worst
                                // case the next sync re-copies this one file.
                            }
                            copied[0]++;
                        } catch (java.nio.file.NoSuchFileException vanished) {
                            // The source file disappeared between visitFile and
                            // copy — common when the IDE re-compiles concurrently
                            // (Make replaces .class atomically). Don't count as
                            // copy, don't fail the walk; the next sync picks it
                            // up. Debug-level only.
                            LOG.debug("Class sync: source vanished during copy: " + file);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.debug("Class sync: skipped " + file + " (" + e.getMessage() + ")");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Walk continues past unreadable files (e.g. a directory
                    // the user can't read). One bad file shouldn't abort
                    // the whole mirror.
                    LOG.debug("Class sync: cannot visit " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("Class sync: walk failed for " + src + " (" + e.getMessage() + ")");
        }
        return new MirrorResult(copied[0], brokenSkipped[0], contributedPaths);
    }

    /**
     * Walks {@code dst} and deletes regular files whose forward-slash-
     * normalized relative paths are NOT in {@code retain}. Used by the caller
     * to reconcile the destination tree to the union of every source root's
     * contributed paths after the copy pass: anything the source no longer
     * claims is an orphan that would otherwise linger and stay loadable by
     * Tomcat. Empty directories left behind are not pruned (harmless to
     * Tomcat; saves a second walk).
     *
     * <p>Per-file IOExceptions are debug-logged and skipped — a transient
     * Windows file-lock should not abort the whole orphan pass, and the
     * next sync will retry.
     *
     * <p>Visible for testing — exercised directly in
     * {@code DeployedClassesSyncTest} so test fixtures don't need to
     * round-trip through {@code syncDeployments}.
     */
    static int removeOrphans(@NotNull Path dst,
                             @NotNull java.util.Set<String> retain) {
        if (!Files.isDirectory(dst)) return 0;
        final int[] removed = {0};
        try {
            Files.walkFileTree(dst, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
                    TomcatProgress.checkCanceled();
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = dst.relativize(file).toString().replace('\\', '/');
                    if (!retain.contains(rel)) {
                        try {
                            Files.delete(file);
                            removed[0]++;
                            LOG.debug("Class sync: removed orphan " + file);
                        } catch (IOException e) {
                            LOG.debug("Class sync: could not delete orphan "
                                    + file + " (" + e.getMessage() + ")");
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.debug("Class sync: orphan walk could not visit "
                            + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("Class sync: orphan walk failed for " + dst + " (" + e.getMessage() + ")");
        }
        return removed[0];
    }

    /**
     * Marker bytes embedded by Eclipse JDT Compiler (ECJ) into class files
     * generated under its "proceed with errors" mode. When a Java source has
     * unresolved imports / symbols, ECJ can still emit a {@code .class} file
     * whose problematic constructor / method bodies are replaced with
     * {@code throw new Error("Unresolved compilation problems: ...")}. The
     * literal English string ends up in the class's constant pool and is
     * stable across ECJ versions — Eclipse's
     * {@code org.eclipse.jdt.internal.compiler.codegen.ProblemReporter}
     * hardcodes this prefix.
     *
     * <p>{@code javac} never produces such files; it aborts the build on
     * unresolved imports. So a {@code .class} carrying this marker is a
     * strong signal that the IDE's incremental compile completed despite
     * errors. Mirroring it into the deployment would replace a working
     * (Maven- or javac-built) copy with a stub that throws at class init.
     */
    private static final byte[] ECJ_ERROR_MARKER =
            "Unresolved compilation problems"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Upper bound on file size for the broken-class scan. Real-world class
     * files almost always sit below 100&nbsp;KB; multi-method generated
     * classes can reach a few hundred KB; truly pathological cases
     * (annotation-processor-generated mega-classes) can exceed 1&nbsp;MB.
     * 16&nbsp;MB is an order of magnitude above any of those — anything
     * past it is either not a real class file or a project we cannot
     * usefully help. Skipping the scan beyond the cap avoids the
     * {@link OutOfMemoryError} risk of {@link Files#readAllBytes} on a
     * malicious / corrupt file.
     */
    private static final long ECJ_SCAN_MAX_BYTES = 16L * 1024 * 1024;

    /**
     * Returns {@code true} when {@code classFile} is an ECJ-generated
     * "compile-with-errors" stub that would throw {@code java.lang.Error} at
     * class init. Restricted to {@code .class} files to keep the resource
     * sync path fast (no scan of {@code .properties} / {@code .xml} etc).
     *
     * <p>Resilience guards (cheap-fast-fail in order):
     * <ol>
     *   <li>Non-{@code .class} files short-circuit to {@code false}.</li>
     *   <li>Files smaller than the marker itself short-circuit — they
     *       physically cannot contain the marker bytes.</li>
     *   <li>Files larger than {@link #ECJ_SCAN_MAX_BYTES} short-circuit —
     *       loading a multi-gigabyte file into a {@code byte[]} for a
     *       substring scan is never the right call.</li>
     *   <li>Any {@link IOException} on size or read falls through to
     *       {@code false} (let the regular copy path handle the error).</li>
     * </ol>
     *
     * <p>Visible for tests so the detection itself can be pinned with
     * synthetic bytes (no live ECJ run needed in CI).
     */
    static boolean isBrokenEcjClass(@NotNull Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(EXT_CLASS)) return false;

        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            // File vanished or not stat-able — let the regular copy path
            // handle the error (it'll also fail and skip).
            return false;
        }
        // Fast reject: a file smaller than the marker cannot contain it.
        if (size < ECJ_ERROR_MARKER.length) return false;
        // Defensive cap: refuse to slurp absurdly large "class" files into
        // memory. Real ECJ stubs are tiny (often <2 KB); this is purely
        // about not OOMing on a corrupt or malicious input.
        if (size > ECJ_SCAN_MAX_BYTES) {
            LOG.warn("Class sync: skipping ECJ-marker scan for oversized class file "
                    + "(" + size + " bytes > " + ECJ_SCAN_MAX_BYTES + "): " + file);
            return false;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | OutOfMemoryError e) {
            // Read failed — err on side of allowing the copy. The mtime-gate
            // copy would also have failed; no worse than pre-detector.
            return false;
        }
        return containsEcjErrorMarker(bytes);
    }

    /**
     * Returns {@code true} iff {@link #ECJ_ERROR_MARKER} appears as a
     * contiguous byte subsequence of {@code haystack}.
     *
     * <p>Naive byte-search rather than KMP / regex — the marker is a fixed
     * 31-byte literal and {@code haystack} is capped at
     * {@link #ECJ_SCAN_MAX_BYTES} by the caller, so even the quadratic
     * worst case runs in microseconds. Previously written as a generic
     * {@code indexOf(haystack, needle)}, but the only call site ever passed
     * {@code ECJ_ERROR_MARKER} — the IDE's "parameter is always this constant"
     * inspection flagged that as dead generality. Inlining the needle makes
     * the call site read as the boolean question it actually asks.
     */
    private static boolean containsEcjErrorMarker(@NotNull byte[] haystack) {
        byte[] needle = ECJ_ERROR_MARKER;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code source} should be written to
     * {@code target}. Two-axis check:
     *
     * <ol>
     *   <li><b>mtime forward:</b> source is strictly newer than destination
     *       — the common edit-then-restart case.</li>
     *   <li><b>size mismatch (tie-breaker):</b> mtimes equal but file sizes
     *       differ. This catches the edge case where a previous sync set
     *       {@code dst.mtime == src.mtime} via {@code Files.setLastModifiedTime}, then
     *       the user edited the source again within the filesystem's mtime
     *       resolution (1-second on some FSes, lower on APFS/NTFS but not
     *       impossible on a fast SSD). Without this tie-breaker, the
     *       second edit silently fails to sync — exactly the "I changed the
     *       file but the restart shows old code" symptom that the class
     *       sync was added to fix.</li>
     * </ol>
     *
     * <p>The mtime gate is still load-bearing for performance: a typical
     * web module has hundreds of class files, and unconditional copying
     * would add visible latency to every restart. The size tie-breaker
     * adds no extra syscall: the destination's mtime and size come from a
     * single {@code Files.readAttributes} call, and the source's from the
     * {@code BasicFileAttributes} the file-tree walk already supplied.
     */
    static boolean shouldCopy(@NotNull Path source,
                              @NotNull BasicFileAttributes sourceAttrs,
                              @NotNull Path target) {
        // Single stat for the destination (mirrors WebResourcesSync.shouldCopy):
        // one readAttributes fetches mtime + size together instead of a separate
        // exists + getLastModifiedTime + size. This runs once per source file, so
        // on a large multi-module sync the saved syscalls add up.
        BasicFileAttributes dstAttrs;
        try {
            dstAttrs = Files.readAttributes(target, BasicFileAttributes.class);
        } catch (java.nio.file.NoSuchFileException missing) {
            return true;
        } catch (IOException e) {
            // Can't read the destination — prefer to copy (safer than leaving stale code).
            return true;
        }
        if (sourceAttrs.lastModifiedTime().toMillis() > dstAttrs.lastModifiedTime().toMillis()) {
            return true;
        }
        // Size-tiebreaker for the equal-or-older mtime case. We don't care about
        // a "src is older than dst" scenario — that would mean the user reverted
        // a file, and overwriting with the older version is fine. So: if mtimes
        // match exactly and sizes differ, copy. This also catches a second edit
        // landing within the filesystem's mtime resolution.
        return sourceAttrs.size() != dstAttrs.size();
    }

}
