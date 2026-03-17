package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
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
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Process handler that monitors Tomcat output for deployment status,
 * errors, and server lifecycle events.
 */
public class TomcatProcessHandler extends KillableColoredProcessHandler implements ProcessListener {

    private static final Logger LOG = Logger.getInstance(TomcatProcessHandler.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 15_000;

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
    private final TomcatLifecycleListener lifecycleListener;
    private volatile long startupTime;
    private volatile long serverStartupTimeMs;
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger warningCount = new AtomicInteger(0);
    private final TomcatOutputPipeline pipeline;
    private final TomcatOutputPipeline.Context pipelineContext;
    private final AtomicBoolean browserLaunchTriggered = new AtomicBoolean(false);
    private final Set<String> readyContexts = ConcurrentHashMap.newKeySet();
    private volatile @Nullable String browserTargetContextName;
    private final boolean activateToolWindow;
    private final boolean showConsoleOnStdout;
    private final boolean showConsoleOnStderr;
    private final AtomicLong lastConsoleActivation = new AtomicLong(0);
    private static final long CONSOLE_ACTIVATION_DEBOUNCE_MS = 500;

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
        // Build lifecycle listener from event consumers
        TomcatOutputPipeline.PipelineLogger pipelineLogger = new TomcatOutputPipeline.PipelineLogger() {
            @Override public void logServerStartup(long durationMs) { deploymentLogger.logServerStartup(durationMs); }
            @Override public void logDeploymentSuccess(@NotNull String name, long ms) { deploymentLogger.logDeploymentSuccess(name, ms); }
            @Override public void logServerInfo(@NotNull String msg) { deploymentLogger.logServerInfo(msg); }
            @Override public void logServerError(@NotNull String msg) { deploymentLogger.logServerError(msg); }
            @Override public void logServerWarning(@NotNull String msg) { deploymentLogger.logServerWarning(msg); }
        };
        this.lifecycleListener = TomcatLifecycleListener.forConfiguration(configuration, pipelineLogger);

        this.pipelineContext = new TomcatOutputPipeline.Context(
                pipelineLogger, lifecycleListener, configurationName,
                contextToArtifactName, serverStartupDetected, deployedArtifactCount,
                errorCount, warningCount, jmxEnabled,
                duration -> { this.serverStartupTimeMs = duration; },
                () -> {
                    triggerRemoteDeploymentIfNeeded();
                    if (shouldWaitForContextBeforeOpeningBrowser()) {
                        tryLaunchBrowserWhenReady();
                    } else {
                        launchBrowserIfEnabled();
                    }
                },
                this::onContextReady
        );
        this.pipeline = TomcatOutputPipeline.create(pipelineContext);

        this.activateToolWindow = configuration.getConfigData().getUiConfig().isActivateToolWindow();
        var logConfig = configuration.getConfigData().getLogFileConfig();
        this.showConsoleOnStdout = logConfig.isShowStdoutConsole();
        this.showConsoleOnStderr = logConfig.isShowStderrConsole();

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
                    waitForProcessExit();
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
                LOG.info("SHUTDOWN command sent, waiting up to " + SHUTDOWN_TIMEOUT_MS + "ms for process exit");
                boolean exited = false;
                try {
                    exited = getProcess().waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Thread interrupted (likely IDE shutting down) — give Tomcat a brief grace period
                    LOG.info("Shutdown wait interrupted, allowing 5s grace period");
                    try {
                        exited = getProcess().waitFor(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e2) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (exited || !getProcess().isAlive()) {
                    LOG.info("Tomcat terminated gracefully");
                    return;
                }
                LOG.warn("Tomcat did not terminate within timeout, forcing kill");
            } catch (Exception e) {
                LOG.warn("Graceful shutdown failed, forcing kill", e);
            }
        }
        super.destroyProcessImpl();
        waitForProcessExit();
    }

