package com.dev.idea.plugins.tomcat.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures use neutral placeholder paths and JAR names. No real framework or
 * library name appears as a fixture value — the scanner is library-agnostic
 * and the tests must be too. Names like {@code "config/app.xml"},
 * {@code "lib-a-1.0.jar"}, {@code "com/example/Foo.class"} are deliberate
 * generics.
 */
@DisplayName("WarClasspathDuplicateScanner")
class WarClasspathDuplicateScannerTest {

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    /** Lays out a deployed exploded WAR shell: {@code <root>/WEB-INF/{classes,lib}/}. */
    private static Path makeWebInf(Path root) throws IOException {
        Path webInf = root.resolve("WEB-INF");
        Files.createDirectories(webInf.resolve("classes"));
        Files.createDirectories(webInf.resolve("lib"));
        return webInf;
    }

    /** Writes a regular file at the given path, creating parent dirs as needed. */
    private static void writeClassesFile(Path webInf, String relPath, String body) throws IOException {
        Path target = webInf.resolve("classes").resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
    }

    /** Builds a JAR at {@code <webInf>/lib/<name>} containing the listed entries (empty payloads). */
    private static void writeJar(Path webInf, String name, String... entryPaths) throws IOException {
        Path jar = webInf.resolve("lib").resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entryPaths) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("real duplicates are detected")
    class RealDuplicates {

