package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Url;
import com.intellij.util.Urls;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev Tomcat Server Console View
 *
 * Enhanced console view that provides intelligent log parsing, deployment
 * tracking, and server status monitoring. Features include:
 * - Automatic detection of server ports and connectors
 * - Deployment progress tracking with timing information
 * - Error highlighting and categorization
 * - Clickable URLs for easy access to deployed applications
 *
 * @author Dev Tomcat Team
 * @see ConsoleViewImpl
 */
public class ServerConsoleView extends ConsoleViewImpl {

    private static final Logger LOG = Logger.getInstance(ServerConsoleView.class);

    // Registry keys
    private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";

    // Time formatting
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Regex patterns for log parsing
    private static final Pattern HTTP_CONNECTOR_PATTERN =
            Pattern.compile("http-nio-(\\d+)");
    private static final Pattern HTTPS_CONNECTOR_PATTERN =
            Pattern.compile("https-jsse-nio-(\\d+)");
    private static final Pattern AJP_CONNECTOR_PATTERN =
            Pattern.compile("ajp-nio-(\\d+)");
    private static final Pattern DEPLOYMENT_START_PATTERN =
            Pattern.compile("Deploying web application.*?(?:directory|archive).*?\\[(.+?)\\]");
    private static final Pattern DEPLOYMENT_FINISH_PATTERN =
            Pattern.compile("Deployment of web application.*?\\[(.+?)\\].*?finished");
    private static final Pattern SERVER_STARTUP_PATTERN =
            Pattern.compile("Server startup in (\\d+) ms");
    private static final Pattern ERROR_PATTERN =
            Pattern.compile("(ERROR|SEVERE|FATAL)\\s*[:|-](.*)");
    private static final Pattern WARNING_PATTERN =
            Pattern.compile("(WARN|WARNING)\\s*[:|-](.*)");

    // Console output categories
    private enum OutputCategory {
        SERVER_LIFECYCLE("SERVER", ConsoleViewContentType.SYSTEM_OUTPUT),
        DEPLOYMENT("DEPLOY", ConsoleViewContentType.SYSTEM_OUTPUT),
        ERROR("ERROR", ConsoleViewContentType.ERROR_OUTPUT),
        WARNING("WARN", ConsoleViewContentType.LOG_WARNING_OUTPUT),
        INFO("INFO", ConsoleViewContentType.NORMAL_OUTPUT),
        DEBUG("DEBUG", ConsoleViewContentType.LOG_DEBUG_OUTPUT);

        private final String prefix;
        private final ConsoleViewContentType contentType;

        OutputCategory(String prefix, ConsoleViewContentType contentType) {
            this.prefix = prefix;
            this.contentType = contentType;
        }
    }

    // State tracking
    private final TomcatRunConfiguration configuration;
    private final Set<String> httpPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> httpsPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> ajpPorts = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private volatile boolean serverReady = false;
    private final long startTime = System.currentTimeMillis();
    private boolean showTimestamps;

    /**
     * Creates a new server console view
     *
     * @param configuration The Tomcat run configuration
     */
    public ServerConsoleView(TomcatRunConfiguration configuration) {
        super(configuration.getProject(), true);
        this.configuration = configuration;
        this.showTimestamps = Registry.is(REG_SHOW_TIMESTAMPS);

        printServerHeader();
    }

    @Override
    public void print(@NotNull String text, @NotNull ConsoleViewContentType contentType) {
        // Skip empty lines
        if (text.trim().isEmpty()) {
            super.print(text, contentType);
            return;
        }

        // Parse and categorize the output
        OutputCategory category = categorizeOutput(text);
        String enhancedText = enhanceOutput(text, category);

        // Print with appropriate formatting
        super.print(enhancedText, category.contentType);

        // Process for state tracking
        processOutputForState(text);
    }

