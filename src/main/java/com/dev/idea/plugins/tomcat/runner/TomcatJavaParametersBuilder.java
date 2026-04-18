package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.coverage.CoverageAgentAttacher;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
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
            PortConfig ports = resolvePortsIfNeeded();

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

    /**
     * Returns conflict-free ports for all Tomcat connectors.
     *
     * <p>If ports were pre-resolved atomically by {@link TomcatCommandLineState} via
     * {@link com.dev.idea.plugins.tomcat.utils.TomcatPortRegistry}, returns them directly.
     * Otherwise falls back to per-launch resolution (non-atomic — concurrent launches may
     * collide; the registry path is always preferred).
     *
     * @throws ExecutionException if any required port cannot be resolved
     */
    @NotNull
    PortConfig resolvePortsIfNeeded() throws ExecutionException {
        if (resolvedPorts != null) {
            LOG.info("Using pre-resolved ports: HTTP=" + resolvedPorts.getHttp()
                    + ", shutdown=" + resolvedPorts.getShutdown());
            return resolvedPorts;
        }

        // Fallback: resolve here. Non-atomic — prefer the TomcatCommandLineState path.
        int httpPort     = getConfigPort(configuration.getHttpPort(),     PortUtils.DEFAULT_HTTP);
        int shutdownPort = getConfigPort(configuration.getShutdownPort(), PortUtils.DEFAULT_SHUTDOWN);
        int jmxPort      = getConfigPort(configuration.getJmxPort(),      PortUtils.DEFAULT_JMX);
        int httpsPort    = getConfigPort(configuration.getHttpsPort(),     PortUtils.DEFAULT_HTTPS);
        int ajpPort      = getConfigPort(configuration.getAjpPort(),       PortUtils.DEFAULT_AJP);

        // Resolve internal conflicts first (same value assigned to multiple connectors)
        Set<Integer> assigned = new HashSet<>();
        assigned.add(httpPort);
        if (assigned.contains(shutdownPort)) { shutdownPort = PortUtils.findNextAvailableExcluding(shutdownPort, assigned); }
        assigned.add(shutdownPort);
        if (assigned.contains(jmxPort))      { jmxPort      = PortUtils.findNextAvailableExcluding(jmxPort, assigned); }
        assigned.add(jmxPort);
        if (assigned.contains(httpsPort))    { httpsPort    = PortUtils.findNextAvailableExcluding(httpsPort, assigned); }
        assigned.add(httpsPort);
        if (assigned.contains(ajpPort))      { ajpPort      = PortUtils.findNextAvailableExcluding(ajpPort, assigned); }

        // Resolve external conflicts (port already bound by another process)
        httpPort     = resolvePortWithLogging("HTTP",     httpPort,     true);
        shutdownPort = resolvePortWithLogging("Shutdown", shutdownPort, true);
        jmxPort      = resolvePortWithLogging("JMX",      jmxPort,      configuration.isJmxEnabled());
        httpsPort    = resolvePortWithLogging("HTTPS",    httpsPort,    configuration.isHttpsEnabled());
        ajpPort      = resolvePortWithLogging("AJP",      ajpPort,      configuration.isAjpEnabled());

        if (httpPort <= 0 || shutdownPort <= 0
                || (configuration.isJmxEnabled()   && jmxPort   <= 0)
                || (configuration.isHttpsEnabled()  && httpsPort <= 0)
                || (configuration.isAjpEnabled()    && ajpPort   <= 0)) {
            throw new ExecutionException("Unable to find available ports for Tomcat run configuration");
        }

        PortConfig ports = new PortConfig();
        ports.setHttp(httpPort);
        ports.setShutdown(shutdownPort);
        ports.setJmx(jmxPort);
        ports.setHttps(httpsPort);
        ports.setAjp(ajpPort);
        return ports;
    }

    private static int getConfigPort(@Nullable Integer configValue, int defaultValue) {
        if (configValue != null && PortUtils.isValid(configValue)) {
            return configValue;
        }
        return defaultValue;
    }

    private int resolvePortWithLogging(@NotNull String serviceName, int port, boolean enabled) {
        if (!enabled) return port;
        if (PortUtils.isAvailable(port)) return port;

        int resolved = PortUtils.findNextAvailable(port);
        if (resolved > 0) {
            String msg = serviceName + " port " + port + " in use, auto-resolved to " + resolved;
            LOG.info(msg);
            if (deploymentLogger != null) {
                deploymentLogger.logServerWarning(msg);
            }
            return resolved;
        }
        // No available port found — return -1 so the port guard (httpPort <= 0 check) catches it
        // and throws a clear ExecutionException instead of passing a conflicted port to Tomcat.
        String msg = serviceName + " port " + port + " in use and no available port could be found";
        LOG.warn(msg);
        if (deploymentLogger != null) {
            deploymentLogger.logServerError(msg);
        }
        return -1;
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

    @NotNull
    private Path getCatalinaHome() throws ExecutionException {
        var tomcatInfo = configuration.getConfigData().getTomcatInfo();
        if (tomcatInfo == null) {
            throw new ExecutionException("No Tomcat server configured");
        }
        return Paths.get(tomcatInfo.getPath());
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
        Sdk sdk = resolveJdkOrNull(configuration, project);
        if (sdk == null) {
            throw new ExecutionException("No JDK configured for the project. Please configure a Project SDK in File → Project Structure.");
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

        // Do NOT add -agentlib:jdwp here. In debug mode, GenericDebuggerRunner's
        // DebugProcessImpl patches the JavaParameters with the JDWP agent based on
        // the RemoteConnection created by TomcatDebugger. Adding it here would create
        // a DUPLICATE agent — the JVM assigns the second one a different port, causing
        // a mismatch between what the debugger connects to and what Tomcat listens on.
        // The resolved debug port flows: TomcatCommandLineState.resolvedDebugPort
        //   → TomcatDebugger reads it → creates RemoteConnection
        //   → GenericDebuggerRunner patches params with matching JDWP arg.
        if (debugMode) {
            LOG.info("Debug mode: JDWP agent will be injected by GenericDebuggerRunner " +
                    "(resolved debug port: " + resolvedDebugPort + ")");
        }

        TomcatVmOptionsConfigurator.configure(
                vmParams,
                configuration.getConfigData().getVmConfig().getVmOptions(),
                ports,
                configuration.isJmxEnabled(),
                configuration.isHttpsEnabled(),
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
