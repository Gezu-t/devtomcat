package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatProjectUtils")
class TomcatProjectUtilsTest {

    // Helper: compute the expected hash suffix the same way sanitizeFileName does.
    private static String hash(String trimmed) {
        return String.format("%08x", trimmed.hashCode());
    }

    @Nested
    @DisplayName("sanitizeFileName")
    class SanitizeFileName {

        @Test
        @DisplayName("passes through simple alphanumeric names unchanged (plus hash suffix)")
        void simpleNames() {
            assertEquals("MyTomcat_" + hash("MyTomcat"),       TomcatProjectUtils.sanitizeFileName("MyTomcat"));
            assertEquals("tomcat-9.0_" + hash("tomcat-9.0"),   TomcatProjectUtils.sanitizeFileName("tomcat-9.0"));
            assertEquals("local_dev_" + hash("local_dev"),     TomcatProjectUtils.sanitizeFileName("local_dev"));
        }

        @Test
        @DisplayName("replaces spaces and special characters with underscores")
        void specialChars() {
            String s1 = TomcatProjectUtils.sanitizeFileName("My Tomcat Config");
            assertTrue(s1.startsWith("My_Tomcat_Config_"), s1);

            String s2 = TomcatProjectUtils.sanitizeFileName("config (1)");
            assertTrue(s2.startsWith("config_1_"), s2);

            String s3 = TomcatProjectUtils.sanitizeFileName("dev/staging");
            assertTrue(s3.startsWith("dev_staging_"), s3);
        }

        @Test
        @DisplayName("collapses consecutive underscores")
        void collapsesUnderscores() {
            String s1 = TomcatProjectUtils.sanitizeFileName("a   b");
            assertTrue(s1.startsWith("a_b_"), s1);

            String s2 = TomcatProjectUtils.sanitizeFileName("x///y");
            assertTrue(s2.startsWith("x_y_"), s2);
        }

        @Test
        @DisplayName("strips leading and trailing underscores from sanitized part")
        void stripsEdgeUnderscores() {
            String s1 = TomcatProjectUtils.sanitizeFileName(" name ");
            assertTrue(s1.startsWith("name_"), s1);

            String s2 = TomcatProjectUtils.sanitizeFileName("(name)");
            assertTrue(s2.startsWith("name_"), s2);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("returns 'unnamed' for null or empty input")
        void nullOrEmpty(String input) {
            assertEquals("unnamed", TomcatProjectUtils.sanitizeFileName(input));
        }

        @Test
        @DisplayName("returns 'unnamed' for whitespace-only input")
        void whitespaceOnly() {
            assertEquals("unnamed", TomcatProjectUtils.sanitizeFileName("   "));
            assertEquals("unnamed", TomcatProjectUtils.sanitizeFileName("\t"));
            assertEquals("unnamed", TomcatProjectUtils.sanitizeFileName("  \t  "));
        }

        @ParameterizedTest
        @CsvSource({
                "'Tomcat 10.1 (local)', Tomcat_10.1_local",
                "'my-app:war exploded', my-app_war_exploded",
                "'  spaced  ', spaced",
                "'dots.and-dashes', dots.and-dashes"
        })
        @DisplayName("sanitized prefix matches expected slug")
        void realisticNames(String input, String expectedPrefix) {
            String result = TomcatProjectUtils.sanitizeFileName(input);
            assertTrue(result.startsWith(expectedPrefix + "_"),
                    "Expected '" + result + "' to start with '" + expectedPrefix + "_'");
        }

        @Test
        @DisplayName("two names differing only in special chars produce different results")
        void collisionPrevention() {
            String a = TomcatProjectUtils.sanitizeFileName("my-tomcat");
            String b = TomcatProjectUtils.sanitizeFileName("my_tomcat");
            assertNotEquals(a, b,
                    "Names that differ only in special chars must not map to the same directory name");
        }
    }

    @Nested
    @DisplayName("resolveConfOverlayPath")
    class ResolveConfOverlayPath {

        @Test
        @DisplayName("builds correct path structure: <project>/.devtomcat/<config>/conf")
        void correctStructure() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/home/user/project", "My Config");