    /**
     * Waits briefly for the OS process to fully exit after a force kill,
     * ensuring ports and resources are released before the next session starts.
     */
    private void waitForProcessExit() {
        try {
            boolean exited = getProcess().waitFor(5, TimeUnit.SECONDS);
            if (exited) {
                LOG.info("Process exited after force kill");
            } else {
                LOG.warn("Process did not exit within 5s after force kill — ports may remain held");
                getProcess().destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void startNotified(@NotNull ProcessEvent event) {
        startupTime = System.currentTimeMillis();
        deploymentLogger.logServerInfo("Tomcat process started");
        lifecycleListener.onServerStarting(configurationName);

        List<DeploymentArtifact> artifacts = configuration.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();
        expectedArtifactCount = artifacts.size();
        if (artifacts.isEmpty()) {
            deploymentLogger.logDeploymentStart(configurationName);
        } else {
            for (DeploymentArtifact artifact : artifacts) {
                deploymentLogger.logDeploymentStart(artifact.getDisplayName());
                String contextName = resolveContextName(artifact.getContextPath());
                contextToArtifactName.put(contextName, artifact.getDisplayName());
                lifecycleListener.onArtifactDeploying(configurationName, artifact.getDisplayName());
            }
        }
        browserTargetContextName = resolveBrowserTargetContext(artifacts);
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

        lifecycleListener.onServerStopped(configurationName, exitCode, duration,
                errorCount.get(), warningCount.get(), serverStartupTimeMs);

        generateSessionSummary(duration, exitCode);
        deploymentLogger.dispose();
    }

    @Override
    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        String text = event.getText();
        if (StringUtil.isNotEmpty(text)) {
            analyzeOutput(text.trim());
        }
        maybeActivateConsole(outputType);
    }

    private void analyzeOutput(@NotNull String text) {
        // ProcessEvent.getText() can deliver multiple lines in a single event
        // (buffered output). Split and process each line individually so all
        // analyzers (especially DeploymentAnalyzer) see every deployment message.
        if (text.indexOf('\n') >= 0) {
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    pipeline.processLine(trimmed, pipelineContext);
                }
            }
        } else {
            pipeline.processLine(text, pipelineContext);
        }
    }

    private void onContextReady(@NotNull String rawContextName) {
        readyContexts.add(resolveContextName(rawContextName));
        tryLaunchBrowserWhenReady();
    }

    private boolean shouldWaitForContextBeforeOpeningBrowser() {
        return configuration.isAfterLaunchEnabled()
                && !TomcatConstants.MODE_REMOTE.equals(configuration.getConfigData().getServerMode())
                && browserTargetContextName != null;
    }

    private void tryLaunchBrowserWhenReady() {
        if (!configuration.isAfterLaunchEnabled() || !serverStartupDetected.get()) {
            return;
        }

        if (!shouldWaitForContextBeforeOpeningBrowser()) {
            launchBrowserIfEnabled();
            return;
        }

        String targetContext = browserTargetContextName;
        if (targetContext != null && readyContexts.contains(targetContext)) {
            launchBrowserIfEnabled();
        }
    }

    private @Nullable String resolveBrowserTargetContext(@NotNull List<DeploymentArtifact> artifacts) {
        if (artifacts.isEmpty()) {
            return null;
        }

        String configuredUrl = configuration.getBrowserUrl();
        String contextFromUrl = extractContextNameFromBrowserUrl(configuredUrl);
        if (contextFromUrl != null) {
            for (DeploymentArtifact artifact : artifacts) {
                if (contextFromUrl.equals(resolveContextName(artifact.getContextPath()))) {
                    return contextFromUrl;
                }
            }
        }

        if (artifacts.size() == 1) {
            return resolveContextName(artifacts.get(0).getContextPath());
        }

        String configuredContext = resolveContextName(configuration.getContextPath());
        for (DeploymentArtifact artifact : artifacts) {
            if (configuredContext.equals(resolveContextName(artifact.getContextPath()))) {
                return configuredContext;
            }
        }

        return null;
    }

