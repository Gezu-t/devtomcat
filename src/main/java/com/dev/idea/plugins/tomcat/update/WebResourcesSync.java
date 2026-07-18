package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.utils.MavenModelProvider;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_CLASSES_PATH;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_LIB_PATH;

/**
 * Mirrors a module's {@code src/main/webapp/} tree into its exploded artifact
 * directory so JSP / JS / CSS / HTML / image edits take effect without a
 * full {@code mvn package} / {@code gradle war} repackage.
 *
 * <p>This complements {@link DeployedClassesSync} which only handles
 * {@code target/classes/} → {@code WEB-INF/classes/}. IntelliJ's
 * incremental Make task compiles {@code .java} → {@code .class} but does
 * NOT copy webapp source resources (JSPs and friends) into the artifact
 * output. Maven only does that during {@code prepare-package}, which the
 * Update action never triggers. Result before this sync was wired in:
 * editing {@code index.jsp} and clicking Update Classes and Resources
 * appeared to do nothing — Tomcat kept serving the previous
 * {@code mvn-package}'d copy from {@code target/<war>/index.jsp}.
 *
 * <p>What gets mirrored: every regular file under {@code src/main/webapp/}
 * EXCEPT files under {@code WEB-INF/classes/} (owned by
 * {@link DeployedClassesSync}) and {@code WEB-INF/lib/} (owned by the
 * build tool's dependency resolver). Symlinks and the destination
 * subtree are skipped. mtime + size gate avoids re-copying unchanged
 * files (the same gate {@link DeployedClassesSync#shouldCopy} uses).
 *
 * <p>Stale cleanup ({@link SyncManifest#reconcile}): a file removed from a
 * webapp source would otherwise stay served by Tomcat, so it is cleaned —
 * but ONLY files this sync itself wrote on a prior run (tracked in a
 * per-deployment manifest). The build legitimately places files in the
 * exploded webapp that no webapp source enumerates — filtered
 * {@code <webResources>}, WAR overlays, frontend build output, generated
 * descriptors — and none of those are ever in the manifest, so they are
 * never deleted (the failure mode of a blunt "delete everything the source
 * walk didn't visit" pass: silent 404s and missing assets). Deferred
 * entirely for an artifact when no source contributed or any source's walk
 * was incomplete.
 *
 * <p>WAR-packaged artifacts are skipped — repackaging is a build-tool
 * concern; the user should switch to exploded deployment or run
 * {@code mvn package} explicitly.
 */
public final class WebResourcesSync {

    private static final Logger LOG = Logger.getInstance(WebResourcesSync.class);

    /**
     * Subtrees under {@code src/main/webapp/} that are NOT mirrored.
     * {@code WEB-INF/classes/} comes from compilation output via
     * {@link DeployedClassesSync}; {@code WEB-INF/lib/} comes from
     * dependency resolution (Maven / Gradle). Mirroring either would
     * either collide with the other pipeline or pull in empty/wrong
     * content.
     */
    private static final Set<String> SKIP_SUBTREES = Set.of(
            WEB_INF_CLASSES_PATH,
            WEB_INF_LIB_PATH);

    private WebResourcesSync() {}

    /** Aggregate result across all artifacts in a single call. */
    public record SyncReport(int artifactsSynced, int totalCopied, int skipped) {}

    /**
     * File name of the LEGACY per-deployment web-resources manifest, which earlier
     * versions wrote into the deployed {@code WEB-INF/}. The live manifest now
     * resides in the IDE-owned {@link SyncManifestStore} (never inside the webapp);
     * this name survives only as the migration source and the self-heal target.
     * Distinct from the class-sync manifest
     * ({@link DeployedClassesSync#CLASS_SYNC_MANIFEST}); each pipeline reconciles
     * exclusively against its own record. Note the store manifest deliberately
     * survives a clean rebuild — reconcile stamps (size+mtime+creation) keep
     * deletions correct across it.
     */
    static final String WEB_RESOURCES_MANIFEST = ".devtomcat-webresources.manifest";

