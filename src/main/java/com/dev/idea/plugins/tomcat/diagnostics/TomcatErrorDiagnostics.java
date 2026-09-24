package com.dev.idea.plugins.tomcat.diagnostics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart diagnostics engine for Tomcat runtime errors.
 *
 * <p>Parses common error patterns out of the Tomcat console stream and emits
 * actionable fix suggestions with severity levels — so the user sees what
 * went wrong and what to do about it, not just the raw stack trace.
 */
public final class TomcatErrorDiagnostics {

    private TomcatErrorDiagnostics() {}

    public enum Severity { INFO, WARNING, ERROR, CRITICAL }

    public static final class Diagnostic {
        private final Severity severity;
        private final String category;
        private final String message;
        private final String suggestion;
        @Nullable private final String quickFixId;

        Diagnostic(@NotNull Severity severity, @NotNull String category,
                   @NotNull String message, @NotNull String suggestion,
                   @Nullable String quickFixId) {
            this.severity = severity;
            this.category = category;
            this.message = message;
            this.suggestion = suggestion;
            this.quickFixId = quickFixId;
        }

        public @NotNull Severity getSeverity() { return severity; }
        public @NotNull String getCategory() { return category; }
        public @NotNull String getMessage() { return message; }
        public @NotNull String getSuggestion() { return suggestion; }
        @Nullable public String getQuickFixId() { return quickFixId; }

        /** Dedup key: category plus message. */
        public @NotNull String identityKey() { return category + "|" + message; }

        @Override
        public String toString() {
            return "[" + severity + "] " + category + ": " + message + " → " + suggestion;
        }
    }

    // --- Pattern groups ---

    private static final Pattern CLASS_NOT_FOUND = Pattern.compile(
            "(?:ClassNotFoundException|NoClassDefFoundError):\\s*([\\w.$]+)");
    private static final Pattern NO_SUCH_METHOD = Pattern.compile(
            "NoSuchMethodError:\\s*'?([^'\"\\n]+)'?");
    private static final Pattern BIND_EXCEPTION = Pattern.compile(
            "(?:BindException|Address already in use)(?:.*?port\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OOM = Pattern.compile(
            "OutOfMemoryError(?::\\s*(.+))?");
    private static final Pattern PERM_DENIED = Pattern.compile(
            "(?:AccessDeniedException|Permission denied|java\\.security\\.AccessControlException)(?::\\s*(.+))?");
    private static final Pattern CONNECT_REFUSED = Pattern.compile(
            "(?:ConnectException|Connection refused)(?:.*?(?:port|localhost:|127\\.0\\.0\\.1:)(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIFECYCLE_EXCEPTION = Pattern.compile(
            "LifecycleException:.*Failed to start component \\[.*StandardContext\\[([^\\]]+)\\]\\]");
    private static final Pattern DEPLOY_ERROR = Pattern.compile(
            "Error deploying (?:deployment descriptor|web application)\\s*\\[.*?([^/\\\\]+)\\.(?:xml|war)\\]");
    private static final Pattern UNSUPPORTED_CLASS_VERSION = Pattern.compile(
            "UnsupportedClassVersionError:.*class file version (\\d+)\\.\\d+");
    private static final Pattern DUPLICATE_CONTEXT = Pattern.compile(
            "(?:The context path|Context)\\s*\\[?(/[^\\]\\s]*)\\]?\\s*(?:is already in use|already exists)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSL_EXCEPTION = Pattern.compile(
            "(?:SSLException|SSLHandshakeException|CertificateException)(?::\\s*(.+))?");
    private static final Pattern JDBC_EXCEPTION = Pattern.compile(
            "(?:SQLException|Cannot create JDBC driver|No suitable driver)(?::\\s*(.+))?");
    private static final Pattern TLD_SCAN_WARNING = Pattern.compile(
            "At least one JAR was scanned for TLDs yet contained no TLDs");
    private static final Pattern LISTENER_START_FAILED = Pattern.compile(
            "One or more listeners failed to start");
    private static final Pattern FILTER_START_FAILED = Pattern.compile(
            "One or more filters failed to start");
    // WebappClassLoaderBase's stop-time leak reports; the harmless ThreadLocal lines do not match.
    private static final Pattern LEAKED_THREAD = Pattern.compile(
            "The web application \\[([^\\]]++)\\] appears to have started a (?:Timer)?[Tt]hread named \\[[^\\]]*+\\][^\\n]*?but has failed to stop it");
    private static final Pattern LEAKED_THREAD_LOCAL = Pattern.compile(
            "The web application \\[([^\\]]++)\\] created a ThreadLocal[^\\n]*?but failed to remove it");
    private static final Pattern LEAKED_JDBC_DRIVER = Pattern.compile(
            "The web application \\[([^\\]]++)\\] registered the JDBC driver \\[([^\\]]++)\\] but failed to unregister it");
    private static final Pattern REQUEST_STILL_RUNNING = Pattern.compile(
            "of web application \\[([^\\]]++)\\] is still processing a request that has yet to finish");
    private static final Pattern DUPLICATE_WEB_FRAGMENT = Pattern.compile(
            "More than one fragment with the name \\[([^\\]]+)] was found.*Duplicate fragments found in \\[(.+)]");
    private static final Pattern MISSING_REQUIRED_SYSTEM_PROPERTY = Pattern.compile(
            "External configuration file was not found in \"null\", check \"([^\"]+)\" system property");
    private static final Pattern PERSISTENCE_DIRECTORY_LOCKED = Pattern.compile(
            "Persistence directory already locked by this process:\\s*(.+)");
    private static final Pattern FAILED_DUE_TO_PREVIOUS_ERRORS = Pattern.compile(
            "Context \\[([^\\]]+)] startup failed due to previous errors");

