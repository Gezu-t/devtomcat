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
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.dev.idea.plugins.tomcat.TomcatConstants;

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

    private final TomcatRunConfiguration configuration;
    private final Project project;
    private boolean debugMode = false;

    public TomcatJavaParametersBuilder(@NotNull TomcatRunConfiguration configuration) {
        this.configuration = configuration;
        this.project = configuration.getProject();
    }

    public TomcatJavaParametersBuilder setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        return this;
    }

    @NotNull
    public JavaParameters build() throws ExecutionException {
        try {
            Path catalinaBase = getCatalinaBase();
            Path catalinaHome = getCatalinaHome();

            int httpPort = PortUtils.DEFAULT_HTTP;
            int shutdownPort = PortUtils.DEFAULT_SHUTDOWN;
            int jmxPort = PortUtils.DEFAULT_JMX;
            int httpsPort = PortUtils.DEFAULT_HTTPS;

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

            if (!PortUtils.isValid(httpPort)) httpPort = PortUtils.DEFAULT_HTTP;
            if (!PortUtils.isValid(shutdownPort)) shutdownPort = PortUtils.DEFAULT_SHUTDOWN;
            if (!PortUtils.isValid(jmxPort)) jmxPort = PortUtils.DEFAULT_JMX;
            if (!PortUtils.isValid(httpsPort)) httpsPort = PortUtils.DEFAULT_HTTPS;

            if (shutdownPort == httpPort) shutdownPort = PortUtils.findNextAvailable(shutdownPort);
            if (jmxPort == httpPort || jmxPort == shutdownPort) jmxPort = PortUtils.findNextAvailable(jmxPort);
            if (httpsPort == httpPort || httpsPort == shutdownPort || httpsPort == jmxPort) httpsPort = PortUtils.findNextAvailable(httpsPort);

            if (!PortUtils.isAvailable(httpPort)) httpPort = PortUtils.findNextAvailable(httpPort);
            if (!PortUtils.isAvailable(shutdownPort)) shutdownPort = PortUtils.findNextAvailable(shutdownPort);
            if (configuration.isJmxEnabled() && !PortUtils.isAvailable(jmxPort)) jmxPort = PortUtils.findNextAvailable(jmxPort);
            if (configuration.isHttpsEnabled() && !PortUtils.isAvailable(httpsPort)) httpsPort = PortUtils.findNextAvailable(httpsPort);

            if (httpPort <= 0 || shutdownPort <= 0 || (configuration.isJmxEnabled() && jmxPort <= 0) || (configuration.isHttpsEnabled() && httpsPort <= 0)) {
                throw new ExecutionException("Unable to find available ports for Tomcat run configuration");
            }

            // Prepare catalina.base with directories and config files (after ports are resolved)
            prepareCatalinaBase(catalinaBase, catalinaHome, httpPort, shutdownPort);

            JavaParameters params = new JavaParameters();
            setupBasicParameters(params, catalinaBase);
            setupClasspath(params, catalinaHome);
            setupEnvironment(params);
            setupVmOptions(params, catalinaBase, catalinaHome, httpPort, shutdownPort, jmxPort, httpsPort);
            setupDeploymentArtifacts(params, catalinaBase);

            return params;

        } catch (IOException e) {
            throw new ExecutionException("Failed to prepare Tomcat directories", e);
        }
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
        if (configuration.getConfigData().getTomcatInfo() == null) {
            throw new ExecutionException("No Tomcat server configured");
        }
        return Paths.get(configuration.getConfigData().getTomcatInfo().getPath());
    }

    private void prepareCatalinaBase(@NotNull Path catalinaBase, @NotNull Path catalinaHome,
                                     int httpPort, int shutdownPort) throws IOException {
        Files.createDirectories(catalinaBase.resolve("temp"));
        Files.createDirectories(catalinaBase.resolve("logs"));
        Files.createDirectories(catalinaBase.resolve("webapps"));
        Files.createDirectories(catalinaBase.resolve("work"));
        Files.createDirectories(catalinaBase.resolve("conf"));

        // Always regenerate server.xml to reflect current port configuration
        copyAndCustomizeServerXml(catalinaHome, catalinaBase, httpPort, shutdownPort);

        // Copy config files from CATALINA_HOME if not already present
        copyIfAbsent(catalinaHome.resolve("conf/web.xml"), catalinaBase.resolve("conf/web.xml"));
        copyIfAbsent(catalinaHome.resolve("conf/context.xml"), catalinaBase.resolve("conf/context.xml"));
        copyIfAbsent(catalinaHome.resolve("conf/tomcat-users.xml"), catalinaBase.resolve("conf/tomcat-users.xml"));

        // Prefer CATALINA_HOME's logging.properties (has proper file handlers for this Tomcat version)
        Path loggingPropertiesPath = catalinaBase.resolve("conf/logging.properties");
        copyIfAbsent(catalinaHome.resolve("conf/logging.properties"), loggingPropertiesPath);
        // Fall back to our default with standard file handlers if CATALINA_HOME didn't have one
        if (!Files.exists(loggingPropertiesPath)) {
            createDefaultLoggingProperties(loggingPropertiesPath);
        }
    }

    private void copyAndCustomizeServerXml(@NotNull Path catalinaHome, @NotNull Path catalinaBase,
                                           int httpPort, int shutdownPort) throws IOException {
        Path sourceServerXml = catalinaHome.resolve("conf/server.xml");
        Path targetServerXml = catalinaBase.resolve("conf/server.xml");

        if (!Files.exists(sourceServerXml)) {
            LOG.warn("server.xml not found at " + sourceServerXml + ", generating minimal config");
            Files.writeString(targetServerXml, generateMinimalServerXml(httpPort, shutdownPort));
            return;
        }

        String xml = Files.readString(sourceServerXml);

        // Update <Server port="..." shutdown="SHUTDOWN"> shutdown port
        xml = Pattern.compile("(<Server\\s[^>]*port\\s*=\\s*\")[^\"]*\"")
                .matcher(xml)
                .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(shutdownPort)) + "\"");

        // Update the first HTTP/1.1 Connector port
        xml = Pattern.compile("(<Connector\\s[^>]*port\\s*=\\s*\")[^\"]*\"([^>]*protocol\\s*=\\s*\"HTTP/1\\.1\")")
                .matcher(xml)
                .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(httpPort)) + "\"$2");

        // Also handle the case where protocol comes before port
        if (!xml.contains("port=\"" + httpPort + "\"")) {
            xml = Pattern.compile("(<Connector\\s[^>]*protocol\\s*=\\s*\"HTTP/1\\.1\"[^>]*port\\s*=\\s*\")[^\"]*\"")
                    .matcher(xml)
                    .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(httpPort)) + "\"");
        }

        Files.writeString(targetServerXml, xml);
        LOG.info("Created server.xml at " + targetServerXml + " (HTTP=" + httpPort + ", shutdown=" + shutdownPort + ")");
    }

    private String generateMinimalServerXml(int httpPort, int shutdownPort) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Server port=\"" + shutdownPort + "\" shutdown=\"SHUTDOWN\">\n" +
                "  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n" +
                "  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n" +
                "  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n" +
                "  <Service name=\"Catalina\">\n" +
                "    <Connector port=\"" + httpPort + "\" protocol=\"HTTP/1.1\"\n" +
                "               connectionTimeout=\"20000\" redirectPort=\"8443\" />\n" +
                "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n" +
                "      <Host name=\"localhost\" appBase=\"webapps\"\n" +
                "            unpackWARs=\"true\" autoDeploy=\"true\" />\n" +
                "    </Engine>\n" +
                "  </Service>\n" +
                "</Server>\n";
    }

    private void copyIfAbsent(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.exists(target) && Files.exists(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Copied " + source.getFileName() + " to " + target);
        }
    }

    private void createDefaultLoggingProperties(@NotNull Path loggingPropertiesPath) throws IOException {
        String loggingConfig =
            "# Standard Tomcat logging configuration with file handlers\n" +
            "handlers = 1catalina.org.apache.juli.AsyncFileHandler, " +
                "2localhost.org.apache.juli.AsyncFileHandler, " +
                "3manager.org.apache.juli.AsyncFileHandler, " +
                "4host-manager.org.apache.juli.AsyncFileHandler, " +
                "java.util.logging.ConsoleHandler\n" +
            "\n" +
            ".handlers = 1catalina.org.apache.juli.AsyncFileHandler, java.util.logging.ConsoleHandler\n" +
            "\n" +
            "# Catalina log\n" +
            "1catalina.org.apache.juli.AsyncFileHandler.level = FINE\n" +
            "1catalina.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs\n" +
            "1catalina.org.apache.juli.AsyncFileHandler.prefix = catalina.\n" +
            "1catalina.org.apache.juli.AsyncFileHandler.maxDays = 90\n" +
            "1catalina.org.apache.juli.AsyncFileHandler.encoding = UTF-8\n" +
            "\n" +
            "# Localhost log\n" +
            "2localhost.org.apache.juli.AsyncFileHandler.level = FINE\n" +
            "2localhost.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs\n" +
            "2localhost.org.apache.juli.AsyncFileHandler.prefix = localhost.\n" +
            "2localhost.org.apache.juli.AsyncFileHandler.maxDays = 90\n" +
            "2localhost.org.apache.juli.AsyncFileHandler.encoding = UTF-8\n" +
            "\n" +
            "# Manager log\n" +
            "3manager.org.apache.juli.AsyncFileHandler.level = FINE\n" +
            "3manager.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs\n" +
            "3manager.org.apache.juli.AsyncFileHandler.prefix = manager.\n" +
            "3manager.org.apache.juli.AsyncFileHandler.maxDays = 90\n" +
            "3manager.org.apache.juli.AsyncFileHandler.encoding = UTF-8\n" +
            "\n" +
            "# Host-Manager log\n" +
            "4host-manager.org.apache.juli.AsyncFileHandler.level = FINE\n" +
            "4host-manager.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs\n" +
            "4host-manager.org.apache.juli.AsyncFileHandler.prefix = host-manager.\n" +
            "4host-manager.org.apache.juli.AsyncFileHandler.maxDays = 90\n" +
            "4host-manager.org.apache.juli.AsyncFileHandler.encoding = UTF-8\n" +
            "\n" +
            "# Console handler\n" +
            "java.util.logging.ConsoleHandler.level = FINE\n" +
            "java.util.logging.ConsoleHandler.formatter = org.apache.juli.OneLineFormatter\n" +
            "java.util.logging.ConsoleHandler.encoding = UTF-8\n" +
            "\n" +
            "# Webapp-specific log routing\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].handlers = 2localhost.org.apache.juli.AsyncFileHandler\n" +
            "\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/manager].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/manager].handlers = 3manager.org.apache.juli.AsyncFileHandler\n" +
            "\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/host-manager].level = INFO\n" +
            "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/host-manager].handlers = 4host-manager.org.apache.juli.AsyncFileHandler\n";

        Files.writeString(loggingPropertiesPath, loggingConfig);
        LOG.debug("DevTomcat: Created logging.properties at " + loggingPropertiesPath);
    }

    private void setupBasicParameters(@NotNull JavaParameters params, @NotNull Path catalinaBase) {
        params.setDefaultCharset(project);
        params.setWorkingDirectory(catalinaBase.toFile());
        params.setJdk(ProjectRootManager.getInstance(project).getProjectSdk());
        params.setMainClass(TOMCAT_MAIN_CLASS);
        params.getProgramParametersList().add("start");
    }

    private void setupClasspath(@NotNull JavaParameters params, @NotNull Path catalinaHome) {
        params.getClassPath().add(catalinaHome.resolve("bin/bootstrap.jar").toFile());
        params.getClassPath().add(catalinaHome.resolve("bin/tomcat-juli.jar").toFile());
    }

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

    private void setupVmOptions(@NotNull JavaParameters params,
                                @NotNull Path catalinaBase,
                                @NotNull Path catalinaHome,
                                int httpPort,
                                int shutdownPort,
                                int jmxPort,
                                int httpsPort) {
        ParametersList vmParams = params.getVMParametersList();

        String vmOptions = null;
        if (configuration.getConfigData() != null && configuration.getConfigData().getVmConfig() != null) {
            vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        }
        if (StringUtil.isNotEmpty(vmOptions)) {
            vmParams.addParametersString(vmOptions);
        }

        if (configuration.isJmxEnabled()) {
            configureJmx(vmParams, jmxPort);
        }

        if (configuration.isHttpsEnabled()) {
            configureHttps(vmParams, httpsPort);
        }

        if (debugMode) {
            String existingVmOptions = configuration.getConfigData().getVmConfig() != null
                    ? configuration.getConfigData().getVmConfig().getVmOptions() : "";
            if (existingVmOptions == null || !existingVmOptions.contains("-agentlib:jdwp")) {
                var debugConfig = configuration.getConfigData().getDebugConfig();
                if (debugConfig != null && debugConfig.isValid()) {
                    String jdwpArg = debugConfig.getDebugVmArgument();
                    vmParams.add(jdwpArg);
                    LOG.info("Debug mode enabled: " + jdwpArg);
                }
            }
        }

        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome, httpPort, shutdownPort);
    }

    private void configureJmx(@NotNull ParametersList vmParams, int jmxPort) {
        vmParams.addProperty(JMX_REMOTE_PROP, "");
        vmParams.addProperty(JMX_PORT_PROP, String.valueOf(jmxPort));
        vmParams.addProperty(JMX_SSL_PROP, "false");
        vmParams.addProperty(JMX_AUTH_PROP, "false");
        vmParams.addProperty(JMX_LOCAL_PROP, "false");
    }

    private void configureHttps(@NotNull ParametersList vmParams, int httpsPort) {
        vmParams.addProperty("server.ssl.enabled", "true");
        vmParams.addProperty("server.port.https", String.valueOf(httpsPort));
    }

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

    private void setupDeploymentArtifacts(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        String serverMode = configuration.getConfigData().getServerMode();

        if (TomcatConstants.MODE_REMOTE.equals(serverMode)) {
            configureRemoteDeployment(params);
        } else {
            configureLocalDeployment(params, catalinaBase);
        }
    }

    private void configureLocalDeployment(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        Path webappsDir = catalinaBase.resolve("webapps");
        Path confCatalinaLocalhost = catalinaBase.resolve("conf/Catalina/localhost");

        try {
            Files.createDirectories(webappsDir);
            Files.createDirectories(confCatalinaLocalhost);
        } catch (IOException e) {
            throw new ExecutionException("Failed to create deployment directories", e);
        }

        boolean hotDeploy = configuration.getConfigData().getDeploymentConfig().isHotDeploymentEnabled();

        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;

            String contextPath = artifact.getContextPath();
            String contextName;
            if ("/".equals(contextPath) || contextPath == null || contextPath.isEmpty()) {
                contextName = "ROOT";
            } else {
                contextName = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
            }

            Path artifactPath = Paths.get(artifact.getPath());
            if (!Files.exists(artifactPath)) {
                throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
            }

            try {
                if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                        || Files.isDirectory(artifactPath)) {
                    String contextXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<Context docBase=\"" + artifactPath.toString().replace("\"", "&quot;")
                            + "\" reloadable=\"" + hotDeploy + "\" />\n";
                    Path contextFile = confCatalinaLocalhost.resolve(contextName + ".xml");
                    Files.writeString(contextFile, contextXml);
                    LOG.info("Deployed exploded artifact via context.xml: " + contextFile);
                } else {
                    Path targetWar = webappsDir.resolve(contextName + ".war");
                    Files.copy(artifactPath, targetWar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Deployed WAR artifact: " + targetWar);
                }
            } catch (IOException e) {
                throw new ExecutionException("Failed to deploy artifact: " + artifact.getPath(), e);
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

    public static TomcatJavaParametersBuilder create(@NotNull TomcatRunConfiguration configuration) {
        return new TomcatJavaParametersBuilder(configuration);
    }
}