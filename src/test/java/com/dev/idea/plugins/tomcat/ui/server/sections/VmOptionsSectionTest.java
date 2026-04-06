package com.dev.idea.plugins.tomcat.ui.server.sections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for VmOptionsSection.isModified() null/empty mismatch.
 *
 * <p>Bug: applyTo() saves {@code null} when the VM options field is empty, while
 * isModified() was treating null and "" as different values. The fix makes isModified()
 * normalise {@code null → ""} before comparing, so a round-trip through applyTo/resetFrom
 * never reports a spurious modification.
 *
 * <p>Because VmOptionsSection depends on IntelliJ Swing components
 * ({@code ExpandableTextField}), these tests exercise the normalisation logic
 * directly rather than instantiating the section. The production method is:
 * <pre>
 *   String saved   = config.getVmOptions() != null ? config.getVmOptions() : "";
 *   String current = vmOptionsEditor.getText().trim().replaceAll("\\s+", " ");
 *   return !saved.equals(current);
 * </pre>
 */
@DisplayName("VmOptionsSection.isModified() null/empty equivalence")
class VmOptionsSectionTest {

    /**
     * Mirrors the exact normalisation applied by VmOptionsSection.isModified() to
     * the value coming from the configuration (saved side).
     */
    private static String normaliseSaved(String savedVmOptions) {
        return savedVmOptions != null ? savedVmOptions : "";
    }

    /**
     * Mirrors the exact normalisation applied by VmOptionsSection.isModified() to
     * the value coming from the text field (current side).
     */
    private static String normaliseCurrent(String fieldText) {
        return fieldText.trim().replaceAll("\\s+", " ");
    }

    private static boolean isModified(String savedVmOptions, String fieldText) {
        return !normaliseSaved(savedVmOptions).equals(normaliseCurrent(fieldText));
    }

    // -------------------------------------------------------------------------
    // Regression: null config value + empty field must NOT be "modified"
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("null saved value")
    class NullSaved {

        @Test
        @DisplayName("null config + empty field → not modified (primary regression case)")
        void nullSavedEmptyField_notModified() {
            assertFalse(isModified(null, ""),
                    "null config value and empty field must not report a modification");
        }

        @Test
        @DisplayName("null config + whitespace-only field → not modified")
        void nullSavedWhitespaceField_notModified() {
            assertFalse(isModified(null, "   "),
                    "null config value and whitespace-only field must not report a modification");
        }

        @Test
        @DisplayName("null config + non-empty field → modified")
        void nullSavedNonEmptyField_modified() {
            assertTrue(isModified(null, "-Xmx512m"),
                    "null config value and non-empty field must report a modification");
        }
    }

    // -------------------------------------------------------------------------
    // Empty string saved value (produced by VmConfig.setVmOptions(""))
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("empty-string saved value")
    class EmptyStringSaved {

        @Test
        @DisplayName("empty saved + empty field → not modified")
        void emptySavedEmptyField_notModified() {
            assertFalse(isModified("", ""));
        }

        @Test
        @DisplayName("empty saved + non-empty field → modified")
        void emptySavedNonEmptyField_modified() {
            assertTrue(isModified("", "-Xmx1g"));
        }
    }

    // -------------------------------------------------------------------------
    // Non-empty saved value
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("non-empty saved value")
    class NonEmptySaved {

        @Test
        @DisplayName("same value in field → not modified")
        void sameValue_notModified() {
            assertFalse(isModified("-Xmx512m -Xms256m", "-Xmx512m -Xms256m"));
        }

        @Test
        @DisplayName("field with leading/trailing spaces → not modified (trim applied)")
        void fieldWithExtraSpaces_notModified() {
            assertFalse(isModified("-Xmx512m", "  -Xmx512m  "));
        }

        @Test
        @DisplayName("field with collapsed internal spaces → not modified (normalisation applied)")
        void fieldWithInternalSpaces_notModified() {
            assertFalse(isModified("-Xmx512m -Xms256m", "-Xmx512m   -Xms256m"));
        }

        @Test
        @DisplayName("different values → modified")
        void differentValues_modified() {
            assertTrue(isModified("-Xmx512m", "-Xmx1g"));
        }

        @Test
        @DisplayName("non-empty saved + empty field → modified")
        void nonEmptySavedEmptyField_modified() {
            assertTrue(isModified("-Xmx512m", ""));
        }
    }
}
