/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 * Enhanced Server Console with professional deployment feedback
 */

package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Url;
import com.intellij.util.Urls;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced server console with professional deployment logging
 * Provides sophisticated log parsing and URL generation
 */
public class ServerConsoleView extends ConsoleViewImpl {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final TomcatRunConfiguration configuration;
    private boolean deploymentStarted = false;
    private boolean serverReady = false;
    private final List<String> httpPorts = new ArrayList<>();
    private final List<String> httpsPorts = new ArrayList<>();
    private final List<String> ajpPorts = new ArrayList<>();
    private long startTime = System.currentTimeMillis();

    public ServerConsoleView(TomcatRunConfiguration configuration) {
        super(configuration.getProject(), true);
        this.configuration = configuration;
        printServerHeader();
    }

    @Override
    public void print(@NotNull String s, @NotNull ConsoleViewContentType contentType) {
        // Parse and enhance the output before printing
        String enhancedOutput = parseAndEnhanceOutput(s);
        super.print(enhancedOutput, contentType);

        // Skip processing exception traces
        if (s.trim().startsWith("at ") || s.trim().startsWith("Caused by:")) {
            return;
        }

        // Parse connector information
        if (parseConnectorInfo(s)) {
            return;
        }

        // Detect deployment events
        detectDeploymentEvents(s);

        // Check for server startup completion
        checkServerStartupCompletion(s);
    }