        @Test
        @DisplayName("same path in WEB-INF/classes/ AND a WEB-INF/lib/ JAR")
        void classesVsJar(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeClassesFile(webInf, "config/app.xml", "version=1");
            writeJar(webInf, "lib-a-1.0.jar", "config/app.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("config/app.xml", dups.get(0).logicalPath());
            assertEquals(List.of("WEB-INF/classes/", "WEB-INF/lib/lib-a-1.0.jar"),
                    dups.get(0).locations());
        }

        @Test
        @DisplayName("same path in two JARs")
        void jarVsJar(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a-1.0.jar", "mappings/User.xml");
            writeJar(webInf, "lib-b-2.0.jar", "mappings/User.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("mappings/User.xml", dups.get(0).logicalPath());
            // Locations sorted alphabetically for stable reporting.
            assertEquals(List.of("WEB-INF/lib/lib-a-1.0.jar", "WEB-INF/lib/lib-b-2.0.jar"),
                    dups.get(0).locations());
        }

        @Test
        @DisplayName("three-way duplicate: classes + two JARs")
        void threeWayDuplicate(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeClassesFile(webInf, "data/seed.sql", "INSERT...");
            writeJar(webInf, "lib-x.jar", "data/seed.sql");
            writeJar(webInf, "lib-y.jar", "data/seed.sql");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals(3, dups.get(0).locations().size());
            assertTrue(dups.get(0).locations().contains("WEB-INF/classes/"));
            assertTrue(dups.get(0).locations().contains("WEB-INF/lib/lib-x.jar"));
            assertTrue(dups.get(0).locations().contains("WEB-INF/lib/lib-y.jar"));
        }

        @Test
        @DisplayName("real .class file collision across JARs")
        void classFileCollision(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-old.jar", "com/example/Util.class");
            writeJar(webInf, "lib-new.jar", "com/example/Util.class");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("com/example/Util.class", dups.get(0).logicalPath());
        }

        @Test
        @DisplayName("multiple distinct duplicates are reported in stable alphabetical order")
        void multipleDuplicatesSorted(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeClassesFile(webInf, "zeta/last.xml", "");
            writeClassesFile(webInf, "alpha/first.properties", "");
            writeJar(webInf, "lib.jar", "alpha/first.properties", "zeta/last.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(2, dups.size());
            // Stable alphabetical ordering so log output doesn't churn run-to-run.
            assertEquals("alpha/first.properties", dups.get(0).logicalPath());
            assertEquals("zeta/last.xml", dups.get(1).logicalPath());
        }
    }

    // -----------------------------------------------------------------------
    // Benign filtering
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("benign duplicates are filtered out (silent)")
    class BenignFiltering {

        @Test
        @DisplayName("MANIFEST.MF in every JAR is silent")
        void manifestSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "META-INF/MANIFEST.MF");
            writeJar(webInf, "lib-b.jar", "META-INF/MANIFEST.MF");
            writeJar(webInf, "lib-c.jar", "META-INF/MANIFEST.MF");

            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("META-INF/services/* entries are silent (ServiceLoader spec)")
        void servicesSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "META-INF/services/com.example.spi.MyService");
            writeJar(webInf, "lib-b.jar", "META-INF/services/com.example.spi.MyService");

            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("META-INF/maven/*/pom.properties entries are silent (per-JAR coordinate)")
        void mavenMetadataSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "META-INF/maven/group.x/artifact-a/pom.properties");
            writeJar(webInf, "lib-b.jar", "META-INF/maven/group.y/artifact-b/pom.properties");
            // Both are under META-INF/maven/ — different artifact paths, but the prefix
            // filter must accept the whole tree as benign.
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("multi-release JAR overrides (META-INF/versions/*) are silent")
        void multiReleaseSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            // Same class compiled for two JVM versions — not a true duplicate.
            writeJar(webInf, "lib-a.jar", "META-INF/versions/11/com/example/Foo.class");
            writeJar(webInf, "lib-b.jar", "META-INF/versions/11/com/example/Foo.class");
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("license, notice, and readme files are silent")
        void legalFilesSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "META-INF/LICENSE", "META-INF/NOTICE.txt", "META-INF/README.md");
            writeJar(webInf, "lib-b.jar", "META-INF/LICENSE", "META-INF/NOTICE.txt", "META-INF/README.md");
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("package-info.class across split-package contributions is silent")
        void packageInfoSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "com/example/util/package-info.class");
            writeJar(webInf, "lib-b.jar", "com/example/util/package-info.class");
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("module-info.class is silent (per-JAR JPMS descriptor)")
        void moduleInfoSilent(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar", "module-info.class");
            writeJar(webInf, "lib-b.jar", "module-info.class");
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Mixed cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("mixed real + benign")
    class MixedCases {

        @Test
        @DisplayName("real duplicate is reported; benign duplicates in the same JARs are ignored")
        void realAndBenignTogether(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeJar(webInf, "lib-a.jar",
                    "META-INF/MANIFEST.MF",
                    "META-INF/services/com.example.Foo",
                    "config/app.xml");
            writeJar(webInf, "lib-b.jar",
                    "META-INF/MANIFEST.MF",
                    "META-INF/services/com.example.Foo",
                    "config/app.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size(), "only config/app.xml should surface; MANIFEST + services are benign");
            assertEquals("config/app.xml", dups.get(0).logicalPath());
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty WEB-INF/classes and WEB-INF/lib yields no duplicates")
        void emptyEverything(@TempDir Path root) throws IOException {
            makeWebInf(root);
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("missing WEB-INF/ entirely returns an empty list (not an exception)")
        void missingWebInf(@TempDir Path root) {
            // root has no WEB-INF subdirectory — scanner is defensive.
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("missing WEB-INF/lib still walks WEB-INF/classes")
        void onlyClasses(@TempDir Path root) throws IOException {
            Path webInf = root.resolve("WEB-INF");
            Files.createDirectories(webInf.resolve("classes"));
            writeClassesFile(webInf, "single.xml", "x");
            // No duplicate possible with only one location — empty result.
            assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());
        }

        @Test
        @DisplayName("non-JAR files in WEB-INF/lib are skipped without failing")
        void nonJarFilesInLib(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            // Some users have a README or .txt in WEB-INF/lib; scanner must skip them.
            Files.writeString(webInf.resolve("lib").resolve("README.txt"), "ignore me");
            Files.writeString(webInf.resolve("lib").resolve("notes.md"), "ignore me");
            writeJar(webInf, "lib-a.jar", "config/app.xml");
            writeClassesFile(webInf, "config/app.xml", "x");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("config/app.xml", dups.get(0).logicalPath());
        }

        @Test
        @DisplayName("corrupted JAR does not crash the scan; other JARs still produce results")
        void corruptedJarTolerated(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            // Fake .jar with non-zip content — ZipFile will throw, scanner must
            // log and continue.
            Files.writeString(webInf.resolve("lib").resolve("broken.jar"), "not a zip");
            writeJar(webInf, "lib-a.jar", "config/app.xml");
            writeJar(webInf, "lib-b.jar", "config/app.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("config/app.xml", dups.get(0).logicalPath());
            // Only the two valid JARs contributed; broken.jar was skipped.
            assertEquals(List.of("WEB-INF/lib/lib-a.jar", "WEB-INF/lib/lib-b.jar"),
                    dups.get(0).locations());
        }

        @Test
        @DisplayName("nested directory in WEB-INF/classes recurses fully")
        void nestedClassesRecursive(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            writeClassesFile(webInf, "a/b/c/deep.xml", "x");
            writeJar(webInf, "lib.jar", "a/b/c/deep.xml");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size());
            assertEquals("a/b/c/deep.xml", dups.get(0).logicalPath());
        }

        @Test
        @DisplayName("zip directory entries are ignored")
        void zipDirectoryEntriesIgnored(@TempDir Path root) throws IOException {
            Path webInf = makeWebInf(root);
            Path jar = webInf.resolve("lib").resolve("lib.jar");
            try (OutputStream out = Files.newOutputStream(jar);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("foo/"));     // directory entry
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("foo/bar.xml")); // file entry
                zip.closeEntry();
            }
            writeClassesFile(webInf, "foo/bar.xml", "x");

            List<WarClasspathDuplicateScanner.DuplicateGroup> dups =
                    WarClasspathDuplicateScanner.scan(root);
            assertEquals(1, dups.size(),
                    "directory entry 'foo/' must not be reported as a duplicate of WEB-INF/classes/foo");
            assertEquals("foo/bar.xml", dups.get(0).logicalPath());
        }
    }

    // -----------------------------------------------------------------------
    // isBenign() direct contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("isBenign contract")
    class IsBenign {

        @Test
        @DisplayName("exact-path matches: MANIFEST, persistence, beans, web-fragment, module-info")
        void exactPaths() {
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/MANIFEST.MF"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/persistence.xml"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/beans.xml"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/web-fragment.xml"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("module-info.class"));
        }

        @Test
        @DisplayName("any non-class metadata under META-INF/ is mergeable, whoever put it there")
        void prefixes() {
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/services/com.example.Foo"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/maven/group/artifact/pom.xml"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/versions/17/com/example/Bar.class"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/native/libfoo.so"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/native-image/reflect-config.json"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/app/config.imports"));
            // A name the plugin has never seen must be covered too — that is the point of a rule.
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/some.library.discovery.properties"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/LICENSE.txt"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("META-INF/NOTICE"));
        }

        @Test
        @DisplayName("suffix matches: */package-info.class")
        void suffixes() {
            assertTrue(WarClasspathDuplicateScanner.isBenign("com/example/foo/package-info.class"));
            assertTrue(WarClasspathDuplicateScanner.isBenign("a/b/c/package-info.class"));
        }

        @Test
        @DisplayName("non-matches: ordinary class, ordinary XML, ordinary properties")
        void nonMatches() {
            assertFalse(WarClasspathDuplicateScanner.isBenign("com/example/Foo.class"));
            assertFalse(WarClasspathDuplicateScanner.isBenign("config/app.xml"));
            assertFalse(WarClasspathDuplicateScanner.isBenign("app.properties"));
            // A class under META-INF/ outside versions/ is a real collision, not metadata.
            assertFalse(WarClasspathDuplicateScanner.isBenign("META-INF/com/example/Foo.class"));
            assertFalse(WarClasspathDuplicateScanner.isBenign("mappings/User.xml"));
            // Adjacent-but-not-prefix paths are NOT benign — defensive against the
            // the META-INF rule accidentally swallowing application files
            // whose paths happen to start with the same letters as a metadata path.
            assertFalse(WarClasspathDuplicateScanner.isBenign("META-INF-extra/app.xml"));
        }
    }
}
