package com.dev.idea.plugins.tomcat.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pre-flight duplicate-context-path detection used by the Setup wizard.
 * The contract: two raw user-typed context paths collide iff
 * {@link SetupDevTomcatProfileAction#canonicalizeForCompare} returns equal
 * strings — same rule the runtime {@code TomcatConfigurationValidator} uses
 * for the post-Apply check, so the wizard never lets through a config that
 * would fail at the next gate.
 */
@DisplayName("SetupDevTomcatProfileAction.canonicalizeForCompare")
class SetupContextPathDuplicateDetectionTest {

    @Test
    @DisplayName("identical bare names collide")
    void identicalBareName() {
        assertEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("api"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("api"));
    }

    @Test
    @DisplayName("/foo and /foo/ resolve to the same context name")
    void trailingSlashFolded() {
        assertEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo/"));
    }

    @Test
    @DisplayName("foo and /foo resolve to the same context name")
    void leadingSlashFolded() {
        assertEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("foo"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo"));
    }

    @Test
    @DisplayName("two empty/root variants all resolve to ROOT")
    void rootVariantsAllEqual() {
        String root = SetupDevTomcatProfileAction.canonicalizeForCompare("/");
        assertEquals(root, SetupDevTomcatProfileAction.canonicalizeForCompare(""));
        assertEquals(root, SetupDevTomcatProfileAction.canonicalizeForCompare("  /  "));
    }

    @Test
    @DisplayName("different paths do not collide")
    void differentPathsDistinct() {
        assertNotEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("/bar"));
    }

    @Test
    @DisplayName("nested path is distinct from its prefix")
    void nestedDistinctFromPrefix() {
        assertNotEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo/bar"));
    }

    @Test
    @DisplayName("case differences are preserved — Tomcat is case-sensitive")
    void caseSensitive() {
        assertNotEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("/Foo"),
                SetupDevTomcatProfileAction.canonicalizeForCompare("/foo"));
    }

    @Test
    @DisplayName("traversal-bearing paths bucket separately rather than silently folding")
    void traversalIsolated() {
        String dotdot = SetupDevTomcatProfileAction.canonicalizeForCompare("/foo/../bar");
        // Must not coincide with the genuinely intended /bar entry.
        assertNotEquals(
                SetupDevTomcatProfileAction.canonicalizeForCompare("/bar"),
                dotdot);
    }
}
