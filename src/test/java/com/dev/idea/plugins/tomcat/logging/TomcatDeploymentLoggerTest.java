package com.dev.idea.plugins.tomcat.logging;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the logger's pure formatting helpers and for the
 * pre-console replay buffer, both exercised without a platform fixture. With no
 * IntelliJ {@code Application} the logger prints straight through instead of
 * scheduling onto the EDT, so a mocked {@code ConsoleView} sees the calls in
 * order — enough to pin what is buffered, what is replayed, and what is dropped.
 */
@DisplayName("TomcatDeploymentLogger")
class TomcatDeploymentLoggerTest {

    @Nested
    @DisplayName("applyCategoryPrefix — no double-prefixing")
    class CategoryPrefix {

        @Test
        @DisplayName("prepends the bracket to a bare line")
        void prependsToBare() {
            assertEquals("[INFO] deploying",
                    TomcatDeploymentLogger.applyCategoryPrefix("deploying", "[INFO]"));
        }

        @Test
        @DisplayName("does not double a prefix the line already carries")
        void doesNotDouble() {
            assertEquals("[INFO] deploying",
                    TomcatDeploymentLogger.applyCategoryPrefix("[INFO] deploying", "[INFO]"));
        }

        @Test
        @DisplayName("a different leading bracket is not treated as a duplicate")
        void differentBracketKept() {
            assertEquals("[WARN] [INFO] relayed",
                    TomcatDeploymentLogger.applyCategoryPrefix("[INFO] relayed", "[WARN]"));
        }
    }

    @Nested
    @DisplayName("getStackTraceString — cause chain, cycle-safe")
    class StackTrace {

        @Test
        @DisplayName("renders the cause chain with 'Caused by:'")
        void rendersCauseChain() {
            Throwable root = new IllegalStateException("root-failure");
            Throwable wrap = new RuntimeException("wrap-failure", root);

            String s = TomcatDeploymentLogger.getStackTraceString(wrap);

            assertTrue(s.contains("wrap-failure"), "top exception must appear:\n" + s);
            assertTrue(s.contains("Caused by: "), "cause marker must appear:\n" + s);
            assertTrue(s.contains("root-failure"), "root cause must appear:\n" + s);
        }

        @Test
        @DisplayName("terminates on a cyclic cause chain instead of looping forever")
        void terminatesOnCycle() {
            Cyclic a = new Cyclic("alpha");
            Cyclic b = new Cyclic("beta");
            a.setLoop(b);
            b.setLoop(a);

            // Must return (the IdentityHashMap guard stops at the second visit);
            // a non-terminating walk would StackOverflow or hang here.
            String s = TomcatDeploymentLogger.getStackTraceString(a);

            assertTrue(s.contains(": alpha"), "first link must appear once:\n" + s);
            assertTrue(s.contains(": beta"), "second link must appear once:\n" + s);
            assertTrue(s.contains("Caused by: "), "the second link is rendered as a cause:\n" + s);
        }
    }

    /** Throwable that forces a {@code getCause()} cycle (standard initCause forbids one). */
    private static final class Cyclic extends RuntimeException {
        private transient Throwable loop;

        Cyclic(String message) {
            super(message);
        }

        void setLoop(Throwable t) {
            this.loop = t;
        }

        @Override
        public synchronized Throwable getCause() {
            return loop;
        }
    }

    @Nested
    @DisplayName("pre-console replay")
    class PreConsoleReplay {

        private TomcatDeploymentLogger logger() {
            Project project = mock(Project.class);
            when(project.getName()).thenReturn("p");
            when(project.isDisposed()).thenReturn(false);
            return new TomcatDeploymentLogger(project);
        }

        @Test
        @DisplayName("lines logged before a console exists are held, oldest first")
        void heldInOrder() {
            TomcatDeploymentLogger logger = logger();
            logger.logServerWarning("launch mode notice");
            logger.logServerInfo("Launch preparation finished");

            List<String> pending = logger.pendingSnapshot();
            assertEquals(2, pending.size(), pending.toString());
            assertTrue(pending.get(0).contains("launch mode notice"), pending.get(0));
            assertTrue(pending.get(1).contains("Launch preparation finished"), pending.get(1));
        }

        @Test
        @DisplayName("attaching a console drains the buffer exactly once")
        void drainedOnAttach() {
            TomcatDeploymentLogger logger = logger();
            logger.logServerInfo("only once");
            assertEquals(1, logger.pendingSnapshot().size());

            logger.setConsoleView(mock(ConsoleView.class));
            assertTrue(logger.pendingSnapshot().isEmpty(), "buffer must be drained on attach");

            logger.setConsoleView(mock(ConsoleView.class));
            assertTrue(logger.pendingSnapshot().isEmpty(), "a second attach must replay nothing");
        }

        @Test
        @DisplayName("with a console attached nothing is buffered")
        void liveLinesAreNotBuffered() {
            TomcatDeploymentLogger logger = logger();
            logger.setConsoleView(mock(ConsoleView.class));

            logger.logServerInfo("live");
            assertTrue(logger.pendingSnapshot().isEmpty());
        }

        @Test
        @DisplayName("overflow past the cap is counted, and the held lines stay the earliest ones")
        void overflowCounted() {
            TomcatDeploymentLogger logger = logger();
            for (int i = 0; i < 520; i++) logger.logServerInfo("line " + i);

            List<String> pending = logger.pendingSnapshot();
            assertEquals(500, pending.size());
            assertEquals(20, logger.pendingOverflowCount());
            // Earliest kept: launch preparation is what this buffer exists for.
            assertTrue(pending.get(0).contains("line 0"), pending.get(0));
            assertTrue(pending.get(499).contains("line 499"), pending.get(499));
        }

        @Test
        @DisplayName("a disposed logger buffers nothing")
        void disposedBuffersNothing() {
            TomcatDeploymentLogger logger = logger();
            logger.dispose();
            logger.logServerInfo("dropped");

            assertTrue(logger.pendingSnapshot().isEmpty());
        }
    }
}
