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
 * Unit tests for {@link TomcatStartupBannerFolding}.
 *
 * <p>{@code shouldFoldLine} takes a non-null {@code Project} per the
 * {@link com.intellij.execution.ConsoleFolding} contract, but our implementation
 * never dereferences it. The IntelliJ Platform Gradle plugin's bytecode
 * instrumentation enforces the {@code @NotNull} contract at runtime, so the
 * tests use a Mockito mock to satisfy the guard rather than passing
 * {@code null} — same pattern the rest of this codebase uses for stubbing
 * platform types.
 */
class TomcatStartupBannerFoldingTest {

    private final TomcatStartupBannerFolding folding = new TomcatStartupBannerFolding();
    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("folds Tomcat system-info banner lines")
    class FoldedLines {

        @Test
        @DisplayName("Server version name")
        void serverVersionName() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO [main] org.apache.catalina.startup.VersionLoggerListener.log Server version name:   Apache Tomcat/10.1.18"));
        }

        @Test
        @DisplayName("Server version number")
        void serverVersionNumber() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Server version number: 10.1.18.0"));
        }

        @Test
        @DisplayName("Server built")
        void serverBuilt() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Server version built: Dec 12 2023 21:33:12 UTC"));
        }

        @Test
        @DisplayName("OS Name / Version / Architecture")
        void osInfo() {
            assertTrue(folding.shouldFoldLine(project, "INFO: OS Name:               Mac OS X"));
            assertTrue(folding.shouldFoldLine(project, "INFO: OS Version:            14.0"));
            assertTrue(folding.shouldFoldLine(project, "INFO: Architecture:          aarch64"));
        }

        @Test
        @DisplayName("Java Home / JVM Version / JVM Vendor")
        void jvmInfo() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Java Home:             /Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"));
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: JVM Version:           17.0.9+11"));
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: JVM Vendor:            Oracle Corporation"));
        }

        @Test
        @DisplayName("CATALINA_BASE / CATALINA_HOME / CATALINA_TMPDIR")
        void catalinaPaths() {
            assertTrue(folding.shouldFoldLine(project, "INFO: CATALINA_BASE:         /home/user/.devtomcat/sandbox"));
            assertTrue(folding.shouldFoldLine(project, "INFO: CATALINA_HOME:         /opt/tomcat-10.1.18"));
            assertTrue(folding.shouldFoldLine(project, "INFO: CATALINA_TMPDIR:       /home/user/.devtomcat/sandbox/temp"));
        }

        @Test
        @DisplayName("APR / Native library / OpenSSL detection")
        void nativeLibInfo() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Loaded Apache Tomcat Native library [1.2.39] using APR version [1.7.4]"));
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: APR capabilities: IPv6 [true], sendfile [true], accept filters [false], random [true], UDS [true]"));
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: OpenSSL successfully initialized [OpenSSL 3.0.11 19 Sep 2023]"));
        }

        @Test
        @DisplayName("Command line argument: -")
        void commandLineArgs() {
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Command line argument: -Djava.util.logging.config.file=/path/conf/logging.properties"));
            assertTrue(folding.shouldFoldLine(project,
                    "INFO: Command line argument: -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager"));
        }
    }

    @Nested
    @DisplayName("does NOT fold user-relevant lines")
    class UnfoldedLines {

        @Test
        @DisplayName("Server startup line (canonical 'Tomcat is up' signal)")
        void serverStartupLineStaysVisible() {
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Server startup in [456] milliseconds"));
        }

        @Test
        @DisplayName("Deployment progress lines")
        void deploymentLinesStayVisible() {
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Deploying web application archive [/path/myapp.war]"));
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Deployment of web application archive [/path/myapp.war] has finished in [1234] ms"));
        }

        @Test
        @DisplayName("Starting Servlet engine / service lines")
        void startingLinesStayVisible() {
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Starting service [Catalina]"));
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Starting Servlet engine: [Apache Tomcat/10.1.18]"));
        }

        @Test
        @DisplayName("application log lines that mention 'Java Home' without colon-suffix")
        void userLogsAreNotMistakenForBanner() {
            assertFalse(folding.shouldFoldLine(project,
                    "DEBUG: looking up Java Home value from environment"));
            assertFalse(folding.shouldFoldLine(project,
                    "INFO: Setting CATALINA_BASE will be required for this operation"));
        }

        @Test
        @DisplayName("stack-trace lines (handled by TomcatStackFrameFolding)")
        void stackFramesAreNotBannerLines() {
            assertFalse(folding.shouldFoldLine(project,
                    "\tat org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:202)"));
        }

        @Test
        @DisplayName("empty / whitespace lines")
        void blankLines() {
            assertFalse(folding.shouldFoldLine(project, ""));
            assertFalse(folding.shouldFoldLine(project, "   "));
        }
    }

    @Test
    @DisplayName("placeholder text includes line count")
    void placeholderText() {
        String placeholder = folding.getPlaceholderText(project, List.of("a", "b", "c"));
        assertNotNull(placeholder);
        assertTrue(placeholder.contains("3"), "Expected line count in placeholder, got: " + placeholder);
        assertTrue(placeholder.toLowerCase().contains("tomcat"),
                "Expected 'Tomcat' label in placeholder, got: " + placeholder);
    }
}
