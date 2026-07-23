package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_JAR;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_CLASSES_PATH;
import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF_LIB_PATH;

/**
 * Per-deployment freshness view: for every packaged module, HOW its bits reach
 * Tomcat and whether the served copy is current or stale — the at-a-glance
 * answer to "my change isn't there".
 *
 * <h2>Delivery vocabulary</h2>
 * <ul>
 *   <li>{@link Delivery#LOOSE_CLASSES} — the module's output is mirrored into
 *       the deployed {@code WEB-INF/classes/} and no {@code WEB-INF/lib} JAR
 *       covers it; the loose copies are what Tomcat serves.</li>
 *   <li>{@link Delivery#JAR_PLUS_OVERLAY} — a deployed {@code WEB-INF/lib} JAR
 *       covers the module AND the class sync overlays newer output into
 *       {@code WEB-INF/classes/} (mirror-covered, or overlay entries recorded
 *       in the sync manifest). Served copy per file: the overlay when present,
 *       else the JAR.</li>
 *   <li>{@link Delivery#JAR_ONLY} — a JAR is deployed but the sync does NOT
 *       cover the module (not a production dependency of the deployment's
 *       module): only a build-tool rebuild refreshes it. The known blind
 *       spot.</li>
 *   <li>{@link Delivery#PACKED_WAR} — WAR deployments: everything ships inside
 *       the WAR; the WAR's mtime is the served state.</li>
 *   <li>{@link Delivery#UNKNOWN} — no project module could be resolved
 *       (external file, missing artifact).</li>
 * </ul>
 *
 * <h2>Freshness</h2>
 * Newest module output vs what Tomcat actually serves for that module.
 * Evidence rules follow {@link DeploymentStaleness}: comparisons short-circuit
 * at the first strictly-newer output file; anything unreadable contributes no
 * evidence — never a false STALE, at most a CURRENT flagged unverified.
 *
 * <p>The classification/comparison core is platform-free (plain paths and
 * maps); {@link #build} is the thin platform-facing composition of the
 * existing seams ({@link DeploymentModuleResolver}, {@link DeployedClassesSync},
 * {@link SyncManifest}) under a read action, with all file walks outside it.
 * PCE propagates unchanged throughout.
 */
public final class DeploymentFreshnessReport {

    /** Sentinel for "no covering JAR" in the served-copy comparison. */
    private static final long NO_JAR = Long.MIN_VALUE;

    /** Row label when no project module backs the deployment. */
    static final String UNRESOLVED_LABEL = "(no project module)";

    private DeploymentFreshnessReport() {}

    /** Overall shape of one deployment. */
    public enum Shape {
        EXPLODED("Exploded directory"),
        PACKED_WAR("Packed WAR"),
        EXTERNAL("External file");

        private final String label;
        Shape(String label) { this.label = label; }
        @NotNull public String getLabel() { return label; }
    }

    /** How a packaged module's bits reach Tomcat. See class javadoc. */
    public enum Delivery { LOOSE_CLASSES, JAR_PLUS_OVERLAY, JAR_ONLY, PACKED_WAR, UNKNOWN }

    /**
     * Freshness of the served copy. {@code staleSinceMillis} (STALE only) is
     * the epoch mtime of the FIRST unserved output file the walk found — the
     * probe short-circuits (existence of a newer file is the question), so this
     * is "a change Tomcat does not serve landed at least this long ago", not
     * necessarily the newest such change. The displayed age is therefore a
     * lower bound on staleness, which is the safe direction to round.
     * {@code verified == false} on CURRENT means some comparison lacked
     * evidence (unreadable file, no serving copy found) — reported honestly,
     * never escalated to STALE.
     */
    public record Freshness(boolean stale, long staleSinceMillis, boolean verified,
                            @Nullable StaleReason reason) {

        @NotNull public static Freshness current() { return new Freshness(false, 0, true, null); }

        @NotNull public static Freshness currentUnverified() { return new Freshness(false, 0, false, null); }

        @NotNull public static Freshness stale(long staleSinceMillis) {
            return stale(staleSinceMillis, StaleReason.OUTPUTS_NEWER);
        }

        @NotNull public static Freshness stale(long staleSinceMillis, @NotNull StaleReason reason) {
            return new Freshness(true, staleSinceMillis, true, reason);
        }
    }

