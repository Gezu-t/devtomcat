package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.execution.configurations.PredefinedLogFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatLogFile")
class TomcatLogFileTest {

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("catalina log ID")
        void catalinaLogId() {
            assertEquals("Tomcat Catalina Log", TomcatLogFile.TOMCAT_CATALINA_LOG_ID);
        }

        @Test
        @DisplayName("localhost log ID")
        void localhostLogId() {
            assertEquals("Tomcat Localhost Log", TomcatLogFile.TOMCAT_LOCALHOST_LOG_ID);
        }

        @Test
        @DisplayName("access log ID")
        void accessLogId() {
            assertEquals("Tomcat Access Log", TomcatLogFile.TOMCAT_ACCESS_LOG_ID);
        }

        @Test
        @DisplayName("manager log ID")
        void managerLogId() {
            assertEquals("Tomcat Manager Log", TomcatLogFile.TOMCAT_MANAGER_LOG_ID);
        }

        @Test
        @DisplayName("host manager log ID")
        void hostManagerLogId() {
            assertEquals("Tomcat Host Manager Log", TomcatLogFile.TOMCAT_HOST_MANAGER_LOG_ID);
        }
    }

    @Nested
    @DisplayName("4-arg constructor")
    class FourArgConstructor {

        @Test
        @DisplayName("sets all fields")
        void setsAllFields() {
            TomcatLogFile log = new TomcatLogFile("test-id", "test.*.log", true, "Test log");
            assertEquals("test-id", log.getId());
            assertEquals("test.*.log", log.getFilenamePattern());
            assertTrue(log.isEnabledByDefault());
            assertEquals("Test log", log.getDescription());
        }

        @Test
        @DisplayName("rejects null id")
        void rejectsNullId() {
            assertThrows(Exception.class, () -> new TomcatLogFile(null, "pattern", true, "desc"));
        }

        @Test
        @DisplayName("rejects null pattern")
        void rejectsNullPattern() {
            assertThrows(Exception.class, () -> new TomcatLogFile("id", null, true, "desc"));
        }

        @Test
        @DisplayName("rejects empty id")
        void rejectsEmptyId() {
            assertThrows(IllegalArgumentException.class, () -> new TomcatLogFile("  ", "pattern", true, "desc"));
        }

        @Test
        @DisplayName("rejects empty pattern")
        void rejectsEmptyPattern() {
            assertThrows(IllegalArgumentException.class, () -> new TomcatLogFile("id", "  ", true, "desc"));
        }
    }

    @Nested
    @DisplayName("2-arg constructor")
    class TwoArgConstructor {

        @Test
        @DisplayName("defaults enabled to true and description to empty")
        void defaults() {
            TomcatLogFile log = new TomcatLogFile("my-log", "my.*.log");
            assertEquals("my-log", log.getId());
            assertEquals("my.*.log", log.getFilenamePattern());
            assertTrue(log.isEnabledByDefault());
            assertEquals("", log.getDescription());
        }
    }

    @Nested
    @DisplayName("factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("createCatalinaLog has correct properties")
        void catalinaLog() {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            assertEquals(TomcatLogFile.TOMCAT_CATALINA_LOG_ID, log.getId());
            assertEquals("catalina.*.log", log.getFilenamePattern());
            assertTrue(log.isEnabledByDefault());
        }

        @Test
        @DisplayName("createLocalhostLog has correct properties")
        void localhostLog() {
            TomcatLogFile log = TomcatLogFile.createLocalhostLog();
            assertEquals(TomcatLogFile.TOMCAT_LOCALHOST_LOG_ID, log.getId());
            assertEquals("localhost.*.log", log.getFilenamePattern());
            assertTrue(log.isEnabledByDefault());
        }

        @Test
        @DisplayName("createAccessLog is disabled by default")
        void accessLog() {
            TomcatLogFile log = TomcatLogFile.createAccessLog();
            assertEquals(TomcatLogFile.TOMCAT_ACCESS_LOG_ID, log.getId());
            assertFalse(log.isEnabledByDefault());
        }

        @Test
        @DisplayName("createManagerLog is disabled by default")
        void managerLog() {
            TomcatLogFile log = TomcatLogFile.createManagerLog();
            assertEquals(TomcatLogFile.TOMCAT_MANAGER_LOG_ID, log.getId());
            assertFalse(log.isEnabledByDefault());
        }

        @Test
        @DisplayName("createHostManagerLog is disabled by default")
        void hostManagerLog() {
            TomcatLogFile log = TomcatLogFile.createHostManagerLog();
            assertEquals(TomcatLogFile.TOMCAT_HOST_MANAGER_LOG_ID, log.getId());
            assertFalse(log.isEnabledByDefault());
        }
    }

    @Nested
    @DisplayName("getStandardLogFiles")
    class StandardLogFiles {

        @Test
        @DisplayName("returns 5 standard log files")
        void returnsFiveFiles() {
            TomcatLogFile[] files = TomcatLogFile.getStandardLogFiles();
            assertEquals(5, files.length);
        }

        @Test
        @DisplayName("first two are enabled by default")
        void firstTwoEnabled() {
            TomcatLogFile[] files = TomcatLogFile.getStandardLogFiles();
            assertTrue(files[0].isEnabledByDefault());
            assertTrue(files[1].isEnabledByDefault());
        }

        @Test
        @DisplayName("last three are disabled by default")
        void lastThreeDisabled() {
            TomcatLogFile[] files = TomcatLogFile.getStandardLogFiles();
            assertFalse(files[2].isEnabledByDefault());
            assertFalse(files[3].isEnabledByDefault());
            assertFalse(files[4].isEnabledByDefault());
        }
    }

    @Nested
    @DisplayName("getDefaultEnabledLogFiles")
    class DefaultEnabledLogFiles {

        @Test
        @DisplayName("returns 2 files")
        void returnsTwoFiles() {
            TomcatLogFile[] files = TomcatLogFile.getDefaultEnabledLogFiles();
            assertEquals(2, files.length);
        }

        @Test
        @DisplayName("all are enabled by default")
        void allEnabled() {
            for (TomcatLogFile file : TomcatLogFile.getDefaultEnabledLogFiles()) {
                assertTrue(file.isEnabledByDefault());
            }
        }
    }

    @Nested
    @DisplayName("createLogFileOptions")
    class CreateLogFileOptions {

        @Test
        @DisplayName("creates options with resolved path")
        void createsWithResolvedPath(@TempDir Path tempDir) {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            LogFileOptions opts = log.createLogFileOptions(tempDir);

            assertEquals(TomcatLogFile.TOMCAT_CATALINA_LOG_ID, opts.getName());
            assertTrue(opts.getPathPattern().contains("catalina"));
        }

        @Test
        @DisplayName("rejects null path")
        void rejectsNullPath() {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            assertThrows(Exception.class, () -> log.createLogFileOptions((Path) null));
        }
    }

    @Nested
    @DisplayName("createPredefinedLogFile")
    class CreatePredefinedLogFileTests {

        @Test
        @DisplayName("no-arg uses enabledByDefault")
        void noArgUsesDefault() {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            PredefinedLogFile plf = log.createPredefinedLogFile();
            assertEquals(TomcatLogFile.TOMCAT_CATALINA_LOG_ID, plf.getId());
            assertTrue(plf.isEnabled());
        }

        @Test
        @DisplayName("boolean arg overrides enabled")
        void booleanArgOverrides() {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            PredefinedLogFile plf = log.createPredefinedLogFile(false);
            assertFalse(plf.isEnabled());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same id is equal")
        void sameIdEqual() {
            TomcatLogFile a = new TomcatLogFile("same-id", "a.*.log", true, "A");
            TomcatLogFile b = new TomcatLogFile("same-id", "b.*.log", false, "B");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different id is not equal")
        void differentIdNotEqual() {
            TomcatLogFile a = new TomcatLogFile("id-a", "a.*.log");
            TomcatLogFile b = new TomcatLogFile("id-b", "a.*.log");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("null is not equal")
        void nullNotEqual() {
            TomcatLogFile log = TomcatLogFile.createCatalinaLog();
            assertNotEquals(null, log);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes id and pattern")
        void includesIdAndPattern() {
            TomcatLogFile log = new TomcatLogFile("test-id", "test.*.log", true, "desc");
            String str = log.toString();
            assertTrue(str.contains("test-id"));
            assertTrue(str.contains("test.*.log"));
        }
    }
}
