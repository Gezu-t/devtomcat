package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.util.SystemInfo;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>IntelliJ Ultimate's bundled Tomcat plugin hides this step internally;
 * DevTomcat now does the same.
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
 *       from the last {@code mvn package} run. See
 *       {@link #collectProductionRoots} for the rationale.</li>
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

    /** Standard webapp class output sub-path. Tomcat mandates this layout. */
    private static final String WEB_INF_CLASSES = "WEB-INF/classes";

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
     *
     * @param project    the IntelliJ project
     * @param artifacts  configured deployments (typically
     *                   {@code TomcatRunConfiguration.getDeployedArtifacts()})
     * @param logger     deployment logger for per-artifact status lines
     * @return a {@link SyncReport} with copy counts (never {@code null})
     */
    @NotNull
    public static SyncReport syncIfNeeded(@NotNull Project project,
                                          @NotNull List<DeploymentArtifact> artifacts,
                                          @NotNull TomcatDeploymentLogger logger) {
        if (project.isDisposed() || artifacts.isEmpty()) {
            return new SyncReport(0, 0, 0);
        }

        // Header line so the user can SEE the feature engaged at all. Without
        // this, a project where every artifact gets skipped looks identical
        // to a project where the sync was never wired in — and that's exactly
        // the "is anything even happening?" failure mode the diagnostics here
        // are designed to surface.
        logger.logServerInfo("Class sync: scanning " + artifacts.size() + " deployment(s)...");

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
                logger.logServerInfo("Class sync skipped '" + name
                        + "': artifact path missing or invalid (" + artifact.getPath() + ")");
                skipped++;
                continue;
            }
            // WAR files cannot be hot-mirrored — repackaging is a build-tool concern.
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) {
                logger.logServerInfo("Class sync skipped '" + name
                        + "': type is " + artifact.getType()
                        + " (only exploded deployments can be hot-mirrored; run mvn package or gradle war for WAR types)");
                skipped++;
                continue;
            }

            Path artifactRoot = Path.of(artifact.getPath());
            Path webInfClasses = artifactRoot.resolve(WEB_INF_CLASSES);
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
                        resolveModuleOutputRootsVerbose(project, artifact));
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
            for (Path src : resolution.sourceRoots()) {
                MirrorResult mr = mirrorTree(src, webInfClasses);
                copiedForThisArtifact += mr.copied();
                brokenForThisArtifact += mr.brokenSkipped();
                if (mr.copied() > 0) {
                    logger.logServerInfo("Class sync:     " + mr.copied() + " file(s) from " + src);
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
            if (copiedForThisArtifact > 0) {
                logger.logServerInfo("Class sync: " + copiedForThisArtifact +
                        " file(s) refreshed in '" + name + "'");
                syncedArtifacts++;
                totalCopied += copiedForThisArtifact;
            } else if (brokenForThisArtifact == 0) {
                logger.logServerInfo("Class sync: '" + name
                        + "' already up to date (source files match deployed WEB-INF/classes mtime/size)");
            }
        }

        logger.logServerInfo("Class sync: scan complete — " + totalCopied
                + " file(s) refreshed across " + syncedArtifacts + " artifact(s), "
                + skipped + " skipped");
        return new SyncReport(syncedArtifacts, totalCopied, skipped);
    }

    /**
     * Verbose form of {@link #resolveModuleOutputRoots} that also carries
     * the strategy name and a diagnostic message when no module matched.
     *
     * <p><b>Must be called inside a read action.</b>
     */
    @NotNull
    static ResolutionReport resolveModuleOutputRootsVerbose(@NotNull Project project,
                                                            @NotNull DeploymentArtifact artifact) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);

        // Strategy 1: direct name match.
        String name = artifact.getName();
        String baseName = name.replaceAll(":war.*$", "")
                              .replaceAll("\\.war$", "")
                              .replaceAll("\\s*\\(.*\\)$", "")
                              .trim();
        if (!baseName.isEmpty()) {
            Module direct = moduleManager.findModuleByName(baseName);
            if (direct != null) {
                return new ResolutionReport(direct.getName(),
                        "name-match: '" + baseName + "'",
                        collectProductionRoots(direct), null);
            }
            // Maven's <finalName> defaults to ${artifactId}-${version}, so the
            // exploded artifact often carries a trailing version suffix the
            // module name doesn't (e.g. artifact 'foo-web-6.0.0' for module
            // 'foo-web'). Retry after stripping a Maven-style version tail.
            String stripped = stripMavenVersionSuffix(baseName);
            if (stripped != null && !stripped.isEmpty()) {
                Module versionStripped = moduleManager.findModuleByName(stripped);
                if (versionStripped != null) {
                    return new ResolutionReport(versionStripped.getName(),
                            "name-match (version-stripped '" + baseName + "' → '" + stripped + "')",
                            collectProductionRoots(versionStripped), null);
                }
            }
        }

        // Strategy 2: content-root containment, longest-match wins.
        String deploymentPath = artifact.getPath();
        if (!deploymentPath.isEmpty()) {
            Module bestMatch = null;
            int bestMatchLength = -1;
            for (Module m : moduleManager.getModules()) {
                for (VirtualFile contentRoot : ModuleRootManager.getInstance(m).getContentRoots()) {
                    String crPath = contentRoot.getPath();
                    if (pathContains(crPath, deploymentPath) && crPath.length() > bestMatchLength) {
                        bestMatchLength = crPath.length();
                        bestMatch = m;
                    }
                }
            }
            if (bestMatch != null) {
                return new ResolutionReport(bestMatch.getName(),
                        "content-root: '" + bestMatch.getName() + "' contains deployment path",
                        collectProductionRoots(bestMatch), null);
            }
        }

        // Strategy 3: single web module fallback.
        Module loneWeb = null;
        int webCount = 0;
        List<String> webModuleNames = new ArrayList<>();
        for (Module m : moduleManager.getModules()) {
            if (TomcatModuleUtils.isWebModule(m)) {
                loneWeb = m;
                webCount++;
                webModuleNames.add(m.getName());
            }
        }
        if (webCount == 1 && loneWeb != null) {
            return new ResolutionReport(loneWeb.getName(),
                    "single-web-module-fallback",
                    collectProductionRoots(loneWeb), null);
        }

        String diag = "no name match for '" + baseName + "', no content-root contains '"
                + deploymentPath + "', and "
                + (webCount == 0
                    ? "no web modules detected in the project"
                    : "multiple web modules present (" + webModuleNames + ") — set the artifact"
                            + " name to match one of these, or place the deployment path inside that"
                            + " module's content root");
        return new ResolutionReport(null, "no-match", List.of(), diag);
    }

    /**
     * Verbose-resolution result. {@code moduleName} is {@code null} only when
     * no strategy matched; in that case {@code diagnostic} carries the
     * explanation for the user-facing log line.
     */
    record ResolutionReport(@Nullable String moduleName,
                            @NotNull String strategy,
                            @NotNull List<Path> sourceRoots,
                            @Nullable String diagnostic) {}

    // ── Module-resolution helpers (package-private for unit tests) ──────────

    /**
     * Matches a Maven-style version tail at the end of an artifact name:
     * a hyphen, a number, optional dot-separated numbers, and optionally a
     * qualifier like {@code -SNAPSHOT}, {@code -RC1}, or {@code .Final}.
     * Examples it matches: {@code -1}, {@code -1.0}, {@code -6.0.0},
     * {@code -1.0-SNAPSHOT}, {@code -2.3.4-RC1}, {@code -3.0.RELEASE}.
     */
    private static final Pattern MAVEN_VERSION_SUFFIX =
            Pattern.compile("-\\d+(?:\\.\\d+)*(?:[-.][A-Za-z0-9]+)*$");

    /**
     * If {@code name} ends with a Maven-style version tail, returns the
     * name with that tail removed; otherwise returns {@code null}.
     */
    @Nullable
    static String stripMavenVersionSuffix(@NotNull String name) {
        Matcher m = MAVEN_VERSION_SUFFIX.matcher(name);
        return m.find() ? name.substring(0, m.start()) : null;
    }

    /**
     * Returns {@code true} when {@code deploymentPath} lives inside (or equals)
     * {@code contentRoot}. Normalises path separators ({@code \} → {@code /})
     * and respects the host filesystem's case sensitivity so a Windows path
     * stored as {@code C:\Users\…} matches an IntelliJ content root reported
     * as {@code C:/Users/…}.
     */
    static boolean pathContains(@NotNull String contentRoot, @NotNull String deploymentPath) {
        return pathContains(contentRoot, deploymentPath, !SystemInfo.isFileSystemCaseSensitive);
    }

    /** Test seam: caller controls case sensitivity. */
    static boolean pathContains(@NotNull String contentRoot,
                                @NotNull String deploymentPath,
                                boolean caseInsensitive) {
        String cr = normalizePath(contentRoot, caseInsensitive);
        String dp = normalizePath(deploymentPath, caseInsensitive);
        if (cr.isEmpty() || dp.isEmpty()) return false;
        if (!dp.startsWith(cr)) return false;
        // Boundary check: forbid '/foo/bar' from matching content root '/foo/ba'.
        return dp.length() == cr.length() || dp.charAt(cr.length()) == '/';
    }

    private static String normalizePath(String path, boolean caseInsensitive) {
        String s = path.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return caseInsensitive ? s.toLowerCase(Locale.ROOT) : s;
    }

    /**
     * Collects production class roots for the resolved web module
     * <strong>and every dependency module it transitively pulls in</strong>.
     *
     * <p>Including dependency modules was the missing piece in the first
     * cut: a multi-module Maven project where the web module depends on a
     * {@code shared} library has {@code shared}'s compiled classes in two
     * places — {@code shared/target/classes/} (fresh after Make) and
     * inside {@code WEB-INF/lib/shared-X.Y.jar} (stale until the next
     * {@code mvn package}). Mirroring only the web module's own output
     * never refreshes the shared classes; Tomcat's classloader then loads
     * the stale ones from the JAR, and the user sees old behaviour after
     * a restart. By bringing in dependency-module roots, the fresh
     * {@code shared/target/classes/Foo.class} lands in
     * {@code web/target/<war>/WEB-INF/classes/} and wins over the JAR's
     * stale {@code Foo.class} via Tomcat's standard classloader precedence
     * ({@code WEB-INF/classes/} is searched before {@code WEB-INF/lib/*.jar}).
     *
     * <p>No safety hazard: Java forbids two compile units defining the same
     * fully-qualified class, so a class can only appear in at most one
     * module's output — the "duplicate-class confusion" intuition doesn't
     * apply. The mtime/size gate in {@link #shouldCopy} keeps repeated
     * restarts cheap even on large dep graphs (~50 µs per stat on SSD).
     *
     * <p>Excluded: SDK roots (JRE classes mustn't go into the webapp) and
     * library JARs (those stay in {@code WEB-INF/lib/} via the build tool).
     *
     * <p><b>Must be called inside a read action.</b>
     */
    @NotNull
    private static List<Path> collectProductionRoots(@NotNull Module module) {
        List<Path> result = new ArrayList<>();
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
                    result.add(p);
                }
            } catch (Exception e) {
                LOG.debug("Class sync: ignored non-filesystem output root " + root);
            }
        }
        return result;
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
        return resolveModuleOutputRootsVerbose(project, artifact).sourceRoots();
    }

    /** Result of mirroring one source root. */
    record MirrorResult(int copied, int brokenSkipped) {
        static final MirrorResult EMPTY = new MirrorResult(0, 0);
    }

    /**
     * Walks {@code src} and copies every file that is missing in {@code dst}
     * or older than its {@code src} counterpart. Returns counts for files
     * copied and files skipped because they were detected as ECJ
     * "compile-with-errors" stubs (see {@link #isBrokenEcjClass}).
     *
     * <p>The walker swallows per-file IOExceptions to avoid aborting a sync
     * mid-way when one file is briefly locked (Windows file-handles, IDE
     * indexing). Each failure is debug-logged with the source path so the
     * issue is visible in {@code idea.log} without polluting the run
     * console.
     */
    // Package-visible so DeployedClassesSyncScenariosTest can drive end-to-end
    // mirror behaviour without standing up a Project/ModuleManager fixture.
    static MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst) {
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
        try {
            // Default FileVisitOption set = do NOT follow symlinks. We don't
            // pass FOLLOW_LINKS: a symlinked subdirectory could point outside
            // the project, into the destination tree, or form a loop. Java's
            // walker honours this and just visits the link as a regular file
            // (which we then skip via the explicit isSymbolicLink check below
            // so it never even gets to the copy path).
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
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
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        // Skip symlinks. The mirror's contract is "copy
                        // source-of-truth class files"; a symlink doesn't
                        // qualify, and following one could land outside the
                        // project's compile output.
                        if (attrs.isSymbolicLink()) {
                            LOG.debug("Class sync: skipping symlink " + file);
                            return FileVisitResult.CONTINUE;
                        }

                        Path rel = src.relativize(file);
                        Path target = dst.resolve(rel.toString());

                        // CRITICAL gate: if the source is a broken ECJ
                        // "compile-with-errors" class file, refuse to copy.
                        // Overwriting a working deployed copy with a stub
                        // that throws
                        //   java.lang.Error("Unresolved compilation problems")
                        // at class init time would fail Tomcat's webapp startup
                        // with no obvious connection to the IDE's compile state.
                        // Leave the working copy in place and surface the count
                        // via the per-artifact summary in syncIfNeeded so the
                        // user knows what happened.
                        if (isBrokenEcjClass(file)) {
                            brokenSkipped[0]++;
                            LOG.warn("Class sync: refusing to copy ECJ broken-class stub: "
                                    + file);
                            return FileVisitResult.CONTINUE;
                        }

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
                                // The source file disappeared between
                                // visitFile and copy — common when the IDE
                                // re-compiles concurrently (Make replaces
                                // .class atomically). Don't count as copy,
                                // don't fail the walk; the next sync picks
                                // it up. Debug-level only.
                                LOG.debug("Class sync: source vanished during copy: " + file);
                            }
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
        return new MirrorResult(copied[0], brokenSkipped[0]);
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
        if (!fileName.endsWith(".class")) return false;

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
        return indexOf(bytes, ECJ_ERROR_MARKER) >= 0;
    }

    /**
     * Naive byte-array substring search. Adequate for our needs — needle is
     * 31 bytes and class files are small enough that even a quadratic-worst-
     * case scan completes in microseconds. Avoids pulling in a regex engine
     * or KMP just for a single short pattern.
     */
    private static int indexOf(@NotNull byte[] haystack, @NotNull byte[] needle) {
        if (needle.length == 0) return 0;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
     *       {@code dst.mtime == src.mtime} via {@code COPY_ATTRIBUTES}, then
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
     * costs one extra {@code Files.size()} stat per file but only when
     * mtimes match, which is rare in practice.
     */
    static boolean shouldCopy(@NotNull Path source,
                              @NotNull BasicFileAttributes sourceAttrs,
                              @NotNull Path target) {
        if (!Files.exists(target)) return true;
        try {
            FileTime srcTime = sourceAttrs.lastModifiedTime();
            FileTime dstTime = Files.getLastModifiedTime(target);
            if (srcTime.toMillis() > dstTime.toMillis()) return true;
            // Size-tiebreaker for the equal-or-older mtime case. We don't
            // care about a "src is older than dst" scenario — that would
            // mean the user reverted a file, and overwriting with the older
            // version is the right behaviour anyway. So: if mtimes match
            // exactly and sizes differ, copy. Sizes also differing while
            // dst is strictly newer is unusual (manual edit of the deployed
            // file?) and overwriting from source still matches user intent
            // (sync source-of-truth back to deploy dir).
            return sourceAttrs.size() != Files.size(target);
        } catch (IOException e) {
            // If we can't read the destination's mtime/size, prefer to
            // copy — safer to overwrite than to leave stale code in place.
            return true;
        }
    }

    // -------------------------------------------------------------------
    // Test seam — extracted helpers visible to the package-private tests.
    // -------------------------------------------------------------------

    /** Visible for tests: deterministic deployment-name → module-name strip. */
    @NotNull
    static String testStripArtifactSuffix(@NotNull String artifactName) {
        return Objects.requireNonNull(artifactName)
                .replaceAll(":war.*$", "")
                .replaceAll("\\.war$", "")
                .replaceAll("\\s*\\(.*\\)$", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
