package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.WebBrowsersDialog;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.util.io.BaseOutputReader;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    private final RunnerSettings runnerSettings;

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
                                @NotNull TomcatRunConfiguration configuration,
                                @NotNull RunnerSettings runnerSettings) {
        super(process, commandLine, charset);
        this.configuration = configuration;
        this.deploymentLogger = deploymentLogger;
        this.runnerSettings = runnerSettings;
        this.configurationName = configuration.getName();
        this.jmxEnabled = configuration.isJmxEnabled();
        this.shutdownPort = configuration.getConfigData().getPortConfig().getShutdown();
        addProcessListener(this);
    }

    @Override
    protected @NotNull BaseOutputReader.Options readerOptions() {
        // Tomcat is a long-running server and can be idle for extended periods.
        return BaseOutputReader.Options.forMostlySilentProcess();
    }

    @Override
    protected void destroyProcessImpl() {
        if (!runnerSettings.isUseDefaultShutdown() && !StringUtil.isEmptyOrSpaces(runnerSettings.getShutdownScript())) {
            LOG.info("Executing custom shutdown script: " + runnerSettings.getShutdownScript());
            try {
                GeneralCommandLine shutdownCmd = new GeneralCommandLine(runnerSettings.getShutdownScript());
                shutdownCmd.withEnvironment(runnerSettings.getEnvironmentVariables());
                shutdownCmd.withParentEnvironmentType(runnerSettings.isPassParentEnvs() ?
                        GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE);
                if (configuration.getTomcatInfo() != null) {
                    shutdownCmd.withWorkDirectory(configuration.getTomcatInfo().getPath());
                }
                
                Process p = shutdownCmd.createProcess();
                p.waitFor(SHUTDOWN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!isProcessTerminated()) {
                    super.destroyProcessImpl();
                }
                return;
            } catch (Exception e) {
                LOG.error("Failed to execute custom shutdown script. Falling back to default behavior.", e);
            }
        }

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
            trackStartupTime(duration);
            launchBrowserIfEnabled();
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

    /**
     * Records startup time and logs trend comparison.
     * DevTomcat exclusive: tracks startup performance across runs.
     */
    private void trackStartupTime(long durationMs) {
        try {
            StartupTimeTracker tracker = ApplicationManager.getApplication()
                    .getService(StartupTimeTracker.class);
            if (tracker != null) {
                tracker.recordStartupTime(configurationName, durationMs);
                String comparison = tracker.formatComparison(configurationName, durationMs);
                if (!comparison.isEmpty()) {
                    deploymentLogger.logServerInfo("📊 " + comparison);
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to track startup time: " + e.getMessage());
        }
    }

    private void launchBrowserIfEnabled() {
        try {
            if (!configuration.isAfterLaunchEnabled()) {
                return;
            }

            String url = configuration.getBrowserUrl();
            if (url == null || url.isEmpty()) {
                Integer port = configuration.getHttpPort();
                String context = configuration.getContextPath();
                url = "http://localhost:" + (port != null ? port : 8080) + (context != null ? context : "/");
            }

            String browserName = configuration.getBrowserName();
            final String targetUrl = url;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if (browserName == null || browserName.isEmpty()
                            || "System Default".equals(browserName)
                            || "System default".equals(browserName)) {
                        BrowserUtil.browse(targetUrl);
                    } else {
                        launchSpecificBrowser(browserName, targetUrl);
                    }
                    deploymentLogger.logServerInfo("Browser opened: " + targetUrl);
                } catch (Exception e) {
                    LOG.warn("Failed to open browser: " + e.getMessage());
                    deploymentLogger.logServerWarning("Could not open browser: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warn("Error launching browser: " + e.getMessage());
        }
    }

    private void launchSpecificBrowser(@NotNull String browserName, @NotNull String url) {
        List<WebBrowsersDialog.BrowserInfo> browsers = WebBrowsersDialog.getBrowserConfigurations();
        for (WebBrowsersDialog.BrowserInfo browser : browsers) {
            if (browserName.equals(browser.getName()) && browser.isActive()) {
                String path = browser.getPath();
                if (path != null && new File(path).exists()) {
                    try {
                        new ProcessBuilder(path, url).start();
                        return;
                    } catch (Exception e) {
                        LOG.warn("Failed to launch " + browserName + ": " + e.getMessage());
                    }
                }
                break;
            }
        }
        // Fall back to system default
        BrowserUtil.browse(url);
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
