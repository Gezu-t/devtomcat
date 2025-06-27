package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.logging.LogFileConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.*;

/**
 * Professional Tomcat Configuration Data Model
 * Central data model for all Tomcat configuration settings
 *
 * This model can be:
 * - Used across all UI components
 * - Serialized/deserialized
 * - Validated
 * - Cloned for configuration copies
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class TomcatConfigurationData implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    // === SERVER CONFIGURATION ===
    private TomcatInfo tomcatInfo;
    private String docBase = "";
    private String contextPath = "/";

    // === PORTS ===
    private PortConfiguration portConfig = new PortConfiguration();

    // === VM AND ENVIRONMENT ===
    private VmConfiguration vmConfig = new VmConfiguration();

    // === BROWSER CONFIGURATION ===
    private BrowserConfiguration browserConfig = new BrowserConfiguration();

    // === DEPLOYMENT ===
    private DeploymentConfiguration deploymentConfig = new DeploymentConfiguration();

    // === LOGS ===
    private List<LogFileConfiguration> logFileConfigurations = new ArrayList<>();

    // === UPDATE ACTIONS ===
    private UpdateConfiguration updateConfig = new UpdateConfiguration();

    // === JRE CONFIGURATION ===
    private String jreSelection = "Project default";

    // === UI OPTIONS ===
    private UiConfiguration uiConfig = new UiConfiguration();

    /**
     * Default constructor with professional defaults
     */
    public TomcatConfigurationData() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        // Professional environment variables
        vmConfig.getEnvironmentVariables().put("JAVA_OPTS", "-Xmx512m -Xms256m");
        vmConfig.getEnvironmentVariables().put("CATALINA_OPTS", "-Dfile.encoding=UTF-8 -Ddevelopment=true");

        // Professional log files
        logFileConfigurations.add(LogFileConfiguration.createCatalinaLog());
        logFileConfigurations.add(LogFileConfiguration.createLocalhostLog());
    }

    // === NESTED CONFIGURATION CLASSES ===

    /**
     * Port configuration model
     */
    public static class PortConfiguration implements Serializable, Cloneable {
        private Integer httpPort = 8080;
        private Integer httpsPort = 8443;
        private Integer jmxPort = 1099;
        private boolean jmxEnabled = true;
        private boolean httpsEnabled = false;

        // Getters and setters
        public Integer getHttpPort() { return httpPort; }
        public void setHttpPort(Integer httpPort) { this.httpPort = httpPort; }

        public Integer getHttpsPort() { return httpsPort; }
        public void setHttpsPort(Integer httpsPort) { this.httpsPort = httpsPort; }

        public Integer getJmxPort() { return jmxPort; }
        public void setJmxPort(Integer jmxPort) { this.jmxPort = jmxPort; }

        public boolean isJmxEnabled() { return jmxEnabled; }
        public void setJmxEnabled(boolean jmxEnabled) { this.jmxEnabled = jmxEnabled; }

        public boolean isHttpsEnabled() { return httpsEnabled; }
        public void setHttpsEnabled(boolean httpsEnabled) { this.httpsEnabled = httpsEnabled; }

        @Override
        public PortConfiguration clone() {
            try {
                return (PortConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * VM configuration model
     */
    public static class VmConfiguration implements Serializable, Cloneable {
        private String vmOptions = "";
        private Map<String, String> environmentVariables = new HashMap<>();
        private boolean passParentEnvs = true;

        public String getVmOptions() { return vmOptions; }
        public void setVmOptions(String vmOptions) { this.vmOptions = vmOptions; }

        public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
        public void setEnvironmentVariables(Map<String, String> vars) {
            this.environmentVariables = vars != null ? vars : new HashMap<>();
        }

        public boolean isPassParentEnvs() { return passParentEnvs; }
        public void setPassParentEnvs(boolean passParentEnvs) { this.passParentEnvs = passParentEnvs; }

        @Override
        public VmConfiguration clone() {
            try {
                VmConfiguration cloned = (VmConfiguration) super.clone();
                cloned.environmentVariables = new HashMap<>(this.environmentVariables);
                return cloned;
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Browser configuration model
     */
    public static class BrowserConfiguration implements Serializable, Cloneable {
        private boolean afterLaunchEnabled = false;
        private String browserUrl = "http://localhost:8080/";
        private String browserName = "System Default";
        private boolean withJavaScriptDebugger = false;

        public boolean isAfterLaunchEnabled() { return afterLaunchEnabled; }
        public void setAfterLaunchEnabled(boolean enabled) { this.afterLaunchEnabled = enabled; }

        public String getBrowserUrl() { return browserUrl; }
        public void setBrowserUrl(String url) { this.browserUrl = url; }

        public String getBrowserName() { return browserName; }
        public void setBrowserName(String name) { this.browserName = name; }

        public boolean isWithJavaScriptDebugger() { return withJavaScriptDebugger; }
        public void setWithJavaScriptDebugger(boolean enabled) { this.withJavaScriptDebugger = enabled; }

        @Override
        public BrowserConfiguration clone() {
            try {
                return (BrowserConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Deployment configuration model
     */
    public static class DeploymentConfiguration implements Serializable, Cloneable {
        private boolean hotDeploymentEnabled = true;
        private boolean updateClassesAndResources = true;
        private List<DeploymentArtifact> artifacts = new ArrayList<>();

        public boolean isHotDeploymentEnabled() { return hotDeploymentEnabled; }
        public void setHotDeploymentEnabled(boolean enabled) { this.hotDeploymentEnabled = enabled; }

        public boolean isUpdateClassesAndResources() { return updateClassesAndResources; }
        public void setUpdateClassesAndResources(boolean enabled) { this.updateClassesAndResources = enabled; }

        public List<DeploymentArtifact> getArtifacts() { return artifacts; }
        public void setArtifacts(List<DeploymentArtifact> artifacts) {
            this.artifacts = artifacts != null ? artifacts : new ArrayList<>();
        }

        @Override
        public DeploymentConfiguration clone() {
            try {
                DeploymentConfiguration cloned = (DeploymentConfiguration) super.clone();
                cloned.artifacts = new ArrayList<>();
                for (DeploymentArtifact artifact : this.artifacts) {
                    cloned.artifacts.add(artifact.clone());
                }
                return cloned;
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Update configuration model
     */
    public static class UpdateConfiguration implements Serializable, Cloneable {
        private String updateAction = "Restart server";
        private boolean showDialog = true;

        public String getUpdateAction() { return updateAction; }
        public void setUpdateAction(String action) { this.updateAction = action; }

        public boolean isShowDialog() { return showDialog; }
        public void setShowDialog(boolean show) { this.showDialog = show; }

        @Override
        public UpdateConfiguration clone() {
            try {
                return (UpdateConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * UI configuration model
     */
    public static class UiConfiguration implements Serializable, Cloneable {
        private boolean showThisPage = false;
        private boolean activateToolWindow = true;
        private boolean focusToolWindow = false;

        public boolean isShowThisPage() { return showThisPage; }
        public void setShowThisPage(boolean show) { this.showThisPage = show; }

        public boolean isActivateToolWindow() { return activateToolWindow; }
        public void setActivateToolWindow(boolean activate) { this.activateToolWindow = activate; }

        public boolean isFocusToolWindow() { return focusToolWindow; }
        public void setFocusToolWindow(boolean focus) { this.focusToolWindow = focus; }

        @Override
        public UiConfiguration clone() {
            try {
                return (UiConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // === MAIN GETTERS/SETTERS ===

    @Nullable
    public TomcatInfo getTomcatInfo() { return tomcatInfo; }
    public void setTomcatInfo(@Nullable TomcatInfo info) { this.tomcatInfo = info; }

    @NotNull
    public String getDocBase() { return docBase; }
    public void setDocBase(@NotNull String docBase) { this.docBase = docBase; }

    @NotNull
    public String getContextPath() { return contextPath; }
    public void setContextPath(@NotNull String path) { this.contextPath = path; }

    @NotNull
    public PortConfiguration getPortConfig() { return portConfig; }
    public void setPortConfig(@NotNull PortConfiguration config) { this.portConfig = config; }

    @NotNull
    public VmConfiguration getVmConfig() { return vmConfig; }
    public void setVmConfig(@NotNull VmConfiguration config) { this.vmConfig = config; }

    @NotNull
    public BrowserConfiguration getBrowserConfig() { return browserConfig; }
    public void setBrowserConfig(@NotNull BrowserConfiguration config) { this.browserConfig = config; }

    @NotNull
    public DeploymentConfiguration getDeploymentConfig() { return deploymentConfig; }
    public void setDeploymentConfig(@NotNull DeploymentConfiguration config) { this.deploymentConfig = config; }

    @NotNull
    public List<LogFileConfiguration> getLogFileConfigurations() { return logFileConfigurations; }
    public void setLogFileConfigurations(@NotNull List<LogFileConfiguration> configs) {
        this.logFileConfigurations = configs;
    }

    @NotNull
    public UpdateConfiguration getUpdateConfig() { return updateConfig; }
    public void setUpdateConfig(@NotNull UpdateConfiguration config) { this.updateConfig = config; }

    @NotNull
    public String getJreSelection() { return jreSelection; }
    public void setJreSelection(@NotNull String selection) { this.jreSelection = selection; }

    @NotNull
    public UiConfiguration getUiConfig() { return uiConfig; }
    public void setUiConfig(@NotNull UiConfiguration config) { this.uiConfig = config; }

    // === UTILITY METHODS ===

    /**
     * Validate the configuration
     */
    public void validate() throws ValidationException {
        if (tomcatInfo == null) {
            throw new ValidationException("No Tomcat server selected");
        }

        // Validate ports
        validatePort("HTTP", portConfig.getHttpPort());
        if (portConfig.isHttpsEnabled()) {
            validatePort("HTTPS", portConfig.getHttpsPort());
        }
        if (portConfig.isJmxEnabled()) {
            validatePort("JMX", portConfig.getJmxPort());
        }

        // Check port conflicts
        Set<Integer> usedPorts = new HashSet<>();
        usedPorts.add(portConfig.getHttpPort());
        if (portConfig.isHttpsEnabled() && !usedPorts.add(portConfig.getHttpsPort())) {
            throw new ValidationException("Port conflict: HTTPS port is already in use");
        }
        if (portConfig.isJmxEnabled() && !usedPorts.add(portConfig.getJmxPort())) {
            throw new ValidationException("Port conflict: JMX port is already in use");
        }

        // Validate paths
        if (docBase == null || docBase.trim().isEmpty()) {
            throw new ValidationException("Document base cannot be empty");
        }
    }

    private void validatePort(String name, Integer port) throws ValidationException {
        if (port == null || port < 1 || port > 65535) {
            throw new ValidationException("Invalid " + name + " port: " + port);
        }
    }

    @Override
    public TomcatConfigurationData clone() {
        try {
            TomcatConfigurationData cloned = (TomcatConfigurationData) super.clone();

            // Deep clone nested objects
            cloned.portConfig = this.portConfig.clone();
            cloned.vmConfig = this.vmConfig.clone();
            cloned.browserConfig = this.browserConfig.clone();
            cloned.deploymentConfig = this.deploymentConfig.clone();
            cloned.updateConfig = this.updateConfig.clone();
            cloned.uiConfig = this.uiConfig.clone();

            // Clone log configurations
            cloned.logFileConfigurations = new ArrayList<>();
            for (LogFileConfiguration config : this.logFileConfigurations) {
                cloned.logFileConfigurations.add(new LogFileConfiguration(config));
            }

            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone configuration", e);
        }
    }

    /**
     * Custom exception for validation errors
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}