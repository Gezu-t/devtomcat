package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.diagnostics.TomcatCompatibilityChecker;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;

import com.dev.idea.plugins.tomcat.utils.PortConflictDetector;
import com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
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
     * Per-launch identifier assigned when "Allow parallel run" is active so this
     * instance gets an isolated CATALINA_BASE. {@code null} means "shared per-config
     * base" — the historical single-instance behaviour.
     *
     * <p>Assigned once at first {@link #createJavaParameters()} / {@link #startProcess()}
     * and reused by the handler so post-launch consumers (updater, Services panel)
     * resolve the same directory.
     */
    @Nullable private volatile String runId;
    private final AtomicBoolean preLaunchDone = new AtomicBoolean(false);

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
     * <p>Uses {@link TomcatPortRegistry} to
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
        String configName = configuration.getName();
        TomcatPortRegistry registry = TomcatPortRegistry.getInstance();

        // Carryover path: the previous process (stopped by stopAndRelaunch) handed
        // its resolved ports down via ExecutionEnvironment user data. Re-use them
        // atomically instead of re-running conflict detection, which would see the
        // OS's TIME_WAIT socket state on the just-released port and bump it up.
        PortConfig carried = getEnvironment().getUserData(CARRIED_PORTS_KEY);
        if (carried != null) {
            List<String> changes = new java.util.ArrayList<>();
            claimAndTrack(carried, registry, configName, changes);
            this.resolvedPorts = carried;
            syncResolvedPortsToConfig(carried);

            Integer carriedDebug = getEnvironment().getUserData(CARRIED_DEBUG_PORT_KEY);
            if (carriedDebug != null && carriedDebug > 0) {
                int claimed = registry.claimPort(carriedDebug, configName);
                this.resolvedDebugPort = claimed > 0 ? claimed : carriedDebug;
                syncResolvedDebugPortToConfig(this.resolvedDebugPort);
            }
            logResolutionChanges(changes);
            return;
        }

        PortConfig originalPorts = configuration.getConfigData().getPortConfig();
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());

        if (isDebug) {
            var debugConfig = configuration.getConfigData().getDebugConfig();
            int debugPort = debugConfig != null ? debugConfig.getPort()
                    : DebugConfig.DEFAULT_DEBUG_PORT;

            PortConflictDetector.DebugPortResolution resolution =
                    PortConflictDetector.resolveConflictsWithDebug(originalPorts, debugPort);

            // Atomically claim all resolved ports through the registry to prevent
            // a second concurrent launcher from grabbing the same ports
            PortConfig rp = resolution.getResolvedConfig();
            claimAndTrack(rp, registry, configName, resolution.getChanges());
            int preClaimDebug = resolution.getDebugPort();
            this.resolvedDebugPort = registry.claimPort(preClaimDebug, configName);
            if (this.resolvedDebugPort == -1) {
                resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                        + ": all ports in search range exhausted — debugger may fail to attach");
                this.resolvedDebugPort = preClaimDebug; // keep original; JVM will fail with a clear error
            } else if (this.resolvedDebugPort != preClaimDebug) {
                resolution.getChanges().add("Debug (JDWP) port " + preClaimDebug
                        + " claimed by a concurrent instance, resolved to " + this.resolvedDebugPort);
            }
            this.resolvedPorts = rp;
            syncResolvedPortsToConfig(rp);
            syncResolvedDebugPortToConfig(this.resolvedDebugPort);
            logResolutionChanges(resolution.getChanges());
        } else {
            PortConflictDetector.PortResolution resolution =
                    PortConflictDetector.resolveConflicts(originalPorts);

            PortConfig rp = resolution.getResolvedConfig();
            claimAndTrack(rp, registry, configName, resolution.getChanges());
            this.resolvedPorts = rp;
            syncResolvedPortsToConfig(rp);
            logResolutionChanges(resolution.getChanges());
        }
    }

    private void syncResolvedPortsToConfig(@NotNull PortConfig rp) {
        writeBackResolvedPorts(configuration, rp);
    }

    private void syncResolvedDebugPortToConfig(int resolvedDebug) {
        writeBackResolvedDebugPort(configuration, resolvedDebug);
    }

    /**
     * Writes the resolved ports back to the configuration's {@link PortConfig}
     * so it becomes the single source of truth for every downstream read — the
     * config dialog, the browser URL generation, the Services panel, and the
     * serializer. Prior to this sync the resolved ports lived only on the
     * process handler, and the dialog kept showing stale pre-resolution values
     * until the user manually edited them.
     *
     * <p>No-op in parallel-run mode: two simultaneous launches of the same
     * configuration would race on the shared {@code PortConfig}, last-writer-
     * wins. In parallel mode per-launch ports stay on the handler; the config
     * represents the user's seed values for the next fresh launch.
     *
     * <p>Static + package-private so unit tests can exercise it without
     * constructing a full {@link ExecutionEnvironment}.
     */
    static void writeBackResolvedPorts(@NotNull TomcatRunConfiguration configuration,
                                        @NotNull PortConfig resolved) {
        if (configuration.isParallelRunEffective()) {
            return;
        }
        PortConfig target = configuration.getConfigData().getPortConfig();
        boolean changed = false;
        if (target.getHttp() != resolved.getHttp()) {
            target.setHttp(resolved.getHttp());
            changed = true;
        }
        if (target.getShutdown() != resolved.getShutdown()) {
            target.setShutdown(resolved.getShutdown());
            changed = true;
        }
        if (target.isHttpsEnabled() && target.getHttps() != resolved.getHttps()) {
            target.setHttps(resolved.getHttps());
            changed = true;
        }
        if (target.isJmxEnabled() && target.getJmx() != resolved.getJmx()) {
            target.setJmx(resolved.getJmx());
            changed = true;
        }
        if (target.isAjpEnabled() && target.getAjp() != resolved.getAjp()) {
            target.setAjp(resolved.getAjp());
            changed = true;
        }
        if (changed) {
            scheduleDashboardRefresh(configuration);
        }
    }

    /**
     * Writes the resolved debug port back to {@code DebugConfig} so the config
     * dialog and serializer agree on the port the JVM actually bound. Same
     * parallel-run caveat as {@link #writeBackResolvedPorts}: skipped when
     * multiple launches of one configuration could race.
     */
    static void writeBackResolvedDebugPort(@NotNull TomcatRunConfiguration configuration,
                                            int resolvedDebug) {
        if (configuration.isParallelRunEffective()) {
            return;
        }
        if (resolvedDebug <= 0) return;
        var debugConfig = configuration.getConfigData().getDebugConfig();
        if (debugConfig != null && debugConfig.getPort() != resolvedDebug) {
            debugConfig.setPort(resolvedDebug);
            scheduleDashboardRefresh(configuration);
        }
    }

    /**
     * Tells the Run Dashboard / Services panel to rebuild its tree so the newly
     * written-back port values are picked up. Without this the first launch of a
     * configuration can leave the Services panel pinned to pre-resolution port
     * values — subsequent launches render correctly because the config already
     * holds the resolved port from the start. Scheduled on the EDT because
     * {@link RunDashboardManager#updateDashboard(boolean)} touches UI state.
     */
    private static void scheduleDashboardRefresh(@NotNull TomcatRunConfiguration configuration) {
        Project project = configuration.getProject();
        if (project == null || project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                RunDashboardManager.getInstance(project).updateDashboard(true);
            } catch (Exception e) {
                LOG.debug("Dashboard refresh after port writeback failed", e);
            }
        });
    }

    /**
     * Claims all ports in the resolved {@link PortConfig} through the registry and
     * appends a change entry for any port the registry bumped further (i.e., a
     * concurrent instance already claimed the port that {@link PortConflictDetector}
     * picked).
     */
    private void claimAndTrack(@NotNull PortConfig rp,
                               @NotNull TomcatPortRegistry registry,
                               @NotNull String configName,
                               @NotNull List<String> changes) {
        int orig, claimed;

        orig = rp.getHttp();
        claimed = registry.claimPort(orig, configName);
        if (claimed == -1) {
            changes.add("HTTP port " + orig + ": all ports in search range exhausted — Tomcat may fail to bind");
        } else if (claimed != orig) {
            changes.add("HTTP port " + orig + " claimed by a concurrent instance, resolved to " + claimed);
            rp.setHttp(claimed);
        }

        orig = rp.getShutdown();
        claimed = registry.claimPort(orig, configName);
        if (claimed == -1) {
            changes.add("Shutdown port " + orig + ": all ports in search range exhausted — Tomcat may fail to bind");
        } else if (claimed != orig) {
            changes.add("Shutdown port " + orig + " claimed by a concurrent instance, resolved to " + claimed);
            rp.setShutdown(claimed);
        }

        if (rp.isHttpsEnabled()) {
            orig = rp.getHttps();
            claimed = registry.claimPort(orig, configName);
            if (claimed == -1) {
                changes.add("HTTPS port " + orig + ": all ports in search range exhausted — Tomcat may fail to bind");
            } else if (claimed != orig) {
                changes.add("HTTPS port " + orig + " claimed by a concurrent instance, resolved to " + claimed);
                rp.setHttps(claimed);
            }
        }

        if (rp.isJmxEnabled()) {
            orig = rp.getJmx();
            claimed = registry.claimPort(orig, configName);
            if (claimed == -1) {
                changes.add("JMX port " + orig + ": all ports in search range exhausted — Tomcat may fail to bind");
            } else if (claimed != orig) {
                changes.add("JMX port " + orig + " claimed by a concurrent instance, resolved to " + claimed);
                rp.setJmx(claimed);
            }
        }

        if (rp.isAjpEnabled()) {
            orig = rp.getAjp();
            claimed = registry.claimPort(orig, configName);
            if (claimed == -1) {
                changes.add("AJP port " + orig + ": all ports in search range exhausted — Tomcat may fail to bind");
            } else if (claimed != orig) {
                changes.add("AJP port " + orig + " claimed by a concurrent instance, resolved to " + claimed);
                rp.setAjp(claimed);
            }
        }
    }

    private void logResolutionChanges(@NotNull List<String> changes) {
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

    /**
     * Resolves (and lazily assigns) the per-launch identifier used to isolate the
     * CATALINA_BASE. Returns {@code null} whenever parallel-run isolation is not
     * <em>effective</em> — either the checkbox is off, or the user pinned an
     * explicit CATALINA_BASE so isolation would be impossible anyway. See
     * {@link TomcatRunConfiguration#isParallelRunEffective()} for the single
     * authoritative predicate; this method and
     * {@link TomcatRunnerDelegate#handleSameExecutorRerun} both consult it so
     * the launch path and the rerun-intercept path agree on whether this
     * configuration should behave as parallel.
     *
     * <p>The pinned-base guard is critical: {@link TomcatProjectUtils#getCatalinaBase}
     * deliberately returns the user's pinned directory regardless of {@code runId},
     * so assigning a runId here would cause the per-run cleanup in
     * {@link TomcatProcessHandler} to walk and delete the pinned directory on
     * process exit — data loss.
     *
     * <p>The id is derived from {@link ExecutionEnvironment#getExecutionId()} —
     * unique per launch within the IDE session and stable across the lifetime of
     * this {@code TomcatCommandLineState} so the builder, the process handler,
     * and post-launch consumers (updater, Services panel) all see the same path.
     */
    @Nullable
    private String resolveRunId() {
        if (!configuration.isParallelRunEffective()) {
            if (configuration.isAllowMultipleInstances()) {
                // Checkbox is on but isolation is impossible because of a pin.
                // Warn once — the same rerun will keep landing in the Update dialog
                // path instead of spawning parallel instances.
                String pinned = configuration.getConfigData().getCatalinaBase();
                LOG.warn("Allow parallel run: CATALINA_BASE is pinned to '" + pinned
                        + "' — parallel isolation disabled, using single-instance semantics "
                        + "(Update dialog on rerun) to prevent shared-directory collisions.");
                if (deploymentLogger != null) {
                    deploymentLogger.logServerWarning(
                            "Allow parallel run is ignored because CATALINA_BASE is pinned — "
                            + "unset the pinned base to enable per-run isolation.");
                }
            }
            return null;
        }
        String current = runId;
        if (current != null) return current;
        long id = getEnvironment().getExecutionId();
        // Prefix so the directory is easy to identify on disk and never collides
        // with legitimate config names. The absolute value is used to avoid a
        // leading '-' in the directory name on pathological IDs.
        current = "run-" + Long.toUnsignedString(id, 36);
        runId = current;
        // Known limitation: IntelliJ's LogFileOptions are attached to the run
        // configuration, not the process, so all parallel launches of this
        // config share one set of Log tabs pointing at the shared per-config
        // logs directory. The actual per-instance logs are written under the
        // isolated CATALINA_BASE; tell the user where to find them.
        if (deploymentLogger != null) {
            deploymentLogger.logServerInfo(
                    "Parallel run active — isolated CATALINA_BASE under .runs/" + current
                    + "/. Log tabs continue to reflect the shared per-config logs/ "
                    + "directory; per-instance logs for this launch live under the "
                    + "isolated base's logs/ subfolder (shown in the server output on startup).");
        }
        return current;
    }

    @Nullable
    String getRunId() {
        return runId;
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
                DebugConfig dc = configuration.getConfigData().getDebugConfig();
                int debugPort = resolvedDebugPort > 0 ? resolvedDebugPort
                        : (dc != null ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT);
                commandLine.withEnvironment(TomcatConstants.ENV_DEBUG_PORT, String.valueOf(debugPort));
                String jdwpArg = TomcatConstants.JDWP_AGENT_PREFIX
                        + String.format(TomcatConstants.JDWP_CONNECTION_FORMAT, TomcatConstants.JDWP_TRANSPORT_SOCKET, debugPort);
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
