package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ThreadDumpJdkFrameFolding}: JDK frames in Tomcat's
 * leaked-thread stacks (no {@code at}, module qualifier first) fold; the
 * application frame that names the code which started the thread stays
 * visible. The mock {@code Project} satisfies the {@code @NotNull} guard.
 */
class ThreadDumpJdkFrameFoldingTest {

    private final ThreadDumpJdkFrameFolding folding = new ThreadDumpJdkFrameFolding();
    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("folds JDK frames")
    class Folded {

        @Test
        @DisplayName("module-qualified frame with version")
        void moduleQualified() {
            assertTrue(folding.shouldFoldLine(project,
                    " java.base@21/jdk.internal.misc.Unsafe.park(Native Method)"));
            assertTrue(folding.shouldFoldLine(project,
                    " java.base@21/java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)"));
            assertTrue(folding.shouldFoldLine(project,
                    " java.base@21/java.lang.Thread.run(Thread.java:1583)"));
        }

        @Test
        @DisplayName("inner and lambda classes")
        void innerClasses() {
            assertTrue(folding.shouldFoldLine(project,
                    " java.base@21/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)"));
            assertTrue(folding.shouldFoldLine(project,
                    " java.base@21/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)"));
        }

        @Test
        @DisplayName("platform class loader prefix")
        void platformLoader() {
            assertTrue(folding.shouldFoldLine(project,
                    " platform/java.net.http@21/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:901)"));
        }

        @Test
        @DisplayName("bare Java 8 form without module")
        void bareForm() {
            assertTrue(folding.shouldFoldLine(project, " sun.misc.Unsafe.park(Native Method)"));
            assertTrue(folding.shouldFoldLine(project, " java.lang.Thread.run(Thread.java:748)"));
            assertTrue(folding.shouldFoldLine(project, "\tjdk.internal.reflect.GeneratedMethodAccessor12.invoke(Unknown Source)"));
        }

        @Test
        @DisplayName("placeholder counts the folded frames")
        void placeholder() {
            String text = folding.getPlaceholderText(project, List.of("a", "b", "c"));
            assertEquals("  <3 JDK frames>", text);
        }
    }

    @Nested
    @DisplayName("keeps everything else visible")
    class Kept {

        @Test
        @DisplayName("application frame, with or without a loader prefix")
        void applicationFrame() {
            assertFalse(folding.shouldFoldLine(project, " com.example.jobs.Poller.run(Poller.java:58)"));
            assertFalse(folding.shouldFoldLine(project, " app//com.example.jobs.Poller.run(Poller.java:58)"));
            assertFalse(folding.shouldFoldLine(project, " org.example.web.Scheduler.lambda$start$0(Scheduler.java:31)"));
        }

        @Test
        @DisplayName("Tomcat frames belong to the Tomcat folding")
        void tomcatFrame() {
            assertFalse(folding.shouldFoldLine(project,
                    " org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63)"));
        }

        @Test
        @DisplayName("'at' frames are the platform's to fold")
        void atFrames() {
            assertFalse(folding.shouldFoldLine(project, "\tat java.base/java.lang.Thread.run(Thread.java:1583)"));
            assertFalse(folding.shouldFoldLine(project, "\tat java.lang.Thread.run(Thread.java:748)"));
        }

        @Test
        @DisplayName("exception headers and log lines that start with a JDK class")
        void headersAndLogLines() {
            assertFalse(folding.shouldFoldLine(project, "java.lang.IllegalStateException: pool is shut down"));
            assertFalse(folding.shouldFoldLine(project, "java.lang.RuntimeException: failed (see Poller.java:3)"));
            assertFalse(folding.shouldFoldLine(project, "java.util.logging.LogManager configured"));
            assertFalse(folding.shouldFoldLine(project,
                    "WARNING [main] org.apache.catalina.loader.WebappClassLoaderBase.clearReferencesThreads "
                            + "The web application [web-module] appears to have started a thread named [worker-1] "
                            + "but has failed to stop it. Stack trace of thread:"));
        }

        @Test
        @DisplayName("blank line")
        void blank() {
            assertFalse(folding.shouldFoldLine(project, ""));
            assertFalse(folding.shouldFoldLine(project, "   "));
        }
    }
}
