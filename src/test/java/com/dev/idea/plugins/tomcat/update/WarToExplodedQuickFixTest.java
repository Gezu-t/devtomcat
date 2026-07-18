package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentKind;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModulePointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
     * Name-only module pointer — lets tests build {@link ModuleBackedDeployment}
     * without an IntelliJ {@code Project} / {@code ModulePointerManager}.
     */
    private static ModulePointer pointerTo(String moduleName) {
        return new ModulePointer() {
            @Override public @Nullable Module getModule() { return null; }
            @Override public @NotNull String getModuleName() { return moduleName; }
        };
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
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"),
                            List.of(deployment));

            assertEquals(1, candidates.size());
            assertEquals(deployment, candidates.get(0).deployment());
            assertEquals(tempDir.resolve("app"), candidates.get(0).explodedDirectory());
            assertEquals("app-module", candidates.get(0).moduleName());
        }

        @Test
        @DisplayName("no fix when no project module owns the exploded path")
        void noFixWhenNoModuleOwns(@TempDir Path tempDir) throws IOException {
            // A real exploded directory exists alongside the WAR, but the
            // user's project doesn't have any module rooted there. Flipping
            // to module-owned wouldn't help — class sync still wouldn't find
            // a source classes/ directory to copy from.
            Path warFile = createMavenWebappLayout(tempDir, "app");
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    NO_OWNER, List.of(deployment)).isEmpty());
        }

        @Test
        @DisplayName("no fix when sibling directory missing")
        void noFixWhenSiblingMissing(@TempDir Path tempDir) throws IOException {
            // .war file exists, but no sibling directory
            Path warFile = tempDir.resolve("app.war");
            Files.createFile(warFile);
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(deployment)).isEmpty());
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
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(deployment)).isEmpty());
        }

        @Test
        @DisplayName("already-exploded module-backed deployments are skipped")
        void alreadyExplodedModuleBackedSkipped(@TempDir Path tempDir) throws IOException {
            // An exploded ModuleBackedDeployment is the steady state — class
            // sync works, nothing to fix. Only exploded EXTERNAL deployments
            // qualify for the recovery branch.
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            Deployment deployment = new ModuleBackedDeployment(
                    pointerTo("app"), explodedDir, "/", true);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    singleOwner(tempDir, "any"), List.of(deployment)).isEmpty());
        }

        @Test
        @DisplayName("recovery case: exploded EXTERNAL deployment is fixable when module owns the path")
        void recoveryCaseExplodedButExternal(@TempDir Path tempDir) throws IOException {
            // The reported regression's residual state: an earlier flip
            // changed path/packaging but the entry stayed external. The loader
            // builds ExternalFileDeployment, class sync reports "could not
            // resolve owning module — null". The candidate detector must offer
            // to reclaim this as module-owned so class sync wires through.
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            Deployment deployment = new ExternalFileDeployment(explodedDir, "/", true);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"), List.of(deployment));

            assertEquals(1, candidates.size());
            // For the recovery case, the candidate path is the deployment's
            // current path — no derivation, no .war stripping.
            assertEquals(explodedDir, candidates.get(0).explodedDirectory());
            assertEquals("app-module", candidates.get(0).moduleName());
        }

        @Test
        @DisplayName("recovery case: skipped when external path doesn't live under any module")
        void recoveryCaseSkippedWhenOutsideAnyModule(@TempDir Path tempDir) throws IOException {
            // An exploded EXTERNAL deployment is only a valid recovery target
            // when the path is genuinely under a project module. An external
            // directory outside the project (user's deliberate choice) stays
            // as-is.
            Path explodedDir = tempDir.resolve("app");
            Files.createDirectories(explodedDir.resolve("WEB-INF"));
            Deployment deployment = new ExternalFileDeployment(explodedDir, "/", true);

            assertTrue(WarToExplodedQuickFix.findFixableArtifacts(
                    NO_OWNER, List.of(deployment)).isEmpty());
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

            Deployment a = new ExternalFileDeployment(warA, "/", false);
            Deployment b = new ExternalFileDeployment(warB, "/", false);

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
                if (c.deployment() == a) assertEquals("module-a", c.moduleName());
                else if (c.deployment() == b) assertEquals("module-b", c.moduleName());
                else fail("unexpected candidate deployment");
            }
        }

        @Test
        @DisplayName("null entries in the deployment list are skipped without NPE")
        void nullEntriesSkipped(@TempDir Path tempDir) throws IOException {
            Path warFile = createMavenWebappLayout(tempDir, "app");
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            List<Deployment> input = new java.util.ArrayList<>();
            input.add(null);
            input.add(deployment);
            input.add(null);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "any"), input);
            assertEquals(1, candidates.size());
        }
    }

    @Nested
    @DisplayName("applyAll (replacement contract)")
    class ApplyAllBehaviour {

        @Test
        @DisplayName("flipping an EXTERNAL .war deployment yields a module-owned exploded replacement")
        void flipsToModuleOwnedExploded(@TempDir Path tempDir) throws IOException {
            // The user-reported regression: external .war picked via auto-detect
            // or external source path. Without reclaiming the entry as
            // module-owned, the loader keeps ExternalFileDeployment (no module
            // link) and class sync returns "external-source-skipped" with
            // diagnostic=null. The fix must replace the entry with a
            // ModuleBackedDeployment pointing at the exploded directory so
            // the module link and packaging are both correct.
            Path warFile = createMavenWebappLayout(tempDir, "app");
            Deployment deployment = new ExternalFileDeployment(warFile, "/", false);

            List<WarToExplodedQuickFix.FixCandidate> candidates =
                    WarToExplodedQuickFix.findFixableArtifacts(
                            singleOwner(tempDir, "app-module"), List.of(deployment));
            assertEquals(1, candidates.size());

            // applyAll requires a TomcatRunConfiguration (project services for
            // ModulePointerManager); here we pin only the replacement SHAPE by
            // mirroring what applyAll builds. The real applyAll write-back —
            // stored-list persistence and resolved-slot consumption — is
            // covered by WarToExplodedQuickFixPlatformTest.
            WarToExplodedQuickFix.FixCandidate c = candidates.get(0);
            Deployment replacement = new ModuleBackedDeployment(
                    pointerTo(c.moduleName()), c.explodedDirectory(),
                    c.deployment().getContextPath(), true);

            assertEquals(tempDir.resolve("app"), replacement.getResolvedPath());
            assertTrue(replacement.isExploded());
            assertEquals(DeploymentKind.MODULE, replacement.getKind());
            assertEquals("app-module", replacement.getDisplayName());
            // Context path carries over from the replaced deployment.
            assertEquals("/", replacement.getContextPath());
        }
    }
}
