package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the static cleanup helpers in {@link LocalDeploymentStrategy} —
 * the {@code .xml} / {@code .war} purge that runs before every IDE-managed
 * launch. The happy path is what most users hit; tests pin the boundary
 * behaviours (filtering by suffix, leaving unrelated files alone, returning
 * a non-null empty list when the directory does not exist).
 *
 * <p>The "stale Tomcat holds file open" failure case is not exercised here
 * because portably forcing {@code Files.deleteIfExists} to throw IOException
 * is awkward on POSIX (open-then-unlink succeeds). The contract is verified
 * structurally: failures are collected into the supplied list and the method
 * does not throw, no matter what the filesystem does. Manual verification on
 * Windows confirms the locked-file path appends to the list.
 */
class LocalDeploymentStrategyCleanupTest {

    @Nested
    @DisplayName("cleanStaleDeployments")
    class CleanStaleDeployments {

        @Test
        @DisplayName("removes every .xml in confDir and every .war in webappsDir")
        void removesXmlAndWar(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            Files.writeString(conf.resolve("ROOT.xml"), "<Context/>");
            Files.writeString(conf.resolve("myapp.xml"), "<Context/>");
            Files.writeString(webapps.resolve("myapp.war"), "PK"); // fake WAR magic

            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(webapps, conf);

            assertTrue(failures.isEmpty(), "happy path should not report failures");
            assertFalse(Files.exists(conf.resolve("ROOT.xml")));
            assertFalse(Files.exists(conf.resolve("myapp.xml")));
            assertFalse(Files.exists(webapps.resolve("myapp.war")));
        }

        @Test
        @DisplayName("preserves files with non-matching suffixes (.properties, .txt, directories)")
        void preservesUnrelatedFiles(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            // Cruft that must survive the sweep
            Files.writeString(conf.resolve("catalina.properties"), "key=value");
            Files.writeString(conf.resolve("logging.properties"), "");
            Files.createDirectory(webapps.resolve("ROOT")); // an exploded webapp directory

            LocalDeploymentStrategy.cleanStaleDeployments(webapps, conf);

            assertTrue(Files.exists(conf.resolve("catalina.properties")));
            assertTrue(Files.exists(conf.resolve("logging.properties")));
            assertTrue(Files.isDirectory(webapps.resolve("ROOT")),
                    "exploded webapp directories must not be deleted by the .war-only sweep");
        }

        @Test
        @DisplayName("missing webapps or conf directory yields an empty failures list (no throw)")
        void missingDirectoriesAreSilent(@TempDir Path tempDir) {
            Path notExistConf = tempDir.resolve("conf-missing");
            Path notExistWebapps = tempDir.resolve("webapps-missing");

            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(notExistWebapps, notExistConf);

            assertTrue(failures.isEmpty());
        }

        @Test
        @DisplayName("empty directories yield an empty failures list")
        void emptyDirectoriesAreSilent(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));

            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(webapps, conf);