    /**
     * Why a row is stale — the two independently-failable questions behind a
     * packed WAR (and the single one behind loose/overlay delivery):
     * <ul>
     *   <li>{@link #OUTPUTS_NEWER} — the compiled output is newer than the
     *       artifact that carries it: the build itself is behind. Remedy is a
     *       build-tool rebuild (or the class sync, for loose/overlay).</li>
     *   <li>{@link #NOT_REDEPLOYED} — the artifact is current but the copy
     *       Tomcat serves is not it. Remedy is a redeploy, NOT a rebuild.</li>
     * </ul>
     * The distinction matters because a deployed copy's mtime is its
     * <em>copy</em> time, never its content time: "the copy is recent" and
     * "the copy contains your change" are different facts, and only asking
     * both can avoid reporting a stale deployment as current.
     */
    public enum StaleReason { OUTPUTS_NEWER, NOT_REDEPLOYED }

    /** One packaged module's line in the view. */
    public record ModuleRow(@NotNull String moduleName,
                            @NotNull Delivery delivery,
                            @NotNull Freshness freshness) {}

    /** The complete freshness report for one deployment. */
    public record Report(@NotNull String deploymentName,
                         @NotNull Shape shape,
                         @NotNull List<ModuleRow> rows) {}

    // ========================================================================
    // Platform-facing builder — thin composition of the existing seams
    // ========================================================================

    /** {@link #build} over every deployment, in order. */
    @NotNull
    public static List<Report> buildAll(@NotNull Project project,
                                        @NotNull List<Deployment> deployments,
                                        @Nullable Path webappsDir) {
        List<Report> out = new ArrayList<>(deployments.size());
        for (Deployment d : deployments) {
            TomcatProgress.checkCanceled();
            out.add(build(project, d, webappsDir));
        }
        return out;
    }

    /**
     * Report for one deployment against the live project model. Model access
     * (module resolution, output roots, sync coverage) runs under a read
     * action; every file walk runs outside it. Call from a background thread.
     *
     * <p>{@code webappsDir} is the running server's {@code webapps} directory
     * (null when not resolvable): a packed WAR's served state is the COPY in
     * there, never the build output the deployment points at — see
     * {@link #forWar}.
     */
    @NotNull
    public static Report build(@NotNull Project project, @NotNull Deployment deployment,
                               @Nullable Path webappsDir) {
        Shape shape = deployment instanceof ExternalFileDeployment ? Shape.EXTERNAL
                : deployment.isExploded() ? Shape.EXPLODED : Shape.PACKED_WAR;
        String name = deployment.getDisplayName();
        Path resolved = deployment.getResolvedPath();
        if (resolved == null || shape == Shape.EXTERNAL) {
            // External files carry no project module by construction.
            return unresolved(name, shape);
        }

        record ModelView(Map<String, Set<Path>> rootsByModule,
                         Map<String, String> artifactNamesByModule,
                         Set<String> mirrorCovered) {}
        ModelView view = TomcatReadActions.compute(() -> {
            // TreeMap: deterministic module order for stable rows.
            Map<String, Set<Path>> roots = new TreeMap<>();
            Map<String, String> artifactNames = new TreeMap<>();
            for (Module m : collectModulesForFreshness(project, deployment)) {
                roots.put(m.getName(), DeployedClassesSync.moduleOwnOutputPaths(m));
                // The sync's own module→JAR identity; keying on the raw module
                // name would miss every qualified/renamed module's JAR.
                artifactNames.put(m.getName(), DeployedClassesSync.libraryArtifactNameFor(m));
            }
            Set<String> covered = new HashSet<>(roots.keySet());
            if (!roots.isEmpty()) {
                // The sync's own resolution names the packaged modules it does
                // NOT mirror — the JAR_ONLY blind spot, straight from the seam.
                covered.removeAll(DeployedClassesSync.resolveTyped(project, deployment)
                        .uncoveredPackagedModules());
            }
            return new ModelView(roots, artifactNames, covered);
        });
        if (view.rootsByModule().isEmpty()) return unresolved(name, shape);

        if (shape == Shape.PACKED_WAR) {
            return forWar(name, resolved, servedWarPath(deployment, webappsDir),
                    view.rootsByModule());
        }
        Map<String, String> deployedJars = DeployedClassesSync.scanDeployedLibraryJars(resolved);
        Path webInfClasses = resolved.resolve(WEB_INF_CLASSES_PATH);
        Set<String> overlayRecordedJars = new HashSet<>();
        SyncManifest.readJarRecords(DeployedClassesSync.classSyncManifestFor(webInfClasses))
                .forEach((jarRel, coverage) -> {
                    if (!coverage.coveredPaths().isEmpty()) overlayRecordedJars.add(jarRel);
                });
        return forExploded(name, resolved, view.rootsByModule(), view.artifactNamesByModule(),
                view.mirrorCovered(), deployedJars, overlayRecordedJars);
    }

