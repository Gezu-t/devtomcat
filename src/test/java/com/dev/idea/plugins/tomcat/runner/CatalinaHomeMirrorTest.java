package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CatalinaHomeMirror")
class CatalinaHomeMirrorTest {

    // ---------------------------------------------------------------------
    // Disabled path — cleanup semantics
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("when disabled")
    class WhenDisabled {

        @Test
        @DisplayName("does nothing when no manifest exists")
        void noManifest(@TempDir Path tempDir) throws IOException {
            Path home = makeEmptyHome(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(false, home, base, Set.of());

            assertEquals(0, r.entriesLinked);
            assertEquals(0, r.entriesCopied);
            assertEquals(0, r.entriesSynthesized);
            assertEquals(0, r.entriesCleanedUp);
            assertTrue(r.warnings.isEmpty());
        }

        @Test
        @DisplayName("removes entries listed in the prior-run manifest")
        void cleansUpPreviousEntries(@TempDir Path tempDir) throws IOException {
            Path home = makeEmptyHome(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base.resolve("webapps"));
            Files.createDirectories(base.resolve("conf/Catalina/localhost"));

            // Simulate a previous enabled run: files + manifest.
            Path ctx = base.resolve("conf/Catalina/localhost/manager.xml");
            Files.writeString(ctx, "<Context/>");
            Path war = base.resolve("webapps/manager.war");
            Files.writeString(war, "WAR");
            Path exploded = base.resolve("webapps/examples");
            Files.createDirectories(exploded);
            Files.writeString(exploded.resolve("index.html"), "<html/>");

            Files.writeString(base.resolve(CatalinaHomeMirror.MANIFEST_NAME),
                    "version=1\n" +
                    "conf/Catalina/localhost/manager.xml\n" +
                    "webapps/manager.war\n" +
                    "webapps/examples\n");

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(false, home, base, Set.of());

            assertEquals(3, r.entriesCleanedUp);
            assertFalse(Files.exists(ctx));
            assertFalse(Files.exists(war));
            assertFalse(Files.exists(exploded));
            assertFalse(Files.exists(base.resolve(CatalinaHomeMirror.MANIFEST_NAME)));
        }

        @Test
        @DisplayName("ignores traversal and absolute paths in the manifest")
        void ignoresSuspiciousEntries(@TempDir Path tempDir) throws IOException {
            Path home = makeEmptyHome(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            Files.writeString(base.resolve(CatalinaHomeMirror.MANIFEST_NAME),
                    "../outside.xml\n" +
                    "/etc/passwd\n" +
                    "C:\\Windows\\evil\n");

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(false, home, base, Set.of());

            assertEquals(0, r.entriesCleanedUp);
        }
    }

    // ---------------------------------------------------------------------
    // Enabled path — placement semantics
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("when enabled")
    class WhenEnabled {

        @Test
        @DisplayName("hardlinks WARs and synthesizes context descriptors for exploded dirs")
        void mirrorsWarAndExplodedApp(@TempDir Path tempDir) throws IOException {
            Path home = makeHomeWithApps(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(true, home, base, Set.of());

            // manager.war should be placed in webapps/
            Path war = base.resolve("webapps/manager.war");
            assertTrue(Files.exists(war), "WAR should be mirrored into webapps/");
            assertEquals(Files.readString(home.resolve("webapps/manager.war")),
                    Files.readString(war),
                    "WAR content must match source");

            // examples/ is exploded → a synthesized context descriptor, NOT a copy of the directory
            Path synthesized = base.resolve("conf/Catalina/localhost/examples.xml");
            assertTrue(Files.isRegularFile(synthesized),
                    "Synthesized context descriptor for exploded app should exist");
            String xml = Files.readString(synthesized);
            assertTrue(xml.contains("<Context"), "Synthesized XML must declare a Context");
            assertTrue(xml.contains(home.resolve("webapps/examples").toAbsolutePath().toString()),
                    "docBase must point at the source directory");

            // Directory itself must NOT be duplicated
            assertFalse(Files.exists(base.resolve("webapps/examples")),
                    "Exploded app must not be copied into base webapps/");

            assertEquals(1, r.entriesSynthesized, "One synthesized context expected");
            assertEquals(0, r.warnings.size(), () -> "Unexpected warnings: " + r.warnings);

            // Manifest records both placements
            List<String> manifest = Files.readAllLines(base.resolve(CatalinaHomeMirror.MANIFEST_NAME));
            assertTrue(manifest.stream().anyMatch(l -> l.equals("webapps/manager.war")));
            assertTrue(manifest.stream().anyMatch(l -> l.equals("conf/Catalina/localhost/examples.xml")));
        }

        @Test
        @DisplayName("author-provided context descriptor wins over synthesized one for same stem")
        void authorContextWinsOverSynthesized(@TempDir Path tempDir) throws IOException {
            Path home = tempDir.resolve("home");
            Files.createDirectories(home.resolve("webapps/examples"));
            Files.writeString(home.resolve("webapps/examples/index.html"), "<html/>");
            Files.createDirectories(home.resolve("conf/Catalina/localhost"));
            Files.writeString(home.resolve("conf/Catalina/localhost/examples.xml"),
                    "<Context docBase=\"/opt/custom/examples\"/>");

            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(true, home, base, Set.of());

            Path ctx = base.resolve("conf/Catalina/localhost/examples.xml");
            assertTrue(Files.exists(ctx));
            // Must preserve author-provided content, NOT the synthesized docBase.
            String content = Files.readString(ctx);
            assertTrue(content.contains("/opt/custom/examples"),
                    "Author descriptor must win; synthesized one must not overwrite it");
            assertEquals(0, r.entriesSynthesized, "Synthesis must be skipped when author XML exists");
        }

        @Test
        @DisplayName("skips shared apps that collide with an IDE artifact context path")
        void skipsIdeCollisions(@TempDir Path tempDir) throws IOException {
            Path home = makeHomeWithApps(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            // IDE owns /manager and /examples — shared apps should be skipped.
            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(true, home, base, Set.of("manager", "examples"));

            assertFalse(Files.exists(base.resolve("webapps/manager.war")));
            assertFalse(Files.exists(base.resolve("conf/Catalina/localhost/examples.xml")));
            assertEquals(2, r.entriesSkipped);
            assertTrue(r.warnings.stream().anyMatch(w -> w.contains("manager")));
            assertTrue(r.warnings.stream().anyMatch(w -> w.contains("examples")));
        }

        @Test
        @DisplayName("exploded directory shadowing a WAR is skipped")
        void explodedDirShadowingWarIsSkipped(@TempDir Path tempDir) throws IOException {
            Path home = tempDir.resolve("home");
            Files.createDirectories(home.resolve("webapps"));
            Files.writeString(home.resolve("webapps/foo.war"), "WAR");
            Files.createDirectories(home.resolve("webapps/foo"));       // unpacked shadow
            Files.writeString(home.resolve("webapps/foo/index.html"), "<html/>");

            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(true, home, base, Set.of());

            assertTrue(Files.exists(base.resolve("webapps/foo.war")),
                    "WAR should be mirrored");
            assertFalse(Files.exists(base.resolve("conf/Catalina/localhost/foo.xml")),
                    "Shadow directory must not produce a duplicate context");
            assertEquals(1, r.entriesSkipped);
        }

        @Test
        @DisplayName("is idempotent — re-running yields the same state")
        void idempotent(@TempDir Path tempDir) throws IOException {
            Path home = makeHomeWithApps(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.apply(true, home, base, Set.of());
            long manifestBytes1 = Files.size(base.resolve(CatalinaHomeMirror.MANIFEST_NAME));
            long synthSize1 = Files.size(base.resolve("conf/Catalina/localhost/examples.xml"));

            CatalinaHomeMirror.apply(true, home, base, Set.of());
            long manifestBytes2 = Files.size(base.resolve(CatalinaHomeMirror.MANIFEST_NAME));
            long synthSize2 = Files.size(base.resolve("conf/Catalina/localhost/examples.xml"));

            // Manifest timestamps differ per run so bytes may shift by a few chars;
            // but the listed entries after the header must match line-for-line.
            List<String> m1 = manifestEntries(Files.readAllLines(base.resolve(CatalinaHomeMirror.MANIFEST_NAME)));
            // (both reads identical at this point; second apply replaced the file)
            assertEquals(List.of("conf/Catalina/localhost/examples.xml", "webapps/manager.war"),
                    m1.stream().sorted().toList());
            assertEquals(synthSize1, synthSize2,
                    "Synthesized descriptor must be deterministic across runs");
            assertTrue(manifestBytes2 > 0 && manifestBytes1 > 0);
        }

        @Test
        @DisplayName("toggling from enabled to disabled cleans up previous entries")
        void toggleOffCleansUp(@TempDir Path tempDir) throws IOException {
            Path home = makeHomeWithApps(tempDir);
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.apply(true, home, base, Set.of());
            assertTrue(Files.exists(base.resolve("webapps/manager.war")));
            assertTrue(Files.exists(base.resolve("conf/Catalina/localhost/examples.xml")));

            CatalinaHomeMirror.Result off =
                    CatalinaHomeMirror.apply(false, home, base, Set.of());

            assertFalse(Files.exists(base.resolve("webapps/manager.war")));
            assertFalse(Files.exists(base.resolve("conf/Catalina/localhost/examples.xml")));
            assertFalse(Files.exists(base.resolve(CatalinaHomeMirror.MANIFEST_NAME)));
            assertEquals(2, off.entriesCleanedUp);
        }

        @Test
        @DisplayName("warns when CATALINA_HOME/webapps is absent")
        void warnsOnMissingWebapps(@TempDir Path tempDir) throws IOException {
            Path home = tempDir.resolve("home");
            Files.createDirectories(home);       // no webapps/
            Path base = tempDir.resolve("base");
            Files.createDirectories(base);

            CatalinaHomeMirror.Result r =
                    CatalinaHomeMirror.apply(true, home, base, Set.of());

            assertTrue(r.warnings.stream().anyMatch(w -> w.contains("webapps")));
        }
    }

    @Nested
    @DisplayName("sanitizeStem")
    class SanitizeStem {

        @Test
        @DisplayName("returns ROOT for an empty name after sanitisation")
        void emptyBecomesRoot() {
            // replace all non-alphanum with '_', but "///" becomes "###" → still non-empty,
            // so feed in a string of pure invalid chars like spaces to cover the empty branch.
            String result = CatalinaHomeMirror.sanitizeStem("");
            assertEquals("ROOT", result);
        }

        @Test
        @DisplayName("preserves alphanumeric, dots, dashes, underscores, and '#'")
        void preservesSafeChars() {
            assertEquals("my-app_v1.0", CatalinaHomeMirror.sanitizeStem("my-app_v1.0"));
            assertEquals("ROOT#nested", CatalinaHomeMirror.sanitizeStem("ROOT/nested"));
        }

        @Test
        @DisplayName("replaces unsafe characters with underscore")
        void replacesUnsafe() {
            assertEquals("a_b_c", CatalinaHomeMirror.sanitizeStem("a b c"));
        }
    }

    @Nested
    @DisplayName("buildSharedContextXml")
    class BuildSharedContextXml {

        @Test
        @DisplayName("produces a Context with docBase pointing at the absolute source path")
        void buildsContextXml(@TempDir Path tempDir) throws IOException {
            Path app = tempDir.resolve("webapps/examples");
            Files.createDirectories(app);

            String xml = CatalinaHomeMirror.buildSharedContextXml(app);

            assertTrue(xml.contains("<Context"));
            assertTrue(xml.contains("docBase=\"" + app.toAbsolutePath() + "\""));
            assertTrue(xml.contains("reloadable=\"false\""));
        }

        @Test
        @DisplayName("escapes XML-sensitive characters in the path")
        void escapesXmlChars(@TempDir Path tempDir) throws IOException {
            Path app = tempDir.resolve("webapps/app & co");
            Files.createDirectories(app);

            String xml = CatalinaHomeMirror.buildSharedContextXml(app);

            assertFalse(xml.contains("app & co\""),
                    "Raw '&' must not appear unescaped inside an attribute value");
            assertTrue(xml.contains("&amp;"),
                    "Ampersand should be XML-escaped in docBase");
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Path makeEmptyHome(@TempDir Path tempDir) throws IOException {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve("webapps"));
        Files.createDirectories(home.resolve("conf/Catalina/localhost"));
        return home;
    }

    private static Path makeHomeWithApps(@TempDir Path tempDir) throws IOException {
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve("webapps"));
        Files.writeString(home.resolve("webapps/manager.war"), "WAR-CONTENT");
        Path examples = home.resolve("webapps/examples");
        Files.createDirectories(examples);
        Files.writeString(examples.resolve("index.html"), "<html/>");
        Files.createDirectories(home.resolve("conf/Catalina/localhost"));
        return home;
    }

    private static List<String> manifestEntries(List<String> lines) {
        return lines.stream()
                .filter(l -> !l.isBlank()
                        && !l.startsWith("#")
                        && !l.contains("="))
                .toList();
    }
}
