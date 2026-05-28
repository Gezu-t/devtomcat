package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WarToExplodedQuickFix")
class WarToExplodedQuickFixTest {

    /**
     * Maven webapp layout: {@code target/<name>.war} alongside
     * {@code target/<name>/WEB-INF/}. Both exist after {@code mvn package}.
     */
    private static Path createMavenWebappLayout(Path target, String warName) throws IOException {
        Path warFile = target.resolve(warName + ".war");
        Files.createFile(warFile);
        Path explodedDir = target.resolve(warName);
        Files.createDirectories(explodedDir.resolve("WEB-INF").resolve("classes"));
        return warFile;
    }

    @Nested
    @DisplayName("deriveExplodedPath")
    class DeriveExplodedPathBehaviour {

        @Test
        @DisplayName("strips .war extension to give sibling directory")
        void stripsWarExtension() {
            Path derived = WarToExplodedQuickFix.deriveExplodedPath("/proj/target/app.war");
            assertNotNull(derived);
            assertEquals("/proj/target/app", derived.toString());
        }

        @Test
        @DisplayName("case-insensitive .WAR extension also handled")
        void caseInsensitiveExtension() {
            Path derived = WarToExplodedQuickFix.deriveExplodedPath("/proj/target/APP.WAR");
            assertNotNull(derived);
            // We keep the casing of the base name, just strip the suffix
            assertEquals("/proj/target/APP", derived.toString());
        }

        @Test
        @DisplayName("version-suffixed WARs keep the version in the directory name")
        void versionSuffixKept() {
            // Maven's <finalName>foo-1.2.3</finalName> produces target/foo-1.2.3.war
            // and target/foo-1.2.3/, so the version must NOT be stripped — both
            // halves of the sibling pair carry it.
            Path derived = WarToExplodedQuickFix.deriveExplodedPath("/proj/target/foo-1.2.3.war");
            assertNotNull(derived);
            assertEquals("/proj/target/foo-1.2.3", derived.toString());
        }

        @Test
        @DisplayName("non-.war path returns null")
        void nonWarReturnsNull() {
            assertNull(WarToExplodedQuickFix.deriveExplodedPath("/proj/target/app.jar"));
            assertNull(WarToExplodedQuickFix.deriveExplodedPath("/proj/target/app"));
        }

        @Test
        @DisplayName("null or empty input returns null")
        void nullOrEmpty() {
            assertNull(WarToExplodedQuickFix.deriveExplodedPath(null));
            assertNull(WarToExplodedQuickFix.deriveExplodedPath(""));
        }

        @Test
        @DisplayName("bare \".war\" with no base name returns null")
        void bareWarSuffixReturnsNull() {
            // Defensive: ".war" has length 4 but no base part — stripping gives empty
            assertNull(WarToExplodedQuickFix.deriveExplodedPath(".war"));
        }
    }

    @Nested
    @DisplayName("isExplodedWebapp")
    class IsExplodedWebappBehaviour {

        @Test
        @DisplayName("true when directory contains WEB-INF/")
        void hasWebInf(@TempDir Path tempDir) throws IOException {
            Path dir = tempDir.resolve("webapp");
            Files.createDirectories(dir.resolve("WEB-INF"));
            assertTrue(WarToExplodedQuickFix.isExplodedWebapp(dir));
        }

        @Test
        @DisplayName("false when directory exists but no WEB-INF/")
        void directoryWithoutWebInf(@TempDir Path tempDir) throws IOException {
            Path dir = tempDir.resolve("not-a-webapp");
            Files.createDirectories(dir);
            assertFalse(WarToExplodedQuickFix.isExplodedWebapp(dir));
        }

        @Test
        @DisplayName("false when directory does not exist")
        void missingDirectory(@TempDir Path tempDir) {
            Path dir = tempDir.resolve("does-not-exist");
            assertFalse(WarToExplodedQuickFix.isExplodedWebapp(dir));
        }

        @Test
        @DisplayName("false when path points at a file, not a directory")
        void pathPointsAtFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("file.war");
            Files.createFile(file);
            assertFalse(WarToExplodedQuickFix.isExplodedWebapp(file));
        }
    }

    @Nested
    @DisplayName("findFixableArtifacts")
    class FindFixableArtifactsBehaviour {

        @Test
        @DisplayName("offers fix when sibling exploded directory exists with WEB-INF")
        void offersFixForMavenLayout(@TempDir Path tempDir) throws IOException {
            Path warFile = createMavenWebappLayout(tempDir, "app");
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(List.of(artifact));

            assertEquals(1, candidates.size());
            assertEquals(artifact, candidates.get(0).artifact());
            assertEquals(tempDir.resolve("app"), candidates.get(0).explodedDirectory());
        }

        @Test
        @DisplayName("no fix when sibling directory missing")
        void noFixWhenSiblingMissing(@TempDir Path tempDir) throws IOException {
            // .war file exists, but no sibling directory
            Path warFile = tempDir.resolve("app.war");
            Files.createFile(warFile);
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("no fix when sibling directory exists but has no WEB-INF")
        void noFixWhenSiblingHasNoWebInf(@TempDir Path tempDir) throws IOException {
            Path warFile = tempDir.resolve("app.war");
            Files.createFile(warFile);
            // Sibling directory exists but isn't a real exploded webapp — e.g.
            // some other build step happened to leave a directory with the
            // same base name. Don't flip into it.
            Files.createDirectories(tempDir.resolve("app"));
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("already-exploded artifacts are skipped")
        void alreadyExplodedSkipped(@TempDir Path tempDir) throws IOException {
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app", explodedDir.toString(), DeploymentArtifact.TYPE_EXPLODED);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("multi-module project: each WAR with a sibling becomes its own candidate")
        void multiModule(@TempDir Path tempDir) throws IOException {
            Path moduleATarget = Files.createDirectories(tempDir.resolve("module-a").resolve("target"));
            Path moduleBTarget = Files.createDirectories(tempDir.resolve("module-b").resolve("target"));
            Path warA = createMavenWebappLayout(moduleATarget, "module-a");
            Path warB = createMavenWebappLayout(moduleBTarget, "module-b");

            DeploymentArtifact a = new DeploymentArtifact("module-a.war", warA.toString(), DeploymentArtifact.TYPE_WAR);
            DeploymentArtifact b = new DeploymentArtifact("module-b.war", warB.toString(), DeploymentArtifact.TYPE_WAR);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(List.of(a, b));

            assertEquals(2, candidates.size());
        }

        @Test
        @DisplayName("null entries in the artifact list are skipped without NPE")
        void nullEntriesSkipped(@TempDir Path tempDir) throws IOException {
            Path warFile = createMavenWebappLayout(tempDir, "app");
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            List<DeploymentArtifact> input = new java.util.ArrayList<>();
            input.add(null);
            input.add(artifact);
            input.add(null);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(input);
            assertEquals(1, candidates.size());
        }
    }
}