    /**
     * Print professional server startup header
     */
    private void printServerHeader() {
        String header = String.format(
                "\n╔════════════════════════════════════════════════════════════════╗\n" +
                        "║                    Dev Tomcat Server Console                    ║\n" +
                        "╠════════════════════════════════════════════════════════════════╣\n" +
                        "║ Project: %-53s ║\n" +
                        "║ Configuration: %-47s ║\n" +
                        "║ Started: %-53s ║\n" +
                        "╚════════════════════════════════════════════════════════════════╝\n\n",
                truncate(configuration.getProject().getName(), 53),
                truncate(configuration.getName(), 47),
                LocalDateTime.now().format(TIMESTAMP_FORMAT)
        );
        super.print(header, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Categorize output based on content
     */
    private OutputCategory categorizeOutput(String text) {
        // Check for errors
        if (ERROR_PATTERN.matcher(text).find()) {
            return OutputCategory.ERROR;
        }

        // Check for warnings
        if (WARNING_PATTERN.matcher(text).find()) {
            return OutputCategory.WARNING;
        }

        // Check for deployment messages
        if (text.contains("Deploy") || text.contains("deployment")) {
            return OutputCategory.DEPLOYMENT;
        }

        // Check for server lifecycle
        if (text.contains("Server startup") || text.contains("Catalina.start") ||
                text.contains("Stopping") || text.contains("Shutdown")) {
            return OutputCategory.SERVER_LIFECYCLE;
        }

        // Check for debug level
        if (text.contains("DEBUG") || text.contains("FINE")) {
            return OutputCategory.DEBUG;
        }

        // Default to info
        return OutputCategory.INFO;
    }

    /**
     * Enhance output with timestamps and formatting
     */
    private String enhanceOutput(String text, OutputCategory category) {
        StringBuilder enhanced = new StringBuilder();

        // Add timestamp if enabled
        if (showTimestamps && !text.trim().isEmpty()) {
            enhanced.append("[")
                    .append(LocalDateTime.now().format(TIMESTAMP_FORMAT))
                    .append("] ");
        }

        // Add category prefix for important messages
        if (category != OutputCategory.INFO && category != OutputCategory.DEBUG) {
            enhanced.append("[").append(category.prefix).append("] ");
        }

        // Append the actual text
        enhanced.append(text);

        // Ensure newline at end if not present
        if (!text.endsWith("\n")) {
            enhanced.append("\n");
        }

        return enhanced.toString();
    }

    /**
     * Process output for state tracking and special handling
     */
    private void processOutputForState(String text) {
        // Skip processing for stack traces
        if (text.trim().startsWith("at ") || text.trim().startsWith("Caused by:")) {
            return;
        }

        // Parse connector information
        parseConnectorInfo(text);

        // Track deployment progress
        trackDeploymentProgress(text);

        // Check for server startup completion
        checkServerStartup(text);
    }

    /**
     * Parse and track connector information
     */
    private void parseConnectorInfo(String text) {
        // HTTP connector
        Matcher httpMatcher = HTTP_CONNECTOR_PATTERN.matcher(text);
        if (httpMatcher.find()) {
            String port = httpMatcher.group(1);
            if (httpPorts.add(port)) {
                printConnectorInfo("HTTP", port);
            }
        }

        // HTTPS connector
        Matcher httpsMatcher = HTTPS_CONNECTOR_PATTERN.matcher(text);
        if (httpsMatcher.find()) {
            String port = httpsMatcher.group(1);
            if (httpsPorts.add(port)) {
                printConnectorInfo("HTTPS", port);
            }
        }

        // AJP connector
        Matcher ajpMatcher = AJP_CONNECTOR_PATTERN.matcher(text);
        if (ajpMatcher.find()) {
            String port = ajpMatcher.group(1);
            if (ajpPorts.add(port)) {
                printConnectorInfo("AJP", port);
            }
        }
    }

    /**
     * Print connector information
     */
    private void printConnectorInfo(String type, String port) {
        String message = String.format("✓ %s connector initialized on port %s\n", type, port);
        super.print(formatWithTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Track deployment progress
     */
    private void trackDeploymentProgress(String text) {
        // Deployment start
        Matcher startMatcher = DEPLOYMENT_START_PATTERN.matcher(text);
        if (startMatcher.find()) {
            String context = startMatcher.group(1);
            deploymentStartTimes.put(context, System.currentTimeMillis());

            String message = String.format("→ Deploying application: %s\n",
                    getApplicationName(context));
            super.print(formatWithTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        // Deployment completion
        Matcher finishMatcher = DEPLOYMENT_FINISH_PATTERN.matcher(text);
        if (finishMatcher.find()) {
            String context = finishMatcher.group(1);
            Long startTime = deploymentStartTimes.remove(context);

            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                String message = String.format("✓ Deployed successfully: %s (took %d ms)\n",
                        getApplicationName(context), duration);
                super.print(formatWithTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);
            }
        }
    }

    /**
     * Check for server startup completion
     */
    private void checkServerStartup(String text) {
        Matcher startupMatcher = SERVER_STARTUP_PATTERN.matcher(text);
        if (startupMatcher.find() && !serverReady) {
            serverReady = true;
            String startupTime = startupMatcher.group(1);

            // Use configured port if no connectors detected
            if (httpPorts.isEmpty() && httpsPorts.isEmpty()) {
                httpPorts.add(String.valueOf(configuration.getPort()));
            }

            printServerReadyMessage(startupTime);
        }
    }

    /**
     * Print server ready message with URLs
     */
    private void printServerReadyMessage(String startupTime) {
        StringBuilder message = new StringBuilder();
        message.append("\n╔════════════════════════════════════════════════════════════════╗\n");
        message.append("║                    Server Started Successfully                  ║\n");
        message.append("╠════════════════════════════════════════════════════════════════╣\n");
        message.append(String.format("║ Startup time: %-48s ║\n", startupTime + " ms"));
        message.append(String.format("║ Hot deployment: %-45s ║\n",
                configuration.isHotDeploymentEnabled() ? "Enabled" : "Disabled"));

        if (configuration.isJmxEnabled()) {
            message.append(String.format("║ JMX monitoring: Port %-40s ║\n",
                    configuration.getJmxPort()));
        }

        message.append("╠════════════════════════════════════════════════════════════════╣\n");
        message.append("║ Application URLs:                                              ║\n");

        // Print application URLs
        List<Url> urls = buildApplicationUrls();
        for (Url url : urls) {
            String urlStr = url.toExternalForm();
            message.append(String.format("║ • %-59s ║\n", truncate(urlStr, 59)));
        }

        message.append("╚════════════════════════════════════════════════════════════════╝\n\n");

        super.print(message.toString(), ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Build list of application URLs
     */
    private List<Url> buildApplicationUrls() {
        List<Url> urls = new ArrayList<>();
        String contextPath = configuration.getContextPath();
        String path = StringUtil.trimStart(contextPath, "/");
        if (path.isEmpty()) {
            path = "/";
        } else {
            path = "/" + path;
        }

        // HTTP URLs
        for (String port : httpPorts) {
            try {
                boolean isDefault = "80".equals(port);
                String authority = "localhost" + (isDefault ? "" : ":" + port);
                urls.add(Urls.newHttpUrl(authority, path));
            } catch (Exception e) {
                LOG.debug("Failed to create HTTP URL for port: " + port, e);
            }
        }

        // HTTPS URLs
        for (String port : httpsPorts) {
            try {
                boolean isDefault = "443".equals(port);
                String authority = "localhost" + (isDefault ? "" : ":" + port);
                urls.add(Urls.newUrl("https", authority, path));
            } catch (Exception e) {
                LOG.debug("Failed to create HTTPS URL for port: " + port, e);
            }
        }

        return urls;
    }

    /**
     * Format text with timestamp if enabled
     */
    private String formatWithTimestamp(String text) {
        if (showTimestamps) {
            return "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + text;
        }
        return text;
    }

    /**
     * Get user-friendly application name from context path
     */
    private String getApplicationName(String context) {
        if (context.isEmpty() || "/".equals(context) || "ROOT".equals(context)) {
            return "ROOT (/)";
        }
        return context.startsWith("/") ? context : "/" + context;
    }

    /**
     * Truncate string to specified length
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 3) + "...";
    }

    /**
     * Print an error message
     */
    public void printError(String message) {
        print("[ERROR] " + message + "\n", ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Print a warning message
     */
    public void printWarning(String message) {
        print("[WARN] " + message + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT);
    }

    /**
     * Print an info message
     */
    public void printInfo(String message) {
        print("[INFO] " + message + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Check if server is ready
     */
    public boolean isServerReady() {
        return serverReady;
    }

    /**
     * Get detected HTTP ports
     */
    public Set<String> getHttpPorts() {
        return new HashSet<>(httpPorts);
    }

    /**
     * Get detected HTTPS ports
     */
    public Set<String> getHttpsPorts() {
        return new HashSet<>(httpsPorts);
    }

    /**
     * Get server startup time
     */
    public long getStartupTime() {
        return System.currentTimeMillis() - startTime;
    }
}