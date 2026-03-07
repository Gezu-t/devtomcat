package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String PARAM_REMOTE_MANAGER_URL = "tomcat.remote.manager.url";
    private static final String PARAM_REMOTE_USERNAME = "tomcat.remote.username";
    private static final String PARAM_REMOTE_PASSWORD = "tomcat.remote.password";
    private static final String PARAM_WEBAPP_PATH = "tomcat.webapp.path";
    private static final String PARAM_WEBAPP_CONTEXT = "tomcat.webapp.context";
    private static final String PARAM_WEBAPP_COUNT = "tomcat.webapp.count";

    // --- Tomcat PostResources (context.xml overlay) ---
    private static final String RESOURCE_CLASS_DIR = "org.apache.catalina.webresources.DirResourceSet";
    private static final String RESOURCE_CLASS_FILE = "org.apache.catalina.webresources.FileResourceSet";
    private static final String WEBAPP_MOUNT_CLASSES = "/WEB-INF/classes";
    private static final String WEBAPP_MOUNT_LIB = "/WEB-INF/lib/";

    private static final String POST_RESOURCE_TEMPLATE =
            "\n    <PostResources className=\"%s\"\n                    base=\"%s\" webAppMount=\"%s\" />";

    private final TomcatRunConfiguration configuration;
    private final Project project;
    private final ExecutionEnvironment environment;
    private boolean debugMode = false;
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
                // Fallback: resolve ports here (e.g., when called without TomcatCommandLineState)
                httpPort = getConfigPort(configuration.getHttpPort(), PortUtils.DEFAULT_HTTP);
                shutdownPort = getConfigPort(configuration.getShutdownPort(), PortUtils.DEFAULT_SHUTDOWN);
                jmxPort = getConfigPort(configuration.getJmxPort(), PortUtils.DEFAULT_JMX);
                httpsPort = getConfigPort(configuration.getHttpsPort(), PortUtils.DEFAULT_HTTPS);
                ajpPort = getConfigPort(configuration.getAjpPort(), PortUtils.DEFAULT_AJP);

                // Resolve internal conflicts (same port used by multiple services)
                if (shutdownPort == httpPort) shutdownPort = PortUtils.findNextAvailable(shutdownPort);
                if (jmxPort == httpPort || jmxPort == shutdownPort) jmxPort = PortUtils.findNextAvailable(jmxPort);
                if (httpsPort == httpPort || httpsPort == shutdownPort || httpsPort == jmxPort) httpsPort = PortUtils.findNextAvailable(httpsPort);
                if (ajpPort == httpPort || ajpPort == shutdownPort || ajpPort == jmxPort || ajpPort == httpsPort) ajpPort = PortUtils.findNextAvailable(ajpPort);

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
        return port;
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
        Files.createDirectories(catalinaBase.resolve(DIR_TEMP));
        Files.createDirectories(catalinaBase.resolve(DIR_LOGS));
        Files.createDirectories(catalinaBase.resolve(DIR_WEBAPPS));
        Files.createDirectories(catalinaBase.resolve(DIR_WORK));
        Files.createDirectories(catalinaBase.resolve(DIR_CONF));

        // Always regenerate server.xml to reflect current port configuration
        copyAndCustomizeServerXml(catalinaHome, catalinaBase, httpPort, shutdownPort, httpsPort, ajpPort);

        // Copy config files from CATALINA_HOME if not already present
        copyIfAbsent(catalinaHome.resolve(CONFIG_WEB_XML), catalinaBase.resolve(CONFIG_WEB_XML));
        copyIfAbsent(catalinaHome.resolve(CONFIG_CONTEXT_XML), catalinaBase.resolve(CONFIG_CONTEXT_XML));
        copyIfAbsent(catalinaHome.resolve(CONFIG_TOMCAT_USERS_XML), catalinaBase.resolve(CONFIG_TOMCAT_USERS_XML));
        copyIfAbsent(catalinaHome.resolve(CONFIG_CATALINA_PROPERTIES), catalinaBase.resolve(CONFIG_CATALINA_PROPERTIES));

        // Prefer CATALINA_HOME's logging.properties (has proper file handlers for this Tomcat version)
        Path loggingPropertiesPath = catalinaBase.resolve(CONFIG_LOGGING_PROPERTIES);
        copyIfAbsent(catalinaHome.resolve(CONFIG_LOGGING_PROPERTIES), loggingPropertiesPath);
        // Fall back to our default with standard file handlers if CATALINA_HOME didn't have one
        if (!Files.exists(loggingPropertiesPath)) {
            createDefaultLoggingProperties(loggingPropertiesPath);
        }
    }

    private void copyAndCustomizeServerXml(@NotNull Path catalinaHome, @NotNull Path catalinaBase,
                                           int httpPort, int shutdownPort,
                                           int httpsPort, int ajpPort) throws IOException {
        Path sourceServerXml = catalinaHome.resolve(CONFIG_SERVER_XML);
        Path targetServerXml = catalinaBase.resolve(CONFIG_SERVER_XML);

        boolean httpsEnabled = configuration.isHttpsEnabled();
        boolean ajpEnabled = configuration.isAjpEnabled();

        if (!Files.exists(sourceServerXml)) {
            LOG.warn("server.xml not found at " + sourceServerXml + ", generating minimal config");
            Files.writeString(targetServerXml, generateMinimalServerXml(httpPort, shutdownPort, httpsPort, httpsEnabled, ajpPort, ajpEnabled));
            return;
        }

        String xml = Files.readString(sourceServerXml);

        // Update <Server port="..." shutdown="SHUTDOWN"> shutdown port
        xml = Pattern.compile("(<Server\\s[^>]*port\\s*=\\s*\")[^\"]*\"")
                .matcher(xml)
                .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(shutdownPort)) + "\"");

        // Update the first HTTP/1.1 Connector port
        String httpProtoEsc = Pattern.quote(PROTOCOL_HTTP);
        xml = Pattern.compile("(<Connector\\s[^>]*port\\s*=\\s*\")[^\"]*\"([^>]*protocol\\s*=\\s*\"" + httpProtoEsc + "\")")
                .matcher(xml)
                .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(httpPort)) + "\"$2");

        // Also handle the case where protocol comes before port
        if (!xml.contains("port=\"" + httpPort + "\"")) {
            xml = Pattern.compile("(<Connector\\s[^>]*protocol\\s*=\\s*\"" + httpProtoEsc + "\"[^>]*port\\s*=\\s*\")[^\"]*\"")
                    .matcher(xml)
                    .replaceFirst("$1" + Matcher.quoteReplacement(String.valueOf(httpPort)) + "\"");
        }

        // Update redirectPort on HTTP connector to match HTTPS port
        if (httpsEnabled) {
            xml = Pattern.compile("(protocol\\s*=\\s*\"" + httpProtoEsc + "\"[^>]*redirectPort\\s*=\\s*\")[^\"]*\"")
                    .matcher(xml)
                    .replaceFirst("$1" + httpsPort + "\"");
        }

        // Handle HTTPS connector
        if (httpsEnabled) {
            xml = applyHttpsConnector(xml, httpsPort);
        }

        // Handle AJP connector
        if (ajpEnabled) {
            xml = applyAjpConnector(xml, ajpPort);
        }

        // Verify critical ports were actually written — regex failures would leave original ports
        if (!xml.contains("port=\"" + httpPort + "\"")) {
            LOG.warn("server.xml may not contain the expected HTTP port " + httpPort +
                    ". The source server.xml may have a non-standard format.");
        }
        if (!xml.contains("port=\"" + shutdownPort + "\"")) {
            LOG.warn("server.xml may not contain the expected shutdown port " + shutdownPort);
        }

        Files.writeString(targetServerXml, xml);
        LOG.info("Created server.xml at " + targetServerXml + " (HTTP=" + httpPort +
                ", shutdown=" + shutdownPort +
                (httpsEnabled ? ", HTTPS=" + httpsPort : "") +
                (ajpEnabled ? ", AJP=" + ajpPort : "") + ")");
    }

    private String applyHttpsConnector(@NotNull String xml, int httpsPort) {
        // Try to find an existing HTTPS connector (SSLEnabled="true" or scheme="https" or Http11NioProtocol with SSL)
        Pattern httpsConnectorPort = Pattern.compile(
                "(<Connector\\s[^>]*(?:SSLEnabled\\s*=\\s*\"true\"|scheme\\s*=\\s*\"https\")[^>]*port\\s*=\\s*\")[^\"]*\"");
        Matcher m = httpsConnectorPort.matcher(xml);
        if (m.find()) {
            // Update existing HTTPS connector port
            return m.replaceFirst("$1" + httpsPort + "\"");
        }

        // Try alternate attribute order: port before SSLEnabled
        Pattern httpsConnectorPortFirst = Pattern.compile(
                "(<Connector\\s[^>]*port\\s*=\\s*\")[^\"]*\"([^>]*(?:SSLEnabled\\s*=\\s*\"true\"|scheme\\s*=\\s*\"https\"))");
        m = httpsConnectorPortFirst.matcher(xml);
        if (m.find()) {
            return m.replaceFirst("$1" + httpsPort + "\"$2");
        }

        // No existing HTTPS connector found — inject one after the HTTP connector
        String httpsConnector = "\n    <Connector port=\"" + httpsPort + "\" protocol=\"" + PROTOCOL_HTTPS + "\"\n" +
                "               maxThreads=\"150\" SSLEnabled=\"true\">\n" +
                "      <!-- Configure SSL certificate in server.xml or use JVM keystore -->\n" +
                "    </Connector>";

        // Insert after the first Connector closing tag
        String httpProtoEsc = Pattern.quote(PROTOCOL_HTTP);
        Pattern firstConnectorEnd = Pattern.compile("(<Connector\\s[^>]*protocol\\s*=\\s*\"" + httpProtoEsc + "\"[^/]*/>)");
        m = firstConnectorEnd.matcher(xml);
        if (m.find()) {
            return xml.substring(0, m.end()) + httpsConnector + xml.substring(m.end());
        }

        return xml;
    }

    private String applyAjpConnector(@NotNull String xml, int ajpPort) {
        String ajpProtoEsc = Pattern.quote(PROTOCOL_AJP);

        // Try to find an existing AJP connector
        Pattern ajpConnectorPort = Pattern.compile(
                "(<Connector\\s[^>]*protocol\\s*=\\s*\"" + ajpProtoEsc + "\"[^>]*port\\s*=\\s*\")[^\"]*\"");
        Matcher m = ajpConnectorPort.matcher(xml);
        if (m.find()) {
            // Update existing AJP connector port
            return m.replaceFirst("$1" + ajpPort + "\"");
        }

        // Try alternate attribute order: port before protocol
        Pattern ajpConnectorPortFirst = Pattern.compile(
                "(<Connector\\s[^>]*port\\s*=\\s*\")[^\"]*\"([^>]*protocol\\s*=\\s*\"" + ajpProtoEsc + "\")");
        m = ajpConnectorPortFirst.matcher(xml);
        if (m.find()) {
            return m.replaceFirst("$1" + ajpPort + "\"$2");
        }

        // Also check for commented-out AJP connector (common in Tomcat 9+) and uncomment it
        Pattern commentedAjp = Pattern.compile("<!--\\s*(<Connector\\s[^>]*protocol\\s*=\\s*\"" + ajpProtoEsc + "\"[^>]*/>)\\s*-->");
        m = commentedAjp.matcher(xml);
        if (m.find()) {
            String uncommented = m.group(1);
            // Update the port in the uncommented connector
            uncommented = uncommented.replaceFirst("port\\s*=\\s*\"[^\"]*\"", "port=\"" + ajpPort + "\"");
            // Add secretRequired="false" if not present (needed for Tomcat 9+)
            if (!uncommented.contains("secretRequired")) {
                uncommented = uncommented.replaceFirst("/>", " secretRequired=\"false\" />");
            }
            return xml.substring(0, m.start()) + uncommented + xml.substring(m.end());
        }

        // No existing AJP connector — inject one before the Engine element
        String ajpConnector = "\n    <Connector port=\"" + ajpPort + "\" protocol=\"" + PROTOCOL_AJP + "\"\n" +
                "               secretRequired=\"false\" redirectPort=\"" + PortConfig.DEFAULT_HTTPS_PORT + "\" />";

        int engineIndex = xml.indexOf("<Engine");
        if (engineIndex > 0) {
            return xml.substring(0, engineIndex) + ajpConnector + "\n    " + xml.substring(engineIndex);
        }

        return xml;
    }

    private String generateMinimalServerXml(int httpPort, int shutdownPort,
                                            int httpsPort, boolean httpsEnabled,
                                            int ajpPort, boolean ajpEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Server port=\"").append(shutdownPort).append("\" shutdown=\"").append(SHUTDOWN_COMMAND).append("\">\n");
        sb.append("  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n");
        sb.append("  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n");
        sb.append("  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n");
        sb.append("  <Service name=\"Catalina\">\n");
        sb.append("    <Connector port=\"").append(httpPort).append("\" protocol=\"").append(PROTOCOL_HTTP).append("\"\n");
        sb.append("               connectionTimeout=\"20000\" redirectPort=\"")
                .append(httpsEnabled ? httpsPort : PortConfig.DEFAULT_HTTPS_PORT).append("\" />\n");

        if (httpsEnabled) {
            sb.append("    <Connector port=\"").append(httpsPort).append("\" protocol=\"").append(PROTOCOL_HTTPS).append("\"\n");
            sb.append("               maxThreads=\"150\" SSLEnabled=\"true\">\n");
            sb.append("      <!-- Configure SSL certificate in server.xml or use JVM keystore -->\n");
            sb.append("    </Connector>\n");
        }

        if (ajpEnabled) {
            sb.append("    <Connector port=\"").append(ajpPort).append("\" protocol=\"").append(PROTOCOL_AJP).append("\"\n");
            sb.append("               secretRequired=\"false\" redirectPort=\"")
                    .append(httpsEnabled ? httpsPort : PortConfig.DEFAULT_HTTPS_PORT).append("\" />\n");
        }

        sb.append("    <Engine name=\"Catalina\" defaultHost=\"").append(DEFAULT_HOST).append("\">\n");
        sb.append("      <Host name=\"").append(DEFAULT_HOST).append("\" appBase=\"").append(DIR_WEBAPPS).append("\"\n");
        sb.append("            unpackWARs=\"true\" autoDeploy=\"true\" />\n");
        sb.append("    </Engine>\n");
        sb.append("  </Service>\n");
        sb.append("</Server>\n");
        return sb.toString();
    }

    private void copyIfAbsent(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.exists(target) && Files.exists(source)) {
            Files.copy(source, target);
            LOG.info("Copied " + source.getFileName() + " to " + target);
        }
    }

    private static final String DEFAULT_LOGGING_RESOURCE = "/defaults/logging.properties";

    private void createDefaultLoggingProperties(@NotNull Path loggingPropertiesPath) throws IOException {
        try (var stream = getClass().getResourceAsStream(DEFAULT_LOGGING_RESOURCE)) {
            if (stream != null) {
                Files.copy(stream, loggingPropertiesPath);
            } else {
                LOG.warn("Bundled logging.properties not found at " + DEFAULT_LOGGING_RESOURCE);
                return;
            }
        }
        LOG.debug("DevTomcat: Created logging.properties at " + loggingPropertiesPath);
    }

    private void setupBasicParameters(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        params.setDefaultCharset(project);
        params.setWorkingDirectory(catalinaBase.toFile());
        params.setJdk(resolveJdk());
        params.setMainClass(TOMCAT_MAIN_CLASS);
        params.getProgramParametersList().add("start");
    }

    @NotNull
    private Sdk resolveJdk() throws ExecutionException {
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
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null) {
            throw new ExecutionException("No JDK configured for the project. Please configure a Project SDK in File → Project Structure.");
        }
        return sdk;
    }

    private void setupClasspath(@NotNull JavaParameters params, @NotNull Path catalinaHome) {
        params.getClassPath().add(catalinaHome.resolve(JAR_BOOTSTRAP).toFile());
        params.getClassPath().add(catalinaHome.resolve(JAR_TOMCAT_JULI).toFile());
    }

    private void setupEnvironment(@NotNull JavaParameters params) {
        String executorId = environment != null && environment.getExecutor() != null
                ? environment.getExecutor().getId() : "Run";
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
                                int httpsPort) {
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

        if (debugMode) {
            String existingVmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
            if (existingVmOptions == null || !existingVmOptions.contains("-agentlib:jdwp")) {
                var debugConfig = configuration.getConfigData().getDebugConfig();
                if (debugConfig != null && debugConfig.isValid()) {
                    String jdwpArg = debugConfig.getDebugVmArgument();
                    vmParams.add(jdwpArg);
                    LOG.info("Debug mode enabled: " + jdwpArg);
                }
            }
        }

        // JDK 17+ module opens required by Tomcat (previously delivered via JDK_JAVA_OPTIONS env var)
        configureModuleOpens(vmParams);

        configureCatalinaProperties(vmParams, catalinaBase, catalinaHome, httpPort, shutdownPort);
    }

    private void configureModuleOpens(@NotNull ParametersList vmParams) {
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
        vmParams.addProperty(JMX_REMOTE_PROP, "");
        vmParams.addProperty(JMX_PORT_PROP, String.valueOf(jmxPort));
        vmParams.addProperty(JMX_SSL_PROP, "false");
        vmParams.addProperty(JMX_AUTH_PROP, "false");
        vmParams.addProperty(JMX_LOCAL_PROP, "false");
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
        String serverMode = configuration.getConfigData().getServerMode();

        if (TomcatConstants.MODE_REMOTE.equals(serverMode)) {
            configureRemoteDeployment(params);
        } else {
            configureLocalDeployment(params, catalinaBase);
        }
    }

    private void configureLocalDeployment(@NotNull JavaParameters params, @NotNull Path catalinaBase) throws ExecutionException {
        Path webappsDir = catalinaBase.resolve(DIR_WEBAPPS);
        Path confCatalinaLocalhost = catalinaBase.resolve(CONTEXT_XML_DIR);

        try {
            Files.createDirectories(webappsDir);
            Files.createDirectories(confCatalinaLocalhost);
            cleanStaleDeployments(webappsDir, confCatalinaLocalhost);
        } catch (IOException e) {
            throw new ExecutionException("Failed to create deployment directories", e);
        }

        boolean hotDeploy = configuration.getConfigData().getDeploymentConfig().isHotDeploymentEnabled();
        boolean preserveSessions = configuration.getConfigData().getDeploymentConfig().isPreserveSessions();

        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getDeployedArtifacts()) {
            if (artifact == null || !artifact.isValid()) continue;

            String contextPath = artifact.getContextPath();
            String contextName;
            if (contextPath == null || contextPath.isEmpty() || DEFAULT_CONTEXT_PATH.equals(contextPath)) {
                contextName = ROOT_CONTEXT_NAME;
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
                    String contextXml = buildContextXml(artifact, artifactPath, hotDeploy, preserveSessions);
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

    @NotNull
    private String buildContextXml(@NotNull DeploymentArtifact artifact,
                                   @NotNull Path artifactPath,
                                   boolean hotDeploy,
                                   boolean preserveSessions) {
        String extraResources = buildExtraResourcesXml(artifact, artifactPath);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Context docBase=\"").append(escapeXmlAttribute(artifactPath.toString()));
        xml.append("\" reloadable=\"").append(hotDeploy).append("\">");

        if (preserveSessions) {
            xml.append("\n  <Manager pathname=\"SESSIONS.ser\" />");
        }
        if (!extraResources.isEmpty()) {
            xml.append("\n  <Resources allowLinking=\"true\">");
            xml.append(extraResources);
            xml.append("\n  </Resources>");
        }

        xml.append("\n</Context>\n");
        return xml.toString();
    }

    private void cleanStaleDeployments(@NotNull Path webappsDir, @NotNull Path confDir) {
        // Remove previous context XML descriptors to prevent conflicts with new deployments
        try (var stream = Files.list(confDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".xml"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException e) { LOG.debug("Failed to clean: " + p, e); }
                  });
        } catch (IOException e) {
            LOG.debug("Could not clean conf directory: " + confDir, e);
        }

        // Remove previous WAR files to prevent WAR/context XML conflicts
        try (var stream = Files.list(webappsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".war"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException e) { LOG.debug("Failed to clean: " + p, e); }
                  });
        } catch (IOException e) {
            LOG.debug("Could not clean webapps directory: " + webappsDir, e);
        }
    }

    private static String escapeXmlAttribute(@NotNull String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    /**
     * Builds extra {@code <PostResources>} XML entries for an exploded artifact's context XML.
     * This adds module classpath entries (class output dirs and dependency JARs) that are not
     * already present in the artifact's WEB-INF/lib or WEB-INF/classes, matching how IntelliJ
     * Ultimate enriches the webapp classloader for multi-module projects.
     */
    @NotNull
    private String buildExtraResourcesXml(@NotNull DeploymentArtifact artifact, @NotNull Path artifactPath) {
        Module module = findModuleForArtifact(artifact);
        if (module == null) {
            LOG.info("No module found for artifact '" + artifact.getName() + "', skipping extra classpath");
            return "";
        }

        // Collect existing JARs in WEB-INF/lib to avoid duplicates
        Set<String> existingLibJars = new HashSet<>();
        Path webInfLib = artifactPath.resolve(WEB_INF).resolve(WEB_INF_LIB);
        if (Files.isDirectory(webInfLib)) {
            try (var stream = Files.list(webInfLib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                      .forEach(p -> existingLibJars.add(p.getFileName().toString()));
            } catch (IOException e) {
                LOG.debug("Could not list WEB-INF/lib: " + e.getMessage());
            }
        }

        // Normalize artifact paths for cross-platform comparison
        // (VirtualFile uses '/' everywhere, Path uses OS separator on Windows)
        String artifactAbsPath = artifactPath.toAbsolutePath().toString().replace('\\', '/');
        String webInfClassesPath = artifactPath.resolve(WEB_INF).resolve(WEB_INF_CLASSES)
                .toAbsolutePath().toString().replace('\\', '/');

        // Get all runtime classpath entries from the module (recursively includes dependencies)
        List<String> extraDirs = new ArrayList<>();
        List<String> extraJars = new ArrayList<>();

        VirtualFile[] classesRoots = OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .classes()
                .getRoots();

        for (VirtualFile root : classesRoots) {
            String rootPath = root.getPath();
            // Strip trailing !/ from JAR URLs
            if (rootPath.endsWith("!/")) {
                rootPath = rootPath.substring(0, rootPath.length() - 2);
            }

            // Skip entries already under the artifact's docBase
            if (rootPath.startsWith(artifactAbsPath)) {
                continue;
            }

            // Convert to OS-native path for File operations and context XML
            String nativePath = rootPath.replace('/', File.separatorChar);
            File file = new File(nativePath);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                // Class output directory — skip if it IS the artifact's WEB-INF/classes
                if (rootPath.equals(webInfClassesPath)) continue;
                extraDirs.add(nativePath);
            } else if (rootPath.endsWith(".jar")) {
                // JAR file — skip if same-named JAR already in WEB-INF/lib
                String jarName = file.getName();
                if (existingLibJars.contains(jarName)) continue;
                extraJars.add(nativePath);
            }
        }

        if (extraDirs.isEmpty() && extraJars.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String dir : extraDirs) {
            sb.append(String.format(POST_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_DIR, escapeXmlAttribute(dir), WEBAPP_MOUNT_CLASSES));
        }
        for (String jar : extraJars) {
            String jarName = new File(jar).getName();
            sb.append(String.format(POST_RESOURCE_TEMPLATE,
                    RESOURCE_CLASS_FILE, escapeXmlAttribute(jar),
                    WEBAPP_MOUNT_LIB + escapeXmlAttribute(jarName)));
        }

        LOG.info("Added " + extraDirs.size() + " class dirs and " + extraJars.size() +
                " JARs as extra resources for artifact '" + artifact.getName() + "'");
        return sb.toString();
    }

    /**
     * Finds the IntelliJ Module associated with a deployment artifact.
     * Tries: artifact name match via ArtifactManager, name-based module lookup,
     * path-based matching, and web module fallback.
     */
    @Nullable
    private Module findModuleForArtifact(@NotNull DeploymentArtifact artifact) {
        try {
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            String name = artifact.getName();

            // 1. Try to find IntelliJ Artifact by name → extract module from packaging elements
            ArtifactManager artifactManager = ArtifactManager.getInstance(project);
            for (Artifact a : artifactManager.getArtifacts()) {
                if (name.equals(a.getName())) {
                    // Artifact name often follows pattern "moduleName:war exploded"
                    String moduleName = a.getName().replaceAll(":war.*$", "").trim();
                    Module module = moduleManager.findModuleByName(moduleName);
                    if (module != null) return module;
                    break;
                }
            }

            // 2. Direct name-based lookup (strip suffixes)
            String baseName = name.replaceAll(":war.*$", "")
                                  .replaceAll("\\.war$", "")
                                  .replaceAll("\\s*\\(.*\\)$", "")
                                  .trim();
            Module module = moduleManager.findModuleByName(baseName);
            if (module != null) return module;

            // 3. Path-based: find module whose content root contains the deployment path
            String deploymentPath = artifact.getPath();
            if (!deploymentPath.isEmpty()) {
                for (Module m : moduleManager.getModules()) {
                    for (VirtualFile contentRoot : ModuleRootManager.getInstance(m).getContentRoots()) {
                        if (deploymentPath.startsWith(contentRoot.getPath())) {
                            if (TomcatModuleUtils.isWebModule(m)) {
                                return m;
                            }
                        }
                    }
                }
            }

            // 4. Single web module fallback
            List<Module> webModules = new ArrayList<>();
            for (Module m : moduleManager.getModules()) {
                if (TomcatModuleUtils.isWebModule(m)) {
                    webModules.add(m);
                }
            }
            if (webModules.size() == 1) return webModules.get(0);

            // 5. Partial name match against web modules
            for (Module m : webModules) {
                String mName = m.getName().toLowerCase();
                String lowerBase = baseName.toLowerCase();
                if (mName.contains(lowerBase) || lowerBase.contains(mName)) {
                    return m;
                }
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Failed to find module for artifact '" + artifact.getName() + "': " + e.getMessage());
            return null;
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
        vmParams.addProperty(PARAM_REMOTE_MANAGER_URL, managerUrl);

        if (remoteConfig.isUseCredentials()) {
            vmParams.addProperty(PARAM_REMOTE_USERNAME, remoteConfig.getUsername());
            vmParams.addProperty(PARAM_REMOTE_PASSWORD, remoteConfig.getPassword());
        }

        List<DeploymentArtifact> artifacts = configuration.getConfigData().getDeploymentConfig().getDeployedArtifacts();
        int index = 0;
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile artifactFile = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);

                if (artifactFile != null) {
                    String contextPath = artifact.getContextPath();
                    vmParams.addProperty(PARAM_WEBAPP_PATH + "." + index, artifactFile.getPath());
                    vmParams.addProperty(PARAM_WEBAPP_CONTEXT + "." + index, contextPath);
                    LOG.info("Deploying artifact remotely [" + index + "]: " + artifactFile.getPath() + " with context: " + contextPath);
                    index++;
                } else {
                    LOG.warn("Deployment artifact not found: " + artifact.getPath());
                    throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
                }
            }
        }
        vmParams.addProperty(PARAM_WEBAPP_COUNT, String.valueOf(index));
    }

    public static TomcatJavaParametersBuilder create(@NotNull TomcatRunConfiguration configuration,
                                                     @NotNull ExecutionEnvironment environment) {
        return new TomcatJavaParametersBuilder(configuration, environment);
    }
}
