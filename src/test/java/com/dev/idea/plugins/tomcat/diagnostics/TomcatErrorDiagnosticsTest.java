package com.dev.idea.plugins.tomcat.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatErrorDiagnostics")
class TomcatErrorDiagnosticsTest {

    @Test
    @DisplayName("empty and normal lines return no diagnostics")
    void noMatchOnNormalLines() {
        assertTrue(TomcatErrorDiagnostics.analyze("").isEmpty());
        assertTrue(TomcatErrorDiagnostics.analyze("INFO: Server startup in 1234 ms").isEmpty());
        assertTrue(TomcatErrorDiagnostics.analyze("Deploying web application directory [ROOT]").isEmpty());
    }

    @Test
    @DisplayName("ClassNotFoundException detected with class name and package")
    void classNotFoundException() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.ClassNotFoundException: com.example.MyService");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.ERROR, d.getSeverity());
        assertEquals("Missing Class", d.getCategory());
        assertTrue(d.getMessage().contains("com.example.MyService"));
        assertTrue(d.getSuggestion().contains("WEB-INF/lib"));
        assertEquals("FIX_CLASSPATH", d.getQuickFixId());
    }

    @Test
    @DisplayName("NoClassDefFoundError detected")
    void noClassDefFoundError() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.NoClassDefFoundError: org/slf4j/LoggerFactory");
        assertFalse(results.isEmpty());
        assertEquals("Missing Class", results.get(0).getCategory());
    }

    @Test
    @DisplayName("NoSuchMethodError detected with version conflict suggestion")
    void noSuchMethodError() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.NoSuchMethodError: 'void com.google.common.base.Preconditions.checkState(boolean)'");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals("Version Conflict", d.getCategory());
        assertTrue(d.getSuggestion().contains("dependency:tree"));
    }

    @Test
    @DisplayName("BindException with port number")
    void bindExceptionWithPort() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.net.BindException: Address already in use port 8080");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.CRITICAL, d.getSeverity());
        assertEquals("Port Conflict", d.getCategory());
        assertTrue(d.getSuggestion().contains("8080"));
        assertEquals("FIX_PORT", d.getQuickFixId());
    }

    @Test
    @DisplayName("BindException without port number")
    void bindExceptionWithoutPort() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.net.BindException: Address already in use");
        assertFalse(results.isEmpty());
        assertEquals("Port Conflict", results.get(0).getCategory());
    }

    @Test
    @DisplayName("OutOfMemoryError heap")
    void oomHeap() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.OutOfMemoryError: Java heap space");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.CRITICAL, d.getSeverity());
        assertTrue(d.getSuggestion().contains("-Xmx"));
        assertEquals("FIX_MEMORY", d.getQuickFixId());
    }

    @Test
    @DisplayName("OutOfMemoryError Metaspace")
    void oomMetaspace() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.OutOfMemoryError: Metaspace");
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getSuggestion().contains("MaxMetaspaceSize"));
    }

    @Test
    @DisplayName("OutOfMemoryError GC overhead")
    void oomGcOverhead() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.OutOfMemoryError: GC overhead limit exceeded");
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getSuggestion().contains("GC overhead"));
    }

    @Test
    @DisplayName("Permission denied")
    void permissionDenied() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.nio.file.AccessDeniedException: /opt/tomcat/conf/server.xml");
        assertFalse(results.isEmpty());
        assertEquals("Permission Denied", results.get(0).getCategory());
    }

    @Test
    @DisplayName("Connection refused")
    void connectionRefused() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.net.ConnectException: Connection refused localhost:8080");
        assertFalse(results.isEmpty());
        assertEquals("Connection Refused", results.get(0).getCategory());
    }

    @Test
    @DisplayName("LifecycleException with context name")
    void lifecycleException() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "LifecycleException: Failed to start component [StandardEngine[Catalina].StandardHost[localhost].StandardContext[/myapp]]");
        assertFalse(results.isEmpty());
        assertEquals("Deployment Failure", results.get(0).getCategory());
        assertTrue(results.get(0).getMessage().contains("/myapp"));
    }

    @Test
    @DisplayName("UnsupportedClassVersionError with Java version calculation")
    void unsupportedClassVersion() {
        // Class version 61 = Java 17
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.UnsupportedClassVersionError: com/example/App has been compiled by a more recent version of the Java Runtime (class file version 61.0)");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.CRITICAL, d.getSeverity());
        assertTrue(d.getMessage().contains("Java 17"));
        assertEquals("FIX_JRE", d.getQuickFixId());
    }

    @Test
    @DisplayName("Duplicate context path")
    void duplicateContext() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "The context path [/myapp] is already in use");
        assertFalse(results.isEmpty());
        assertEquals("Duplicate Context", results.get(0).getCategory());
    }

    @Test
    @DisplayName("SSL exception")
    void sslException() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "javax.net.ssl.SSLHandshakeException: PKIX path building failed");
        assertFalse(results.isEmpty());
        assertEquals("SSL Error", results.get(0).getCategory());
    }

    @Test
    @DisplayName("JDBC exception")
    void jdbcException() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.sql.SQLException: Cannot create JDBC driver for connect URL");
        assertFalse(results.isEmpty());
        assertEquals("Database Error", results.get(0).getCategory());
    }

    @Test
    @DisplayName("TLD scan warning")
    void tldScanWarning() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "At least one JAR was scanned for TLDs yet contained no TLDs");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.INFO, d.getSeverity());
        assertEquals("Performance", d.getCategory());
    }

    @Test
    @DisplayName("Listener start failed")
    void listenerStartFailed() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "SEVERE: One or more listeners failed to start");
        assertFalse(results.isEmpty());
        assertEquals("Initialization Error", results.get(0).getCategory());
    }

    @Test
    @DisplayName("Filter start failed")
    void filterStartFailed() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "SEVERE: One or more filters failed to start");
        assertFalse(results.isEmpty());
        assertEquals("Initialization Error", results.get(0).getCategory());
    }

    @Test
    @DisplayName("Classloader leak warning")
    void classloaderLeak() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "WARNING: The web application [myapp] appears to have started a thread named [Timer-0]");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.WARNING, d.getSeverity());
        assertEquals("Memory Leak", d.getCategory());
    }

    @Test
    @DisplayName("formatForConsole includes severity and suggestion")
    void formatForConsole() {
        TomcatErrorDiagnostics.Diagnostic d = TomcatErrorDiagnostics.analyze(
                "java.lang.ClassNotFoundException: com.example.Foo").get(0);
        String formatted = TomcatErrorDiagnostics.formatForConsole(d);
        assertTrue(formatted.startsWith("[ERROR]"));
        assertTrue(formatted.contains("Missing Class"));
        assertTrue(formatted.contains("WEB-INF/lib"));
    }

    @Test
    @DisplayName("Deploy error detected")
    void deployError() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "Error deploying web application [/opt/tomcat/webapps/myapp.war]");
        assertFalse(results.isEmpty());
        assertEquals("Deployment Error", results.get(0).getCategory());
    }

    @Test
    @DisplayName("duplicate web fragments are diagnosed as packaging conflicts")
    void duplicateWebFragments() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "java.lang.IllegalArgumentException: More than one fragment with the name [org_apache_jasper] was found. "
                        + "This is not legal with relative ordering. Duplicate fragments found in "
                        + "[[file:/tmp/a/tomcat-jasper.jar, file:/tmp/b/tomcat-embed-jasper.jar]].");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals("Packaging Conflict", d.getCategory());
        assertTrue(d.getSuggestion().contains("WEB-INF/lib"));
        assertTrue(d.getSuggestion().contains("tomcat-jasper"));
    }

    @Test
    @DisplayName("missing required system property is diagnosed")
    void missingRequiredSystemProperty() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "External configuration file was not found in \"null\", check \"wcc.config.dir\" system property");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals(TomcatErrorDiagnostics.Severity.CRITICAL, d.getSeverity());
        assertEquals("Missing Runtime Property", d.getCategory());
        assertTrue(d.getSuggestion().contains("-Dwcc.config.dir=<path>"));
    }

    @Test
    @DisplayName("locked persistence directory is diagnosed with cleanup guidance")
    void lockedPersistenceDirectory() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "Persistence directory already locked by this process: C:\\tmp\\wcc-local");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals("Locked Persistence Directory", d.getCategory());
        assertTrue(d.getMessage().contains("C:\\tmp\\wcc-local"));
        assertTrue(d.getSuggestion().contains("unique persistence path"));
    }

    @Test
    @DisplayName("startup failed due to previous errors is explained as secondary symptom")
    void failedDueToPreviousErrors() {
        List<TomcatErrorDiagnostics.Diagnostic> results = TomcatErrorDiagnostics.analyze(
                "10-Mar-2026 08:16:54.579 SEVERE [main] org.apache.catalina.core.StandardContext.startInternal Context [/wipo-connect-local-backend] startup failed due to previous errors");
        assertFalse(results.isEmpty());
        TomcatErrorDiagnostics.Diagnostic d = results.get(0);
        assertEquals("Secondary Startup Failure", d.getCategory());
        assertTrue(d.getSuggestion().contains("first Caused by:"));
    }
}
