package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
import com.dev.idea.plugins.tomcat.logging.LogFileConfiguration;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dev Tomcat Run Configuration
 *
 * Represents a single Tomcat server run configuration with all necessary
 * settings for deployment, debugging, and server management.
 *
 * This class encapsulates:
 * - Server selection and configuration
 * - Deployment artifacts and settings
 * - VM options and environment variables
 * - Logging configuration
 * - Browser integration settings
 *
 * All configuration data is stored in a reusable model (TomcatConfigurationData)
 * for better organization and maintainability.
 *
 * @author Dev Tomcat Team
 * @see TomcatConfigurationData
 * @see LocatableConfigurationBase
 */
public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfiguration.class);

    // XML attribute names for persistence
    private static final String ATTR_PORT = "port";
    private static final String ATTR_JMX_PORT = "jmxPort";
    private static final String ATTR_JMX_ENABLED = "jmxEnabled";
    private static final String ATTR_DOC_BASE = "docBase";
    private static final String ATTR_CONTEXT_PATH = "contextPath";
    private static final String ATTR_VM_OPTIONS = "vmOptions";
    private static final String ATTR_BROWSER_URL = "browserUrl";
    private static final String ATTR_AFTER_LAUNCH = "afterLaunchEnabled";
    private static final String ATTR_BROWSER_NAME = "browserName";
    private static final String ATTR_HOT_DEPLOY = "hotDeploymentEnabled";
    private static final String ATTR_UPDATE_CLASSES = "updateClassesAndResources";
    private static final String ATTR_PASS_PARENT_ENVS = "passParentEnvs";

    // XML element names
    private static final String ELEM_DEPLOYMENTS = "deployments";
    private static final String ELEM_ARTIFACT = "artifact";
    private static final String ELEM_ENV_VARS = "environmentVariables";
    private static final String ELEM_ENV_VAR = "variable";
    private static final String ELEM_LOG_FILES = "logFiles";
    private static final String ELEM_LOG_FILE = "logFile";
    private static final String ELEM_TOMCAT_INFO = "tomcatInfo";

    // Configuration data model
    private final TomcatConfigurationData configData = new TomcatConfigurationData();

    // Thread safety for updates
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    // Legacy field for backward compatibility
    private String docBase = "";

    /**
     * Creates a new Tomcat run configuration
     *
     * @param project The project this configuration belongs to
     * @param factory The factory that created this configuration
     * @param name The configuration name
     */
    public TomcatRunConfiguration(@NotNull Project project,
                                  @NotNull ConfigurationFactory factory,
                                  String name) {
        super(project, factory, name);
        initializeDefaults();
    }

    /**
     * Initialize configuration with sensible defaults
     */
    private void initializeDefaults() {
        LOG.debug("Initializing Dev Tomcat configuration defaults");

        // Try to auto-select a Tomcat server
        try {
            List<TomcatInfo> servers = TomcatServerManagerState.getInstance().getTomcatInfos();
            if (!servers.isEmpty()) {
                configData.setTomcatInfo(servers.get(0));
                LOG.debug("Auto-selected Tomcat server: " + configData.getTomcatInfo().getName());
            }
        } catch (Exception e) {
            LOG.warn("Failed to auto-select Tomcat server", e);
        }

        // Initialize with default log configurations if empty
        if (configData.getLogFileConfigurations().isEmpty()) {
            List<LogFileConfiguration> defaultLogs = new ArrayList<>();
            defaultLogs.add(LogFileConfiguration.createCatalinaLog());
            defaultLogs.add(LogFileConfiguration.createLocalhostLog());
            defaultLogs.add(LogFileConfiguration.createManagerLog());
            defaultLogs.add(LogFileConfiguration.createHostManagerLog());
            configData.setLogFileConfigurations(defaultLogs);
            LOG.debug("Initialized with default log configurations");
        }

        LOG.debug("Configuration initialized with " +
                configData.getLogFileConfigurations().size() + " log files, " +
                configData.getVmConfig().getEnvironmentVariables().size() + " environment variables");
    }

    // === CONFIGURATION EDITOR ===

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new TomcatConfigurationEditor(getProject());
    }

    // === STATE CREATION ===

    @Override
    @Nullable
    public TomcatCommandLineState getState(@NotNull Executor executor,
                                           @NotNull ExecutionEnvironment env) {
        return new TomcatCommandLineState(env, this);
    }

    // === VALIDATION ===

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        // Validate using the model's validation
        try {
            configData.validate();
        } catch (TomcatConfigurationData.ValidationException e) {
            throw new RuntimeConfigurationException(e.getMessage());
        }

        // Additional validation - make docBase optional for now
        // Remove this check or make it conditional based on deployment artifacts
        if (configData.getDeploymentConfig().getArtifacts().isEmpty() && StringUtil.isEmpty(docBase)) {
            throw new RuntimeConfigurationException("At least one deployment artifact must be configured");
        }

        // Validate port ranges
        int port = configData.getPortConfig().getHttpPort();
        if (port < 1 || port > 65535) {
            throw new RuntimeConfigurationException("HTTP port must be between 1 and 65535");
        }

        if (configData.getPortConfig().isJmxEnabled()) {
            int jmxPort = configData.getPortConfig().getJmxPort();
            if (jmxPort < 1 || jmxPort > 65535) {
                throw new RuntimeConfigurationException("JMX port must be between 1 and 65535");
            }
            if (jmxPort == port) {
                throw new RuntimeConfigurationException("JMX port cannot be the same as HTTP port");
            }
        }

        LOG.debug("Configuration validation passed");
    }

    // === PERSISTENCE ===

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);

        isUpdating.set(true);
        try {
            readConfigurationFromXml(element);
            LOG.debug("Configuration loaded from XML");
        } finally {
            isUpdating.set(false);
        }
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        super.writeExternal(element);

        isUpdating.set(true);
        try {
            writeConfigurationToXml(element);
            LOG.debug("Configuration saved to XML");
        } finally {
            isUpdating.set(false);
        }
    }

    /**
     * Read configuration from XML element
     *
     * @param element The XML element to read from
     */
    private void readConfigurationFromXml(Element element) {
        // Read port configuration
        readAttribute(element, ATTR_PORT, value ->
                configData.getPortConfig().setHttpPort(Integer.parseInt(value)));
        readAttribute(element, ATTR_JMX_PORT, value ->
                configData.getPortConfig().setJmxPort(Integer.parseInt(value)));
        readAttribute(element, ATTR_JMX_ENABLED, value ->
                configData.getPortConfig().setJmxEnabled(Boolean.parseBoolean(value)));

        // Read paths
        docBase = element.getAttributeValue(ATTR_DOC_BASE, "");
        configData.setContextPath(element.getAttributeValue(ATTR_CONTEXT_PATH, "/"));

        // Read VM options
        configData.getVmConfig().setVmOptions(
                element.getAttributeValue(ATTR_VM_OPTIONS, ""));

        // Read browser configuration
        readAttribute(element, ATTR_BROWSER_URL,
                configData.getBrowserConfig()::setBrowserUrl);
        readAttribute(element, ATTR_AFTER_LAUNCH, value ->
                configData.getBrowserConfig().setAfterLaunchEnabled(Boolean.parseBoolean(value)));
        readAttribute(element, ATTR_BROWSER_NAME,
                configData.getBrowserConfig()::setBrowserName);

        // Read deployment configuration
        readAttribute(element, ATTR_HOT_DEPLOY, value ->
                configData.getDeploymentConfig().setHotDeploymentEnabled(Boolean.parseBoolean(value)));
        readAttribute(element, ATTR_UPDATE_CLASSES, value ->
                configData.getDeploymentConfig().setUpdateClassesAndResources(Boolean.parseBoolean(value)));
        readAttribute(element, ATTR_PASS_PARENT_ENVS, value ->
                configData.getVmConfig().setPassParentEnvs(Boolean.parseBoolean(value)));

        // Read complex elements
        readTomcatInfo(element);
        readDeploymentArtifacts(element);
        readEnvironmentVariables(element);
        readLogFiles(element);
    }

    /**
     * Helper method to safely read an attribute and apply it
     */
    private void readAttribute(Element element, String attribute, AttributeConsumer consumer) {
        String value = element.getAttributeValue(attribute);
        if (value != null) {
            try {
                consumer.accept(value);
            } catch (Exception e) {
                LOG.warn("Failed to read attribute: " + attribute, e);
            }
        }
    }

    /**
     * Read Tomcat server information from XML
     */
    private void readTomcatInfo(Element element) {
        Element tomcatElement = element.getChild(ELEM_TOMCAT_INFO);
        if (tomcatElement != null) {
            String serverId = tomcatElement.getAttributeValue("id");
            if (serverId != null) {
                // Find the server by ID
                TomcatServerManagerState.getInstance().getTomcatInfos().stream()
                        .filter(info -> serverId.equals(info.getId()))
                        .findFirst()
                        .ifPresent(configData::setTomcatInfo);
            }
        }
    }

    /**
     * Read deployment artifacts from XML
     */
    private void readDeploymentArtifacts(Element element) {
        Element deploymentsElement = element.getChild(ELEM_DEPLOYMENTS);
        if (deploymentsElement != null) {
            List<DeploymentArtifact> artifacts = new ArrayList<>();
            for (Element artifactElement : deploymentsElement.getChildren(ELEM_ARTIFACT)) {
                try {
                    String name = artifactElement.getAttributeValue("name");
                    String type = artifactElement.getAttributeValue("type");
                    String serverPath = artifactElement.getAttributeValue("serverPath");
                    String localPath = artifactElement.getAttributeValue("localPath");

                    // Check for required attributes
                    if (StringUtil.isEmpty(name) || StringUtil.isEmpty(type)) {
                        LOG.warn("Skipping invalid deployment artifact: name=" + name + ", type=" + type);
                        continue;
                    }

                    DeploymentArtifact artifact = new DeploymentArtifact(
                            name,
                            type,
                            StringUtil.notNullize(serverPath, "/"),
                            StringUtil.notNullize(localPath, "")
                    );

                    // Read additional attributes if present
                    String applicationContext = artifactElement.getAttributeValue("applicationContext");
                    if (!StringUtil.isEmpty(applicationContext)) {
                        artifact.setApplicationContext(applicationContext);
                    }

                    String deployed = artifactElement.getAttributeValue("deployed");
                    if (deployed != null) {
                        artifact.setDeployed(Boolean.parseBoolean(deployed));
                    }

                    artifacts.add(artifact);
                } catch (Exception e) {
                    LOG.warn("Error reading deployment artifact", e);
                }
            }
            configData.getDeploymentConfig().setArtifacts(artifacts);
        }
    }

    /**
     * Read environment variables from XML
     */
    private void readEnvironmentVariables(Element element) {
        Element envVarsElement = element.getChild(ELEM_ENV_VARS);
        if (envVarsElement != null) {
            Map<String, String> envVars = new HashMap<>();
            for (Element varElement : envVarsElement.getChildren(ELEM_ENV_VAR)) {
                String name = varElement.getAttributeValue("name");
                String value = varElement.getAttributeValue("value");
                if (name != null && value != null) {
                    envVars.put(name, value);
                }
            }
            configData.getVmConfig().setEnvironmentVariables(envVars);
        }
    }

    /**
     * Read log file configurations from XML
     */
    private void readLogFiles(Element element) {
        Element logFilesElement = element.getChild(ELEM_LOG_FILES);
        if (logFilesElement != null) {
            List<LogFileConfiguration> logFiles = new ArrayList<>();
            for (Element logElement : logFilesElement.getChildren(ELEM_LOG_FILE)) {
                try {
                    String id = logElement.getAttributeValue("id");
                    String path = logElement.getAttributeValue("path");

                    // Skip invalid entries
                    if (StringUtil.isEmpty(id) || StringUtil.isEmpty(path)) {
                        LOG.warn("Skipping invalid log file configuration: id=" + id + ", path=" + path);
                        continue;
                    }

                    boolean enabled = Boolean.parseBoolean(
                            logElement.getAttributeValue("enabled", "true")
                    );

                    // Use the constructor that matches the parameters
                    LogFileConfiguration logFile = new LogFileConfiguration(id, path, enabled);
                    logFiles.add(logFile);
                } catch (Exception e) {
                    LOG.warn("Error reading log file configuration", e);
                }
            }

            // Only set if we found valid log files
            if (!logFiles.isEmpty()) {
                configData.setLogFileConfigurations(logFiles);
            } else {
                // If no valid log files found, initialize with defaults
                LOG.debug("No valid log files found in configuration, using defaults");
                List<LogFileConfiguration> defaultLogs = new ArrayList<>();
                defaultLogs.add(LogFileConfiguration.createCatalinaLog());
                defaultLogs.add(LogFileConfiguration.createLocalhostLog());
                defaultLogs.add(LogFileConfiguration.createManagerLog());
                defaultLogs.add(LogFileConfiguration.createHostManagerLog());
                configData.setLogFileConfigurations(defaultLogs);
            }
        }
    }

    /**
     * Write configuration to XML element
     *
     * @param element The XML element to write to
     */
    private void writeConfigurationToXml(Element element) {
        // Write port configuration
        element.setAttribute(ATTR_PORT,
                String.valueOf(configData.getPortConfig().getHttpPort()));
        element.setAttribute(ATTR_JMX_PORT,
                String.valueOf(configData.getPortConfig().getJmxPort()));
        element.setAttribute(ATTR_JMX_ENABLED,
                String.valueOf(configData.getPortConfig().isJmxEnabled()));

        // Write paths
        element.setAttribute(ATTR_DOC_BASE, docBase);
        element.setAttribute(ATTR_CONTEXT_PATH, configData.getContextPath());

        // Write VM options
        element.setAttribute(ATTR_VM_OPTIONS, configData.getVmConfig().getVmOptions());

        // Write browser configuration
        element.setAttribute(ATTR_BROWSER_URL,
                configData.getBrowserConfig().getBrowserUrl());
        element.setAttribute(ATTR_AFTER_LAUNCH,
                String.valueOf(configData.getBrowserConfig().isAfterLaunchEnabled()));
        element.setAttribute(ATTR_BROWSER_NAME,
                configData.getBrowserConfig().getBrowserName());

        // Write deployment configuration
        element.setAttribute(ATTR_HOT_DEPLOY,
                String.valueOf(configData.getDeploymentConfig().isHotDeploymentEnabled()));
        element.setAttribute(ATTR_UPDATE_CLASSES,
                String.valueOf(configData.getDeploymentConfig().isUpdateClassesAndResources()));
        element.setAttribute(ATTR_PASS_PARENT_ENVS,
                String.valueOf(configData.getVmConfig().isPassParentEnvs()));

        // Write complex elements
        writeTomcatInfo(element);
        writeDeploymentArtifacts(element);
        writeEnvironmentVariables(element);
        writeLogFiles(element);
    }

    /**
     * Write Tomcat server information to XML
     */
    private void writeTomcatInfo(Element element) {
        TomcatInfo tomcatInfo = configData.getTomcatInfo();
        if (tomcatInfo != null) {
            Element tomcatElement = new Element(ELEM_TOMCAT_INFO);
            tomcatElement.setAttribute("id", tomcatInfo.getId());
            tomcatElement.setAttribute("name", tomcatInfo.getName());
            tomcatElement.setAttribute("version", tomcatInfo.getVersion());
            element.addContent(tomcatElement);
        }
    }

    /**
     * Write deployment artifacts to XML
     */
    private void writeDeploymentArtifacts(Element element) {
        List<DeploymentArtifact> artifacts = configData.getDeploymentConfig().getArtifacts();
        if (!artifacts.isEmpty()) {
            Element deploymentsElement = new Element(ELEM_DEPLOYMENTS);
            for (DeploymentArtifact artifact : artifacts) {
                try {
                    Element artifactElement = new Element(ELEM_ARTIFACT);
                    artifactElement.setAttribute("name", artifact.getName());
                    artifactElement.setAttribute("type", artifact.getType());
                    artifactElement.setAttribute("serverPath", artifact.getServerPath());
                    artifactElement.setAttribute("localPath", artifact.getLocalPath());

                    // Write additional attributes
                    artifactElement.setAttribute("applicationContext", artifact.getApplicationContext());
                    artifactElement.setAttribute("deployed", String.valueOf(artifact.isDeployed()));

                    deploymentsElement.addContent(artifactElement);
                } catch (Exception e) {
                    LOG.warn("Error writing deployment artifact: " + artifact.getName(), e);
                }
            }
            element.addContent(deploymentsElement);
        }
    }

    /**
     * Write environment variables to XML
     */
    private void writeEnvironmentVariables(Element element) {
        Map<String, String> envVars = configData.getVmConfig().getEnvironmentVariables();
        if (!envVars.isEmpty()) {
            Element envVarsElement = new Element(ELEM_ENV_VARS);
            envVars.forEach((name, value) -> {
                Element varElement = new Element(ELEM_ENV_VAR);
                varElement.setAttribute("name", name);
                varElement.setAttribute("value", value);
                envVarsElement.addContent(varElement);
            });
            element.addContent(envVarsElement);
        }
    }

    /**
     * Write log file configurations to XML
     */
    private void writeLogFiles(Element element) {
        List<LogFileConfiguration> logFiles = configData.getLogFileConfigurations();
        if (!logFiles.isEmpty()) {
            Element logFilesElement = new Element(ELEM_LOG_FILES);
            for (LogFileConfiguration logFile : logFiles) {
                try {
                    Element logElement = new Element(ELEM_LOG_FILE);

                    // Ensure we have valid values before writing
                    String id = logFile.getId();
                    String path = logFile.getPath();

                    if (StringUtil.isEmpty(id) || StringUtil.isEmpty(path)) {
                        LOG.warn("Skipping invalid log file during save: id=" + id + ", path=" + path);
                        continue;
                    }

                    logElement.setAttribute("id", id);
                    logElement.setAttribute("path", path);
                    logElement.setAttribute("enabled", String.valueOf(logFile.isEnabled()));
                    logFilesElement.addContent(logElement);
                } catch (Exception e) {
                    LOG.warn("Error writing log file configuration", e);
                }
            }
            element.addContent(logFilesElement);
        }
    }

    @Override
    public RunConfiguration clone() {
        TomcatRunConfiguration clone = (TomcatRunConfiguration) super.clone();

        // Deep clone the configuration data
        // TODO: Implement proper clone() in TomcatConfigurationData
        clone.configData.setTomcatInfo(this.configData.getTomcatInfo());
        clone.configData.setContextPath(this.configData.getContextPath());
        clone.configData.getPortConfig().setHttpPort(
                this.configData.getPortConfig().getHttpPort());
        clone.configData.getPortConfig().setJmxPort(
                this.configData.getPortConfig().getJmxPort());
        clone.configData.getPortConfig().setJmxEnabled(
                this.configData.getPortConfig().isJmxEnabled());

        // Clone collections
        clone.configData.getDeploymentConfig().setArtifacts(
                new ArrayList<>(this.configData.getDeploymentConfig().getArtifacts()));
        clone.configData.setLogFileConfigurations(
                new ArrayList<>(this.configData.getLogFileConfigurations()));
        clone.configData.getVmConfig().setEnvironmentVariables(
                new HashMap<>(this.configData.getVmConfig().getEnvironmentVariables()));

        clone.docBase = this.docBase;

        LOG.debug("Configuration cloned");
        return clone;
    }

    // === DELEGATE GETTERS/SETTERS ===
    // These delegate to the configuration data model

    public TomcatInfo getTomcatInfo() {
        return configData.getTomcatInfo();
    }

    public void setTomcatInfo(TomcatInfo tomcatInfo) {
        configData.setTomcatInfo(tomcatInfo);
    }

    public String getDocBase() {
        return docBase;
    }

    public void setDocBase(String docBase) {
        this.docBase = docBase;
    }

    public String getContextPath() {
        return configData.getContextPath();
    }

    public void setContextPath(String contextPath) {
        configData.setContextPath(contextPath);
    }

    public Integer getPort() {
        return configData.getPortConfig().getHttpPort();
    }

    public void setPort(Integer port) {
        if (!isUpdating.get() && !Objects.equals(getPort(), port)) {
            Integer oldPort = getPort();
            configData.getPortConfig().setHttpPort(port);

            // Update browser URL if needed
            updateBrowserUrl(port);

            LOG.debug("HTTP port updated from " + oldPort + " to " + port);
        } else {
            configData.getPortConfig().setHttpPort(port);
        }
    }

    /**
     * Update browser URL when port changes
     */
    private void updateBrowserUrl(Integer newPort) {
        if (newPort != null) {
            String currentUrl = configData.getBrowserConfig().getBrowserUrl();
            if (currentUrl != null && currentUrl.contains("localhost:")) {
                String newUrl = currentUrl.replaceAll("localhost:\\d+", "localhost:" + newPort);
                configData.getBrowserConfig().setBrowserUrl(newUrl);
                LOG.debug("Browser URL updated to: " + newUrl);
            }
        }
    }

    public Integer getJmxPort() {
        return configData.getPortConfig().getJmxPort();
    }

    public void setJmxPort(Integer jmxPort) {
        configData.getPortConfig().setJmxPort(jmxPort);
    }

    public boolean isJmxEnabled() {
        return configData.getPortConfig().isJmxEnabled();
    }

    public void setJmxEnabled(boolean jmxEnabled) {
        configData.getPortConfig().setJmxEnabled(jmxEnabled);
    }

    public boolean isAfterLaunchEnabled() {
        return configData.getBrowserConfig().isAfterLaunchEnabled();
    }

    public void setAfterLaunchEnabled(boolean enabled) {
        configData.getBrowserConfig().setAfterLaunchEnabled(enabled);
    }

    public String getBrowserUrl() {
        return configData.getBrowserConfig().getBrowserUrl();
    }

    public void setBrowserUrl(String url) {
        configData.getBrowserConfig().setBrowserUrl(url);
    }

    public String getBrowserName() {
        return configData.getBrowserConfig().getBrowserName();
    }

    public void setBrowserName(String name) {
        configData.getBrowserConfig().setBrowserName(name);
    }

    public String getVmOptions() {
        return configData.getVmConfig().getVmOptions();
    }

    public void setVmOptions(String vmOptions) {
        configData.getVmConfig().setVmOptions(vmOptions);
    }

    public Map<String, String> getEnvironmentVariables() {
        return configData.getVmConfig().getEnvironmentVariables();
    }

    public void setEnvironmentVariables(Map<String, String> vars) {
        configData.getVmConfig().setEnvironmentVariables(vars);
    }

    public boolean isPassParentEnvs() {
        return configData.getVmConfig().isPassParentEnvs();
    }

    public void setPassParentEnvs(boolean pass) {
        configData.getVmConfig().setPassParentEnvs(pass);
    }

    public boolean isHotDeploymentEnabled() {
        return configData.getDeploymentConfig().isHotDeploymentEnabled();
    }

    public void setHotDeploymentEnabled(boolean enabled) {
        configData.getDeploymentConfig().setHotDeploymentEnabled(enabled);
    }

    public boolean isUpdateClassesAndResources() {
        return configData.getDeploymentConfig().isUpdateClassesAndResources();
    }

    public void setUpdateClassesAndResources(boolean enabled) {
        configData.getDeploymentConfig().setUpdateClassesAndResources(enabled);
    }

    public List<DeploymentArtifact> getDeploymentArtifacts() {
        return configData.getDeploymentConfig().getArtifacts();
    }

    public void setDeploymentArtifacts(List<DeploymentArtifact> artifacts) {
        configData.getDeploymentConfig().setArtifacts(artifacts);
    }

    public List<LogFileConfiguration> getLogFileConfigurations() {
        return configData.getLogFileConfigurations();
    }

    public void setLogFileConfigurations(List<LogFileConfiguration> configs) {
        configData.setLogFileConfigurations(configs);
    }

    public String getUpdateAction() {
        return configData.getUpdateConfig().getUpdateAction();
    }

    public void setUpdateAction(String action) {
        configData.getUpdateConfig().setUpdateAction(action);
    }

    public boolean isShowDialog() {
        return configData.getUpdateConfig().isShowDialog();
    }

    public void setShowDialog(boolean show) {
        configData.getUpdateConfig().setShowDialog(show);
    }

    public String getJreSelection() {
        return configData.getJreSelection();
    }

    public void setJreSelection(String selection) {
        configData.setJreSelection(selection);
    }

    public boolean isActivateToolWindow() {
        return configData.getUiConfig().isActivateToolWindow();
    }

    public void setActivateToolWindow(boolean activate) {
        configData.getUiConfig().setActivateToolWindow(activate);
    }

    public boolean isFocusToolWindow() {
        return configData.getUiConfig().isFocusToolWindow();
    }

    public void setFocusToolWindow(boolean focus) {
        configData.getUiConfig().setFocusToolWindow(focus);
    }

    /**
     * Get the configuration data model for advanced usage
     *
     * @return The underlying configuration data model
     */
    public TomcatConfigurationData getConfigurationData() {
        return configData;
    }

    /**
     * Get a human-readable summary of this configuration
     *
     * @return Configuration summary string
     */
    public String getConfigurationSummary() {
        TomcatInfo info = configData.getTomcatInfo();
        TomcatConfigurationData.PortConfiguration ports = configData.getPortConfig();
        TomcatConfigurationData.DeploymentConfiguration deployment = configData.getDeploymentConfig();

        StringBuilder summary = new StringBuilder("Dev Tomcat Configuration: ");
        summary.append("Server=").append(info != null ? info.getName() : "None");
        summary.append(", HTTP=").append(ports.getHttpPort());
        summary.append(", Context=").append(configData.getContextPath());

        if (ports.isJmxEnabled()) {
            summary.append(", JMX=").append(ports.getJmxPort());
        }

        if (deployment.isHotDeploymentEnabled()) {
            summary.append(", HotDeploy=Enabled");
        }

        summary.append(", Artifacts=").append(deployment.getArtifacts().size());
        summary.append(", EnvVars=").append(configData.getVmConfig().getEnvironmentVariables().size());
        summary.append(", LogFiles=").append(configData.getLogFileConfigurations().size());

        return summary.toString();
    }

    /**
     * Functional interface for attribute reading
     */
    @FunctionalInterface
    private interface AttributeConsumer {
        void accept(String value) throws Exception;
    }
}