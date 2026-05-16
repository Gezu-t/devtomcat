package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArtifactStalenessDetector}'s defensive branches —
 * the cases where we want to return an empty list quickly without ever
 * touching the project's VFS or module roots.
 *
 * <p>The deep VFS-walk path needs a real {@code Project} fixture with
 * {@code ModuleManager}/{@code ModuleRootManager} set up; that lives in
 * platform integration tests, not here. These tests guard the contracts
 * that the detector must satisfy under the conditions a Mockito unit test
 * can fabricate: empty inputs, disposed project, missing/blank paths.
 */
class ArtifactStalenessDetectorTest {

    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("returns empty list on degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("empty artifact list")
        void emptyArtifactList() {
            when(project.isDisposed()).thenReturn(false);
            List<ArtifactStalenessDetector.StaleReport> reports =
                    ArtifactStalenessDetector.findStaleArtifacts(project, List.of());
            assertTrue(reports.isEmpty());
        }

        @Test
        @DisplayName("disposed project")
        void disposedProject() {
            when(project.isDisposed()).thenReturn(true);
            DeploymentArtifact artifact = new DeploymentArtifact("foo", "/tmp/nope.war",
                    DeploymentArtifact.TYPE_WAR);
            List<ArtifactStalenessDetector.StaleReport> reports =
                    ArtifactStalenessDetector.findStaleArtifacts(project, List.of(artifact));
            assertTrue(reports.isEmpty());
        }

        @Test
        @DisplayName("artifact with blank path")
        void artifactWithBlankPath() {
            when(project.isDisposed()).thenReturn(false);
            DeploymentArtifact artifact = new DeploymentArtifact("foo", "   ",
                    DeploymentArtifact.TYPE_WAR);
            List<ArtifactStalenessDetector.StaleReport> reports =
                    ArtifactStalenessDetector.findStaleArtifacts(project, List.of(artifact));
            assertTrue(reports.isEmpty());
        }

        @Test
        @DisplayName("artifact path that does not exist on disk")
        void artifactPathDoesNotExist() {
            when(project.isDisposed()).thenReturn(false);
            DeploymentArtifact artifact = new DeploymentArtifact("foo",
                    "/tmp/devtomcat-this-path-must-not-exist-" + System.nanoTime() + ".war",
                    DeploymentArtifact.TYPE_WAR);
            List<ArtifactStalenessDetector.StaleReport> reports =
                    ArtifactStalenessDetector.findStaleArtifacts(project, List.of(artifact));
            assertTrue(reports.isEmpty(),
                    "missing artifact is the validation pass's concern, not staleness — "
                            + "we should not double-warn");
        }

        @Test
        @DisplayName("artifact list containing nulls is tolerant")
        void nullArtifactInList() {
            when(project.isDisposed()).thenReturn(false);
            List<DeploymentArtifact> mixed = new java.util.ArrayList<>();
            mixed.add(null);
            List<ArtifactStalenessDetector.StaleReport> reports =
                    ArtifactStalenessDetector.findStaleArtifacts(project, mixed);
            assertNotNull(reports);
            assertTrue(reports.isEmpty());
        }
    }

    @Nested
    @DisplayName("StaleReport record contract")
    class StaleReportContract {

        @Test
        @DisplayName("preserves the three fields passed in")
        void preservesFields() {
            ArtifactStalenessDetector.StaleReport report =
                    new ArtifactStalenessDetector.StaleReport(
                            "myapp:war", "/tmp/myapp.war", "UserService.java");
            assertEquals("myapp:war", report.artifactDisplayName());
            assertEquals("/tmp/myapp.war", report.artifactPath());
            assertEquals("UserService.java", report.exampleSourceFile());
        }
    }
}
