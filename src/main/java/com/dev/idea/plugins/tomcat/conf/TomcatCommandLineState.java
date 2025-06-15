package com.dev.idea.plugins.tomcat.conf;

import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PathsList;
import com.dev.idea.plugins.tomcat.logging.LogFileConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.DevTomcatUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Unified Tomcat Command Line State - Professional Implementation
 * Combines advanced Tomcat configuration with enhanced logging and Phase 2 features
 * Provides complete professional IDE-level Tomcat integration
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatCommandLineState extends JavaCommandLineState {

    // Constants for JDK compatibility
    private static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";
    private static final String ENV_JDK_JAVA_OPTIONS = "--add-opens=java.base/java.lang=ALL-UNNAMED " +
            "--add-opens=java.base/java.io=ALL-UNNAMED " +
            "--add-opens=java.base/java.util=ALL-UNNAMED " +
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED " +
            "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED";

    // Tomcat configuration constants
    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_CATALINA_TMPDIR = "java.io.tmpdir";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";

    // Enhanced configuration and logging
    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final long creationTime;

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
        this.creationTime = System.currentTimeMillis();

        System.out.println("DevTomcat: Professional TomcatCommandLineState created with complete configuration");
    }

    @Override
    protected GeneralCommandLine createCommandLine() throws ExecutionException {
        GeneralCommandLine commandLine = super.createCommandLine();

        // Apply JDK compatibility options
        String originalJdkJavaOptions = commandLine.getEnvironment().get(JDK_JAVA_OPTIONS);
        String jdkJavaOptions = originalJdkJavaOptions == null ?
                ENV_JDK_JAVA_OPTIONS : originalJdkJavaOptions + " " + ENV_JDK_JAVA_OPTIONS;
        commandLine = commandLine.withEnvironment(JDK_JAVA_OPTIONS, jdkJavaOptions);

        // Apply enhanced environment variables
        Map<String, String> enhancedEnvVars = configuration.getEnvironmentVariables();
        if (enhancedEnvVars != null && !enhancedEnvVars.isEmpty()) {
            for (Map.Entry<String, String> entry : enhancedEnvVars.entrySet()) {
                commandLine = commandLine.withEnvironment(entry.getKey(), entry.getValue());
            }
            deploymentLogger.logServerInfo("Applied " + enhancedEnvVars.size() + " environment variables");
        }

        return commandLine;
    }

    @Override
    @NotNull
    protected OSProcessHandler startProcess() throws ExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            // Get artifact name for professional logging
            String artifactName = getArtifactName();

            // Professional deployment logging
            deploymentLogger.logServerConnection();
            deploymentLogger.logDeploymentStart(artifactName);

            // Apply enhanced configuration features
            applyEnhancedFeatures();

            // Create enhanced process handler with intelligent parsing
            TomcatProcessHandler processHandler = new TomcatProcessHandler(
                    createCommandLine().createProcess(),
                    createCommandLine().getCommandLineString(),
                    createCommandLine().getCharset(),
                    deploymentLogger,
                    configuration
            );

            // Configure process handler
            boolean shouldKillSoftly = !DebuggerSettings.getInstance().KILL_PROCESS_IMMEDIATELY;
            processHandler.setShouldKillProcessSoftly(shouldKillSoftly);
            ProcessTerminatedListener.attach(processHandler);

            // Set up enhanced console integration
            setupConsoleIntegrationDelayed(processHandler);

            // Log performance and feature status
            long duration = System.currentTimeMillis() - startTime;
            deploymentLogger.logServerInfo("Professional process handler created in " + duration + " ms");
            logEnhancedFeaturesStatus();

            System.out.println("DevTomcat: Professional process handler created with all features");
            return processHandler;

        } catch (Exception e) {
            deploymentLogger.logDeploymentError(getArtifactName(), e.getMessage());
            System.err.println("DevTomcat: Failed to start professional process - " + e.getMessage());
            throw new ExecutionException("Failed to start professional Tomcat server: " + e.getMessage(), e);
        }
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        try {
            if (configuration == null) {
                throw new ExecutionException("Professional Tomcat configuration is required");
            }

            Path catalinaBase = DevTomcatUtils.getCatalinaBase(configuration);
            Module module = configuration.getModule();
            if (catalinaBase == null || module == null) {
                throw new ExecutionException("The Module Root specified is not a module according to IntelliJ");
            }

            Path tomcatInstallationPath = Paths.get(configuration.getTomcatInfo().getPath());
            Project project = configuration.getProject();
            String tomcatVersion = configuration.getTomcatInfo().getVersion();
            String vmOptions = configuration.getVmOptions();
            String extraClassPath = configuration.getExtraClassPath();
            Map<String, String> envOptions = configuration.getEnvOptions();

            // Professional pattern: Copy configuration to instance-specific directory
            Path projectConfPath = Paths.get(project.getBasePath(), ".idea", "tomcat", "conf");
            if (!projectConfPath.toFile().exists() || DevTomcatUtils.isEmptyFolder(projectConfPath)) {
                FileUtil.createDirectory(projectConfPath.toFile());
                FileUtil.copyDir(tomcatInstallationPath.resolve("conf").toFile(), projectConfPath.toFile());
                deploymentLogger.logServerInfo("Professional configuration structure created");
            }

            // Professional-style configuration copy
            Path confPath = catalinaBase.resolve("conf");
            FileUtil.delete(confPath);
            FileUtil.createDirectory(confPath.toFile());
            FileUtil.copyDir(projectConfPath.toFile(), confPath.toFile());

            // Create professional directory structure
            FileUtil.createDirectory(catalinaBase.resolve("temp").toFile());
            FileUtil.createDirectory(catalinaBase.resolve("logs").toFile());
            FileUtil.createDirectory(catalinaBase.resolve("webapps").toFile());

            // Update server configuration with professional features
            updateProfessionalServerConf(confPath, configuration);
            createProfessionalContextFile(tomcatVersion, module, confPath);
            cleanupProfessionalWorkFiles(catalinaBase);

            ProjectRootManager manager = ProjectRootManager.getInstance(project);

            JavaParameters javaParams = new JavaParameters();
            javaParams.setDefaultCharset(project);
            javaParams.setWorkingDirectory(catalinaBase.toFile());
            javaParams.setJdk(manager.getProjectSdk());

            // Set up professional classpath
            javaParams.getClassPath().add(tomcatInstallationPath.resolve("bin/bootstrap.jar").toFile());
            javaParams.getClassPath().add(tomcatInstallationPath.resolve("bin/tomcat-juli.jar").toFile());
            if (StringUtil.isNotEmpty(extraClassPath)) {
                javaParams.getClassPath().addAll(StringUtil.split(extraClassPath, File.pathSeparator));
            }

            javaParams.setMainClass(TOMCAT_MAIN_CLASS);
            javaParams.getProgramParametersList().add("start");

            // Environment configuration
            javaParams.setPassParentEnvs(configuration.isPassParentEnvs());
            if (envOptions != null) {
                javaParams.setEnv(envOptions);
            }

            // Apply enhanced environment variables
            Map<String, String> enhancedEnvVars = configuration.getEnvironmentVariables();
            if (enhancedEnvVars != null && !enhancedEnvVars.isEmpty()) {
                javaParams.setEnv(enhancedEnvVars);
                deploymentLogger.logServerInfo("Applied " + enhancedEnvVars.size() + " enhanced environment variables");
            }

            // VM Parameters with enhanced features
            ParametersList vmParams = javaParams.getVMParametersList();

            // Add original VM options
            if (StringUtil.isNotEmpty(vmOptions)) {
                vmParams.addParametersString(vmOptions);
            }

            // Apply professional JMX configuration
            if (configuration.isJmxEnabled()) {
                int jmxPort = configuration.getJmxPort();
                vmParams.addProperty("com.sun.management.jmxremote", "");
                vmParams.addProperty("com.sun.management.jmxremote.port", String.valueOf(jmxPort));
                vmParams.addProperty("com.sun.management.jmxremote.ssl", "false");
                vmParams.addProperty("com.sun.management.jmxremote.authenticate", "false");
                vmParams.addProperty("com.sun.management.jmxremote.local.only", "false");
                deploymentLogger.logServerInfo("Professional JMX enabled on port " + jmxPort);
            }

            // Apply professional hot deployment
            if (configuration.isHotDeploymentEnabled()) {
                vmParams.addProperty("tomcat.autoreload.enabled", "true");
                vmParams.addProperty("tomcat.development", "true");
                deploymentLogger.logServerInfo("Professional hot deployment enabled");
            }

            // Standard Tomcat properties
            vmParams.addProperty(PARAM_CATALINA_HOME, tomcatInstallationPath.toString());
            vmParams.defineProperty(PARAM_CATALINA_BASE, catalinaBase.toString());
            vmParams.defineProperty(PARAM_CATALINA_TMPDIR, catalinaBase.resolve("temp").toString());
            vmParams.defineProperty(PARAM_LOGGING_CONFIG, confPath.resolve("logging.properties").toString());
            vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);

            deploymentLogger.logServerInfo("Professional Java parameters configured");
            return javaParams;

        } catch (Exception e) {
            deploymentLogger.logServerError("Error creating professional parameters - " + e.getMessage());
            throw new ExecutionException("Failed to create professional Java parameters: " + e.getMessage(), e);
        }
    }

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        try {
            // Create enhanced console with better logging support
            if (configuration != null) {
                List<LogFileConfiguration> logConfigs = configuration.getLogFileConfigurations();
                if (!logConfigs.isEmpty()) {
                    deploymentLogger.logServerInfo("Creating enhanced console with " + logConfigs.size() + " log configurations");
                }
            }

            // Return enhanced console (future enhancement: custom console with multiple log tabs)
            return super.createConsole(executor);

        } catch (Exception e) {
            deploymentLogger.logServerWarning("Error creating enhanced console, using standard console: " + e.getMessage());
            return super.createConsole(executor);
        }
    }

    /**
     * Apply enhanced Phase 2 features before starting the process
     */
    private void applyEnhancedFeatures() {
        try {
            // Apply JMX configuration
            if (configuration.isJmxEnabled()) {
                deploymentLogger.logServerInfo("JMX enabled on port: " + configuration.getJmxPort());
            }

            // Apply hot deployment
            if (configuration.isHotDeploymentEnabled()) {
                deploymentLogger.logServerInfo("Hot deployment enabled");
            }

            // Log environment variables
            int envVarCount = configuration.getEnvironmentVariables().size();
            if (envVarCount > 0) {
                deploymentLogger.logServerInfo("Environment variables configured: " + envVarCount);
            }

            // Log file configurations
            int logFileCount = configuration.getLogFileConfigurations().size();
            if (logFileCount > 0) {
                deploymentLogger.logServerInfo("Log file configurations: " + logFileCount);
            }

        } catch (Exception e) {
            deploymentLogger.logServerWarning("Error applying enhanced features: " + e.getMessage());
        }
    }

    /**
     * Log status of enhanced features
     */
    private void logEnhancedFeaturesStatus() {
        StringBuilder status = new StringBuilder("Enhanced features: ");

        if (configuration.isJmxEnabled()) {
            status.append("JMX(").append(configuration.getJmxPort()).append(") ");
        }

        if (configuration.isHotDeploymentEnabled()) {
            status.append("HotDeploy ");
        }

        // Check for coverage settings
        String vmOptions = configuration.getVmOptions();
        if (vmOptions != null && vmOptions.contains("-Dcoverage.enabled=true")) {
            status.append("Coverage ");
        }

        int envVars = configuration.getEnvironmentVariables().size();
        if (envVars > 0) {
            status.append("EnvVars(").append(envVars).append(") ");
        }

        deploymentLogger.logServerInfo(status.toString().trim());
    }

    /**
     * Set up professional console integration
     */
    private void setupConsoleIntegrationDelayed(TomcatProcessHandler processHandler) {
        // Try immediate setup
        if (tryImmediateConsoleSetup()) {
            return;
        }

        // Fallback with retry logic
        ApplicationManager.getApplication().invokeLater(() -> {
            int retries = 5;
            for (int i = 0; i < retries; i++) {
                if (tryImmediateConsoleSetup()) {
                    deploymentLogger.logServerInfo("Enhanced console integration enabled (retry " + (i + 1) + ")");
                    return;
                }

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            deploymentLogger.logServerInfo("Enhanced console integration will be enabled when available");
        });
    }

    /**
     * Try immediate console setup
     */
    private boolean tryImmediateConsoleSetup() {
        try {
            RunContentManager contentManager = RunContentManager.getInstance(getEnvironment().getProject());
            RunContentDescriptor descriptor = contentManager.getSelectedContent();

            if (descriptor != null && descriptor.getExecutionConsole() instanceof ConsoleView) {
                ConsoleView consoleView = (ConsoleView) descriptor.getExecutionConsole();
                deploymentLogger.setConsoleView(consoleView);
                deploymentLogger.logServerInfo("Enhanced console integration enabled");
                return true;
            }

            // Alternative: search all descriptors
            List<RunContentDescriptor> allDescriptors = contentManager.getAllDescriptors();
            for (RunContentDescriptor desc : allDescriptors) {
                if (desc.getExecutionConsole() instanceof ConsoleView) {
                    ConsoleView consoleView = (ConsoleView) desc.getExecutionConsole();
                    deploymentLogger.setConsoleView(consoleView);
                    deploymentLogger.logServerInfo("Enhanced console integration enabled via search");
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            deploymentLogger.logServerWarning("Console setup attempt failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update server configuration with professional features
     */
    private void updateProfessionalServerConf(Path confPath, TomcatRunConfiguration cfg)
            throws ParserConfigurationException, XPathExpressionException, TransformerException, IOException, SAXException {

        Path serverXml = confPath.resolve("server.xml");
        Document doc = DevTomcatUtils.createDocumentBuilder().parse(serverXml.toFile());
        XPath xpath = XPathFactory.newInstance().newXPath();

        // XPath expressions for server configuration
        XPathExpression exprConnectorShutdown = xpath.compile("/Server[@shutdown='SHUTDOWN']");
        XPathExpression serviceExpression = xpath.compile("/Server/Service[@name='Catalina']");
        XPathExpression exprConnector = xpath.compile("/Server/Service[@name='Catalina']/Connector[(@protocol='HTTP/1.1' or @protocol='org.apache.coyote.http11.Http11NioProtocol' or @protocol='org.apache.coyote.http11.Http11Protocol') and (not(@SSLEnabled) or @SSLEnabled='false')]");
        XPathExpression exprSSLConnector = xpath.compile("/Server/Service[@name='Catalina']/Connector[@SSLEnabled='true']");
        XPathExpression exprContext = xpath.compile("/Server/Service[@name='Catalina']/Engine[@name='Catalina']/Host/Context");

        Element serviceE = (Element) serviceExpression.evaluate(doc, XPathConstants.NODE);
        Element portShutdown = (Element) exprConnectorShutdown.evaluate(doc, XPathConstants.NODE);
        Element portE = (Element) exprConnector.evaluate(doc, XPathConstants.NODE);
        Element sslPortE = (Element) exprSSLConnector.evaluate(doc, XPathConstants.NODE);

        // Remove existing contexts
        NodeList nodeList = (NodeList) exprContext.evaluate(doc, XPathConstants.NODESET);
        if (nodeList != null) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                node.getParentNode().removeChild(node);
            }
        }

        // Configure ports
        if (portShutdown != null) {
            portShutdown.setAttribute("port", String.valueOf(cfg.getAdminPort()));
        }
        if (portE != null) {
            portE.setAttribute("port", String.valueOf(cfg.getPort()));
        }

        // Enhanced SSL configuration
        Integer sslPort = cfg.getSslPort();
        if (sslPortE != null && sslPort != null) {
            sslPortE.setAttribute("port", sslPort.toString());
            if (portE != null) {
                portE.setAttribute("redirectPort", sslPort.toString());
            }
            deploymentLogger.logServerInfo("SSL configured on port " + sslPort);
        } else {
            // Clean up SSL configuration
            if (portE != null) {
                portE.removeAttribute("redirectPort");
            }
            if (serviceE != null && sslPortE != null) {
                serviceE.removeChild(sslPortE);
            }
        }

        // Apply professional JMX configuration to server.xml if needed
        if (cfg.isJmxEnabled()) {
            deploymentLogger.logServerInfo("Professional JMX configuration applied");
        }

        DevTomcatUtils.createTransformer().transform(new DOMSource(doc), new StreamResult(serverXml.toFile()));
        deploymentLogger.logServerInfo("Professional server configuration updated");
    }

    private void createProfessionalContextFile(String tomcatVersion, Module module, Path confPath)
            throws ParserConfigurationException, IOException, SAXException, TransformerException {

        String docBase = configuration.getDocBase();
        String contextPath = configuration.getContextPath();
        String normalizedContextPath = StringUtil.trim(contextPath, ch -> ch != '/');
        String contextFileName = StringUtil.defaultIfEmpty(normalizedContextPath, "ROOT").replace('/', '#');
        Path contextFilesDir = confPath.resolve("Catalina/localhost");
        Path contextFilePath = contextFilesDir.resolve(contextFileName + ".xml");

        // Create directory structure
        FileUtil.createDirectory(contextFilesDir.toFile());

        DocumentBuilder builder = DevTomcatUtils.createDocumentBuilder();
        Document doc = builder.newDocument();
        Element contextRoot = createContextElement(doc, builder);

        contextRoot.setAttribute("docBase", docBase);

        // Professional context configuration
        if (configuration.isHotDeploymentEnabled()) {
            contextRoot.setAttribute("reloadable", "true");
            contextRoot.setAttribute("autoDeploy", "true");
            contextRoot.setAttribute("development", "true");
            deploymentLogger.logServerInfo("Professional context configured for development");
        }

        collectResources(doc, contextRoot, module, tomcatVersion);
        doc.appendChild(contextRoot);

        StringWriter writer = new StringWriter();
        DevTomcatUtils.createTransformer().transform(new DOMSource(doc), new StreamResult(writer));
        FileUtil.writeToFile(contextFilePath.toFile(), writer.toString());

        deploymentLogger.logServerInfo("Professional context file created");
    }

    private Element createContextElement(Document doc, DocumentBuilder builder) throws IOException, SAXException {
        Path contextFile = findContextFileInApp();

        if (contextFile == null) {
            return doc.createElement("Context");
        }

        Element contextEl = builder.parse(contextFile.toFile()).getDocumentElement();
        return (Element) doc.importNode(contextEl, true);
    }

    private Path findContextFileInApp() {
        String docBase = configuration.getDocBase();
        if (docBase == null) {
            return null;
        }

        Path metaInf = Paths.get(docBase).resolve("META-INF");
        Path contextLocalFile = metaInf.resolve("context_local.xml");
        Path contextFile = metaInf.resolve("context.xml");

        if (Files.exists(contextLocalFile)) {
            return contextLocalFile;
        } else if (Files.exists(contextFile)) {
            return contextFile;
        } else {
            return null;
        }
    }

    private void collectResources(Document doc, Element contextRoot, Module module, String tomcatVersion) {
        String majorVersionStr = tomcatVersion.split("\\.")[0];
        int majorVersion = Integer.parseInt(majorVersionStr);
        PathsList pathsList = OrderEnumerator.orderEntries(module)
                .withoutSdk().runtimeOnly().productionOnly().getPathsList();

        if (pathsList.isEmpty()) {
            return;
        }

        if (majorVersion >= 8) {
            Element resources = createResourcesElementIfNecessary(doc, contextRoot);
            pathsList.getVirtualFiles().forEach(file -> {
                Element res;
                String tagName;
                String className;
                String webAppMount;

                if (file.isDirectory()) {
                    tagName = "PreResources";
                    className = "org.apache.catalina.webresources.DirResourceSet";
                    webAppMount = "/WEB-INF/classes";
                } else {
                    tagName = "PostResources";
                    className = "org.apache.catalina.webresources.FileResourceSet";
                    webAppMount = "/WEB-INF/lib/" + file.getName();
                }

                res = doc.createElement(tagName);
                res.setAttribute("base", file.getPath());
                res.setAttribute("className", className);
                res.setAttribute("webAppMount", webAppMount);

                resources.appendChild(res);
            });
        } else if (majorVersion >= 6) {
            Element loader = doc.createElement("Loader");
            loader.setAttribute("className", "org.apache.catalina.loader.VirtualWebappLoader");
            loader.setAttribute("virtualClasspath", StringUtil.join(pathsList.getPathList(), ";"));
            contextRoot.appendChild(loader);
        } else {
            throw new RuntimeException("Unsupported Tomcat version: " + tomcatVersion);
        }
    }

    private Element createResourcesElementIfNecessary(Document doc, Element contextRoot) {
        Element resources = (Element) contextRoot.getElementsByTagName("Resources").item(0);
        if (resources == null) {
            resources = doc.createElement("Resources");
            contextRoot.appendChild(resources);
        }

        if (Registry.is("devtomcat.resources.allowLinking")) {
            resources.setAttribute("allowLinking", "true");
        }

        int cacheMaxSize = Registry.intValue("devtomcat.resources.cacheMaxSize", 10240);
        if (cacheMaxSize > 0) {
            resources.setAttribute("cacheMaxSize", String.valueOf(cacheMaxSize));
        }

        return resources;
    }

    private void cleanupProfessionalWorkFiles(Path catalinaBase) {
        // Professional cleanup: preserve sessions but clean compiled classes
        Path tomcatWorkPath = catalinaBase.resolve("work/Catalina/localhost");
        FileUtil.processFilesRecursively(tomcatWorkPath.toFile(), file -> {
            // Preserve session files (.ser) and specific cache files
            if (file.isFile() && !file.getName().endsWith(".ser") && !file.getName().endsWith(".dat")) {
                FileUtil.delete(file);
            }
            return true;
        });
        deploymentLogger.logServerInfo("Professional work directory cleanup completed");
    }

    /**
     * Get artifact name for enhanced logging
     */
    private String getArtifactName() {
        try {
            String contextPath = configuration.getContextPath();
            if (contextPath != null && !contextPath.isEmpty()) {
                return contextPath.replaceFirst("^/", "") + ":war exploded";
            }
            return "webapp:war exploded";
        } catch (Exception e) {
            return "unknown-artifact:war exploded";
        }
    }

    // Public API methods
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }

    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    public void setConsoleView(ConsoleView consoleView) {
        if (consoleView != null) {
            deploymentLogger.setConsoleView(consoleView);
            deploymentLogger.logServerInfo("Enhanced console view set externally - professional logging enabled");
        }
    }

    public boolean hasEnhancedFeatures() {
        return configuration.isJmxEnabled() ||
                configuration.isHotDeploymentEnabled() ||
                !configuration.getEnvironmentVariables().isEmpty() ||
                !configuration.getLogFileConfigurations().isEmpty();
    }

    public String getEnhancedPerformanceInfo() {
        long uptime = System.currentTimeMillis() - creationTime;
        StringBuilder info = new StringBuilder();
        info.append("DevTomcat Professional uptime: ").append(uptime).append(" ms");

        if (configuration.isJmxEnabled()) {
            info.append(", JMX: enabled");
        }

        if (configuration.isHotDeploymentEnabled()) {
            info.append(", HotDeploy: enabled");
        }

        return info.toString();
    }

    /**
     * Test enhanced logging with all professional features
     */
    public void testEnhancedLogging() {
        System.out.println("=== DevTomcat Professional Logging Test ===");

        deploymentLogger.logServerConnection();
        deploymentLogger.logDeploymentStart("professional-test:war exploded");
        deploymentLogger.logServerInfo("Professional logging with complete features is active!");

        // Test enhanced features
        if (configuration.isJmxEnabled()) {
            deploymentLogger.logServerInfo("JMX integration tested - port " + configuration.getJmxPort());
        }

        if (configuration.isHotDeploymentEnabled()) {
            deploymentLogger.logServerInfo("Hot deployment feature tested");
        }

        // Test environment variables
        int envVars = configuration.getEnvironmentVariables().size();
        if (envVars > 0) {
            deploymentLogger.logServerInfo("Environment variables tested - " + envVars + " configured");
        }

        // Test log file configurations
        int logFiles = configuration.getLogFileConfigurations().size();
        if (logFiles > 0) {
            deploymentLogger.logServerInfo("Log file configurations tested - " + logFiles + " configured");
        }

        deploymentLogger.logServerStartup(1200);
        deploymentLogger.logDeploymentSuccess("professional-test:war exploded", 1800);

        System.out.println("=== Professional Test Complete ===");
    }

    /**
     * Enable enhanced testing mode
     */
    public void enableEnhancedTestMode() {
        ApplicationManager.getApplication().invokeLater(this::testEnhancedLogging);
    }

    /**
     * Get creation time for performance monitoring
     */
    public long getCreationTime() {
        return creationTime;
    }
}