    /**
     * Every module whose bits reach this deployment. {@code resolveAll} widens
     * to all packaged modules for artifact-backed deployments, but returns only
     * the primary module for a module-backed one — so for those the production
     * dependency modules (the JAR/overlay audience the view exists to explain)
     * are added from the sync's own source-root resolution. Read action required.
     */
    @NotNull
    private static Set<Module> collectModulesForFreshness(@NotNull Project project,
                                                          @NotNull Deployment deployment) {
        Set<Module> modules = new java.util.LinkedHashSet<>(
                DeploymentModuleResolver.resolveAll(deployment, project));
        Module primary = DeploymentModuleResolver.resolve(deployment, project);
        if (primary == null) return modules;
        modules.add(primary);
        for (Module dep : DeployedClassesSync.productionDependencyModules(primary)) {
            modules.add(dep);
        }
        return modules;
    }

    /**
     * The WAR copy Tomcat actually serves: {@code webapps/<context>.war}. The
     * deployment's own path is the build output — a rebuilt-but-not-redeployed
     * WAR would read "Current" against itself while Tomcat still serves the
     * previous copy, which is precisely the case this view exists to catch.
     * Null when the webapps directory is unknown (server not running).
     */
    @Nullable
    private static Path servedWarPath(@NotNull Deployment deployment, @Nullable Path webappsDir) {
        if (webappsDir == null) return null;
        return com.dev.idea.plugins.tomcat.utils.TomcatDeploymentPaths.warFile(webappsDir,
                com.dev.idea.plugins.tomcat.utils.ContextPathUtils.resolveContextNameSafe(
                        deployment.getContextPath(), LOG));
    }

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(DeploymentFreshnessReport.class);

    // ========================================================================
    // Platform-free core — plain paths and maps, driven directly by tests
    // ========================================================================

