package com.dev.idea.plugins.tomcat.setting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatInfo")
class TomcatInfoTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("sets empty name, version, and path")
        void setsEmptyFields() {
            TomcatInfo info = new TomcatInfo();
            assertEquals("", info.getName());
            assertEquals("", info.getVersion());
            assertEquals("", info.getPath());
        }

        @Test
        @DisplayName("generates non-empty UUID id")
        void generatesUuid() {
            TomcatInfo info = new TomcatInfo();
            assertNotNull(info.getId());
            assertFalse(info.getId().isEmpty());
        }
    }

    @Nested
    @DisplayName("3-arg constructor")
    class ThreeArgConstructor {

        @Test
        @DisplayName("sets name, version, and path")
        void setsFields() {
            TomcatInfo info = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat");
            assertEquals("Tomcat 10", info.getName());
            assertEquals("10.1.2", info.getVersion());
            assertEquals("/opt/tomcat", info.getPath());
        }

        @Test
        @DisplayName("generates UUID id")
        void generatesUuid() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/path");
            assertNotNull(info.getId());
            assertFalse(info.getId().isEmpty());
        }

        @Test
        @DisplayName("rejects null name")
        void rejectsNullName() {
            assertThrows(Exception.class, () -> new TomcatInfo(null, "10", "/path"));
        }

        @Test
        @DisplayName("rejects null version")
        void rejectsNullVersion() {
            assertThrows(Exception.class, () -> new TomcatInfo("Tomcat", null, "/path"));
        }

        @Test
        @DisplayName("rejects null path")
        void rejectsNullPath() {
            assertThrows(Exception.class, () -> new TomcatInfo("Tomcat", "10", null));
        }
    }

    @Nested
    @DisplayName("null setters")
    class NullSetters {

        @Test
        @DisplayName("setName(null) sets empty string")
        void setNameNull() {
            TomcatInfo info = new TomcatInfo();
            info.setName(null);
            assertEquals("", info.getName());
        }

        @Test
        @DisplayName("setVersion(null) sets empty string")
        void setVersionNull() {
            TomcatInfo info = new TomcatInfo();
            info.setVersion(null);
            assertEquals("", info.getVersion());
        }

        @Test
        @DisplayName("setPath(null) sets empty string")
        void setPathNull() {
            TomcatInfo info = new TomcatInfo();
            info.setPath(null);
            assertEquals("", info.getPath());
        }
    }

    @Nested
    @DisplayName("setId")
    class SetId {

        @Test
        @DisplayName("null generates new UUID")
        void nullGeneratesUuid() {
            TomcatInfo info = new TomcatInfo();
            String originalId = info.getId();
            info.setId(null);
            assertNotNull(info.getId());
            assertFalse(info.getId().isEmpty());
            assertNotEquals(originalId, info.getId());
        }

        @Test
        @DisplayName("empty generates new UUID")
        void emptyGeneratesUuid() {
            TomcatInfo info = new TomcatInfo();
            String originalId = info.getId();
            info.setId("");
            assertNotNull(info.getId());
            assertFalse(info.getId().isEmpty());
            assertNotEquals(originalId, info.getId());
        }

        @Test
        @DisplayName("whitespace-only generates new UUID")
        void whitespaceGeneratesUuid() {
            TomcatInfo info = new TomcatInfo();
            info.setId("   ");
            assertNotNull(info.getId());
            assertFalse(info.getId().isEmpty());
        }

        @Test
        @DisplayName("valid id is preserved")
        void validIdPreserved() {
            TomcatInfo info = new TomcatInfo();
            info.setId("custom-id-123");
            assertEquals("custom-id-123", info.getId());
        }
    }

    @Nested
    @DisplayName("getMajorVersion")
    class GetMajorVersion {

        @Test
        @DisplayName("parses 10.1.2 as 10")
        void parsesFullVersion() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10.1.2", "/path");
            assertEquals(10, info.getMajorVersion());
        }

        @Test
        @DisplayName("parses 9.0 as 9")
        void parsesTwoPartVersion() {
            TomcatInfo info = new TomcatInfo("Tomcat", "9.0", "/path");
            assertEquals(9, info.getMajorVersion());
        }

        @Test
        @DisplayName("empty version returns 0")
        void emptyVersionReturnsZero() {
            TomcatInfo info = new TomcatInfo();
            assertEquals(0, info.getMajorVersion());
        }

        @Test
        @DisplayName("non-numeric version returns 0")
        void nonNumericReturnsZero() {
            TomcatInfo info = new TomcatInfo("Tomcat", "abc", "/path");
            assertEquals(0, info.getMajorVersion());
        }

        @Test
        @DisplayName("single number version parsed correctly")
        void singleNumber() {
            TomcatInfo info = new TomcatInfo("Tomcat", "11", "/path");
            assertEquals(11, info.getMajorVersion());
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("both empty is invalid")
        void bothEmptyIsInvalid() {
            TomcatInfo info = new TomcatInfo();
            assertFalse(info.isValid());
        }

        @Test
        @DisplayName("name only is invalid")
        void nameOnlyIsInvalid() {
            TomcatInfo info = new TomcatInfo();
            info.setName("Tomcat");
            assertFalse(info.isValid());
        }

        @Test
        @DisplayName("path only is invalid")
        void pathOnlyIsInvalid() {
            TomcatInfo info = new TomcatInfo();
            info.setPath("/opt/tomcat");
            assertFalse(info.isValid());
        }

        @Test
        @DisplayName("both name and path set is valid")
        void bothSetIsValid() {
            TomcatInfo info = new TomcatInfo();
            info.setName("Tomcat");
            info.setPath("/opt/tomcat");
            assertTrue(info.isValid());
        }
    }

    @Nested
    @DisplayName("validate")
    class ValidateTests {

        @Test
        @DisplayName("empty name throws")
        void emptyNameThrows() {
            TomcatInfo info = new TomcatInfo();
            info.setPath("/some/path");
            assertThrows(IllegalStateException.class, info::validate);
        }

        @Test
        @DisplayName("empty path throws")
        void emptyPathThrows() {
            TomcatInfo info = new TomcatInfo();
            info.setName("Tomcat");
            assertThrows(IllegalStateException.class, info::validate);
        }

        @Test
        @DisplayName("nonexistent path throws")
        void nonexistentPathThrows() {
            TomcatInfo info = new TomcatInfo();
            info.setName("Tomcat");
            info.setPath("/nonexistent/path/to/tomcat");
            assertThrows(IllegalStateException.class, info::validate);
        }

        @Test
        @DisplayName("valid config does not throw")
        void validConfigDoesNotThrow(@TempDir Path tempDir) {
            TomcatInfo info = new TomcatInfo();
            info.setName("Tomcat");
            info.setPath(tempDir.toString());
            assertDoesNotThrow(info::validate);
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("fields are copied")
        void fieldsCopied() {
            TomcatInfo original = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat");
            TomcatInfo cloned = original.clone();

            assertEquals(original.getId(), cloned.getId());
            assertEquals(original.getName(), cloned.getName());
            assertEquals(original.getVersion(), cloned.getVersion());
            assertEquals(original.getPath(), cloned.getPath());
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIsIndependent() {
            TomcatInfo original = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat");
            TomcatInfo cloned = original.clone();

            cloned.setName("Modified");
            assertEquals("Tomcat 10", original.getName());
            assertEquals("Modified", cloned.getName());
        }

        @Test
        @DisplayName("clone equals original")
        void cloneEqualsOriginal() {
            TomcatInfo original = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat");
            TomcatInfo cloned = original.clone();

            assertEquals(original, cloned);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same instance is equal")
        void sameInstanceIsEqual() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/path");
            assertEquals(info, info);
        }

        @Test
        @DisplayName("equal by ID only")
        void equalByIdOnly() {
            TomcatInfo a = new TomcatInfo("Tomcat A", "10", "/path/a");
            TomcatInfo b = new TomcatInfo("Tomcat B", "11", "/path/b");
            b.setId(a.getId());

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different IDs are not equal")
        void differentIdsNotEqual() {
            TomcatInfo a = new TomcatInfo("Tomcat", "10", "/path");
            TomcatInfo b = new TomcatInfo("Tomcat", "10", "/path");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("null is not equal")
        void nullNotEqual() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/path");
            assertNotEquals(null, info);
        }

        @Test
        @DisplayName("different type is not equal")
        void differentTypeNotEqual() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/path");
            assertNotEquals("not a TomcatInfo", info);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("format is name (version)")
        void formatIsNameVersion() {
            TomcatInfo info = new TomcatInfo("Apache Tomcat", "10.1.2", "/path");
            assertEquals("Apache Tomcat (10.1.2)", info.toString());
        }

        @Test
        @DisplayName("empty fields produce empty parentheses")
        void emptyFields() {
            TomcatInfo info = new TomcatInfo();
            assertEquals(" ()", info.toString());
        }
    }

    @Nested
    @DisplayName("getCatalinaHome and getCatalinaBase")
    class CatalinaAccessors {

        @Test
        @DisplayName("getCatalinaHome returns path")
        void catalinaHomeReturnsPath() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/opt/tomcat");
            assertEquals("/opt/tomcat", info.getCatalinaHome());
        }

        @Test
        @DisplayName("getCatalinaBase returns path")
        void catalinaBaseReturnsPath() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/opt/tomcat");
            assertEquals("/opt/tomcat", info.getCatalinaBase());
        }

        @Test
        @DisplayName("catalina paths match getPath")
        void catalinaPathsMatchGetPath() {
            TomcatInfo info = new TomcatInfo("Tomcat", "10", "/custom/path");
            assertEquals(info.getPath(), info.getCatalinaHome());
            assertEquals(info.getPath(), info.getCatalinaBase());
        }
    }
}
