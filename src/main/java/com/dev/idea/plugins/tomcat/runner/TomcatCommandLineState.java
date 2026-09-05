package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;

import com.dev.idea.plugins.tomcat.utils.LaunchPathMapper;
import com.dev.idea.plugins.tomcat.utils.PhaseTimings;
import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
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
import com.intellij.openapi.project.Project;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.filters.ExceptionFilter;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
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
    /** Decided once in pre-launch setup; {@code null} for an ordinary host launch. */
    private volatile WslLaunchMode wslMode;

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
        this.runIdAssigner = new RunIdAssigner(configuration, environment, deploymentLogger);
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        try {
            // Ensure pre-launch setup runs exactly once, even if the framework
            // calls getJavaParameters()/createJavaParameters() before startProcess().
            // Inside the try so a STRICT/RECLAIM refusal (IllegalStateException from
            // LaunchPortClaimer) releases ports that claimAndTrack already reserved.
            long prepStart = System.nanoTime();
            ensurePreLaunchSetup();

            WslLaunchMode wsl = wslMode;
            TomcatJavaParametersBuilder builder = new TomcatJavaParametersBuilder(configuration, getEnvironment())
                    .setDebugMode(isDebug)
                    .setDeploymentLogger(deploymentLogger)
                    .setRunId(resolveRunId())
                    .setPathMapper(wsl != null ? wsl.mapper() : LaunchPathMapper.IDENTITY);
            if (resolvedPorts != null) {
                builder.setResolvedPorts(resolvedPorts);
            }
            if (resolvedDebugPort > 0) {
                builder.setResolvedDebugPort(resolvedDebugPort);
            }
            JavaParameters params = builder.build();
            if (wsl != null) {
                // Once per launch: the parameters are built exactly once.
                if (params.getWorkingDirectory() != null) {
                    deploymentLogger.logServerWarning(wsl.consoleMessage(params.getWorkingDirectory()));
                }
                logCrossDistroWarnings(wsl);
            }
            // One-line launch-prep total: this is the time spent under the
            // runner's pre-launch modal (port claim, catalina.base prep,
            // class/web sync, artifact deployment), so slow launches are
            // diagnosable from the run console instead of guesswork.
            deploymentLogger.logServerInfo("Launch preparation finished in "
                    + (System.nanoTime() - prepStart) / 1_000_000 + " ms");
            return params;
        } catch (ProcessCanceledException e) {
            // The user pressed Cancel on the launch-preparation progress (the
            // platform computes these parameters under its patch-parameters
            // modal, and the sync/deploy loops poll the indicator). Release
            // claimed ports and let the cancellation propagate unchanged —
            // wrapping it below would turn a deliberate cancel into an error
            // dialog.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.ownerKey(
                            configuration.getProject(), configuration.getName()));
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            // Release ports claimed by ensurePreLaunchSetup() since the process
            // will never start and processTerminated() will never fire.
            // releaseAllFor() is idempotent, so double-release from startProcess() is safe.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.ownerKey(
                            configuration.getProject(), configuration.getName()));
            if (e instanceof ExecutionException) throw (ExecutionException) e;
            // Wrap with phase + exception class so the user sees WHERE the
            // failure occurred even when the cause's message is null (common
            // for NullPointerException / IllegalStateException without a
            // message constructor arg). Without this, the run console shows
            // a bare "ExecutionException: null" with no signal whatsoever.
            throw new ExecutionException(
                    "Could not build Java command line for Tomcat: "
                            + describeFailure(e),
                    e);
        }
    }

    /**
     * Produces a "{ExceptionClass}: {message}" string that is never empty.
     * Falls back to the exception's simple class name when {@code getMessage()}
     * is null — the bare class name is more useful than the literal string
     * "null" when surfaced to the user via the run console.
     */
    @NotNull
    static String describeFailure(@NotNull Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + msg;
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
        // side-effectful setup.
        //
        // Note: this state is constructed only for local-mode configurations
        // (TomcatRunConfiguration.getState branches on isRemoteMode and routes
        // remote configs to RemoteDeploymentRunProfileState). The previous
        // isRemoteMode() guards in this method are therefore unreachable and
        // have been removed.
        requireRegisteredTomcatServer();
        // WSL mode is decided here, before any side effect, so its guards fail
        // the launch without leaving claimed ports or balloons behind.
        resolveWslMode();
        // Kill any orphan Tomcats left over from prior runs of THIS config so
        // their ports free up before the port-conflict detector sees them.
        // Without this, a zombie on the seed port pushes us onto the next free
        // port, and the user sees the dialog's seed permanently disagree with
        // the Services panel's actually-bound port. Runs after the registration
        // gate so we don't waste cycles scanning for a launch that's about to
        // fail anyway.
        PhaseTimings checks = new PhaseTimings();
        long t = System.nanoTime();
        reclaimOrphanTomcats();
        checks.record("orphan reclaim", t);

        t = System.nanoTime();
        checkCompatibility();
        checks.record("compatibility", t);
        t = System.nanoTime();
        runPreflightValidation();
        checks.record("preflight", t);
        warnIfManualJdwpInDebugMode();

        // Both calls below are non-throwing (LaunchPortClaimer.claim() returns a
        // Resolution, LocalDeploymentStrategy.resolveCredentials is a no-op), so
        // there is no ExecutionException to catch and no ports to release here.
        // Failures during port-claim turn into LaunchPortClaimer's own balloon /
        // unrecoverable-state surfaces.
        t = System.nanoTime();
        resolvePortConflicts();
        checks.record("port conflicts", t);
        new LocalDeploymentStrategy().resolveCredentials(configuration);
        // Where the pre-launch time went; the builder prints the same for preparation.
        LOG.info("Pre-launch checks: " + checks.summary());
        deploymentLogger.logServerInfo("Pre-launch checks: " + checks.summary());
    }

    /**
     * Warns if the user manually added {@code -agentlib:jdwp} in VM options while
     * launching in Debug mode. GenericDebuggerRunner injects its own JDWP agent,
     * so a manual one creates a duplicate — the JVM assigns the second agent a
     * different port, causing the debugger to connect to the wrong one.
     *
     * <p>Only relevant for local mode: this state is constructed exclusively
     * for local-mode configurations, so no remote-mode guard is needed.
     */
    private void warnIfManualJdwpInDebugMode() {
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        if (!isDebug) return;

        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        if (CatalinaScriptSupport.hasManualJdwpAgent(vmOptions)) {
            deploymentLogger.logServerWarning(
                    "Manual -agentlib:jdwp detected in VM options. " +
                    "In Debug mode, the IDE injects its own JDWP agent automatically. " +
                    "Having two agents causes a port mismatch. Remove the manual one " +
                    "from VM options, or switch to Run mode if you want manual JDWP control.");
            // Console-only — the warning above is enough; a balloon used to
            // fire here every debug launch with manual JDWP, which is too
            // repetitive for what is fundamentally a config-hygiene reminder.
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
     * Decides the experimental WSL launch mode once. A WSL-hosted home whose
     * distribution cannot be resolved fails loudly here (never a Linux command
     * exec'd by a Windows process — "os error 2"); a resolved mode enforces a
     * WSL-side JDK, the default startup and a non-coverage executor, all of
     * which the {@code wsl.exe} wrapping cannot honour otherwise.
     */
    private void resolveWslMode() throws ExecutionException {
        TomcatInfo info = configuration.getTomcatInfo();
        WslLaunchMode mode = info == null ? null : WslLaunchMode.resolve(info.getPath());
        if (mode == null) return;

        mode.requireWslSideJdk(resolveJdk());

        String executorId = getEnvironment().getExecutor().getId();
        RunnerSettings rs = configuration.getConfigData().getRunnerSettings(executorId);
        WslLaunchMode.requireSupportedLaunchShape(rs.isUseDefaultStartup(), rs.getStartupScript(), executorId);
        this.wslMode = mode;
        LOG.info("WSL mode: Tomcat runs inside distribution '" + mode.distroName() + "'");
    }

    /**
     * A path that named a different distribution was still translated, with the
     * distro segment stripped — it now resolves against the launch distribution.
     * Only the user can tell whether that was intended, so the warning belongs in
     * the run console, not just {@code idea.log}.
     */
    private void logCrossDistroWarnings(@NotNull WslLaunchMode wsl) {
        for (String warning : wsl.drainCrossDistroWarnings()) {
            deploymentLogger.logServerWarning(warning);
        }
    }

    /**
     * Runs preflight validation to catch common failures before Tomcat starts:
     * missing path-based system properties, duplicate JARs in deployed artifacts,
     * and locked cache/temp directories.
     */
    private void runPreflightValidation() throws ExecutionException {
        // In WSL mode the JVM's own paths are distro-side; resolving them against
        // the Windows filesystem would report a correct value as missing.
        TomcatPreflightValidator.PreflightResult result =
                TomcatPreflightValidator.validate(configuration, wslMode == null);

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
            String firstBlocking = issues.stream()
                    .filter(TomcatCompatibilityChecker.CompatibilityIssue::isBlocking)
                    .map(TomcatCompatibilityChecker.CompatibilityIssue::getMessage)
                    .findFirst().orElse("Unknown issue");
            // Surface the JDK-mismatch balloon ONLY when the blocking issue
            // is actually about the JDK. Other blocking issues (e.g. "No
            // Tomcat server configured") would get the wrong title and a
            // misleading remediation. Filtering on the "Java" substring
            // covers the two JDK-related blocking messages produced by
            // TomcatCompatibilityChecker ("No JDK configured. Tomcat X
            // requires Java Y+." and "Tomcat X requires Java Y+, but the
            // configured JDK is Java Z") and excludes the no-server case.
            // Console message remains the source of truth for the precise
            // error; the balloon is the actionable companion that persists
            // in the notification panel after the failure modal closes.
            if (firstBlocking.contains("Java")) {
                TomcatCompatibilityPrompt.showJdkMismatchPrompt(
                        configuration.getProject(), configuration, firstBlocking);
            }
            throw new ExecutionException("Compatibility check failed: " + firstBlocking);
        }
    }

    @Nullable
    /**
     * Launch preparation does not hold the IDE read lock.
     *
     * <p>The platform's {@code getJavaParameters()} wraps {@code createJavaParameters()}
     * in a read action unless this says otherwise. Everything DevTomcat does there —
     * process enumeration, socket and lock probes, catalina.base assembly, the
     * class and web syncs, artifact copies, jar scans — is filesystem work that
     * needs no model access, and holding the read lock across it blocked every
     * write action in the IDE (typing, refactoring, VFS commits) for the whole
     * preparation.
     *
     * <p>The contract this relies on: every project-model read on the path takes
     * its own short read action at its boundary — {@code ArtifactBackedDeployment}
     * and {@code ModuleRef} for pointer resolution, the sync pipelines and the
     * strategy's model snapshot for module/root enumeration, and
     * {@code TomcatJavaParametersBuilder#resolveJdkOrNull} for the project SDK.
     * A new model read added to this path must do the same.
     */
    @Override
    protected boolean isReadActionRequired() {
        return false;
    }

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
        WslLaunchMode wsl = wslMode;

        String executorId = getEnvironment().getExecutor().getId();
        RunnerSettings runnerSettings = configuration.getConfigData().getRunnerSettings(executorId);

        GeneralCommandLine commandLine;
        if (!runnerSettings.isUseDefaultStartup() && !StringUtil.isEmptyOrSpaces(runnerSettings.getStartupScript())) {
            // Never reached in WSL mode: resolveWslMode() refuses a custom
            // startup script before any side effect (shell scripts are not
            // wrapped for the distribution in this iteration).
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
                // Pass the resolved JDK so the JDWP agent address syntax matches
                // what that JVM accepts: Java 9+ takes "address=*:port"; Java 8
                // takes the no-host form "address=port" (the wildcard is
                // rejected with TRANSPORT_INIT(510) and the JVM refuses to start).
                Sdk debugJdk = resolveJdk();
                CatalinaScriptSupport.applyCustomScriptDebugSupport(
                        commandLine, tokens, debugPort, debugJdk);
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
            JavaParameters params = getJavaParameters();
            if (wsl != null) {
                // The runner's launcher hook was added after build(); it carries
                // host-only paths the distribution cannot exec.
                WslLaunchMode.neutralizeHostLauncherProxy(params);
            }
            commandLine = params.toCommandLine();
            if (wsl != null) {
                Sdk jdk = params.getJdk();
                String jdkHome = jdk != null ? jdk.getHomePath() : null;
                if (jdkHome == null) {
                    throw new ExecutionException(WslLaunchMode.jdkMessage(wsl.distroName()));
                }
                String workingDir = params.getWorkingDirectory();
                if (workingDir == null) {
                    throw new ExecutionException("Unable to determine catalina.base directory");
                }
                commandLine = wsl.patchCommandLine(commandLine, configuration.getProject(), workingDir, jdkHome);
                logCrossDistroWarnings(wsl);
            }
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
        if (wsl != null) {
            handler.setLaunchPathMapper(wsl.mapper());
            wsl.patchProcessHandler(commandLine, handler);
        }
        return handler;
        } catch (ProcessCanceledException e) {
            // Same cancel-passthrough rationale as createJavaParameters above.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.ownerKey(
                            configuration.getProject(), configuration.getName()));
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            // Release any ports claimed during resolvePortConflicts() since
            // processTerminated() will never be called if we don't return a handler.
            TomcatPortRegistry.getInstance()
                    .releaseAllFor(com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry.ownerKey(
                            configuration.getProject(), configuration.getName()));
            if (e instanceof ExecutionException) throw (ExecutionException) e;
            // Same wrap-with-phase rationale as createJavaParameters above.
            throw new ExecutionException(
                    "Could not start Tomcat process: " + describeFailure(e), e);
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
            // Attach the platform's Java exception filter so stack-trace lines
            // like "at com.foo.Bar.baz(Bar.java:42)" become clickable and jump
            // to the source. JavaCommandLineState does NOT install this by
            // default — convention is for each Java run config (ApplicationConfiguration,
            // JUnitConfiguration, etc.) to attach it explicitly via
            // getConsoleBuilder() or post-create. We do it here because we
            // already override createConsole for the deployment logger, and
            // attaching after the platform's own filter chain keeps the
            // ordering deterministic.
            //
            // Scope is GlobalSearchScope.allScope so users can navigate into
            // framework code (web/ORM libraries, Tomcat itself) — these are
            // the most common destinations in a Tomcat stack trace, not the
            // user's own classes. Restricting to project source would defeat
            // the point.
            Project project = getEnvironment().getProject();
            console.addMessageFilter(new ExceptionFilter(GlobalSearchScope.allScope(project)));
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