    /**
     * Report for an exploded deployment. {@code outputRootsByModule} maps each
     * packaged module to its own production output roots;
     * {@code mirrorCoveredModules} names the modules the class sync mirrors;
     * {@code deployedLibraryJars} is {@link DeployedClassesSync#scanDeployedLibraryJars};
     * {@code overlayRecordedJarRelPaths} are artifact-root-relative JAR paths
     * with overlay entries recorded in the sync manifest.
     */
    @NotNull
    static Report forExploded(@NotNull String deploymentName,
                              @NotNull Path artifactRoot,
                              @NotNull Map<String, ? extends Collection<Path>> outputRootsByModule,
                              @NotNull Map<String, String> artifactNamesByModule,
                              @NotNull Set<String> mirrorCoveredModules,
                              @NotNull Map<String, String> deployedLibraryJars,
                              @NotNull Set<String> overlayRecordedJarRelPaths) {
        if (outputRootsByModule.isEmpty()) return unresolved(deploymentName, Shape.EXPLODED);
        Path webInfClasses = artifactRoot.resolve(WEB_INF_CLASSES_PATH);
        List<ModuleRow> rows = new ArrayList<>(outputRootsByModule.size());
        for (Map.Entry<String, ? extends Collection<Path>> e : outputRootsByModule.entrySet()) {
            TomcatProgress.checkCanceled();
            String module = e.getKey();
            // Same module→JAR identity the sync matches on (Maven artifactId /
            // Gradle project name / module stem) — the raw module name misses
            // every qualified or renamed module's JAR.
            String artifactName = artifactNamesByModule.getOrDefault(module, module);
            String jarFile = deployedLibraryJars.get(
                    LibraryArtifactNames.libraryArtifactKey(artifactName + EXT_JAR));
            if (jarFile == null) {
                rows.add(new ModuleRow(module, Delivery.LOOSE_CLASSES,
                        servedCopyFreshness(e.getValue(), webInfClasses, NO_JAR)));
                continue;
            }
            Long jarMtime = mtimeOrNull(artifactRoot.resolve(WEB_INF_LIB_PATH).resolve(jarFile));
            boolean overlay = mirrorCoveredModules.contains(module)
                    || overlayRecordedJarRelPaths.contains(WEB_INF_LIB_PATH + "/" + jarFile);
            if (overlay) {
                rows.add(new ModuleRow(module, Delivery.JAR_PLUS_OVERLAY,
                        servedCopyFreshness(e.getValue(), webInfClasses,
                                jarMtime != null ? jarMtime : NO_JAR)));
            } else {
                rows.add(new ModuleRow(module, Delivery.JAR_ONLY,
                        jarMtime == null ? Freshness.currentUnverified()
                                         : againstMtime(jarMtime, e.getValue())));
            }
        }
        return new Report(deploymentName, Shape.EXPLODED, List.copyOf(rows));
    }

    /**
     * Report for a packed-WAR deployment: every module ships inside the WAR, so
     * ONE mtime is the served state for all of them — the mtime of the WAR
     * Tomcat serves from {@code webapps/}, NOT the build-output WAR the
     * deployment points at. Comparing against the build output would report
     * "Current" for a WAR that was rebuilt but never redeployed, while Tomcat
     * still serves the previous copy — the exact confusion this view exists to
     * end. When {@code servedWar} is null (server not running / webapps
     * unknown) the build output is the only available anchor, and every row is
     * flagged unverified rather than claiming a freshness it cannot prove.
     */
    @NotNull
    static Report forWar(@NotNull String deploymentName,
                         @NotNull Path builtWarPath,
                         @Nullable Path servedWarPath,
                         @NotNull Map<String, ? extends Collection<Path>> outputRootsByModule) {
        if (outputRootsByModule.isEmpty()) return unresolved(deploymentName, Shape.PACKED_WAR);
        Long builtMtime = mtimeOrNull(builtWarPath);
        // TWO independent questions — a deployed copy's mtime is its COPY time,
        // so it can only answer "is the served copy the current build", never
        // "does it contain your change". Asking only the second (against the
        // copy's stamp) reports a stale deployment as Current whenever the copy
        // is younger than the outputs it lacks.
        Long servedMtime = servedWarPath == null ? null : mtimeOrNull(servedWarPath);
        boolean deployedIsCurrentBuild = servedMtime != null
                && TomcatProjectUtils.isUpToDateCopy(builtWarPath, servedWarPath);
        List<ModuleRow> rows = new ArrayList<>(outputRootsByModule.size());
        for (Map.Entry<String, ? extends Collection<Path>> e : outputRootsByModule.entrySet()) {
            TomcatProgress.checkCanceled();
            rows.add(new ModuleRow(e.getKey(), Delivery.PACKED_WAR,
                    warFreshness(builtMtime, servedMtime, deployedIsCurrentBuild, e.getValue())));
        }
        return new Report(deploymentName, Shape.PACKED_WAR, List.copyOf(rows));
    }