    /**
     * Print server header with DevTomcat branding
     */
    private void printServerHeader() {
        String header = String.format(
                "%n=== DevTomcat Server Console ===%n" +
                        "Project: %s%n" +
                        "Configuration: %s%n" +
                        "Started: %s%n" +
                        "=================================%n%n",
                configuration.getProject().getName(),
                configuration.getName(),
                LocalDateTime.now().format(TIMESTAMP_FORMAT)
        );
        super.print(header, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Parse and enhance output with professional formatting
     */
    private String parseAndEnhanceOutput(String s) {
        // Add timestamps to important messages
        if (s.contains("INFO") && (s.contains("Starting") || s.contains("Started") || s.contains("Initializing"))) {
            return addTimestamp(s);
        }

        // Highlight deployment messages
        if (s.contains("Deploying") || s.contains("deployment") || s.contains("Context")) {
            return addTimestamp("DEPLOY: " + s.trim()) + "\n";
        }

        // Highlight server lifecycle messages
        if (s.contains("Server startup") || s.contains("Catalina.start")) {
            return addTimestamp("SERVER: " + s.trim()) + "\n";
        }

        return s;
    }

    /**
     * Add professional timestamp
     */
    private String addTimestamp(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        return String.format("[%s] %s", timestamp, message.trim());
    }

    /**
     * Parse connector information (HTTP, HTTPS, AJP)
     */
    private boolean parseConnectorInfo(String s) {
        boolean parsed = false;

        // HTTP Connector Pattern
        Pattern httpPattern = Pattern.compile("http-nio-(\\d+)");
        Matcher httpMatcher = httpPattern.matcher(s);
        if (httpMatcher.find()) {
            String port = httpMatcher.group(1);
            if (!httpPorts.contains(port)) {
                httpPorts.add(port);
                printConnectorInfo("HTTP", port);
            }
            parsed = true;
        }

        // HTTPS Connector Pattern
        Pattern httpsPattern = Pattern.compile("https-jsse-nio-(\\d+)");
        Matcher httpsMatcher = httpsPattern.matcher(s);
        if (httpsMatcher.find()) {
            String port = httpsMatcher.group(1);
            if (!httpsPorts.contains(port)) {
                httpsPorts.add(port);
                printConnectorInfo("HTTPS", port);
            }
            parsed = true;
        }

        // AJP Connector Pattern
        Pattern ajpPattern = Pattern.compile("ajp-nio-(\\d+)");
        Matcher ajpMatcher = ajpPattern.matcher(s);
        if (ajpMatcher.find()) {
            String port = ajpMatcher.group(1);
            if (!ajpPorts.contains(port)) {
                ajpPorts.add(port);
                printConnectorInfo("AJP", port);
            }
            parsed = true;
        }

        return parsed;
    }

    /**
     * Print professional connector information
     */
    private void printConnectorInfo(String type, String port) {
        String message = String.format("%s Connector configured on port %s%n", type, port);
        super.print(addTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Detect deployment events and provide professional feedback
     */
    private void detectDeploymentEvents(String s) {
        String contextPath = configuration.getContextPath();
        String normalizedContext = StringUtil.trimStart(contextPath, "/");

        // Deployment start detection
        if (s.contains("Deploying web application") && s.contains(normalizedContext)) {
            if (!deploymentStarted) {
                deploymentStarted = true;
                String message = String.format("Artifact %s:war exploded: Artifact is being deployed, please wait...%n",
                        normalizedContext.isEmpty() ? "ROOT" : normalizedContext);
                super.print(addTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);
            }
        }

        // Deployment completion detection
        if (s.contains("Deployment of web application") && s.contains("finished") && s.contains(normalizedContext)) {
            if (deploymentStarted) {
                long deployTime = System.currentTimeMillis() - startTime;
                String message = String.format("Artifact %s:war exploded: Artifact is deployed successfully%n",
                        normalizedContext.isEmpty() ? "ROOT" : normalizedContext);
                super.print(addTimestamp(message), ConsoleViewContentType.SYSTEM_OUTPUT);

                // Print deployment time
                String timeMessage = String.format("Deployment completed in %d ms%n", deployTime);
                super.print(addTimestamp(timeMessage), ConsoleViewContentType.SYSTEM_OUTPUT);
            }
        }
    }

    /**
     * Check for server startup completion and print application URLs
     */
    private void checkServerStartupCompletion(String s) {
        if (!serverReady && (s.contains("org.apache.catalina.startup.Catalina start")
                || s.contains("Server startup"))) {

            serverReady = true;

            // Use configured ports if not detected from logs
            if (httpPorts.isEmpty() && httpsPorts.isEmpty()) {
                httpPorts.add(String.valueOf(configuration.getPort()));
                Integer sslPort = configuration.getSslPort();
                if (sslPort != null) {
                    httpsPorts.add(String.valueOf(sslPort));
                }
            }

            // Print server startup completion
            long startupTime = System.currentTimeMillis() - startTime;
            String startupMessage = String.format("%nServer startup completed in %d ms%n", startupTime);
            super.print(addTimestamp(startupMessage), ConsoleViewContentType.SYSTEM_OUTPUT);

            // Print application URLs
            printApplicationUrls();

            // Print JMX information if enabled
            if (configuration.isJmxEnabled()) {
                String jmxMessage = String.format("JMX monitoring available on port %d%n",
                        configuration.getJmxPort());
                super.print(addTimestamp(jmxMessage), ConsoleViewContentType.SYSTEM_OUTPUT);
            }

            printReadyMessage();
        }
    }

    /**
     * Print application URLs
     */
    private void printApplicationUrls() {
        List<Url> urls = buildApplicationUrls();
        if (!urls.isEmpty()) {
            super.print(addTimestamp("Application URLs:") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
            for (Url url : urls) {
                String urlMessage = String.format("  %s%n", url.toString());
                super.print(urlMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
            }
            super.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        }
    }

    /**
     * Build application URLs
     */
    private List<Url> buildApplicationUrls() {
        List<Url> urls = new ArrayList<>();
        String contextPath = configuration.getContextPath();
        String path = "/" + StringUtil.trimStart(contextPath, "/");

        // Normalize path for ROOT context
        if ("/".equals(path) || path.isEmpty()) {
            path = "/";
        }

        // HTTP URLs
        for (String httpPort : httpPorts) {
            try {
                boolean isDefaultPort = "80".equals(httpPort);
                String authority = "localhost" + (isDefaultPort ? "" : ":" + httpPort);
                urls.add(Urls.newHttpUrl(authority, path));
            } catch (Exception e) {
                // Ignore malformed URLs
            }
        }

        // HTTPS URLs
        for (String httpsPort : httpsPorts) {
            try {
                boolean isDefaultPort = "443".equals(httpsPort);
                String authority = "localhost" + (isDefaultPort ? "" : ":" + httpsPort);
                urls.add(Urls.newUrl("https", authority, path));
            } catch (Exception e) {
                // Ignore malformed URLs
            }
        }

        return urls;
    }

    /**
     * Print server ready message
     */
    private void printReadyMessage() {
        String readyMessage = String.format(
                "%n=== DevTomcat Server Ready ===%n" +
                        "Hot deployment: %s%n" +
                        "JMX monitoring: %s%n" +
                        "Environment variables: %d configured%n" +
                        "=============================%n%n",
                configuration.isHotDeploymentEnabled() ? "Enabled" : "Disabled",
                configuration.isJmxEnabled() ? "Port " + configuration.getJmxPort() : "Disabled",
                configuration.getEnvironmentVariables().size()
        );
        super.print(readyMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Print error message
     */
    public void printError(String message) {
        String errorMessage = addTimestamp("ERROR: " + message) + "\n";
        super.print(errorMessage, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /**
     * Print warning message
     */
    public void printWarning(String message) {
        String warningMessage = addTimestamp("WARNING: " + message) + "\n";
        super.print(warningMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Print info message
     */
    public void printInfo(String message) {
        String infoMessage = addTimestamp("INFO: " + message) + "\n";
        super.print(infoMessage, ConsoleViewContentType.NORMAL_OUTPUT);
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
    public List<String> getHttpPorts() {
        return new ArrayList<>(httpPorts);
    }

    /**
     * Get detected HTTPS ports
     */
    public List<String> getHttpsPorts() {
        return new ArrayList<>(httpsPorts);
    }

    /**
     * Get server startup time
     */
    public long getStartupTime() {
        return System.currentTimeMillis() - startTime;
    }
}