package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.ide.browsers.WebBrowser;
import com.intellij.ide.browsers.WebBrowserManager;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.RunnerAndConfigurationSettings;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;
import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.util.execution.ParametersListUtil;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Process handler that monitors Tomcat output for deployment status,
 * errors, and server lifecycle events.
 */
public class TomcatProcessHandler extends KillableColoredProcessHandler implements ProcessListener {

    private static final Logger LOG = Logger.getInstance(TomcatProcessHandler.class);

    private static final long SHUTDOWN_TIMEOUT_MS = 15_000;

    private final TomcatRunConfiguration configuration;
    /**
     * Stable identity of the run configuration as IntelliJ sees it.
     * Survives cross-executor switches (Run→Debug), config cloning, and renames.
     * Used by the runner delegate to match descriptors to environments without
     * relying on fragile reference equality on {@link TomcatRunConfiguration}.
     */
    @Nullable private final RunnerAndConfigurationSettings launchSettings;
    private final TomcatDeploymentLogger deploymentLogger;
    private final String configurationName;
    private final String executorId;
    private final int shutdownPort;
    private final int httpPort;
    @Nullable private final PortConfig resolvedPorts;
    /**
     * The JDWP port this process is attached to (post-conflict-resolution),
     * or {@code -1} when the handler is not running under the debug executor.
     * Carried across same-executor restarts so a TIME_WAIT socket on the just-
     * released port doesn't force the new launch onto a different JDWP port.
     */
    private final int resolvedDebugPort;
    private final RunnerSettings runnerSettings;
    /**
     * Per-launch identifier assigned when "Allow parallel run" is active — this
     * handler owns an isolated {@code CATALINA_BASE} under
     * {@code .runs/{runId}/}. {@code null} means the shared per-config base
     * (single-instance mode) and no cleanup happens on exit.
     */
    @Nullable private final String runId;

