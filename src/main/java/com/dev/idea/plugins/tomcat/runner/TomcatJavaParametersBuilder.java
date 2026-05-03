package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.coverage.CoverageAgentAttacher;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Builds Java parameters for Tomcat execution.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatJavaParametersBuilder {

    private static final Logger LOG = Logger.getInstance(TomcatJavaParametersBuilder.class);

    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private final TomcatRunConfiguration configuration;
    private final Project project;
    private final ExecutionEnvironment environment;
    private boolean debugMode = false;
    private int resolvedDebugPort = -1;
    private PortConfig resolvedPorts;
    private TomcatDeploymentLogger deploymentLogger;
    @Nullable private String runId;

    public TomcatJavaParametersBuilder(@NotNull TomcatRunConfiguration configuration,
                                       @NotNull ExecutionEnvironment environment) {
        this.configuration = configuration;
        this.project = configuration.getProject();
        this.environment = environment;
    }

    public TomcatJavaParametersBuilder setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        return this;
    }

    public TomcatJavaParametersBuilder setResolvedDebugPort(int resolvedDebugPort) {
        this.resolvedDebugPort = resolvedDebugPort;
        return this;
    }

    /**
     * Port to use when {@link #resolvedDebugPort} has not been set (no port
     * conflict resolution ran, typical for coverage-only or run-only executors).
     * Reads the persisted {@link DebugConfig} port, falling back to the default.
     */
    private int effectiveDebugPort() {
        com.dev.idea.plugins.tomcat.model.debug.DebugConfig dc =
                configuration.getConfigData().getDebugConfig();
        return dc != null && dc.isValid()
                ? dc.getPort()
                : com.dev.idea.plugins.tomcat.model.debug.DebugConfig.DEFAULT_DEBUG_PORT;
    }

    /**
     * Appends the JDWP agent argument to a VM parameter list in a canonical
     * {@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:port}
     * form. Extracted to a package-private static helper so unit tests can
     * verify the injection without standing up the full {@code build()} pipeline
     * (which needs a real Tomcat install, artifacts, etc.). Callers are
     * responsible for gating the call on {@link #debugMode}.
     */
    static void injectJdwpAgent(@NotNull ParametersList vmParams, int port) {
        String jdwpArg = TomcatConstants.JDWP_AGENT_PREFIX
                + String.format(
                        TomcatConstants.JDWP_CONNECTION_FORMAT,
                        TomcatConstants.JDWP_TRANSPORT_SOCKET,
                        port);
        vmParams.add(jdwpArg);
    }

    public TomcatJavaParametersBuilder setResolvedPorts(@Nullable PortConfig resolvedPorts) {
        this.resolvedPorts = resolvedPorts;
        return this;
    }

    public TomcatJavaParametersBuilder setDeploymentLogger(@Nullable TomcatDeploymentLogger deploymentLogger) {
        this.deploymentLogger = deploymentLogger;
        return this;
    }

    /**
     * Assigns the per-run identifier used to derive an isolated CATALINA_BASE
     * when <em>Allow parallel run</em> is active. {@code null} means "shared
     * per-config base" — the historical single-instance behaviour.
     */
    public TomcatJavaParametersBuilder setRunId(@Nullable String runId) {
        this.runId = runId;
        return this;
    }

    @Nullable
    public String getRunId() {
        return runId;
    }

    @NotNull
    /**
     * True when this builder is assembling parameters for the Coverage
     * executor. Read from the execution environment instead of a setter so
     * there is no third flag to keep in sync with {@link #debugMode} — the
     * executor id is the source of truth and both runtime and tests resolve
     * it the same way.
     */
    private boolean isCoverageExecutor() {
        return TomcatConstants.COVERAGE_MODE.equals(environment.getExecutor().getId());
    }

    public JavaParameters build() throws ExecutionException {
        try {
            Path catalinaBase = getCatalinaBase();
            Path catalinaHome = getCatalinaHome();
            PortConfig ports = new PortResolver(configuration, resolvedPorts, deploymentLogger).resolve();

            prepareCatalinaBase(catalinaBase, catalinaHome, ports);

            Sdk jdk = resolveJdk();
            JavaParameters params = new JavaParameters();
            setupBasicParameters(params, catalinaBase, jdk);
            setupClasspath(params, catalinaHome);
            setupEnvironment(params);
            setupVmOptions(params, catalinaBase, catalinaHome, ports, jdk);
            setupDeploymentArtifacts(params, catalinaBase);

            // Coverage agent injection must happen after the Tomcat VM options
            // are set — the coverage -javaagent string is order-sensitive
            // relative to other agents (debug JDWP, etc.) and the platform's
            // appendCoverageArgument expects to see the fully-built parameter
            // list so it can position itself correctly.
            if (isCoverageExecutor()) {
                CoverageAgentAttacher.attach(configuration, params);
            }

            return params;

        } catch (IOException e) {
            throw new ExecutionException("Failed to prepare Tomcat directories", e);
        }
    }

    @NotNull
    private Path getCatalinaBase() throws ExecutionException {
        // A non-null runId is the signal that "Allow parallel run" gave us an isolated
        // per-launch base. When unset (default), this resolves the shared per-config base.
        Path base = TomcatProjectUtils.getCatalinaBase(configuration, runId);
        if (base == null) {
            throw new ExecutionException("Unable to determine catalina.base directory");
        }
        return base;
    }

    /**
     * Resolve CATALINA_HOME via {@link TomcatServerManagerState#resolve} so the
     * runtime, the editor, and the configuration-validator all use the same
     * source of truth. Registration is required: an embedded snapshot whose path
     * exists on disk is <b>not</b> enough — if the resolver can't match by ID,
     * path, or name, the launch is blocked with a clear message pointing the
     * user at Application Servers. This is the second gate behind
     * {@link com.dev.idea.plugins.tomcat.conf.TomcatConfigurationValidator},
     * covering code paths that bypass run-configuration validation.
     *
     * <p>When the resolver <i>does</i> match but via drift (stale persisted ID,
     * moved path, renamed server), upgrade the config's embedded reference to
     * the canonical registered instance so the next open is clean.
     */
    @NotNull
    private Path getCatalinaHome() throws ExecutionException {
        TomcatInfo persisted = configuration.getConfigData().getTomcatInfo();
        if (persisted == null) {
            throw new ExecutionException("No Tomcat server configured."
                    + " Open the run configuration and select a server from Application Servers.");
        }

        TomcatInfo resolved = TomcatServerManagerState.getInstance().resolveOrAutoRegister(persisted);
        if (resolved == null) {
            String name = !persisted.getName().isEmpty() ? persisted.getName() : "(unnamed)";
            String path = persisted.getPath();
            throw new ExecutionException("Tomcat server '" + name + "' is not registered."
                    + " Persisted path: " + (path.isEmpty() ? "(empty)" : path) + "."
                    + " Open the run configuration and select a registered server,"
                    + " or add one via Configure.");
        }

        String resolvedPath = resolved.getPath();
        if (resolvedPath.isEmpty() || !Files.isDirectory(Paths.get(resolvedPath))) {
            throw new ExecutionException("Registered Tomcat server '" + resolved.getName()
                    + "' has an invalid path: "
                    + (resolvedPath.isEmpty() ? "(empty)" : resolvedPath)
                    + ". Open Application Servers settings to fix it.");
        }

        if (resolved != persisted) {
            LOG.info("Launch reconciled drifted persisted reference"
                    + " (id=" + persisted.getId() + ", path=" + persisted.getPath() + ")"
                    + " to registered server (id=" + resolved.getId()
                    + ", path=" + resolvedPath + ")");
            configuration.getConfigData().setTomcatInfo(resolved);
        }
        return Paths.get(resolvedPath);
    }

    private void prepareCatalinaBase(@NotNull Path catalinaBase, @NotNull Path catalinaHome,
                                     @NotNull PortConfig ports) throws IOException {
        Path confOverlay = TomcatProjectUtils.getConfOverlayDirectory(configuration);
        boolean overlayActive = confOverlay != null && Files.isDirectory(confOverlay);

        boolean hotDeployEnabled = configuration.isHotDeploymentEnabled();
        Set<String> reservedContextStems = collectIdeContextStems();

        List<String> warnings = TomcatConfigPreparer.prepare(
                catalinaBase, catalinaHome,
                ports.getHttp(), ports.getShutdown(),
                ports.getHttps(), configuration.isHttpsEnabled(),
                ports.getAjp(),  configuration.isAjpEnabled(),
                overlayActive ? confOverlay : null,
                hotDeployEnabled,
                reservedContextStems);

        if (deploymentLogger != null) {
            if (overlayActive) {
                deploymentLogger.logServerWarning(
                        "Using conf overlay from " + confOverlay +
                        " — files here override CATALINA_HOME defaults");
            }
            for (String warning : warnings) {
                deploymentLogger.logServerWarning(warning);
            }
        }
    }

    /**
     * Returns the set of context-XML stems (e.g. "ROOT", "myapp") claimed by this
     * configuration's IDE-managed artifacts so {@link CatalinaHomeMirror} can skip
     * conflicting shared apps and leave the IDE descriptor in charge.
     */
    @NotNull
    private Set<String> collectIdeContextStems() {
        List<DeploymentArtifact> artifacts =
                configuration.getConfigData().getDeploymentConfig().getArtifacts();
        if (artifacts == null || artifacts.isEmpty()) {
            return Set.of();
        }
        Set<String> stems = new HashSet<>();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            try {
                stems.add(ContextPathUtils.resolveContextName(artifact.getContextPath()));
            } catch (IllegalArgumentException e) {
                // Invalid context path — ignore here; deployment strategy reports it at deploy time.
                LOG.debug("Skipping invalid context path for mirror reservation: " + artifact.getContextPath());
            }
        }
        return stems;
    }

    private void setupBasicParameters(@NotNull JavaParameters params, @NotNull Path catalinaBase, @NotNull Sdk jdk) {
        params.setDefaultCharset(project);
        params.setWorkingDirectory(catalinaBase.toFile());
        params.setJdk(jdk);
        params.setMainClass(TOMCAT_MAIN_CLASS);
        params.getProgramParametersList().add("start");
    }

    @NotNull
    private Sdk resolveJdk() throws ExecutionException {
        String jreSelection = configuration.getConfigData().getJreSelection();
        boolean explicitSelection = jreSelection != null
                && !jreSelection.isEmpty()
                && !TomcatConstants.JRE_PROJECT_DEFAULT.equals(jreSelection);

        Sdk sdk = resolveJdkOrNull(configuration, project);
        if (sdk == null) {
            throw new ExecutionException("No JDK configured for the project. Please configure a Project SDK in File → Project Structure.");
        }
        // Surface the silent fallback in the run console — without this, removing
        // a configured JDK launched the project SDK with no user-visible signal.
        if (explicitSelection
                && deploymentLogger != null
                && ProjectJdkTable.getInstance().findJdk(jreSelection) == null) {
            deploymentLogger.logServerWarning(
                    "Configured JRE '" + jreSelection + "' is not registered. "
                            + "Falling back to the project SDK ('" + sdk.getName() + "'). "
                            + "Open the run configuration to pick a registered JRE.");
        }
        return sdk;
    }

    /**
     * Resolves the JDK for a configuration, returning null if none is found.
     * Shared between the builder (which throws on null) and
     * {@link TomcatCommandLineState} (which passes null to the compatibility checker).
     */
    @Nullable
    static Sdk resolveJdkOrNull(@NotNull TomcatRunConfiguration configuration, @NotNull Project project) {
        String jreSelection = configuration.getConfigData().getJreSelection();
        if (jreSelection != null
                && !jreSelection.isEmpty()
                && !TomcatConstants.JRE_PROJECT_DEFAULT.equals(jreSelection)) {
            Sdk sdk = ProjectJdkTable.getInstance().findJdk(jreSelection);
            if (sdk != null) {
                LOG.info("Using configured JRE: " + sdk.getName());
                return sdk;
            }
            LOG.warn("Configured JRE '" + jreSelection + "' not found, falling back to project SDK");
        }
        return ProjectRootManager.getInstance(project).getProjectSdk();
    }

    private void setupClasspath(@NotNull JavaParameters params,
                                @NotNull Path catalinaHome) throws ExecutionException {
        Path bootstrap = catalinaHome.resolve(JAR_BOOTSTRAP);
        Path tomcatJuli = catalinaHome.resolve(JAR_TOMCAT_JULI);

        if (!Files.isRegularFile(bootstrap)) {
            throw new ExecutionException(
                    "Required Tomcat JAR not found: " + bootstrap +
                    ". Verify that the configured Tomcat home directory is a valid Tomcat installation.");
        }
        if (!Files.isRegularFile(tomcatJuli)) {
            throw new ExecutionException(
                    "Required Tomcat JAR not found: " + tomcatJuli +
                    ". Verify that the configured Tomcat home directory is a valid Tomcat installation.");
        }

        params.getClassPath().add(bootstrap.toFile());
        params.getClassPath().add(tomcatJuli.toFile());
    }

    private void setupEnvironment(@NotNull JavaParameters params) {
        String executorId = environment != null && environment.getExecutor() != null
                ? environment.getExecutor().getId() : "Run";

        // Ensure computed env vars (JAVA_OPTS from VM options) are present
        // even if the Startup/Connection tab was never visited
        RuntimeEnvResolver.ensureComputedEnvVars(
                configuration.getConfigData(), executorId);

        RunnerSettings rs = configuration.getConfigData().getRunnerSettings(executorId);
        boolean passParent = rs.isPassParentEnvs();
        Map<String, String> env = rs.getEnvironmentVariables();

        params.setPassParentEnvs(passParent);
        if (env != null) {
            params.setEnv(env);
        }
    }

    private void setupVmOptions(@NotNull JavaParameters params,
                                @NotNull Path catalinaBase,
                                @NotNull Path catalinaHome,
                                @NotNull PortConfig ports,
                                @NotNull Sdk jdk) {
        ParametersList vmParams = params.getVMParametersList();

        // Inject -agentlib:jdwp ourselves in debug mode. The previous comment here
        // claimed GenericDebuggerRunner's patcher would inject the agent, but that
        // only runs from its super.doExecute() — and TomcatDebugger.doExecute()
        // bypasses super entirely to call attachVirtualMachine() directly. With no
        // JDWP agent on the JVM, the debugger tried to attach to a port nothing was
        // listening on and every breakpoint was silently skipped.
        //
        // Single source of truth for the port: resolvedDebugPort (set by
        // TomcatCommandLineState's port-conflict resolver) falling back to the
        // persisted DebugConfig. TomcatDebugger reads the same value to create the
        // RemoteConnection, so attach and agent always agree on the port. The
        // manual-JDWP warning in TomcatCommandLineState.warnIfManualJdwpInDebugMode
        // still fires if the user also added -agentlib:jdwp to VM options, which
        // would create two agents and break the attach.
        if (debugMode) {
            int port = resolvedDebugPort > 0
                    ? resolvedDebugPort
                    : effectiveDebugPort();
            injectJdwpAgent(vmParams, port);
            LOG.info("Debug mode: injected JDWP agent on port " + port
                    + " (resolvedDebugPort=" + resolvedDebugPort + ")");
        }

        TomcatVmOptionsConfigurator.configure(
                vmParams,
                configuration.getConfigData().getVmConfig().getVmOptions(),
                ports,
                configuration.isJmxEnabled(),
                catalinaBase,
                catalinaHome,
                jdk
        );
    }

    private void setupDeploymentArtifacts(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        DeploymentStrategy strategy = DeploymentStrategy.create(configuration);
        strategy.configureDeployment(params, catalinaBase, configuration, project, deploymentLogger);
    }

    public static TomcatJavaParametersBuilder create(@NotNull TomcatRunConfiguration configuration,
                                                     @NotNull ExecutionEnvironment environment) {
        return new TomcatJavaParametersBuilder(configuration, environment);
    }
}
