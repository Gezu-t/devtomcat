package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TomcatStackFrameFolding}. The folding is meant to
 * collapse runs of container-internal frames into a single placeholder while
 * leaving user frames and JDK frames visible — verifying that boundary is the
 * point of these tests.
 *
 * <p>The {@code Project} parameter on the platform method signature is never
 * dereferenced by our implementation; the tests pass a Mockito mock to satisfy
 * the {@code @NotNull} bytecode guard the IntelliJ Gradle plugin instruments
 * into the compiled class.
 */
class TomcatStackFrameFoldingTest {

    private final TomcatStackFrameFolding folding = new TomcatStackFrameFolding();
    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("folds Tomcat-internal stack frames")
    class FoldedFrames {

        @Test
        @DisplayName("org.apache.catalina.* frames")
        void catalinaFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:230)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:202)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.catalina.startup.HostConfig.deployDirectory(HostConfig.java:1136)"));
        }

        @Test
        @DisplayName("org.apache.coyote.* frames")
        void coyoteFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:391)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:889)"));
        }

        @Test
        @DisplayName("org.apache.tomcat.* frames")
        void tomcatFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1191)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.tomcat.util.net.NioEndpoint$Poller.run(NioEndpoint.java:737)"));
        }

        @Test
        @DisplayName("org.apache.jasper.* (JSP runtime) frames")
        void jasperFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.jasper.servlet.JspServletWrapper.handleJspException(JspServletWrapper.java:579)"));
        }

        @Test
        @DisplayName("org.apache.el.* / org.apache.naming.* / org.apache.juli.* frames")
        void supportingApacheFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.el.parser.AstValue.getValue(AstValue.java:140)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.naming.resources.FileDirContext.doGetAttributes(FileDirContext.java:438)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat org.apache.juli.logging.DirectJDKLog.log(DirectJDKLog.java:172)"));
        }

        @Test
        @DisplayName("jakarta.servlet.* frames")
        void jakartaServletFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat jakarta.servlet.http.HttpServlet.service(HttpServlet.java:529)"));
            assertTrue(folding.shouldFoldLine(project,
                    "\tat jakarta.servlet.http.HttpServlet.service(HttpServlet.java:623)"));
        }

        @Test
        @DisplayName("javax.servlet.* frames (legacy / pre-Tomcat-10)")
        void javaxServletFrames() {
            assertTrue(folding.shouldFoldLine(project,
                    "\tat javax.servlet.http.HttpServlet.service(HttpServlet.java:741)"));
        }

        @Test
        @DisplayName("indentation variance — multiple spaces, mixed tabs")
        void indentationFlexibility() {
            assertTrue(folding.shouldFoldLine(project,
                    "    at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:202)"));
            assertTrue(folding.shouldFoldLine(project,
                    " \t at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:391)"));
        }
    }

    @Nested
    @DisplayName("does NOT fold non-Tomcat frames")
    class UnfoldedFrames {

        @Test
        @DisplayName("user code frames stay visible")
        void userFrames() {
            assertFalse(folding.shouldFoldLine(project,
                    "\tat com.myapp.UserService.lookup(UserService.java:42)"));
            assertFalse(folding.shouldFoldLine(project,
                    "\tat io.acme.MyController.handle(MyController.kt:18)"));
        }

        @Test
        @DisplayName("JDK frames (platform folds these separately)")
        void jdkFrames() {
            assertFalse(folding.shouldFoldLine(project,
                    "\tat java.base/java.lang.Thread.run(Thread.java:842)"));
            assertFalse(folding.shouldFoldLine(project,
                    "\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)"));
        }

        @Test
        @DisplayName("third-party Apache projects unrelated to Tomcat")
        void otherApacheProjectsKeepVisible() {
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.apache.commons.io.IOUtils.copy(IOUtils.java:142)"));
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.apache.http.client.HttpClient.execute(HttpClient.java:55)"));
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.apache.kafka.clients.producer.KafkaProducer.send(KafkaProducer.java:912)"));
        }

        @Test
        @DisplayName("framework frames (Spring, Hibernate)")
        void frameworkFrames() {
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1067)"));
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.hibernate.engine.spi.AbstractEntityPersister.load(AbstractEntityPersister.java:1234)"));
        }

        @Test
        @DisplayName("exception header lines")
        void exceptionHeaders() {
            assertFalse(folding.shouldFoldLine(project, "java.lang.NullPointerException: foo is null"));
            assertFalse(folding.shouldFoldLine(project, "Caused by: java.sql.SQLException: connection refused"));
        }

        @Test
        @DisplayName("regular log lines")
        void regularLogs() {
            assertFalse(folding.shouldFoldLine(project, "INFO: Server startup in [456] milliseconds"));
            assertFalse(folding.shouldFoldLine(project,
                    "WARN: Connection pool exhausted, retrying"));
        }

        @Test
        @DisplayName("blank / whitespace lines")
        void blankLines() {
            assertFalse(folding.shouldFoldLine(project, ""));
            assertFalse(folding.shouldFoldLine(project, "   "));
            assertFalse(folding.shouldFoldLine(project, "\t"));
        }

        @Test
        @DisplayName("lines mentioning the prefix but without 'at ' (not a stack frame)")
        void prefixLooseMatches() {
            // A user log message that happens to start with org.apache.catalina.* — not a frame.
            assertFalse(folding.shouldFoldLine(project,
                    "DEBUG: org.apache.catalina.startup.Catalina is being initialized"));
        }
    }

    @Test
    @DisplayName("placeholder text includes line count")
    void placeholderText() {
        String placeholder = folding.getPlaceholderText(project, List.of("a", "b", "c", "d", "e"));
        assertNotNull(placeholder);
        assertTrue(placeholder.contains("5"), "Expected line count in placeholder, got: " + placeholder);
        assertTrue(placeholder.toLowerCase().contains("tomcat") || placeholder.toLowerCase().contains("servlet"),
                "Expected Tomcat/servlet label in placeholder, got: " + placeholder);
    }
}
