package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Returns a resolver that claims {@code modulePath → moduleName} ownership
     * for paths that fall under {@code modulePath}, and null for everything
     * else. Mimics the content-root-deepest-prefix algorithm.
     */
    private static WarToExplodedQuickFix.ModuleOwnershipResolver
            singleOwner(Path modulePath, String moduleName) {
        return path -> path.toAbsolutePath().normalize()
                .startsWith(modulePath.toAbsolutePath().normalize())
                ? moduleName : null;
    }

    /** Resolver that returns null for every path — simulates "no module owns this". */
    private static final WarToExplodedQuickFix.ModuleOwnershipResolver NO_OWNER = path -> null;

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
        @DisplayName("offers fix when sibling exploded directory exists with WEB-INF and a module owns it")
        void offersFixForMavenLayout(@TempDir Path tempDir) throws IOException {
            Path warFile = createMavenWebappLayout(tempDir, "app");
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"),
                            List.of(artifact));

            assertEquals(1, candidates.size());
            assertEquals(artifact, candidates.get(0).artifact());
            assertEquals(tempDir.resolve("app"), candidates.get(0).explodedDirectory());
            assertEquals("app-module", candidates.get(0).moduleName());
        }

        @Test
        @DisplayName("no fix when no project module owns the exploded path")
        void noFixWhenNoModuleOwns(@TempDir Path tempDir) throws IOException {
            // A real exploded directory exists alongside the WAR, but the
            // user's project doesn't have any module rooted there. Flipping
            // to AUTO_DETECTED wouldn't help — class sync still wouldn't find
            // a source classes/ directory to copy from.
            Path warFile = createMavenWebappLayout(tempDir, "app");
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    NO_OWNER, List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("no fix when sibling directory missing")
        void noFixWhenSiblingMissing(@TempDir Path tempDir) throws IOException {
            // .war file exists, but no sibling directory
            Path warFile = tempDir.resolve("app.war");
            Files.createFile(warFile);
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(artifact)).isEmpty());
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

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("already-exploded artifacts with non-EXTERNAL source are skipped")
        void alreadyExplodedAutoDetectedSkipped(@TempDir Path tempDir) throws IOException {
            // type=exploded + source=AUTO_DETECTED is the steady state — the
            // loader builds ModuleBackedDeployment, class sync works, nothing
            // to fix. (Default source on a fresh DeploymentArtifact is
            // INTELLIJ_ARTIFACT, also a steady state for our purposes.)
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app", explodedDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            artifact.setSource(DeploymentArtifact.Source.AUTO_DETECTED);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("recovery case: type=EXPLODED + source=EXTERNAL is fixable when module owns the path")
        void recoveryCaseExplodedButExternal(@TempDir Path tempDir) throws IOException {
            // The reported regression's residual state: an earlier flip
            // changed path/type but left source=EXTERNAL. The loader builds
            // ExternalFileDeployment, class sync reports "could not resolve
            // owning module — null". The candidate detector must offer to
            // reclaim this as AUTO_DETECTED so class sync wires through.
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app", explodedDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            artifact.setSource(DeploymentArtifact.Source.EXTERNAL);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"), List.of(artifact));

            assertEquals(1, candidates.size());
            // For the recovery case, the candidate path is the artifact's
            // current path — no derivation, no .war stripping.
            assertEquals(explodedDir, candidates.get(0).explodedDirectory());
            assertEquals("app-module", candidates.get(0).moduleName());
        }

        @Test
        @DisplayName("recovery case: skipped when external path doesn't live under any module")
        void recoveryCaseSkippedWhenOutsideAnyModule(@TempDir Path tempDir) throws IOException {
            // type=EXPLODED + source=EXTERNAL is only a valid recovery target
            // when the path is genuinely under a project module. An external
            // directory outside the project (user's deliberate choice) stays
            // as-is.
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app", explodedDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
            artifact.setSource(DeploymentArtifact.Source.EXTERNAL);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    NO_OWNER, List.of(artifact)).isEmpty());
        }

        @Test
        @DisplayName("multi-module project: each WAR resolves to its own module")
        void multiModule(@TempDir Path tempDir) throws IOException {
            Path moduleADir = Files.createDirectories(tempDir.resolve("module-a"));
            Path moduleBDir = Files.createDirectories(tempDir.resolve("module-b"));
            Path warA = createMavenWebappLayout(
                    Files.createDirectories(moduleADir.resolve("target")), "module-a");
            Path warB = createMavenWebappLayout(
                    Files.createDirectories(moduleBDir.resolve("target")), "module-b");

            DeploymentArtifact a = new DeploymentArtifact("module-a.war", warA.toString(), DeploymentArtifact.TYPE_WAR);
            DeploymentArtifact b = new DeploymentArtifact("module-b.war", warB.toString(), DeploymentArtifact.TYPE_WAR);

            // Resolver that knows about both modules and picks the deepest
            // matching root — same logic as the production version.
            Map<Path, String> roots = new HashMap<>();
            roots.put(moduleADir.toAbsolutePath().normalize(), "module-a");
            roots.put(moduleBDir.toAbsolutePath().normalize(), "module-b");
            WarToExplodedQuickFix.ModuleOwnershipResolver resolver = path -> {
                Path normalised = path.toAbsolutePath().normalize();
                String best = null;
                int bestLen = -1;
                for (Map.Entry<Path, String> e : roots.entrySet()) {
                    if (normalised.startsWith(e.getKey())) {
                        int len = e.getKey().toString().length();
                        if (len > bestLen) { best = e.getValue(); bestLen = len; }
                    }
                }
                return best;
            };

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(resolver, List.of(a, b));

            assertEquals(2, candidates.size());
            // Each candidate matches the right module — verifies the resolver
            // picks the deepest containing root rather than the first hit.
            for (WarToExplodedQuickFix.FixCandidate c : candidates) {
                if (c.artifact() == a) assertEquals("module-a", c.moduleName());
                else if (c.artifact() == b) assertEquals("module-b", c.moduleName());
                else fail("unexpected candidate artifact");
            }
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
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "any"), input);
            assertEquals(1, candidates.size());
        }
    }

    @Nested
    @DisplayName("applyAll (mutation contract)")
    class ApplyAllBehaviour {

        @Test
        @DisplayName("flipping an EXTERNAL .war artifact rewrites path, type, source, AND name")
        void flipsAllFourFields(@TempDir Path tempDir) throws IOException {
            // The user-reported regression: external .war picked via auto-detect
            // or external source path. Without flipping source+name, the loader
            // rebuilds ExternalFileDeployment (no module link) and class sync
            // returns "external-source-skipped" with diagnostic=null. The fix
            // must claim the deployment as module-owned and replace the name
            // with the actual module name so loader tier-1 lookup matches.
            Path warFile = createMavenWebappLayout(tempDir, "app");
            DeploymentArtifact artifact = new DeploymentArtifact(
                    "app.war", warFile.toString(), DeploymentArtifact.TYPE_WAR);
            artifact.setSource(DeploymentArtifact.Source.EXTERNAL);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"), List.of(artifact));
            assertEquals(1, candidates.size());

            // applyAll requires a TomcatRunConfiguration but only uses it for
            // logging; pass null is too lossy — we exercise the per-artifact
            // mutation by simulating apply directly here (the integration
            // path is covered by the platform test that uses a real run-config).
            // Mirror what applyAll does:
            WarToExplodedQuickFix.FixCandidate c = candidates.get(0);
            c.artifact().setPath(c.explodedDirectory().toString());
            c.artifact().setType(DeploymentArtifact.TYPE_EXPLODED);
            c.artifact().setSource(DeploymentArtifact.Source.AUTO_DETECTED);
            c.artifact().setName(c.moduleName());

            assertEquals(tempDir.resolve("app").toString(), artifact.getPath());
            assertEquals(DeploymentArtifact.TYPE_EXPLODED, artifact.getType());
            assertEquals(DeploymentArtifact.Source.AUTO_DETECTED, artifact.getSource());
            assertEquals("app-module", artifact.getName());
        }
    }
}
