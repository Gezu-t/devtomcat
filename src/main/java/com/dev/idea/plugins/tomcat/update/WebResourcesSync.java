package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

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
            "WEB-INF/classes",
            "WEB-INF/lib");

    private WebResourcesSync() {}

    /** Result of mirroring one source webapp root. */
    public record MirrorResult(int copied) {
        public static final MirrorResult EMPTY = new MirrorResult(0);
    }

    /** Aggregate result across all artifacts in a single call. */
    public record SyncReport(int artifactsSynced, int totalCopied, int skipped) {}

    /**
     * Mirrors {@code src/main/webapp/} into the exploded artifact directory
     * for every exploded {@link DeploymentArtifact} in {@code artifacts}.
     * Logs per-artifact progress through {@code logger} so a stale-resource
     * failure tells the user exactly which artifact / source root / target
     * was scanned.
     *
     * @param project    the IntelliJ project (must not be disposed)
     * @param artifacts  configured deployments
     * @param logger     deployment logger for per-artifact status lines
     * @return aggregate sync counts (never null)
     */
    @NotNull
    public static SyncReport syncIfNeeded(@NotNull Project project,
                                          @NotNull List<DeploymentArtifact> artifacts,
                                          @NotNull TomcatDeploymentLogger logger) {
        if (project.isDisposed() || artifacts.isEmpty()) {
            return new SyncReport(0, 0, 0);
        }

        logger.logServerInfo("Web resources sync: scanning " + artifacts.size() + " deployment(s)...");

        int syncedArtifacts = 0;
        int totalCopied = 0;
        int skipped = 0;

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) {
                skipped++;
                continue;
            }
            String name = artifact.getDisplayName();

            if (!artifact.isValid()) {
                logger.logServerInfo("Web resources sync skipped '" + name
                        + "': artifact path missing or invalid (" + artifact.getPath() + ")");
                skipped++;
                continue;
            }
            // WAR artifacts cannot be hot-mirrored — only the build tool can
            // re-pack the archive. Same call-out as class sync.
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) {
                logger.logServerInfo("Web resources sync skipped '" + name
                        + "': type is " + artifact.getType()
                        + " (only exploded deployments can be hot-mirrored)");
                skipped++;
                continue;
            }

            Path artifactRoot = Path.of(artifact.getPath());

            // Resolve every applicable source directory: convention dirs (Maven
            // src/main/webapp, Eclipse WebContent, IntelliJ default web/, etc.),
            // content-root-IS-webapp layouts, AND Maven war-plugin <webResources>
            // extra dirs. Multiple sources are mirrored in order — later sources
            // overlay earlier ones on shared relative paths, matching Maven's
            // own webResources copy semantics.
            List<Path> webappSources;
            try {
                webappSources = TomcatReadActions.compute(() -> findWebappSourceRoots(project, artifact));
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
            for (Path src : webappSources) {
                logger.logServerInfo("Web resources sync: '" + name + "' -> " + src + " -> " + artifactRoot);
                MirrorResult mr = mirrorTree(src, artifactRoot);
                copiedForThisArtifact += mr.copied();
                if (mr.copied() > 0) {
                    logger.logServerInfo("Web resources sync:     " + mr.copied() + " file(s) from " + src);
                }
            }
            if (copiedForThisArtifact > 0) {
                logger.logServerInfo("Web resources sync: " + copiedForThisArtifact
                        + " file(s) refreshed in '" + name + "'");
                syncedArtifacts++;
                totalCopied += copiedForThisArtifact;
            } else {
                logger.logServerInfo("Web resources sync: '" + name
                        + "' already up to date (source files match deployed copies' mtime/size)");
            }
        }

        logger.logServerInfo("Web resources sync: scan complete — " + totalCopied
                + " file(s) refreshed across " + syncedArtifacts + " artifact(s), "
                + skipped + " skipped");
        return new SyncReport(syncedArtifacts, totalCopied, skipped);
    }

    /**
     * Resolves a {@link DeploymentArtifact} to every applicable webapp
     * source directory. The returned list is ordered so that LATER entries
     * overlay earlier ones on shared relative paths (matching Maven's own
     * {@code webResources} overlay semantics):
     * <ol>
     *   <li><b>{@code WebFacet.getWebRoots()}</b> when present (IntelliJ
     *       Ultimate + JavaEE plugin) — authoritative because the user has
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
     * <p><b>Must be called inside a read action</b> — touches
     * {@link ModuleManager}, {@code ModuleRootManager}, and {@code FacetManager}.
     */
    @NotNull
    static List<Path> findWebappSourceRoots(@NotNull Project project,
                                            @NotNull DeploymentArtifact artifact) {
        return findWebappSourceRootsForTyped(project,
                com.dev.idea.plugins.tomcat.model.DeploymentAdapter.toTyped(project, artifact));
    }

    /**
     * Typed entry point. Uses the same {@link DeployedClassesSync#resolveTyped}
     * dispatch as class sync so both pipelines pick the same module for any
     * given deployment.
     */
    @NotNull
    static List<Path> findWebappSourceRootsForTyped(@NotNull Project project,
                                                    @NotNull com.dev.idea.plugins.tomcat.model.Deployment deployment) {
        DeployedClassesSync.ResolutionReport report =
                DeployedClassesSync.resolveTyped(project, deployment);
        String moduleName = report.moduleName();
        if (moduleName == null) return java.util.Collections.emptyList();
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) return java.util.Collections.emptyList();

        java.util.LinkedHashSet<Path> ordered = new java.util.LinkedHashSet<>();
        // 1. WebFacet roots — authoritative, user-configured (Ultimate only).
        for (VirtualFile root : TomcatModuleUtils.findWebFacetRoots(module)) {
            ordered.add(Path.of(root.getPath()));
        }
        // 2. Convention sources (Maven, Eclipse, IntelliJ defaults).
        for (VirtualFile root : TomcatModuleUtils.findWebRoots(module)) {
            ordered.add(Path.of(root.getPath()));
        }
        // 3. Maven <webResources> extras (additive overlay; filtered skipped).
        for (Path extra : findMavenExtraWebResources(module, project)) {
            ordered.add(extra);
        }
        // 4. Unconventional fallback ONLY if steps 1-3 found nothing — avoids
        //    scanning the project filesystem (slower) when a cheaper signal
        //    already gave us the answer, and prevents the scan from
        //    duplicating roots discovered via the convention list.
        if (ordered.isEmpty()) {
            for (VirtualFile root : TomcatModuleUtils.findUnconventionalWebRoots(module)) {
                ordered.add(Path.of(root.getPath()));
            }
        }
        return new java.util.ArrayList<>(ordered);
    }

    /**
     * Reads {@code maven-war-plugin}'s {@code <webResources>} configuration
     * from the resolved Maven model and returns every declared
     * {@code <directory>} as an absolute {@link Path}. Skips entries with
     * {@code <filtering>true</filtering>} (see {@link #findWebappSourceRoots}).
     *
     * <p>Reflective access so the plugin still loads on Community Edition
     * and Gradle-only projects where {@code MavenProjectsManager} is
     * absent — degrades to empty list.
     */
    @NotNull
    private static List<Path> findMavenExtraWebResources(@NotNull Module module,
                                                         @NotNull Project project) {
        Object mavenProject = com.dev.idea.plugins.tomcat.utils.MavenReflection
                .findMavenProject(module, project);
        if (mavenProject == null) return java.util.Collections.emptyList();
        try {
            // MavenProject.findPlugin(groupId, artifactId) → MavenPlugin
            Object warPlugin = mavenProject.getClass()
                    .getMethod("findPlugin", String.class, String.class)
                    .invoke(mavenProject, "org.apache.maven.plugins", "maven-war-plugin");
            if (warPlugin == null) return java.util.Collections.emptyList();

            // MavenPlugin.getConfigurationElement() → org.jdom.Element (or null)
            Object configObj = warPlugin.getClass().getMethod("getConfigurationElement").invoke(warPlugin);
            if (!(configObj instanceof org.jdom.Element config)) return java.util.Collections.emptyList();

            org.jdom.Element webResources = config.getChild("webResources");
            if (webResources == null) return java.util.Collections.emptyList();

            // Module root for resolving any relative <directory> values.
            VirtualFile[] roots = com.intellij.openapi.roots.ModuleRootManager
                    .getInstance(module).getContentRoots();
            Path moduleRoot = roots.length > 0 ? Path.of(roots[0].getPath()) : null;

            List<Path> dirs = new java.util.ArrayList<>();
            for (org.jdom.Element resource : webResources.getChildren("resource")) {
                String filtering = resource.getChildText("filtering");
                if ("true".equalsIgnoreCase(filtering)) {
                    // Maven would token-substitute these. We can't safely mirror
                    // raw templates over filtered deployed copies — skip.
                    continue;
                }
                String dir = resource.getChildText("directory");
                if (dir == null || dir.isBlank()) continue;
                Path resolved = Path.of(dir);
                if (!resolved.isAbsolute() && moduleRoot != null) {
                    resolved = moduleRoot.resolve(dir);
                }
                if (java.nio.file.Files.isDirectory(resolved)) {
                    dirs.add(resolved);
                }
            }
            return dirs;
        } catch (NoClassDefFoundError | Exception e) {
            // Maven plugin not present (Community-edition / Gradle-only project),
            // war-plugin not declared, or unexpected model shape — no extras.
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Walks {@code src} and copies every regular file that's missing in
     * {@code dst} or older / different size than its source. Files under
     * {@link #SKIP_SUBTREES} are not visited. Symlinks (file and directory)
     * are skipped — same reasoning as {@link DeployedClassesSync#mirrorTree}.
     * Per-file IOExceptions are swallowed with a debug log so a single
     * locked file does not abort the whole sync.
     *
     * <p>Package-visible for {@code WebResourcesSyncTest}.
     */
    @NotNull
    static MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst) {
        if (!Files.isDirectory(src)) return MirrorResult.EMPTY;

        // Nesting guard: same hazard as DeployedClassesSync — if dst is
        // inside src (or vice versa), the walker would either loop or
        // overwrite the source. The user pointed somewhere wrong; bail
        // loudly rather than fill the disk.
        Path srcNorm = src.toAbsolutePath().normalize();
        Path dstNorm = dst.toAbsolutePath().normalize();
        if (dstNorm.startsWith(srcNorm) || srcNorm.startsWith(dstNorm)) {
            LOG.warn("Web resources sync: refusing nested src/dst paths: src=" + srcNorm + " dst=" + dstNorm);
            return MirrorResult.EMPTY;
        }

        final int[] copied = {0};
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        LOG.debug("Web resources sync: skipping symlinked directory " + dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Skip subtrees that are owned by other pipelines.
                    Path rel = src.relativize(dir);
                    String relPath = rel.toString().replace('\\', '/');
                    for (String skip : SKIP_SUBTREES) {
                        if (relPath.equals(skip) || relPath.startsWith(skip + "/")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (attrs.isSymbolicLink()) {
                            LOG.debug("Web resources sync: skipping symlink " + file);
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = src.relativize(file);
                        Path target = dst.resolve(rel.toString());

                        if (shouldCopy(file, attrs, target)) {
                            Path parent = target.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            try {
                                Files.copy(file, target,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.COPY_ATTRIBUTES);
                                copied[0]++;
                            } catch (java.nio.file.NoSuchFileException vanished) {
                                LOG.debug("Web resources sync: source vanished during copy: " + file);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.debug("Web resources sync: skipped " + file + " (" + e.getMessage() + ")");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.debug("Web resources sync: cannot visit " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("Web resources sync: walk failed for " + src + " (" + e.getMessage() + ")");
        }
        return new MirrorResult(copied[0]);
    }

    /**
     * Copy decision: copy when the destination is missing, the source
     * mtime is strictly newer, OR the sizes differ. Mirrors the class-sync
     * gate's logic so the two stay consistent (especially the size
     * tie-breaker which catches fast successive edits that land within
     * filesystem mtime resolution).
     */
    private static boolean shouldCopy(@NotNull Path src,
                                      @NotNull BasicFileAttributes srcAttrs,
                                      @NotNull Path target) throws IOException {
        // Single stat: try to read dst attrs; NoSuchFile => copy.
        BasicFileAttributes dstAttrs;
        try {
            dstAttrs = Files.readAttributes(target, BasicFileAttributes.class);
        } catch (java.nio.file.NoSuchFileException missing) {
            return true;
        }
        long srcMillis = srcAttrs.lastModifiedTime().toMillis();
        long dstMillis = dstAttrs.lastModifiedTime().toMillis();
        if (srcMillis > dstMillis) return true;
        return srcAttrs.size() != dstAttrs.size();
    }
}
