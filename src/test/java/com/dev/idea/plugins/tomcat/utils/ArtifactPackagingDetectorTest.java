package com.dev.idea.plugins.tomcat.utils;

import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArtifactPackagingDetector")
class ArtifactPackagingDetectorTest {

    @Test
    @DisplayName("resolves WAR packaging from artifact type ID")
    void resolvesWarTypeFromArtifactTypeId() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("war");

        assertFalse(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
    }

    @Test
    @DisplayName("resolves exploded packaging from artifact type ID")
    void resolvesExplodedTypeFromArtifactTypeId() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("exploded");

        assertTrue(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
    }

    @Test
    @DisplayName("Community Edition plain type falls back to name")
    void communityEditionPlainTypeFallsBackToName() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("plain"); // CE artifact type
        when(artifact.getName()).thenReturn("myapp:war exploded");

        assertTrue(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
    }

    @Test
    @DisplayName("Community Edition plain type with directory output resolves exploded")
    void communityEditionPlainTypeWithDirectoryOutput(@TempDir Path tempDir) {
        // A generic type id and a hint-free name carry no packaging signal —
        // the output-path shape (a directory) must decide. This is the shape
        // where an id-only check would wrongly report WAR.
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn("plain");
        when(artifact.getName()).thenReturn("app-1.0.0");
        when(artifact.getOutputFilePath()).thenReturn(tempDir.toString());

        assertTrue(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
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

        assertTrue(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
    }

    @Test
    @DisplayName("falls back to name when artifact type lookup fails")
    void fallsBackToNameWhenArtifactTypeLookupFails() {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenThrow(new RuntimeException("boom"));
        when(artifact.getName()).thenReturn("sample.war");

        assertFalse(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
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

        assertTrue(ArtifactPackagingDetector.resolveExplodedPackaging(artifact));
    }
}
