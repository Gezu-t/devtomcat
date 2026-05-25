package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.project.Project;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    @DisplayName("syncIfNeeded returns an empty report on degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("disposed project: no scan, empty report")
        void disposedProject() {
            when(project.isDisposed()).thenReturn(true);
            DeployedClassesSync.SyncReport r = DeployedClassesSync.syncIfNeeded(
                    project, List.of(new DeploymentArtifact("foo", "/tmp/x", DeploymentArtifact.TYPE_EXPLODED)),
                    logger);
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
            assertFalse(r.didAnything());
        }

        @Test
        @DisplayName("empty artifact list: no scan, empty report")
        void emptyArtifactList() {
            when(project.isDisposed()).thenReturn(false);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncIfNeeded(project, List.of(), logger);
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
        }

        @Test
        @DisplayName("WAR artifact: skipped (can't hot-mirror inside a packaged WAR)")
        void warArtifactSkipped() {
            when(project.isDisposed()).thenReturn(false);
            DeploymentArtifact war =
                    new DeploymentArtifact("foo", "/tmp/nope.war", DeploymentArtifact.TYPE_WAR);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncIfNeeded(project, List.of(war), logger);
            // The artifact is invalid (file doesn't exist) so it short-circuits
            // via the isValid() check rather than the type check, but either
            // way the report should show zero work done.
            assertEquals(0, r.artifactsSynced());
            assertEquals(0, r.filesCopied());
        }

        @Test
        @DisplayName("invalid (missing on disk) artifact is skipped")
        void invalidArtifact() {
            when(project.isDisposed()).thenReturn(false);
            DeploymentArtifact missing = new DeploymentArtifact(
                    "foo", "/tmp/devtomcat-does-not-exist-" + System.nanoTime() + "/",
                    DeploymentArtifact.TYPE_EXPLODED);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncIfNeeded(project, List.of(missing), logger);
            assertEquals(1, r.artifactsSkipped());
            assertEquals(0, r.filesCopied());
        }

        @Test
        @DisplayName("null artifact in the list is tolerant")
        void nullArtifact() {
            when(project.isDisposed()).thenReturn(false);
            List<DeploymentArtifact> mixed = new java.util.ArrayList<>();
            mixed.add(null);
            DeployedClassesSync.SyncReport r =
                    DeployedClassesSync.syncIfNeeded(project, mixed, logger);
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
    @DisplayName("testStripArtifactSuffix normalises Maven/IntelliJ name conventions")
    class StripSuffix {

        @Test
        @DisplayName("strips :war exploded suffix")
        void stripsColonWar() {
            assertEquals("myapp", DeployedClassesSync.testStripArtifactSuffix("MyApp:war exploded"));
        }

        @Test
        @DisplayName("strips .war suffix")
        void stripsDotWar() {
            assertEquals("myapp", DeployedClassesSync.testStripArtifactSuffix("myapp.war"));
        }

        @Test
        @DisplayName("strips trailing parenthesised qualifier")
        void stripsParen() {
            assertEquals("myapp", DeployedClassesSync.testStripArtifactSuffix("myapp (Custom)"));
        }

        @Test
        @DisplayName("plain name unchanged (modulo case)")
        void plainName() {
            assertEquals("myapp", DeployedClassesSync.testStripArtifactSuffix("MyApp"));
        }
    }

    @Nested
    @DisplayName("stripMavenVersionSuffix recognises ${artifactId}-${version} pattern")
    class StripMavenVersion {

        @Test
        @DisplayName("strips '-6.0.0' three-segment version (the common Maven finalName tail)")
        void stripsThreeSegmentVersion() {
            assertEquals("web-module",
                    DeployedClassesSync.stripMavenVersionSuffix("web-module-6.0.0"));
        }

        @Test
        @DisplayName("strips a version tail that itself contains hyphens in the artifactId")
        void stripsVersionFromHyphenatedArtifactId() {
            // Multi-hyphen artifactId + version — exercises that the regex is
            // anchored to the version tail, not the first hyphen it sees.
            assertEquals("a-b-c-d",
                    DeployedClassesSync.stripMavenVersionSuffix("a-b-c-d-1.2.3"));
        }

        @Test
        @DisplayName("strips '-1' single-segment version")
        void stripsSingleSegment() {
            assertEquals("app", DeployedClassesSync.stripMavenVersionSuffix("app-1"));
        }

        @Test
        @DisplayName("strips '-1.0-SNAPSHOT'")
        void stripsSnapshot() {
            assertEquals("foo",
                    DeployedClassesSync.stripMavenVersionSuffix("foo-1.0-SNAPSHOT"));
        }

        @Test
        @DisplayName("strips '-2.3.4-RC1' qualified version")
        void stripsQualifiedRc() {
            assertEquals("bar",
                    DeployedClassesSync.stripMavenVersionSuffix("bar-2.3.4-RC1"));
        }

        @Test
        @DisplayName("strips '-3.0.RELEASE' dotted qualifier")
        void stripsDottedRelease() {
            assertEquals("svc",
                    DeployedClassesSync.stripMavenVersionSuffix("svc-3.0.RELEASE"));
        }

        @Test
        @DisplayName("returns null when no version tail is present")
        void noVersionReturnsNull() {
            org.junit.jupiter.api.Assertions.assertNull(
                    DeployedClassesSync.stripMavenVersionSuffix("plainname"));
        }

        @Test
        @DisplayName("returns null when the trailing token is not a number")
        void trailingNonNumericReturnsNull() {
            org.junit.jupiter.api.Assertions.assertNull(
                    DeployedClassesSync.stripMavenVersionSuffix("my-app-final"));
        }
    }

    @Nested
    @DisplayName("pathContains normalises slashes + case so cross-platform paths match")
    class PathContains {

        @Test
        @DisplayName("Windows backslash deployment path matches forward-slash content root")
        void backslashVsForwardSlash() {
            // IntelliJ reports VirtualFile paths with '/' on every OS;
            // artifact paths on Windows arrive with '\'. Normalisation must
            // bridge the two so a path that is literally inside the content
            // root is recognised as such.
            String contentRoot = "C:/projects/web-module";
            String deployment = "C:\\projects\\web-module\\target\\web-module-6.0.0";
            assertTrue(DeployedClassesSync.pathContains(contentRoot, deployment, true));
        }

        @Test
        @DisplayName("case-insensitive FS: lowercase drive letter still matches")
        void caseInsensitiveDriveLetter() {
            assertTrue(DeployedClassesSync.pathContains(
                    "C:/Users/me/proj", "c:/users/me/proj/target/x", true));
        }

        @Test
        @DisplayName("case-sensitive FS: differing case does NOT match")
        void caseSensitiveStrict() {
            assertFalse(DeployedClassesSync.pathContains(
                    "/home/me/proj", "/Home/me/proj/target", false));
        }

        @Test
        @DisplayName("exact equality matches")
        void exactMatch() {
            assertTrue(DeployedClassesSync.pathContains(
                    "/home/me/proj", "/home/me/proj", false));
        }

        @Test
        @DisplayName("trailing slash on content root does not break match")
        void trailingSlashOnRoot() {
            assertTrue(DeployedClassesSync.pathContains(
                    "/home/me/proj/", "/home/me/proj/sub", false));
        }

        @Test
        @DisplayName("boundary check: prefix without slash does NOT match")
        void boundaryRejectsPrefix() {
            // '/foo/ba' should not match '/foo/bar' as a containing root.
            assertFalse(DeployedClassesSync.pathContains(
                    "/foo/ba", "/foo/bar", false));
        }

        @Test
        @DisplayName("empty input is false")
        void emptyInput() {
            assertFalse(DeployedClassesSync.pathContains("", "/anything", false));
            assertFalse(DeployedClassesSync.pathContains("/anything", "", false));
        }

        @Test
        @DisplayName("mixed separators inside a single string normalise consistently")
        void mixedSeparatorsInOnePath() {
            // A path joined from a Windows root + Unix-style relative tail
            // (or vice versa) — happens with naive string concatenation in
            // build scripts. Both sides should normalise to the same form.
            assertTrue(DeployedClassesSync.pathContains(
                    "C:/projects\\app", "C:\\projects/app/target/x", true));
        }

        @Test
        @DisplayName("double slashes don't break containment")
        void doubleSlashesCollapse() {
            // Common with naive string concatenation: contentRoot + "/" + sub
            // when contentRoot already ends with '/'.
            assertTrue(DeployedClassesSync.pathContains(
                    "/home/me/proj", "/home/me//proj/target", false));
        }

        @Test
        @DisplayName("UNC-style path (\\\\server\\share) matches its own subpath")
        void uncStylePath() {
            // After normalisation both sides become single-slash strings;
            // since both halves get the same treatment, containment still
            // holds for the intended logical relationship.
            assertTrue(DeployedClassesSync.pathContains(
                    "\\\\server\\share\\proj",
                    "\\\\server\\share\\proj\\target\\x",
                    true));
        }

        @Test
        @DisplayName("Unix-style absolute path with mixed depth")
        void unixDeepNesting() {
            assertTrue(DeployedClassesSync.pathContains(
                    "/opt/workspace/repo/module-a",
                    "/opt/workspace/repo/module-a/build/libs/exploded",
                    false));
        }
    }
}
