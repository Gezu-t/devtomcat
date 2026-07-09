package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.ModulePointer;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactPointer;
import com.intellij.packaging.artifacts.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adapter covering only the directions / cases we can exercise without
 * a real {@code ArtifactPointerManager} / {@code ModulePointerManager}
 * (those are project services that need a platform fixture). The
 * EXTERNAL leg of {@code toTyped} is service-free and gets full
 * coverage; ARTIFACT / MODULE cases of {@code toTyped} are exercised
 * end-to-end in Phase 2 integration tests once call sites are wired.
 */
class DeploymentAdapterTest {

    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("toTyped — legacy → new")
    class ToTyped {

        @Test
        @DisplayName("EXTERNAL source produces ExternalFileDeployment with exploded flag preserved")
        void externalRoundTrip() {
            DeploymentArtifact legacy = new DeploymentArtifact(
                    "my.war", "/abs/path/my.war", DeploymentArtifact.TYPE_WAR);
            legacy.setSource(DeploymentArtifact.Source.EXTERNAL);
            legacy.setContextPath("/foo");

            Deployment typed = DeploymentAdapter.toTyped(project, legacy);

            ExternalFileDeployment e = assertInstanceOf(ExternalFileDeployment.class, typed);
            assertEquals(Path.of("/abs/path/my.war"), e.getExternalPath());
            assertEquals("/foo", e.getContextPath());
            assertFalse(e.isExploded());
        }

        @Test
        @DisplayName("EXTERNAL with TYPE_EXPLODED maps exploded=true")
        void externalExploded() {
            DeploymentArtifact legacy = new DeploymentArtifact(
                    "dir", "/abs/dir", DeploymentArtifact.TYPE_EXPLODED);
            legacy.setSource(DeploymentArtifact.Source.EXTERNAL);

            Deployment typed = DeploymentAdapter.toTyped(project, legacy);
            assertTrue(((ExternalFileDeployment) typed).isExploded());
        }
    }

    @Nested
    @DisplayName("toLegacy — new → legacy")
    class ToLegacy {

        @Test
        @DisplayName("ArtifactBackedDeployment → INTELLIJ_ARTIFACT source")
        void artifactBacked() {
            ArtifactType type = mock(ArtifactType.class);
            when(type.getId()).thenReturn("exploded-war");
            Artifact artifact = mock(Artifact.class);
            when(artifact.getArtifactType()).thenReturn(type);
            when(artifact.getOutputFilePath()).thenReturn("/out/app");

            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("app");
            when(ptr.getArtifact()).thenReturn(artifact);

            ArtifactBackedDeployment typed = new ArtifactBackedDeployment(ptr, "/ctx");

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("app", legacy.getName());
            assertEquals("/out/app", legacy.getPath());
            assertEquals(DeploymentArtifact.TYPE_EXPLODED, legacy.getType());
            assertEquals("/ctx", legacy.getContextPath());
            assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT, legacy.getSource());
        }

        @Test
        @DisplayName("ArtifactBackedDeployment with stale pointer → empty path string")
        void artifactBackedStalePointer() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("ghost");
            when(ptr.getArtifact()).thenReturn(null);

            ArtifactBackedDeployment typed = new ArtifactBackedDeployment(ptr, "/c");

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("ghost", legacy.getName());
            assertEquals("", legacy.getPath());
            assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT, legacy.getSource());
        }

        @Test
        @DisplayName("ArtifactBackedDeployment with unresolved pointer preserves last-known path and exploded type")
        void artifactBackedUnresolvedFallsBackToLastKnown() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("ghost");
            when(ptr.getArtifact()).thenReturn(null);

            // Pointer does not resolve, but the deployment carried the last-known
            // persisted path + exploded flag: the round trip must not blank the
            // path or flip the packaging to war.
            ArtifactBackedDeployment typed = new ArtifactBackedDeployment(
                    ptr, "/c", "/out/app", /* lastKnownExploded */ true);

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("ghost", legacy.getName());
            assertEquals("/out/app", legacy.getPath());
            assertEquals(DeploymentArtifact.TYPE_EXPLODED, legacy.getType());
            assertEquals(DeploymentArtifact.Source.INTELLIJ_ARTIFACT, legacy.getSource());
        }

        @Test
        @DisplayName("ModuleBackedDeployment round-trips the stored legacy name, not the resolved module name")
        void moduleBackedPreservesLegacyName() {
            ModulePointer ptr = mock(ModulePointer.class);
            when(ptr.getModuleName()).thenReturn("web-mod");

            // Stored display name (e.g. an artifact filename) differs from the
            // resolved module name; toLegacy must echo back the stored name.
            ModuleBackedDeployment typed = new ModuleBackedDeployment(
                    ptr, Path.of("/target/web-mod"), "/ctx", true, "web-mod.war");

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("web-mod.war", legacy.getName());
            assertEquals(DeploymentArtifact.Source.AUTO_DETECTED, legacy.getSource());
        }

        @Test
        @DisplayName("ModuleBackedDeployment → AUTO_DETECTED source")
        void moduleBacked() {
            ModulePointer ptr = mock(ModulePointer.class);
            when(ptr.getModuleName()).thenReturn("web-mod");

            ModuleBackedDeployment typed = new ModuleBackedDeployment(
                    ptr, Path.of("/target/web-mod"), "/ctx", true);

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("web-mod", legacy.getName());
            assertEquals("/target/web-mod", legacy.getPath());
            assertEquals(DeploymentArtifact.TYPE_EXPLODED, legacy.getType());
            assertEquals("/ctx", legacy.getContextPath());
            assertEquals(DeploymentArtifact.Source.AUTO_DETECTED, legacy.getSource());
        }

        @Test
        @DisplayName("ExternalFileDeployment → EXTERNAL source")
        void externalFile() {
            ExternalFileDeployment typed = new ExternalFileDeployment(
                    Path.of("/x/y.war"), "/ctx", false);

            DeploymentArtifact legacy = DeploymentAdapter.toLegacy(typed);
            assertEquals("y.war", legacy.getName());
            assertEquals("/x/y.war", legacy.getPath());
            assertEquals(DeploymentArtifact.TYPE_WAR, legacy.getType());
            assertEquals("/ctx", legacy.getContextPath());
            assertEquals(DeploymentArtifact.Source.EXTERNAL, legacy.getSource());
        }
    }
}
