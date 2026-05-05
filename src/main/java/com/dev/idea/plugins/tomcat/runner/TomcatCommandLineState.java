package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;

import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the Tomcat process command line and manages process lifecycle.
 * Delegates parameter building to {@link TomcatJavaParametersBuilder}.
 */
public class TomcatCommandLineState extends JavaCommandLineState {

    private static final Logger LOG = Logger.getInstance(TomcatCommandLineState.class);

    /**
     * Carries the resolved {@link PortConfig} from a stopped process into its
     * cross-executor relaunch, so the same ports are reused instead of
     * re-running conflict detection (which would see the OS's {@code TIME_WAIT}
     * socket state and pick a different port).
     */
    public static final Key<PortConfig> CARRIED_PORTS_KEY =
            Key.create("devtomcat.carried.resolved.ports");
    /** Debug JDWP port carried across a cross-executor relaunch. */
    public static final Key<Integer> CARRIED_DEBUG_PORT_KEY =
            Key.create("devtomcat.carried.resolved.debug.port");

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private volatile PortConfig resolvedPorts;
    private volatile int resolvedDebugPort = -1;
    /**
     * Per-launch ID assigner. Holds the single source of truth for "what runId
     * does this launch use" — see {@link RunIdAssigner} for the parallel-run
     * effectiveness rules and the warning-once guarantees.
     */
    private final RunIdAssigner runIdAssigner;
    private final AtomicBoolean preLaunchDone = new AtomicBoolean(false);

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
        this.runIdAssigner = new RunIdAssigner(configuration, environment, deploymentLogger);
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        // Ensure pre-launch setup runs exactly once, even if the framework
        // calls getJavaParameters()/createJavaParameters() before startProcess()
        ensurePreLaunchSetup();

        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        try {
            TomcatJavaParametersBuilder builder = new TomcatJavaParametersBuilder(configuration, getEnvironment())
                    .setDebugMode(isDebug)
                    .setDeploymentLogger(deploymentLogger)
                    .setRunId(resolveRunId());
            if (resolvedPorts != null) {
                builder.setResolvedPorts(resolvedPorts);
            }
            if (resolvedDebugPort > 0) {
                builder.setResolvedDebugPort(resolvedDebugPort);
            }
            return builder.build();
        } catch (ExecutionException | RuntimeException e) {
            // Release ports claimed by ensurePreLaunchSetup() since the process
            // will never start and processTerminated() will never fire.
            // releaseAllFor() is idempotent, so double-release from startProcess() is safe.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(configuration.getName());
            if (e instanceof ExecutionException) throw (ExecutionException) e;
            throw new ExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Runs compatibility checks, port conflict detection, and credential resolution
     * exactly once, guarded by {@link #preLaunchDone}. Called from both
     * {@link #createJavaParameters()} and {@link #startProcess()} to handle
     * IntelliJ framework calling {@code getJavaParameters()} before {@code startProcess()}.
     */
    private void ensurePreLaunchSetup() throws ExecutionException {
        if (!preLaunchDone.compareAndSet(false, true)) return;

        // Fail fast on unregistered server — before port conflict detection,
        // compatibility checks, or any other work that fires user-visible
        // notifications. Otherwise the user sees "Port Auto-Resolved" balloons
        // and only afterwards the real error ("not registered"), which makes
        // the registration problem look secondary. This is the same gate as
        // TomcatJavaParametersBuilder.getCatalinaHome() but it runs before
        // side-effectful setup. Remote mode is exempt because there's no local
        // Tomcat install to register.
        if (!configuration.isRemoteMode()) {
            requireRegisteredTomcatServer();
            // Kill any orphan Tomcats left over from prior runs of THIS config so
            // their ports free up before the port-conflict detector sees them.
            // Without this, a zombie on the seed port pushes us onto the next free
            // port, and the user sees the dialog's seed permanently disagree with
            // the Services panel's actually-bound port. Runs after the registration
            // gate so we don't waste cycles scanning for a launch that's about to
            // fail anyway.
            reclaimOrphanTomcats();
        }

        checkCompatibility();
        runPreflightValidation();
        warnIfManualJdwpInDebugMode();
        // Port conflict detection is only meaningful for local mode — remote ports
        // are on the remote machine and not claimable or detectable from here.
        boolean portsReserved = false;
        try {
            if (!configuration.isRemoteMode()) {
                resolvePortConflicts();
                portsReserved = true;
            }

            DeploymentStrategy.create(configuration).resolveCredentials(configuration);

            if (configuration.isRemoteMode()) {
                RemoteConfig rc = configuration.getConfigData().getRemoteConfig();
                if (rc != null && rc.isUseCredentials() && rc.getPassword().isEmpty()) {
                    throw new ExecutionException(
                            "Remote deployment requires credentials but no password was found. " +
                            "Configure credentials in the Remote tab or store them in PasswordSafe.");
                }
            }
        } catch (ExecutionException e) {
            // Release any ports we already claimed — processTerminated() won't fire
            // because the process was never started.
            if (portsReserved) {
                TomcatPortRegistry.getInstance()
                        .releaseAllFor(configuration.getName());
            }
            throw e;
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
        if (configuration.isRemoteMode()) return;

        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        if (CatalinaScriptSupport.hasManualJdwpAgent(vmOptions)) {
            deploymentLogger.logServerWarning(
                    "Manual -agentlib:jdwp detected in VM options. " +
                    "In Debug mode, the IDE injects its own JDWP agent automatically. " +
                    "Having two agents causes a port mismatch. Remove the manual one " +
                    "from VM options, or switch to Run mode if you want manual JDWP control.");
            notifyUser("DevTomcat: Duplicate JDWP Agent",
                    "Remove -agentlib:jdwp from VM options when using Debug mode.\n" +
                    "The IDE injects its own agent automatically.",
                    NotificationType.WARNING);
        }
    }

    // Static command-line / JDWP / JPDA helpers moved to {@link CatalinaScriptSupport}.
    // The remaining instance pipeline below delegates to that class via fully-qualified
    // calls in startProcess() and warnIfManualJdwpInDebugMode().

    /**
     * Resolve and atomically claim all ports for this launch, then store the
     * results in this state's instance fields. Delegates to
     * {@link LaunchPortClaimer}; see that class for the full carry-over /
     * conflict-detection / writeback contract. The fields it sets
     * ({@link #resolvedPorts}, {@link #resolvedDebugPort}) are read by
     * {@link #createJavaParameters()} and the process handler.
     */
    private void resolvePortConflicts() {
        LaunchPortClaimer.Resolution result =
                new LaunchPortClaimer(configuration, getEnvironment(), deploymentLogger).claim();
        this.resolvedPorts = result.ports();
        if (result.hasDebugPort()) {
            this.resolvedDebugPort = result.debugPort();
        }
    }

    /**
     * Returns the resolved debug port after conflict detection, or -1 if not in debug mode.
     */
    public int getResolvedDebugPort() {
        return resolvedDebugPort;
    }

    /**
     * Kill orphan Tomcat processes left over from prior launches of this
     * configuration before port-conflict detection runs. Delegates to
     * {@link OrphanTomcatReclaimer} — see that class for the full identification
     * contract (including the boundary-aware matcher that prevents same-prefix
     * config-name collisions) and the polite/grace/force termination strategy.
     */
    private void reclaimOrphanTomcats() {
        new OrphanTomcatReclaimer(configuration, deploymentLogger).reclaim();
    }

    /**
     * Early registration gate that runs before any side-effectful pre-launch
     * work (port conflict detection, compatibility check, preflight). When the
     * run configuration references a Tomcat that isn't registered, we throw
     * immediately with the same wording
     * {@link TomcatJavaParametersBuilder#getCatalinaHome()} uses — without this
     * the user sees port auto-resolve balloons before the real "not registered"
     * error, making it look like port resolution succeeded and only the launch
     * failed.
     *
     * <p>If the resolver reconciles via ID/path/name drift, the config's
     * {@link TomcatInfo} reference is upgraded to the canonical registered
     * instance so downstream code (compatibility check, builder) reads a
     * consistent, already-resolved value.
     */
    private void requireRegisteredTomcatServer() throws ExecutionException {
        TomcatInfo persisted = configuration.getConfigData().getTomcatInfo();
        if (persisted == null) {
            throw new ExecutionException("No Tomcat server configured."
                    + " Open the run configuration and select a server from Application Servers.");
        }
        TomcatInfo resolved = com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState
                .getInstance().resolve(persisted);
        if (resolved == null) {
            String name = !persisted.getName().isEmpty() ? persisted.getName() : "(unnamed)";
            String path = persisted.getPath();
            throw new ExecutionException("Tomcat server '" + name + "' is not registered."
                    + " Persisted path: " + (path.isEmpty() ? "(empty)" : path) + "."
                    + " Open the run configuration and select a registered server,"
                    + " or add one via Configure.");
        }
        if (resolved != persisted) {
            LOG.info("Pre-launch reconciled drifted persisted reference"
                    + " (id=" + persisted.getId() + ", path=" + persisted.getPath() + ")"
                    + " to registered server (id=" + resolved.getId()
                    + ", path=" + resolved.getPath() + ")");
            configuration.getConfigData().setTomcatInfo(resolved);
        }
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

    /**
     * Resolve the per-launch run ID for parallel-run mode. Idempotent —
     * subsequent calls return the same value within a single launch.
     * Delegates to {@link RunIdAssigner}; see that class for the
     * parallel-run-effective predicate, the pinned-base guard, and the
     * warning-once policy.
     */
    @Nullable
    private String resolveRunId() {
        return runIdAssigner.resolve();
    }

    /**
     * Realign plugin-managed log paths so the IDE's {@code RunContentBuilder}
     * creates Log tabs that point at files this launch actually writes to.
     * Delegates to {@link LogFilePathAligner} — see that class for the full
     * filename-matching contract and the parallel-run vs single-instance
     * directory selection.
     */
    private void alignLogFilePathsWithRuntimeBase() {
        new LogFilePathAligner(configuration).align(runIdAssigner.resolve());
    }

    private void notifyUser(@NotNull String title, @NotNull String content, @NotNull NotificationType type) {
        TomcatNotifier.notify(configuration.getProject(), title, content, type);
    }

    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        // Ensure pre-launch setup (compatibility, ports, credentials) runs exactly once.
        // This may already have been called by createJavaParameters() if the framework
        // invoked getJavaParameters() before startProcess().
        //
        // IMPORTANT: wrap the entire method body so that if anything fails after ports
        // are claimed (resolvePortConflicts), we release them here. processTerminated()
        // is only called if we successfully return a handler — if we throw, it never fires.
        try {
        ensurePreLaunchSetup();

        String executorId = getEnvironment().getExecutor().getId();
        RunnerSettings runnerSettings = configuration.getConfigData().getRunnerSettings(executorId);

        GeneralCommandLine commandLine;
        if (!runnerSettings.isUseDefaultStartup() && !StringUtil.isEmptyOrSpaces(runnerSettings.getStartupScript())) {
            List<String> tokens = ParametersListUtil.parse(
                    runnerSettings.getStartupScript());
            boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(executorId);
            if (isDebug) {
                tokens = CatalinaScriptSupport.enableCatalinaJpda(tokens);
            }
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
            if (isDebug) {
                DebugConfig dc = configuration.getConfigData().getDebugConfig();
                int debugPort = resolvedDebugPort > 0 ? resolvedDebugPort
                        : (dc != null ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT);
                CatalinaScriptSupport.applyCustomScriptDebugSupport(commandLine, tokens, debugPort);
                deploymentLogger.logServerInfo(
                        "Debug mode with custom startup: JDWP injected via environment variables"
                                + (CatalinaScriptSupport.isCatalinaCommand(tokens) ? " and catalina jpda mode" : ""));
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

        // Realign plugin-managed LogFileOptions paths with the ACTUAL catalina.base
        // this launch will use. Without this, parallel runs (whose base is
        // <config>/.runs/<runId>/) have their log tabs pointing at the shared
        // <config>/logs/ directory that never gets written to, so "Logs" never
        // shows up in the Services panel. Single-instance mode also benefits
        // because a stale .runs/<id>/ path left over from a prior parallel run
        // gets realigned back to the config-level logs dir.
        alignLogFilePathsWithRuntimeBase();

        Process process = commandLine.createProcess();

        TomcatProcessHandler handler = new TomcatProcessHandler(
                process,
                commandLine.getCommandLineString(),
                StandardCharsets.UTF_8,
                deploymentLogger,
                configuration,
                runnerSettings,
                resolvedPorts,
                resolvedDebugPort,
                executorId,
                getEnvironment().getRunnerAndConfigurationSettings(),
                resolveRunId()
        );
        ProcessTerminatedListener.attach(handler);
        return handler;
        } catch (ExecutionException | RuntimeException e) {
            // Release any ports claimed during resolvePortConflicts() since
            // processTerminated() will never be called if we don't return a handler.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(configuration.getName());
            if (e instanceof ExecutionException) throw (ExecutionException) e;
            throw new ExecutionException(e.getMessage(), e);
        }
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