    /**
     * Deletes DevTomcat's own leftover metadata files from {@code webInfDir} —
     * exactly {@link #WEB_RESOURCES_MANIFEST} and
     * {@link DeployedClassesSync#CLASS_SYNC_MANIFEST}, nothing else. Earlier
     * versions could write them into a <em>source</em> webapp's {@code WEB-INF};
     * they are plugin bookkeeping and never belong in the user's tree. Exact
     * filename match only; best-effort (IO failures are logged and skipped).
     * Returns how many files were removed. Package-visible for tests.
     */
    static int removeLegacyMetadata(@NotNull Path webInfDir) {
        int removed = 0;
        for (String name : new String[]{WEB_RESOURCES_MANIFEST, DeployedClassesSync.CLASS_SYNC_MANIFEST}) {
            try {
                if (Files.deleteIfExists(webInfDir.resolve(name))) {
                    removed++;
                    LOG.info("Removed leftover DevTomcat metadata: " + webInfDir.resolve(name));
                }
            } catch (IOException e) {
                LOG.debug("Could not remove leftover metadata " + webInfDir.resolve(name)
                        + " (" + e.getMessage() + ")");
            }
        }
        return removed;
    }

    /**
     * Location of the per-deployment web-resources manifest for a given exploded
     * artifact root: in the IDE-owned manifest store, never inside the webapp.
     * A legacy manifest written by earlier versions at
     * {@code <artifactRoot>/WEB-INF/.devtomcat-webresources.manifest} is adopted
     * into the store (and removed from the webapp) on first contact.
     */
    @NotNull
    static Path webResourcesManifestFor(@NotNull Path artifactRoot) {
        return SyncManifestStore.resolveWithMigration(
                "webresources", artifactRoot,
                artifactRoot.resolve(WEB_INF).resolve(WEB_RESOURCES_MANIFEST));
    }

