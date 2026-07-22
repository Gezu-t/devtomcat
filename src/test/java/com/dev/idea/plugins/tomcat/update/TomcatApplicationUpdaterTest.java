package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the platform-free seams of TomcatApplicationUpdater: the
 * pure-logic static helpers, the WAR re-copy loops (deployment list + webapps
 * directory in, file effects out), and the sync-gap notification wiring
 * (injected gate + balloon seam).
 *
 * <p>The instance methods (performUpdate, executeUpdate, etc.) require IntelliJ
 * Platform infrastructure (Project, CompilerManager, RunManager, etc.) and are not
 * tested here — they delegate to the seams covered below.
 */
@DisplayName("TomcatApplicationUpdater")
class TomcatApplicationUpdaterTest {

    /** Every deployment fresh — for tests where staleness is not the subject. */
    private static final Function<Deployment, DeploymentStaleness.Verdict> FRESH =
            d -> DeploymentStaleness.Verdict.fresh();

    @Nested
    @DisplayName("mapActionToDisplay")
    class MapActionToDisplayTests {

        @Test
        @DisplayName("UPDATE_RESOURCES maps to 'Update resources'")
        void updateResources() {
            assertEquals("Update resources",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.UPDATE_RESOURCES));
        }

        @Test
        @DisplayName("UPDATE_CLASSES_AND_RESOURCES maps to 'Update classes and resources'")
        void updateClassesAndResources() {
            assertEquals("Update classes and resources",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES));
        }

        @Test
        @DisplayName("REDEPLOY maps to 'Redeploy'")
        void redeploy() {
            assertEquals("Redeploy",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.REDEPLOY));
        }

        @Test
        @DisplayName("RESTART_SERVER maps to 'Restart server'")
        void restartServer() {
            assertEquals("Restart server",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.RESTART_SERVER));
        }

        @Test
        @DisplayName("unknown action passes through unchanged")
        void unknownActionPassThrough() {
            assertEquals("some_custom_action",
                    TomcatApplicationUpdater.mapActionToDisplay("some_custom_action"));
        }

        @Test
        @DisplayName("empty string passes through unchanged")
        void emptyStringPassThrough() {
            assertEquals("",
                    TomcatApplicationUpdater.mapActionToDisplay(""));
        }
    }

    @Nested
    @DisplayName("findUnsyncableExternalDeployments")
    class FindUnsyncableExternalDeployments {

        private final Deployment externalA =
                new ExternalFileDeployment(Path.of("/projects/X/app-1.0.0"), "/app", true);
        private final Deployment externalB =
                new ExternalFileDeployment(Path.of("/projects/X/web-module.war"), "/web", false);

        private WarToExplodedQuickFix.FixCandidate candidateFor(Deployment d) {
            return new WarToExplodedQuickFix.FixCandidate(
                    d, Path.of("/projects/X/web-module"), "web-module");
        }

        @Test
        @DisplayName("an external deployment with no reclaim candidate is unsyncable")
        void externalWithoutCandidate() {
            assertEquals(List.of(externalA),
                    TomcatApplicationUpdater.findUnsyncableExternalDeployments(
                            List.of(externalA), List.of()));
        }

        @Test
        @DisplayName("an external deployment covered by a reclaim candidate is NOT reported")
        void externalWithCandidateExcluded() {
            assertEquals(List.of(externalA),
                    TomcatApplicationUpdater.findUnsyncableExternalDeployments(
                            List.of(externalA, externalB), List.of(candidateFor(externalB))));
        }

        @Test
        @DisplayName("all externals fixable → nothing to report")
        void allFixable() {
            assertTrue(TomcatApplicationUpdater.findUnsyncableExternalDeployments(
                    List.of(externalA, externalB),
                    List.of(candidateFor(externalA), candidateFor(externalB))).isEmpty());
        }

        @Test
        @DisplayName("no externals → nothing to report")
        void noExternals() {
            assertTrue(TomcatApplicationUpdater.findUnsyncableExternalDeployments(
                    List.of(), List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("redeployWarArtifactsInto — WAR re-copy loop after compile")
    class RedeployWarArtifactsInto {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        @Test
        @DisplayName("an unchanged WAR is NOT re-copied — no mtime advance, no context restart")
        void unchangedWarSkipped(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "same-size-A");
            Path target = webapps.resolve("app.war");
            Files.writeString(target, "same-size-B"); // same length, distinct bytes
            Files.setLastModifiedTime(source, FileTime.fromMillis(100_000L));
            Files.setLastModifiedTime(target, FileTime.fromMillis(200_000L));

            TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger, FRESH);

            assertEquals("same-size-B", Files.readString(target),
                    "an up-to-date deployed WAR must not be overwritten");
            assertEquals(200_000L, Files.getLastModifiedTime(target).toMillis(),
                    "the deployed WAR's mtime must not advance — Tomcat restarts the context on any advance");
            verify(logger).logServerInfo(contains("copy skipped"));
        }

        @Test
        @DisplayName("a changed WAR is re-copied to webapps")
        void changedWarCopied(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "new-war-bytes");
            Path target = webapps.resolve("app.war");
            Files.writeString(target, "old");
            Files.setLastModifiedTime(target, FileTime.fromMillis(100_000L));
            Files.setLastModifiedTime(source, FileTime.fromMillis(200_000L));

            TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger, FRESH);

            assertEquals("new-war-bytes", Files.readString(target));
            verify(logger).logServerInfo(contains("Re-deployed WAR"));
        }

        @Test
        @DisplayName("a missing deployed copy is always written")
        void missingTargetCopied(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "new-war-bytes");

            TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger, FRESH);

            assertEquals("new-war-bytes", Files.readString(webapps.resolve("app.war")));
        }

        @Test
        @DisplayName("exploded deployments are outside the WAR loop's scope")
        void explodedSkipped(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path exploded = Files.createDirectories(tmp.resolve("app-1.0.0"));

            TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(exploded, "/app", true)), webapps, logger, FRESH);

            assertFalse(Files.exists(webapps.resolve("app.war")));
            verifyNoInteractions(logger);
        }
    }

    @Nested
    @DisplayName("redeployWarDeployment — WAR branch of the explicit Redeploy action")
    class RedeployWarDeploymentTests {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        @Test
        @DisplayName("explicit Redeploy of an unchanged WAR skips the copy — sessions survive")
        void unchangedWarSkipped(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "same-size-A");
            Path target = webapps.resolve("app.war");
            Files.writeString(target, "same-size-B");
            Files.setLastModifiedTime(source, FileTime.fromMillis(100_000L));
            Files.setLastModifiedTime(target, FileTime.fromMillis(200_000L));
            Deployment dep = new ExternalFileDeployment(source, "/app", false);

            TomcatApplicationUpdater.redeployWarDeployment(dep, source, webapps, logger, FRESH);

            assertEquals("same-size-B", Files.readString(target));
            assertEquals(200_000L, Files.getLastModifiedTime(target).toMillis());
            verify(logger).logServerInfo(contains("Rebuild it with the build tool"));
        }

        @Test
        @DisplayName("a rebuilt WAR is copied and reported")
        void rebuiltWarCopied(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "rebuilt-war-bytes");
            Path target = webapps.resolve("app.war");
            Files.writeString(target, "old");
            Files.setLastModifiedTime(target, FileTime.fromMillis(100_000L));
            Files.setLastModifiedTime(source, FileTime.fromMillis(200_000L));
            Deployment dep = new ExternalFileDeployment(source, "/app", false);

            TomcatApplicationUpdater.redeployWarDeployment(dep, source, webapps, logger, FRESH);

            assertEquals("rebuilt-war-bytes", Files.readString(target));
            verify(logger).logServerInfo(contains("Redeployed WAR"));
        }
    }

    @Nested
    @DisplayName("stale-WAR blocking — a stale WAR is never deployed silently")
    class StaleWarBlocking {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        private static DeploymentStaleness.Verdict staleVerdict(Path tmp) {
            return DeploymentStaleness.Verdict.stale(
                    "web-module", tmp.resolve("out/classes/A.class"), 60_000L);
        }

        @Test
        @DisplayName("a stale WAR is blocked: not copied, warned, and reported to the caller")
        void staleWarBlockedInUpdateLoop(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "stale-war-bytes");
            Deployment dep = new ExternalFileDeployment(source, "/app", false);

            List<Deployment> blocked = TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(dep), webapps, logger, d -> staleVerdict(tmp));

            assertEquals(List.of(dep), blocked);
            assertFalse(Files.exists(webapps.resolve("app.war")),
                    "a stale WAR must not reach webapps");
            verify(logger).logServerWarning(contains("Stale WAR not deployed"));
        }

        @Test
        @DisplayName("the verdict is not evaluated for a WAR that would not be copied anyway")
        void unchangedWarNeverEvaluated(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "same-size-A");
            Path target = webapps.resolve("app.war");
            Files.writeString(target, "same-size-B");
            Files.setLastModifiedTime(source, FileTime.fromMillis(100_000L));
            Files.setLastModifiedTime(target, FileTime.fromMillis(200_000L));

            List<Deployment> blocked = TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger,
                    d -> fail("up-to-date check must run BEFORE the staleness verdict"));

            assertTrue(blocked.isEmpty());
        }

        @Test
        @DisplayName("Deploy Anyway semantics: re-running the blocked set with fresh verdicts copies it")
        void deployAnywayCopiesBlockedSet(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "stale-war-bytes");
            Deployment dep = new ExternalFileDeployment(source, "/app", false);

            List<Deployment> blocked = TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(dep), webapps, logger, d -> staleVerdict(tmp));
            List<Deployment> secondRound = TomcatApplicationUpdater.redeployWarArtifactsInto(
                    blocked, webapps, logger, FRESH);

            assertTrue(secondRound.isEmpty());
            assertEquals("stale-war-bytes", Files.readString(webapps.resolve("app.war")),
                    "the override deploys the WAR as last built");
        }

        @Test
        @DisplayName("explicit Redeploy blocks a stale WAR the same way")
        void redeployWarDeploymentBlocksStale(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "stale-war-bytes");
            Deployment dep = new ExternalFileDeployment(source, "/app", false);

            boolean blocked = TomcatApplicationUpdater.redeployWarDeployment(
                    dep, source, webapps, logger, d -> staleVerdict(tmp));

            assertTrue(blocked);
            assertFalse(Files.exists(webapps.resolve("app.war")));
            verify(logger).logServerWarning(contains("Stale WAR not deployed"));
        }

        @Test
        @DisplayName("an UNKNOWN verdict never blocks — what cannot be judged deploys as before")
        void unknownVerdictDeploys(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path source = tmp.resolve("app-1.0.0.war");
            Files.writeString(source, "war-bytes");

            List<Deployment> blocked = TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger,
                    d -> DeploymentStaleness.Verdict.unknown());

            assertTrue(blocked.isEmpty());
            assertEquals("war-bytes", Files.readString(webapps.resolve("app.war")));
        }
    }

    @Nested
    @DisplayName("notifyBlockedStaleWarDeployments — session-gated Deploy Anyway balloon")
    class NotifyBlockedStaleWars {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        private static final class RecordingNotifier implements TomcatApplicationUpdater.UpdateNotifier {
            final List<String> actionLabels = new ArrayList<>();
            Runnable lastAction;

            @Override
            public void infoWithAction(@NotNull String title, @NotNull String content,
                                       @NotNull String actionLabel, @NotNull Runnable action) {
                actionLabels.add(actionLabel);
                lastAction = action;
            }

            @Override
            public void warning(@NotNull String title, @NotNull String content) {
                fail("blocked-stale-WAR notification must carry the Deploy Anyway action");
            }
        }

        @Test
        @DisplayName("balloon fires once per session with Deploy Anyway wired through")
        void balloonOncePerSessionWithAction(@TempDir Path tmp) {
            Deployment dep = new ExternalFileDeployment(tmp.resolve("app-1.0.0.war"), "/app", false);
            SessionNotificationGate gate = new SessionNotificationGate();
            RecordingNotifier notifier = new RecordingNotifier();
            AtomicBoolean overrideRan = new AtomicBoolean();

            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(dep), logger, gate, "scope-1", notifier, () -> overrideRan.set(true));
            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(dep), logger, gate, "scope-1", notifier, () -> overrideRan.set(true));

            assertEquals(List.of("Deploy Anyway"), notifier.actionLabels,
                    "one balloon per (scope, blocked set) per session");
            notifier.lastAction.run();
            assertTrue(overrideRan.get(), "the balloon action must invoke the injected override");
        }

        @Test
        @DisplayName("a changed blocked set re-arms the balloon")
        void changedSetReArms(@TempDir Path tmp) {
            SessionNotificationGate gate = new SessionNotificationGate();
            RecordingNotifier notifier = new RecordingNotifier();

            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(new ExternalFileDeployment(tmp.resolve("app-1.0.0.war"), "/app", false)),
                    logger, gate, "scope-1", notifier, () -> {});
            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(new ExternalFileDeployment(tmp.resolve("other-2.0.0.war"), "/other", false)),
                    logger, gate, "scope-1", notifier, () -> {});

            assertEquals(2, notifier.actionLabels.size());
        }

        @Test
        @DisplayName("nothing blocked → no balloon")
        void emptySilent() {
            RecordingNotifier notifier = new RecordingNotifier();
            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(), logger, new SessionNotificationGate(), "scope-1", notifier, () -> {});
            assertTrue(notifier.actionLabels.isEmpty());
        }
    }

    @Nested
    @DisplayName("rebuildThenContinue — opt-in build-tool package gate")
    class RebuildThenContinue {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        private static UpdateConfig configWithRebuild(boolean enabled) {
            UpdateConfig config = new UpdateConfig();
            config.setRebuildBeforeRedeploy(enabled);
            return config;
        }

        /** Records rebuild order; modules in {@code failing} report a failed build. */
        private static final class FakeRebuilder implements TomcatApplicationUpdater.RebuildInvoker<String> {
            final List<String> rebuilt = new ArrayList<>();
            final java.util.Set<String> failing = new java.util.HashSet<>();

            @Override
            public void rebuild(@NotNull String module, @NotNull Runnable onSuccess,
                                @NotNull java.util.function.Consumer<String> onFailure) {
                rebuilt.add(module);
                if (failing.contains(module)) {
                    onFailure.accept("package failed for " + module);
                } else {
                    onSuccess.run();
                }
            }
        }

        @Test
        @DisplayName("enabled with a WAR module: rebuild runs BEFORE the deploy continuation")
        void rebuildBeforeContinuation() {
            List<String> order = new ArrayList<>();
            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true), () -> List.of("web-module"),
                    (module, onSuccess, onFailure) -> { order.add("rebuild:" + module); onSuccess.run(); },
                    logger, () -> order.add("deploy"));

            assertEquals(List.of("rebuild:web-module", "deploy"), order);
        }

        @Test
        @DisplayName("rebuild failure: continuation NOT invoked, abort error logged")
        void failureStopsDeploy() {
            FakeRebuilder rebuilder = new FakeRebuilder();
            rebuilder.failing.add("web-module");
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true), () -> List.of("web-module"),
                    rebuilder, logger, () -> deployed.set(true));

            assertFalse(deployed.get(), "nothing may deploy after a failed build");
            verify(logger).logServerError(contains("nothing deployed"));
        }

        @Test
        @DisplayName("disabled (the config default): continuation direct, module resolution never runs")
        void disabledBypasses() {
            FakeRebuilder rebuilder = new FakeRebuilder();
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(new UpdateConfig(),
                    () -> fail("module resolution must be lazy — never evaluated when the option is off"),
                    rebuilder, logger, () -> deployed.set(true));

            assertTrue(deployed.get());
            assertTrue(rebuilder.rebuilt.isEmpty());
            verifyNoInteractions(logger);
        }

        @Test
        @DisplayName("no modules resolved: continuation direct, no warning")
        void noModulesBypasses() {
            FakeRebuilder rebuilder = new FakeRebuilder();
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true), List::of,
                    rebuilder, logger, () -> deployed.set(true));

            assertTrue(deployed.get());
            assertTrue(rebuilder.rebuilt.isEmpty());
            verifyNoInteractions(logger);
        }

        @Test
        @DisplayName("enabled but no rebuilder: warning logged, continuation direct")
        void noRebuilderWarnsAndContinues() {
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true), () -> List.of("web-module"),
                    null, logger, () -> deployed.set(true));

            assertTrue(deployed.get(), "missing integration must not block the redeploy");
            verify(logger).logServerWarning(contains("Maven integration is unavailable"));
        }

        @Test
        @DisplayName("multiple deployments of the same module: one rebuild")
        void duplicatesDeduped() {
            FakeRebuilder rebuilder = new FakeRebuilder();
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true),
                    () -> List.of("web-module", "web-module", "app-module"),
                    rebuilder, logger, () -> deployed.set(true));

            assertEquals(List.of("web-module", "app-module"), rebuilder.rebuilt,
                    "each module is packaged exactly once, in first-seen order");
            assertTrue(deployed.get());
        }

        @Test
        @DisplayName("first failure stops the chain — later modules are not built")
        void failureStopsChain() {
            FakeRebuilder rebuilder = new FakeRebuilder();
            rebuilder.failing.add("web-module");
            AtomicBoolean deployed = new AtomicBoolean();

            TomcatApplicationUpdater.rebuildThenContinue(configWithRebuild(true),
                    () -> List.of("web-module", "app-module"),
                    rebuilder, logger, () -> deployed.set(true));

            assertEquals(List.of("web-module"), rebuilder.rebuilt);
            assertFalse(deployed.get());
        }
    }

    @Nested
    @DisplayName("notifyBlockedStaleWarDeployments — Rebuild and Deploy remedy")
    class NotifyBlockedStaleWarsRebuildRemedy {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        /** Records both balloon shapes so the offered-actions set is assertable. */
        private static final class RecordingNotifier implements TomcatApplicationUpdater.UpdateNotifier {
            final List<List<String>> offeredActionSets = new ArrayList<>();
            Runnable firstAction;
            Runnable secondAction;

            @Override
            public void infoWithAction(@NotNull String title, @NotNull String content,
                                       @NotNull String actionLabel, @NotNull Runnable action) {
                offeredActionSets.add(List.of(actionLabel));
                firstAction = action;
                secondAction = null;
            }

            @Override
            public void infoWithActions(@NotNull String title, @NotNull String content,
                                        @NotNull String actionLabel, @NotNull Runnable action,
                                        @NotNull String secondActionLabel, @NotNull Runnable second) {
                offeredActionSets.add(List.of(actionLabel, secondActionLabel));
                firstAction = action;
                secondAction = second;
            }

            @Override
            public void warning(@NotNull String title, @NotNull String content) {
                fail("blocked-stale-WAR notification must carry actions");
            }
        }

        @Test
        @DisplayName("rebuilder available: both actions offered, remedy first")
        void bothActionsOffered(@TempDir Path tmp) {
            Deployment dep = new ExternalFileDeployment(tmp.resolve("app-1.0.0.war"), "/app", false);
            RecordingNotifier notifier = new RecordingNotifier();

            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(dep), logger, new SessionNotificationGate(), "scope-1",
                    notifier, () -> {}, () -> {});

            assertEquals(List.of(List.of("Rebuild and Deploy", "Deploy Anyway")),
                    notifier.offeredActionSets);
        }

        @Test
        @DisplayName("no rebuilder (null remedy): only Deploy Anyway")
        void deployAnywayOnlyWithoutRebuilder(@TempDir Path tmp) {
            Deployment dep = new ExternalFileDeployment(tmp.resolve("app-1.0.0.war"), "/app", false);
            RecordingNotifier notifier = new RecordingNotifier();

            TomcatApplicationUpdater.notifyBlockedStaleWarDeployments(
                    List.of(dep), logger, new SessionNotificationGate(), "scope-1",
                    notifier, () -> {}, null);

            assertEquals(List.of(List.of("Deploy Anyway")), notifier.offeredActionSets);
        }

        @Test
        @DisplayName("buildRebuildAndDeployRemedy: no invoker → no second button")
        void noInvokerNoRemedy() {
            assertNull(TomcatApplicationUpdater.buildRebuildAndDeployRemedy(
                    null, List.of("web-module"), logger, () -> fail("must not run")));
        }

        @Test
        @DisplayName("buildRebuildAndDeployRemedy: nothing rebuildable → no second button")
        void noModulesNoRemedy() {
            assertNull(TomcatApplicationUpdater.buildRebuildAndDeployRemedy(
                    (m, ok, err) -> fail("must not run"), List.of(), logger,
                    () -> fail("must not run")));
        }

        @Test
        @DisplayName("buildRebuildAndDeployRemedy: rebuild chain completes, THEN the re-verdict deploy runs")
        void remedyRebuildsThenDeploys() {
            List<String> order = new ArrayList<>();

            Runnable remedy = TomcatApplicationUpdater.buildRebuildAndDeployRemedy(
                    (module, onSuccess, onFailure) -> { order.add("rebuild:" + module); onSuccess.run(); },
                    List.of("web-module", "app-module"), logger,
                    () -> order.add("re-verdict-deploy"));

            assertNotNull(remedy);
            remedy.run();
            assertEquals(List.of("rebuild:web-module", "rebuild:app-module", "re-verdict-deploy"), order);
        }

        @Test
        @DisplayName("buildRebuildAndDeployRemedy: a failed rebuild never reaches the deploy step")
        void remedyFailureStopsBeforeDeploy() {
            Runnable remedy = TomcatApplicationUpdater.buildRebuildAndDeployRemedy(
                    (module, onSuccess, onFailure) -> onFailure.accept("package failed"),
                    List.of("web-module"), logger,
                    () -> fail("nothing may deploy after a failed build"));

            assertNotNull(remedy);
            remedy.run();
            verify(logger).logServerError(contains("nothing deployed"));
        }
    }

    @Nested
    @DisplayName("rebuildableWarModules — deployment→module selection for the rebuild chain")
    class RebuildableWarModules {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        private static com.intellij.openapi.module.Module module(String name) {
            com.intellij.openapi.module.Module m = mock(com.intellij.openapi.module.Module.class);
            org.mockito.Mockito.when(m.getName()).thenReturn(name);
            return m;
        }

        @Test
        @DisplayName("exploded deployments never enter the rebuild set")
        void explodedSkipped(@TempDir Path tmp) throws Exception {
            Deployment exploded = new ExternalFileDeployment(
                    Files.createDirectories(tmp.resolve("app-1.0.0")), "/app", true);

            assertTrue(TomcatApplicationUpdater.rebuildableWarModules(
                    List.of(exploded),
                    d -> fail("an exploded deployment must not be resolved"),
                    m -> true, logger).isEmpty());
        }

        @Test
        @DisplayName("a WAR deployment that resolves to no module is skipped")
        void unresolvedSkipped(@TempDir Path tmp) throws Exception {
            Deployment war = new ExternalFileDeployment(
                    Files.writeString(tmp.resolve("app-1.0.0.war"), "war"), "/app", false);

            assertTrue(TomcatApplicationUpdater.rebuildableWarModules(
                    List.of(war), d -> null, m -> true, logger).isEmpty());
        }

        @Test
        @DisplayName("a module the build tool cannot package is warned about and excluded")
        void unpackageableWarnedAndExcluded(@TempDir Path tmp) throws Exception {
            Deployment war = new ExternalFileDeployment(
                    Files.writeString(tmp.resolve("app-1.0.0.war"), "war"), "/app", false);
            com.intellij.openapi.module.Module m = module("web-module");

            List<com.intellij.openapi.module.Module> out =
                    TomcatApplicationUpdater.rebuildableWarModules(
                            List.of(war), d -> m, mod -> false, logger);

            assertTrue(out.isEmpty());
            verify(logger).logServerWarning(contains("web-module"));
        }

        @Test
        @DisplayName("no rebuilder (null capability): raw modules returned so the gate can name the gap")
        void nullCapabilityReturnsRaw(@TempDir Path tmp) throws Exception {
            Deployment war = new ExternalFileDeployment(
                    Files.writeString(tmp.resolve("app-1.0.0.war"), "war"), "/app", false);
            com.intellij.openapi.module.Module m = module("web-module");

            assertEquals(List.of(m), TomcatApplicationUpdater.rebuildableWarModules(
                    List.of(war), d -> m, null, logger));
            verifyNoInteractions(logger);
        }
    }

    @Nested
    @DisplayName("notifySyncGaps — console + session-gated balloon wiring")
    class NotifySyncGaps {

        /** Records fired balloons so the wiring itself is assertable. */
        private static final class RecordingNotifier implements TomcatApplicationUpdater.UpdateNotifier {
            final List<String> warnings = new ArrayList<>();
            final List<String> infoLabels = new ArrayList<>();
            Runnable lastAction;

            @Override
            public void infoWithAction(@NotNull String title, @NotNull String content,
                                       @NotNull String actionLabel, @NotNull Runnable action) {
                infoLabels.add(actionLabel);
                lastAction = action;
            }

            @Override
            public void warning(@NotNull String title, @NotNull String content) {
                warnings.add(title + "|" + content);
            }
        }

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);
        private final SessionNotificationGate gate = new SessionNotificationGate();
        private final RecordingNotifier notifier = new RecordingNotifier();

        private final Deployment externalA =
                new ExternalFileDeployment(Path.of("/projects/X/app-1.0.0"), "/app", true);
        private final Deployment externalB =
                new ExternalFileDeployment(Path.of("/projects/X/web-module.war"), "/web", false);

        private WarToExplodedQuickFix.FixCandidate candidateFor(Deployment d) {
            return new WarToExplodedQuickFix.FixCandidate(
                    d, Path.of("/projects/X/web-module"), "web-module");
        }

        private void run(List<Deployment> deployments,
                         List<WarToExplodedQuickFix.FixCandidate> candidates,
                         Runnable reclaimAction) {
            TomcatApplicationUpdater.notifySyncGaps(
                    deployments, candidates, logger, gate, "scope-1", notifier, reclaimAction);
        }

        @Test
        @DisplayName("unsyncable external → console warning every time, balloon once per session")
        void unsyncableExternalConsoleAndGatedBalloon() {
            run(List.of(externalA), List.of(), () -> {});
            run(List.of(externalA), List.of(), () -> {});

            verify(logger, times(2)).logServerWarning(
                    contains("Hot reload (class/resource sync) is OFF"));
            assertEquals(1, notifier.warnings.size(),
                    "an identical deployment set must not re-balloon within a session");
            assertTrue(notifier.infoLabels.isEmpty(),
                    "no reclaim candidates → no reclaim balloon");
        }

        @Test
        @DisplayName("a changed unsyncable set re-arms the balloon")
        void changedSetRenotifies() {
            run(List.of(externalA), List.of(), () -> {});
            run(List.of(externalA, externalB), List.of(), () -> {});

            assertEquals(2, notifier.warnings.size(),
                    "a changed deployment set is new information and must re-notify");
        }

        @Test
        @DisplayName("reclaim candidates → console mapping every time, gated action balloon once")
        void reclaimOfferGatedWithAction() {
            AtomicBoolean applied = new AtomicBoolean();
            run(List.of(externalB), List.of(candidateFor(externalB)), () -> applied.set(true));
            run(List.of(externalB), List.of(candidateFor(externalB)), () -> applied.set(true));

            verify(logger, times(2)).logServerWarning(contains("Hot-reload reclaim available"));
            assertEquals(List.of("Reclaim Deployment"), notifier.infoLabels,
                    "one gated balloon with the single-candidate action label");
            assertTrue(notifier.warnings.isEmpty(),
                    "a fixable external gets the reclaim offer, not the hot-reload-OFF warning");
            notifier.lastAction.run();
            assertTrue(applied.get(), "the balloon action must be the injected reclaim action");
        }

        @Test
        @DisplayName("no externals, no candidates → fully silent")
        void nothingToReport() {
            run(List.of(), List.of(), () -> {});

            verifyNoInteractions(logger);
            assertTrue(notifier.warnings.isEmpty());
            assertTrue(notifier.infoLabels.isEmpty());
        }
    }
}
