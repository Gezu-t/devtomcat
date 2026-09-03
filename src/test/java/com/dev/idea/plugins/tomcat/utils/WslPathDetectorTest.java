package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the WSL-home guard. Neutral placeholder distros/paths only.
 */
@DisplayName("WslPathDetector")
class WslPathDetectorTest {

    @Nested
    @DisplayName("isWslPath")
    class IsWslPath {

        @Test
        @DisplayName("\\\\wsl$\\ UNC path is WSL")
        void wslDollarPrefix() {
            assertTrue(WslPathDetector.isWslPath("\\\\wsl$\\Ubuntu\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("\\\\wsl.localhost\\ UNC path is WSL")
        void wslLocalhostPrefix() {
            assertTrue(WslPathDetector.isWslPath("\\\\wsl.localhost\\Debian\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("forward-slash UNC form is WSL too")
        void forwardSlashForm() {
            assertTrue(WslPathDetector.isWslPath("//wsl$/Ubuntu/opt/apache-tomcat"));
        }

        @Test
        @DisplayName("prefix match is case-insensitive")
        void caseInsensitive() {
            assertTrue(WslPathDetector.isWslPath("\\\\WSL$\\Ubuntu\\opt\\apache-tomcat"));
            assertTrue(WslPathDetector.isWslPath("\\\\Wsl.LocalHost\\Ubuntu\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("a normal Windows path is NOT WSL")
        void windowsPathIsNot() {
            assertFalse(WslPathDetector.isWslPath("C:\\apache-tomcat-9"));
        }

        @Test
        @DisplayName("a plain host UNC share is NOT WSL")
        void plainUncIsNot() {
            assertFalse(WslPathDetector.isWslPath("\\\\fileserver\\share\\apache-tomcat"));
        }

        @Test
        @DisplayName("a native Linux/macOS path is NOT flagged (dev machines aren't WSL)")
        void posixPathIsNot() {
            assertFalse(WslPathDetector.isWslPath("/opt/apache-tomcat"));
        }

        @Test
        @DisplayName("null / blank is not WSL")
        void nullBlank() {
            assertFalse(WslPathDetector.isWslPath(null));
            assertFalse(WslPathDetector.isWslPath("   "));
        }
    }

    @Nested
    @DisplayName("distroOf")
    class DistroOf {

        @Test
        @DisplayName("extracts the distro segment from a \\\\wsl.localhost path")
        void localhostDistro() {
            assertEquals("Debian",
                    WslPathDetector.distroOf("\\\\wsl.localhost\\Debian\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("extracts the distro from a \\\\wsl$ path with no trailing segment")
        void dollarDistroNoTail() {
            assertEquals("Ubuntu", WslPathDetector.distroOf("\\\\wsl$\\Ubuntu"));
        }

        @Test
        @DisplayName("non-WSL path has no distro")
        void nonWslNull() {
            assertNull(WslPathDetector.distroOf("C:\\apache-tomcat-9"));
        }
    }

    @Nested
    @DisplayName("unsupportedMessage")
    class UnsupportedMessage {

        @Test
        @DisplayName("names the distro and stays honest about the limitation")
        void namesDistroAndLimitation() {
            String msg = WslPathDetector.unsupportedMessage("\\\\wsl$\\Ubuntu\\opt\\apache-tomcat");
            assertTrue(msg.contains("Ubuntu"), "should name the distribution");
            assertTrue(msg.contains("WSL"));
            assertTrue(msg.toLowerCase().contains("local host process"),
                    "should explain WHY it can't run");
            assertTrue(msg.toLowerCase().contains("planned"),
                    "should set the expectation that support is planned");
        }
    }
}
