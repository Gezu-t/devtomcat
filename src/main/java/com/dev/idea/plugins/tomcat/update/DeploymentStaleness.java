package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
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
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Staleness verdicts for packed-WAR deployments: is the WAR file older than
 * the newest compiled output of the modules it packages? A stale WAR deployed
 * as-is silently ships old code — the build tool froze the WAR at the last
 * {@code mvn package} / {@code gradle war}, and no amount of IDE compilation
 * updates its contents.
 *
 * <p>The verdict vocabulary is deliberately three-valued:
 * <ul>
 *   <li>{@link Kind#STALE} — at least one file under a packaged module's
 *       production output roots is strictly newer than the WAR. Carries the
 *       module, an example newer file, and how much newer for the message.</li>
 *   <li>{@link Kind#FRESH} — every readable output file is at or before the
 *       WAR's mtime. A WAR older than <em>nothing</em> is FRESH.</li>
 *   <li>{@link Kind#UNKNOWN} — no packaged modules could be resolved (external
 *       WAR, missing artifact, unreadable WAR). Never treated as stale: what
 *       cannot be judged must not be blocked.</li>
 * </ul>
 *
 * <p>Unreadable or missing output roots contribute nothing — a stat failure
 * can never produce a false STALE. The output walk short-circuits at the first
 * strictly-newer file (existence is the question, not the global max) and
 * polls {@link TomcatProgress#checkCanceled()} so Cancel stays responsive on
 * large output trees.
 *
 * <p>Consumers: the update actions block a STALE WAR copy behind an explicit
 * "Deploy Anyway" override (see {@code TomcatApplicationUpdater}); the launch
 * path only warns ({@link #warnIfStaleWarAtLaunch}) — blocking a launch copy
 * would start Tomcat with no webapp at all, which is worse than stale.
 */
public final class DeploymentStaleness {

    private DeploymentStaleness() {}

    /** The three-valued staleness outcome. See class javadoc for semantics. */
    public enum Kind { FRESH, STALE, UNKNOWN }

    /**
     * Verdict for one WAR deployment. {@code moduleName}, {@code newerOutput}
     * and {@code newerByMillis} are populated only for {@link Kind#STALE} —
     * the evidence the user-facing message names.
     */
    public record Verdict(@NotNull Kind kind,
                          @Nullable String moduleName,
                          @Nullable Path newerOutput,
                          long newerByMillis) {

        private static final Verdict FRESH_VERDICT = new Verdict(Kind.FRESH, null, null, 0);
        private static final Verdict UNKNOWN_VERDICT = new Verdict(Kind.UNKNOWN, null, null, 0);

        @NotNull
        public static Verdict fresh() { return FRESH_VERDICT; }

        @NotNull
        public static Verdict unknown() { return UNKNOWN_VERDICT; }

        @NotNull
        public static Verdict stale(@NotNull String moduleName,
                                    @NotNull Path newerOutput,
                                    long newerByMillis) {
            return new Verdict(Kind.STALE, moduleName, newerOutput, newerByMillis);
        }

        public boolean isStale() { return kind == Kind.STALE; }
    }

    /** One output file found strictly newer than the comparison target. */
    record NewerFile(@NotNull Path file, long mtimeMillis) {}

    /**
     * Returns the first regular file under {@code roots} whose mtime is
     * STRICTLY newer than {@code targetMtimeMillis}, or {@code null} when none
     * exists. Exact-equal mtimes are not newer. Short-circuits at the first
     * hit; missing/unreadable roots and unreadable entries contribute nothing
     * (never a false positive). Cancellation-aware; PCE propagates unchanged.
     */
    @Nullable
    static NewerFile findOutputNewerThan(long targetMtimeMillis, @NotNull Collection<Path> roots) {
        return findOutputNewerThan(targetMtimeMillis, roots, null);
    }

    /**
     * {@link #findOutputNewerThan(long, Collection)} with a per-file observer
     * so tests can pin the short-circuit contract (the walk must STOP at the
     * first strictly-newer file, not compute a global max).
     */
    @Nullable
    static NewerFile findOutputNewerThan(long targetMtimeMillis,
                                         @NotNull Collection<Path> roots,
                                         @Nullable Consumer<Path> visitObserver) {
        for (Path root : roots) {
            TomcatProgress.checkCanceled();
            if (!Files.isDirectory(root)) continue; // missing/unreadable root: no evidence
            NewerFile[] hit = new NewerFile[1];
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
                        if (visitObserver != null) visitObserver.accept(file);
                        if (attrs.isRegularFile()
                                && attrs.lastModifiedTime().toMillis() > targetMtimeMillis) {
                            hit[0] = new NewerFile(file, attrs.lastModifiedTime().toMillis());
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE; // unreadable entry: no evidence
                    }
                });
            } catch (IOException e) {
                continue; // unreadable root: no evidence, never a false STALE
            }
            if (hit[0] != null) return hit[0];
        }
        return null;
    }

    /**
     * Platform-free verdict core: compares {@code targetMtimeMillis} (the WAR's
     * mtime) against the production output roots of each named module. An empty
     * module map is {@link Kind#UNKNOWN} — a deployment whose modules cannot be
     * resolved must not be blocked on a judgment nobody could make.
     */
    @NotNull
    static Verdict verdictFor(long targetMtimeMillis,
                              @NotNull Map<String, ? extends Collection<Path>> outputRootsByModule) {
        if (outputRootsByModule.isEmpty()) return Verdict.unknown();
        for (Map.Entry<String, ? extends Collection<Path>> e : outputRootsByModule.entrySet()) {
            NewerFile newer = findOutputNewerThan(targetMtimeMillis, e.getValue());
            if (newer != null) {
                return Verdict.stale(e.getKey(), newer.file(),
                        newer.mtimeMillis() - targetMtimeMillis);
            }
        }
        return Verdict.fresh();
    }

    /**
     * Verdict for a WAR deployment against the project model: resolves every
     * packaged module ({@link DeploymentModuleResolver#resolveAll}) and each
     * module's own production output roots (the same derivation
     * {@link DeployedClassesSync} mirrors from), then delegates to
     * {@link #verdictFor}. The model walk runs under a read action; the file
     * walk does not. An unreadable/missing WAR is {@link Kind#UNKNOWN}.
     */
    @NotNull
    public static Verdict evaluate(@NotNull Project project, @NotNull Deployment deployment) {
        Path war = deployment.getResolvedPath();
        if (war == null) return Verdict.unknown();
        long warMtime;
        try {
            warMtime = Files.getLastModifiedTime(war).toMillis();
        } catch (IOException e) {
            return Verdict.unknown();
        }
        // TreeMap: deterministic module order, so the STALE evidence (which
        // module gets named) is stable across runs.
        Map<String, Set<Path>> rootsByModule = TomcatReadActions.compute(() -> {
            Map<String, Set<Path>> byModule = new TreeMap<>();
            for (Module m : DeploymentModuleResolver.resolveAll(deployment, project)) {
                byModule.put(m.getName(), DeployedClassesSync.moduleOwnOutputPaths(m));
            }
            return byModule;
        });
        return verdictFor(warMtime, rootsByModule);
    }

    /**
     * Launch-path staleness surface: console warning (plus a once-per-session
     * balloon) when a WAR about to be copied into {@code webapps/} at launch is
     * stale. Deliberately NON-blocking — refusing the launch copy would start
     * Tomcat with no webapp at all, which is strictly worse than serving old
     * code with a loud warning.
     */
    public static void warnIfStaleWarAtLaunch(@Nullable Project project,
                                              @NotNull Deployment deployment,
                                              @Nullable TomcatDeploymentLogger logger,
                                              @NotNull String scopeId) {
        if (project == null || project.isDisposed()) return;
        Verdict verdict = evaluate(project, deployment);
        warnStaleWarAtLaunch(deployment.getDisplayName(), verdict, logger,
                SessionNotificationGate.INSTANCE, scopeId,
                (title, content) -> TomcatNotifier.warning(project, title, content));
    }

    /**
     * The platform-free wiring of {@link #warnIfStaleWarAtLaunch}: console line
     * on every launch, balloon gated once per (scope, deployment, module) per
     * IDE session. Package-visible so tests pin the gating and the message
     * without a Project.
     */
    static void warnStaleWarAtLaunch(@NotNull String deploymentName,
                                     @NotNull Verdict verdict,
                                     @Nullable TomcatDeploymentLogger logger,
                                     @NotNull SessionNotificationGate gate,
                                     @NotNull String scopeId,
                                     @NotNull BiConsumer<String, String> balloon) {
        if (!verdict.isStale()) return;
        if (logger != null) {
            logger.logServerWarning("Deploying a stale WAR: '" + deploymentName
                    + "' predates the compiled output of module '" + verdict.moduleName()
                    + "' (" + verdict.newerOutput() + " is " + describeAge(verdict.newerByMillis())
                    + " newer). Tomcat will serve the old code — rebuild the WAR with"
                    + " 'mvn package' / 'gradle war' and redeploy.");
        }
        if (gate.shouldNotify("stale-war-launch|" + scopeId + "|" + deploymentName,
                Set.of(deploymentName + "|" + verdict.moduleName()))) {
            balloon.accept("Deploying a stale WAR",
                    deploymentName + ": the WAR is older than module '" + verdict.moduleName()
                            + "' compiled output. Rebuild with 'mvn package' / 'gradle war'.");
        }
    }

    /**
     * Human-readable magnitude for "how much newer" in the staleness messages.
     * Coarse on purpose — the message needs scale, not precision.
     */
    @NotNull
    static String describeAge(long millis) {
        long seconds = Math.max(millis, 0) / 1000;
        if (seconds < 1) return "moments";
        if (seconds < 120) return seconds + " s";
        long minutes = seconds / 60;
        if (minutes < 120) return minutes + " min";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h";
        return (hours / 24) + " day(s)";
    }
}