    private final AtomicBoolean serverStartupDetected = new AtomicBoolean(false);
    private final AtomicInteger deployedArtifactCount = new AtomicInteger(0);
    private final AtomicInteger expectedArtifactCount = new AtomicInteger(0);
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
                                @NotNull String executorId,
                                @Nullable RunnerAndConfigurationSettings launchSettings) {
        this(process, commandLine, charset, deploymentLogger, configuration,
                runnerSettings, resolvedPorts, -1, executorId, launchSettings, null);
    }

    public TomcatProcessHandler(@NotNull Process process,
                                @NotNull String commandLine,
                                @NotNull Charset charset,
                                @NotNull TomcatDeploymentLogger deploymentLogger,
                                @NotNull TomcatRunConfiguration configuration,
                                @NotNull RunnerSettings runnerSettings,
                                @Nullable PortConfig resolvedPorts,
                                int resolvedDebugPort,
                                @NotNull String executorId,
                                @Nullable RunnerAndConfigurationSettings launchSettings,
                                @Nullable String runId) {
        super(process, commandLine, charset);
        this.configuration = configuration;
        this.launchSettings = launchSettings;
        this.deploymentLogger = deploymentLogger;
        this.runnerSettings = runnerSettings;
        this.configurationName = configuration.getName();
        this.executorId = executorId;
        this.runId = runId;
        this.jmxEnabled = configuration.isJmxEnabled();
        // Use resolved ports (post-conflict-detection) when available, fall back to config
        PortConfig ports = resolvedPorts != null ? resolvedPorts : configuration.getConfigData().getPortConfig();
        this.resolvedPorts = resolvedPorts;
        this.resolvedDebugPort = resolvedDebugPort;
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
        this.showConsoleOnStdout = configuration.isShowConsoleOnStdOut();
        this.showConsoleOnStderr = configuration.isShowConsoleOnStdErr();

        addProcessListener(this);
    }

    @Override
    protected @NotNull BaseOutputReader.Options readerOptions() {
        // Tomcat is a long-running server and can be idle for extended periods.
        return BaseOutputReader.Options.forMostlySilentProcess();
    }

    @Override
    protected void destroyProcessImpl() {
        // Freeze error/warning counters — shutdown cleanup messages (JDBC driver
        // deregistration, leaked thread warnings) are not actionable and should
        // not inflate the dashboard badge. Warnings still appear in the console.
        pipelineContext.markShuttingDown();
        // Run shutdown logic on a pooled thread to avoid blocking the EDT
        ApplicationManager.getApplication().executeOnPooledThread(this::doGracefulShutdown);
    }

    private void doGracefulShutdown() {
        if (!runnerSettings.isUseDefaultShutdown() && !StringUtil.isEmptyOrSpaces(runnerSettings.getShutdownScript())) {
            LOG.info("Executing custom shutdown script: " + runnerSettings.getShutdownScript());
            try {
                List<String> tokens = ParametersListUtil.parse(
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

        List<DeploymentArtifact> artifacts = configuration.getDeployedArtifacts();
        expectedArtifactCount.set(artifacts.size());
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
        return ContextPathUtils.resolveContextNameSafe(contextPath, LOG);
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

        // Release all ports claimed by this configuration so they become available
        // to the next launch without waiting for the OS to reclaim them
        TomcatPortRegistry.getInstance()
                .releaseAllFor(configurationName);

        try {
            lifecycleListener.onServerStopped(configurationName, exitCode, duration,
                    errorCount.get(), warningCount.get(), serverStartupTimeMs);
            generateSessionSummary(duration, exitCode);
        } finally {
            cleanupParallelRunBase();
            // Always dispose the logger, even if the lifecycle callbacks throw.
            deploymentLogger.dispose();
        }
    }

    /**
     * When this handler owns a per-run isolated CATALINA_BASE (parallel mode),
     * delete it after the process terminates so disk state never accumulates
     * across launches. No-op for the shared per-config base.
     *
     * <p>Runs on a pooled thread — the cleanup walks work/, logs/, temp/, and
     * the mirror-managed subtree which can be sizable.
     *
     * <p><b>Safety contract:</b> the resolved path's parent directory name must
     * equal {@link TomcatProjectUtils#PARALLEL_RUNS_SUBDIR} ({@code .runs}) before
     * anything is deleted. {@link TomcatProjectUtils#getCatalinaBase} ignores
     * {@code runId} when the user pinned an explicit CATALINA_BASE, so without
     * this check we would recursively delete whatever directory the user pinned.
     * {@link RunIdAssigner#resolve()} already refuses to assign a
     * runId when a pin is in effect; this is defense-in-depth against any future
     * code path that constructs a handler with a runId directly.
     */
    private void cleanupParallelRunBase() {
        if (runId == null) {
            return;
        }
        Path base = TomcatProjectUtils.getCatalinaBase(configuration, runId);
        if (base == null || !Files.isDirectory(base, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // Refuse to follow a symlink at the run-base root — otherwise a planted
        // link under .runs/ could redirect the recursive delete onto an
        // unrelated tree. The walker handles symlinks for nested entries; this
        // closes the gap at the root.
        if (Files.isSymbolicLink(base)) {
            LOG.warn("Refusing to delete CATALINA_BASE '" + base
                    + "' on process exit: path is a symbolic link.");
            return;
        }
        Path parent = base.getParent();
        if (parent == null
                || parent.getFileName() == null
                || !TomcatProjectUtils.PARALLEL_RUNS_SUBDIR.equals(parent.getFileName().toString())) {
            LOG.warn("Refusing to delete CATALINA_BASE '" + base + "' on process exit: "
                    + "path is not under the " + TomcatProjectUtils.PARALLEL_RUNS_SUBDIR
                    + "/ isolation subtree (likely a pinned user directory). "
                    + "runId was '" + runId + "'.");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                deleteRecursively(base);
                LOG.info("Removed parallel-run CATALINA_BASE: " + base);
            } catch (IOException e) {
                LOG.warn("Could not remove parallel-run CATALINA_BASE '" + base + "': " + e.getMessage());
            }
        });
    }

    private static void deleteRecursively(@NotNull Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attrs) throws IOException {
                if (!current.equals(dir) && attrs.isSymbolicLink()) {
                    Files.deleteIfExists(current);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(current);
                return FileVisitResult.CONTINUE;
            }
        });
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
        String resolved = resolveContextName(rawContextName);
        readyContexts.add(resolved);
        LOG.info("Context ready: '" + resolved + "' (raw: '" + rawContextName +
                "'), target: '" + browserTargetContextName + "', ready: " + readyContexts);
        tryLaunchBrowserWhenReady();
    }

    private boolean shouldWaitForContextBeforeOpeningBrowser() {
        return configuration.isAfterLaunchEnabled()
                && !configuration.isRemoteMode()
                && browserTargetContextName != null;
    }

    private void tryLaunchBrowserWhenReady() {
        if (!configuration.isAfterLaunchEnabled()) {
            LOG.debug("Browser launch disabled (afterLaunch not enabled)");
            return;
        }
        if (!serverStartupDetected.get()) {
            LOG.debug("Browser launch deferred (server startup not yet detected)");
            return;
        }

        if (!shouldWaitForContextBeforeOpeningBrowser()) {
            LOG.info("Browser launch: not waiting for context — launching immediately");
            launchBrowserIfEnabled();
            return;
        }

        String targetContext = browserTargetContextName;
        if (targetContext != null && readyContexts.contains(targetContext)) {
            LOG.info("Browser launch: target context '" + targetContext + "' is ready — launching");
            launchBrowserIfEnabled();
        } else {
            LOG.debug("Browser launch waiting: target='" + targetContext +
                    "', ready=" + readyContexts);
        }
    }

    private @Nullable String resolveBrowserTargetContext(@NotNull List<DeploymentArtifact> artifacts) {
        if (artifacts.isEmpty()) {
            return null;
        }

        // 1. Try matching the browser URL's context to a deployed artifact
        String configuredUrl = configuration.getBrowserUrl();
        String contextFromUrl = extractContextNameFromBrowserUrl(configuredUrl);
        if (contextFromUrl != null) {
            for (DeploymentArtifact artifact : artifacts) {
                if (contextFromUrl.equals(resolveContextName(artifact.getContextPath()))) {
                    return contextFromUrl;
                }
            }
        }

        // 2. Single artifact — use it directly
        if (artifacts.size() == 1) {
            return resolveContextName(artifacts.get(0).getContextPath());
        }

        // 3. Try matching the global context path to a deployed artifact
        String configuredContext = resolveContextName(configuration.getContextPath());
        for (DeploymentArtifact artifact : artifacts) {
            if (configuredContext.equals(resolveContextName(artifact.getContextPath()))) {
                return configuredContext;
            }
        }

        // 4. Fall back to the first artifact rather than returning null (which
        //    silently prevents the browser from opening with no indication why)
        String fallback = resolveContextName(artifacts.get(0).getContextPath());
        LOG.warn("Browser URL context does not match any deployed artifact; " +
                "falling back to first artifact's context: " + fallback);
        return fallback;
    }

    static @Nullable String extractContextNameFromBrowserUrl(@Nullable String url) {
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
     *
     * <p>Only activates if the tool window is already visible (expanded). Skipped when
     * the tool window is collapsed — i.e. when the user is viewing the console via the
     * Services panel — to prevent Services from losing focus and auto-hiding.
     */
    private void maybeActivateConsole(@NotNull Key outputType) {
        if (!activateToolWindow) return;

        boolean shouldActivate =
                (outputType == ProcessOutputTypes.STDOUT && showConsoleOnStdout) ||
                (outputType == ProcessOutputTypes.STDERR && showConsoleOnStderr);
        if (!shouldActivate) return;

        long now = System.currentTimeMillis();
        long last = lastConsoleActivation.getAndSet(now);
        if (now - last < CONSOLE_ACTIVATION_DEBOUNCE_MS) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // executorId matches ToolWindow ID ("Run" or "Debug")
                ToolWindow tw = ToolWindowManager.getInstance(configuration.getProject())
                        .getToolWindow(executorId);
                // Only activate if already visible — avoids stealing focus from the
                // Services panel when the user is viewing the console there instead.
                if (tw != null && tw.isVisible() && !tw.isActive()) {
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
        if (!configuration.isRemoteMode()) {
            return;
        }
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig == null || !remoteConfig.isValid()) {
            deploymentLogger.logServerWarning("Remote mode enabled but configuration is invalid — skipping remote deployment");
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(
                    configuration.getProject(), "Deploying to Remote Tomcat", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    CredentialResolver.ensureResolved(remoteConfig);

                    TomcatManagerDeployer deployer = new TomcatManagerDeployer(remoteConfig);
                    List<DeploymentArtifact> artifacts = configuration.getDeployedArtifacts().stream()
                            .filter(artifact -> artifact != null && artifact.isValid())
                            .toList();
                    if (artifacts.isEmpty()) {
                        deploymentLogger.logServerInfo("Remote mode active, but no valid artifacts are configured for deployment");
                        return;
                    }

                    indicator.setText("Testing remote connection...");
                    if (isProcessTerminatingOrTerminated()) {
                        return;
                    }
                    String error = deployer.testConnection();
                    if (isProcessTerminatingOrTerminated()) {
                        return;
                    }
                    if (error != null) {
                        deploymentLogger.logServerError("Remote connection failed: " + error);
                        for (DeploymentArtifact artifact : artifacts) {
                            lifecycleListener.onArtifactFailed(configurationName, artifact.getDisplayName());
                        }
                        return;
                    }

                    int successCount = 0;
                    int total = artifacts.size();
                    for (int i = 0; i < total; i++) {
                        DeploymentArtifact artifact = artifacts.get(i);
                        if (indicator.isCanceled() || isProcessTerminatingOrTerminated()) {
                            deploymentLogger.logServerWarning("Remote deployment cancelled");
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
                                lifecycleListener.onArtifactCancelled(configurationName, artifact.getDisplayName());
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

    /** True once the local process has begun shutdown — short-circuit for long-running background work. */
    private boolean isProcessTerminatingOrTerminated() {
        return isProcessTerminating() || isProcessTerminated();
    }

    private void launchBrowserIfEnabled() {
        try {
            if (!configuration.isAfterLaunchEnabled()) {
                return;
            }
            if (!browserLaunchTriggered.compareAndSet(false, true)) {
                return;
            }

            // Single source of truth: configuration.getBrowserUrl() returns either the
            // user-customised URL verbatim or a live-computed auto URL derived from the
            // config's current httpPort + contextPath. In single-instance mode port
            // resolution at launch was written back to the config (see
            // TomcatCommandLineState.syncResolvedPortsToConfig), so the computed URL
            // already carries the actual listening port.
            //
            // In parallel-run mode we deliberately do NOT write back (instances would
            // race on the shared config). In that case the config port is the seed
            // value and may differ from this instance's runtime httpPort — we still
            // rewrite here as a safety net so the browser opens on the right port for
            // *this* parallel instance.
            String url = rewritePortIfNeeded(configuration.getBrowserUrl(), httpPort);

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

    /**
     * Rewrites the port in a URL to the resolved runtime port.
     * If the URL contains a port that differs from {@code resolvedPort},
     * it is replaced. This handles the case where the saved browser URL
     * references the configured port but Tomcat started on an auto-resolved one.
     */
    @NotNull
    static String rewritePortIfNeeded(@NotNull String url, int resolvedPort) {
        try {
            URI uri = URI.create(url.trim());
            // Host-gated rewrite. The safety net exists for the parallel-run case
            // where the config URL's port is the user's seed value but THIS
            // instance bound a different port — rewriting lets the browser reach
            // this specific Tomcat. That intent only applies when the URL is
            // actually pointing at this Tomcat. A user-customised URL against a
            // proxy / CDN / port-forward ("http://proxy.example.com:9090/...")
            // has its port chosen deliberately as part of their routing — we
            // must not silently mutate it.
            if (!isLoopbackHost(uri.getHost())) {
                return url;
            }
            int currentPort = uri.getPort();
            if (currentPort > 0 && currentPort != resolvedPort) {
                String rewritten = new URI(uri.getScheme(), uri.getUserInfo(),
                        uri.getHost(), resolvedPort,
                        uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
                LOG.info("Browser URL port rewritten: " + currentPort + " → " + resolvedPort);
                return rewritten;
            }
        } catch (Exception e) {
            LOG.debug("Could not parse browser URL for port rewrite: " + url, e);
        }
        return url;
    }

    /**
     * True when {@code host} names the local machine's loopback interface — the
     * only case in which the browser URL's port can be safely rewritten to this
     * Tomcat instance's runtime port.
     *
     * <p>Accepts {@code localhost} (case-insensitive), the IPv4 loopback
     * {@code 127.0.0.1}, and both the short {@code ::1} and expanded
     * {@code 0:0:0:0:0:0:0:1} forms of the IPv6 loopback. {@link URI#getHost()}
     * returns IPv6 literals with their surrounding brackets in Java 17+, so we
     * strip those before comparing.
     */
    private static boolean isLoopbackHost(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String normalized = host;
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equalsIgnoreCase(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
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
                serverStartupDetected.get(), deployedArtifactCount.get(), expectedArtifactCount.get(), errorCount.get(), warningCount.get());
        deploymentLogger.logServerInfo(summary);
        LOG.info(summary);
    }

    public boolean isServerStartupDetected() {
        return serverStartupDetected.get();
    }

    /**
     * True when the handler has fully terminated — the process is gone and no
     * shutdown work is still in flight.
     *
     * <p>Distinct from the raw {@link #isProcessTerminated()} flag, which can
     * briefly read {@code true} while {@link #isProcessTerminating()} is also
     * {@code true} during the shutdown overlap window (custom shutdown script
     * finished but parallel-run base cleanup is still running, etc.). Surface
     * callers that decide "hide the node / proceed with a fresh launch" must
     * use this method rather than the raw flag — otherwise the overlap state
     * bypasses the {@code "Tomcat is shutting down"} branch of the shared gate
     * and reopens the same UX drift the gate was introduced to close.
     *
     * <p>Rule of thumb: when the decision is "should this UI element exist?"
     * use {@code isFullyTerminated()}. When the decision is "is the JVM
     * actually dead for cleanup purposes?" the raw flag is fine.
     */
    public boolean isFullyTerminated() {
        return isProcessTerminated() && !isProcessTerminating();
    }

    /**
     * Returns {@code null} when this handler is ready to accept a restart / update
     * gesture, or a user-facing reason string explaining why it is not.
     *
     * <p>Single source of truth for the startup-gate shared by the toolbar rerun
     * intercept, the Services panel actions, the Ctrl+F10 updater provider, and
     * the frame-deactivation listener. Callers decide how to surface the reason
     * (balloon notification, disabled action tooltip, silent skip) but share the
     * same predicate so the surfaces cannot drift on whether restart is allowed.
     *
     * <p>Delegates to {@link #computeRestartBlockReason(boolean, boolean, boolean)}
     * so the policy itself is independently testable without a live process.
     */
    @Nullable
    public String getRestartBlockReason() {
        return computeRestartBlockReason(
                isProcessTerminating(), isProcessTerminated(), serverStartupDetected.get());
    }

    /**
     * Pure-logic companion of {@link #getRestartBlockReason()}. Returns the user-facing
     * block reason for a handler in the given state, or {@code null} when restart is
     * allowed. Exposed package-private so unit tests can exercise every branch without
     * constructing a live {@link KillableColoredProcessHandler}.
     *
     * <p>Messages are phrased for direct display in tooltips and balloons — short,
     * no trailing punctuation, referencing Tomcat explicitly so a Services-panel
     * tooltip is unambiguous about which process is meant.
     *
     * <p>Precedence when multiple flags are set: {@code terminating} wins over
     * {@code terminated} (a handler briefly reports both during shutdown; shutdown
     * is the more actionable message) which wins over {@code !startupDetected}.
     */
    @Nullable
    static String computeRestartBlockReason(boolean terminating,
                                             boolean terminated,
                                             boolean startupDetected) {
        if (terminating) {
            return "Tomcat is shutting down";
        }
        if (terminated) {
            return "Tomcat is not running";
        }
        if (!startupDetected) {
            return "Tomcat is still starting — restart will be available once startup completes";
        }
        return null;
    }

    public boolean isDeploymentCompleted() {
        return deployedArtifactCount.get() >= expectedArtifactCount.get();
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

    /**
     * Returns the per-launch identifier that isolates this instance's
     * {@code CATALINA_BASE} when "Allow parallel run" was active. {@code null}
     * when the handler launched under the shared per-config base.
     */
    @Nullable
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the actual HTTP port this process was launched on.
     * May differ from {@code configuration.getHttpPort()} when the configured
     * port was unavailable and auto-resolved (e.g. 8080 → 8082).
     */
    public int getHttpPort() {
        return httpPort;
    }

    /**
     * Returns the {@link RunnerAndConfigurationSettings} that launched this process,
     * or {@code null} for a process launched outside the normal editor flow.
     * This is the stable identity across cross-executor switches and renames.
     */
    @Nullable
    public RunnerAndConfigurationSettings getLaunchSettings() {
        return launchSettings;
    }

    /**
     * Returns the resolved {@link PortConfig} this process was launched with
     * (post-conflict-detection), or {@code null} if ports were not pre-resolved.
     * Used to carry the same ports across a cross-executor relaunch so the OS's
     * {@code TIME_WAIT} state doesn't force a different port on the new launch.
     */
    @Nullable
    public PortConfig getResolvedPorts() {
        return resolvedPorts;
    }

    /**
     * Returns the JDWP port this process was launched on (post-conflict-resolution),
     * or {@code -1} if the handler is not attached under the debug executor.
     */
    public int getResolvedDebugPort() {
        return resolvedDebugPort;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
