package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArtifactSelectionHandler")
class ArtifactSelectionHandlerTest {

    @Test
    @DisplayName("resolves WAR type from artifact type ID")
    void resolvesWarTypeFromArtifactTypeId() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("war");

        assertEquals(DeploymentArtifact.TYPE_WAR, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }

    @Test
    @DisplayName("resolves exploded type from artifact type ID")
    void resolvesExplodedTypeFromArtifactTypeId() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("exploded");

        assertEquals(DeploymentArtifact.TYPE_EXPLODED, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }

    @Test
    @DisplayName("Community Edition plain type falls back to name")
    void communityEditionPlainTypeFallsBackToName() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("plain"); // CE artifact type
        when(artifact.getName()).thenReturn("myapp:war exploded");

        assertEquals(DeploymentArtifact.TYPE_EXPLODED, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }

    @Test
    @DisplayName("defaults to exploded when nothing matches")
    void defaultsToExplodedWhenNothingMatches() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("jar");
        when(artifact.getName()).thenReturn("utils");
        when(artifact.getOutputFilePath()).thenReturn(null);

        assertEquals(DeploymentArtifact.TYPE_EXPLODED, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }

    @Test
    @DisplayName("falls back to name when artifact type lookup fails")
    void fallsBackToNameWhenArtifactTypeLookupFails() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenThrow(new RuntimeException("boom"));
        when(artifact.getName()).thenReturn("sample.war");

        assertEquals(DeploymentArtifact.TYPE_WAR, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }

    @Test
    @DisplayName("falls back to output path when artifact type lookup fails")
    void fallsBackToOutputPathWhenArtifactTypeLookupFails() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenThrow(new RuntimeException("boom"));
        when(artifact.getName()).thenReturn("sample");
        when(artifact.getOutputFilePath()).thenReturn(System.getProperty("java.io.tmpdir"));

        assertEquals(DeploymentArtifact.TYPE_EXPLODED, ArtifactSelectionHandler.resolveDeploymentType(artifact));
    }
}
