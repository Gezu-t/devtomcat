package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.runner.TomcatPreflightValidator.PreflightIssue;
import com.dev.idea.plugins.tomcat.runner.TomcatPreflightValidator.PreflightResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatPreflightValidator")
class TomcatPreflightValidatorTest {

    // =========================================================================
    // parseSystemProperties — production method, handles quoting
    // =========================================================================

    @Nested
    @DisplayName("parseSystemProperties")
    class ParseSystemProperties {

        @Test
        @DisplayName("parses simple -Dkey=value")
        void parsesSimple() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Dfoo.bar=/some/path");
            assertEquals(Map.of("foo.bar", "/some/path"), result);
        }

        @Test
        @DisplayName("parses multiple properties interspersed with other flags")
        void parsesMultipleMixed() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Dcatalina.home=/opt/tomcat -Xmx512m -Djava.io.tmpdir=/tmp/tc");
            assertEquals(2, result.size());
            assertEquals("/opt/tomcat", result.get("catalina.home"));
            assertEquals("/tmp/tc", result.get("java.io.tmpdir"));
        }

        @Test
        @DisplayName("handles double-quoted value with spaces")
        void handlesDoubleQuotedSpaces() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Djavax.net.ssl.keyStore=\"/path/with spaces/keystore.jks\"");
            assertEquals("/path/with spaces/keystore.jks", result.get("javax.net.ssl.keyStore"));
        }

        @Test
        @DisplayName("handles single-quoted value with spaces")
        void handlesSingleQuotedSpaces() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Djavax.net.ssl.keyStore='/path/with spaces/keystore.jks'");
            assertEquals("/path/with spaces/keystore.jks", result.get("javax.net.ssl.keyStore"));
        }

        @Test
        @DisplayName("returns empty map for empty string")
        void emptyString() {
            assertTrue(TomcatPreflightValidator.parseSystemProperties("").isEmpty());
        }

        @Test
        @DisplayName("ignores non-property flags like -Xmx")
        void ignoresNonPropertyFlags() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Xmx512m -Xms256m --add-opens=java.base/java.lang=ALL-UNNAMED");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("handles value containing equals sign")
        void valueContainingEquals() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Dsome.prop=key=value");
            assertEquals("key=value", result.get("some.prop"));
        }

        @Test
        @DisplayName("handles Windows backslash paths")
        void windowsBackslashPaths() {
            Map<String, String> result = TomcatPreflightValidator.parseSystemProperties(
                    "-Dcatalina.home=C:\\Tomcat\\apache-tomcat-9");
            assertEquals("C:\\Tomcat\\apache-tomcat-9", result.get("catalina.home"));
        }
    }

    // =========================================================================
    // stripQuotes — production method
    // =========================================================================

    @Nested
    @DisplayName("stripQuotes")
    class StripQuotes {

        @Test
        @DisplayName("strips matched double quotes")
        void stripsDoubleQuotes() {
            assertEquals("hello", TomcatPreflightValidator.stripQuotes("\"hello\""));
        }

        @Test
        @DisplayName("does not strip single quotes (handled by normalizeSingleQuotes)")
        void doesNotStripSingleQuotes() {
            assertEquals("'hello'", TomcatPreflightValidator.stripQuotes("'hello'"));
        }

        @Test
        @DisplayName("does not strip mismatched quotes")
        void doesNotStripMismatched() {
            assertEquals("\"hello'", TomcatPreflightValidator.stripQuotes("\"hello'"));
        }

        @Test
        @DisplayName("returns empty unquoted string")
        void handlesEmptyQuoted() {
            assertEquals("", TomcatPreflightValidator.stripQuotes("\"\""));
        }

        @Test
        @DisplayName("returns unquoted string unchanged")
        void noQuotes() {
            assertEquals("/some/path", TomcatPreflightValidator.stripQuotes("/some/path"));
        }
    }

    // =========================================================================
    // normalizeSingleQuotes — production method
    // =========================================================================

    @Nested
    @DisplayName("normalizeSingleQuotes")
    class NormalizeSingleQuotes {

        @Test
        @DisplayName("converts matched single quotes to double quotes")
        void convertsMatchedSingleQuotes() {
            assertEquals("-Dfoo=\"bar baz\"",
                    TomcatPreflightValidator.normalizeSingleQuotes("-Dfoo='bar baz'"));
        }

        @Test
        @DisplayName("leaves double-quoted strings unchanged")
        void leavesDoubleQuotesAlone() {
            assertEquals("-Dfoo=\"bar baz\"",
                    TomcatPreflightValidator.normalizeSingleQuotes("-Dfoo=\"bar baz\""));
        }

        @Test
        @DisplayName("leaves unmatched single quote as-is")
        void unmatchedSingleQuote() {
            assertEquals("it's fine",
                    TomcatPreflightValidator.normalizeSingleQuotes("it's fine"));
        }

        @Test
        @DisplayName("handles no quotes")
        void noQuotes() {
            assertEquals("-Dfoo=bar",
                    TomcatPreflightValidator.normalizeSingleQuotes("-Dfoo=bar"));
        }

        @Test
        @DisplayName("handles multiple quoted segments")
        void multipleQuotedSegments() {
            assertEquals("-Da=\"x y\" -Db=\"p q\"",
                    TomcatPreflightValidator.normalizeSingleQuotes("-Da='x y' -Db='p q'"));
        }
    }

    // =========================================================================
    // checkRequiredSystemProperties — production method
    // =========================================================================

    @Nested
    @DisplayName("checkRequiredSystemProperties")
    class CheckRequiredSystemProperties {

        @Test
        @DisplayName("reports error for nonexistent catalina.home path")
        void detectsMissingCatalinaHome() {
            Map<String, String> props = Map.of("catalina.home", "/nonexistent/path/tomcat42");
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertEquals(1, issues.size());
            assertTrue(issues.get(0).isBlocking());
            assertTrue(issues.get(0).getMessage().contains("catalina.home"));
            assertTrue(issues.get(0).getMessage().contains("/nonexistent/path/tomcat42"));
        }

        @Test
        @DisplayName("reports error for nonexistent keystore path")
        void detectsMissingKeystore() {
            Map<String, String> props = Map.of("javax.net.ssl.keyStore", "/no/such/keystore.jks");
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertEquals(1, issues.size());
            assertTrue(issues.get(0).getMessage().contains("keyStore"));
        }

        @Test
        @DisplayName("no error for existing path")
        void passesForExistingPath(@TempDir Path tempDir) {
            Map<String, String> props = Map.of("catalina.home", tempDir.toString());
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("ignores non-path properties")
        void ignoresNonPathProperties() {
            Map<String, String> props = Map.of(
                    "my.custom.prop", "someValue",
                    "user.timezone", "UTC");
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("reports multiple missing paths")
        void reportsMultipleMissingPaths() {
            Map<String, String> props = Map.of(
                    "catalina.home", "/nonexistent1",
                    "javax.net.ssl.trustStore", "/nonexistent2");
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertEquals(2, issues.size());
            assertTrue(issues.stream().allMatch(PreflightIssue::isBlocking));
        }

        @Test
        @DisplayName("skips empty property values")
        void skipsEmptyValues() {
            Map<String, String> props = Map.of("catalina.home", "");
            List<PreflightIssue> issues = new ArrayList<>();

            TomcatPreflightValidator.checkRequiredSystemProperties(props, issues);

            assertTrue(issues.isEmpty());
        }
    }

    // =========================================================================
    // checkDuplicateJarsInDirectory — production method
    // =========================================================================

    @Nested
    @DisplayName("checkDuplicateJarsInDirectory")
    class CheckDuplicateJars {

        @Test
        @DisplayName("detects two versions of same library")
        void detectsDuplicateVersions(@TempDir Path tempDir) throws IOException {
            Path libDir = tempDir.resolve("WEB-INF/lib");
            Files.createDirectories(libDir);
            Files.createFile(libDir.resolve("guava-30.1.jar"));
            Files.createFile(libDir.resolve("guava-31.0.jar"));
            Files.createFile(libDir.resolve("slf4j-api-2.0.9.jar")); // no duplicate

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.checkDuplicateJarsInDirectory(libDir, "test-app", issues);

            assertEquals(1, issues.size());
            assertFalse(issues.get(0).isBlocking()); // warning, not error
            assertTrue(issues.get(0).getMessage().contains("guava-30.1.jar"));
            assertTrue(issues.get(0).getMessage().contains("guava-31.0.jar"));
            assertTrue(issues.get(0).getMessage().contains("test-app"));
        }

        @Test
        @DisplayName("no issues when each library has single version")
        void noDuplicates(@TempDir Path tempDir) throws IOException {
            Path libDir = tempDir.resolve("WEB-INF/lib");
            Files.createDirectories(libDir);
            Files.createFile(libDir.resolve("guava-31.0.jar"));
            Files.createFile(libDir.resolve("slf4j-api-2.0.9.jar"));
            Files.createFile(libDir.resolve("jackson-core-2.15.2.jar"));

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.checkDuplicateJarsInDirectory(libDir, "test-app", issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("detects multiple duplicate groups")
        void detectsMultipleDuplicateGroups(@TempDir Path tempDir) throws IOException {
            Path libDir = tempDir.resolve("WEB-INF/lib");
            Files.createDirectories(libDir);
            Files.createFile(libDir.resolve("guava-30.1.jar"));
            Files.createFile(libDir.resolve("guava-31.0.jar"));
            Files.createFile(libDir.resolve("jackson-core-2.14.0.jar"));
            Files.createFile(libDir.resolve("jackson-core-2.15.2.jar"));

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.checkDuplicateJarsInDirectory(libDir, "test-app", issues);

            assertEquals(2, issues.size());
        }

        @Test
        @DisplayName("handles empty lib directory")
        void handlesEmptyLibDir(@TempDir Path tempDir) throws IOException {
            Path libDir = tempDir.resolve("WEB-INF/lib");
            Files.createDirectories(libDir);

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.checkDuplicateJarsInDirectory(libDir, "test-app", issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("ignores non-jar files")
        void ignoresNonJarFiles(@TempDir Path tempDir) throws IOException {
            Path libDir = tempDir.resolve("WEB-INF/lib");
            Files.createDirectories(libDir);
            Files.createFile(libDir.resolve("README.txt"));
            Files.createFile(libDir.resolve("config.xml"));

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.checkDuplicateJarsInDirectory(libDir, "test-app", issues);

            assertTrue(issues.isEmpty());
        }
    }

    // =========================================================================
    // extractJarBaseName — production method
    // =========================================================================

    @Nested
    @DisplayName("extractJarBaseName")
    class ExtractJarBaseName {

        @Test
        @DisplayName("extracts base from versioned JAR")
        void versionedJar() {
            assertEquals("guava", TomcatPreflightValidator.extractJarBaseName("guava-31.0.1.jar"));
        }

        @Test
        @DisplayName("extracts base from two-part version")
        void twoPartVersion() {
            assertEquals("slf4j-api", TomcatPreflightValidator.extractJarBaseName("slf4j-api-2.0.jar"));
        }

        @Test
        @DisplayName("extracts base from SNAPSHOT JAR")
        void snapshotJar() {
            assertEquals("spring-core", TomcatPreflightValidator.extractJarBaseName("spring-core-6.1.0-SNAPSHOT.jar"));
        }

        @Test
        @DisplayName("extracts base with classifier suffix")
        void classifierJar() {
            assertEquals("guava", TomcatPreflightValidator.extractJarBaseName("guava-31.0.1-jre.jar"));
        }

        @Test
        @DisplayName("returns full name minus .jar for unversioned JAR")
        void unversionedJar() {
            assertEquals("tools", TomcatPreflightValidator.extractJarBaseName("tools.jar"));
        }

        @Test
        @DisplayName("handles JAR with three-part version")
        void threePartVersion() {
            assertEquals("jackson-core", TomcatPreflightValidator.extractJarBaseName("jackson-core-2.15.2.jar"));
        }

        @Test
        @DisplayName("handles JAR with single-part version")
        void singlePartVersion() {
            assertEquals("log4j", TomcatPreflightValidator.extractJarBaseName("log4j-1.jar"));
        }

        @Test
        @DisplayName("handles JAR with four-part version")
        void fourPartVersion() {
            assertEquals("some-lib", TomcatPreflightValidator.extractJarBaseName("some-lib-1.2.3.4.jar"));
        }
    }

    // =========================================================================
    // scanDirectoryForLocks — production method
    // =========================================================================

    @Nested
    @DisplayName("scanDirectoryForLocks")
    class ScanDirectoryForLocks {

        @Test
        @DisplayName("no issues for directory with unlocked files")
        void unlockedFiles(@TempDir Path tempDir) throws IOException {
            Path workDir = tempDir.resolve("work");
            Files.createDirectories(workDir);
            Files.writeString(workDir.resolve("data.txt"), "some data");
            Files.writeString(workDir.resolve("cache.bin"), "cache content");

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.scanDirectoryForLocks(workDir, "work", issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("detects file locked by another channel")
        void detectsLockedFile(@TempDir Path tempDir) throws IOException {
            Path workDir = tempDir.resolve("work");
            Files.createDirectories(workDir);
            Path lockedFile = workDir.resolve("cache.lock");
            Files.writeString(lockedFile, "locked data");

            // Hold an exclusive lock on the file, simulating another Tomcat instance
            try (FileChannel channel = FileChannel.open(lockedFile,
                    StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                List<PreflightIssue> issues = new ArrayList<>();
                TomcatPreflightValidator.scanDirectoryForLocks(workDir, "work", issues);

                assertEquals(1, issues.size());
                assertFalse(issues.get(0).isBlocking()); // warning, not error
                assertTrue(issues.get(0).getMessage().contains("locked file"));
                assertTrue(issues.get(0).getMessage().contains("cache.lock"));
            }
        }

        @Test
        @DisplayName("detects locked file in nested subdirectory")
        void detectsLockedInSubdir(@TempDir Path tempDir) throws IOException {
            Path tempSubDir = tempDir.resolve("temp/wcc-local/cache");
            Files.createDirectories(tempSubDir);
            Path lockedFile = tempSubDir.resolve("ehcache.data");
            Files.writeString(lockedFile, "cache");

            try (FileChannel channel = FileChannel.open(lockedFile,
                    StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                List<PreflightIssue> issues = new ArrayList<>();
                TomcatPreflightValidator.scanDirectoryForLocks(
                        tempDir.resolve("temp"), "temp", issues);

                assertEquals(1, issues.size());
                assertTrue(issues.get(0).getMessage().contains("ehcache.data"));
            }
        }

        @Test
        @DisplayName("file instead of directory produces blocking error")
        void fileInsteadOfDirectory(@TempDir Path tempDir) throws IOException {
            Path workPath = tempDir.resolve("work");
            Files.writeString(workPath, "not a directory");

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.scanDirectoryForLocks(workPath, "work", issues);

            assertEquals(1, issues.size());
            assertTrue(issues.get(0).isBlocking());
            assertTrue(issues.get(0).getMessage().contains("not a directory"));
        }

        @Test
        @DisplayName("nonexistent directory produces no issues")
        void nonexistentDirectory(@TempDir Path tempDir) {
            Path workDir = tempDir.resolve("nonexistent");

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.scanDirectoryForLocks(workDir, "work", issues);

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("empty directory produces no issues")
        void emptyDirectory(@TempDir Path tempDir) throws IOException {
            Path workDir = tempDir.resolve("work");
            Files.createDirectories(workDir);

            List<PreflightIssue> issues = new ArrayList<>();
            TomcatPreflightValidator.scanDirectoryForLocks(workDir, "work", issues);

            assertTrue(issues.isEmpty());
        }
    }

    // =========================================================================
    // isFileLocked — production method
    // =========================================================================

    @Nested
    @DisplayName("isFileLocked")
    class IsFileLocked {

        @Test
        @DisplayName("returns false for unlocked file")
        void unlockedFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("data.txt");
            Files.writeString(file, "hello");

            assertFalse(TomcatPreflightValidator.isFileLocked(file));
        }

        @Test
        @DisplayName("returns true for exclusively locked file")
        void lockedFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("data.txt");
            Files.writeString(file, "hello");

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                assertTrue(TomcatPreflightValidator.isFileLocked(file));
            }
        }
    }

    // =========================================================================
    // PreflightResult
    // =========================================================================

    @Nested
    @DisplayName("PreflightResult")
    class PreflightResultTests {

        @Test
        @DisplayName("empty result has no issues")
        void emptyResult() {
            PreflightResult result = new PreflightResult(List.of());
            assertFalse(result.hasIssues());
            assertFalse(result.hasBlockingIssues());
            assertTrue(result.getWarnings().isEmpty());
            assertTrue(result.getBlockingIssues().isEmpty());
        }

        @Test
        @DisplayName("result with warning is not blocking")
        void warningNotBlocking() {
            PreflightResult result = new PreflightResult(List.of(
                    new PreflightIssue(PreflightIssue.Severity.WARNING, "test warning")));
            assertTrue(result.hasIssues());
            assertFalse(result.hasBlockingIssues());
            assertEquals(1, result.getWarnings().size());
        }

        @Test
        @DisplayName("result with error is blocking")
        void errorIsBlocking() {
            PreflightResult result = new PreflightResult(List.of(
                    new PreflightIssue(PreflightIssue.Severity.ERROR, "test error")));
            assertTrue(result.hasIssues());
            assertTrue(result.hasBlockingIssues());
            assertEquals("test error", result.getBlockingMessage());
        }

        @Test
        @DisplayName("mixed result separates warnings from errors")
        void mixedResult() {
            PreflightResult result = new PreflightResult(List.of(
                    new PreflightIssue(PreflightIssue.Severity.WARNING, "warn1"),
                    new PreflightIssue(PreflightIssue.Severity.ERROR, "err1"),
                    new PreflightIssue(PreflightIssue.Severity.WARNING, "warn2")));
            assertTrue(result.hasBlockingIssues());
            assertEquals(2, result.getWarnings().size());
            assertEquals(1, result.getBlockingIssues().size());
            assertEquals("err1", result.getBlockingMessage());
        }

        @Test
        @DisplayName("getBlockingMessage returns fallback when no errors")
        void blockingMessageFallback() {
            PreflightResult result = new PreflightResult(List.of());
            assertEquals("Unknown preflight failure", result.getBlockingMessage());
        }
    }

    // =========================================================================
    // PreflightIssue
    // =========================================================================

    @Nested
    @DisplayName("PreflightIssue")
    class PreflightIssueTests {

        @Test
        @DisplayName("ERROR severity is blocking")
        void errorIsBlocking() {
            PreflightIssue issue = new PreflightIssue(PreflightIssue.Severity.ERROR, "msg");
            assertTrue(issue.isBlocking());
            assertEquals(PreflightIssue.Severity.ERROR, issue.getSeverity());
        }

        @Test
        @DisplayName("WARNING severity is not blocking")
        void warningNotBlocking() {
            PreflightIssue issue = new PreflightIssue(PreflightIssue.Severity.WARNING, "msg");
            assertFalse(issue.isBlocking());
        }

        @Test
        @DisplayName("toString includes severity and message")
        void toStringFormat() {
            PreflightIssue issue = new PreflightIssue(PreflightIssue.Severity.ERROR, "bad path");
            assertEquals("[ERROR] bad path", issue.toString());
        }
    }
}