    /**
     * Mirrors {@code src/main/webapp/} into the exploded artifact directory
     * for every exploded {@link Deployment} in {@code deployments}. Per-entry
     * progress goes through {@code logger} so a stale-resource failure points
     * at the source root / target that was scanned.
     */
    @NotNull
    public static SyncReport syncDeployments(@NotNull Project project,
                                             @NotNull List<Deployment> deployments,
                                             @NotNull TomcatDeploymentLogger logger) {
        if (project.isDisposed() || deployments.isEmpty()) {
            return new SyncReport(0, 0, 0);
        }

        logger.logServerInfo("Web resources sync: scanning " + deployments.size() + " deployment(s)...");

        long passStart = System.nanoTime();
        int syncedArtifacts = 0;
        int totalCopied = 0;
        int skipped = 0;

        for (Deployment deployment : deployments) {
            String name = deployment.getDisplayName();
            TomcatProgress.setDetail("Syncing web resources: " + name);
            long artifactStart = System.nanoTime();
            Path artifactRoot = deployment.getResolvedPath();

            if (artifactRoot == null || !deployment.isValid()) {
                logger.logServerInfo("Web resources sync skipped '" + name
                        + "': deployment path missing or invalid"
                        + (artifactRoot != null ? " (" + artifactRoot + ")" : ""));
                skipped++;
                continue;
            }
            // WAR artifacts cannot be hot-mirrored — only the build tool can
            // re-pack the archive. Same call-out as class sync.
            if (!deployment.isExploded()) {
                logger.logServerInfo("Web resources sync skipped '" + name
                        + "': type is war"
                        + " (only exploded deployments can be hot-mirrored)");
                skipped++;
                continue;
            }

            // Never mirror into the user's source tree — same invariant as class
            // sync. A content-directory docBase (e.g. src/main/webapp) is refused
            // so an overlay source is never copied over hand-authored files.
            if (Boolean.TRUE.equals(TomcatReadActions.compute(
                    () -> DeploymentSafety.isInsideProjectContent(project, artifactRoot)))) {
                logger.logServerWarning("Web resources sync skipped '" + name
                        + "': deployment path is inside the project source tree (" + artifactRoot + "). "
                        + "Point this deployment at the exploded build output instead.");
                skipped++;
                continue;
            }

            // Resolve every applicable source directory: convention dirs (Maven
            // src/main/webapp, Eclipse WebContent, IntelliJ default web/, etc.),
            // content-root-IS-webapp layouts, AND Maven war-plugin <webResources>
            // extra dirs. Multiple sources are mirrored in order — later sources
            // overlay earlier ones on shared relative paths, matching Maven's
            // own webResources copy semantics.
            List<Path> webappSources;
            try {
                webappSources = TomcatReadActions.compute(() -> findWebappSourceRootsForTyped(project, deployment));
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                // Cancellation from the read-action traversal must propagate
                // before the generic handler, or the user's Cancel is mislogged
                // as a resolution failure and the loop continues (mirrors the
                // DeployedClassesSync fix).
                throw pce;
            } catch (Throwable t) {
                LOG.debug("Web resources sync: module resolve threw for '" + name + "': " + t.getMessage());
                logger.logServerWarning("Web resources sync skipped '" + name
                        + "': module resolution threw (" + t.getMessage() + ")");
                skipped++;
                continue;
            }

            if (webappSources.isEmpty()) {
                logger.logServerInfo("Web resources sync skipped '" + name
                        + "': no webapp source directory found under the owning module's content"
                        + " roots (checked src/main/webapp, web, WebContent, src/webapp, webapp,"
                        + " WebRoot, src/main/web, src/main/resources/static, public, the content"
                        + " root itself, and any Maven war-plugin <webResources> dirs — fine for"
                        + " class-only modules with no JSP/JS/CSS to mirror)");
                skipped++;
                continue;
            }

            int copiedForThisArtifact = 0;
            // Union of every webapp source root's contributed paths — what this
            // run's sync claims to have covered. The stale-file reconcile below
            // records it as the new manifest and deletes ONLY files a prior
            // manifest recorded that are no longer contributed (removed from
            // source). Files the build placed that no webapp source enumerates
            // (filtered <webResources>, WAR overlays, frontend build output,
            // generated descriptors) are never in any manifest and never deleted.
            java.util.Set<String> contributedPaths = new java.util.HashSet<>();
            // Same partial-walk guard as DeployedClassesSync: if ANY source
            // root's walk was incomplete, contributedPaths is a partial union
            // and reconciling against it would delete previously-synced files
            // the failed root still owns. Defer the reconcile instead.
            boolean allRootsWalkedCleanly = true;
            for (Path src : webappSources) {
                // Self-heal: earlier versions (before the source-tree guard and
                // the manifest store) could leave DevTomcat manifests inside a
                // SOURCE webapp's WEB-INF. They are ours by exact name — remove
                // them so the user's tree stays clean.
                int healed = removeLegacyMetadata(src.resolve(WEB_INF));
                if (healed > 0) {
                    logger.logServerInfo("Web resources sync: removed " + healed
                            + " leftover DevTomcat metadata file(s) from " + src.resolve(WEB_INF));
                }
                logger.logServerInfo("Web resources sync: '" + name + "' -> " + src + " -> " + artifactRoot);
                TreeMirror.MirrorResult mr = mirrorTree(src, artifactRoot);
                copiedForThisArtifact += mr.copied();
                contributedPaths.addAll(mr.contributedPaths());
                if (mr.walkFailed()) {
                    allRootsWalkedCleanly = false;
                    logger.logServerWarning("Web resources sync: source root " + src
                            + " for '" + name + "' could not be fully read;"
                            + " stale-file cleanup deferred to avoid deleting deployed files.");
                }
                if (mr.copied() > 0) {
                    logger.logServerInfo("Web resources sync:     " + mr.copied() + " file(s) from " + src);
                }
            }
            int orphansRemovedForThisArtifact = 0;
            // Reconcile against the manifest of what WE synced last run: delete
            // only files we previously wrote and no longer do. Requires every
            // root's walk to have completed cleanly AND at least one contributed
            // path (all-empty means every source was unreadable — reconciling on
            // that would mark everything we ever synced as stale).
            if (!contributedPaths.isEmpty() && allRootsWalkedCleanly) {
                orphansRemovedForThisArtifact = SyncManifest.reconcile(
                        artifactRoot, webResourcesManifestFor(artifactRoot), contributedPaths);
                if (orphansRemovedForThisArtifact > 0) {
                    logger.logServerInfo("Web resources sync: removed " + orphansRemovedForThisArtifact
                            + " stale file(s) from '" + name
                            + "' (previously synced, now removed from source)");
                }
            } else if (!contributedPaths.isEmpty()) {
                // Deferred run: not safe to delete, but the mirror may have just
                // overwritten deployed files — refresh their recorded stamps or
                // a file edited during a deferred run could never be cleaned
                // once removed from source (stale stamp = permanent leak).
                SyncManifest.refresh(
                        artifactRoot, webResourcesManifestFor(artifactRoot), contributedPaths);
            }
            long artifactMs = (System.nanoTime() - artifactStart) / 1_000_000;
            if (copiedForThisArtifact > 0 || orphansRemovedForThisArtifact > 0) {
                logger.logServerInfo("Web resources sync: " + copiedForThisArtifact
                        + " file(s) refreshed and " + orphansRemovedForThisArtifact
                        + " orphan(s) removed in '" + name + "' (" + contributedPaths.size()
                        + " source path(s) scanned, " + artifactMs + " ms)");
                syncedArtifacts++;
                totalCopied += copiedForThisArtifact;
            } else {
                logger.logServerInfo("Web resources sync: '" + name
                        + "' already up to date (source files match deployed copies' mtime/size; "
                        + contributedPaths.size() + " source path(s) scanned, " + artifactMs + " ms)");
            }
        }

        logger.logServerInfo("Web resources sync: scan complete — " + totalCopied
                + " file(s) refreshed across " + syncedArtifacts + " artifact(s), "
                + skipped + " skipped (" + (System.nanoTime() - passStart) / 1_000_000 + " ms)");
        return new SyncReport(syncedArtifacts, totalCopied, skipped);
    }