    // Patterns kept deliberately
    // library-agnostic: they describe Tomcat/JVM/network-level conditions, not
    // application-framework errors.
    private static final Pattern ECJ_UNRESOLVED_COMPILATION = Pattern.compile(
            "java\\.lang\\.Error:\\s*Unresolved compilation problems?");
    private static final Pattern BACKEND_UNREACHABLE = Pattern.compile(
            "(?:Connection refused|Connection timed out)[^\\n]*localhost[:/]+(\\d{2,5})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE_ACCESS_ERROR = Pattern.compile(
            "InaccessibleObjectException|module [^\\s]+ does not (?:export|open)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SFTP_CONNECTION_FAILURE = Pattern.compile(
            "JSchException[^\\n]*(?:Connection refused|Connection timed out|Auth fail|UnknownHostKey)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Analyzes a Tomcat log line and returns diagnostics if a known error pattern is detected.
     * Can return multiple diagnostics from a single line (e.g., root cause + context).
     *
     * @param text the log line to analyze
     * @return list of diagnostics (empty if no known pattern matched)
     */
    @NotNull
    public static List<Diagnostic> analyze(@NotNull String text) {
        // Fast-reject: bail before any regex if no failure keyword is in the line.
        if (!mightContainDiagnostic(text)) return java.util.Collections.emptyList();
        List<Diagnostic> results = new ArrayList<>();

        // ClassNotFoundException / NoClassDefFoundError
        Matcher m = CLASS_NOT_FOUND.matcher(text);
        if (m.find()) {
            String className = m.group(1);
            String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            results.add(new Diagnostic(Severity.ERROR, "Missing Class",
                    "Class not found: " + className,
                    "Verify that the JAR containing '" + className + "' is included in your artifact's WEB-INF/lib. "
                            + "Check Maven/Gradle dependencies for package '" + pkg + "'. "
                            + "If this is a multi-module project, ensure the dependent module is in the artifact definition.",
                    "FIX_CLASSPATH"));
        }

        // NoSuchMethodError — version conflict
        m = NO_SUCH_METHOD.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Version Conflict",
                    "Method not found: " + m.group(1),
                    "This usually means a JAR version mismatch. Check WEB-INF/lib for duplicate JARs "
                            + "with different versions. Run 'mvn dependency:tree' or 'gradle dependencies' "
                            + "to find conflicting transitive dependencies.",
                    null));
        }

        // BindException — port in use
        m = BIND_EXCEPTION.matcher(text);
        if (m.find()) {
            String port = m.group(1);
            String portMsg = port != null ? " on port " + port : "";
            results.add(new Diagnostic(Severity.CRITICAL, "Port Conflict",
                    "Address already in use" + portMsg,
                    "Another process is using this port. "
                            + (port != null ? "Run 'lsof -i :" + port + "' (macOS/Linux) or 'netstat -aon | findstr :" + port + "' (Windows) to find it. " : "")
                            + "Stop the conflicting process or change the port in Server tab.",
                    "FIX_PORT"));
        }

        // OutOfMemoryError
        m = OOM.matcher(text);
        if (m.find()) {
            String detail = m.group(1);
            String specific;
            if (detail != null && detail.contains("Metaspace")) {
                specific = "Increase Metaspace: add '-XX:MaxMetaspaceSize=512m' to VM options.";
            } else if (detail != null && detail.contains("PermGen")) {
                specific = "Increase PermGen: add '-XX:MaxPermSize=256m' to VM options (Java 7 only).";
            } else if (detail != null && detail.contains("GC overhead")) {
                specific = "GC overhead limit exceeded. Increase heap with '-Xmx' or investigate memory leaks.";
            } else {
                specific = "Increase heap size: add '-Xmx1024m' (or higher) to VM options in Server tab.";
            }
            results.add(new Diagnostic(Severity.CRITICAL, "Out of Memory",
                    "JVM ran out of memory" + (detail != null ? ": " + detail : ""),
                    specific,
                    "FIX_MEMORY"));
        }

        // Permission denied
        m = PERM_DENIED.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Permission Denied",
                    "Access denied" + (m.group(1) != null ? ": " + m.group(1) : ""),
                    "Check file permissions on the Tomcat directory and catalina.base. "
                            + "On Unix, ensure the user has read/write/execute access. "
                            + "On Windows, run the IDE as administrator or fix folder permissions.",
                    null));
        }

        // Connection refused (remote deployment)
        m = CONNECT_REFUSED.matcher(text);
        if (m.find()) {
            String port = m.group(1);
            results.add(new Diagnostic(Severity.ERROR, "Connection Refused",
                    "Cannot connect" + (port != null ? " to port " + port : ""),
                    "The target server is not running or the port is blocked by a firewall. "
                            + "Verify the server is started and the port is correct.",
                    null));
        }

        // LifecycleException — failed to start context
        m = LIFECYCLE_EXCEPTION.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Deployment Failure",
                    "Failed to start context [" + m.group(1) + "]",
                    "The web application failed to initialize. Check the root cause in the stack trace below "
                            + "(usually a missing class, configuration error, or database connection failure).",
                    null));
        }

        // Deployment descriptor error
        m = DEPLOY_ERROR.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Deployment Error",
                    "Error deploying " + m.group(1),
                    "Check the deployment descriptor XML for syntax errors. "
                            + "Verify the artifact path exists and is accessible.",
                    null));
        }

        // UnsupportedClassVersionError — Java version mismatch
        m = UNSUPPORTED_CLASS_VERSION.matcher(text);
        if (m.find()) {
            int classVersion = Integer.parseInt(m.group(1));
            // class file version 45 = Java 1.1, 52 = Java 8, 55 = Java 11, 61 = Java 17.
            // Skip the diagnostic for nonsensically small values from malformed output.
            if (classVersion >= 45) {
                int javaVersion = classVersion - 44;
                results.add(new Diagnostic(Severity.CRITICAL, "Java Version Mismatch",
                        "Class compiled with Java " + javaVersion + " but running on an older JVM",
                        "Your application requires Java " + javaVersion + "+. "
                                + "Change the JRE in Server tab → JRE Configuration, or recompile with a lower target.",
                        "FIX_JRE"));
            }
        }

        // Duplicate context path
        m = DUPLICATE_CONTEXT.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Duplicate Context",
                    "Context path " + m.group(1) + " already exists",
                    "Another application is already deployed at this context path. "
                            + "Change the context path in Deployment tab or undeploy the conflicting app.",
                    null));
        }

        // SSL errors
        m = SSL_EXCEPTION.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "SSL Error",
                    "SSL/TLS failure" + (m.group(1) != null ? ": " + m.group(1) : ""),
                    "Check your HTTPS connector configuration in server.xml. "
                            + "Verify the keystore path, password, and certificate validity.",
                    null));
        }

        // JDBC errors
        m = JDBC_EXCEPTION.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Database Error",
                    "JDBC connection failure" + (m.group(1) != null ? ": " + m.group(1) : ""),
                    "Verify database is running and accessible. Check JDBC URL, credentials, "
                            + "and ensure the JDBC driver JAR is in WEB-INF/lib.",
                    null));
        }

        // TLD scan warning — performance optimization hint
        if (TLD_SCAN_WARNING.matcher(text).find()) {
            results.add(new Diagnostic(Severity.INFO, "Performance",
                    "TLD scanning found JARs with no TLDs",
                    "Add a jarsToSkip list in catalina.properties to speed up startup. "
                            + "See Tomcat docs for 'tomcat.util.scan.StandardJarScanFilter.jarsToSkip'.",
                    null));
        }

        // Listener start failed
        if (LISTENER_START_FAILED.matcher(text).find()) {
            results.add(new Diagnostic(Severity.ERROR, "Initialization Error",
                    "One or more listeners failed to start",
                    "A ServletContextListener threw an exception during initialization. "
                            + "Check the stack trace for the failing listener class and its root cause.",
                    null));
        }

        // Filter start failed
        if (FILTER_START_FAILED.matcher(text).find()) {
            results.add(new Diagnostic(Severity.ERROR, "Initialization Error",
                    "One or more filters failed to start",
                    "A servlet Filter threw an exception during init(). "
                            + "Check the stack trace for the failing filter class.",
                    null));
        }

        // Duplicate web fragments from conflicting JARs in the web artifact
        m = DUPLICATE_WEB_FRAGMENT.matcher(text);
        if (m.find()) {
            String fragmentName = m.group(1);
            String jars = m.group(2);
            results.add(new Diagnostic(Severity.ERROR, "Packaging Conflict",
                    "Duplicate web fragment '" + fragmentName + "' found in multiple JARs",
                    "Your deployed web artifact contains conflicting libraries. Remove one of the duplicate JARs from WEB-INF/lib. "
                            + "Check your dependency tree and artifact packaging rules. Conflicting entries: " + jars,
                    null));
        }

        // Missing required external config system property
        m = MISSING_REQUIRED_SYSTEM_PROPERTY.matcher(text);
        if (m.find()) {
            String property = m.group(1);
            results.add(new Diagnostic(Severity.CRITICAL, "Missing Runtime Property",
                    "Required system property is not set: " + property,
                    "Add '-D" + property + "=<path>' in Server tab VM options, or provide the property through your startup environment. "
                            + "The application cannot load its external configuration until this property is set.",
                    null));
        }

        // Disk-backed cache/store lock under temp or custom persistence directory
        m = PERSISTENCE_DIRECTORY_LOCKED.matcher(text);
        if (m.find()) {
            String path = m.group(1).trim();
            results.add(new Diagnostic(Severity.ERROR, "Locked Persistence Directory",
                    "Disk-backed cache directory is already locked: " + path,
                    "A previous run or a parallel instance is still holding this cache directory. Stop other Tomcat/Java processes using it, "
                            + "then delete the stale directory if needed. For a durable fix, configure a unique persistence path per run configuration.",
                    null));
        }

        // Secondary failure line that often hides the real root cause above it
        m = FAILED_DUE_TO_PREVIOUS_ERRORS.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.ERROR, "Secondary Startup Failure",
                    "Context " + m.group(1) + " failed due to previous errors",
                    "This line is usually a follow-up symptom, not the root cause. Scroll earlier in the log for the first Caused by:, "
                            + "deployment error, or application exception that occurred before this message.",
                    null));
        }

        // Message names the application, so the pipeline's dedup gives one advisory per app and kind.
        m = LEAKED_THREAD.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.WARNING, "Memory Leak",
                    "Web application [" + m.group(1) + "] left threads running after it stopped",
                    "Each reload in the same JVM keeps the stopped classloader alive; a full Stop is unaffected. "
                            + "Shut down executors, timers and HTTP clients in ServletContextListener.contextDestroyed. "
                            + "The application frame in each thread's stack names the code that started it.",
                    null));
        }

        m = LEAKED_THREAD_LOCAL.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.WARNING, "Memory Leak",
                    "Web application [" + m.group(1) + "] left ThreadLocals on Tomcat's worker threads",
                    "Tomcat renews its worker threads to contain this. "
                            + "To fix it, remove the ThreadLocal in a finally block when the request ends.",
                    null));
        }

        m = LEAKED_JDBC_DRIVER.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.WARNING, "Memory Leak",
                    "Web application [" + m.group(1) + "] left JDBC driver " + m.group(2) + " registered",
                    "Tomcat deregistered it. Move the driver JAR from WEB-INF/lib to Tomcat's lib directory, "
                            + "or call DriverManager.deregisterDriver in ServletContextListener.contextDestroyed.",
                    null));
        }

        m = REQUEST_STILL_RUNNING.matcher(text);
        if (m.find()) {
            results.add(new Diagnostic(Severity.WARNING, "Memory Leak",
                    "Web application [" + m.group(1) + "] stopped with a request still running",
                    "The request's thread keeps the stopped classloader alive until it finishes. "
                            + "Tomcat waits the context's unloadDelay (default 2000 ms) before giving up.",
                    null));
        }

        // 1.1.0 additions
        // ECJ broken-class stub at class init
        if (ECJ_UNRESOLVED_COMPILATION.matcher(text).find()) {
            results.add(new Diagnostic(Severity.CRITICAL, "ECJ Compilation Stub",
                    "A class file was compiled with Eclipse JDT despite unresolved errors",
                    "Settings → Build, Execution, Deployment → Compiler → Java Compiler: switch from "
                            + "Eclipse to Javac, then Build → Rebuild Project. If you must keep Eclipse, "
                            + "fix all unresolved imports — ECJ proceeds with errors and writes stubs that throw at runtime.",
                    "FIX_CLASSPATH"));
        }

        // Backend unreachable on localhost — generic connectivity diagnostic for
        // multi-service localhost setups. Stays library-agnostic: no advice mentions
        // any specific framework, file, or property name.
        m = BACKEND_UNREACHABLE.matcher(text);
        if (m.find()) {
            String port = m.group(1);
            results.add(new Diagnostic(Severity.ERROR, "Backend Unreachable",
                    "Cannot connect to localhost service" + (port != null ? " on port " + port : ""),
                    "Something on this machine expected a service at localhost"
                            + (port != null ? ":" + port : "") + " but couldn't reach it. "
                            + "Verify the peer service is running and bound to that port. If you run several "
                            + "Tomcat configs together, check the run console's port-resolution lines — a "
                            + "bumped port can leave hardcoded peer URLs pointing at the wrong slot.",
                    null));
        }

        // JDK 9+ module-access denial
        if (MODULE_ACCESS_ERROR.matcher(text).find()) {
            results.add(new Diagnostic(Severity.ERROR, "Module Access Denied",
                    "JVM rejected reflective access to internal JDK classes",
                    "Add --add-opens flags to Server tab → VM options. Common ones: "
                            + "--add-opens=java.base/java.lang=ALL-UNNAMED "
                            + "--add-opens=java.base/java.util=ALL-UNNAMED "
                            + "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED. "
                            + "The stack trace above names the specific module/package that needs opening.",
                    null));
        }

        // JSch / SFTP connectivity
        if (SFTP_CONNECTION_FAILURE.matcher(text).find()) {
            results.add(new Diagnostic(Severity.WARNING, "SFTP Connection Failed",
                    "JSch couldn't reach the configured SFTP host",
                    "Verify the host is reachable from this machine (corporate VPN often required). "
                            + "Check credentials / known_hosts. Often non-critical at startup — the app may "
                            + "retry on first request — but flagged here to make the cause findable later.",
                    null));
        }

        return results;
    }

    /**
     * Formats a diagnostic for console display with the [SEVERITY] prefix.
     * Shows the detected message followed by the actionable suggestion so the
     * user sees both <em>what</em> was detected and <em>how</em> to fix it.
     */
    @NotNull
    public static String formatForConsole(@NotNull Diagnostic diagnostic) {
        return "[" + diagnostic.getSeverity() + "] " + diagnostic.getCategory()
                + ": " + diagnostic.getMessage()
                + " — " + diagnostic.getSuggestion();
    }

    // Substrings that must be present for ANY pattern in this class to match.
    // Lets analyze() skip the regex sweep for 90%+ of normal log lines.
    private static final String[] DIAGNOSTIC_KEYWORDS = {
            "Exception", "Error", "SEVERE", "FATAL", "WARNING",
            "failed", "Failed", "denied", "refused",
            "Cannot", "Could not", "no suitable",
            "InaccessibleObject", "module ", "TLDs",
            "fragment", "locked", "Unresolved",
            "JSch", "memory leak", "ThreadLocal",
            "Address already in use", "already in use",
            "already exists", "appears to have started",
            "External configuration file"
    };

    private static boolean mightContainDiagnostic(@NotNull String text) {
        for (String kw : DIAGNOSTIC_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
