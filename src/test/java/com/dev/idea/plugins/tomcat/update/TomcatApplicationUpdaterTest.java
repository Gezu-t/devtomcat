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
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger);

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
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger);

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
                    List.of(new ExternalFileDeployment(source, "/app", false)), webapps, logger);

            assertEquals("new-war-bytes", Files.readString(webapps.resolve("app.war")));
        }

        @Test
        @DisplayName("exploded deployments are outside the WAR loop's scope")
        void explodedSkipped(@TempDir Path tmp) throws Exception {
            Path webapps = Files.createDirectories(tmp.resolve("webapps"));
            Path exploded = Files.createDirectories(tmp.resolve("app-1.0.0"));

            TomcatApplicationUpdater.redeployWarArtifactsInto(
                    List.of(new ExternalFileDeployment(exploded, "/app", true)), webapps, logger);

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

            TomcatApplicationUpdater.redeployWarDeployment(dep, source, webapps, logger);

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

            TomcatApplicationUpdater.redeployWarDeployment(dep, source, webapps, logger);

            assertEquals("rebuilt-war-bytes", Files.readString(target));
            verify(logger).logServerInfo(contains("Redeployed WAR"));
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
