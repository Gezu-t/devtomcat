package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
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

        @Test
        @DisplayName("legacy syncIfNeeded forwarder: empty list short-circuits without project services")
        void legacyForwarderEmptyList() {
            when(project.isDisposed()).thenReturn(false);
            // Empty list should never trigger toTyped() — the deprecated
            // forwarder must short-circuit before touching project services.
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncIfNeeded(project, List.of(), logger);
            assertNotNull(r);
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
            // set dst.mtime = src.mtime via COPY_ATTRIBUTES, then the user
            // edited src again within filesystem mtime resolution. Without
            // the size check we'd silently skip and serve stale code.
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
}
