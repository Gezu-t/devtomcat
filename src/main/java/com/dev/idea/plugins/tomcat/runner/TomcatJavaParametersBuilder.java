package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
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
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Builds Java parameters for Tomcat execution.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatJavaParametersBuilder {

    private static final Logger LOG = Logger.getInstance(TomcatJavaParametersBuilder.class);

    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_CATALINA_TMPDIR = "java.io.tmpdir";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";

    private static final String JMX_REMOTE_PROP = "com.sun.management.jmxremote";
    private static final String JMX_PORT_PROP = "com.sun.management.jmxremote.port";
    private static final String JMX_SSL_PROP = "com.sun.management.jmxremote.ssl";
    private static final String JMX_AUTH_PROP = "com.sun.management.jmxremote.authenticate";
    private static final String JMX_LOCAL_PROP = "com.sun.management.jmxremote.local.only";

    private static final String PARAM_HTTPS_PORT = "tomcat.https.port";
    private static final String PARAM_SERVER_PORT = "server.port";
    private static final String PARAM_SHUTDOWN_PORT = "server.shutdown.port";
    private final TomcatRunConfiguration configuration;
    private final Project project;
    private final ExecutionEnvironment environment;
    private boolean debugMode = false;
    private int resolvedDebugPort = -1;
    private PortConfig resolvedPorts;
    private TomcatDeploymentLogger deploymentLogger;

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

    @NotNull
    public JavaParameters build() throws ExecutionException {
        try {
            Path catalinaBase = getCatalinaBase();
            Path catalinaHome = getCatalinaHome();

            int httpPort;
            int shutdownPort;
            int jmxPort;
            int httpsPort;
            int ajpPort;

            if (resolvedPorts != null) {
                // Use pre-resolved ports from PortConflictDetector (conflict-free)
                httpPort = resolvedPorts.getHttp();
                shutdownPort = resolvedPorts.getShutdown();
                jmxPort = resolvedPorts.getJmx();
                httpsPort = resolvedPorts.getHttps();
                ajpPort = resolvedPorts.getAjp();
                LOG.info("Using pre-resolved ports: HTTP=" + httpPort + ", shutdown=" + shutdownPort);
            } else {
                // Fallback: resolve ports here (e.g., when called without TomcatCommandLineState).
                // NOTE: this path is non-atomic — two simultaneous launches can still observe the
                // same port as free. The preferred path goes through TomcatCommandLineState which
                // uses TomcatPortRegistry for atomic claiming.
                httpPort = getConfigPort(configuration.getHttpPort(), PortUtils.DEFAULT_HTTP);
                shutdownPort = getConfigPort(configuration.getShutdownPort(), PortUtils.DEFAULT_SHUTDOWN);
                jmxPort = getConfigPort(configuration.getJmxPort(), PortUtils.DEFAULT_JMX);
                httpsPort = getConfigPort(configuration.getHttpsPort(), PortUtils.DEFAULT_HTTPS);
                ajpPort = getConfigPort(configuration.getAjpPort(), PortUtils.DEFAULT_AJP);

                // Resolve internal conflicts (same port used by multiple services).
                // Track already-assigned ports so each findNextAvailable skips ports already in use
                // by sibling services resolved in this same pass.
                java.util.Set<Integer> assigned = new java.util.HashSet<>();
                assigned.add(httpPort);
                if (assigned.contains(shutdownPort)) { shutdownPort = PortUtils.findNextAvailableExcluding(shutdownPort, assigned); }
                assigned.add(shutdownPort);
                if (assigned.contains(jmxPort)) { jmxPort = PortUtils.findNextAvailableExcluding(jmxPort, assigned); }
                assigned.add(jmxPort);
                if (assigned.contains(httpsPort)) { httpsPort = PortUtils.findNextAvailableExcluding(httpsPort, assigned); }
                assigned.add(httpsPort);
                if (assigned.contains(ajpPort)) { ajpPort = PortUtils.findNextAvailableExcluding(ajpPort, assigned); }

                // Resolve external conflicts (port in use by another process)
                httpPort = resolvePortWithLogging("HTTP", httpPort, true);
                shutdownPort = resolvePortWithLogging("Shutdown", shutdownPort, true);
                jmxPort = resolvePortWithLogging("JMX", jmxPort, configuration.isJmxEnabled());
                httpsPort = resolvePortWithLogging("HTTPS", httpsPort, configuration.isHttpsEnabled());
                ajpPort = resolvePortWithLogging("AJP", ajpPort, configuration.isAjpEnabled());
            }

            if (httpPort <= 0 || shutdownPort <= 0 || (configuration.isJmxEnabled() && jmxPort <= 0)
                    || (configuration.isHttpsEnabled() && httpsPort <= 0)
                    || (configuration.isAjpEnabled() && ajpPort <= 0)) {
                throw new ExecutionException("Unable to find available ports for Tomcat run configuration");
            }

            // Prepare catalina.base with directories and config files (after ports are resolved)
            prepareCatalinaBase(catalinaBase, catalinaHome, httpPort, shutdownPort, httpsPort, ajpPort);

            Sdk jdk = resolveJdk();

            JavaParameters params = new JavaParameters();
            setupBasicParameters(params, catalinaBase, jdk);
            setupClasspath(params, catalinaHome);
            setupEnvironment(params);
            setupVmOptions(params, catalinaBase, catalinaHome, httpPort, shutdownPort, jmxPort, httpsPort, jdk);
            setupDeploymentArtifacts(params, catalinaBase);

            return params;

        } catch (IOException e) {
            throw new ExecutionException("Failed to prepare Tomcat directories", e);
        }
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
        Path base = TomcatProjectUtils.getCatalinaBase(configuration);
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
                                     int httpPort, int shutdownPort,
                                     int httpsPort, int ajpPort) throws IOException {
        boolean httpsEnabled = configuration.isHttpsEnabled();
        boolean ajpEnabled = configuration.isAjpEnabled();

        // Resolve conf overlay: <project>/.devtomcat/<config-name>/conf/
        Path confOverlay = TomcatProjectUtils.getConfOverlayDirectory(configuration);
        boolean overlayActive = confOverlay != null && java.nio.file.Files.isDirectory(confOverlay);

        java.util.List<String> warnings = TomcatConfigPreparer.prepare(
                catalinaBase, catalinaHome, httpPort, shutdownPort,
                httpsPort, httpsEnabled, ajpPort, ajpEnabled,
                overlayActive ? confOverlay : null);

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
                                int httpPort,
                                int shutdownPort,
                                int jmxPort,
                                int httpsPort,
                                @NotNull Sdk jdk) {
        ParametersList vmParams = params.getVMParametersList();

        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        if (StringUtil.isNotEmpty(vmOptions)) {
            vmParams.addParametersString(vmOptions);
        }

        if (configuration.isJmxEnabled()) {
            configureJmx(vmParams, jmxPort);
        }

        if (configuration.isHttpsEnabled()) {
            configureHttps(vmParams, httpsPort);
        }

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

        // JDK 9+ module opens required by Tomcat (previously delivered via JDK_JAVA_OPTIONS env var)
        configureModuleOpens(vmParams, jdk);

        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome, httpPort, shutdownPort);
    }

    private void configureModuleOpens(@NotNull ParametersList vmParams, @NotNull Sdk jdk) {
        // --add-opens requires JDK 9+; passing these flags to JDK 8 crashes with "Unrecognized option"
        JavaSdkVersion sdkVersion = JavaSdkVersion.fromVersionString(jdk.getVersionString());
        if (sdkVersion == null || !sdkVersion.isAtLeast(JavaSdkVersion.JDK_1_9)) {
            return;
        }
        String[] moduleOpens = {
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED"
        };
        for (String open : moduleOpens) {
            vmParams.add(open);
        }
    }

    private void configureJmx(@NotNull ParametersList vmParams, int jmxPort) {
        // Use defineProperty (set-or-replace) so that user VM options containing these
        // properties are overridden rather than duplicated.
        vmParams.defineProperty(JMX_REMOTE_PROP, "");
        vmParams.defineProperty(JMX_PORT_PROP, String.valueOf(jmxPort));
        vmParams.defineProperty(JMX_SSL_PROP, "false");
        vmParams.defineProperty(JMX_AUTH_PROP, "false");
        vmParams.defineProperty(JMX_LOCAL_PROP, "false");
    }

    private void configureHttps(@NotNull ParametersList vmParams, int httpsPort) {
        // HTTPS is configured entirely via server.xml connectors (see copyAndCustomizeServerXml).
        // Expose port as a system property for custom scripts/monitoring only.
        vmParams.defineProperty(PARAM_HTTPS_PORT, String.valueOf(httpsPort));
    }

    private void configureCatalinaProperties(@NotNull ParametersList vmParams,
                                             @NotNull Path catalinaBase,
                                             @NotNull Path catalinaHome,
                                             int httpPort,
                                             int shutdownPort) {
        vmParams.defineProperty(PARAM_CATALINA_HOME, catalinaHome.toString());
        vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
        vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve(DIR_TEMP).toString());
        vmParams.defineProperty(PARAM_LOGGING_CONFIG, catalinaBase.resolve(CONFIG_LOGGING_PROPERTIES).toString());
        vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);
        vmParams.defineProperty(PARAM_SERVER_PORT, String.valueOf(httpPort));
        vmParams.defineProperty(PARAM_SHUTDOWN_PORT, String.valueOf(shutdownPort));
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
