package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Reusable builder for Tomcat Java parameters
 * Encapsulates the complex logic of building Java parameters for Tomcat
 *
 * @author Gezahegn Lemma (Gezu)
 * @version 1.6
 */
public class TomcatJavaParametersBuilder {

    private static final Logger LOG = Logger.getInstance(TomcatJavaParametersBuilder.class);

    // Constants
    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_CATALINA_TMPDIR = "java.io.tmpdir";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";

    // JMX default settings
    private static final String JMX_REMOTE_PROP = "com.sun.management.jmxremote";
    private static final String JMX_PORT_PROP = "com.sun.management.jmxremote.port";
    private static final String JMX_SSL_PROP = "com.sun.management.jmxremote.ssl";
    private static final String JMX_AUTH_PROP = "com.sun.management.jmxremote.authenticate";
    private static final String JMX_LOCAL_PROP = "com.sun.management.jmxremote.local.only";

    private final TomcatRunConfiguration configuration;
    private final Project project;

    public TomcatJavaParametersBuilder(@NotNull TomcatRunConfiguration configuration) {
        this.configuration = configuration;
        this.project = configuration.getProject();
    }

    /**
     * Builds JavaParameters for Tomcat execution
     *
     * @return Configured JavaParameters
     * @throws ExecutionException if configuration is invalid
     */
    @NotNull
    public JavaParameters build() throws ExecutionException {
        try {
            // Get paths
            Path catalinaBase = getCatalinaBase();
            Path catalinaHome = getCatalinaHome();

            // Ensure required directories exist
            ensureDirectoriesExist(catalinaBase);

            // Prefer ConfigData (new model) but keep backward compatibility with legacy getters
            // Defaults from PortUtils
            int httpPort = PortUtils.DEFAULT_HTTP;
            int shutdownPort = PortUtils.DEFAULT_SHUTDOWN;
            int jmxPort = PortUtils.DEFAULT_JMX;
            int httpsPort = PortUtils.DEFAULT_HTTPS;

            // Get ports from PortConfig through TomcatRunConfiguration convenience methods
            Integer configHttpPort = configuration.getHttpPort();
            Integer configShutdownPort = configuration.getShutdownPort();
            Integer configJmxPort = configuration.getJmxPort();
            Integer configHttpsPort = configuration.getHttpsPort();

            if (configHttpPort != null) {
                httpPort = configHttpPort;
            }
            if (configShutdownPort != null) {
                shutdownPort = configShutdownPort;
            }
            if (configJmxPort != null) {
                jmxPort = configJmxPort;
            }
            if (configHttpsPort != null) {
                httpsPort = configHttpsPort;
            }

            // If JMX/HTTPS are disabled, keep the port values computed above but they won't be applied unless enabled

            // Validate and normalize ports using PortUtils (this project does not provide validatePorts/PortValidationResult APIs)
            if (!PortUtils.isValid(httpPort)) httpPort = PortUtils.DEFAULT_HTTP;
            if (!PortUtils.isValid(shutdownPort)) shutdownPort = PortUtils.DEFAULT_SHUTDOWN;
            if (!PortUtils.isValid(jmxPort)) jmxPort = PortUtils.DEFAULT_JMX;
            if (!PortUtils.isValid(httpsPort)) httpsPort = PortUtils.DEFAULT_HTTPS;

            // Ensure ports are unique
            if (shutdownPort == httpPort) shutdownPort = PortUtils.findNextAvailable(shutdownPort);
            if (jmxPort == httpPort || jmxPort == shutdownPort) jmxPort = PortUtils.findNextAvailable(jmxPort);
            if (httpsPort == httpPort || httpsPort == shutdownPort || httpsPort == jmxPort) httpsPort = PortUtils.findNextAvailable(httpsPort);

            // Ensure ports are available (best-effort auto-fix)
            if (!PortUtils.isAvailable(httpPort)) httpPort = PortUtils.findNextAvailable(httpPort);
            if (!PortUtils.isAvailable(shutdownPort)) shutdownPort = PortUtils.findNextAvailable(shutdownPort);
            if (configuration.isJmxEnabled() && !PortUtils.isAvailable(jmxPort)) jmxPort = PortUtils.findNextAvailable(jmxPort);
            if (configuration.isHttpsEnabled() && !PortUtils.isAvailable(httpsPort)) httpsPort = PortUtils.findNextAvailable(httpsPort);

            if (httpPort <= 0 || shutdownPort <= 0 || (configuration.isJmxEnabled() && jmxPort <= 0) || (configuration.isHttpsEnabled() && httpsPort <= 0)) {
                throw new ExecutionException("Unable to find available ports for Tomcat run configuration");
            }

            // Create JavaParameters
            JavaParameters params = new JavaParameters();

            // Basic setup
            setupBasicParameters(params, catalinaBase);

            // Classpath
            setupClasspath(params, catalinaHome);

            // Environment
            setupEnvironment(params);

            // VM options
            setupVmOptions(params, catalinaBase, catalinaHome, httpPort, shutdownPort, jmxPort, httpsPort);

            // Deployment artifacts
            setupDeploymentArtifacts(params, catalinaBase);

            return params;

        } catch (IOException e) {
            throw new ExecutionException("Failed to prepare Tomcat directories", e);
        }
    }

