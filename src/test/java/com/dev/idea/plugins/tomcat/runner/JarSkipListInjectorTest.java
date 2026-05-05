package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the file-mutation primitive that owns
 * {@code CATALINA_BASE/conf/catalina.properties} updates for every JAR-scan
 * compatibility shim (BCEL/module-info today, container-provided JARs on
 * affected Tomcats, future shims that need to extend the skip list).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Tomcat 7 layout (extends {@code DefaultJarScanner.jarsToSkip}).</li>
 *   <li>Tomcat 8.5+ layout (extends {@code StandardJarScanFilter.jarsToSkip}).</li>
 *   <li>Idempotent rerun with identical input (no growth, ALREADY_COVERED).</li>
 *   <li>Refresh-in-place when input changes (no stacking, REFRESHED).</li>
 *   <li>Multi-line {@code \}-continuation values are merged correctly via
 *       {@link Properties#load} semantics.</li>
 *   <li>Original file content is preserved verbatim above the appendix.</li>
 *   <li>Empty / missing-file edge cases.</li>
 *   <li>Multi-line reason header is rendered with each line prefixed by {@code #}.</li>
 *   <li><b>Legacy marker migration</b>: a previous-release appendix written
 *       under the BCEL-specific marker is replaced in place by the new
 *       generic marker; the file does not accumulate two appendix blocks.</li>
 * </ul>
 */
class JarSkipListInjectorTest {

    private static final String REASON = "Test reason header.";

    private static Path setupBase(Path tempDir, String propertyName, String existingValue) throws Exception {
        Path conf = Files.createDirectories(tempDir.resolve("conf"));
        Files.writeString(conf.resolve("catalina.properties"),
                "# Tomcat property file\n"
                        + propertyName + "=" + existingValue + "\n"
                        + "some.unrelated.property=preserved\n");
        return tempDir;
    }

    private static String readProps(Path catalinaBase) throws Exception {
        return Files.readString(catalinaBase.resolve("conf").resolve("catalina.properties"));
    }

    private static Properties parsedProps(Path catalinaBase) throws Exception {
        Properties p = new Properties();
        try (var is = Files.newInputStream(catalinaBase.resolve("conf").resolve("catalina.properties"))) {
            p.load(is);
        }
        return p;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Nested
    @DisplayName("applyToCatalinaProperties — happy paths")
    class HappyPaths {

        @Test
        @DisplayName("Tomcat 7 layout: extends DefaultJarScanner.jarsToSkip in place")
        void tomcat7Layout(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "bootstrap.jar,tomcat-juli.jar,el-api.jar");

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar", "snakeyaml-2.2.jar"), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.APPENDED, outcome);
            String merged = parsedProps(base).getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip");
            assertTrue(merged.contains("bootstrap.jar"), "Existing entry preserved");
            assertTrue(merged.contains("tomcat-juli.jar"));
            assertTrue(merged.contains("el-api.jar"));
            assertTrue(merged.contains("jackson-core-2.17.0.jar"), "New JAR appended");
            assertTrue(merged.contains("snakeyaml-2.2.jar"));
            assertTrue(readProps(base).contains("some.unrelated.property=preserved"),
                    "Unrelated lines preserved");
        }

        @Test
        @DisplayName("Tomcat 8.5+ layout: extends StandardJarScanFilter.jarsToSkip")
        void tomcat85Layout(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.StandardJarScanFilter.jarsToSkip",
                    "tomcat-*.jar,jakarta.servlet-api*.jar");

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("jaxb-api-2.3.1.jar"), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.APPENDED, outcome);
            String merged = parsedProps(base).getProperty("tomcat.util.scan.StandardJarScanFilter.jarsToSkip");
            assertTrue(merged.contains("tomcat-*.jar"));
            assertTrue(merged.contains("jakarta.servlet-api*.jar"));
            assertTrue(merged.contains("jaxb-api-2.3.1.jar"));
        }

        @Test
        @DisplayName("Neither skip property defined: writes both for forward/backward compat")
        void neitherDefined(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            Files.writeString(conf.resolve("catalina.properties"),
                    "# Custom\nfoo=bar\n");

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    tempDir, List.of("snakeyaml-2.2.jar"), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.APPENDED, outcome);
            Properties p = parsedProps(tempDir);
            assertTrue(p.getProperty("tomcat.util.scan.StandardJarScanFilter.jarsToSkip", "")
                            .contains("snakeyaml-2.2.jar"),
                    "StandardJarScanFilter property added");
            assertTrue(p.getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip", "")
                            .contains("snakeyaml-2.2.jar"),
                    "DefaultJarScanner property added");
        }
    }

    @Nested
    @DisplayName("applyToCatalinaProperties — idempotency and refresh")
    class Idempotency {

        @Test
        @DisplayName("Identical input on rerun returns ALREADY_COVERED, no file growth")
        void idempotentRerun(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            List<String> jars = List.of("jackson-core-2.17.0.jar", "byte-buddy-1.14.9.jar");

            JarSkipListInjector.applyToCatalinaProperties(base, jars, REASON, null);
            String first = readProps(base);
            assertEquals(1, countOccurrences(first, JarSkipListInjector.APPENDIX_MARKER),
                    "First run produces exactly one marker");

            JarSkipListInjector.Outcome second =
                    JarSkipListInjector.applyToCatalinaProperties(base, jars, REASON, null);
            assertEquals(JarSkipListInjector.Outcome.ALREADY_COVERED, second);
            String afterSecond = readProps(base);
            assertEquals(1, countOccurrences(afterSecond, JarSkipListInjector.APPENDIX_MARKER),
                    "File does not accumulate appendix blocks");
        }

        @Test
        @DisplayName("Adding a JAR on rerun refreshes the appendix in place (REFRESHED)")
        void refreshOnNewInput(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");

            JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar"), REASON, null);

            JarSkipListInjector.Outcome second = JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar", "snakeyaml-2.2.jar"), REASON, null);
            assertEquals(JarSkipListInjector.Outcome.REFRESHED, second);

            String content = readProps(base);
            assertEquals(1, countOccurrences(content, JarSkipListInjector.APPENDIX_MARKER),
                    "Appendix is replaced in place, not duplicated");
            assertTrue(content.contains("snakeyaml-2.2.jar"));
            assertTrue(content.contains("jackson-core-2.17.0.jar"));
        }

        @Test
        @DisplayName("Legacy 1.0.9 marker is recognised and replaced in place on upgrade")
        void legacyMarkerUpgrade(@TempDir Path tempDir) throws Exception {
            // A user upgrading from 1.0.9 may have a pinned CATALINA_BASE that
            // contains an appendix written under the OLD BCEL-specific marker.
            // The injector must recognise that marker and replace the block
            // rather than appending a second new-marker block beside it.
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            String legacyAppendix =
                    "\n# DevTomcat: BCEL/module-info compatibility appendix (auto-generated)\n"
                    + "# Older Tomcat releases ship a BCEL parser that throws...\n"
                    + "tomcat.util.scan.DefaultJarScanner.jarsToSkip=tomcat-juli.jar,old-jar.jar\n"
                    + "# DevTomcat: end of BCEL/module-info appendix\n";
            String content =
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip=tomcat-juli.jar\n"
                    + "trailer=keep\n"
                    + legacyAppendix;
            Files.writeString(conf.resolve("catalina.properties"), content);

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    tempDir, List.of("new-jar.jar"), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.REFRESHED, outcome,
                    "Legacy marker presence must produce REFRESHED, not APPENDED");
            String after = readProps(tempDir);

            // The OLD marker text must be gone (so the file does not accumulate
            // marker pairs across upgrade). The legacy appendix's JAR list,
            // however, is preserved — Properties.load applies later-wins so the
            // legacy override's value (tomcat-juli.jar, old-jar.jar) is what
            // we read as 'existing', and we union it with the new input.
            assertFalse(after.contains("# DevTomcat: BCEL/module-info compatibility appendix"),
                    "Legacy marker line must be removed; the file must not accumulate marker pairs");
            assertFalse(after.contains("# DevTomcat: end of BCEL/module-info appendix"),
                    "Legacy end-marker must also be removed");
            assertEquals(1, countOccurrences(after, JarSkipListInjector.APPENDIX_MARKER),
                    "Exactly one marker pair after the upgrade replace");
            assertTrue(after.contains("trailer=keep"),
                    "Trailing original content must survive the in-place replace");

            // Effective merged value preserves both legacy entries and the new one.
            String merged = parsedProps(tempDir)
                    .getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip");
            assertTrue(merged.contains("tomcat-juli.jar"),
                    "Original CATALINA_HOME-shipped entry preserved");
            assertTrue(merged.contains("old-jar.jar"),
                    "Legacy appendix's JAR list is preserved through the union, not discarded");
            assertTrue(merged.contains("new-jar.jar"),
                    "New JAR added to the merged value");
        }
    }

    @Nested
    @DisplayName("applyToCatalinaProperties — content preservation and edge cases")
    class Preservation {

        @Test
        @DisplayName("Multi-line property value with backslash continuation parses and merges correctly")
        void multilineContinuation(@TempDir Path tempDir) throws Exception {
            Path conf = Files.createDirectories(tempDir.resolve("conf"));
            String original =
                    "# Header\n"
                    + "common.loader=...\n"
                    + "\n"
                    + "tomcat.util.scan.DefaultJarScanner.jarsToSkip=\\\n"
                    + "bootstrap.jar,\\\n"
                    + "tomcat-juli.jar\n"
                    + "\n"
                    + "# Trailer\n";
            Files.writeString(conf.resolve("catalina.properties"), original);

            JarSkipListInjector.applyToCatalinaProperties(
                    tempDir, List.of("snakeyaml-2.2.jar"), REASON, null);

            String after = readProps(tempDir);
            int markerIdx = after.indexOf(JarSkipListInjector.APPENDIX_MARKER);
            int trailerIdx = after.indexOf("# Trailer");
            assertTrue(markerIdx > trailerIdx,
                    "Appendix must be after the original content");
            String merged = parsedProps(tempDir)
                    .getProperty("tomcat.util.scan.DefaultJarScanner.jarsToSkip");
            assertTrue(merged.contains("bootstrap.jar"),
                    "Continuation-line entry preserved in merged value");
            assertTrue(merged.contains("tomcat-juli.jar"));
            assertTrue(merged.contains("snakeyaml-2.2.jar"));
        }

        @Test
        @DisplayName("Multi-line reason header is rendered with each line prefixed by '#'")
        void multilineReasonHeader(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            String reason = "First line of reason.\nSecond line of reason.\nThird.";

            JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("foo.jar"), reason, null);

            String after = readProps(base);
            assertTrue(after.contains("# First line of reason."),
                    "Each reason line prefixed by '# ' so the file remains valid properties syntax");
            assertTrue(after.contains("# Second line of reason."));
            assertTrue(after.contains("# Third."));
        }

        @Test
        @DisplayName("Empty input is a no-op")
        void emptyInput(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            String before = readProps(base);

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of(), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.NO_JARS, outcome);
            assertEquals(before, readProps(base));
        }

        @Test
        @DisplayName("Missing catalina.properties is reported, not crashed on")
        void missingFile(@TempDir Path tempDir) throws Exception {
            Files.createDirectories(tempDir.resolve("conf"));

            JarSkipListInjector.Outcome outcome = JarSkipListInjector.applyToCatalinaProperties(
                    tempDir, List.of("foo.jar"), REASON, null);

            assertEquals(JarSkipListInjector.Outcome.PROPERTIES_FILE_UNAVAILABLE, outcome);
        }
    }

    @Nested
    @DisplayName("applyToCatalinaProperties — logger surface")
    class LoggerOutput {

        @Test
        @DisplayName("Logger receives a one-line summary with the JAR count and reason header")
        void loggerSummary(@TempDir Path tempDir) throws Exception {
            Path base = setupBase(tempDir,
                    "tomcat.util.scan.DefaultJarScanner.jarsToSkip",
                    "tomcat-juli.jar");
            TomcatDeploymentLogger logger = org.mockito.Mockito.mock(TomcatDeploymentLogger.class);

            JarSkipListInjector.applyToCatalinaProperties(
                    base, List.of("jackson-core-2.17.0.jar"),
                    "Module-info compatibility for Tomcat 7.", logger);

            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("JAR-scan compatibility"));
            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("Module-info compatibility for Tomcat 7."));
        }
    }

    @Nested
    @DisplayName("replaceOrAppendAppendix — file-rewrite primitive")
    class ReplaceOrAppend {

        @Test
        @DisplayName("appends when no prior appendix is present")
        void appendsWhenAbsent() {
            String original = "common.loader=foo\nfoo=bar\n";
            String appendix = "\n" + JarSkipListInjector.APPENDIX_MARKER + "\n"
                    + "x=y\n" + JarSkipListInjector.APPENDIX_END_MARKER + "\n";
            String result = JarSkipListInjector.replaceOrAppendAppendix(original, appendix);
            assertTrue(result.startsWith(original));
            assertTrue(result.endsWith(appendix));
        }

        @Test
        @DisplayName("replaces the existing appendix block in place, no duplication")
        void replacesInPlace() {
            String header = "common.loader=foo\n";
            String oldBlock = "\n" + JarSkipListInjector.APPENDIX_MARKER + "\n"
                    + "old.line=1\n" + JarSkipListInjector.APPENDIX_END_MARKER + "\n";
            String trailer = "trailing=keep\n";
            String original = header + oldBlock + trailer;

            String newBlock = "\n" + JarSkipListInjector.APPENDIX_MARKER + "\n"
                    + "new.line=2\n" + JarSkipListInjector.APPENDIX_END_MARKER + "\n";
            String result = JarSkipListInjector.replaceOrAppendAppendix(original, newBlock);

            assertTrue(result.contains(header));
            assertTrue(result.contains(trailer));
            assertTrue(result.contains("new.line=2"));
            assertFalse(result.contains("old.line=1"));
            assertEquals(1, countOccurrences(result, JarSkipListInjector.APPENDIX_MARKER));
        }

        @Test
        @DisplayName("recognises legacy 1.0.9 BCEL marker pair and replaces it in place")
        void legacyMarkerReplaced() {
            String header = "common.loader=foo\n";
            String legacyBlock =
                    "\n# DevTomcat: BCEL/module-info compatibility appendix (auto-generated)\n"
                    + "old.legacy=1\n"
                    + "# DevTomcat: end of BCEL/module-info appendix\n";
            String trailer = "trailing=keep\n";
            String original = header + legacyBlock + trailer;

            String newBlock = "\n" + JarSkipListInjector.APPENDIX_MARKER + "\n"
                    + "new.line=2\n" + JarSkipListInjector.APPENDIX_END_MARKER + "\n";
            String result = JarSkipListInjector.replaceOrAppendAppendix(original, newBlock);

            assertFalse(result.contains("old.legacy=1"),
                    "Legacy block contents must be removed");
            assertFalse(result.contains("# DevTomcat: BCEL/module-info compatibility appendix"),
                    "Legacy marker must be removed");
            assertTrue(result.contains(trailer),
                    "Trailing content preserved");
            assertTrue(result.contains("new.line=2"),
                    "New block written in place of the legacy one");
        }
    }
}
