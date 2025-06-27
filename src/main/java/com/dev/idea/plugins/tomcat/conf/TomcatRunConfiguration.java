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
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DevTomcat Run Configuration - Refactored with Reusable Models
 * Uses TomcatConfigurationData for better organization and reusability
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

    // === CONFIGURATION DATA MODEL ===
    private final TomcatConfigurationData configData = new TomcatConfigurationData();

    // === LOOP PREVENTION ===
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    // === LEGACY FIELDS (for backward compatibility) ===
    private String docBase = "";
    private boolean showThisPage = false;

    public TomcatRunConfiguration(@NotNull Project project,
                                  @NotNull ConfigurationFactory factory,
                                  String name) {
        super(project, factory, name);
        initializeDefaults();
        System.out.println("DevTomcat: TomcatRunConfiguration created with reusable models");
    }

    private void initializeDefaults() {
        // Initialize Tomcat server
        try {
            List<TomcatInfo> tomcatInfos = TomcatServerManagerState.getInstance().getTomcatInfos();
            if (!tomcatInfos.isEmpty()) {
                configData.setTomcatInfo(tomcatInfos.get(0));
                System.out.println("DevTomcat: Using Tomcat server: " + configData.getTomcatInfo().getName());
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error loading Tomcat servers: " + e.getMessage());
        }

        // Configuration data already has professional defaults
        System.out.println("DevTomcat: Configuration initialized with " +
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
    public @Nullable TomcatCommandLineState getState(@NotNull Executor executor,
                                                     @NotNull ExecutionEnvironment env) {
        return new TomcatCommandLineState(env, this);
    }

    // === VALIDATION ===

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        try {
            configData.validate();

            // Additional validation
            if (docBase == null || docBase.trim().isEmpty()) {
                throw new RuntimeConfigurationException("Document base cannot be empty");
            }

            System.out.println("DevTomcat: Configuration validation passed");
        } catch (TomcatConfigurationData.ValidationException e) {
            throw new RuntimeConfigurationException(e.getMessage());
        }
    }

    // === PERSISTENCE ===

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);

        isUpdating.set(true);
        try {
            readConfigurationFromXml(element);
            System.out.println("DevTomcat: Configuration loaded from XML");
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
            System.out.println("DevTomcat: Configuration saved to XML");
        } finally {
            isUpdating.set(false);
        }
    }

    private void readConfigurationFromXml(Element element) {
        try {
            // Read ports
            String portStr = element.getAttributeValue("port");
            if (portStr != null) {
                configData.getPortConfig().setHttpPort(Integer.parseInt(portStr));
            }

            String jmxPortStr = element.getAttributeValue("jmxPort");
            if (jmxPortStr != null) {
                configData.getPortConfig().setJmxPort(Integer.parseInt(jmxPortStr));
            }

            String jmxEnabledStr = element.getAttributeValue("jmxEnabled");
            if (jmxEnabledStr != null) {
                configData.getPortConfig().setJmxEnabled(Boolean.parseBoolean(jmxEnabledStr));
            }

            // Read paths
            docBase = element.getAttributeValue("docBase");
            if (docBase == null) docBase = "";

            String contextPath = element.getAttributeValue("contextPath");
            configData.setContextPath(contextPath != null ? contextPath : "/");

            // Read VM options
            String vmOptions = element.getAttributeValue("vmOptions");
            configData.getVmConfig().setVmOptions(vmOptions != null ? vmOptions : "");

            // Read deployment artifacts
            Element deploymentsElement = element.getChild("deployments");
            if (deploymentsElement != null) {
                List<DeploymentArtifact> artifacts = new ArrayList<>();
                for (Element artifactElement : deploymentsElement.getChildren("artifact")) {
                    DeploymentArtifact artifact = new DeploymentArtifact(
                            artifactElement.getAttributeValue("name"),
                            artifactElement.getAttributeValue("type"),
                            artifactElement.getAttributeValue("serverPath"),
                            artifactElement.getAttributeValue("localPath")
                    );
                    artifacts.add(artifact);
                }
                configData.getDeploymentConfig().setArtifacts(artifacts);
            }

            // Read browser config
            String browserUrl = element.getAttributeValue("browserUrl");
            if (browserUrl != null) {
                configData.getBrowserConfig().setBrowserUrl(browserUrl);
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error reading XML configuration: " + e.getMessage());
        }
    }

    private void writeConfigurationToXml(Element element) {
        try {
            // Write ports
            element.setAttribute("port", String.valueOf(configData.getPortConfig().getHttpPort()));
            element.setAttribute("jmxPort", String.valueOf(configData.getPortConfig().getJmxPort()));
            element.setAttribute("jmxEnabled", String.valueOf(configData.getPortConfig().isJmxEnabled()));

            // Write paths
            element.setAttribute("docBase", docBase);
            element.setAttribute("contextPath", configData.getContextPath());

            // Write VM options
            element.setAttribute("vmOptions", configData.getVmConfig().getVmOptions());

            // Write browser config
            element.setAttribute("browserUrl", configData.getBrowserConfig().getBrowserUrl());

            // Write deployment artifacts
            List<DeploymentArtifact> artifacts = configData.getDeploymentConfig().getArtifacts();
            if (!artifacts.isEmpty()) {
                Element deploymentsElement = new Element("deployments");
                for (DeploymentArtifact artifact : artifacts) {
                    Element artifactElement = new Element("artifact");
                    artifactElement.setAttribute("name", artifact.getName());
                    artifactElement.setAttribute("type", artifact.getType());
                    artifactElement.setAttribute("serverPath", artifact.getServerPath());
                    artifactElement.setAttribute("localPath", artifact.getLocalPath());
                    deploymentsElement.addContent(artifactElement);
                }
                element.addContent(deploymentsElement);
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error writing XML configuration: " + e.getMessage());
        }
    }

    @Override
    public RunConfiguration clone() {
        TomcatRunConfiguration clone = (TomcatRunConfiguration) super.clone();

        // Deep clone the configuration data
        // Note: Would need to implement clone() in TomcatConfigurationData
        // For now, manually clone important parts
        clone.configData.setTomcatInfo(this.configData.getTomcatInfo());
        clone.configData.setContextPath(this.configData.getContextPath());
        clone.configData.getPortConfig().setHttpPort(this.configData.getPortConfig().getHttpPort());
        clone.configData.getPortConfig().setJmxPort(this.configData.getPortConfig().getJmxPort());
        clone.configData.getPortConfig().setJmxEnabled(this.configData.getPortConfig().isJmxEnabled());

        // Clone collections
        clone.configData.getDeploymentConfig().setArtifacts(
                new ArrayList<>(this.configData.getDeploymentConfig().getArtifacts())
        );
        clone.configData.setLogFileConfigurations(
                new ArrayList<>(this.configData.getLogFileConfigurations())
        );

        clone.docBase = this.docBase;

        System.out.println("DevTomcat: Configuration cloned");
        return clone;
    }

    // === DELEGATE GETTERS/SETTERS ===

    // Core configuration
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

    // Port configuration
    public Integer getPort() {
        return configData.getPortConfig().getHttpPort();
    }

    public void setPort(Integer port) {
        if (!isUpdating.get() && !Objects.equals(getPort(), port)) {
            Integer oldPort = getPort();
            configData.getPortConfig().setHttpPort(port);

            // Update browser URL
            updateBrowserUrl(port);

            System.out.println("DevTomcat: HTTP port updated from " + oldPort + " to " + port);
        } else {
            configData.getPortConfig().setHttpPort(port);
        }
    }

    public Integer getJmxPort() {
        return configData.getPortConfig().getJmxPort();
    }

    public void setJmxPort(Integer jmxPort) {
        if (!isUpdating.get() && !Objects.equals(getJmxPort(), jmxPort)) {
            Integer oldPort = getJmxPort();
            configData.getPortConfig().setJmxPort(jmxPort);
            System.out.println("DevTomcat: JMX port updated from " + oldPort + " to " + jmxPort);
        } else {
            configData.getPortConfig().setJmxPort(jmxPort);
        }
    }

    public boolean isJmxEnabled() {
        return configData.getPortConfig().isJmxEnabled();
    }

    public void setJmxEnabled(boolean jmxEnabled) {
        configData.getPortConfig().setJmxEnabled(jmxEnabled);
    }

    // Browser configuration
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

    private void updateBrowserUrl(Integer newPort) {
        if (newPort != null) {
            String currentUrl = configData.getBrowserConfig().getBrowserUrl();
            if (currentUrl != null && currentUrl.contains("localhost:")) {
                String newUrl = currentUrl.replaceAll("localhost:\\d+", "localhost:" + newPort);
                configData.getBrowserConfig().setBrowserUrl(newUrl);
                System.out.println("DevTomcat: Browser URL updated to: " + newUrl);
            }
        }
    }

    // VM and environment
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

    // Deployment configuration
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

    // Log configuration
    public List<LogFileConfiguration> getLogFileConfigurations() {
        return configData.getLogFileConfigurations();
    }

    public void setLogFileConfigurations(List<LogFileConfiguration> configs) {
        configData.setLogFileConfigurations(configs);
    }

    // Update configuration
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

    // JRE configuration
    public String getJreSelection() {
        return configData.getJreSelection();
    }

    public void setJreSelection(String selection) {
        configData.setJreSelection(selection);
    }

    // UI configuration
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

    public boolean isShowThisPage() {
        return showThisPage;
    }

    public void setShowThisPage(boolean show) {
        this.showThisPage = show;
    }

    // === UTILITY METHODS ===

    public String getConfigurationSummary() {
        TomcatInfo info = configData.getTomcatInfo();
        TomcatConfigurationData.PortConfiguration ports = configData.getPortConfig();
        TomcatConfigurationData.DeploymentConfiguration deployment = configData.getDeploymentConfig();

        StringBuilder summary = new StringBuilder("DevTomcat Configuration: ");
        summary.append("Server=").append(info != null ? info.getName() : "None");
        summary.append(", HTTP=").append(ports.getHttpPort());
        summary.append(", Context=").append(configData.getContextPath());

        if (ports.isJmxEnabled()) {
            summary.append(", JMX=").append(ports.getJmxPort());
        }

        if (deployment.isHotDeploymentEnabled()) {
            summary.append(", HotDeploy=true");
        }

        summary.append(", Artifacts=").append(deployment.getArtifacts().size());
        summary.append(", EnvVars=").append(configData.getVmConfig().getEnvironmentVariables().size());
        summary.append(", LogFiles=").append(configData.getLogFileConfigurations().size());

        return summary.toString();
    }

    /**
     * Get the configuration data model for advanced usage
     */
    public TomcatConfigurationData getConfigurationData() {
        return configData;
    }
}