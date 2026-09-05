package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.utils.LibraryArtifactNames;
import com.dev.idea.plugins.tomcat.utils.MavenModelProvider;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

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
 *       considered. Destination trees outside {@code WEB-INF/classes/}
 *       (e.g. {@code WEB-INF/lib/*.jar}, webapp resources) are never
 *       touched.</li>
 *   <li>Mtime gate: a file is copied only when the source is strictly newer
 *       than the destination (or the destination is absent). This keeps
 *       repeated restarts cheap and avoids touching files that match.</li>
 *   <li>Stale reconcile ({@link SyncManifest#reconcile}): a class removed from
 *       source leaves a {@code .class} that would otherwise stay loadable, so it
 *       is cleaned — but ONLY files this sync itself wrote on a prior run (tracked
 *       in a per-deployment manifest). A class the artifact build legitimately
 *       deploys from a root the mirror doesn't cover is never in the manifest and
 *       is never deleted, so reconciliation cannot strip a live class
 *       ({@code ClassNotFoundException}). Deferred entirely for an artifact when no
 *       source root contributed or any root's walk was incomplete.</li>
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
    public record SyncReport(int artifactsSynced, int filesCopied, int artifactsSkipped,
                             @NotNull Set<String> changedArtifacts) {
        public SyncReport(int artifactsSynced, int filesCopied, int artifactsSkipped) {
            this(artifactsSynced, filesCopied, artifactsSkipped, Set.of());
        }
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
        Set<String> changedArtifacts = new LinkedHashSet<>();
        int totalCopied = 0;
        int skipped = 0;
        List<SyncSkip> skipReports = new ArrayList<>();

        for (Deployment deployment : deployments) {
            String name = deployment.getDisplayName();
            TomcatProgress.setDetail("Syncing classes: " + name);
            long artifactStart = System.nanoTime();
            Path artifactRoot = deployment.getResolvedPath();

            if (artifactRoot == null || !deployment.isValid()) {
                logger.logServerInfo("Class sync skipped '" + name
                        + "': deployment path missing or invalid"
                        + (artifactRoot != null ? " (" + artifactRoot + ")" : ""));
                skipReports.add(new SyncSkip(name, "invalid-path",
                        "deployment path missing or invalid — build the project or re-add the"
                        + " deployment in the Deployment tab"));
                skipped++;
                continue;
            }
            // WAR files cannot be hot-mirrored — repackaging is a build-tool concern.
            if (!deployment.isExploded()) {
                logger.logServerInfo("Class sync skipped '" + name
                        + "': type is war"
                        + " (only exploded deployments can be hot-mirrored; run mvn package or gradle war for WAR types)");
                skipReports.add(new SyncSkip(name, "war-type",
                        "packed WAR — only exploded deployments hot-reload; switch to the"
                        + " exploded output in the Deployment tab, or rebuild with"
                        + " 'mvn package' / 'gradle war' and Redeploy"));
                skipped++;
                continue;
            }

            // Never write into the user's source tree. An exploded deployment
            // whose docBase is a content directory (e.g. src/main/webapp) would
            // have compiled classes mirrored — and reconcile-deleted — under it.
            // Refuse and point the user at the build output instead. Build
            // outputs (target/, build/, out/) are excluded from content, so this
            // fires only for the genuinely unsafe case.
            if (Boolean.TRUE.equals(TomcatReadActions.compute(
                    () -> DeploymentSafety.isInsideProjectContent(project, artifactRoot)))) {
                logger.logServerWarning("Class sync skipped '" + name
                        + "': deployment path is inside the project source tree (" + artifactRoot + "). "
                        + "DevTomcat will not write compiled classes into your sources. "
                        + "Point this deployment at the exploded build output instead "
                        + "(e.g. target/<finalName> for Maven, the exploded war output for Gradle).");
                skipReports.add(new SyncSkip(name, "source-tree",
                        "deployment path is inside the project source tree — point it at the"
                        + " exploded build output (target/<finalName> for Maven) instead"));
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
                skipReports.add(new SyncSkip(name, "webinf-unwritable",
                        "cannot create WEB-INF/classes under the deployment — check the"
                        + " directory's permissions"));
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
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                // User cancelled the launch-prep / update indicator: the read
                // action's OrderEnumerator/ModuleRootManager traversal hit
                // ProgressManager.checkCanceled(). Must propagate BEFORE the
                // generic handler below, or Cancel is mislogged as a resolution
                // failure and the loop continues as if nothing happened.
                throw pce;
            } catch (Throwable t) {
                LOG.debug("Could not resolve module output for '" + name + "': " + t.getMessage());
                logger.logServerWarning("Class sync skipped '" + name
                        + "': module resolution threw (" + t.getMessage() + ")");
                skipReports.add(new SyncSkip(name, "resolution-error",
                        "module resolution failed — see the run console for the error"));
                skipped++;
                continue;
            }

            if (resolution.moduleName() == null) {
                logger.logServerWarning("Class sync skipped '" + name
                        + "': could not resolve owning module — " + resolution.diagnostic());
                // External-path deployments already get their own dedicated
                // balloon (with the reclaim offer when fixable) — don't repeat
                // them here with a weaker remedy.
                if (!(deployment instanceof ExternalFileDeployment)) {
                    skipReports.add(new SyncSkip(name, "no-module",
                            "no owning module — re-add the deployment via the Deployment tab"
                            + " so it links to a project module"));
                }
                skipped++;
                continue;
            }
            if (resolution.sourceRoots().isEmpty()) {
                logger.logServerWarning("Class sync skipped '" + name
                        + "': module '" + resolution.moduleName()
                        + "' resolved but no production class output found (Make may not have run yet,"
                        + " or the module has no compilation output — check Build > Build Project first)");
                skipReports.add(new SyncSkip(name, "no-compile-output",
                        "module '" + resolution.moduleName() + "' has no production compile output"
                        + " yet — run Build > Build Project first"));
                skipped++;
                continue;
            }

            // Log what we're about to do so a stale-classes failure points at
            // either the source paths (Make didn't write here) or the target
            // (Tomcat's not serving from here).
            logger.logServerInfo("Class sync: '" + name + "' -> module '" + resolution.moduleName()
                    + "' (" + resolution.strategy() + "), "
                    + resolution.sourceRoots().size() + " source root(s) -> " + webInfClasses);

            // The artifact packages module(s) the incremental sync does NOT cover
            // (not a production dependency of the resolved module). Their classes
            // reach the served WEB-INF/classes only via a full artifact build —
            // so a missing class from one of them is a "Rebuild Project" case, not
            // a sync bug. Surface it instead of leaving a silent ClassNotFound.
            if (!resolution.uncoveredPackagedModules().isEmpty()) {
                logger.logServerWarning("Class sync: '" + name + "' also packages module(s) "
                        + String.join(", ", resolution.uncoveredPackagedModules())
                        + " that are not a production dependency of '" + resolution.moduleName()
                        + "'. The incremental sync does not mirror them — their classes are"
                        + " deployed only by a full artifact build. If a class from them is"
                        + " missing at startup, run Build > Rebuild Project.");

                // An uncovered module deployed as a WEB-INF/lib JAR is invisible
                // to the overlay machinery entirely — when its compiled output
                // outruns the deployed JAR, Tomcat serves old code with zero
                // signal. Detect and say so explicitly (the invisible failure
                // behind "I did a clean install three times").
                // Two maps, both keyed by module name: the output roots to probe
                // and the module's JAR identity. The identity MUST come from
                // libraryArtifactNameFor — a raw module name does not match the
                // deployed JAR for Gradle subprojects ("app.sub.main" vs
                // sub-1.0.jar) or any module whose IDE name differs from its
                // artifactId, and the mismatch silently disables this warning.
                record UncoveredView(Map<String, Set<Path>> roots, Map<String, String> artifactNames) {}
                UncoveredView uncovered = TomcatReadActions.compute(() -> {
                    // TreeMap: deterministic order for stable messages.
                    Map<String, Set<Path>> byModule = new TreeMap<>();
                    Map<String, String> identities = new HashMap<>();
                    ModuleManager mm = ModuleManager.getInstance(project);
                    for (String moduleName : resolution.uncoveredPackagedModules()) {
                        Module m = mm.findModuleByName(moduleName);
                        if (m == null) continue;
                        byModule.put(moduleName, moduleOwnOutputPaths(m));
                        identities.put(moduleName, libraryArtifactNameFor(m));
                    }
                    return new UncoveredView(byModule, identities);
                });
                warnOutdatedUncoveredJars(name,
                        findOutdatedUncoveredJars(uncovered.roots(), uncovered.artifactNames(),
                                scanDeployedLibraryJars(artifactRoot), artifactRoot),
                        logger, SessionNotificationGate.INSTANCE,
                        project.getLocationHash() + "|" + name,
                        (title, content) -> TomcatNotifier.warning(project, title, content));
            }

            ArtifactSyncOutcome outcome = syncArtifactTree(
                    name, artifactRoot, webInfClasses, resolution.sourceRoots(), logger);
            if (outcome.deployedTreeChanged()) changedArtifacts.add(name);
            long artifactMs = (System.nanoTime() - artifactStart) / 1_000_000;
            if (outcome.copied() > 0) {
                logger.logServerInfo("Class sync: " + outcome.copied() +
                        " file(s) refreshed in '" + name + "' (" + outcome.contributedCount()
                        + " source path(s) scanned, " + artifactMs + " ms)");
                syncedArtifacts++;
                totalCopied += outcome.copied();
            } else if (outcome.brokenSkipped() == 0) {
                logger.logServerInfo("Class sync: '" + name
                        + "' already up to date (source files match deployed WEB-INF/classes mtime/size; "
                        + outcome.contributedCount() + " source path(s) scanned, " + artifactMs + " ms)");
            }
        }

        String passSummary = "Class sync: scan complete — " + totalCopied
                + " file(s) refreshed across " + syncedArtifacts + " artifact(s), "
                + skipped + " skipped (" + (System.nanoTime() - passStart) / 1_000_000 + " ms)";
        LOG.info(passSummary);
        logger.logServerInfo(passSummary);
        warnSkippedDeployments(skipReports, SessionNotificationGate.INSTANCE,
                String.valueOf(project.getLocationHash()),
                (title, content) -> TomcatNotifier.warning(project, title, content));
        return new SyncReport(syncedArtifacts, totalCopied, skipped, changedArtifacts);
    }

    /**
     * One skipped deployment in a sync pass: a stable reason key (for the
     * session gate) plus the human reason-with-remedy line the balloon shows.
     * The console already logged the full diagnostic at the skip site.
     */
    record SyncSkip(@NotNull String deploymentName, @NotNull String reasonKey,
                    @NotNull String remedy) {}

    /**
     * Loud-skip notification: balloon once per (project, deployment+reason set)
     * per IDE session listing every skipped deployment WITH its remedy —
     * console lines scroll away; a hot reload that silently does nothing is
     * this plugin's worst failure mode. A changed skip set re-notifies; a
     * resolved one goes quiet. Collaborators injected so tests pin the wiring.
     */
    static void warnSkippedDeployments(@NotNull List<SyncSkip> skips,
                                       @NotNull SessionNotificationGate gate,
                                       @NotNull String scopeId,
                                       @NotNull BiConsumer<String, String> balloon) {
        if (skips.isEmpty()) return;
        Set<String> key = new HashSet<>();
        StringBuilder lines = new StringBuilder();
        for (SyncSkip s : skips) {
            if (lines.length() > 0) lines.append("\n");
            lines.append("• ").append(s.deploymentName()).append(": ").append(s.remedy());
            key.add(s.deploymentName() + "|" + s.reasonKey());
        }
        if (!gate.shouldNotify("sync-skips|" + scopeId, key)) return;
        balloon.accept(skips.size() == 1
                        ? "Hot reload is off for a deployment"
                        : "Hot reload is off for " + skips.size() + " deployments",
                lines.toString());
    }

    /**
     * Per-artifact outcome of {@link #syncArtifactTree}: files copied, ECJ
     * broken-stub copies refused, and the number of source paths this pass
     * claimed (for the summary log lines).
     */
    /** {@code deployedTreeChanged}: a file was copied, an orphan removed or a stale overlay dropped — the context must reload to see it. */
    record ArtifactSyncOutcome(int copied, int brokenSkipped, int contributedCount, boolean deployedTreeChanged) {}

    /**
     * The complete per-artifact tree-sync sequence for one exploded deployment,
     * exactly as {@link #syncDeployments} runs it after module resolution:
     * deployed {@code WEB-INF/lib} scan, stale-overlay drop pass, covering-JAR-
     * floored mirror per source root with JAR-coverage recording, then the
     * manifest reconcile (or stamp refresh when a walk failed). Pure file I/O,
     * package-visible: tests drive the production sequence end to end without a
     * Project/ModuleManager fixture, so reordering or dropping a pass here is
     * test-visible.
     */
    @NotNull
    static ArtifactSyncOutcome syncArtifactTree(@NotNull String name,
                                                @NotNull Path artifactRoot,
                                                @NotNull Path webInfClasses,
                                                @NotNull List<SourceRoot> sourceRoots,
                                                @NotNull TomcatDeploymentLogger logger) {
        int copiedForThisArtifact = 0;
        int brokenForThisArtifact = 0;
        // Library JARs actually packaged into this deployment's WEB-INF/lib/.
        // A dependency module's resource policy is decided against this:
        // jarred dependency → .class-only (its resources come from the JAR);
        // dependency NOT packaged here → full content (so its resources
        // still reach Tomcat). Scanned once per artifact off the model.
        Map<String, String> deployedLibraryJars = scanDeployedLibraryJars(artifactRoot);
        Set<String> deployedLibraryKeys = deployedLibraryJars.keySet();

        // Overlay-staleness pass BEFORE mirroring: overlay classes mirrored
        // while an older WEB-INF/lib JAR covered their dependency shadow a
        // build-tool-rebuilt JAR (WEB-INF/classes loads first). Drop them
        // (stamp-verified, per the manifest's covering-JAR records) so the
        // newer JAR serves; the mirror below re-creates an overlay entry
        // only where the IDE output is newer than the JAR again.
        Path syncManifest = classSyncManifestFor(webInfClasses);
        int overlaysDropped = SyncManifest.dropStaleJarOverlays(
                webInfClasses, syncManifest, artifactRoot);
        if (overlaysDropped > 0) {
            logger.logServerInfo("Class sync: removed " + overlaysDropped
                    + " stale overlay class file(s) from '" + name
                    + "' — their WEB-INF/lib JAR was rebuilt more recently,"
                    + " so the newer JAR now serves those classes");
        }
        // This pass's covering-JAR coverage, recorded into the manifest at
        // the reconcile/refresh below so the NEXT pass can detect a rebuilt
        // JAR and drop the overlay entries mirrored under its cover.
        Map<String, SyncManifest.JarCoverage> jarCoverage = new LinkedHashMap<>();
        // Union of every source root's contributed paths — what this run's
        // sync claims to have covered. The stale-file reconcile below
        // records it as the new manifest and deletes ONLY classes a prior
        // manifest recorded (stamp-verified) that are no longer contributed
        // (removed from source). With PreResources gone, the deployed copy
        // is the sole authority for what Tomcat loads, so a stale class
        // would keep getting resolved by the classloader and the user sees
        // "I deleted that class, why is it still here" behaviour.
        java.util.Set<String> contributedPaths = new java.util.HashSet<>();
        // Read after dropStaleJarOverlays so the stamps reflect its deletions.
        Map<String, SyncManifest.Stamp> recordedStamps = SyncManifest.readStamped(syncManifest);
        Map<String, SyncManifest.Stamp> currentStamps = new java.util.HashMap<>();
        // Tracks whether EVERY source root's walk fully enumerated its
        // contribution. If any root's walk failed (vanished source, nesting
        // refusal, unreadable subtree, aborted walk), contributedPaths is an
        // incomplete union and the orphan pass below is deferred — deleting
        // a file the failed root legitimately owns but never visited would
        // strip a working deployment (silent staleness or NoClassDefFound).
        boolean allRootsWalkedCleanly = true;
        for (SourceRoot src : sourceRoots) {
            boolean classesOnly = shouldMirrorClassesOnly(src, deployedLibraryKeys);
            // Covering-JAR gate: when a deployed JAR covers this dependency
            // root, only IDE output NEWER than the JAR may overlay it —
            // otherwise the JAR's copy is the freshest and mirroring (or
            // keeping) loose classes would shadow it. Files at/below the
            // floor are not contributed, so the reconcile below also drops
            // their previously-mirrored copies.
            String coveringJar = coveringJarFor(src, deployedLibraryJars);
            long jarMtimeFloor = Long.MIN_VALUE;
            SyncManifest.Stamp coveringJarStamp = null;
            if (coveringJar != null) {
                coveringJarStamp = SyncManifest.stampOf(
                        artifactRoot.resolve(WEB_INF_LIB_PATH).resolve(coveringJar));
                if (!coveringJarStamp.isUnknown()) {
                    jarMtimeFloor = coveringJarStamp.mtimeMillis();
                }
            }
            TreeMirror.MirrorResult mr =
                    mirrorTree(src.path(), webInfClasses, classesOnly, jarMtimeFloor, recordedStamps);
            currentStamps.putAll(mr.stamps());
            if (coveringJar != null && !coveringJarStamp.isUnknown()
                    && !mr.contributedPaths().isEmpty()) {
                String jarRel = WEB_INF_LIB_PATH + "/" + coveringJar;
                jarCoverage.merge(jarRel,
                        new SyncManifest.JarCoverage(coveringJarStamp, mr.contributedPaths()),
                        (a, b) -> {
                            Set<String> union = new HashSet<>(a.coveredPaths());
                            union.addAll(b.coveredPaths());
                            return new SyncManifest.JarCoverage(a.stamp(), union);
                        });
            }
            copiedForThisArtifact += mr.copied();
            brokenForThisArtifact += mr.brokenSkipped();
            contributedPaths.addAll(mr.contributedPaths());
            if (mr.walkFailed()) {
                allRootsWalkedCleanly = false;
                logger.logServerWarning("Class sync: source root " + src.path()
                        + " for '" + name + "' could not be fully read;"
                        + " orphan cleanup deferred to avoid deleting deployed files.");
            }
            if (mr.copied() > 0) {
                logger.logServerInfo("Class sync:     " + mr.copied() + " file(s) from " + src.path()
                        + (classesOnly ? " (.class only)" : ""));
            }
        }
        // Orphan-reconcile only when at least one source root actually
        // contributed (the FAILED MirrorResult from a non-existent src
        // returns no paths) AND every root's walk completed cleanly. Two
        // failure modes are guarded here:
        //   - Empty contributedPaths could mean "every source root was
        //     unreadable today"; deleting everything in WEB-INF/classes/ on
        //     that failure mode would destroy a working deployment.
        //   - A PARTIAL failure — one root walks fine while another (e.g. a
        //     dependency module) fails mid-walk — leaves contributedPaths
        //     non-empty but missing the failed root's paths; running the
        //     orphan pass then would delete every file that failed root
        //     previously mirrored. allRootsWalkedCleanly defers the pass in
        //     that case, leaving stale files rather than risking live ones.
        int orphansRemovedForThisArtifact = 0;
        if (!contributedPaths.isEmpty() && allRootsWalkedCleanly) {
            // Reconcile against the manifest of what WE synced last run: delete
            // only classes we previously wrote and no longer do (removed from
            // source). A class this sync never wrote — e.g. one the artifact
            // build assembles from a root the module resolver doesn't enumerate
            // — is NOT in the manifest and is never deleted, so we can't strip a
            // legitimately-deployed class and cause ClassNotFoundException.
            orphansRemovedForThisArtifact = SyncManifest.reconcileStamped(
                    webInfClasses, syncManifest, stampsFor(contributedPaths, currentStamps), jarCoverage)
                    .removed();
            if (orphansRemovedForThisArtifact > 0) {
                logger.logServerInfo("Class sync: removed " + orphansRemovedForThisArtifact
                        + " stale class file(s) from '" + name
                        + "' (previously synced, now removed from source)");
            }
        } else if (!contributedPaths.isEmpty()) {
            // Deferred run: not safe to delete, but the mirror may have just
            // overwritten deployed files — refresh their recorded stamps or
            // a class edited during a deferred run could never be cleaned
            // once removed from source (stale stamp = a permanently
            // loadable stale class, the exact bug this manifest fixes).
            SyncManifest.refreshStamped(
                    webInfClasses, syncManifest, stampsFor(contributedPaths, currentStamps), jarCoverage);
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
        return new ArtifactSyncOutcome(
                copiedForThisArtifact, brokenForThisArtifact, contributedPaths.size(),
                copiedForThisArtifact > 0 || orphansRemovedForThisArtifact > 0 || overlaysDropped > 0);
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
        PackagingElementResolvingContext ctx =
                ArtifactManager.getInstance(project).getResolvingContext();
        Module module = walkPackagingTreeForModule(artifact.getRootElement(), ctx);
        if (module == null) {
            return new ResolutionReport(null, "artifact-has-no-module", List.of(),
                    "IntelliJ Artifact '" + d.getArtifactName() + "' contains no module-output"
                    + " element (built from files / libraries only)");
        }
        List<SourceRoot> roots = collectProductionRootsForArtifact(project, artifact, module, ctx);
        return new ResolutionReport(module.getName(),
                "artifact-tree: '" + d.getArtifactName() + "'",
                roots, null,
                uncoveredPackagedModules(artifact, module, roots, ctx));
    }

    /**
     * Production class-output roots the artifact should deploy into
     * {@code WEB-INF/classes/}: the union of {@link #collectProductionRoots}
     * across EVERY module the artifact packages, not just the first. A class
     * in a module that the artifact composes directly into the webapp but that
     * is not a production dependency of the primary module is then still
     * mirrored — the incremental sync no longer under-covers a multi-module
     * artifact relative to what a full artifact build assembles.
     *
     * <p>The whole-project Make that precedes launch has already compiled every
     * module's output, so these roots exist on disk; broadening the mirror set
     * simply reaches the ones the single-module walk skipped. For the common
     * single-module artifact this is identical to {@link #collectProductionRoots}.
     * Must run in a read action.
     */
    @NotNull
    private static List<SourceRoot> collectProductionRootsForArtifact(
            @NotNull Project project,
            @NotNull Artifact artifact,
            @NotNull Module primary,
            @NotNull PackagingElementResolvingContext ctx) {
        Set<Module> packaged = collectPackagedModules(artifact.getRootElement(), ctx);
        if (packaged.size() <= 1) {
            // Common case: nothing beyond the primary — no extra walks.
            return collectProductionRoots(project, primary);
        }
        // Primary first so its closure defines the base policy; then the rest,
        // name-ordered for deterministic output.
        List<Module> ordered = new ArrayList<>();
        ordered.add(primary);
        packaged.stream()
                .filter(m -> !m.equals(primary))
                .sorted(Comparator.comparing(Module::getName))
                .forEach(ordered::add);

        List<List<SourceRoot>> perModule = new ArrayList<>();
        for (Module m : ordered) {
            perModule.add(collectProductionRoots(project, m));
        }
        return mergeProductionRoots(perModule);
    }

    /**
     * Merges per-module production-root lists into one, de-duplicated by path.
     * When a path appears as both a module's own output ({@code classesOnly ==
     * false}, full-content) and another module's dependency view
     * ({@code classesOnly == true}, {@code .class}-only), the own/full-content
     * entry wins so that root's non-class resources are not dropped. First
     * occurrence sets the order. Pure and package-visible for testing.
     */
    @NotNull
    static List<SourceRoot> mergeProductionRoots(@NotNull List<List<SourceRoot>> perModule) {
        Map<Path, SourceRoot> byPath = new LinkedHashMap<>();
        for (List<SourceRoot> roots : perModule) {
            for (SourceRoot r : roots) {
                SourceRoot existing = byPath.get(r.path());
                if (existing == null || (existing.classesOnly() && !r.classesOnly())) {
                    byPath.put(r.path(), r);
                }
            }
        }
        return new ArrayList<>(byPath.values());
    }

    /**
     * Names the modules the artifact packages whose output the single-module
     * re-derivation from {@code resolved} does not cover — i.e. packaged
     * modules that are neither {@code resolved} nor one of its production
     * dependencies (whose roots are already in {@code coveredRoots}). Only
     * such a module's classes depend on a full artifact build to reach the
     * served tree; the incremental sync never mirrors them.
     *
     * <p>Empty for the common single-module artifact — computed only when the
     * tree actually packages more than one module. Must run in a read action.
     */
    @NotNull
    private static List<String> uncoveredPackagedModules(@NotNull Artifact artifact,
                                                         @NotNull Module resolved,
                                                         @NotNull List<SourceRoot> coveredRoots,
                                                         @NotNull PackagingElementResolvingContext ctx) {
        Set<Module> packaged = collectPackagedModules(artifact.getRootElement(), ctx);
        if (packaged.size() <= 1) return List.of();
        Set<Path> covered = new HashSet<>();
        for (SourceRoot r : coveredRoots) covered.add(r.path());
        List<String> uncovered = new ArrayList<>();
        for (Module pm : packaged) {
            if (pm.equals(resolved)) continue;
            boolean anyRootCovered = false;
            for (Path own : moduleOwnOutputPaths(pm)) {
                if (covered.contains(own)) { anyRootCovered = true; break; }
            }
            if (!anyRootCovered) uncovered.add(pm.getName());
        }
        Collections.sort(uncovered);
        return uncovered;
    }

    /**
     * The module's OWN production class-output paths (dependency modules
     * excluded). Package-visible: {@link DeploymentStaleness} compares these
     * against deployed WAR / JAR mtimes. Call under a read action.
     */
    @NotNull
    static Set<Path> moduleOwnOutputPaths(@NotNull Module module) {
        return collectRootPaths(OrderEnumerator.orderEntries(module)
                .withoutDepModules()
                .productionOnly()
                .withoutSdk()
                .withoutLibraries()
                .classes()
                .getRoots());
    }

    /**
     * Every module the artifact packages (all {@code ModulePackagingElement}s
     * in the tree), not just the first — the diagnostic counterpart to
     * {@link #walkPackagingTreeForModule}, which stops at the first hit.
     */
    @NotNull
    static Set<Module> collectPackagedModules(@NotNull PackagingElement<?> element,
                                              @NotNull PackagingElementResolvingContext ctx) {
        Set<Module> out = new HashSet<>();
        collectPackagedModules(element, ctx, out,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private static void collectPackagedModules(@NotNull PackagingElement<?> element,
                                               @NotNull PackagingElementResolvingContext ctx,
                                               @NotNull Set<Module> out,
                                               @NotNull Set<PackagingElement<?>> visited) {
        if (!visited.add(element)) return;
        Module direct = tryFindModuleOnElement(element, ctx);
        if (direct != null) out.add(direct);
        if (element instanceof CompositePackagingElement<?> composite) {
            for (PackagingElement<?> child : composite.getChildren()) {
                collectPackagedModules(child, ctx, out, visited);
            }
        }
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
     *
     * <p>{@code uncoveredPackagedModules} names modules the artifact packages
     * whose output the single-module re-derivation does NOT cover (they are
     * not the resolved module and not one of its production dependencies).
     * Their classes reach the served tree only via a full artifact build, not
     * the incremental sync — so a stale/absent class from one of them is a
     * "run Rebuild" case. Empty for the common single-module artifact.
     */
    record ResolutionReport(@Nullable String moduleName,
                            @NotNull String strategy,
                            @NotNull List<SourceRoot> sourceRoots,
                            @Nullable String diagnostic,
                            @NotNull List<String> uncoveredPackagedModules) {
        /** Convenience: no uncovered-module info (failures, module-backed, external). */
        ResolutionReport(@Nullable String moduleName,
                         @NotNull String strategy,
                         @NotNull List<SourceRoot> sourceRoots,
                         @Nullable String diagnostic) {
            this(moduleName, strategy, sourceRoots, diagnostic, List.of());
        }
    }

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
            // Cancellation must escape the reflective boundary: this runs under
            // cancelable read actions (launch prep, and per-config matching on
            // the action-update path), where an InvocationTargetException
            // wrapping a PCE must not degrade to "no module".
            if (e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() instanceof com.intellij.openapi.progress.ProcessCanceledException pce) {
                throw pce;
            }
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

            String artifactName = libraryArtifactNameFor(dep);

            // withoutDepModules(): map ONLY dep's own output under dep's
            // artifact identity. Without it, orderEntries(dep) also returns
            // dep's own module-dependencies' outputs, which would be put()
            // under dep's name — in a diamond graph (M→{A,B}, A→B, B→S) the
            // visited guard can block the corrective recursion, permanently
            // mis-keying a transitive root (e.g. S's output to "B"). Each
            // transitive root is already mapped by its true owner via the
            // recursion below.
            for (VirtualFile outputRoot : OrderEnumerator.orderEntries(dep)
                    .withoutDepModules()
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
        return scanDeployedLibraryJars(artifactRoot).keySet();
    }

    /**
     * Like {@link #scanDeployedLibraryKeys} but keeps the mapping from each
     * version-independent artifact key to the deployed JAR's file name, so a
     * covered dependency root can be tied to the concrete JAR that covers it
     * (for the covering-JAR mtime floor and the manifest's JAR records).
     */
    /**
     * The production module dependencies whose output this module's deployment
     * also carries — the same closure {@link #collectProductionRoots} mirrors,
     * as modules rather than roots. The freshness view needs them because a
     * module-backed deployment resolves to one module while its {@code
     * WEB-INF/lib} carries every dependency's JAR. <strong>Read action
     * required.</strong>
     */
    @NotNull
    static Set<Module> productionDependencyModules(@NotNull Module module) {
        Set<Module> deps = new java.util.LinkedHashSet<>();
        collectProductionDependencyModules(module, deps, new HashSet<>());
        return deps;
    }

    private static void collectProductionDependencyModules(@NotNull Module module,
                                                           @NotNull Set<Module> out,
                                                           @NotNull Set<String> visited) {
        if (!visited.add(module.getName())) return;
        for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
            if (!(entry instanceof ModuleOrderEntry moduleEntry)) continue;
            Module dep = moduleEntry.getModule();
            if (dep == null) continue;
            out.add(dep);
            collectProductionDependencyModules(dep, out, visited);
        }
    }

    /**
     * The build-agnostic artifact identity for a module, in order of precision:
     * <ol>
     *   <li>Maven artifactId — exact when the module is a Maven project.</li>
     *   <li>External-system (Gradle) project name — the build's OWN name for the
     *       module, which lines up with the JAR it packages far better than the
     *       IntelliJ module name does. A Gradle subproject's module is
     *       {@code app.sub.main}, not {@code sub}, so the module-name-stem
     *       fallback alone mis-keyed it and mirrored its resources full-content
     *       even when they already shipped in {@code sub.jar} (duplicate
     *       classpath).</li>
     *   <li>Module-name stem — final fallback for plain / JPS projects.</li>
     * </ol>
     * All three are reduced to a version-independent key downstream. Package-
     * visible because module→deployed-JAR matching must use ONE basis: the
     * freshness view keying on the raw module name instead reported a
     * jar-covered module as loose classes. <strong>Read action required.</strong>
     */
    @NotNull
    public static String libraryArtifactNameFor(@NotNull Module module) {
        String artifactName = MavenModelProvider.artifactId(module);
        if (artifactName == null) {
            artifactName = externalSystemArtifactName(module);
        }
        if (artifactName == null) {
            String moduleName = module.getName();
            int dot = moduleName.lastIndexOf('.');
            artifactName = dot >= 0 ? moduleName.substring(dot + 1) : moduleName;
        }
        return artifactName;
    }

    @NotNull
    static Map<String, String> scanDeployedLibraryJars(@NotNull Path artifactRoot) {
        Map<String, String> jars = new HashMap<>();
        Path webInfLib = artifactRoot.resolve(WEB_INF_LIB_PATH);
        if (!Files.isDirectory(webInfLib)) return jars;
        try (var stream = Files.list(webInfLib)) {
            stream.map(p -> p.getFileName().toString())
                  .filter(n -> n.endsWith(EXT_JAR))
                  .forEach(n -> jars.put(LibraryArtifactNames.libraryArtifactKey(n), n));
        } catch (IOException e) {
            LOG.debug("Class sync: could not list WEB-INF/lib at " + webInfLib
                    + ": " + e.getMessage());
        }
        return jars;
    }

    /**
     * The file name of the deployed {@code WEB-INF/lib} JAR covering
     * {@code root}, or {@code null} when the root is the module's own output,
     * has no resolved identity, or no matching JAR is deployed. Non-null
     * exactly when {@link #shouldMirrorClassesOnly} returned {@code true}
     * because of an actual JAR (not the unresolved-identity default).
     */
    @Nullable
    static String coveringJarFor(@NotNull SourceRoot root,
                                 @NotNull Map<String, String> deployedLibraryJars) {
        if (!root.classesOnly() || root.artifactName() == null) return null;
        return deployedLibraryJars.get(
                LibraryArtifactNames.libraryArtifactKey(root.artifactName() + EXT_JAR));
    }

    /**
     * An uncovered packaged module whose deployed {@code WEB-INF/lib} JAR is
     * older than the module's own compiled output — the sync cannot overlay it
     * (uncovered), so Tomcat serves the old JAR until a build-tool
     * install/package refreshes it.
     */
    record OutdatedJar(@NotNull String moduleName, @NotNull String jarFileName,
                       @NotNull Path newerOutput, long newerByMillis) {}

    /**
     * Detects {@link OutdatedJar}s among the UNCOVERED packaged modules only —
     * modules the overlay mirrors are excluded by construction (their staleness
     * is already handled by the covering-JAR floor + drop pass, in both
     * directions). A module with no matching deployed JAR, or an unreadable
     * JAR, contributes nothing: absence of evidence is never an alarm.
     * Platform-free; short-circuits per module at the first newer output file.
     */
    @NotNull
    static List<OutdatedJar> findOutdatedUncoveredJars(
            @NotNull Map<String, ? extends Collection<Path>> outputRootsByUncoveredModule,
            @NotNull Map<String, String> artifactNameByModule,
            @NotNull Map<String, String> deployedLibraryJars,
            @NotNull Path artifactRoot) {
        List<OutdatedJar> outdated = new ArrayList<>();
        for (Map.Entry<String, ? extends Collection<Path>> e : outputRootsByUncoveredModule.entrySet()) {
            TomcatProgress.checkCanceled();
            // Match on the module's JAR identity (libraryArtifactNameFor), NOT
            // its IDE module name: the two differ for Gradle subprojects and
            // any module renamed away from its artifactId, and a mismatch here
            // silently disables the warning instead of raising a false one.
            String artifactName = artifactNameByModule.getOrDefault(e.getKey(), e.getKey());
            String jarFile = deployedLibraryJars.get(
                    LibraryArtifactNames.libraryArtifactKey(artifactName + EXT_JAR));
            if (jarFile == null) continue;
            Path jar = artifactRoot.resolve(WEB_INF_LIB_PATH).resolve(jarFile);
            long jarMtime;
            try {
                jarMtime = Files.getLastModifiedTime(jar).toMillis();
            } catch (IOException ex) {
                continue;
            }
            DeploymentStaleness.NewerFile newer =
                    DeploymentStaleness.findOutputNewerThan(jarMtime, e.getValue());
            if (newer != null) {
                outdated.add(new OutdatedJar(e.getKey(), jarFile,
                        newer.file(), newer.mtimeMillis() - jarMtime));
            }
        }
        return outdated;
    }

    /**
     * Outdated-JAR notification wiring: console warning on EVERY action (the
     * console is the authoritative surface), balloon once per (deployment,
     * module+JAR set) per IDE session. Deliberately NO action button — nothing
     * is auto-fixable until a build-tool integration exists; a fake action
     * would train users to distrust the balloon. Collaborators injected so
     * tests pin the wiring without the platform.
     */
    static void warnOutdatedUncoveredJars(@NotNull String artifactName,
                                          @NotNull List<OutdatedJar> outdated,
                                          @NotNull TomcatDeploymentLogger logger,
                                          @NotNull SessionNotificationGate gate,
                                          @NotNull String scopeId,
                                          @NotNull BiConsumer<String, String> balloon) {
        if (outdated.isEmpty()) return;
        Set<String> key = new HashSet<>();
        StringBuilder pairs = new StringBuilder();
        for (OutdatedJar o : outdated) {
            if (pairs.length() > 0) pairs.append(", ");
            pairs.append("'").append(o.jarFileName())
                 .append("' (module '").append(o.moduleName()).append("')");
            key.add(o.moduleName() + "|" + o.jarFileName());
        }
        boolean one = outdated.size() == 1;
        logger.logServerWarning("Outdated dependency JAR" + (one ? "" : "s") + " in '"
                + artifactName + "': " + pairs + " — the deployed JAR is older than the"
                + " module's compiled output, and the class sync does not cover"
                + (one ? " that module" : " those modules") + " (not a production dependency"
                + " of the deployment's module). Tomcat keeps serving the old JAR."
                + " Run 'mvn install' / 'gradle build' to refresh it.");
        if (!gate.shouldNotify("outdated-jar|" + scopeId, key)) return;
        balloon.accept(one ? "Outdated dependency JAR deployed"
                        : outdated.size() + " outdated dependency JARs deployed",
                artifactName + ": " + pairs + (one ? " is" : " are") + " older than the"
                        + " compiled output, and the class sync cannot cover"
                        + (one ? " it" : " them")
                        + ". Run 'mvn install' / 'gradle build' to refresh.");
    }

    /**
     * Full-content mirror — copies every file (the web module's own output
     * root). Convenience overload of {@link #mirrorTree(Path, Path, boolean)}
     * with {@code classesOnly == false}.
     */
    // Package-visible so DeployedClassesSyncScenariosTest can drive end-to-end
    // mirror behaviour without standing up a Project/ModuleManager fixture.
    static TreeMirror.MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst) {
        return mirrorTree(src, dst, false);
    }

    /**
     * Walks {@code src} and copies every file that is missing in {@code dst}
     * or older than its {@code src} counterpart, via {@link TreeMirror} with
     * the class-sync policy. Returns counts for files copied and files
     * skipped because they were detected as ECJ "compile-with-errors" stubs
     * (see {@link #isBrokenEcjClass} — refusing them keeps a working deployed
     * copy in place instead of a stub that throws at class init).
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
     */
    static TreeMirror.MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst, boolean classesOnly) {
        return mirrorTree(src, dst, classesOnly, Long.MIN_VALUE);
    }

    /**
     * {@link #mirrorTree(Path, Path, boolean)} plus a covering-JAR mtime floor:
     * source files not strictly newer than {@code sourceMtimeFloorMillis} are
     * skipped without contributing. Used for dependency roots whose classes are
     * also deployed as a {@code WEB-INF/lib} JAR — only IDE output newer than
     * that JAR may overlay it, or a stale loose class would shadow a
     * build-tool-rebuilt JAR (Tomcat searches {@code WEB-INF/classes} first).
     * {@link Long#MIN_VALUE} = no floor.
     */
    static TreeMirror.MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst,
                                              boolean classesOnly, long sourceMtimeFloorMillis) {
        return mirrorTree(src, dst, classesOnly, sourceMtimeFloorMillis, null);
    }

    /** {@code recordedStamps}: the manifest's stamps, letting an up-to-date file skip its destination stat. */
    static TreeMirror.MirrorResult mirrorTree(@NotNull Path src, @NotNull Path dst,
                                              boolean classesOnly, long sourceMtimeFloorMillis,
                                              @Nullable Map<String, SyncManifest.Stamp> recordedStamps) {
        return TreeMirror.mirrorTree(src, dst, new TreeMirror.Policy(
                "Class sync",
                Set.of(),
                classesOnly
                        ? f -> !f.getFileName().toString().endsWith(EXT_CLASS)
                        : null,
                // ECJ broken-stub veto — the engine applies it only to copy
                // candidates (after the mtime/size gate), keeping a no-op
                // sync at stat-only cost.
                DeployedClassesSync::isBrokenEcjClass,
                // Generic failure stat-ing the destination: copy anyway
                // (safer than leaving stale code).
                true,
                sourceMtimeFloorMillis,
                recordedStamps));
    }

    /** Every contributed path with its known stamp; {@link SyncManifest.Stamp#UNKNOWN} where the mirror had none. */
    @NotNull
    static Map<String, SyncManifest.Stamp> stampsFor(@NotNull Set<String> contributed,
                                                     @NotNull Map<String, SyncManifest.Stamp> known) {
        Map<String, SyncManifest.Stamp> out = new java.util.HashMap<>(Math.max(16, contributed.size() * 2));
        for (String rel : contributed) out.put(rel, known.getOrDefault(rel, SyncManifest.Stamp.UNKNOWN));
        return out;
    }

    /**
     * File name of the LEGACY per-deployment class-sync manifest, which earlier
     * versions wrote into the deployed {@code WEB-INF/}. The live manifest now
     * resides in the IDE-owned {@link SyncManifestStore} (never inside the webapp);
     * this name survives only as the migration source and the self-heal target.
     * Note the store manifest deliberately survives a clean rebuild — reconcile
     * stamps (size+mtime+creation) keep deletions correct across it.
     */
    static final String CLASS_SYNC_MANIFEST = ".devtomcat-classsync.manifest";

    /**
     * Location of the per-deployment class-sync manifest for a given
     * {@code WEB-INF/classes} directory: in the IDE-owned manifest store, never
     * inside the webapp. A legacy manifest written by earlier versions at
     * {@code WEB-INF/.devtomcat-classsync.manifest} is adopted into the store
     * (and removed from the webapp) on first contact.
     */
    @NotNull
    public static Path classSyncManifestFor(@NotNull Path webInfClasses) {
        return SyncManifestStore.resolveWithMigration(
                "classsync", webInfClasses, webInfClasses.resolveSibling(CLASS_SYNC_MANIFEST));
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
     * Copy decision — delegates to the shared {@link TreeMirror#shouldCopy}
     * gate (missing destination, strictly-newer source mtime, or size
     * tie-breaker for edits landing within the filesystem's mtime
     * resolution), with the class-sync policy for a generic failure reading
     * the destination's attributes: copy anyway, safer than leaving stale
     * code.
     */
    static boolean shouldCopy(@NotNull Path source,
                              @NotNull BasicFileAttributes sourceAttrs,
                              @NotNull Path target) {
        try {
            return TreeMirror.shouldCopy(source, sourceAttrs, target, true);
        } catch (IOException cannotHappen) {
            // copyOnDstStatError == true resolves every generic dst-stat
            // failure to "copy"; nothing on that path throws.
            return true;
        }
    }

}
