package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.elements.CompositePackagingElement;
import com.intellij.packaging.elements.PackagingElement;
import com.intellij.packaging.elements.PackagingElementResolvingContext;
import com.intellij.packaging.impl.elements.ModulePackagingElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link DeployedClassesSync}.
 *
 * <p>The full sync path (resolve module → walk classpath → mirror) needs a
 * real IntelliJ Project fixture with {@code ModuleManager} wired up and a
 * read-action coordinator alive — that belongs in a platform-fixture
 * integration suite, not here. These tests guard the seams a Mockito unit
 * test can fabricate:
 *
 * <ul>
 *   <li>Degenerate inputs (disposed project, empty artifact list, WAR-only,
 *       invalid artifacts) return an empty report without ever touching the
 *       platform services.</li>
 *   <li>The {@code shouldCopy} mtime gate is correct: copies on missing
 *       targets, copies on newer source, no-ops on equal/older source.</li>
 *   <li>The {@code SyncReport} record contract.</li>
 * </ul>
 */
class DeployedClassesSyncTest {

    // The manifest store must never write into the real IDE system directory
    // from a test; redirect it to a per-test temp root.
    @org.junit.jupiter.api.BeforeEach
    void redirectManifestStore(@org.junit.jupiter.api.io.TempDir java.nio.file.Path storeRoot) {
        SyncManifestStore.setRootOverride(storeRoot);
    }

    @org.junit.jupiter.api.AfterEach
    void resetManifestStore() {
        SyncManifestStore.setRootOverride(null);
    }

    private final Project project = mock(Project.class);
    private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

    @Nested
    @DisplayName("syncDeployments returns an empty report on degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("disposed project: no scan, empty report")
        void disposedProject() {
            when(project.isDisposed()).thenReturn(true);
            Deployment d = new ExternalFileDeployment(Path.of("/tmp/x"), "/", true);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncDeployments(project, List.of(d), logger);
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
            assertFalse(r.didAnything());
        }

        @Test
        @DisplayName("empty deployment list: no scan, empty report")
        void emptyDeploymentList() {
            when(project.isDisposed()).thenReturn(false);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncDeployments(project, List.of(), logger);
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
        }

        @Test
        @DisplayName("WAR deployment: skipped (can't hot-mirror inside a packaged WAR)")
        void warDeploymentSkipped() {
            when(project.isDisposed()).thenReturn(false);
            // exploded=false → typed equivalent of TYPE_WAR. Path also missing
            // so this could short-circuit via the validity check too — either
            // way the report shows zero work done.
            Deployment war = new ExternalFileDeployment(Path.of("/tmp/nope.war"), "/", false);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncDeployments(project, List.of(war), logger);
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
        }

        @Test
        @DisplayName("invalid (missing on disk) deployment is skipped")
        void invalidDeployment() {
            when(project.isDisposed()).thenReturn(false);
            Deployment missing = new ExternalFileDeployment(
                    Path.of("/tmp/devtomcat-does-not-exist-" + System.nanoTime() + "/"),
                    "/", true);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncDeployments(project, List.of(missing), logger);
            assertEquals(1, r.artifactsSkipped());
            assertEquals(0, r.filesCopied());
        }

    }

    @Nested
    @DisplayName("shouldCopy mtime gate")
    class ShouldCopy {

        @Test
        @DisplayName("missing destination → must copy")
        void missingDestination(@TempDir Path dir) throws Exception {
            Path src = Files.writeString(dir.resolve("A.class"), "x");
            Path dst = dir.resolve("missing/A.class");
            BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class);
            assertTrue(DeployedClassesSync.shouldCopy(src, attrs, dst));
        }

