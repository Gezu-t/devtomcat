package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.execution.configurations.GeneralCommandLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    @DisplayName("returns false for unrelated agent like -agentlib:jdwp_other")
    void unrelatedAgentDoesNotMatch() {
        assertFalse(TomcatCommandLineState.hasManualJdwpAgent("-agentlib:jdwp_other"));
    }

    @Test
    @DisplayName("detects -agentlib:jdwp without =")
    void detectsJdwpAgentBare() {
        assertTrue(TomcatCommandLineState.hasManualJdwpAgent("-agentlib:jdwp"));
    }

    @Test
    @DisplayName("detects -agentlib:jdwp followed by space")
    void detectsJdwpAgentWithSpace() {
        assertTrue(TomcatCommandLineState.hasManualJdwpAgent("-agentlib:jdwp -Xmx512m"));
    }

    @Test
    @DisplayName("inserts jpda before catalina run")
    void enablesCatalinaJpdaForRun() {
        assertEquals(
                List.of("catalina.sh", "jpda", "run"),
                TomcatCommandLineState.enableCatalinaJpda(List.of("catalina.sh", "run"))
        );
    }

    @Test
    @DisplayName("does not duplicate jpda when already present")
    void doesNotDuplicateCatalinaJpda() {
        assertEquals(
                List.of("catalina.sh", "jpda", "run"),
                TomcatCommandLineState.enableCatalinaJpda(List.of("catalina.sh", "jpda", "run"))
        );
    }

    @Test
    @DisplayName("appendVmOptIfMissing appends JDWP to existing opts once")
    void appendVmOptIfMissingAppendsOnce() {
        String jdwp = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005";

        assertEquals(
                "-Xmx512m " + jdwp,
                TomcatCommandLineState.appendVmOptIfMissing("-Xmx512m", jdwp)
        );
        assertEquals(
                jdwp,
                TomcatCommandLineState.appendVmOptIfMissing(jdwp, jdwp)
        );
    }

    @Test
    @DisplayName("custom script debug support exports JDWP to common Tomcat env vars")
    void applyCustomScriptDebugSupportExportsEnv() {
        GeneralCommandLine commandLine = new GeneralCommandLine("catalina.sh", "jpda", "run");
        commandLine.withEnvironment(TomcatConstants.ENV_CATALINA_OPTS, "-Dfoo=bar");

        TomcatCommandLineState.applyCustomScriptDebugSupport(
                commandLine, List.of("catalina.sh", "jpda", "run"), 5005);

        assertEquals("5005", commandLine.getEnvironment().get(TomcatConstants.ENV_DEBUG_PORT));
        assertEquals("5005", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_ADDRESS));
        assertEquals("dt_socket", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_TRANSPORT));
        assertEquals("n", commandLine.getEnvironment().get(TomcatConstants.ENV_JPDA_SUSPEND));
        assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JDWP_OPTS).contains("address=*:5005"));
        assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS).contains("address=*:5005"));
        assertTrue(commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS).contains("address=*:5005"));
    }
}
