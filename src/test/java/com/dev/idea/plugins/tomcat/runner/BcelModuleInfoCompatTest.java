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
    @DisplayName("applyModuleInfoSkipToCatalinaProperties — skip-list injection")
    class CatalinaPropertiesInjection {

        /**
         * Builds a minimal CATALINA_BASE/conf/ directory with a {@code catalina.properties}
         * containing the given existing skip-list value under the given property name.
         */
        private Path setupCatalinaBase(@TempDir Path tempDir,
                                       String propertyName,
                                       String existingValue) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            String content = "# Tomcat property file\n"
                    + propertyName + "=" + existingValue + "\n"
                    + "some.unrelated.property=preserved\n";
            Files.writeString(conf.resolve("catalina.properties"), content);
            return tempDir;
        }

        private String readProps(Path catalinaBase) throws Exception {
            return Files.readString(catalinaBase.resolve("conf").resolve("catalina.properties"));
        }

        private Properties parsedProps(Path catalinaBase) throws Exception {
            Properties p = new Properties();
            try (var is = Files.newInputStream(catalinaBase.resolve("conf").resolve("catalina.properties"))) {
                p.load(is);
            }
            return p;
        }

        @Test
        @DisplayName("Tomcat 7 layout: extends DefaultJarScanner.jarsToSkip in place")
        void tomcat7Layout(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "bootstrap.jar,tomcat-juli.jar,el-api.jar");

            BcelModuleInfoCompat.InjectionOutcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            base,
                            List.of("jackson-core-2.17.0.jar", "snakeyaml-2.2.jar"),
                            null);

            assertEquals(BcelModuleInfoCompat.InjectionOutcome.APPENDED, outcome);
            Properties p = parsedProps(base);
            String merged = p.getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip");
            // Properties.load applies later-wins semantics, so the appendix
            // override at the end of the file becomes the effective value.
            assertTrue(merged.contains("bootstrap.jar"), "Existing entries must be preserved");
            assertTrue(merged.contains("tomcat-juli.jar"));
            assertTrue(merged.contains("el-api.jar"));
            assertTrue(merged.contains("jackson-core-2.17.0.jar"), "Modular JAR must be appended");
            assertTrue(merged.contains("snakeyaml-2.2.jar"));
            // Original lines untouched (we only appended).
            assertTrue(readProps(base).contains("some.unrelated.property=preserved"));
        }

        @Test
        @DisplayName("Tomcat 8.5+ layout: extends StandardJarScanFilter.jarsToSkip")
        void tomcat85Layout(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.StandardJarScanFilter.jarsToSkip",
                    "tomcat-*.jar,jakarta.servlet-api*.jar");

            BcelModuleInfoCompat.InjectionOutcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            base,
                            List.of("jaxb-api-2.3.1.jar"),
                            null);

            assertEquals(BcelModuleInfoCompat.InjectionOutcome.APPENDED, outcome);
            String merged = parsedProps(base)
                    .getProperty("tomcat.util.scan.StandardJarScanFilter.jarsToSkip");
            assertTrue(merged.contains("tomcat-*.jar"));
            assertTrue(merged.contains("jakarta.servlet-api*.jar"));
            assertTrue(merged.contains("jaxb-api-2.3.1.jar"));
        }

        @Test
        @DisplayName("Idempotent: rerun with identical input refreshes appendix in place, no growth")
        void idempotentRerun(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            List<String> jars = List.of("jackson-core-2.17.0.jar", "byte-buddy-1.14.9.jar");

            BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(base, jars, null);
            String first = readProps(base);
            int firstAppendixCount = countOccurrences(first, BcelModuleInfoCompat.APPENDIX_MARKER);
            assertEquals(1, firstAppendixCount, "First run must produce exactly one appendix marker");

            // Second invocation: same JARs, no change to existing entries.
            BcelModuleInfoCompat.InjectionOutcome second =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(base, jars, null);
            assertEquals(BcelModuleInfoCompat.InjectionOutcome.ALREADY_COVERED, second,
                    "Second run with identical input must report ALREADY_COVERED");
            String afterSecond = readProps(base);
            assertEquals(1, countOccurrences(afterSecond, BcelModuleInfoCompat.APPENDIX_MARKER),
                    "File must not accumulate duplicate appendix blocks");
        }

        @Test
        @DisplayName("Different inputs across runs refresh the appendix in place")
        void refreshOnNewInput(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");

            BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar"), null);
            BcelModuleInfoCompat.InjectionOutcome second =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            base, List.of("jackson-core-2.17.0.jar", "snakeyaml-2.2.jar"), null);
            assertEquals(BcelModuleInfoCompat.InjectionOutcome.REFRESHED, second,
                    "Adding a new JAR on rerun must refresh the appendix");
            String content = readProps(base);
            assertEquals(1, countOccurrences(content, BcelModuleInfoCompat.APPENDIX_MARKER),
                    "Appendix must be replaced in place, not duplicated");
            assertTrue(content.contains("snakeyaml-2.2.jar"), "New JAR present after refresh");
            assertTrue(content.contains("jackson-core-2.17.0.jar"), "Original JAR still present after refresh");
        }

        @Test
        @DisplayName("Original property block is preserved verbatim above the appendix")
        void originalContentPreserved(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            // Multi-line property value with \-continuation, comments, and blank lines.
            String original =
                    "# Header comment\n"
                    + "common.loader=...\n"
                    + "\n"
                    + "tomcat.util.scan.DefaultJarScanner.jarsToSkip=\\\n"
                    + "bootstrap.jar,\\\n"
                    + "tomcat-juli.jar\n"
                    + "\n"
                    + "# Trailing comment\n";
            Files.writeString(conf.resolve("catalina.properties"), original);

            BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                    tempDir, List.of("snakeyaml-2.2.jar"), null);

            String after = readProps(tempDir);
            // Every original line must still be there, in order.
            assertTrue(after.startsWith(original) || after.contains(original.trim()),
                    "Original content must be preserved. Got:\n" + after);
            // Appendix must be after the original content.
            int markerIdx = after.indexOf(BcelModuleInfoCompat.APPENDIX_MARKER);
            int origEndIdx = after.indexOf("# Trailing comment");
            assertTrue(markerIdx > origEndIdx,
                    "Appendix must come AFTER the original content");
            // Existing skip entries are still in the merged value via Properties.load semantics.
            String merged = parsedProps(tempDir).getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip");
            assertTrue(merged.contains("bootstrap.jar"), "Original entry preserved in effective value");
            assertTrue(merged.contains("tomcat-juli.jar"));
            assertTrue(merged.contains("snakeyaml-2.2.jar"));
        }

        @Test
        @DisplayName("Empty modular-jar list is a no-op")
        void emptyInputIsNoOp(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            String before = readProps(base);

            BcelModuleInfoCompat.InjectionOutcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            base, List.of(), null);

            assertEquals(BcelModuleInfoCompat.InjectionOutcome.NO_MODULAR_JARS, outcome);
            assertEquals(before, readProps(base), "File must be untouched on empty input");
        }

        @Test
        @DisplayName("Missing catalina.properties is reported, not crashed on")
        void missingPropertiesFile(@TempDir Path tempDir) throws Exception {
            // Create CATALINA_BASE but no conf/catalina.properties.
            Files.createDirectories(tempDir.resolve("conf"));

            BcelModuleInfoCompat.InjectionOutcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            tempDir, List.of("foo.jar"), null);

            assertEquals(BcelModuleInfoCompat.InjectionOutcome.PROPERTIES_FILE_UNAVAILABLE, outcome);
        }

        @Test
        @DisplayName("Neither skip property defined: writes both names so any Tomcat picks it up")
        void neitherPropertyDefined(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            Files.writeString(conf.resolve("catalina.properties"),
                    "# Custom catalina.properties\nfoo=bar\n");

            BcelModuleInfoCompat.InjectionOutcome outcome =
                    BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                            tempDir, List.of("snakeyaml-2.2.jar"), null);

            assertEquals(BcelModuleInfoCompat.InjectionOutcome.APPENDED, outcome);
            Properties p = parsedProps(tempDir);
            assertTrue(p.getProperty("tomcat.util.scan.StandardJarScanFilter.jarsToSkip", "")
                            .contains("snakeyaml-2.2.jar"),
                    "StandardJarScanFilter property must be added when neither was present");
            assertTrue(p.getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip", "")
                            .contains("snakeyaml-2.2.jar"),
                    "DefaultJarScanner property must also be added for legacy compatibility");
        }

        @Test
        @DisplayName("Logger receives a one-line summary on success")
        void loggerSurfacesSummary(@TempDir Path tempDir) throws Exception {
            Path base = setupCatalinaBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(
                            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger.class);

            BcelModuleInfoCompat.applyModuleInfoSkipToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar"), logger);

            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("module-info compatibility"));
            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("jackson-core-2.17.0.jar"));
        }

        private int countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) >= 0) {
                count++;
                idx += needle.length();
            }
            return count;
        }
    }

    @Nested
    @DisplayName("replaceOrAppendAppendix — file-rewrite primitive")
    class ReplaceOrAppendAppendix {

        @Test
        @DisplayName("appends when no prior appendix is present")
        void appendsWhenAbsent() {
            String original = "common.loader=foo\nfoo=bar\n";
            String appendix = "\n" + BcelModuleInfoCompat.APPENDIX_MARKER + "\n"
                    + "x=y\n" + BcelModuleInfoCompat.APPENDIX_END_MARKER + "\n";
            String result = BcelModuleInfoCompat.replaceOrAppendAppendix(original, appendix);
            assertTrue(result.startsWith(original), "Original must be preserved as a prefix");
            assertTrue(result.endsWith(appendix), "Appendix must be at the end");
        }

        @Test
        @DisplayName("replaces the existing appendix block in place, no duplication")
        void replacesInPlace() {
            String header = "common.loader=foo\n";
            String oldBlock = "\n" + BcelModuleInfoCompat.APPENDIX_MARKER + "\n"
                    + "old.line=1\n" + BcelModuleInfoCompat.APPENDIX_END_MARKER + "\n";
            String trailer = "trailing=keep\n";
            String original = header + oldBlock + trailer;

            String newBlock = "\n" + BcelModuleInfoCompat.APPENDIX_MARKER + "\n"
                    + "new.line=2\n" + BcelModuleInfoCompat.APPENDIX_END_MARKER + "\n";
            String result = BcelModuleInfoCompat.replaceOrAppendAppendix(original, newBlock);

            assertTrue(result.contains(header), "Header preserved");
            assertTrue(result.contains(trailer), "Trailing content preserved (replace must be in place)");
            assertTrue(result.contains("new.line=2"), "New appendix written");
            assertFalse(result.contains("old.line=1"), "Old appendix removed");
            // Exactly one marker pair.
            assertEquals(1,
                    (result.length() - result.replace(BcelModuleInfoCompat.APPENDIX_MARKER, "").length())
                            / BcelModuleInfoCompat.APPENDIX_MARKER.length(),
                    "Exactly one appendix marker after replace");
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
