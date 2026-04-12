package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.packaging.artifacts.Artifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArtifactMatchingUtils")
class ArtifactMatchingUtilsTest {

    @Test
    @DisplayName("exact name match wins before fallbacks")
    void exactNameMatchWinsBeforeFallbacks() {
        DeploymentArtifact deployment = new DeploymentArtifact("portal", "/build/portal", "exploded");
        Artifact exact = artifact("portal", "/other");
        Artifact fallback = artifact("PORTAL", "/build/portal");

        Artifact matched = ArtifactMatchingUtils.findMatchingArtifact(
                new Artifact[]{fallback, exact},
                deployment,
                null
        );

        assertEquals(exact, matched);
    }

    @Test
    @DisplayName("case-insensitive match is used before output path")
    void caseInsensitiveMatchBeforeOutputPath() {
        DeploymentArtifact deployment = new DeploymentArtifact("portal", "/build/portal", "exploded");
        Artifact caseInsensitive = artifact("PORTAL", "/other");
        Artifact pathMatch = artifact("other", "/build/portal");

        Artifact matched = ArtifactMatchingUtils.findMatchingArtifact(
                new Artifact[]{caseInsensitive, pathMatch},
                deployment,
                null
        );

        assertEquals(caseInsensitive, matched);
    }

    @Test
    @DisplayName("output path match is used before base module name")
    void outputPathMatchBeforeBaseModuleName() {
        DeploymentArtifact deployment = new DeploymentArtifact("webapp-one", "/build/portal", "exploded");
        Artifact pathMatch = artifact("renamed-artifact", "/build/portal");
        Artifact baseNameMatch = artifact("webapp-one:war exploded", "/other");

        Artifact matched = ArtifactMatchingUtils.findMatchingArtifact(
                new Artifact[]{pathMatch, baseNameMatch},
                deployment,
                null
        );

        assertEquals(pathMatch, matched);
    }

    @Test
    @DisplayName("base module name is final fallback")
    void baseModuleNameFallback() {
        DeploymentArtifact deployment = new DeploymentArtifact("webapp-one_war_exploded", "", "exploded");
        Artifact baseNameMatch = artifact("webapp-one:war exploded", "/other");

        Artifact matched = ArtifactMatchingUtils.findMatchingArtifact(
                new Artifact[]{baseNameMatch},
                deployment,
                null
        );

        assertEquals(baseNameMatch, matched);
    }

    @Test
    @DisplayName("empty deployment name does not match")
    void emptyDeploymentNameDoesNotMatch() {
        DeploymentArtifact deployment = new DeploymentArtifact("", "/build/portal", "exploded");

        Artifact matched = ArtifactMatchingUtils.findMatchingArtifact(
                new Artifact[]{artifact("portal", "/build/portal")},
                deployment,
                null
        );

        assertNull(matched);
    }

    private static Artifact artifact(String name, String outputPath) {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getName()).thenReturn(name);
        when(artifact.getOutputFilePath()).thenReturn(outputPath);
        return artifact;
    }
}