    /**
     * Get Catalina base directory
     */
    @NotNull
    private Path getCatalinaBase() throws ExecutionException {
        Path base = TomcatProjectUtils.getCatalinaBase(configuration);
        if (base == null) {
            throw new ExecutionException("Unable to determine catalina.base directory");
        }
        return base;
    }

    /**
     * Get Catalina home directory
     */
    @NotNull
    private Path getCatalinaHome() throws ExecutionException {
        if (configuration.getConfigData().getTomcatInfo() == null) {
            throw new ExecutionException("No Tomcat server configured");
        }
        return Paths.get(configuration.getConfigData().getTomcatInfo().getPath());
    }

    /**
     * Ensure required directories exist
     */
    private void ensureDirectoriesExist(@NotNull Path catalinaBase) throws IOException {
        Files.createDirectories(catalinaBase.resolve("temp"));
        Files.createDirectories(catalinaBase.resolve("logs"));
        Files.createDirectories(catalinaBase.resolve("webapps"));
        Files.createDirectories(catalinaBase.resolve("work"));
        Files.createDirectories(catalinaBase.resolve("conf"));

        // Create logging.properties if it doesn't exist
        Path loggingPropertiesPath = catalinaBase.resolve("conf/logging.properties");
        if (!Files.exists(loggingPropertiesPath)) {
            createDefaultLoggingProperties(loggingPropertiesPath);
        }
    }

    /**
     * Create default logging.properties file
     */
    private void createDefaultLoggingProperties(@NotNull Path loggingPropertiesPath) throws IOException {
        String loggingConfig =
            "handlers = java.util.logging.ConsoleHandler\n" +
            ".handlers = java.util.logging.ConsoleHandler\n" +
            "\n" +
            "java.util.logging.ConsoleHandler.level = FINE\n" +
            "java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter\n" +
            "\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].handlers = java.util.logging.ConsoleHandler\n" +
            "\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/manager].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/manager].handlers = java.util.logging.ConsoleHandler\n" +
            "\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/host-manager].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/host-manager].handlers = java.util.logging.ConsoleHandler\n";

        Files.writeString(loggingPropertiesPath, loggingConfig);
        System.out.println("DevTomcat: Created logging.properties at " + loggingPropertiesPath);
    }

    /**
     * Setup basic parameters
     */
    private void setupBasicParameters(@NotNull JavaParameters params, @NotNull Path catalinaBase) {
        params.setDefaultCharset(project);
        params.setWorkingDirectory(catalinaBase.toFile());
        params.setJdk(ProjectRootManager.getInstance(project).getProjectSdk());
        params.setMainClass(TOMCAT_MAIN_CLASS);
        params.getProgramParametersList().add("start");
    }

    /**
     * Setup classpath
     */
    private void setupClasspath(@NotNull JavaParameters params, @NotNull Path catalinaHome) {
        params.getClassPath().add(catalinaHome.resolve("bin/bootstrap.jar").toFile());
        params.getClassPath().add(catalinaHome.resolve("bin/tomcat-juli.jar").toFile());
    }

    /**
     * Setup environment
     */
    private void setupEnvironment(@NotNull JavaParameters params) {
        boolean passParent = false;
        Map<String, String> env = null;

        if (configuration.getConfigData() != null && configuration.getConfigData().getVmConfig() != null) {
            passParent = configuration.getConfigData().getVmConfig().isPassParentEnvs();
            env = configuration.getConfigData().getVmConfig().getEnvironmentVariables();
        }

        params.setPassParentEnvs(passParent);
        if (env != null) {
            params.setEnv(env);
        }
    }

