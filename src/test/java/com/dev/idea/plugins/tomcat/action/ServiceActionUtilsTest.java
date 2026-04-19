package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.pom.Navigatable;
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
        @DisplayName("hidden when handler is fully terminated")
        void hiddenWhenFullyTerminated() {
            // Fully terminated = terminated && !terminating. The node is gone.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.isFullyTerminated()).thenReturn(true);
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertFalse(p.isVisible());
            assertFalse(p.isEnabled());
        }

        @Test
        @DisplayName("shutdown overlap (terminating+terminated) stays visible-disabled")
        void shutdownOverlapStaysVisibleDisabled() {
            // The handler's javadoc documents that terminating and terminated can
            // both briefly read true. Using the raw terminated flag here would hide
            // the button in that window, bypassing the shared gate. Gating on
            // isFullyTerminated() keeps the overlap routed through
            // getRestartBlockReason() so the "shutting down" tooltip still appears.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.isFullyTerminated()).thenReturn(false);
            when(handler.getRestartBlockReason()).thenReturn("Tomcat is shutting down");
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertTrue(p.isVisible());
            assertFalse(p.isEnabled());
            assertEquals("Tomcat is shutting down", p.getDescription());
        }

        @Test
        @DisplayName("visible but disabled when handler is terminating (shutdown tooltip)")
        void visibleDisabledWhenTerminating() {
            // Terminating is a user-visible transient state, not a "gone" state —
            // keep the button in place with the shared block reason as tooltip so
            // the rerun-icon and Services-panel surfaces show the same message.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            when(handler.getRestartBlockReason()).thenReturn("Tomcat is shutting down");
            Presentation p = new Presentation();
            ServiceActionUtils.applyStartupGate(p, mock(TomcatRunConfiguration.class), handler, READY);
            assertTrue(p.isVisible());
            assertFalse(p.isEnabled());
            assertEquals("Tomcat is shutting down", p.getDescription());
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

    @Nested
    @DisplayName("findTomcatProcessHandler")
    class FindTomcatProcessHandlerTests {

        @Test
        @DisplayName("resolves wrapped navigatable values from Services selection")
        void resolvesWrappedNavigatableValues() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            RunContentDescriptor descriptor = mock(RunContentDescriptor.class);
            when(descriptor.getProcessHandler()).thenReturn(handler);

            AnActionEvent event = mock(AnActionEvent.class);
            when(event.getData(CommonDataKeys.NAVIGATABLE))
                    .thenReturn(new ValueNavigatable(new DescriptorWrapper(descriptor)));
            when(event.getData(PlatformCoreDataKeys.SELECTED_ITEMS)).thenReturn(null);

            assertSame(handler, ServiceActionUtils.findTomcatProcessHandler(event));
        }

        @Test
        @DisplayName("resolves wrapped selected items when navigatable is absent")
        void resolvesWrappedSelectedItems() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            RunContentDescriptor descriptor = mock(RunContentDescriptor.class);
            when(descriptor.getProcessHandler()).thenReturn(handler);

            AnActionEvent event = mock(AnActionEvent.class);
            when(event.getData(CommonDataKeys.NAVIGATABLE)).thenReturn(null);
            when(event.getData(PlatformCoreDataKeys.SELECTED_ITEMS))
                    .thenReturn(new Object[]{new ValueWrapper(descriptor)});

            assertSame(handler, ServiceActionUtils.findTomcatProcessHandler(event));
        }

        @Test
        @DisplayName("self-referencing wrapper does not stack-overflow")
        void selfReferencingWrapperDoesNotLoop() {
            // Pathological wrapper: getValue() returns itself. Without the
            // MAX_UNWRAP_DEPTH guard this recurses forever and SOEs the EDT.
            // The guard must make extractProcessHandler bail cleanly with null.
            AnActionEvent event = mock(AnActionEvent.class);
            when(event.getData(CommonDataKeys.NAVIGATABLE)).thenReturn(null);
            when(event.getData(PlatformCoreDataKeys.SELECTED_ITEMS))
                    .thenReturn(new Object[]{new SelfReferencingWrapper()});

            // Completes and returns null — the important thing is no StackOverflowError.
            assertNull(ServiceActionUtils.findTomcatProcessHandler(event));
        }

        @Test
        @DisplayName("two-node wrapper cycle does not stack-overflow")
        void cycleBetweenTwoWrappersDoesNotLoop() {
            // A getValue() B, B getValue() A — also unbounded without the guard.
            // Most realistic cycle shape after a single-node self-ref.
            CycleWrapper a = new CycleWrapper();
            CycleWrapper b = new CycleWrapper();
            a.peer = b;
            b.peer = a;

            AnActionEvent event = mock(AnActionEvent.class);
            when(event.getData(CommonDataKeys.NAVIGATABLE)).thenReturn(null);
            when(event.getData(PlatformCoreDataKeys.SELECTED_ITEMS))
                    .thenReturn(new Object[]{a});

            assertNull(ServiceActionUtils.findTomcatProcessHandler(event));
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

    private static final class ValueNavigatable implements Navigatable {
        private final Object value;

        private ValueNavigatable(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return value;
        }

        @Override
        public void navigate(boolean requestFocus) {}

        @Override
        public boolean canNavigate() {
            return false;
        }

        @Override
        public boolean canNavigateToSource() {
            return false;
        }
    }

    private static final class ValueWrapper {
        private final Object value;

        private ValueWrapper(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return value;
        }
    }

    private static final class DescriptorWrapper {
        private final RunContentDescriptor descriptor;

        private DescriptorWrapper(RunContentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        public RunContentDescriptor getDescriptor() {
            return descriptor;
        }
    }

    /** Wrapper whose {@code getValue()} returns itself — exercises the depth cap. */
    private static final class SelfReferencingWrapper {
        public Object getValue() {
            return this;
        }
    }

    /**
     * Wrapper pair {@code a.getValue() == b}, {@code b.getValue() == a} — the
     * shortest possible wrapper cycle after a single-node self-ref.
     */
    private static final class CycleWrapper {
        CycleWrapper peer;

        public Object getValue() {
            return peer;
        }
    }
}
