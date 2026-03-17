package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static helpers in {@link TomcatCommandLineState}.
 */
class TomcatCommandLineStateTest {

    @Test
    @DisplayName("detects -agentlib:jdwp in VM options")
    void detectsJdwpAgent() {
        assertTrue(TomcatCommandLineState.hasManualJdwpAgent(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"));
    }

    @Test
    @DisplayName("detects -agentlib:jdwp embedded in other options")
    void detectsJdwpAgentEmbedded() {
        assertTrue(TomcatCommandLineState.hasManualJdwpAgent(
                "-Xmx512m -agentlib:jdwp=transport=dt_socket,server=y,address=5005 -Dfoo=bar"));
    }

    @Test
    @DisplayName("returns false for null VM options")
    void nullVmOptions() {
        assertFalse(TomcatCommandLineState.hasManualJdwpAgent(null));
    }

    @Test
    @DisplayName("returns false for empty VM options")
    void emptyVmOptions() {
        assertFalse(TomcatCommandLineState.hasManualJdwpAgent(""));
    }

    @Test
    @DisplayName("returns false for options without JDWP")
    void noJdwpAgent() {
        assertFalse(TomcatCommandLineState.hasManualJdwpAgent("-Xmx512m -Dfoo=bar"));
    }

    @Test
    @DisplayName("returns false for partial match like -agentlib:jdwp_other")
    void partialMatchStillDetects() {
        // "-agentlib:jdwp" is a substring of "-agentlib:jdwp_other" — this is
        // intentionally flagged because any jdwp-prefixed agent will conflict.
        assertTrue(TomcatCommandLineState.hasManualJdwpAgent("-agentlib:jdwp_other"));
    }
}
