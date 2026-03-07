package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.stats.StartupTimeTracker;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.ide.browsers.WebBrowser;
import com.intellij.ide.browsers.WebBrowserManager;
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
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dev.idea.plugins.tomcat.diagnostics.TomcatErrorDiagnostics;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Process handler that monitors Tomcat output for deployment status,
 * errors, and server lifecycle events.
 */
public class TomcatProcessHandler extends KillableColoredProcessHandler implements ProcessListener {

    private static final Logger LOG = Logger.getInstance(TomcatProcessHandler.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 10_000;

    private static final Pattern STARTUP_PATTERN = Pattern.compile(
            "(?i).*server startup in (\\d+).*(?:ms|milliseconds).*");
    private static final Pattern DESCRIPTOR_DEPLOYED_PATTERN = Pattern.compile(
            "Deployment of (?:deployment descriptor|web application archive) \\[.*?([^/\\\\]+)\\.(?:xml|war)\\] has finished in \\[(\\d+)\\] ms");
    private static final Pattern JMX_PATTERN = Pattern.compile(
            "(?i).*jmx.*(?:started|enabled|listening).*port\\s*(\\d+).*");
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "\\b(?:SEVERE|ERROR|FATAL)\\b|" +
            "^\\s*Caused by:\\s|" +
            "^[a-zA-Z_$][a-zA-Z0-9_$.]*(?:Exception|Error)\\b");
    private static final Pattern WARNING_PATTERN = Pattern.compile(
            "\\b(?:WARNING|WARN)\\b");
    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
            "(?i).*context\\s+\\[([^\\]]+)\\].*(?:started|deployed|initialized).*");
    private static final Pattern RELOAD_PATTERN = Pattern.compile(
            "(?i)Reloading Context with name \\[([^\\]]+)\\] (?:is completed|has started)");

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final String configurationName;
    private final String executorId;
    private final int shutdownPort;
    private final int httpPort;
    private final RunnerSettings runnerSettings;

    private final AtomicBoolean serverStartupDetected = new AtomicBoolean(false);
    private final AtomicInteger deployedArtifactCount = new AtomicInteger(0);
    private volatile int expectedArtifactCount;
    private final Map<String, String> contextToArtifactName = new ConcurrentHashMap<>();
    private final boolean jmxEnabled;
    @Nullable private final TomcatDeploymentStatusService statusService;
    @Nullable private final TomcatDeploymentHistory historyService;
    @Nullable private volatile TomcatDeploymentHistory.HistoryEntry historyEntry;
    private volatile long startupTime;
    private volatile long serverStartupTimeMs;
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger warningCount = new AtomicInteger(0);

    public TomcatProcessHandler(@NotNull Process process,
                                @NotNull String commandLine,
                                @NotNull Charset charset,
                                @NotNull TomcatDeploymentLogger deploymentLogger,
                                @NotNull TomcatRunConfiguration configuration,
                                @NotNull RunnerSettings runnerSettings,
                                @Nullable PortConfig resolvedPorts,
                                @NotNull String executorId) {
        super(process, commandLine, charset);
        this.configuration = configuration;
        this.deploymentLogger = deploymentLogger;
        this.runnerSettings = runnerSettings;
        this.configurationName = configuration.getName();
        this.executorId = executorId;
        this.jmxEnabled = configuration.isJmxEnabled();
        // Use resolved ports (post-conflict-detection) when available, fall back to config
        PortConfig ports = resolvedPorts != null ? resolvedPorts : configuration.getConfigData().getPortConfig();
        this.shutdownPort = ports.getShutdown();
        this.httpPort = ports.getHttp();
        boolean projectAvailable = !configuration.getProject().isDisposed();
        this.statusService = projectAvailable
                ? TomcatDeploymentStatusService.getInstance(configuration.getProject()) : null;
        this.historyService = projectAvailable
                ? TomcatDeploymentHistory.getInstance(configuration.getProject()) : null;
        addProcessListener(this);
    }

    @Override
    protected @NotNull BaseOutputReader.Options readerOptions() {
        // Tomcat is a long-running server and can be idle for extended periods.
        return BaseOutputReader.Options.forMostlySilentProcess();
    }

    @Override
    protected void destroyProcessImpl() {
        // Run shutdown logic on a pooled thread to avoid blocking the EDT
        ApplicationManager.getApplication().executeOnPooledThread(this::doGracefulShutdown);
    }

    private void doGracefulShutdown() {
        if (!runnerSettings.isUseDefaultShutdown() && !StringUtil.isEmptyOrSpaces(runnerSettings.getShutdownScript())) {
            LOG.info("Executing custom shutdown script: " + runnerSettings.getShutdownScript());
            try {
                List<String> tokens = com.intellij.util.execution.ParametersListUtil.parse(
                        runnerSettings.getShutdownScript());
                GeneralCommandLine shutdownCmd = new GeneralCommandLine(tokens);
                shutdownCmd.withEnvironment(runnerSettings.getEnvironmentVariables());
                shutdownCmd.withParentEnvironmentType(runnerSettings.isPassParentEnvs() ?
                        GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE);
                if (configuration.getTomcatInfo() != null) {
                    shutdownCmd.withWorkDirectory(configuration.getTomcatInfo().getPath());
                }

                Process p = shutdownCmd.createProcess();
                try {
                    boolean exited = p.waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!exited) {
                        p.destroyForcibly();
                        LOG.warn("Custom shutdown script did not exit within timeout, killed");
                    }
                } finally {
                    closeQuietly(p.getInputStream());
                    closeQuietly(p.getErrorStream());
                    closeQuietly(p.getOutputStream());
                }
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
                try (Socket socket = new Socket(DEFAULT_HOST, shutdownPort);
                     OutputStream out = socket.getOutputStream()) {
                    out.write(SHUTDOWN_COMMAND.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                try {
                    getProcess().waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (isProcessTerminated()) {
                    LOG.info("Tomcat terminated gracefully");
                    return;
                }
                LOG.warn("Tomcat did not terminate within timeout, forcing kill");
            } catch (Exception e) {
                LOG.warn("Graceful shutdown failed, forcing kill", e);
            }
        }
        super.destroyProcessImpl();
    }

    @Override
    public void startNotified(@NotNull ProcessEvent event) {
        startupTime = System.currentTimeMillis();
        deploymentLogger.logServerInfo("Tomcat process started");
        if (statusService != null) {
            statusService.onServerStarting(configurationName);
        }

        // Begin history entry
        if (historyService != null) {
            historyEntry = historyService.startEntry(configurationName);
        }

        List<DeploymentArtifact> artifacts = configuration.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();
        expectedArtifactCount = Math.max(artifacts.size(), 1);
        if (artifacts.isEmpty()) {
            deploymentLogger.logDeploymentStart(configurationName);
        } else {
            for (DeploymentArtifact artifact : artifacts) {
                deploymentLogger.logDeploymentStart(artifact.getDisplayName());
                String contextName = resolveContextName(artifact.getContextPath());
                contextToArtifactName.put(contextName, artifact.getDisplayName());
                if (statusService != null) {
                    statusService.onArtifactDeploying(configurationName, artifact.getDisplayName());
                }
                if (historyEntry != null) {
                    historyEntry.artifactNames.add(artifact.getDisplayName());
                }
            }
        }
    }

    private static String resolveContextName(@Nullable String contextPath) {
        if (contextPath == null) return ROOT_CONTEXT_NAME;
        String trimmed = contextPath.trim();
        if (trimmed.isEmpty() || DEFAULT_CONTEXT_PATH.equals(trimmed)) {
            return ROOT_CONTEXT_NAME;
        }
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
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

        if (statusService != null) {
            statusService.onServerStopped(configurationName, exitCode);
        }

        // Record deployment history entry
        if (historyService != null && historyEntry != null) {
            historyEntry.durationMs = duration;
            historyEntry.exitCode = exitCode;
            historyEntry.success = exitCode == 0;
            historyEntry.errorCount = errorCount.get();
            historyEntry.warningCount = warningCount.get();
            historyEntry.startupTimeMs = serverStartupTimeMs;
            historyService.recordCompleted(historyEntry);
        }

        generateSessionSummary(duration, exitCode);
        deploymentLogger.dispose();
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
        if (startupMatcher.find() && serverStartupDetected.compareAndSet(false, true)) {
            try {
                long duration = Long.parseLong(startupMatcher.group(1));
                serverStartupTimeMs = duration;
                deploymentLogger.logServerStartup(duration);
                trackStartupTime(duration);
                if (statusService != null) {
                    statusService.onServerStarted(configurationName, duration);
                }
                triggerRemoteDeploymentIfNeeded();
                launchBrowserIfEnabled();
            } catch (NumberFormatException e) {
                LOG.debug("Could not parse startup time from: " + startupMatcher.group(1));
            }
        }

        Matcher descriptorMatcher = DESCRIPTOR_DEPLOYED_PATTERN.matcher(text);
        if (descriptorMatcher.find()) {
            try {
                String contextName = descriptorMatcher.group(1);
                long descriptorDuration = Long.parseLong(descriptorMatcher.group(2));
                String artifactName = contextToArtifactName.getOrDefault(contextName, contextName);
                deploymentLogger.logDeploymentSuccess(artifactName, descriptorDuration);
                deployedArtifactCount.incrementAndGet();
                if (statusService != null) {
                    statusService.onArtifactDeployed(configurationName, artifactName);
                }
            } catch (NumberFormatException e) {
                LOG.debug("Could not parse deployment duration from: " + descriptorMatcher.group(2));
            }
        }

        Matcher contextMatcher = CONTEXT_PATTERN.matcher(text);
        if (contextMatcher.find()) {
            deploymentLogger.logServerInfo("Context deployed: " + contextMatcher.group(1));
        }

        Matcher reloadMatcher = RELOAD_PATTERN.matcher(text);
        if (reloadMatcher.find()) {
            String ctx = reloadMatcher.group(1);
            String normalizedCtx = ctx.startsWith("/") ? ctx.substring(1) : ctx;
            String artifactName = contextToArtifactName.getOrDefault(normalizedCtx, ctx);
            deploymentLogger.logServerInfo("Auto-reloaded: " + artifactName);
            if (statusService != null) {
                if (text.contains("has started")) {
                    statusService.onArtifactReloading(configurationName, artifactName);
                } else {
                    statusService.onArtifactDeployed(configurationName, artifactName);
                }
            }
        }

        if (jmxEnabled) {
            Matcher jmxMatcher = JMX_PATTERN.matcher(text);
            if (jmxMatcher.find()) {
                deploymentLogger.logServerInfo("JMX active on port " + jmxMatcher.group(1));
            }
        }

        // Smart diagnostics — analyze ALL lines (including warnings and info for TLD hints)
        List<TomcatErrorDiagnostics.Diagnostic> diagnostics = TomcatErrorDiagnostics.analyze(text);
        if (!diagnostics.isEmpty()) {
            for (TomcatErrorDiagnostics.Diagnostic diag : diagnostics) {
                deploymentLogger.logServerInfo(TomcatErrorDiagnostics.formatForConsole(diag));
            }
        }

        if (ERROR_PATTERN.matcher(text).find()) {
            errorCount.incrementAndGet();
            deploymentLogger.logServerError(text);
            if (statusService != null) {
                statusService.onError(configurationName);
            }
        } else if (WARNING_PATTERN.matcher(text).find()) {
            warningCount.incrementAndGet();
            deploymentLogger.logServerWarning(text);
            if (statusService != null) {
                statusService.onWarning(configurationName);
            }
        }
    }

    /**
     * If the configuration uses Remote mode, deploys artifacts to the remote
     * Tomcat via Manager API after the local server startup is detected.
     */
    private void triggerRemoteDeploymentIfNeeded() {
        if (!TomcatConstants.MODE_REMOTE.equals(configuration.getConfigData().getServerMode())) {
            return;
        }
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig == null || !remoteConfig.isValid()) {
            deploymentLogger.logServerWarning("Remote mode enabled but configuration is invalid — skipping remote deployment");
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            TomcatManagerDeployer deployer = new TomcatManagerDeployer(remoteConfig);

            // Test connectivity first
            String error = deployer.testConnection();
            if (error != null) {
                deploymentLogger.logServerError("Remote connection failed: " + error);
                return;
            }

            List<DeploymentArtifact> artifacts = configuration.getConfigData()
                    .getDeploymentConfig().getDeployedArtifacts();
            int successCount = 0;
            for (DeploymentArtifact artifact : artifacts) {
                if (artifact == null || !artifact.isValid()) continue;
                if (statusService != null) {
                    statusService.onArtifactDeploying(configurationName, artifact.getDisplayName());
                }
                boolean ok = deployer.deploy(artifact, deploymentLogger);
                if (ok) {
                    successCount++;
                    if (statusService != null) {
                        statusService.onArtifactDeployed(configurationName, artifact.getDisplayName());
                    }
                } else {
                    if (statusService != null) {
                        statusService.onArtifactFailed(configurationName, artifact.getDisplayName());
                    }
                }
            }
            deploymentLogger.logServerInfo("Remote deployment complete: " +
                    successCount + "/" + artifacts.size() + " artifact(s) deployed");
        });
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
                    deploymentLogger.logServerInfo("Startup trend: " + comparison);
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
                String context = configuration.getContextPath();
                url = "http://" + DEFAULT_HOST + ":" + httpPort
                        + (context != null ? context : DEFAULT_CONTEXT_PATH);
            }

            boolean jsDebug = configuration.isWithJsDebugger();
            String browserName = configuration.getBrowserName();
            final String targetUrl = url;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    WebBrowser browser = resolveBrowser(browserName);
                    // Passing project enables JS debugger integration when the
                    // JavaScript plugin is installed (provides JavaScriptDebuggerStarter EP)
                    if (browser != null) {
                        BrowserLauncher.getInstance().browse(
                                targetUrl, browser,
                                jsDebug ? configuration.getProject() : null);
                    } else if (jsDebug) {
                        BrowserLauncher.getInstance().browse(
                                targetUrl, null, configuration.getProject());
                    } else {
                        BrowserUtil.browse(targetUrl);
                    }

                    String logMsg = "Browser opened: " + targetUrl;
                    if (jsDebug) {
                        logMsg += " (JS debugger enabled)";
                    }
                    deploymentLogger.logServerInfo(logMsg);
                } catch (Exception e) {
                    LOG.warn("Failed to open browser", e);
                    deploymentLogger.logServerWarning("Could not open browser: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warn("Error launching browser", e);
        }
    }

    @Nullable
    private static WebBrowser resolveBrowser(@Nullable String browserName) {
        if (browserName == null || browserName.isEmpty()
                || BROWSER_SYSTEM_DEFAULT.equalsIgnoreCase(browserName)) {
            return null;
        }
        for (WebBrowser browser : WebBrowserManager.getInstance().getActiveBrowsers()) {
            if (browserName.equals(browser.getName())) {
                return browser;
            }
        }
        return null;
    }

    private void generateSessionSummary(long duration, int exitCode) {
        String summary = String.format(
                "Session summary: config=%s, duration=%dms, exit=%d, started=%s, deployed=%d/%d, errors=%d, warnings=%d",
                configurationName, duration, exitCode,
                serverStartupDetected.get(), deployedArtifactCount.get(), expectedArtifactCount, errorCount.get(), warningCount.get());
        deploymentLogger.logServerInfo(summary);
        LOG.info(summary);
    }

    public boolean isServerStartupDetected() {
        return serverStartupDetected.get();
    }

    public boolean isDeploymentCompleted() {
        return deployedArtifactCount.get() >= expectedArtifactCount;
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public int getWarningCount() {
        return warningCount.get();
    }

    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }

    @NotNull
    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    @NotNull
    public String getExecutorId() {
        return executorId;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
