package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Unit tests for {@link ArtifactStructureValidator}. Every test uses real
 * temp-directory fixtures so the filesystem-level checks are exercised
 * end-to-end — these are the cases that drive the user-visible behaviour.
 */
class ArtifactStructureValidatorTest {

    @Nested
    @DisplayName("exploded artifact validation")
    class ExplodedArtifact {

        @Test
        @DisplayName("valid exploded webapp with WEB-INF/ passes")
        void validExplodedPasses(@TempDir Path tempDir) throws IOException {
            Path webInf = Files.createDirectories(tempDir.resolve("WEB-INF"));
            Files.createDirectory(webInf.resolve("classes"));
            Files.writeString(webInf.resolve("classes").resolve("Foo.class"), "");

            DeploymentArtifact artifact = new DeploymentArtifact("myapp",
                    tempDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertFalse(result.hasBlockingErrors());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("missing WEB-INF/ blocks the launch")
        void missingWebInfBlocks(@TempDir Path tempDir) {
            // tempDir exists but contains nothing — no WEB-INF/
            DeploymentArtifact artifact = new DeploymentArtifact("myapp",
                    tempDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertTrue(result.hasBlockingErrors());
            assertEquals(1, result.blockingErrors().size());
            String msg = result.blockingErrors().get(0);
            assertTrue(msg.contains("myapp"));
            assertTrue(msg.contains("WEB-INF"));
            // Error message should suggest the fix
            assertTrue(msg.toLowerCase().contains("build")
                    || msg.toLowerCase().contains("package")
                    || msg.toLowerCase().contains("artifacts"),
                    "expected a build-related hint in the error: " + msg);
        }

        @Test
        @DisplayName("empty WEB-INF/classes/ warns but does not block")
        void emptyClassesWarns(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("WEB-INF").resolve("classes"));
            // classes/ exists but is empty

            DeploymentArtifact artifact = new DeploymentArtifact("myapp",
                    tempDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertFalse(result.hasBlockingErrors(),
                    "empty classes/ should not block — some apps put everything in WEB-INF/lib");
            assertTrue(result.hasWarnings());
            assertTrue(result.warnings().get(0).contains("classes"));
        }

        @Test
        @DisplayName("WEB-INF/ exists, no classes/ at all — passes (some legacy apps)")
        void noClassesDirAtAllPasses(@TempDir Path tempDir) throws IOException {
            Path webInf = Files.createDirectories(tempDir.resolve("WEB-INF"));
            Files.createDirectory(webInf.resolve("lib"));
            Files.writeString(webInf.resolve("web.xml"), "<web-app/>");
            // No classes/ directory at all — that's fine for some apps

            DeploymentArtifact artifact = new DeploymentArtifact("myapp",
                    tempDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertFalse(result.hasBlockingErrors());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("exploded path that is a file (not a directory) blocks")
        void explodedPathThatIsAFileBlocks(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("not-a-directory.war");
            Files.writeString(file, "PK");

            DeploymentArtifact artifact = new DeploymentArtifact("myapp",
                    file.toString(), DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertTrue(result.hasBlockingErrors());
            String msg = result.blockingErrors().get(0);
            assertTrue(msg.contains("myapp"));
            assertTrue(msg.toLowerCase().contains("directory") || msg.toLowerCase().contains("war"));
        }
    }

    @Nested
    @DisplayName("WAR artifact validation")
    class WarArtifact {

        @Test
        @DisplayName("valid .war file passes")
        void validWarFilePasses(@TempDir Path tempDir) throws IOException {
            Path war = tempDir.resolve("myapp.war");
            Files.writeString(war, "PK fake zip bytes");

            DeploymentArtifact artifact = new DeploymentArtifact("myapp:war",
                    war.toString(), DeploymentArtifact.TYPE_WAR);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertFalse(result.hasBlockingErrors());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("WAR path pointing at a directory blocks")
        void warPathPointingAtDirectoryBlocks(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("WEB-INF"));

            DeploymentArtifact artifact = new DeploymentArtifact("myapp:war",
                    tempDir.toString(), DeploymentArtifact.TYPE_WAR);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            assertTrue(result.hasBlockingErrors());
            String msg = result.blockingErrors().get(0);
            assertTrue(msg.contains("myapp"));
            assertTrue(msg.toLowerCase().contains("directory"));
            assertTrue(msg.toLowerCase().contains("exploded") || msg.toLowerCase().contains(".war"),
                    "expected a 'switch type to Exploded' hint: " + msg);
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("empty artifact list yields a clean result")
        void emptyList() {
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of());
            assertFalse(result.hasBlockingErrors());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("artifact path that does not exist is skipped (handled by existence check)")
        void nonExistentPathSkipped(@TempDir Path tempDir) {
            DeploymentArtifact artifact = new DeploymentArtifact("ghost",
                    tempDir.resolve("does-not-exist").toString(),
                    DeploymentArtifact.TYPE_EXPLODED);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(artifact));

            // Structure check defers to the upstream existence check — no double-warning.
            assertFalse(result.hasBlockingErrors());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("null artifact in list is tolerated")
        void nullArtifact() {
            List<DeploymentArtifact> list = new java.util.ArrayList<>();
            list.add(null);
            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(list);
            assertFalse(result.hasBlockingErrors());
        }
    }

    @Nested
    @DisplayName("multi-artifact reporting")
    class MultiArtifact {

        @Test
        @DisplayName("collects errors from every offending artifact")
        void multipleErrorsCollected(@TempDir Path tempDir) throws IOException {
            // Artifact 1: missing WEB-INF/
            Path a = Files.createDirectory(tempDir.resolve("a"));
            DeploymentArtifact art1 = new DeploymentArtifact("alpha",
                    a.toString(), DeploymentArtifact.TYPE_EXPLODED);

            // Artifact 2: WAR pointing at directory
            Path b = Files.createDirectory(tempDir.resolve("b"));
            DeploymentArtifact art2 = new DeploymentArtifact("beta",
                    b.toString(), DeploymentArtifact.TYPE_WAR);

            ArtifactStructureValidator.Result result =
                    ArtifactStructureValidator.validate(List.of(art1, art2));

            assertEquals(2, result.blockingErrors().size());
            assertTrue(result.blockingErrors().stream().anyMatch(m -> m.contains("alpha")));
            assertTrue(result.blockingErrors().stream().anyMatch(m -> m.contains("beta")));
        }
    }
}
