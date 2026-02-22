package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Process handler that monitors Tomcat output for deployment status,
 * errors, and server lifecycle events.
 */
public class TomcatProcessHandler extends KillableColoredProcessHandler implements ProcessListener {

    private static final Logger LOG = Logger.getInstance(TomcatProcessHandler.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 10_000;
    private static final String SHUTDOWN_COMMAND = "SHUTDOWN";

    private static final Pattern STARTUP_PATTERN = Pattern.compile(
            "(?i).*server startup in (\\d+).*(?:ms|milliseconds).*");
    private static final Pattern DEPLOYMENT_PATTERN = Pattern.compile(
            "(?i).*(deploy|deployment|artifact|exploded|war).*(?:started|deployed|successful|completed|finished).*");
    private static final Pattern JMX_PATTERN = Pattern.compile(
            "(?i).*jmx.*(?:started|enabled|listening).*port\\s*(\\d+).*");
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i).*(error|exception|failed|severe|fatal|cannot|unable).*");
    private static final Pattern WARNING_PATTERN = Pattern.compile(
            "(?i).*(warn|warning|deprecated|problem).*");
    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
            "(?i).*context\\s+\\[([^\\]]+)\\].*(?:started|deployed|initialized).*");

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final String configurationName;
    private final int shutdownPort;

    private boolean serverStartupDetected = false;
    private boolean deploymentCompleted = false;
    private final boolean jmxEnabled;
    private long startupTime = System.currentTimeMillis();
    private int errorCount = 0;
    private int warningCount = 0;

    public TomcatProcessHandler(@NotNull Process process,
                                @NotNull String commandLine,
                                @NotNull Charset charset,
                                @NotNull TomcatDeploymentLogger deploymentLogger,
                                @NotNull TomcatRunConfiguration configuration) {
        super(process, commandLine, charset);
        this.configuration = configuration;
        this.deploymentLogger = deploymentLogger;
        this.configurationName = configuration.getName();
        this.jmxEnabled = configuration.isJmxEnabled();
        this.shutdownPort = configuration.getConfigData().getPortConfig().getShutdown();
        addProcessListener(this);
    }

    @Override
    protected void destroyProcessImpl() {
        if (shutdownPort > 0) {
            try {
                LOG.info("Sending SHUTDOWN command to port " + shutdownPort);
                try (Socket socket = new Socket("localhost", shutdownPort);
                     OutputStream out = socket.getOutputStream()) {
                    out.write(SHUTDOWN_COMMAND.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline && !isProcessTerminated()) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (isProcessTerminated()) {
                    LOG.info("Tomcat terminated gracefully");
                    return;
                }
                LOG.warn("Tomcat did not terminate within timeout, forcing kill");
            } catch (Exception e) {
                LOG.warn("Graceful shutdown failed, forcing kill: " + e.getMessage());
            }
        }
        super.destroyProcessImpl();
    }

    @Override
    public void startNotified(@NotNull ProcessEvent event) {
        startupTime = System.currentTimeMillis();
        deploymentLogger.logServerInfo("Tomcat process started");
        deploymentLogger.logDeploymentStart(configurationName);
    }

    @Override
    public void processTerminated(@NotNull ProcessEvent event) {
        long duration = System.currentTimeMillis() - startupTime;
        int exitCode = event.getExitCode();

        if (exitCode == 0) {
            deploymentLogger.logServerInfo("Tomcat terminated normally after " + duration + "ms");
        } else {
            deploymentLogger.logServerError("Tomcat terminated with exit code " + exitCode);
        }

        generateSessionSummary(duration, exitCode);
    }

    @Override
    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        String text = event.getText();
        if (StringUtil.isNotEmpty(text)) {
            analyzeOutput(text.trim());
        }
    }

    private void analyzeOutput(@NotNull String text) {
        if (text.isEmpty()) return;

        Matcher startupMatcher = STARTUP_PATTERN.matcher(text);
        if (startupMatcher.find() && !serverStartupDetected) {
            serverStartupDetected = true;
            long duration = Long.parseLong(startupMatcher.group(1));
            deploymentLogger.logServerStartup(duration);
        }

        Matcher deploymentMatcher = DEPLOYMENT_PATTERN.matcher(text);
        if (deploymentMatcher.find() && !deploymentCompleted) {
            deploymentCompleted = true;
            long totalTime = System.currentTimeMillis() - startupTime;
            deploymentLogger.logDeploymentSuccess(configurationName, totalTime);
        }

        Matcher contextMatcher = CONTEXT_PATTERN.matcher(text);
        if (contextMatcher.find()) {
            deploymentLogger.logServerInfo("Context deployed: " + contextMatcher.group(1));
        }

        if (jmxEnabled) {
            Matcher jmxMatcher = JMX_PATTERN.matcher(text);
            if (jmxMatcher.find()) {
                deploymentLogger.logServerInfo("JMX active on port " + jmxMatcher.group(1));
            }
        }

        if (ERROR_PATTERN.matcher(text).find()) {
            errorCount++;
            deploymentLogger.logServerError(text);
            String suggestion = getErrorSuggestion(text);
            if (!suggestion.isEmpty()) {
                deploymentLogger.logServerInfo("Suggestion: " + suggestion);
            }
        }

        if (WARNING_PATTERN.matcher(text).find()) {
            warningCount++;
            deploymentLogger.logServerWarning(text);
        }
    }

    private String getErrorSuggestion(@NotNull String errorText) {
        String lower = errorText.toLowerCase();
        if (lower.contains("port") && lower.contains("bind"))
            return "Port already in use — check if another Tomcat instance is running";
        if (lower.contains("classnotfound"))
            return "Missing dependency — verify classpath and Maven/Gradle dependencies";
        if (lower.contains("outofmemory"))
            return "Increase heap size using -Xmx in VM options";
        if (lower.contains("permission"))
            return "Check file permissions and Tomcat directory access rights";
        return "";
    }

    private void generateSessionSummary(long duration, int exitCode) {
        String summary = String.format(
                "Session summary: config=%s, duration=%dms, exit=%d, started=%s, deployed=%s, errors=%d, warnings=%d",
                configurationName, duration, exitCode,
                serverStartupDetected, deploymentCompleted, errorCount, warningCount);
        deploymentLogger.logServerInfo(summary);
        LOG.info(summary);
    }

    public boolean isServerStartupDetected() {
        return serverStartupDetected;
    }

    public boolean isDeploymentCompleted() {
        return deploymentCompleted;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public int getWarningCount() {
        return warningCount;
    }
}
