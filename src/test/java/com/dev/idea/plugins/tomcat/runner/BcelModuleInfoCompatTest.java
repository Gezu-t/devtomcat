package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the BCEL/module-info compatibility shim:
 * <ul>
 *   <li>Version gate accepts every affected Tomcat and rejects every fixed one.</li>
 *   <li>JAR scanner detects {@code module-info.class} at the root <em>and</em> under
 *       {@code META-INF/versions/<n>/} (Multi-Release JAR layout).</li>
 *   <li>Edge cases (null/blank/garbage versions, corrupt JARs) fall back to safe
 *       defaults rather than throwing or producing false positives.</li>
 * </ul>
 *
 * Constructing real {@link TomcatInfo} instances avoids fragile mocking; the
 * version field is the only thing the gate inspects.
 */
class BcelModuleInfoCompatTest {

    private static TomcatInfo info(String version) {
        TomcatInfo t = new TomcatInfo();
        t.setVersion(version);
        return t;
    }

    @Nested
    @DisplayName("isAffectedByBcelModuleInfoBug — version gate")
    class VersionGate {

        @Test
        @DisplayName("Tomcat 7.x is always affected")
        void tomcat7AlwaysAffected() {
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("7.0.109")));
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("7.0.0")));
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("7.99.999")));
        }

        @Test
        @DisplayName("Tomcat 8.0.x is affected (branch never received the fix)")
        void tomcat80Affected() {
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.0.0")));
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.0.53")));
        }

        @Test
        @DisplayName("Tomcat 8.5.x: affected through 8.5.50, fixed in 8.5.51+")
        void tomcat85Boundary() {
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.5.0")));
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.5.50")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.5.51")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.5.52")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("8.5.99")));
        }

        @Test
        @DisplayName("Tomcat 9.0.x: affected through 9.0.30, fixed in 9.0.31+")
        void tomcat90Boundary() {
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.0")));
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.30")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.31")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.99")));
        }

        @Test
        @DisplayName("Tomcat 10.x and 11.x are never affected")
        void tomcat10And11NotAffected() {
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("10.0.0")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("10.1.30")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("11.0.0")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("11.0.5")));
        }

        @Test
        @DisplayName("trailing build number (9.0.56.0) parses cleanly")
        void trailingBuildNumber() {
            // ServerInfo.properties typically writes "MAJOR.MINOR.PATCH.BUILD".
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.30.0")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0.31.0")));
        }

        @Test
        @DisplayName("null TomcatInfo is treated as not-affected (conservative default)")
        void nullInfoNotAffected() {
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(null));
        }

        @Test
        @DisplayName("blank or unparseable version is treated as not-affected")
        void blankOrUnparseableNotAffected() {
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("   ")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("not-a-version")));
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.x.31")));
        }

        @Test
        @DisplayName("major-only or major.minor versions still parse")
        void shortVersions() {
            // 7 alone -> affected (7.0.0 implied).
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("7")));
            // 9.0 alone -> affected (9.0.0 implied, < 9.0.31).
            assertTrue(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("9.0")));
            // 11 alone -> not affected.
            assertFalse(BcelModuleInfoCompat.isAffectedByBcelModuleInfoBug(info("11")));
        }
    }

    @Nested
    @DisplayName("findJarsContainingModuleInfo — JAR scanning")
    class JarScan {

        @Test
        @DisplayName("missing or non-directory webInfLib returns empty list")
        void missingDir(@TempDir Path tmp) {
            assertTrue(BcelModuleInfoCompat.findJarsContainingModuleInfo(tmp.resolve("nope")).isEmpty());

            Path file = tmp.resolve("not-a-dir");
            try { Files.writeString(file, "x"); } catch (Exception e) { fail(e); }
            assertTrue(BcelModuleInfoCompat.findJarsContainingModuleInfo(file).isEmpty());
        }

        @Test
        @DisplayName("empty webInfLib returns empty list")
        void emptyDir(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            assertTrue(BcelModuleInfoCompat.findJarsContainingModuleInfo(lib).isEmpty());
        }

        @Test
        @DisplayName("JARs without module-info are not flagged")
        void noModuleInfo(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("plain.jar"), "com/example/Foo.class", "META-INF/MANIFEST.MF");
            assertTrue(BcelModuleInfoCompat.findJarsContainingModuleInfo(lib).isEmpty());
        }

        @Test
        @DisplayName("module-info.class at root is detected")
        void rootModuleInfo(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("modular.jar"), "module-info.class", "com/example/Foo.class");
            assertEquals(List.of("modular.jar"),
                    BcelModuleInfoCompat.findJarsContainingModuleInfo(lib));
        }

        @Test
        @DisplayName("META-INF/versions/9/module-info.class (Multi-Release) is detected")
        void multiReleaseModuleInfo(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("mr.jar"),
                    "com/example/Foo.class",
                    "META-INF/versions/9/module-info.class");
            assertEquals(List.of("mr.jar"),
                    BcelModuleInfoCompat.findJarsContainingModuleInfo(lib));
        }

        @Test
        @DisplayName("higher Multi-Release versions (11, 17) also match")
        void higherMultiReleaseVersions(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("a.jar"), "META-INF/versions/11/module-info.class");
            writeJar(lib.resolve("b.jar"), "META-INF/versions/17/module-info.class");
            assertEquals(List.of("a.jar", "b.jar"),
                    BcelModuleInfoCompat.findJarsContainingModuleInfo(lib));
        }

        @Test
        @DisplayName("near-miss entries are not false positives")
        void nearMissNotFlagged(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("nearmiss.jar"),
                    "module-info.classx",                     // wrong suffix
                    "META-INF/versions/module-info.class",    // missing version segment
                    "META-INF/versions/abc/module-info.class",// non-numeric version
                    "module-info");                           // no .class
            assertTrue(BcelModuleInfoCompat.findJarsContainingModuleInfo(lib).isEmpty());
        }

        @Test
        @DisplayName("results are deterministic, sorted, and exclude non-jars")
        void deterministicSortedExcludesNonJars(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("c.jar"), "module-info.class");
            writeJar(lib.resolve("a.jar"), "module-info.class");
            writeJar(lib.resolve("b.jar"), "module-info.class");
            // A non-JAR named "module-info.class" must not be picked up by the directory walk.
            Files.writeString(lib.resolve("module-info.class"), "not a jar");
            // A nested directory must not be inspected as a JAR.
            Files.createDirectory(lib.resolve("subdir"));

            assertEquals(List.of("a.jar", "b.jar", "c.jar"),
                    BcelModuleInfoCompat.findJarsContainingModuleInfo(lib));
        }

        @Test
        @DisplayName("corrupt JAR is skipped instead of aborting the scan")
        void corruptJarSkipped(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            // A file with a .jar extension that is NOT a valid zip.
            Files.writeString(lib.resolve("broken.jar"), "this is not a zip file at all");
            // A real JAR alongside, with module-info, must still be detected.
            writeJar(lib.resolve("good.jar"), "module-info.class");

            List<String> result = BcelModuleInfoCompat.findJarsContainingModuleInfo(lib);
            assertEquals(List.of("good.jar"), result,
                    "Corrupt JAR must be skipped without aborting the scan; valid JARs alongside still detected");
        }

        @Test
        @DisplayName("upper-case .JAR extension is recognised")
        void upperCaseJarExtension(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectory(tmp.resolve("lib"));
            writeJar(lib.resolve("UPPER.JAR"), "module-info.class");
            assertEquals(List.of("UPPER.JAR"),
                    BcelModuleInfoCompat.findJarsContainingModuleInfo(lib));
        }
    }

    @Nested
    @DisplayName("applyModuleInfoSkipToCatalinaProperties — thin wrapper over JarSkipListInjector")
    class WrapperDelegate {

        @Test
        @DisplayName("delegates to JarSkipListInjector and reports REFRESHED on rerun with new input")
        void delegatesToInjector(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            Files.writeString(conf.resolve("catalina.properties"),
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip=tomcat-juli.jar\n");

            // First run appends.
            JarSkipListInjector.Outcome first =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            tempDir, List.of("jackson-core-2.17.0.jar"), null);
            assertEquals(JarSkipListInjector.Outcome.APPENDED, first);

            // Second run with the same input is a no-op (already covered).
            JarSkipListInjector.Outcome second =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            tempDir, List.of("jackson-core-2.17.0.jar"), null);
            assertEquals(JarSkipListInjector.Outcome.ALREADY_COVERED, second);
        }

        @Test
        @DisplayName("empty input returns NO_JARS, never touches the file")
        void emptyInput(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            Files.writeString(conf.resolve("catalina.properties"),
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip=tomcat-juli.jar\n");
            String before = Files.readString(conf.resolve("catalina.properties"));

            JarSkipListInjector.Outcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            tempDir, List.of(), null);

            assertEquals(JarSkipListInjector.Outcome.NO_JARS, outcome);
            assertEquals(before, Files.readString(conf.resolve("catalina.properties")));
        }
    }

    @Nested
    @DisplayName("jarContainsModuleInfo — single-JAR direct check")
    class SingleJarCheck {

        @Test
        @DisplayName("non-existent file returns false rather than throwing")
        void nonExistentFile(@TempDir Path tmp) {
            assertFalse(BcelModuleInfoCompat.jarContainsModuleInfo(tmp.resolve("missing.jar")));
        }

        @Test
        @DisplayName("JAR with both root and Multi-Release module-info still returns true")
        void bothPresent(@TempDir Path tmp) throws Exception {
            Path jar = tmp.resolve("both.jar");
            writeJar(jar, "module-info.class", "META-INF/versions/9/module-info.class");
            assertTrue(BcelModuleInfoCompat.jarContainsModuleInfo(jar));
        }
    }

    // ---------------------------------------------------------------- //
    // Test helpers
    // ---------------------------------------------------------------- //

    /** Writes a minimal JAR (zip) at {@code path} containing {@code entries} with empty bytes. */
    private static void writeJar(Path path, String... entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String e : entries) {
                zos.putNextEntry(new ZipEntry(e));
                zos.closeEntry();
            }
        }
        Files.write(path, baos.toByteArray());
    }
}
