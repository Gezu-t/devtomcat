package com.dev.idea.plugins.tomcat.utils;

import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProjectArtifactDetector")
class ProjectArtifactDetectorTest {

    @Test
    @DisplayName("isWebArtifact accepts WAR type IDs")
    void isWebArtifactAcceptsWarTypeIds() {
        Artifact artifact = artifact("sample", "war", "WAR");

        assertTrue(ProjectArtifactDetector.isWebArtifact(artifact));
    }

    @Test
    @DisplayName("isWebArtifact falls back to presentable name for community-style artifacts")
    void isWebArtifactFallsBackToPresentableName() {
        Artifact artifact = artifact("sample", "plain", "Web Application: Archive");

        assertTrue(ProjectArtifactDetector.isWebArtifact(artifact));
    }

    @Test
    @DisplayName("isWebArtifact returns false when artifact type lookup fails")
    void isWebArtifactReturnsFalseWhenLookupFails() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getName()).thenReturn("broken");
        when(artifact.getArtifactType()).thenThrow(new RuntimeException("boom"));

        assertFalse(ProjectArtifactDetector.isWebArtifact(artifact));
    }

    private static Artifact artifact(String name, String typeId, String presentableName) {
        Artifact artifact = mock(Artifact.class);
        ArtifactType type = mock(ArtifactType.class);
        when(artifact.getName()).thenReturn(name);
        when(artifact.getArtifactType()).thenReturn(type);
        when(type.getId()).thenReturn(typeId);
        when(type.getPresentableName()).thenReturn(presentableName);
        return artifact;
    }
}