    /**
     * Resolves a deployment to every applicable webapp source directory, using
     * the same {@link DeployedClassesSync#resolveTyped} dispatch as class sync
     * so both pipelines pick the same module for any given deployment. The
     * returned list is ordered so that LATER entries overlay earlier ones on
     * shared relative paths (matching Maven's own {@code webResources} overlay
     * semantics):
     * <ol>
     *   <li><b>{@code WebFacet.getWebRoots()}</b> when present (requires the
     *       platform's JavaEE plugin) — authoritative because the user has
     *       explicitly configured these roots in Project Structure → Facets.</li>
     *   <li><b>Convention sources</b> from {@link TomcatModuleUtils#findWebRoots}
     *       — Maven {@code src/main/webapp}, Eclipse {@code WebContent},
     *       IntelliJ default {@code web/}, the content root itself when it
     *       hosts {@code WEB-INF/}, and six more conventional paths,
     *       case-insensitively matched.</li>
     *   <li><b>Maven war-plugin {@code <webResources>} extras</b> — extra
     *       source directories declared in the resolved Maven model, in
     *       declaration order. Filtered entries are skipped.</li>
     *   <li><b>Unconventional fallback scan</b> via
     *       {@link TomcatModuleUtils#findUnconventionalWebRoots} — only when
     *       steps 1-3 returned nothing. Catches Gradle {@code webAppDirName}
     *       overrides and arbitrary user-named webapp directories by walking
     *       content roots looking for any folder that hosts {@code WEB-INF/}.
     *       Bounded by depth and a per-name exclusion list.</li>
     * </ol>
     *
     * <p>Returns an empty list when the owning module cannot be determined
     * or no source at all matched. Never {@code null}.
     *
     * <p>Public because the deployment-strategy context-XML builder reuses the
     * same resolver to mount these roots as a read-only overlay (so the
     * overlay covers exactly the directories this pipeline mirrors).
     *
     * <p><b>Must be called inside a read action</b> — touches
     * {@link ModuleManager}, {@code ModuleRootManager}, and {@code FacetManager}.
     */
    @NotNull
    public static List<Path> findWebappSourceRootsForTyped(@NotNull Project project,
                                                           @NotNull Deployment deployment) {
        DeployedClassesSync.ResolutionReport report =
                DeployedClassesSync.resolveTyped(project, deployment);
        String moduleName = report.moduleName();
        if (moduleName == null) return Collections.emptyList();
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) return Collections.emptyList();

        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        // 1. WebFacet roots — authoritative, user-configured. Requires the
        //    platform's JavaEE plugin to be present and the module to have
        //    a Web facet attached.
        for (VirtualFile root : TomcatModuleUtils.findWebFacetRoots(module)) {
            ordered.add(Path.of(root.getPath()));
        }
        // 2. Convention sources (Maven, Eclipse, IntelliJ defaults).
        for (VirtualFile root : TomcatModuleUtils.findWebRoots(module)) {
            ordered.add(Path.of(root.getPath()));
        }
        // 2b. maven-war-plugin <warSourceDirectory> — the build's authoritative
        //     webapp source dir, read from the resolved Maven model. Covers a
        //     custom warSourceDirectory the convention scan above (which looks
        //     for the default src/main/webapp and friends) would otherwise miss.
        Path warSourceDir = findMavenWarSourceDir(module);
        if (warSourceDir != null) {
            ordered.add(warSourceDir);
        }
        // 3. Maven <webResources> extras (additive overlay; filtered skipped).
        ordered.addAll(findMavenExtraWebResources(module));
        // 4. Unconventional fallback ONLY if steps 1-3 found nothing — avoids
        //    scanning the project filesystem (slower) when a cheaper signal
        //    already gave us the answer, and prevents the scan from
        //    duplicating roots discovered via the convention list.
        if (ordered.isEmpty()) {
            for (VirtualFile root : TomcatModuleUtils.findUnconventionalWebRoots(module)) {
                ordered.add(Path.of(root.getPath()));
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * Reads {@code maven-war-plugin}'s {@code <webResources>} configuration
     * from the resolved Maven model and returns every declared
     * {@code <directory>} as an absolute {@link Path}. Skips entries with
     * {@code <filtering>true</filtering>} (see {@link #findWebappSourceRoots}).
     *
     * <p>The configuration is read through {@link MavenModelProvider} (typed,
     * optional Maven dependency); it is present whenever the Maven plugin is —
     * including IntelliJ Community, which bundles Maven support — and absent only
     * on an IDE without it (e.g. a Gradle-only setup or Maven disabled), where
     * this degrades to an empty list.
     */
    private static final String WAR_PLUGIN_WEB_RESOURCES_ELEMENT = "webResources";
    private static final String WAR_PLUGIN_RESOURCE_ELEMENT = "resource";
    private static final String WAR_PLUGIN_DIRECTORY_ELEMENT = "directory";
    private static final String WAR_PLUGIN_FILTERING_ELEMENT = "filtering";
    private static final String WAR_PLUGIN_WAR_SOURCE_DIRECTORY_ELEMENT = "warSourceDirectory";

    @NotNull
    private static List<Path> findMavenExtraWebResources(@NotNull Module module) {
        Element config = mavenWarPluginConfig(module);
        if (config == null) return Collections.emptyList();
        Element webResources = config.getChild(WAR_PLUGIN_WEB_RESOURCES_ELEMENT);
        if (webResources == null) return Collections.emptyList();

        Path moduleRoot = moduleContentRoot(module);
        List<Path> dirs = new ArrayList<>();
        for (Element resource : webResources.getChildren(WAR_PLUGIN_RESOURCE_ELEMENT)) {
            String filtering = resource.getChildText(WAR_PLUGIN_FILTERING_ELEMENT);
            if ("true".equalsIgnoreCase(filtering)) {
                // Maven would token-substitute these. We can't safely mirror
                // raw templates over filtered deployed copies — skip.
                continue;
            }
            Path resolved = resolveAgainstModule(
                    resource.getChildText(WAR_PLUGIN_DIRECTORY_ELEMENT), moduleRoot);
            if (resolved != null && Files.isDirectory(resolved)) {
                dirs.add(resolved);
            }
        }
        return dirs;
    }

    /**
     * Reads the maven-war-plugin's {@code <warSourceDirectory>} — the build's
     * authoritative webapp source directory, which overrides the default
     * {@code src/main/webapp} the convention scan looks for. Returns {@code null}
     * when the Maven model is unavailable, the element is absent, or the
     * directory doesn't exist (the convention / fallback sources then apply).
     * Same degrade-to-absent contract as {@link #findMavenExtraWebResources}.
     */
    @Nullable
    private static Path findMavenWarSourceDir(@NotNull Module module) {
        Element config = mavenWarPluginConfig(module);
        if (config == null) return null;
        Path resolved = resolveAgainstModule(
                config.getChildText(WAR_PLUGIN_WAR_SOURCE_DIRECTORY_ELEMENT), moduleContentRoot(module));
        return resolved != null && Files.isDirectory(resolved) ? resolved : null;
    }

    /**
     * The maven-war-plugin's resolved {@code <configuration>} element via the
     * typed {@link MavenModelProvider}, or {@code null} when the Maven plugin is
     * absent (a Gradle-only IDE or Maven disabled — not Community, which bundles
     * Maven) or the war plugin isn't declared. Shared by the
     * {@code <webResources>} and {@code <warSourceDirectory>} readers.
     */
    @Nullable
    private static Element mavenWarPluginConfig(@NotNull Module module) {
        return MavenModelProvider.warPluginConfiguration(module);
    }

    @Nullable
    private static Path moduleContentRoot(@NotNull Module module) {
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        return roots.length > 0 ? Path.of(roots[0].getPath()) : null;
    }

    /**
     * Resolves a possibly-relative config {@code dir} against {@code moduleRoot};
     * returns {@code null} when {@code dir} is blank or relative with no module
     * root. Existence is checked by the caller.
     */
    @Nullable
    private static Path resolveAgainstModule(@Nullable String dir, @Nullable Path moduleRoot) {
        if (dir == null || dir.isBlank()) return null;
        Path resolved = Path.of(dir);
        if (resolved.isAbsolute()) return resolved;
        return moduleRoot != null ? moduleRoot.resolve(dir) : null;
    }

    /**
     * Walks {@code src} and copies every regular file that's missing in
     * {@code dst} or older / different size than its source, via
     * {@link TreeMirror} with the web-resources policy: subtrees under
     * {@link #SKIP_SUBTREES} are not visited, symlinks (file and directory)
     * are skipped, and DevTomcat's own metadata files are never imported
     * into the deployment.
     *
     * <p>Package-visible for {@code WebResourcesSyncTest}.
     */
    @NotNull
    static TreeMirror.MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst) {
        return TreeMirror.mirrorTree(src, dst, new TreeMirror.Policy(
                "Web resources sync",
                SKIP_SUBTREES,
                // Never import DevTomcat's own metadata (e.g. a legacy
                // .devtomcat-*.manifest an old version left in the source
                // webapp) into the deployment — and never claim it as
                // contributed content.
                f -> f.getFileName().toString().startsWith(".devtomcat-"),
                null,
                // Generic failure stat-ing the destination: skip the file
                // for this run (it stays contributed; the engine debug-logs
                // the skip).
                false));
    }
}