            assertTrue(failures.isEmpty());
        }

        @Test
        @DisplayName("active-context overload removes leftover webapps/<context>/ directories")
        void activeContextsRemovesLeftoverDirs(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            // Simulate a previous WAR extract — exists as a directory under webapps/
            Path leftoverMyapp = Files.createDirectories(webapps.resolve("myapp").resolve("WEB-INF"));
            Files.writeString(leftoverMyapp.resolve("web.xml"), "<web-app/>");
            Path leftoverIndex = webapps.resolve("myapp").resolve("index.html");
            Files.writeString(leftoverIndex, "stale");

            // Currently deploying an artifact whose resolved context name is 'myapp'
            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of("myapp"));

            assertTrue(failures.isEmpty());
            assertFalse(Files.exists(webapps.resolve("myapp")),
                    "leftover webapps/myapp/ must be removed so the new deploy is clean");
        }

        @Test
        @DisplayName("active-context overload preserves bundled-app directories not in the set")
        void preservesBundledDirsOutsideActiveSet(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            // Mirror-managed bundled apps that the launch does NOT target
            Files.createDirectories(webapps.resolve("ROOT"));
            Files.createDirectories(webapps.resolve("manager"));
            Files.createDirectories(webapps.resolve("host-manager"));
            // User's own artifact about to deploy at context 'myapp'
            Files.createDirectories(webapps.resolve("myapp").resolve("WEB-INF"));

            LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of("myapp"));

            assertFalse(Files.exists(webapps.resolve("myapp")),
                    "active context's leftover dir must be removed");
            assertTrue(Files.isDirectory(webapps.resolve("ROOT")),
                    "mirrored ROOT must not be touched");
            assertTrue(Files.isDirectory(webapps.resolve("manager")),
                    "mirrored manager must not be touched");
            assertTrue(Files.isDirectory(webapps.resolve("host-manager")),
                    "mirrored host-manager must not be touched");
        }

        @Test
        @DisplayName("preserve set keeps mirror-written .war/.xml while stale files are still removed")
        void preserveKeepsMirrorOutput(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            // Written by CatalinaHomeMirror earlier in the same launch — must survive.
            Path mirroredWar = Files.writeString(webapps.resolve("shared.war"), "war");
            Path mirroredXml = Files.writeString(conf.resolve("ROOT.xml"), "<Context/>");
            // Genuine leftovers from a previous run — must be removed.
            Files.writeString(webapps.resolve("stale.war"), "old");
            Files.writeString(conf.resolve("stale.xml"), "old");

            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of(),
                    Set.of(mirroredWar.normalize(), mirroredXml.normalize()));

            assertTrue(failures.isEmpty());
            assertTrue(Files.exists(webapps.resolve("shared.war")), "mirror WAR must survive cleanup");
            assertTrue(Files.exists(conf.resolve("ROOT.xml")), "mirror descriptor must survive cleanup");
            assertFalse(Files.exists(webapps.resolve("stale.war")), "stale WAR must be removed");
            assertFalse(Files.exists(conf.resolve("stale.xml")), "stale descriptor must be removed");
        }

        @Test
        @DisplayName("a '.' context name never deletes the webapps directory itself")
        void dotContextDoesNotWipeWebapps(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            Files.createDirectories(webapps.resolve("existingApp"));

            // "." would resolve to webapps/. == webapps; the guard must skip it so the
            // whole tree is not deleted (belt-and-suspenders behind resolveContextName).
            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of("."));

            assertTrue(failures.isEmpty());
            assertTrue(Files.isDirectory(webapps), "webapps directory must survive");
            assertTrue(Files.isDirectory(webapps.resolve("existingApp")),
                    "other deployed contexts must survive");
        }

        @Test
        @DisplayName("active-context overload tolerates missing dir (no failure entry)")
        void missingLeftoverDirIsSilent(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));

            // Set names a context whose directory does NOT exist — should not error.
            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of("never-deployed"));

            assertTrue(failures.isEmpty());
        }

        @Test
        @DisplayName("active-context overload handles multi-segment context names like foo#bar")
        void multiSegmentContext(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            // Tomcat encodes /foo/bar context paths as foo#bar on disk
            Files.createDirectories(webapps.resolve("foo#bar").resolve("WEB-INF"));

            LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, Set.of("foo#bar"));

            assertFalse(Files.exists(webapps.resolve("foo#bar")));
        }

        @Test
        @DisplayName("active-context with null / blank entries does not throw")
        void tolerantOfBlankEntries(@TempDir Path tempDir) throws IOException {
            Path conf = Files.createDirectory(tempDir.resolve("conf"));
            Path webapps = Files.createDirectory(tempDir.resolve("webapps"));
            Set<String> activeContexts = new java.util.HashSet<>();
            activeContexts.add(null);
            activeContexts.add("");
            activeContexts.add("   ");
            activeContexts.add("real");

            // Should not throw — blanks are skipped, only 'real' is processed.
            // 'real' directory does not exist, so the result is an empty failures list.
            List<Path> failures = LocalDeploymentStrategy.cleanStaleDeployments(
                    webapps, conf, activeContexts);
            assertTrue(failures.isEmpty());
        }
    }

    @Nested
    @DisplayName("sweepRemovedDeployments — manifest-scoped ghost-context cleanup")
    class SweepRemovedDeployments {

        private void seedManifest(Path base, String... stems) throws IOException {
            LocalDeploymentStrategy.writeDeployedContexts(
                    base.resolve(LocalDeploymentStrategy.DEPLOYED_CONTEXTS_MANIFEST),
                    Set.of(stems));
        }

        @Test
        @DisplayName("a context we deployed before but not now has its extracted dir + war + descriptor removed")
        void removedDeploymentSwept(@TempDir Path base) throws IOException {
            Path webapps = Files.createDirectories(base.resolve("webapps"));
            Path conf = Files.createDirectories(base.resolve("conf"));
            Files.createDirectories(webapps.resolve("gone").resolve("WEB-INF"));
            Files.writeString(webapps.resolve("gone.war"), "war");
            Files.writeString(conf.resolve("gone.xml"), "<Context/>");
            seedManifest(base, "gone", "kept");
            Files.createDirectories(webapps.resolve("kept")); // still deployed this launch

            List<Path> failures = LocalDeploymentStrategy.sweepRemovedDeployments(
                    base, webapps, conf, Set.of("kept"), Set.of());

            assertTrue(failures.isEmpty());
            assertFalse(Files.exists(webapps.resolve("gone")), "removed deployment's extraction must go");
            assertFalse(Files.exists(webapps.resolve("gone.war")), "its leftover war must go");
            assertFalse(Files.exists(conf.resolve("gone.xml")), "its leftover descriptor must go");
            assertTrue(Files.isDirectory(webapps.resolve("kept")), "still-deployed context is untouched");
        }

        @Test
        @DisplayName("a bundled app never in the manifest is NEVER swept, even absent from preserve")
        void bundledAppNeverSwept(@TempDir Path base) throws IOException {
            Path webapps = Files.createDirectories(base.resolve("webapps"));
            Path conf = Files.createDirectories(base.resolve("conf"));
            Files.createDirectories(webapps.resolve("ROOT"));
            Files.createDirectories(webapps.resolve("manager"));
            // Manifest records only our own prior deployment — NOT the bundled apps.
            seedManifest(base, "myapp");

            LocalDeploymentStrategy.sweepRemovedDeployments(
                    base, webapps, conf, Set.of(), Set.of());

            assertTrue(Files.isDirectory(webapps.resolve("ROOT")),
                    "a mirror/bundled app we never recorded deploying must never be swept");
            assertTrue(Files.isDirectory(webapps.resolve("manager")));
        }

        @Test
        @DisplayName("no manifest (fresh base) sweeps nothing and records the current set")
        void firstLaunchNoManifest(@TempDir Path base) throws IOException {
            Path webapps = Files.createDirectories(base.resolve("webapps"));
            Path conf = Files.createDirectories(base.resolve("conf"));
            Files.createDirectories(webapps.resolve("preexisting")); // e.g. a hand-placed app

            List<Path> failures = LocalDeploymentStrategy.sweepRemovedDeployments(
                    base, webapps, conf, Set.of("app1"), Set.of());

            assertTrue(failures.isEmpty());
            assertTrue(Files.isDirectory(webapps.resolve("preexisting")),
                    "with no prior manifest nothing is a proven orphan");
            assertEquals(Set.of("app1"), LocalDeploymentStrategy.readDeployedContexts(
                    base.resolve(LocalDeploymentStrategy.DEPLOYED_CONTEXTS_MANIFEST)),
                    "current deployed set is recorded for next launch");
        }

        @Test
        @DisplayName("preserve is a second guard: a removed context whose war the mirror now owns is kept")
        void preserveGuardsMirrorReclaim(@TempDir Path base) throws IOException {
            // We deployed our own app at context 'ROOT' before; this launch we removed
            // it AND the mirror now provides ROOT.war. It must NOT be deleted.
            Path webapps = Files.createDirectories(base.resolve("webapps"));
            Path conf = Files.createDirectories(base.resolve("conf"));
            Path mirrorWar = Files.writeString(webapps.resolve("ROOT.war"), "mirror");
            Files.createDirectories(webapps.resolve("ROOT"));
            seedManifest(base, "ROOT");

            LocalDeploymentStrategy.sweepRemovedDeployments(
                    base, webapps, conf, Set.of(), Set.of(mirrorWar.normalize()));

            assertTrue(Files.exists(webapps.resolve("ROOT.war")), "mirror-owned war must survive");
            assertTrue(Files.isDirectory(webapps.resolve("ROOT")),
                    "its extraction must survive because the war is preserved");
        }

        @Test
        @DisplayName("a context still deployed this launch is never swept even if in the manifest")
        void stillActiveNotSwept(@TempDir Path base) throws IOException {
            Path webapps = Files.createDirectories(base.resolve("webapps"));
            Path conf = Files.createDirectories(base.resolve("conf"));
            Files.createDirectories(webapps.resolve("app"));
            seedManifest(base, "app");

            LocalDeploymentStrategy.sweepRemovedDeployments(
                    base, webapps, conf, Set.of("app"), Set.of());

            assertTrue(Files.isDirectory(webapps.resolve("app")));
        }

        @Test
        @DisplayName("manifest round-trips; absent manifest reads empty")
        void manifestRoundTrip(@TempDir Path base) {
            Path manifest = base.resolve(LocalDeploymentStrategy.DEPLOYED_CONTEXTS_MANIFEST);
            LocalDeploymentStrategy.writeDeployedContexts(manifest, Set.of("a", "b#c", "ROOT"));
            assertEquals(Set.of("a", "b#c", "ROOT"),
                    LocalDeploymentStrategy.readDeployedContexts(manifest));
            assertTrue(LocalDeploymentStrategy.readDeployedContexts(
                    base.resolve("nope")).isEmpty());
        }
    }

    @Nested
    @DisplayName("deleteEndingWith")
    class DeleteEndingWith {

        @Test
        @DisplayName("filters by suffix and only deletes matches")
        void filtersBySuffix(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("a.xml"), "");
            Files.writeString(tempDir.resolve("b.xml"), "");
            Files.writeString(tempDir.resolve("a.war"), "");
            Files.writeString(tempDir.resolve("readme.txt"), "");
            List<Path> failures = new ArrayList<>();

            LocalDeploymentStrategy.deleteEndingWith(tempDir, ".xml", failures);

            assertTrue(failures.isEmpty());
            assertFalse(Files.exists(tempDir.resolve("a.xml")));
            assertFalse(Files.exists(tempDir.resolve("b.xml")));
            assertTrue(Files.exists(tempDir.resolve("a.war")), ".war must survive .xml sweep");
            assertTrue(Files.exists(tempDir.resolve("readme.txt")));
        }

        @Test
        @DisplayName("suffix match is literal (no glob, no partial)")
        void literalSuffixOnly(@TempDir Path tempDir) throws IOException {
            // Tricky names that should NOT be matched by '.xml'
            Files.writeString(tempDir.resolve("not-xml.txt"), "");
            Files.writeString(tempDir.resolve("contains.xml.bak"), ""); // ends with .bak, not .xml
            Files.writeString(tempDir.resolve("real.xml"), "");
            List<Path> failures = new ArrayList<>();

            LocalDeploymentStrategy.deleteEndingWith(tempDir, ".xml", failures);

            assertTrue(failures.isEmpty());
            assertFalse(Files.exists(tempDir.resolve("real.xml")));
            assertTrue(Files.exists(tempDir.resolve("not-xml.txt")));
            assertTrue(Files.exists(tempDir.resolve("contains.xml.bak")),
                    "'contains.xml.bak' ends in '.bak', not '.xml' — must be preserved");
        }

        @Test
        @DisplayName("non-existent directory does not throw, failures list stays empty")
        void nonExistentDirectoryIsSilent(@TempDir Path tempDir) {
            List<Path> failures = new ArrayList<>();

            LocalDeploymentStrategy.deleteEndingWith(
                    tempDir.resolve("does-not-exist"), ".xml", failures);

            assertEquals(0, failures.size());
        }
    }
}