    /**
     * Q1 — does the BUILD carry the outputs? (the war's own mtime IS its
     * content time, so outputs newer than it are simply not inside it.)
     * Q2 — is the copy Tomcat serves that build? ({@code isUpToDateCopy}: a
     * copy's mtime is copy time, so only comparing it back to the source can
     * answer this.) Q1 losing wins the report — rebuild before redeploy.
     * When only Q2 fails, staleness is dated from the first output the SERVED
     * copy predates (how long Tomcat has been missing the change), falling
     * back to the build time when the divergence is not in the outputs.
     * An unknown served copy is never claimed as verified.
     */
    @NotNull
    private static Freshness warFreshness(@Nullable Long builtMtime,
                                          @Nullable Long servedMtime,
                                          boolean deployedIsCurrentBuild,
                                          @NotNull Collection<Path> outputRoots) {
        if (builtMtime == null) return Freshness.currentUnverified();
        DeploymentStaleness.NewerFile newerThanBuild =
                DeploymentStaleness.findOutputNewerThan(builtMtime, outputRoots);
        if (newerThanBuild != null) {
            return Freshness.stale(newerThanBuild.mtimeMillis(), StaleReason.OUTPUTS_NEWER);
        }
        if (servedMtime == null) return Freshness.currentUnverified();
        if (deployedIsCurrentBuild) return Freshness.current();
        DeploymentStaleness.NewerFile newerThanServed =
                DeploymentStaleness.findOutputNewerThan(servedMtime, outputRoots);
        return Freshness.stale(
                newerThanServed != null ? newerThanServed.mtimeMillis() : builtMtime,
                StaleReason.NOT_REDEPLOYED);
    }

    /** Report for a deployment with no resolvable project module. */
    @NotNull
    static Report unresolved(@NotNull String deploymentName, @NotNull Shape shape) {
        return new Report(deploymentName, shape, List.of(
                new ModuleRow(UNRESOLVED_LABEL, Delivery.UNKNOWN, Freshness.currentUnverified())));
    }

    /** Single-mtime comparison (JAR / WAR served state) via the shared probe. */
    @NotNull
    private static Freshness againstMtime(long servedMtimeMillis,
                                          @NotNull Collection<Path> outputRoots) {
        DeploymentStaleness.NewerFile newer =
                DeploymentStaleness.findOutputNewerThan(servedMtimeMillis, outputRoots);
        return newer != null ? Freshness.stale(newer.mtimeMillis()) : Freshness.current();
    }

