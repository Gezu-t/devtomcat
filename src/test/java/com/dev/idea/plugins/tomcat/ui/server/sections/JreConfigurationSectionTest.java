package com.dev.idea.plugins.tomcat.ui.server.sections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("JreConfigurationSection.extractMajorVersion")
class JreConfigurationSectionTest {

    @Test
    @DisplayName("canonical Java 17 version string")
    void java17() {
        assertEquals("17", JreConfigurationSection.extractMajorVersion("17.0.2"));
    }

    @Test
    @DisplayName("Java 21 with build and pre-release tags")
    void java21WithBuildAndTag() {
        assertEquals("21", JreConfigurationSection.extractMajorVersion("21.0.1+12-LTS"));
    }

    @Test
    @DisplayName("legacy 1.8 form maps to 8")
    void legacy18() {
        assertEquals("8", JreConfigurationSection.extractMajorVersion("1.8.0_351"));
    }

    @Test
    @DisplayName("vendor-quoted form like 'java version \"21.0.1\"'")
    void vendorQuoted() {
        assertEquals("21", JreConfigurationSection.extractMajorVersion("java version \"21.0.1\""));
    }

    @Test
    @DisplayName("Java 11 plain")
    void java11Plain() {
        assertEquals("11", JreConfigurationSection.extractMajorVersion("11.0.20"));
    }

    @Test
    @DisplayName("null input returns null")
    void nullInput() {
        assertNull(JreConfigurationSection.extractMajorVersion(null));
    }

    @Test
    @DisplayName("empty string returns null")
    void emptyString() {
        assertNull(JreConfigurationSection.extractMajorVersion(""));
    }

    @Test
    @DisplayName("non-numeric garbage returns null")
    void nonNumericGarbage() {
        assertNull(JreConfigurationSection.extractMajorVersion("not a version"));
    }

    @Test
    @DisplayName("single-digit feature version")
    void singleDigit() {
        assertEquals("8", JreConfigurationSection.extractMajorVersion("1.8"));
    }

    @Test
    @DisplayName("trailing-dot input still parses")
    void trailingDot() {
        assertEquals("17", JreConfigurationSection.extractMajorVersion("17.0."));
    }

    @Test
    @DisplayName("trailing-plus input still parses")
    void trailingPlus() {
        assertEquals("21", JreConfigurationSection.extractMajorVersion("21+"));
    }

    @Test
    @DisplayName("leading whitespace and prefix tolerated")
    void leadingPrefix() {
        assertEquals("17", JreConfigurationSection.extractMajorVersion("  OpenJDK 17.0.2  "));
    }
}
