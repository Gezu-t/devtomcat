package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the two pure detection helpers behind the JDK auto-detect
 * dialog. These run without a platform fixture: {@code isValidJdk} only touches
 * the filesystem and {@code SystemInfo.isWindows}, and {@code extractVersion} is
 * pure string parsing. The dialog's Swing/model wiring is not exercised here.
 */
@DisplayName("AutoDetectJdkDialog detection helpers")
class AutoDetectJdkDialogTest {

    @Nested
    @DisplayName("isValidJdk — structural JDK-home gate")
    class IsValidJdk {

        @Test
        @DisplayName("a directory with bin/<java launcher> is a valid JDK home")
        void acceptsDirWithLauncher(@TempDir Path home) throws IOException {
            Path bin = Files.createDirectories(home.resolve("bin"));
            // Match the launcher the gate looks for on whatever OS runs the test
            // (java.exe on Windows, java elsewhere) so the assertion is real on
            // every platform rather than pinned to one.
            String launcher = SystemInfo.isWindows ? "java.exe" : "java";
            Files.createFile(bin.resolve(launcher));

            assertTrue(AutoDetectJdkDialog.isValidJdk(home.toFile()),
                    "a directory containing bin/" + launcher + " must be accepted as a JDK home");
        }

        @Test
        @DisplayName("a directory without the launcher is rejected")
        void rejectsDirWithoutLauncher(@TempDir Path home) {
            assertFalse(AutoDetectJdkDialog.isValidJdk(home.toFile()),
                    "a directory with no bin/java launcher is not a JDK home");
        }

        @Test
        @DisplayName("a non-existent path is rejected")
        void rejectsMissingPath() {
            assertFalse(AutoDetectJdkDialog.isValidJdk(new File(System.getProperty("java.io.tmpdir"),
                            "devtomcat-no-such-jdk-home")),
                    "a path with no bin/java launcher is not a JDK home");
        }
    }

    @Nested
    @DisplayName("extractVersion — directory name to display label")
    class ExtractVersion {

        @Test
        @DisplayName("takes the first dash-separated segment that starts with a digit")
        void firstNumericSegment() {
            assertEquals("JDK 17.0.2", AutoDetectJdkDialog.extractVersion("jdk-17.0.2"));
            assertEquals("JDK 21", AutoDetectJdkDialog.extractVersion("openjdk-21"));
            assertEquals("JDK 17.0.10", AutoDetectJdkDialog.extractVersion("corretto-17.0.10"));
        }

        @Test
        @DisplayName("skips leading non-numeric segments before the version")
        void skipsNonNumericSegments() {
            assertEquals("JDK 22.3.0", AutoDetectJdkDialog.extractVersion("graalvm-ce-java17-22.3.0"));
        }

        @Test
        @DisplayName("preserves a build suffix on the version segment")
        void preservesBuildSuffix() {
            assertEquals("JDK 17.0.5+8", AutoDetectJdkDialog.extractVersion("temurin-17.0.5+8"));
        }

        @Test
        @DisplayName("a name with no dash degrades to the bare label")
        void noDashBareLabel() {
            assertEquals("JDK", AutoDetectJdkDialog.extractVersion("jdk17"));
            assertEquals("JDK", AutoDetectJdkDialog.extractVersion("zulu17.0.5"));
        }
    }
}