    /**
     * Per-file freshness of the deployed {@code WEB-INF/classes} copies against
     * the module's output roots (LOOSE / OVERLAY delivery). For each output
     * file the served copy is the same-relative-path file under
     * {@code webInfClasses} when present, else the covering JAR
     * ({@code jarMtimeMillis}; {@link #NO_JAR} = none). Short-circuits at the
     * first strictly-newer output file; unreadable entries and files with no
     * serving copy contribute no evidence (CURRENT flagged unverified).
     * Cancellation-aware; PCE propagates.
     */
    @NotNull
    static Freshness servedCopyFreshness(@NotNull Collection<Path> outputRoots,
                                         @NotNull Path webInfClasses,
                                         long jarMtimeMillis) {
        boolean[] unverified = new boolean[1];
        Freshness[] stale = new Freshness[1];
        for (Path root : outputRoots) {
            TomcatProgress.checkCanceled();
            if (!Files.isDirectory(root)) continue; // no output = nothing to be stale
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        TomcatProgress.checkCanceled();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        TomcatProgress.checkCanceled();
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        long outMtime = attrs.lastModifiedTime().toMillis();
                        Path served = webInfClasses.resolve(root.relativize(file).toString());
                        long servedMtime;
                        try {
                            servedMtime = Files.getLastModifiedTime(served).toMillis();
                        } catch (IOException absentOrUnreadable) {
                            if (jarMtimeMillis != NO_JAR) {
                                // No overlay copy — the covering JAR serves it.
                                if (outMtime > jarMtimeMillis) {
                                    stale[0] = Freshness.stale(outMtime);
                                    return FileVisitResult.TERMINATE;
                                }
                            } else {
                                // No serving copy found: no evidence, never STALE.
                                unverified[0] = true;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        if (outMtime > servedMtime) {
                            stale[0] = Freshness.stale(outMtime);
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        unverified[0] = true; // unreadable entry: no evidence
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                unverified[0] = true; // unreadable root: no evidence, never STALE
            }
            if (stale[0] != null) return stale[0];
        }
        return unverified[0] ? Freshness.currentUnverified() : Freshness.current();
    }

    @Nullable
    private static Long mtimeOrNull(@NotNull Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    // ========================================================================
    // Display wording — owned by the core so the dialog stays dumb
    // ========================================================================

    /** Human label for a delivery kind. */
    @NotNull
    public static String deliveryLabel(@NotNull Delivery delivery) {
        return switch (delivery) {
            case LOOSE_CLASSES    -> "Loose classes in WEB-INF/classes";
            case JAR_PLUS_OVERLAY -> "WEB-INF/lib JAR + class overlay";
            case JAR_ONLY         -> "WEB-INF/lib JAR only (no overlay)";
            case PACKED_WAR       -> "Packed inside WAR";
            case UNKNOWN          -> "Unknown";
        };
    }

    /** Freshness cell text, with the delivery-appropriate remedy when stale. */
    @NotNull
    public static String freshnessLabel(@NotNull ModuleRow row) {
        return freshnessLabel(row, System.currentTimeMillis());
    }

    /** {@link #freshnessLabel(ModuleRow)} with an injectable clock for tests. */
    @NotNull
    static String freshnessLabel(@NotNull ModuleRow row, long nowMillis) {
        Freshness f = row.freshness();
        if (!f.stale()) {
            return f.verified() ? "Current" : "Current (not fully verified)";
        }
        String age = DeploymentStaleness.describeAge(nowMillis - f.staleSinceMillis());
        return "Stale for " + age + " — " + remedy(row.delivery(), f.reason());
    }

    /** The action that actually refreshes each delivery kind — never a generic one. */
    @NotNull
    static String remedy(@NotNull Delivery delivery) {
        return remedy(delivery, StaleReason.OUTPUTS_NEWER);
    }

    /**
     * {@link #remedy(Delivery)} refined by WHY the row is stale: a build that
     * is current but never reached webapps needs a redeploy, not a rebuild —
     * telling the user to rebuild something already correct sends them in a
     * circle (the exact loop this view exists to end).
     */
    @NotNull
    static String remedy(@NotNull Delivery delivery, @Nullable StaleReason reason) {
        if (reason == StaleReason.NOT_REDEPLOYED) {
            return "redeploy: the built WAR has not been deployed";
        }
        return switch (delivery) {
            // Only a build-tool install refreshes a JAR the sync cannot overlay.
            case JAR_ONLY   -> "rebuild: mvn install / gradle build";
            case PACKED_WAR -> "rebuild: mvn package / gradle war";
            // Loose and overlaid copies are exactly what the class sync writes.
            case LOOSE_CLASSES, JAR_PLUS_OVERLAY -> "run 'Update classes and resources'";
            case UNKNOWN    -> "re-add the deployment so it links to a project module";
        };
    }
}