        @Test
        @DisplayName("source strictly newer than destination → must copy")
        void sourceNewer(@TempDir Path dir) throws Exception {
            Path src = Files.writeString(dir.resolve("A.class"), "new");
            Path dst = Files.writeString(dir.resolve("dst.class"), "old");
            Files.setLastModifiedTime(dst, FileTime.fromMillis(1_000L));
            Files.setLastModifiedTime(src, FileTime.fromMillis(2_000L));
            BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class);
            assertTrue(DeployedClassesSync.shouldCopy(src, attrs, dst));
        }

        @Test
        @DisplayName("equal mtime AND equal size → no copy (avoid churn)")
        void equalMtimeEqualSize(@TempDir Path dir) throws Exception {
            // Same content → same size after writeString.
            Path src = Files.writeString(dir.resolve("A.class"), "same");
            Path dst = Files.writeString(dir.resolve("dst.class"), "same");
            Files.setLastModifiedTime(src, FileTime.fromMillis(2_000L));
            Files.setLastModifiedTime(dst, FileTime.fromMillis(2_000L));
            BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class);
            assertFalse(DeployedClassesSync.shouldCopy(src, attrs, dst));
        }

        @Test
        @DisplayName("equal mtime BUT different size → copy (size tie-breaker)")
        void equalMtimeDifferentSize(@TempDir Path dir) throws Exception {
            // The drift case the size-tiebreak guards against: a previous sync
            // set dst.mtime = src.mtime, then the user edited src again within
            // filesystem mtime resolution. Without the size check we'd silently
            // skip and serve stale code.
            Path src = Files.writeString(dir.resolve("A.class"), "edited-larger-content");
            Path dst = Files.writeString(dir.resolve("dst.class"), "old");
            Files.setLastModifiedTime(src, FileTime.fromMillis(2_000L));
            Files.setLastModifiedTime(dst, FileTime.fromMillis(2_000L));
            BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class);
            assertTrue(DeployedClassesSync.shouldCopy(src, attrs, dst),
                    "size mismatch with equal mtime must trigger a copy");
        }

        @Test
        @DisplayName("destination newer with same size → no copy")
        void destinationNewerSameSize(@TempDir Path dir) throws Exception {
            // User reverted nothing; src is just older. Skip.
            Path src = Files.writeString(dir.resolve("A.class"), "abc");
            Path dst = Files.writeString(dir.resolve("dst.class"), "xyz"); // same size as src
            Files.setLastModifiedTime(src, FileTime.fromMillis(1_000L));
            Files.setLastModifiedTime(dst, FileTime.fromMillis(2_000L));
            BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class);
            assertFalse(DeployedClassesSync.shouldCopy(src, attrs, dst));
        }
    }

    @Nested
    @DisplayName("isBrokenEcjClass detects ECJ compile-with-errors stubs")
    class EcjBrokenClassDetection {

        /**
         * Builds a synthetic byte array that looks enough like a class file
         * to exercise the detector. We do not need a real, JVM-loadable
         * class — the detector only scans constant-pool bytes for the
         * "Unresolved compilation problems" marker. Real ECJ stub classes
         * embed this string verbatim, so a file containing it (with .class
         * extension) is the signal we are pinning.
         */
        private byte[] withEcjMarker() {
            byte[] header = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                    0, 0, 0, 0x3D, 0, 10};
            byte[] marker = "Unresolved compilation problems: import foo;\n".getBytes();
            byte[] tail = new byte[64];
            byte[] all = new byte[header.length + marker.length + tail.length];
            System.arraycopy(header, 0, all, 0, header.length);
            System.arraycopy(marker, 0, all, header.length, marker.length);
            return all;
        }

        @Test
        @DisplayName(".class with the ECJ marker is detected as broken")
        void detectsMarkerInClassFile(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("Broken.class");
            Files.write(file, withEcjMarker());
            assertTrue(DeployedClassesSync.isBrokenEcjClass(file),
                    "presence of 'Unresolved compilation problems' marker must trigger detection");
        }

        @Test
        @DisplayName(".class without the marker is NOT detected as broken")
        void cleanClassFileNotDetected(@TempDir Path dir) throws Exception {
            // Plausibly-shaped class header without the marker string.
            byte[] clean = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                    0, 0, 0, 0x3D, 0, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            Path file = dir.resolve("Clean.class");
            Files.write(file, clean);
            assertFalse(DeployedClassesSync.isBrokenEcjClass(file),
                    "a class without the ECJ marker must not be flagged as broken");
        }

        @Test
        @DisplayName("non-.class files are never scanned (return false)")
        void nonClassFileNotScanned(@TempDir Path dir) throws Exception {
            // Even if a .properties file accidentally contains the literal
            // string (user data), the detector skips it via the extension
            // gate so resource sync stays fast.
            Path file = dir.resolve("messages.properties");
            Files.writeString(file, "key=Unresolved compilation problems: not really\n");
            assertFalse(DeployedClassesSync.isBrokenEcjClass(file),
                    "non-.class extensions must short-circuit before the byte scan");
        }

        @Test
        @DisplayName("missing file returns false (no false-positive copy block)")
        void missingFileReturnsFalse(@TempDir Path dir) {
            Path file = dir.resolve("nope.class");
            assertFalse(DeployedClassesSync.isBrokenEcjClass(file),
                    "a missing file must not be treated as broken — readAllBytes IOException"
                            + " falls through to 'allow the copy attempt'");
        }
    }

    @Nested
    @DisplayName("SyncReport record contract")
    class SyncReportContract {

        @Test
        @DisplayName("preserves the three count fields")
        void preservesFields() {
            DeployedClassesSync.SyncReport r = new DeployedClassesSync.SyncReport(2, 17, 1);
            assertEquals(2, r.artifactsSynced());
            assertEquals(17, r.filesCopied());
            assertEquals(1, r.artifactsSkipped());
        }

        @Test
        @DisplayName("didAnything reflects filesCopied > 0")
        void didAnythingIsCopiesGate() {
            assertTrue(new DeployedClassesSync.SyncReport(0, 1, 0).didAnything());
            assertFalse(new DeployedClassesSync.SyncReport(1, 0, 0).didAnything());
            assertFalse(new DeployedClassesSync.SyncReport(0, 0, 5).didAnything());
        }
    }

    @Nested
    @DisplayName("walkPackagingTreeForModule — structural artifact→module resolution")
    class WalkPackagingTree {

        private final PackagingElementResolvingContext ctx =
                mock(PackagingElementResolvingContext.class);

        /**
         * Mocks an object that implements BOTH {@link PackagingElement} and
         * {@link ModulePackagingElement}. The real concrete classes (e.g.
         * {@code ModuleOutputPackagingElementBase}) implement both
         * interfaces in parallel — they don't share a parent. So in a real
         * artifact tree, an {@code instanceof ModulePackagingElement} check
         * on a {@code PackagingElement<?>} can be true. The mock has to
         * replicate that shape.
         */
        private PackagingElement<?> mockModuleElement(Module module) {
            PackagingElement<?> element = mock(PackagingElement.class,
                    withSettings().extraInterfaces(ModulePackagingElement.class));
            when(((ModulePackagingElement) element).findModule(ctx)).thenReturn(module);
            return element;
        }

        @Test
        @DisplayName("ModulePackagingElement at the root returns its module directly")
        void moduleAtRoot() {
            Module module = mock(Module.class);
            PackagingElement<?> leaf = mockModuleElement(module);

            assertSame(module,
                    DeployedClassesSync.walkPackagingTreeForModule(leaf, ctx));
        }

        @Test
        @DisplayName("Module element found inside a composite child")
        void moduleInsideComposite() {
            Module module = mock(Module.class);
            PackagingElement<?> child = mockModuleElement(module);

            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(child)).when(root).getChildren();

            assertSame(module,
                    DeployedClassesSync.walkPackagingTreeForModule(root, ctx));
        }

        @Test
        @DisplayName("Deeply nested composite — depth-first walk reaches the module")
        void deeplyNestedComposite() {
            Module module = mock(Module.class);
            PackagingElement<?> leaf = mockModuleElement(module);

            CompositePackagingElement<?> inner = mock(CompositePackagingElement.class);
            doReturn(List.of(leaf)).when(inner).getChildren();

            CompositePackagingElement<?> outer = mock(CompositePackagingElement.class);
            doReturn(List.of(inner)).when(outer).getChildren();

            assertSame(module,
                    DeployedClassesSync.walkPackagingTreeForModule(outer, ctx));
        }

        @Test
        @DisplayName("No module elements in tree → null")
        void noModuleInTree() {
            PackagingElement<?> nonModuleLeaf = mock(PackagingElement.class);
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(nonModuleLeaf)).when(root).getChildren();

            assertNull(DeployedClassesSync.walkPackagingTreeForModule(root, ctx));
        }

        @Test
        @DisplayName("ModulePackagingElement whose findModule returns null → keep walking")
        void moduleElementWithNullModule() {
            // Edge case: a stale ModulePackagingElement whose target module
            // was deleted but the element wasn't refreshed yet. The walker
            // should keep looking instead of giving up.
            PackagingElement<?> stale = mockModuleElement(null);

            Module realModule = mock(Module.class);
            PackagingElement<?> live = mockModuleElement(realModule);

            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(stale, live)).when(root).getChildren();

            assertSame(realModule,
                    DeployedClassesSync.walkPackagingTreeForModule(root, ctx));
        }
    }

    @Nested
    @DisplayName("collectPackagedModules — all packaged modules, not just the first")
    class CollectPackagedModules {

        private final PackagingElementResolvingContext ctx =
                mock(PackagingElementResolvingContext.class);

        private PackagingElement<?> mockModuleElement(Module module) {
            PackagingElement<?> element = mock(PackagingElement.class,
                    withSettings().extraInterfaces(ModulePackagingElement.class));
            when(((ModulePackagingElement) element).findModule(ctx)).thenReturn(module);
            return element;
        }

        @Test
        @DisplayName("single module → set of one")
        void singleModule() {
            Module m = mock(Module.class);
            assertEquals(Set.of(m),
                    DeployedClassesSync.collectPackagedModules(mockModuleElement(m), ctx));
        }

        @Test
        @DisplayName("two sibling modules under a composite → both collected (walk does NOT stop at the first)")
        void twoSiblingModules() {
            Module a = mock(Module.class);
            Module b = mock(Module.class);
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(mockModuleElement(a), mockModuleElement(b))).when(root).getChildren();

            assertEquals(Set.of(a, b),
                    DeployedClassesSync.collectPackagedModules(root, ctx));
        }

        @Test
        @DisplayName("modules across nested composites are all collected")
        void nestedComposites() {
            Module a = mock(Module.class);
            Module b = mock(Module.class);
            CompositePackagingElement<?> inner = mock(CompositePackagingElement.class);
            doReturn(List.of(mockModuleElement(b))).when(inner).getChildren();
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(mockModuleElement(a), inner)).when(root).getChildren();

            assertEquals(Set.of(a, b),
                    DeployedClassesSync.collectPackagedModules(root, ctx));
        }

        @Test
        @DisplayName("the same module packaged twice is de-duplicated")
        void deduplicatesSameModule() {
            Module a = mock(Module.class);
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(mockModuleElement(a), mockModuleElement(a))).when(root).getChildren();

            assertEquals(Set.of(a),
                    DeployedClassesSync.collectPackagedModules(root, ctx));
        }

        @Test
        @DisplayName("no module elements → empty set")
        void noModules() {
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(mock(PackagingElement.class))).when(root).getChildren();

            assertTrue(DeployedClassesSync.collectPackagedModules(root, ctx).isEmpty());
        }

        @Test
        @DisplayName("a stale element whose module is null is skipped, real siblings still collected")
        void skipsNullModule() {
            Module real = mock(Module.class);
            CompositePackagingElement<?> root = mock(CompositePackagingElement.class);
            doReturn(List.of(mockModuleElement(null), mockModuleElement(real))).when(root).getChildren();

            assertEquals(Set.of(real),
                    DeployedClassesSync.collectPackagedModules(root, ctx));
        }
    }

    @Nested
    @DisplayName("mergeProductionRoots — union multi-module roots, dedup, own-wins")
    class MergeProductionRoots {

        private DeployedClassesSync.SourceRoot own(String path) {
            return new DeployedClassesSync.SourceRoot(java.nio.file.Path.of(path), false, null);
        }

        private DeployedClassesSync.SourceRoot dep(String path, String artifact) {
            return new DeployedClassesSync.SourceRoot(java.nio.file.Path.of(path), true, artifact);
        }

        @Test
        @DisplayName("disjoint roots from two modules are all kept, in order")
        void unionsDisjoint() {
            var a = own("/proj/a/out");
            var b = own("/proj/b/out");
            var merged = DeployedClassesSync.mergeProductionRoots(List.of(List.of(a), List.of(b)));
            assertEquals(List.of(a, b), merged);
        }

        @Test
        @DisplayName("a path shared across modules is de-duplicated to one entry")
        void deduplicatesSharedPath() {
            var shared = dep("/proj/lib/out", "lib");
            var merged = DeployedClassesSync.mergeProductionRoots(
                    List.of(List.of(shared), List.of(dep("/proj/lib/out", "lib"))));
            assertEquals(1, merged.size());
            assertEquals(java.nio.file.Path.of("/proj/lib/out"), merged.get(0).path());
        }

        @Test
        @DisplayName("own (full-content) wins over a dependency (.class-only) view of the same path")
        void ownWinsOverDependency() {
            // Module A sees /proj/b/out as a .class-only dependency; module B owns
            // it full-content. The merged root must be full-content so B's
            // resources are not dropped — regardless of which came first.
            var asDep = dep("/proj/b/out", "b");
            var asOwn = own("/proj/b/out");

            var depFirst = DeployedClassesSync.mergeProductionRoots(
                    List.of(List.of(asDep), List.of(asOwn)));
            assertEquals(1, depFirst.size());
            assertFalse(depFirst.get(0).classesOnly(), "own must win even when the dependency view came first");

            var ownFirst = DeployedClassesSync.mergeProductionRoots(
                    List.of(List.of(asOwn), List.of(asDep)));
            assertEquals(1, ownFirst.size());
            assertFalse(ownFirst.get(0).classesOnly(), "own must stay when it came first");
        }

        @Test
        @DisplayName("empty input yields empty output")
        void emptyInput() {
            assertTrue(DeployedClassesSync.mergeProductionRoots(List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveTyped — type-dispatched resolution")
    class TypedDispatch {

        @Test
        @DisplayName("EXTERNAL → silent skip, no diagnostic")
        void externalSilentSkip() {
            com.dev.idea.plugins.tomcat.model.ExternalFileDeployment external =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            java.nio.file.Path.of("/tmp/external"), "/c", true);
            DeployedClassesSync.ResolutionReport r =
                    DeployedClassesSync.resolveTyped(project, external);
            assertEquals("external-source-skipped", r.strategy());
            assertNull(r.moduleName());
            assertNull(r.diagnostic());
            assertTrue(r.sourceRoots().isEmpty());
        }

        @Test
        @DisplayName("ARTIFACT with deleted Artifact → 'artifact-missing' + actionable diagnostic")
        void artifactMissing() {
            com.intellij.packaging.artifacts.ArtifactPointer ptr =
                    org.mockito.Mockito.mock(
                            com.intellij.packaging.artifacts.ArtifactPointer.class);
            org.mockito.Mockito.when(ptr.getArtifactName()).thenReturn("ghost-artifact");
            org.mockito.Mockito.when(ptr.getArtifact()).thenReturn(null);

            com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment d =
                    new com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment(ptr, "/c");

            DeployedClassesSync.ResolutionReport r =
                    DeployedClassesSync.resolveTyped(project, d);
            assertEquals("artifact-missing", r.strategy());
            assertNull(r.moduleName());
            assertNotNull(r.diagnostic());
            assertTrue(r.diagnostic().contains("ghost-artifact"));
        }

        @Test
        @DisplayName("MODULE with deleted Module → 'module-missing' + actionable diagnostic")
        void moduleMissing() {
            com.intellij.openapi.module.ModulePointer ptr =
                    org.mockito.Mockito.mock(com.intellij.openapi.module.ModulePointer.class);
            org.mockito.Mockito.when(ptr.getModuleName()).thenReturn("vanished-mod");
            org.mockito.Mockito.when(ptr.getModule()).thenReturn(null);

            com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment d =
                    new com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment(
                            ptr, java.nio.file.Path.of("/out"), "/c", true);

            DeployedClassesSync.ResolutionReport r =
                    DeployedClassesSync.resolveTyped(project, d);
            assertEquals("module-missing", r.strategy());
            assertNull(r.moduleName());
            assertNotNull(r.diagnostic());
            assertTrue(r.diagnostic().contains("vanished-mod"));
        }
    }

    @Nested
    @DisplayName("shouldMirrorClassesOnly — per-root resource policy vs deployed WEB-INF/lib")
    class ShouldMirrorClassesOnlyPolicy {

        /** A dependency root: classesOnly candidate, tagged with an artifact identity. */
        private static DeployedClassesSync.SourceRoot dependency(String artifactName) {
            return new DeployedClassesSync.SourceRoot(Path.of("/out/dep"), true, artifactName);
        }

        @Test
        @DisplayName("own root (classesOnly=false) always mirrors full content")
        void ownRootAlwaysFullContent() {
            DeployedClassesSync.SourceRoot own =
                    new DeployedClassesSync.SourceRoot(Path.of("/out/web"), false, null);
            // Never .class-only, regardless of what the deployed lib set holds.
            assertFalse(DeployedClassesSync.shouldMirrorClassesOnly(own, Set.of("web")));
            assertFalse(DeployedClassesSync.shouldMirrorClassesOnly(own, Set.of()));
        }

        @Test
        @DisplayName("dependency packaged in WEB-INF/lib → .class-only (resources come from the JAR)")
        void jarredDependencyClassesOnly() {
            assertTrue(DeployedClassesSync.shouldMirrorClassesOnly(
                    dependency("common"), Set.of("common", "shared")));
        }

        @Test
        @DisplayName("dependency absent from WEB-INF/lib → full content (resources must still reach Tomcat)")
        void unjarredDependencyFullContent() {
            assertFalse(DeployedClassesSync.shouldMirrorClassesOnly(
                    dependency("common"), Set.of("shared")));
        }

        @Test
        @DisplayName("dependency with no resolved identity → .class-only (duplicate-safe default)")
        void unresolvedIdentityClassesOnly() {
            assertTrue(DeployedClassesSync.shouldMirrorClassesOnly(
                    dependency(null), Set.of("shared")));
            // Empty lib set must NOT flip the default to full content.
            assertTrue(DeployedClassesSync.shouldMirrorClassesOnly(
                    dependency(null), Set.of()));
        }
    }

    @Nested
    @DisplayName("scanDeployedLibraryKeys — version-independent keys from deployed WEB-INF/lib")
    class ScanDeployedLibraryKeys {

        /** Creates an empty file at {@code WEB-INF/lib/<jarName>} under {@code artifactRoot}. */
        private static void writeLibFile(Path artifactRoot, String jarName) throws Exception {
            Path lib = Files.createDirectories(artifactRoot.resolve("WEB-INF/lib"));
            Files.createFile(lib.resolve(jarName));
        }

        @Test
        @DisplayName("absent WEB-INF/lib → empty set")
        void absentLibDir(@TempDir Path tmp) {
            assertTrue(DeployedClassesSync.scanDeployedLibraryKeys(tmp).isEmpty());
        }

        @Test
        @DisplayName("maps each JAR to its version-independent artifact key")
        void mapsJarsToKeys(@TempDir Path tmp) throws Exception {
            writeLibFile(tmp, "common-1.2.3.jar");
            writeLibFile(tmp, "log4j-api-2.20.0.jar");
            writeLibFile(tmp, "spring-boot-starter.jar"); // no version segment
            assertEquals(Set.of("common", "log4j-api", "spring-boot-starter"),
                    DeployedClassesSync.scanDeployedLibraryKeys(tmp));
        }

        @Test
        @DisplayName("non-JAR files in WEB-INF/lib are ignored")
        void ignoresNonJarFiles(@TempDir Path tmp) throws Exception {
            writeLibFile(tmp, "common-1.0.0.jar");
            writeLibFile(tmp, "notes.txt");
            assertEquals(Set.of("common"),
                    DeployedClassesSync.scanDeployedLibraryKeys(tmp));
        }

        @Test
        @DisplayName("scanDeployedLibraryJars maps each key to the deployed JAR's actual file name")
        void jarsMapKeepsActualFileNames(@TempDir Path tmp) throws Exception {
            writeLibFile(tmp, "common-1.2.3.jar");
            writeLibFile(tmp, "log4j-api-2.20.0.jar");
            writeLibFile(tmp, "notes.txt");
            // The VALUE must be the on-disk file name, not the key: the
            // covering-JAR floor stats WEB-INF/lib/<value>, and the manifest's
            // JAR records are written under it — a wrong value silently disarms
            // both (unknown stamp -> no floor, no record).
            assertEquals(java.util.Map.of(
                            "common", "common-1.2.3.jar",
                            "log4j-api", "log4j-api-2.20.0.jar"),
                    DeployedClassesSync.scanDeployedLibraryJars(tmp));
        }

        @Test
        @DisplayName("version drift: a packaged dependency is .class-only even when versions differ")
        void versionDriftStillMatches(@TempDir Path tmp) throws Exception {
            // The build packaged the dependency at one version; the IDE classpath
            // exposes that module's compile output under its artifactId. The two
            // must reconcile on identity alone — no version match required — so the
            // dependency mirrors .class-only and its resources are not duplicated.
            writeLibFile(tmp, "common-2.0.0.jar");
            Set<String> deployed = DeployedClassesSync.scanDeployedLibraryKeys(tmp);

            DeployedClassesSync.SourceRoot dep =
                    new DeployedClassesSync.SourceRoot(Path.of("/out/common"), true, "common");
            assertTrue(DeployedClassesSync.shouldMirrorClassesOnly(dep, deployed),
                    "a packaged dependency must mirror .class-only even when the deployed "
                            + "JAR version differs from the classpath module");
        }
    }

    @Nested
    @DisplayName("coveringJarFor — ties a covered dependency root to its deployed JAR")
    class CoveringJarFor {

        private final java.util.Map<String, String> jars =
                java.util.Map.of("common", "common-1.0.0.jar", "shared", "shared-2.0.jar");

        @Test
        @DisplayName("covered dependency root → the deployed JAR's file name (version-independent match)")
        void coveredDependency() {
            DeployedClassesSync.SourceRoot dep =
                    new DeployedClassesSync.SourceRoot(Path.of("/out/common"), true, "common");
            assertEquals("common-1.0.0.jar", DeployedClassesSync.coveringJarFor(dep, jars));
        }

        @Test
        @DisplayName("own root is never covered")
        void ownRootNeverCovered() {
            DeployedClassesSync.SourceRoot own =
                    new DeployedClassesSync.SourceRoot(Path.of("/out/web"), false, null);
            assertNull(DeployedClassesSync.coveringJarFor(own, jars));
        }

        @Test
        @DisplayName("unresolved identity or no matching JAR → no cover")
        void unresolvedOrUnmatched() {
            assertNull(DeployedClassesSync.coveringJarFor(
                    new DeployedClassesSync.SourceRoot(Path.of("/out/x"), true, null), jars));
            assertNull(DeployedClassesSync.coveringJarFor(
                    new DeployedClassesSync.SourceRoot(Path.of("/out/x"), true, "unpackaged"), jars));
        }
    }

    @Nested
    @DisplayName("findOutdatedUncoveredJars — uncovered module output vs deployed WEB-INF/lib JAR")
    class FindOutdatedUncoveredJars {

        private Path artifactRoot;
        private Path jar;
        private Path outRoot;

        private void scaffold(Path tmp, long jarMtime, long outputMtime) throws Exception {
            artifactRoot = tmp.resolve("app-1.0.0");
            jar = artifactRoot.resolve("WEB-INF/lib/common-1.0.0.jar");
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar-bytes");
            Files.setLastModifiedTime(jar, FileTime.fromMillis(jarMtime));
            outRoot = tmp.resolve("out/common");
            Path cls = outRoot.resolve("A.class");
            Files.createDirectories(cls.getParent());
            Files.writeString(cls, "class-bytes");
            Files.setLastModifiedTime(cls, FileTime.fromMillis(outputMtime));
        }

        @Test
        @DisplayName("uncovered module with output newer than its deployed JAR → reported")
        void uncoveredNewerOutputReported(@TempDir Path tmp) throws Exception {
            scaffold(tmp, 100_000L, 300_000L);

            List<DeployedClassesSync.OutdatedJar> outdated =
                    DeployedClassesSync.findOutdatedUncoveredJars(
                            java.util.Map.of("common", List.of(outRoot)),
                            java.util.Map.of("common", "common-1.0.0.jar"), artifactRoot);

            assertEquals(1, outdated.size());
            assertEquals("common", outdated.get(0).moduleName());
            assertEquals("common-1.0.0.jar", outdated.get(0).jarFileName());
            assertEquals(200_000L, outdated.get(0).newerByMillis());
        }

        @Test
        @DisplayName("JAR newer than the output → silent")
        void jarNewerSilent(@TempDir Path tmp) throws Exception {
            scaffold(tmp, 300_000L, 100_000L);
            assertTrue(DeployedClassesSync.findOutdatedUncoveredJars(
                    java.util.Map.of("common", List.of(outRoot)),
                    java.util.Map.of("common", "common-1.0.0.jar"), artifactRoot).isEmpty());
        }

        @Test
        @DisplayName("module with no matching deployed JAR → silent (not this diagnostic's case)")
        void noMatchingJarSilent(@TempDir Path tmp) throws Exception {
            scaffold(tmp, 100_000L, 300_000L);
            assertTrue(DeployedClassesSync.findOutdatedUncoveredJars(
                    java.util.Map.of("common", List.of(outRoot)),
                    java.util.Map.of(), artifactRoot).isEmpty());
        }

        @Test
        @DisplayName("a recorded JAR missing on disk → silent, never an alarm")
        void missingJarSilent(@TempDir Path tmp) throws Exception {
            scaffold(tmp, 100_000L, 300_000L);
            Files.delete(jar);
            assertTrue(DeployedClassesSync.findOutdatedUncoveredJars(
                    java.util.Map.of("common", List.of(outRoot)),
                    java.util.Map.of("common", "common-1.0.0.jar"), artifactRoot).isEmpty());
        }

        @Test
        @DisplayName("covered modules are excluded by construction — an empty uncovered map is silent")
        void emptyUncoveredMapSilent(@TempDir Path tmp) throws Exception {
            scaffold(tmp, 100_000L, 300_000L);
            assertTrue(DeployedClassesSync.findOutdatedUncoveredJars(
                    java.util.Map.of(),
                    java.util.Map.of("common", "common-1.0.0.jar"), artifactRoot).isEmpty());
        }
    }

    @Nested
    @DisplayName("warnOutdatedUncoveredJars — console every action, balloon once per session")
    class WarnOutdatedUncoveredJars {

        private final TomcatDeploymentLogger logger =
                org.mockito.Mockito.mock(TomcatDeploymentLogger.class);

        private static DeployedClassesSync.OutdatedJar outdated() {
            return new DeployedClassesSync.OutdatedJar(
                    "common", "common-1.0.0.jar", Path.of("/projects/X/out/A.class"), 60_000L);
        }

        @Test
        @DisplayName("console warns on every action; the balloon fires once per session")
        void consoleEveryActionBalloonOnce() {
            SessionNotificationGate gate = new SessionNotificationGate();
            java.util.concurrent.atomic.AtomicInteger balloons =
                    new java.util.concurrent.atomic.AtomicInteger();

            DeployedClassesSync.warnOutdatedUncoveredJars("app-1.0.0", List.of(outdated()),
                    logger, gate, "scope-1", (t, c) -> balloons.incrementAndGet());
            DeployedClassesSync.warnOutdatedUncoveredJars("app-1.0.0", List.of(outdated()),
                    logger, gate, "scope-1", (t, c) -> balloons.incrementAndGet());

            assertEquals(1, balloons.get());
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.times(2))
                    .logServerWarning(org.mockito.ArgumentMatchers.contains("Outdated dependency JAR"));
        }

        @Test
        @DisplayName("no outdated jars → fully silent")
        void emptySilent() {
            DeployedClassesSync.warnOutdatedUncoveredJars("app-1.0.0", List.of(),
                    logger, new SessionNotificationGate(), "scope-1",
                    (t, c) -> org.junit.jupiter.api.Assertions.fail("no balloon expected"));
            org.mockito.Mockito.verifyNoInteractions(logger);
        }
    }

    @Nested
    @DisplayName("gradleArtifactNameFromLinkedId")
    class GradleArtifactNameFromLinkedId {

        @Test
        @DisplayName("subproject path → leaf subproject name")
        void subprojectLeaf() {
            assertEquals("sub", DeployedClassesSync.gradleArtifactNameFromLinkedId(":app:sub"));
            assertEquals("lib", DeployedClassesSync.gradleArtifactNameFromLinkedId(":lib"));
        }

        @Test
        @DisplayName("trailing source-set segment is dropped (the real Gradle multi-module fix)")
        void dropsSourceSetSegment() {
            assertEquals("sub", DeployedClassesSync.gradleArtifactNameFromLinkedId(":app:sub:main"));
            assertEquals("sub", DeployedClassesSync.gradleArtifactNameFromLinkedId(":app:sub:test"));
        }

        @Test
        @DisplayName("filesystem-style path → leaf directory name")
        void filesystemPathLeaf() {
            assertEquals("sub", DeployedClassesSync.gradleArtifactNameFromLinkedId("/work/app/sub"));
        }

        @Test
        @DisplayName("blank or null → null (caller falls back to the module-name stem)")
        void blankIsNull() {
            assertNull(DeployedClassesSync.gradleArtifactNameFromLinkedId(null));
            assertNull(DeployedClassesSync.gradleArtifactNameFromLinkedId(""));
            assertNull(DeployedClassesSync.gradleArtifactNameFromLinkedId("   "));
        }

        @Test
        @DisplayName("root project (no path separators) is returned as-is")
        void rootProject() {
            assertEquals("my-app", DeployedClassesSync.gradleArtifactNameFromLinkedId("my-app"));
        }
    }
}
