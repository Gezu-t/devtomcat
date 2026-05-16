package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-static {@code describeFailure} helper in
 * {@link TomcatCommandLineState}. The helper guarantees that a failure
 * description is never empty, so the user always sees at least the
 * exception class name when the cause's {@code getMessage()} is null —
 * which is common for {@code NullPointerException},
 * {@code IllegalStateException}, etc. when constructed without a message.
 *
 * <p>Without this guarantee, the wrap in {@code createJavaParameters} /
 * {@code startProcess} can produce a literal "ExecutionException: null"
 * in the run console — a worst-case error surface that this test pins
 * against.
 */
class TomcatCommandLineStateTest {

    @Test
    @DisplayName("describeFailure returns 'ClassName: message' when message is non-empty")
    void withMessage() {
        Throwable t = new IllegalStateException("port 8080 is taken");
        assertEquals("IllegalStateException: port 8080 is taken",
                TomcatCommandLineState.describeFailure(t));
    }

    @Test
    @DisplayName("describeFailure returns 'ClassName' when message is null")
    void nullMessage() {
        Throwable t = new NullPointerException();   // getMessage() == null
        String result = TomcatCommandLineState.describeFailure(t);
        assertEquals("NullPointerException", result);
        assertTrue(!result.contains("null"),
                "description must never literally contain 'null' on null messages");
    }

    @Test
    @DisplayName("describeFailure returns 'ClassName' when message is blank")
    void blankMessage() {
        Throwable t = new RuntimeException("   ");
        assertEquals("RuntimeException", TomcatCommandLineState.describeFailure(t));
    }

    @Test
    @DisplayName("describeFailure handles checked exceptions identically")
    void checkedExceptionWithMessage() {
        Throwable t = new java.io.IOException("disk full");
        assertEquals("IOException: disk full", TomcatCommandLineState.describeFailure(t));
    }
}
