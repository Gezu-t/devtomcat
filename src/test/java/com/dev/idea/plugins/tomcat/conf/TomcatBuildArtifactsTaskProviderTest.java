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

/**
 * Pins the "Select Artifacts" pre-launch selection contract that executeTask relies on:
 * empty selection == verify all, a non-empty selection filters by {@link Deployment#getDisplayName()},
 * and an unchecked (unselected) invalid artifact never survives to block the launch. Runs without a
 * platform fixture — {@link ExternalFileDeployment} keys its display name off the file name and its
 * validity off file existence.
 */
@DisplayName("TomcatBuildArtifactsTaskProvider.applyArtifactSelection")
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
}