            assertTrue(result.toString().startsWith("/home/user/project/.devtomcat/My_Config_"),
                    "Unexpected path: " + result);
            assertEquals("conf", result.getFileName().toString());
        }

        @Test
        @DisplayName("sanitizes config name in the path")
        void sanitizesConfigName() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "Tomcat 10.1 (local)");

            assertTrue(result.toString().contains("/.devtomcat/Tomcat_10.1_local_"),
                    "Unexpected path: " + result);
        }

        @Test
        @DisplayName("uses 'unnamed' when config name is null")
        void nullConfigName() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", null);

            assertEquals(Path.of("/proj/.devtomcat/unnamed/conf"), result);
        }

        @Test
        @DisplayName("path ends with conf directory")
        void endsWithConf() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "test");

            assertEquals("conf", result.getFileName().toString());
        }

        @Test
        @DisplayName("uses 'unnamed' when config name is whitespace-only")
        void whitespaceConfigName() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "   ");

            assertEquals(Path.of("/proj/.devtomcat/unnamed/conf"), result);
        }

        @Test
        @DisplayName("path is under .devtomcat")
        void containsDevtomcat() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "test");

            assertTrue(result.toString().contains(".devtomcat"),
                    "Path should contain .devtomcat: " + result);
        }

        @Test
        @DisplayName("two configs with same slug but different names produce different paths")
        void collisionPrevention() {
            Path a = TomcatProjectUtils.resolveConfOverlayPath("/proj", "my-tomcat");
            Path b = TomcatProjectUtils.resolveConfOverlayPath("/proj", "my_tomcat");
            assertNotEquals(a, b, "Different config names must not collide on the same path");
        }
    }

    @Nested
    @DisplayName("isUpToDateCopy — atomicCopy freshness gate")
    class IsUpToDateCopy {

        private Path write(Path dir, String name, String content, long mtimeMillis) throws Exception {
            Path f = dir.resolve(name);
            Files.writeString(f, content);
            Files.setLastModifiedTime(f, FileTime.fromMillis(mtimeMillis));
            return f;
        }

        @Test
        @DisplayName("absent target → not up to date (copy)")
        void absentTarget(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "app.war", "bytes", 10_000L);
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, tmp.resolve("missing.war")));
        }

        @Test
        @DisplayName("smaller target → not up to date (copy)")
        void smallerTarget(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "longer-bytes", 10_000L);
            Path target = write(tmp, "dst.war", "short", 20_000L);
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("larger target → not up to date (copy)")
        void largerTarget(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "short", 10_000L);
            Path target = write(tmp, "dst.war", "longer-bytes", 20_000L);
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("target older than source → not up to date (source was rebuilt)")
        void olderTarget(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "bytes", 20_000L);
            Path target = write(tmp, "dst.war", "bytes", 10_000L);
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("target newer than source, same size → up to date (skip)")
        void newerTarget(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "bytes", 10_000L);
            Path target = write(tmp, "dst.war", "bytes", 20_000L);
            assertTrue(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("equal mtime and equal size → up to date (skip)")
        void equalTimeEqualSize(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "bytes", 10_000L);
            Path target = write(tmp, "dst.war", "bytes", 10_000L);
            assertTrue(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("equal mtime but different size → not up to date (copy)")
        void equalTimeDifferentSize(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "rebuilt-different-size", 10_000L);
            Path target = write(tmp, "dst.war", "bytes", 10_000L);
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, target));
        }

        @Test
        @DisplayName("after atomicCopy the copy reads up to date until the source is rebuilt")
        void roundTripWithAtomicCopy(@TempDir Path tmp) throws Exception {
            Path source = write(tmp, "src.war", "war-bytes", System.currentTimeMillis() - 60_000);
            Path target = tmp.resolve("dst.war");
            TomcatProjectUtils.atomicCopy(source, target);
            assertTrue(TomcatProjectUtils.isUpToDateCopy(source, target),
                    "a fresh atomicCopy must read as up to date");

            // "Rebuild" the source after the copy — mtime advances past the target's.
            Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
            assertFalse(TomcatProjectUtils.isUpToDateCopy(source, target),
                    "a source rebuilt after the copy must trigger a re-copy");
        }
    }
}
