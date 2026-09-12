package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the "Select Artifacts" pre-launch selection contract that executeTask relies on:
 * empty selection == verify all, a non-empty selection filters by {@link Deployment#getDisplayName()},
 * and an unchecked (unselected) invalid artifact never survives to block the launch. Runs without a
 * platform fixture — {@link ExternalFileDeployment} keys its display name off the file name and its
 * validity off file existence. Also pins the balloon body a blocked launch shows, which has to
 * carry the failing artifacts itself: the launch aborts, so no run console ever opens.
 */
@DisplayName("TomcatBuildArtifactsTaskProvider")
class TomcatBuildArtifactsTaskProviderTest {

    private static Deployment dep(Path p) {
        return new ExternalFileDeployment(p, "/" + p.getFileName(), false);
    }

    @Test
    @DisplayName("empty selection verifies all deployments (guards against an inverted isEmpty check)")
    void emptySelectionVerifiesAll(@TempDir Path dir) throws IOException {
        Deployment a = dep(Files.createFile(dir.resolve("a.war")));
        Deployment b = dep(dir.resolve("missing.war")); // never created -> invalid
        List<Deployment> all = List.of(a, b);

        assertEquals(all, TomcatBuildArtifactsTaskProvider.applyArtifactSelection(all, List.of()));
    }

    @Test
    @DisplayName("non-empty selection returns exactly the selected display names")
    void subsetSelection(@TempDir Path dir) throws IOException {
        Deployment a = dep(Files.createFile(dir.resolve("a.war")));
        Deployment b = dep(Files.createFile(dir.resolve("b.war")));
        Deployment c = dep(Files.createFile(dir.resolve("c.war")));

        List<Deployment> result = TomcatBuildArtifactsTaskProvider.applyArtifactSelection(
                List.of(a, b, c), List.of("a.war", "c.war"));

        assertEquals(List.of(a, c), result);
    }

    @Test
    @DisplayName("an invalid artifact NOT in the selection is excluded (cannot block the launch)")
    void invalidOutsideSelectionExcluded(@TempDir Path dir) throws IOException {
        Deployment good = dep(Files.createFile(dir.resolve("good.war")));
        Deployment badUnselected = dep(dir.resolve("bad.war")); // invalid

        List<Deployment> result = TomcatBuildArtifactsTaskProvider.applyArtifactSelection(
                List.of(good, badUnselected), List.of("good.war"));

        assertEquals(List.of(good), result);
    }

    @Test
    @DisplayName("an invalid artifact IN the selection is kept (so executeTask blocks on it)")
    void invalidInsideSelectionKept(@TempDir Path dir) {
        Deployment badSelected = dep(dir.resolve("bad.war")); // invalid

        List<Deployment> result = TomcatBuildArtifactsTaskProvider.applyArtifactSelection(
                List.of(badSelected), List.of("bad.war"));

        assertEquals(List.of(badSelected), result);
        assertFalse(result.get(0).isValid(), "selected invalid artifact is kept so the launch is blocked");
    }

    @Test
    @DisplayName("selection keys on getDisplayName — a mismatched name domain silently drops the deployment")
    void selectionKeysOnDisplayName(@TempDir Path dir) throws IOException {
        // Regression: the Before-Launch task's selection names must be derived from
        // Deployment.getDisplayName() (what this filter matches), not a different name
        // domain. A normalized module-backed deployment reports its MODULE name here,
        // so the editor must persist that same name — selecting it by any other name
        // (e.g. a legacy artifact name) silently drops it from pre-launch assembly and
        // reproduces the "no artifact" symptom.
        Deployment dep = dep(Files.createFile(dir.resolve("web-module")));
        assertEquals("web-module", dep.getDisplayName());

        assertEquals(List.of(dep), TomcatBuildArtifactsTaskProvider.applyArtifactSelection(
                List.of(dep), List.of("web-module")), "selected by its display name → kept");
        assertEquals(0, TomcatBuildArtifactsTaskProvider.applyArtifactSelection(
                List.of(dep), List.of("web-module:war exploded")).size(),
                "selected by a mismatched name domain → wrongly dropped");
    }

    @Test
    @DisplayName("a blocked launch names the failing artifacts in the balloon")
    void blockingErrorsAreListed() {
        String body = TomcatBuildArtifactsTaskProvider.blockingErrorsMessage(
                List.of("'web' is missing WEB-INF/ at /p/target/web", "'api' is a file, not a directory"));

        assertTrue(body.contains("'web' is missing WEB-INF/ at /p/target/web"), body);
        assertTrue(body.contains("'api' is a file, not a directory"), body);
        assertTrue(body.endsWith("Build the project, then launch again."), body);
    }

    @Test
    @DisplayName("past the cap the remaining errors are counted, not listed")
    void blockingErrorsAreCapped() {
        String body = TomcatBuildArtifactsTaskProvider.blockingErrorsMessage(
                List.of("one", "two", "three", "four", "five"));

        assertTrue(body.contains("one") && body.contains("three"), body);
        assertFalse(body.contains("four"), "past the cap the errors are counted, not listed");
        assertTrue(body.contains("and 2 more"), body);
    }
}