    /**
     * Setup VM options
     */
    private void setupVmOptions(@NotNull JavaParameters params,
                                @NotNull Path catalinaBase,
                                @NotNull Path catalinaHome,
                                int httpPort,
                                int shutdownPort,
                                int jmxPort,
                                int httpsPort) {
        ParametersList vmParams = params.getVMParametersList();

        // User-defined VM options
        String vmOptions = null;
        if (configuration.getConfigData() != null && configuration.getConfigData().getVmConfig() != null) {
            vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        }
        if (StringUtil.isNotEmpty(vmOptions)) {
            vmParams.addParametersString(vmOptions);
        }

        // JMX configuration
        if (configuration.isJmxEnabled()) {
            configureJmx(vmParams, jmxPort);
        }

        // HTTPS configuration
        if (configuration.isHttpsEnabled()) {
            configureHttps(vmParams, httpsPort);
        }

        // Hot deployment
        if (configuration.getConfigData().getDeploymentConfig().isHotDeploymentEnabled()) {
            configureHotDeployment(vmParams);
        }

        // Catalina system properties
        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome, httpPort, shutdownPort);
    }

    /**
     * Configure JMX settings
     */
    private void configureJmx(@NotNull ParametersList vmParams, int jmxPort) {
        vmParams.addProperty(JMX_REMOTE_PROP, "");
        vmParams.addProperty(JMX_PORT_PROP, String.valueOf(jmxPort));
        vmParams.addProperty(JMX_SSL_PROP, "false");
        vmParams.addProperty(JMX_AUTH_PROP, "false");
        vmParams.addProperty(JMX_LOCAL_PROP, "false");
    }

    /**
     * Configure HTTPS settings
     */
    private void configureHttps(@NotNull ParametersList vmParams, int httpsPort) {
        vmParams.addProperty("server.ssl.enabled", "true");
        vmParams.addProperty("server.port.https", String.valueOf(httpsPort));
    }

    /**
     * Configure hot deployment settings
     */
    private void configureHotDeployment(@NotNull ParametersList vmParams) {
        vmParams.addProperty("tomcat.autoreload.enabled", "true");
        vmParams.addProperty("tomcat.reloadable", "true");
        vmParams.addProperty("tomcat.antiResourceLocking", "false");
        vmParams.addProperty("tomcat.antiJARLocking", "false");
    }

    /**
     * Configure Catalina system properties
     */
    private void configureCatalinaProperties(@NotNull ParametersList vmParams,
                                             @NotNull Path catalinaBase,
                                             @NotNull Path catalinaHome,
                                             int httpPort,
                                             int shutdownPort) {
        vmParams.defineProperty(PARAM_CATALINA_HOME, catalinaHome.toString());
        vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
        vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve("temp").toString());
        vmParams.defineProperty(PARAM_LOGGING_CONFIG, catalinaBase.resolve("conf/logging.properties").toString());
        vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);
        vmParams.defineProperty("server.port", String.valueOf(httpPort));
        vmParams.defineProperty("server.shutdown.port", String.valueOf(shutdownPort));
    }

    /**
     * Setup deployment artifacts
     */
    private void setupDeploymentArtifacts(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        String serverMode = configuration.getConfigData().getServerMode();

        if ("Remote".equals(serverMode)) {
            configureRemoteDeployment(params);
        } else {
            configureLocalDeployment(params, catalinaBase);
        }
    }

    private void configureLocalDeployment(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        ParametersList vmParams = params.getVMParametersList();
        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getArtifacts()) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile artifactFile = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);

                if (artifactFile != null) {
                    String contextPath = artifact.getContextPath();
                    vmParams.addProperty("tomcat.webapp.path", artifactFile.getPath());
                    vmParams.addProperty("tomcat.webapp.context", contextPath);
                    LOG.info("Deploying artifact: " + artifactFile.getPath() + " with context: " + contextPath);
                } else {
                    LOG.warn("Deployment artifact not found: " + artifact.getPath());
                    throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
                }
            }
        }
    }

    private void configureRemoteDeployment(@NotNull JavaParameters params) throws ExecutionException {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();

        if (remoteConfig == null || !remoteConfig.isValid()) {
            throw new ExecutionException("Remote configuration is not valid");
        }

        String managerUrl = remoteConfig.getManagerUrl();
        if (StringUtil.isEmpty(managerUrl)) {
            throw new ExecutionException("Remote manager URL not specified");
        }

        ParametersList vmParams = params.getVMParametersList();
        vmParams.addProperty("tomcat.remote.manager.url", managerUrl);

        if (remoteConfig.isUseCredentials()) {
            vmParams.addProperty("tomcat.remote.username", remoteConfig.getUsername());
            vmParams.addProperty("tomcat.remote.password", remoteConfig.getPassword());
        }

        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getArtifacts()) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile artifactFile = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);

                if (artifactFile != null) {
                    String contextPath = artifact.getContextPath();
                    vmParams.addProperty("tomcat.webapp.path", artifactFile.getPath());
                    vmParams.addProperty("tomcat.webapp.context", contextPath);
                    LOG.info("Deploying artifact remotely: " + artifactFile.getPath() + " with context: " + contextPath);
                } else {
                    LOG.warn("Deployment artifact not found: " + artifact.getPath());
                    throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
                }
            }
        }
    }

    /**
     * Create builder for a configuration
     */
    public static TomcatJavaParametersBuilder create(@NotNull TomcatRunConfiguration configuration) {
        return new TomcatJavaParametersBuilder(configuration);
    }
}