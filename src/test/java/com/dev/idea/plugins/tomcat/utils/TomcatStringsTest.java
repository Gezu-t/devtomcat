package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TomcatStrings} — specifically the
 * {@code defaultIfBlank} contract that closes the
 * {@code StringUtil.notNullize(value, default)} empty-string trap.
 *
 * <p>The categories below pin the four input shapes the helper must
 * normalize to default: {@code null}, empty, whitespace-only, and
 * the (non-default) "look-alike-but-different" case where the input is
 * non-blank and must be preserved verbatim.
 */
@DisplayName("TomcatStrings.defaultIfBlank")
class TomcatStringsTest {

    private static final String DEFAULT = "DEFAULT";

    @Nested
    @DisplayName("blank inputs ⇒ default")
    class BlankInputs {

        @Test
        @DisplayName("null input returns the default")
        void nullReturnsDefault() {
            assertEquals(DEFAULT, TomcatStrings.defaultIfBlank(null, DEFAULT));
        }

        @Test
        @DisplayName("empty string returns the default — the original notNullize bug")
        void emptyReturnsDefault() {
            // The whole reason this helper exists. StringUtil.notNullize("", DEFAULT)
            // returns "" and silently violates the invariant the default is meant
            // to enforce. defaultIfBlank fixes that.
            assertEquals(DEFAULT, TomcatStrings.defaultIfBlank("", DEFAULT));
        }

        @Test
        @DisplayName("whitespace-only string returns the default")
        void whitespaceReturnsDefault() {
            // Same family of bug as empty: a stored "  " is also semantically
            // "no value supplied" and must normalize to default. notNullize
            // would have returned "  " unchanged.
            assertEquals(DEFAULT, TomcatStrings.defaultIfBlank("   ", DEFAULT));
        }

        @Test
        @DisplayName("tab and newline whitespace also count as blank")
        void mixedWhitespaceReturnsDefault() {
            // String.isBlank() covers the full Unicode whitespace class —
            // including tab, newline, vertical-tab. Pin that the helper
            // honors the broad definition rather than only ASCII space.
            assertEquals(DEFAULT, TomcatStrings.defaultIfBlank("\t\n  ", DEFAULT));
        }
    }

    @Nested
    @DisplayName("non-blank inputs ⇒ preserved verbatim")
    class NonBlankInputs {

        @Test
        @DisplayName("typical non-empty value is returned unchanged")
        void preservesValue() {
            assertEquals("custom", TomcatStrings.defaultIfBlank("custom", DEFAULT));
        }

        @Test
        @DisplayName("does not trim leading/trailing whitespace")
        void preservesSurroundingWhitespace() {
            // Trimming is a separate concern. The helper only decides
            // "blank vs not blank"; preserving surrounding whitespace
            // lets callers decide whether to trim independently.
            assertEquals(" value ", TomcatStrings.defaultIfBlank(" value ", DEFAULT));
        }

        @Test
        @DisplayName("a single non-whitespace character qualifies as non-blank")
        void singleCharIsNonBlank() {
            // Boundary: even one printable character is enough to escape
            // the default.
            assertEquals("/", TomcatStrings.defaultIfBlank("/", DEFAULT));
            assertEquals("a", TomcatStrings.defaultIfBlank("a", DEFAULT));
        }

        @Test
        @DisplayName("value containing whitespace plus a real character is non-blank")
        void mixedContentIsNonBlank() {
            // " a " has one non-whitespace char — must be preserved.
            assertEquals(" a ", TomcatStrings.defaultIfBlank(" a ", DEFAULT));
        }
    }

    @Nested
    @DisplayName("real-world callers (smoke tests)")
    class RealWorldShapes {

        @Test
        @DisplayName("contextPath: empty stored value normalizes to '/'")
        void contextPathEmpty() {
            assertEquals("/", TomcatStrings.defaultIfBlank("", "/"));
        }

        @Test
        @DisplayName("jreSelection: whitespace stored value normalizes to 'Project default'")
        void jreSelectionWhitespace() {
            assertEquals("Project default", TomcatStrings.defaultIfBlank("  ", "Project default"));
        }

        @Test
        @DisplayName("managerUrl: null normalizes to default URL")
        void managerUrlNull() {
            assertEquals("http://localhost:8080/manager",
                    TomcatStrings.defaultIfBlank(null, "http://localhost:8080/manager"));
        }
    }
}
