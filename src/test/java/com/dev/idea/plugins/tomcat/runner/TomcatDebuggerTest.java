package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
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

    @Test
    @DisplayName("local attach prefers resolved runtime debug port")
    void localAttachPrefersResolvedRuntimePort() {
        DebugConfig config = new DebugConfig();
        config.setPort(5005);

        assertEquals(5011, TomcatDebugger.resolveDebugPortForAttach(false, null, 5011, config));
    }

    @Test
    @DisplayName("local attach falls back to configured debug port")
    void localAttachFallsBackToConfiguredPort() {
        DebugConfig config = new DebugConfig();
        config.setPort(5009);

        assertEquals(5009, TomcatDebugger.resolveDebugPortForAttach(false, null, -1, config));
    }

    @Test
    @DisplayName("remote attach uses runner settings host and port")
    void remoteAttachUsesRunnerSettings() {
        RunnerSettings settings = new RunnerSettings();
        settings.setDebugHost("10.0.0.8");
        settings.setDebugPort(9009);

        assertEquals("10.0.0.8", TomcatDebugger.resolveDebugHostForAttach(true, settings));
        assertEquals(9009, TomcatDebugger.resolveDebugPortForAttach(true, settings, -1, null));
    }

    @Test
    @DisplayName("defaults stay on localhost and 5005 when no debug settings exist")
    void defaultsAreStable() {
        assertEquals("127.0.0.1", TomcatDebugger.resolveDebugHostForAttach(false, null));
        assertEquals("127.0.0.1", TomcatDebugger.resolveDebugHostForAttach(true, null));
        assertEquals(DebugConfig.DEFAULT_DEBUG_PORT,
                TomcatDebugger.resolveDebugPortForAttach(false, null, -1, null));
        assertEquals(DebugConfig.DEFAULT_DEBUG_PORT,
                TomcatDebugger.resolveDebugPortForAttach(true, null, -1, null));
    }
}
