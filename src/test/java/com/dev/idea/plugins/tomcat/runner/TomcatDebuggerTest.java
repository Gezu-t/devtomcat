package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-logic methods in TomcatDebugger.
 *
 * <p>Instantiating the full debugger execution path requires IntelliJ Platform
 * infrastructure (GenericDebuggerRunner, ExecutionEnvironment, etc.), so only
 * the stateless identity methods are tested here.
 */
@DisplayName("TomcatDebugger")
class TomcatDebuggerTest {

    private final TomcatDebugger debugger = new TomcatDebugger();

    @Test
    @DisplayName("getRunnerId returns the expected constant")
    void runnerIdIsStable() {
        assertEquals("DevTomcatEnterpriseDebugger", debugger.getRunnerId());
    }

    @Test
    @DisplayName("getRunnerId is non-null and non-empty")
    void runnerIdIsNonEmpty() {
        String id = debugger.getRunnerId();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }
}
