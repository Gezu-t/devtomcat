package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.actionSystem.Presentation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ServiceActionUtils")
class ServiceActionUtilsTest {

    @Nested
    @DisplayName("isRunning")
    class IsRunningTests {

        @Test
        @DisplayName("null handler is not running")
        void nullHandlerNotRunning() {
            assertFalse(ServiceActionUtils.isRunning(null));
        }

        @Test
        @DisplayName("terminated handler is not running")
        void terminatedNotRunning() {
            ProcessHandler handler = new StubProcessHandler(true, false);
            assertFalse(ServiceActionUtils.isRunning(handler));
        }

        @Test
        @DisplayName("terminating handler is not running")
        void terminatingNotRunning() {
            ProcessHandler handler = new StubProcessHandler(false, true);
            assertFalse(ServiceActionUtils.isRunning(handler));
        }

        @Test
        @DisplayName("active handler is running")
        void activeIsRunning() {
            ProcessHandler handler = new StubProcessHandler(false, false);
            assertTrue(ServiceActionUtils.isRunning(handler));
        }
    }

    @Nested
    @DisplayName("applyStartupGate")
    class ApplyStartupGateTests {

        private static final String READY = "Restart the Tomcat server";

        @Test
        @DisplayName("hidden when config is missing")
        void hiddenWhenConfigMissing() {
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, null, mock(TomcatProcessHandler.class), READY);
            assertFalse(p.isVisible());
            assertFalse(p.isEnabled());
        }

        @Test
        @DisplayName("hidden when handler is missing")
        void hiddenWhenHandlerMissing() {
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), null, READY);
            assertFalse(p.isVisible());
            assertFalse(p.isEnabled());
        }

        @Test
        @DisplayName("hidden when handler is terminated")
        void hiddenWhenTerminated() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.isProcessTerminated()).thenReturn(true);
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertFalse(p.isVisible());
            assertFalse(p.isEnabled());
        }

        @Test
        @DisplayName("hidden when handler is terminating")
        void hiddenWhenTerminating() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.isProcessTerminating()).thenReturn(true);
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertFalse(p.isVisible());
            assertFalse(p.isEnabled());
        }

        @Test
        @DisplayName("visible but disabled with block reason while starting up")
        void visibleDisabledWhileStarting() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.getRestartBlockReason()).thenReturn("Tomcat is still starting");
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertTrue(p.isVisible());
            assertFalse(p.isEnabled());
            assertEquals("Tomcat is still starting", p.getDescription());
        }

        @Test
        @DisplayName("visible and enabled once startup completes")
        void visibleEnabledWhenReady() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.getRestartBlockReason()).thenReturn(null);
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertTrue(p.isVisible());
            assertTrue(p.isEnabled());
            assertEquals(READY, p.getDescription());
        }
    }

    /**
     * Minimal ProcessHandler stub for testing isRunning() logic.
     */
    private static class StubProcessHandler extends ProcessHandler {
        private final boolean terminated;
        private final boolean terminating;

        StubProcessHandler(boolean terminated, boolean terminating) {
            this.terminated = terminated;
            this.terminating = terminating;
        }

        @Override
        public boolean isProcessTerminated() {
            return terminated;
        }

        @Override
        public boolean isProcessTerminating() {
            return terminating;
        }

        @Override
        protected void destroyProcessImpl() {}

        @Override
        protected void detachProcessImpl() {}

        @Override
        public boolean detachIsDefault() {
            return false;
        }

        @Override
        public OutputStream getProcessInput() {
            return null;
        }
    }
}
