package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;

import com.dev.idea.plugins.tomcat.utils.PortConflictDetector;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the Tomcat process command line and manages process lifecycle.
 * Delegates parameter building to {@link TomcatJavaParametersBuilder}.
 */
public class TomcatCommandLineState extends JavaCommandLineState {

    private static final Logger LOG = Logger.getInstance(TomcatCommandLineState.class);

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private volatile PortConfig resolvedPorts;
    private volatile int resolvedDebugPort = -1;
    private final java.util.concurrent.atomic.AtomicBoolean preLaunchDone = new java.util.concurrent.atomic.AtomicBoolean(false);

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        // Ensure pre-launch setup runs exactly once, even if the framework
        // calls getJavaParameters()/createJavaParameters() before startProcess()
        ensurePreLaunchSetup();

        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        TomcatJavaParametersBuilder builder = new TomcatJavaParametersBuilder(configuration, getEnvironment())
                .setDebugMode(isDebug)
                .setDeploymentLogger(deploymentLogger);
        if (resolvedPorts != null) {
            builder.setResolvedPorts(resolvedPorts);
        }
        if (resolvedDebugPort > 0) {
            builder.setResolvedDebugPort(resolvedDebugPort);
        }
        return builder.build();
    }

    /**
     * Runs compatibility checks, port conflict detection, and credential resolution
     * exactly once, guarded by {@link #preLaunchDone}. Called from both
     * {@link #createJavaParameters()} and {@link #startProcess()} to handle
     * IntelliJ framework calling {@code getJavaParameters()} before {@code startProcess()}.
     */
    private void ensurePreLaunchSetup() throws ExecutionException {
        if (!preLaunchDone.compareAndSet(false, true)) return;

        checkCompatibility();
        runPreflightValidation();
        warnIfManualJdwpInDebugMode();
        resolvePortConflicts();

        DeploymentStrategy.create(configuration).resolveCredentials(configuration);

        if (TomcatConstants.MODE_REMOTE.equals(configuration.getConfigData().getServerMode())) {
            RemoteConfig rc = configuration.getConfigData().getRemoteConfig();
            if (rc != null && rc.isUseCredentials() && rc.getPassword().isEmpty()) {
                throw new ExecutionException(
                        "Remote deployment requires credentials but no password was found. " +
                        "Configure credentials in the Remote tab or store them in PasswordSafe.");
            }
        }
    }

    /**
     * Warns if the user manually added {@code -agentlib:jdwp} in VM options while
     * launching in Debug mode. GenericDebuggerRunner injects its own JDWP agent,
     * so a manual one creates a duplicate — the JVM assigns the second agent a
     * different port, causing the debugger to connect to the wrong one.
     */
    private void warnIfManualJdwpInDebugMode() {
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        if (!isDebug) return;

        // Only warn for local debug — in remote mode the user must supply their
        // own JDWP agent on the remote JVM, so a manual -agentlib:jdwp is expected.
        if (TomcatConstants.MODE_REMOTE.equals(configuration.getConfigData().getServerMode())) return;

        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        if (hasManualJdwpAgent(vmOptions)) {
            deploymentLogger.logServerWarning(
                    "Manual -agentlib:jdwp detected in VM options. " +
                    "In Debug mode, the IDE injects its own JDWP agent automatically. " +
                    "Having two agents causes a port mismatch — remove the manual one " +
                    "from VM options, or switch to Run mode if you want manual JDWP control.");
            notifyUser("DevTomcat: Duplicate JDWP Agent",
                    "Remove -agentlib:jdwp from VM options when using Debug mode.\n" +
                    "The IDE injects its own agent automatically.",
                    NotificationType.WARNING);
        }
    }

    /**
     * Returns true if the given VM options string contains a manual JDWP agent argument.
     * Matches {@code -agentlib:jdwp} followed by {@code =} or end-of-string/whitespace,
     * avoiding false positives on unrelated agents like {@code -agentlib:jdwp_other}.
     * Package-visible for testing.
     */
    static boolean hasManualJdwpAgent(@Nullable String vmOptions) {
        if (vmOptions == null) return false;
        int idx = vmOptions.indexOf("-agentlib:jdwp");
        if (idx < 0) return false;
        int end = idx + "-agentlib:jdwp".length();
        // Must be followed by '=', whitespace, or end of string
        return end >= vmOptions.length()
                || vmOptions.charAt(end) == '='
                || Character.isWhitespace(vmOptions.charAt(end));
    }

    /**
     * Detects port conflicts before launch and auto-resolves them.
     *
     * <p>Uses {@link com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry} to
     * atomically claim all ports (Tomcat + JDWP) before the process starts.
     * This closes the race window where two configurations start simultaneously,
     * both observe a port as free before either JVM has bound to it, and both
     * end up on the same port.
     *
     * <p>The resolved ports are stored in {@link #resolvedPorts} and used
     * by the builder via {@link #createJavaParameters()}. All claimed ports
     * are released automatically when the process terminates.
     */
    private void resolvePortConflicts() {
        PortConfig originalPorts = configuration.getConfigData().getPortConfig();
        String configName = configuration.getName();
        com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry registry =
                com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.getInstance();

        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());

        if (isDebug) {
            var debugConfig = configuration.getConfigData().getDebugConfig();
            int debugPort = debugConfig != null ? debugConfig.getPort()
                    : com.dev.idea.plugins.tomcat.model.debug.DebugConfig.DEFAULT_DEBUG_PORT;

            PortConflictDetector.DebugPortResolution resolution =
                    PortConflictDetector.resolveConflictsWithDebug(originalPorts, debugPort);

            // Atomically claim all resolved ports through the registry to prevent
            // a second concurrent launcher from grabbing the same ports
            PortConfig rp = resolution.getResolvedConfig();
            rp.setHttp(registry.claimPort(rp.getHttp(), configName));
            rp.setShutdown(registry.claimPort(rp.getShutdown(), configName));
            if (rp.isHttpsEnabled()) rp.setHttps(registry.claimPort(rp.getHttps(), configName));
            if (rp.isJmxEnabled())   rp.setJmx(registry.claimPort(rp.getJmx(), configName));
            if (rp.isAjpEnabled())   rp.setAjp(registry.claimPort(rp.getAjp(), configName));
            this.resolvedDebugPort = registry.claimPort(resolution.getDebugPort(), configName);
            this.resolvedPorts = rp;
            logResolutionChanges(resolution.getChanges());
        } else {
            PortConflictDetector.PortResolution resolution =
                    PortConflictDetector.resolveConflicts(originalPorts);

            PortConfig rp = resolution.getResolvedConfig();
            rp.setHttp(registry.claimPort(rp.getHttp(), configName));
            rp.setShutdown(registry.claimPort(rp.getShutdown(), configName));
            if (rp.isHttpsEnabled()) rp.setHttps(registry.claimPort(rp.getHttps(), configName));
            if (rp.isJmxEnabled())   rp.setJmx(registry.claimPort(rp.getJmx(), configName));
            if (rp.isAjpEnabled())   rp.setAjp(registry.claimPort(rp.getAjp(), configName));
            this.resolvedPorts = rp;
            logResolutionChanges(resolution.getChanges());
        }
    }

    private void logResolutionChanges(@NotNull java.util.List<String> changes) {
        if (!changes.isEmpty()) {
            deploymentLogger.logServerWarning("Port conflicts detected and auto-resolved:");
            for (String change : changes) {
                deploymentLogger.logServerWarning("  " + change);
            }
            notifyUser("DevTomcat: Port Auto-Resolved",
                    String.join("\n", changes),
                    NotificationType.WARNING);
        }
    }

    /**
     * Returns the resolved debug port after conflict detection, or -1 if not in debug mode.
     */
    public int getResolvedDebugPort() {
        return resolvedDebugPort;
    }

    /**
     * Runs preflight validation to catch common failures before Tomcat starts:
     * missing path-based system properties, duplicate JARs in deployed artifacts,
     * and locked cache/temp directories.
     */
    private void runPreflightValidation() throws ExecutionException {
        TomcatPreflightValidator.PreflightResult result =
                TomcatPreflightValidator.validate(configuration);

        if (!result.hasIssues()) return;

        for (TomcatPreflightValidator.PreflightIssue issue : result.getWarnings()) {
            deploymentLogger.logServerWarning("Preflight: " + issue.getMessage());
        }
        for (TomcatPreflightValidator.PreflightIssue issue : result.getBlockingIssues()) {
            deploymentLogger.logServerError("Preflight: " + issue.getMessage());
        }

        if (result.hasBlockingIssues()) {
            throw new ExecutionException("Preflight check failed: " + result.getBlockingMessage());
        }
    }

    /**
     * Validates Tomcat/JDK compatibility before launch.
     * Blocks launch on critical mismatches (e.g., Tomcat 11 with Java 11),
     * warns on non-blocking issues (e.g., Jakarta namespace).
     */
    private void checkCompatibility() throws ExecutionException {
        TomcatInfo tomcatInfo = configuration.getTomcatInfo();
        Sdk jdk = resolveJdk();

        List<TomcatCompatibilityChecker.CompatibilityIssue> issues =
                TomcatCompatibilityChecker.check(tomcatInfo, jdk);

        for (TomcatCompatibilityChecker.CompatibilityIssue issue : issues) {
            if (issue.isBlocking()) {
                deploymentLogger.logServerError(issue.getMessage());
            } else {
                deploymentLogger.logServerWarning(issue.getMessage());
            }
        }

        if (TomcatCompatibilityChecker.hasBlockingIssues(issues)) {
            throw new ExecutionException(
                    "Compatibility check failed: " + issues.stream()
                            .filter(TomcatCompatibilityChecker.CompatibilityIssue::isBlocking)
                            .map(TomcatCompatibilityChecker.CompatibilityIssue::getMessage)
                            .findFirst().orElse("Unknown issue"));
        }
    }

    @Nullable
    private Sdk resolveJdk() {
        return TomcatJavaParametersBuilder.resolveJdkOrNull(configuration, configuration.getProject());
    }

    private void notifyUser(@NotNull String title, @NotNull String content, @NotNull NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(TomcatConstants.NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type)
                    .notify(configuration.getProject());
        } catch (Exception e) {
            // Notification group may not be registered — fall back silently
            LOG.debug("Could not show notification: " + e.getMessage());
        }
    }

    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        // Ensure pre-launch setup (compatibility, ports, credentials) runs exactly once.
        // This may already have been called by createJavaParameters() if the framework
        // invoked getJavaParameters() before startProcess().
        ensurePreLaunchSetup();

        String executorId = getEnvironment().getExecutor().getId();
        RunnerSettings runnerSettings = configuration.getConfigData().getRunnerSettings(executorId);

        GeneralCommandLine commandLine;
        if (!runnerSettings.isUseDefaultStartup() && !StringUtil.isEmptyOrSpaces(runnerSettings.getStartupScript())) {
            List<String> tokens = ParametersListUtil.parse(
                    runnerSettings.getStartupScript());
            commandLine = new GeneralCommandLine(tokens);
            commandLine.withEnvironment(runnerSettings.getEnvironmentVariables());
            commandLine.withParentEnvironmentType(runnerSettings.isPassParentEnvs() ?
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE);

            // Propagate resolved ports as environment variables so custom scripts can use them
            if (resolvedPorts != null) {
                commandLine.withEnvironment(TomcatConstants.ENV_HTTP_PORT, String.valueOf(resolvedPorts.getHttp()));
                commandLine.withEnvironment(TomcatConstants.ENV_SHUTDOWN_PORT, String.valueOf(resolvedPorts.getShutdown()));
                commandLine.withEnvironment(TomcatConstants.ENV_HTTPS_PORT, String.valueOf(resolvedPorts.getHttps()));
                commandLine.withEnvironment(TomcatConstants.ENV_JMX_PORT, String.valueOf(resolvedPorts.getJmx()));
                commandLine.withEnvironment(TomcatConstants.ENV_AJP_PORT, String.valueOf(resolvedPorts.getAjp()));
            }

            // In debug mode, propagate the JDWP agent arg and port so custom scripts
            // can include them. Without this, debug + custom script silently fails to attach.
            boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(executorId);
            if (isDebug) {
                int debugPort = resolvedDebugPort > 0 ? resolvedDebugPort
                        : (configuration.getConfigData().getDebugConfig() != null
                            ? configuration.getConfigData().getDebugConfig().getPort()
                            : com.dev.idea.plugins.tomcat.model.debug.DebugConfig.DEFAULT_DEBUG_PORT);
                commandLine.withEnvironment(TomcatConstants.ENV_DEBUG_PORT, String.valueOf(debugPort));
                String jdwpArg = TomcatConstants.JDWP_AGENT_PREFIX
                        + String.format(TomcatConstants.JDWP_CONNECTION_FORMAT,
                            TomcatConstants.JDWP_TRANSPORT_SOCKET, debugPort);
                commandLine.withEnvironment(TomcatConstants.ENV_JDWP_OPTS, jdwpArg);
                deploymentLogger.logServerInfo("Debug mode with custom script: add $TOMCAT_JDWP_OPTS to your CATALINA_OPTS or JAVA_OPTS");
            }

            if (configuration.getTomcatInfo() != null) {
                commandLine.withWorkDirectory(configuration.getTomcatInfo().getPath());
            }
        } else {
            if (!runnerSettings.isUseDefaultStartup()) {
                LOG.warn("Custom startup enabled but no script configured, falling back to default startup");
            }
            commandLine = getJavaParameters().toCommandLine();
        }
        
        // Re-sync log files after catalina.base is prepared (log files now exist on disk)
        // so RunContentBuilder creates tabs for them
        configuration.syncTomcatLogFiles();

        Process process = commandLine.createProcess();

        TomcatProcessHandler handler = new TomcatProcessHandler(
                process,
                commandLine.getCommandLineString(),
                StandardCharsets.UTF_8,
                deploymentLogger,
                configuration,
                runnerSettings,
                resolvedPorts,
                executorId
        );
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        ConsoleView console = super.createConsole(executor);
        if (console == null) {
            console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(getEnvironment().getProject())
                    .getConsole();
        }
        if (console != null) {
            deploymentLogger.setConsoleView(console);
        }
        return console;
    }

    @NotNull
    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }
}
