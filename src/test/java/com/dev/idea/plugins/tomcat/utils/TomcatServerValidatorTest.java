package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatServerValidator")
class TomcatServerValidatorTest {

    @Nested
    @DisplayName("validateInstallation")
    class ValidateInstallation {

        @Test
        @DisplayName("nonexistent directory returns error")
        void nonexistentDir() {
            ValidationResult result = TomcatServerValidator.validateInstallation("/no/such/path/xyz");
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("does not exist")));
        }

        @Test
        @DisplayName("empty directory returns errors for missing bin and conf")
        void emptyDir(@TempDir Path tempDir) {
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("bin")));
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("conf")));
        }

        @Test
        @DisplayName("dir with bin but no catalina script returns error")
        void binNoCatalina(@TempDir Path tempDir) throws IOException {
            Files.createDirectory(tempDir.resolve("bin"));
            Files.createDirectory(tempDir.resolve("conf"));
            Files.createFile(tempDir.resolve("conf/server.xml"));

            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("catalina")));
        }

        @Test
        @DisplayName("dir with conf but no server.xml returns error")
        void confNoServerXml(@TempDir Path tempDir) throws IOException {
            Path bin = Files.createDirectory(tempDir.resolve("bin"));
            Files.createFile(bin.resolve("catalina.sh"));
            Files.createDirectory(tempDir.resolve("conf"));

            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("server.xml")));
        }

        @Test
        @DisplayName("valid installation with catalina.sh passes")
        void validWithSh(@TempDir Path tempDir) throws IOException {
            createMinimalTomcatStructure(tempDir, "catalina.sh");
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertTrue(result.isValid(), "Errors: " + result.getErrors());
        }

        @Test
        @DisplayName("valid installation with catalina.bat passes")
        void validWithBat(@TempDir Path tempDir) throws IOException {
            createMinimalTomcatStructure(tempDir, "catalina.bat");
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertTrue(result.isValid(), "Errors: " + result.getErrors());
        }

        @Test
        @DisplayName("missing webapps adds warning not error")
        void missingWebappsWarning(@TempDir Path tempDir) throws IOException {
            createMinimalTomcatStructure(tempDir, "catalina.sh");
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertTrue(result.isValid());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("webapps")));
        }

        @Test
        @DisplayName("missing logs adds warning not error")
        void missingLogsWarning(@TempDir Path tempDir) throws IOException {
            createMinimalTomcatStructure(tempDir, "catalina.sh");
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertTrue(result.isValid());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("logs")));
        }

        @Test
        @DisplayName("full structure with webapps and logs has no warnings")
        void fullStructureNoWarnings(@TempDir Path tempDir) throws IOException {
            createMinimalTomcatStructure(tempDir, "catalina.sh");
            Files.createDirectory(tempDir.resolve("webapps"));
            Files.createDirectory(tempDir.resolve("logs"));
            ValidationResult result = TomcatServerValidator.validateInstallation(tempDir.toString());
            assertTrue(result.isValid());
            assertTrue(result.getWarnings().isEmpty());
        }
    }

    @Nested
    @DisplayName("isValidInstallation")
    class IsValidInstallation {

        @Test
        @DisplayName("delegates to validateInstallation")
        void delegatesCorrectly(@TempDir Path tempDir) throws IOException {
            assertFalse(TomcatServerValidator.isValidInstallation("/no/such/path"));
            createMinimalTomcatStructure(tempDir, "catalina.sh");
            assertTrue(TomcatServerValidator.isValidInstallation(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("detectVersion")
    class DetectVersion {

        @Test
        @DisplayName("returns Unknown for empty dir")
        void emptyDirUnknown(@TempDir Path tempDir) {
            assertEquals("Unknown", TomcatServerValidator.detectVersion(tempDir.toString()));
        }

        @Test
        @DisplayName("extracts version from catalina.sh")
        void extractsFromSh(@TempDir Path tempDir) throws IOException {
            Path bin = Files.createDirectory(tempDir.resolve("bin"));
            Files.writeString(bin.resolve("catalina.sh"),
                    "#!/bin/sh\n# Apache Tomcat 10.1.25\necho hello");
            assertEquals("10.1.25", TomcatServerValidator.detectVersion(tempDir.toString()));
        }

        @Test
        @DisplayName("extracts version from RELEASE-NOTES")
        void extractsFromReleaseNotes(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("RELEASE-NOTES"),
                    "Apache Tomcat Version 9.0.93\nRelease Notes");
            assertEquals("9.0.93", TomcatServerValidator.detectVersion(tempDir.toString()));
        }

        @Test
        @DisplayName("prefers catalina script over RELEASE-NOTES")
        void prefersScript(@TempDir Path tempDir) throws IOException {
            Path bin = Files.createDirectory(tempDir.resolve("bin"));
            Files.writeString(bin.resolve("catalina.sh"), "# Version 10.1.25");
            Files.writeString(tempDir.resolve("RELEASE-NOTES"), "Version 9.0.93");
            assertEquals("10.1.25", TomcatServerValidator.detectVersion(tempDir.toString()));
        }
    }

    private void createMinimalTomcatStructure(Path root, String catalinaScript) throws IOException {
        Path bin = Files.createDirectory(root.resolve("bin"));
        Files.createFile(bin.resolve(catalinaScript));
        Path conf = Files.createDirectory(root.resolve("conf"));
        Files.createFile(conf.resolve("server.xml"));
    }
}
