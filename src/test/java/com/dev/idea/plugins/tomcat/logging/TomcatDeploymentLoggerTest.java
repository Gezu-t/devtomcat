package com.dev.idea.plugins.tomcat.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the logger's pure formatting helpers, exercised without a
 * platform fixture (no Project / ConsoleView). The console-routing path needs
 * the EDT and is not covered here; these tests pin the string-shaping logic
 * that the console path depends on.
 */
@DisplayName("TomcatDeploymentLogger formatting helpers")
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
}
