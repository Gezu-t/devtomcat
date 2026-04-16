package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogFileConfig")
class LogFileConfigTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("generates non-empty id")
        void generatesId() {
            LogFileConfig config = new LogFileConfig();
            assertNotNull(config.getId());
            assertFalse(config.getId().isEmpty());
            assertTrue(config.getId().startsWith("logfile-"));
        }

        @Test
        @DisplayName("default log files list is empty")
        void emptyLogFiles() {
            LogFileConfig config = new LogFileConfig();
            assertTrue(config.getLogFiles().isEmpty());
            assertFalse(config.hasLogFiles());
            assertEquals(0, config.getLogFileCount());
        }

        @Test
        @DisplayName("stdout/stderr console enabled by default")
        void consoleDefaults() {
            LogFileConfig config = new LogFileConfig();
            assertTrue(config.isShowStdoutConsole());
            assertTrue(config.isShowStderrConsole());
        }

        @Test
        @DisplayName("save console to file disabled by default")
        void saveConsoleDefault() {
            LogFileConfig config = new LogFileConfig();
            assertFalse(config.isSaveConsoleToFile());
            assertEquals("", config.getSaveConsoleFilePath());
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields")
        void copiesAllFields() {
            LogFileConfig original = new LogFileConfig();
            original.addLogFile("/logs/catalina.log");
            original.setShowStdoutConsole(false);
            original.setShowStderrConsole(false);
            original.setSaveConsoleToFile(true);
            original.setSaveConsoleFilePath("/tmp/console.log");

            LogFileConfig copy = new LogFileConfig(original);
            assertEquals(original.getId(), copy.getId());
            assertEquals(original.getLogFiles(), copy.getLogFiles());
            assertEquals(original.isShowStdoutConsole(), copy.isShowStdoutConsole());
            assertEquals(original.isShowStderrConsole(), copy.isShowStderrConsole());
            assertEquals(original.isSaveConsoleToFile(), copy.isSaveConsoleToFile());
            assertEquals(original.getSaveConsoleFilePath(), copy.getSaveConsoleFilePath());
        }

        @Test
        @DisplayName("null produces fresh instance")
        void nullProducesFresh() {
            LogFileConfig config = new LogFileConfig(null);
            assertNotNull(config.getId());
            assertTrue(config.getLogFiles().isEmpty());
        }

        @Test
        @DisplayName("copy is independent")
        void copyIsIndependent() {
            LogFileConfig original = new LogFileConfig();
            original.addLogFile("/logs/test.log");

            LogFileConfig copy = new LogFileConfig(original);
            copy.addLogFile("/logs/other.log");

            assertEquals(1, original.getLogFileCount());
            assertEquals(2, copy.getLogFileCount());
        }
    }

    @Nested
    @DisplayName("addLogFile")
    class AddLogFile {

        @Test
        @DisplayName("adds valid log file")
        void addsValidFile() {
            LogFileConfig config = new LogFileConfig();
            assertTrue(config.addLogFile("/logs/catalina.log"));
            assertEquals(1, config.getLogFileCount());
            assertTrue(config.hasLogFiles());
        }

        @Test
        @DisplayName("rejects empty path")
        void rejectsEmpty() {
            LogFileConfig config = new LogFileConfig();
            assertFalse(config.addLogFile(""));
            assertFalse(config.addLogFile("   "));
        }

        @Test
        @DisplayName("rejects duplicate path")
        void rejectsDuplicate() {
            LogFileConfig config = new LogFileConfig();
            assertTrue(config.addLogFile("/logs/test.log"));
            assertFalse(config.addLogFile("/logs/test.log"));
            assertEquals(1, config.getLogFileCount());
        }

        @Test
        @DisplayName("trims whitespace")
        void trimsWhitespace() {
            LogFileConfig config = new LogFileConfig();
            assertTrue(config.addLogFile("  /logs/test.log  "));
            assertEquals("/logs/test.log", config.getLogFile(0));
        }
    }

    @Nested
    @DisplayName("removeLogFile")
    class RemoveLogFile {

        @Test
        @DisplayName("removes existing file")
        void removesExisting() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/logs/test.log");
            assertTrue(config.removeLogFile("/logs/test.log"));
            assertEquals(0, config.getLogFileCount());
        }

        @Test
        @DisplayName("returns false for non-existing")
        void returnsFalseForNonExisting() {
            LogFileConfig config = new LogFileConfig();
            assertFalse(config.removeLogFile("/logs/missing.log"));
        }
    }

    @Nested
    @DisplayName("removeLogFileAt")
    class RemoveLogFileAt {

        @Test
        @DisplayName("removes at valid index")
        void removesAtValidIndex() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/logs/a.log");
            config.addLogFile("/logs/b.log");
            assertEquals("/logs/a.log", config.removeLogFileAt(0));
            assertEquals(1, config.getLogFileCount());
        }

        @Test
        @DisplayName("returns null for invalid index")
        void nullForInvalidIndex() {
            LogFileConfig config = new LogFileConfig();
            assertNull(config.removeLogFileAt(0));
            assertNull(config.removeLogFileAt(-1));
        }
    }

    @Nested
    @DisplayName("getLogFile")
    class GetLogFile {

        @Test
        @DisplayName("returns file at valid index")
        void validIndex() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/logs/test.log");
            assertEquals("/logs/test.log", config.getLogFile(0));
        }

        @Test
        @DisplayName("returns null for invalid index")
        void invalidIndex() {
            LogFileConfig config = new LogFileConfig();
            assertNull(config.getLogFile(0));
            assertNull(config.getLogFile(-1));
        }
    }

    @Nested
    @DisplayName("setLogFiles")
    class SetLogFiles {

        @Test
        @DisplayName("replaces existing files")
        void replacesExisting() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/old.log");
            config.setLogFiles(Arrays.asList("/new1.log", "/new2.log"));
            assertEquals(2, config.getLogFileCount());
        }

        @Test
        @DisplayName("null clears list")
        void nullClears() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/test.log");
            config.setLogFiles(null);
            assertEquals(0, config.getLogFileCount());
        }

        @Test
        @DisplayName("filters null and empty entries")
        void filtersNullAndEmpty() {
            LogFileConfig config = new LogFileConfig();
            config.setLogFiles(Arrays.asList("/valid.log", null, "", "  ", "/valid2.log"));
            assertEquals(2, config.getLogFileCount());
        }
    }

    @Nested
    @DisplayName("clearLogFiles")
    class ClearLogFiles {

        @Test
        @DisplayName("removes all files")
        void removesAll() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/a.log");
            config.addLogFile("/b.log");
            config.clearLogFiles();
            assertEquals(0, config.getLogFileCount());
            assertFalse(config.hasLogFiles());
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("empty config is invalid")
        void emptyIsInvalid() {
            assertFalse(new LogFileConfig().isValid());
        }

        @Test
        @DisplayName("config with valid file is valid")
        void withFileIsValid() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/logs/test.log");
            assertTrue(config.isValid());
        }
    }

    @Nested
    @DisplayName("console settings")
    class ConsoleSettings {

        @Test
        @DisplayName("setters update values")
        void settersWork() {
            LogFileConfig config = new LogFileConfig();
            config.setShowStdoutConsole(false);
            config.setShowStderrConsole(false);
            config.setSaveConsoleToFile(true);
            config.setSaveConsoleFilePath("/tmp/out.log");

            assertFalse(config.isShowStdoutConsole());
            assertFalse(config.isShowStderrConsole());
            assertTrue(config.isSaveConsoleToFile());
            assertEquals("/tmp/out.log", config.getSaveConsoleFilePath());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            LogFileConfig original = new LogFileConfig();
            original.addLogFile("/logs/test.log");
            original.setShowStdoutConsole(false);

            LogFileConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            LogFileConfig original = new LogFileConfig();
            original.addLogFile("/logs/test.log");

            LogFileConfig cloned = original.clone();
            cloned.addLogFile("/logs/other.log");

            assertEquals(1, original.getLogFileCount());
            assertEquals(2, cloned.getLogFileCount());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("equal configs")
        void equalConfigs() {
            LogFileConfig a = new LogFileConfig();
            a.addLogFile("/logs/test.log");
            LogFileConfig b = new LogFileConfig();
            b.addLogFile("/logs/test.log");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different log files not equal")
        void differentFilesNotEqual() {
            LogFileConfig a = new LogFileConfig();
            a.addLogFile("/a.log");
            LogFileConfig b = new LogFileConfig();
            b.addLogFile("/b.log");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different console settings not equal")
        void differentConsoleNotEqual() {
            LogFileConfig a = new LogFileConfig();
            LogFileConfig b = new LogFileConfig();
            b.setShowStdoutConsole(false);

            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes file count")
        void includesCount() {
            LogFileConfig config = new LogFileConfig();
            config.addLogFile("/test.log");
            assertTrue(config.toString().contains("1"));
        }
    }
}
