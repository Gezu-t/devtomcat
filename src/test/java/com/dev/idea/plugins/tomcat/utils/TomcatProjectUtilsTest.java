package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatProjectUtils")
class TomcatProjectUtilsTest {

    @Nested
    @DisplayName("sanitizeFileName")
    class SanitizeFileName {

        @Test
        @DisplayName("passes through simple alphanumeric names unchanged")
        void simpleNames() {
            assertEquals("MyTomcat", TomcatProjectUtils.sanitizeFileName("MyTomcat"));
            assertEquals("tomcat-9.0", TomcatProjectUtils.sanitizeFileName("tomcat-9.0"));
            assertEquals("local_dev", TomcatProjectUtils.sanitizeFileName("local_dev"));
        }

        @Test
        @DisplayName("replaces spaces and special characters with underscores")
        void specialChars() {
            assertEquals("My_Tomcat_Config", TomcatProjectUtils.sanitizeFileName("My Tomcat Config"));
            assertEquals("config_1", TomcatProjectUtils.sanitizeFileName("config (1)"));
            assertEquals("dev_staging", TomcatProjectUtils.sanitizeFileName("dev/staging"));
        }

        @Test
        @DisplayName("collapses consecutive underscores")
        void collapsesUnderscores() {
            assertEquals("a_b", TomcatProjectUtils.sanitizeFileName("a   b"));
            assertEquals("x_y", TomcatProjectUtils.sanitizeFileName("x///y"));
        }

        @Test
        @DisplayName("strips leading and trailing underscores")
        void stripsEdgeUnderscores() {
            assertEquals("name", TomcatProjectUtils.sanitizeFileName(" name "));
            assertEquals("name", TomcatProjectUtils.sanitizeFileName("(name)"));
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
        @DisplayName("handles realistic config names")
        void realisticNames(String input, String expected) {
            assertEquals(expected, TomcatProjectUtils.sanitizeFileName(input));
        }
    }

    @Nested
    @DisplayName("resolveConfOverlayPath")
    class ResolveConfOverlayPath {

        @Test
        @DisplayName("builds correct path structure: <project>/.devtomcat/<config>/conf")
        void correctStructure() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/home/user/project", "My Config");

            assertEquals(Path.of("/home/user/project/.devtomcat/My_Config/conf"), result);
        }

        @Test
        @DisplayName("sanitizes config name in the path")
        void sanitizesConfigName() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "Tomcat 10.1 (local)");

            assertEquals(Path.of("/proj/.devtomcat/Tomcat_10.1_local/conf"), result);
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
        @DisplayName("path contains .devtomcat segment")
        void containsDevtomcat() {
            Path result = TomcatProjectUtils.resolveConfOverlayPath("/proj", "test");

            assertTrue(result.toString().contains(".devtomcat"),
                    "Path should contain .devtomcat: " + result);
        }
    }
}