    private static @Nullable String extractContextNameFromBrowserUrl(@Nullable String url) {
        if (StringUtil.isEmptyOrSpaces(url)) {
            return null;
        }

        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null || path.isEmpty() || DEFAULT_CONTEXT_PATH.equals(path)) {
                return ROOT_CONTEXT_NAME;
            }

            String[] segments = path.split("/");
            for (String segment : segments) {
                if (!segment.isBlank()) {
                    return segment;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not parse browser URL for context: " + url, e);
        }

        return null;
    }

    /**
     * Activates the Run/Debug tool window when output arrives on stdout or stderr,
     * if the corresponding "Show console when message is printed to..." setting is enabled.
     * Debounced to avoid excessive EDT dispatches on rapid output.
     */
    private void maybeActivateConsole(@NotNull Key outputType) {
        if (!activateToolWindow) return;

        boolean shouldActivate =
                (outputType == ProcessOutputTypes.STDOUT && showConsoleOnStdout) ||
                (outputType == ProcessOutputTypes.STDERR && showConsoleOnStderr);
        if (!shouldActivate) return;

        long now = System.currentTimeMillis();
        long last = lastConsoleActivation.get();
        if (now - last < CONSOLE_ACTIVATION_DEBOUNCE_MS) return;
        if (!lastConsoleActivation.compareAndSet(last, now)) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // executorId matches ToolWindow ID ("Run" or "Debug")
                ToolWindow tw = ToolWindowManager.getInstance(configuration.getProject())
                        .getToolWindow(executorId);
                if (tw != null && !tw.isActive()) {
                    tw.activate(null);
                }
            } catch (Exception e) {
                LOG.debug("Could not activate console: " + e.getMessage());
            }
        });
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

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            new com.intellij.openapi.progress.Task.Backgroundable(
                    configuration.getProject(), "Deploying to Remote Tomcat", true) {
                @Override
                public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                    CredentialResolver.ensureResolved(remoteConfig);

                    TomcatManagerDeployer deployer = new TomcatManagerDeployer(remoteConfig);

                    indicator.setText("Testing remote connection...");
                    String error = deployer.testConnection();
                    if (error != null) {
                        deploymentLogger.logServerError("Remote connection failed: " + error);
                        return;
                    }

                    List<DeploymentArtifact> artifacts = configuration.getConfigData()
                            .getDeploymentConfig().getDeployedArtifacts();
                    int successCount = 0;
                    int total = artifacts.size();
                    for (int i = 0; i < total; i++) {
                        DeploymentArtifact artifact = artifacts.get(i);
                        if (artifact == null || !artifact.isValid()) continue;
                        if (indicator.isCanceled()) {
                            deploymentLogger.logServerWarning("Remote deployment cancelled by user");
                            return;
                        }
                        indicator.setText("Deploying " + artifact.getDisplayName() + " (" + (i + 1) + "/" + total + ")");
                        indicator.setFraction((double) i / total);
                        lifecycleListener.onArtifactDeploying(configurationName, artifact.getDisplayName());
                        TomcatManagerDeployer.DeployResult result =
                                deployer.deployWithProgress(artifact, deploymentLogger, indicator);
                        switch (result) {
                            case SUCCESS -> {
                                successCount++;
                                lifecycleListener.onArtifactDeployed(configurationName, artifact.getDisplayName());
                            }
                            case CANCELLED -> {
                                deploymentLogger.logServerWarning("Deployment cancelled: " + artifact.getDisplayName());
                                // Don't mark as failed — user chose to cancel
                                return;
                            }
                            case FAILED ->
                                lifecycleListener.onArtifactFailed(configurationName, artifact.getDisplayName());
                        }
                    }
                    indicator.setFraction(1.0);
                    deploymentLogger.logServerInfo("Remote deployment complete: " +
                            successCount + "/" + total + " artifact(s) deployed");
                }
            });
    }

    private void launchBrowserIfEnabled() {
        try {
            if (!configuration.isAfterLaunchEnabled()) {
                return;
            }
            if (!browserLaunchTriggered.compareAndSet(false, true)) {
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
