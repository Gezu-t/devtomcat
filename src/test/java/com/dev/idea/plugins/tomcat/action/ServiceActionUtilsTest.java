package com.dev.idea.plugins.tomcat.action;

import com.intellij.execution.process.ProcessHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

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
