package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.conf.TomcatLogFile;
import com.dev.idea.plugins.tomcat.logging.LogFileConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tomcat Configuration Data Model
 *
 * Central data model that holds all configuration settings for a Tomcat run configuration.
 * This model provides:
 * - Type-safe access to all configuration options
 * - Validation of configuration values
 * - Deep cloning support for configuration copying
 * - Serialization support for persistence
 *
 * The model is organized into logical sub-configurations for better maintainability
 * and clear separation of concerns.
 *
 * @author Dev Tomcat Team
 * @see TomcatInfo
 * @see DeploymentArtifact
 * @see LogFileConfiguration
 */
public class TomcatConfigurationData implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;

    // === CORE CONFIGURATION ===
    @Nullable private TomcatInfo tomcatInfo;
    @NotNull private String contextPath = "/";

    // === SUB-CONFIGURATIONS ===
    @NotNull private final PortConfiguration portConfig = new PortConfiguration();
    @NotNull private final VmConfiguration vmConfig = new VmConfiguration();
    @NotNull private final BrowserConfiguration browserConfig = new BrowserConfiguration();
    @NotNull private final DeploymentConfiguration deploymentConfig = new DeploymentConfiguration();
    @NotNull private final UpdateConfiguration updateConfig = new UpdateConfiguration();
    @NotNull private final UiConfiguration uiConfig = new UiConfiguration();

    // === ADDITIONAL SETTINGS ===
    @NotNull private List<LogFileConfiguration> logFileConfigurations = new ArrayList<>();
    @NotNull private String jreSelection = "Project default";

    /**
     * Default constructor that initializes with sensible defaults
     */
    public TomcatConfigurationData() {
        initializeDefaults();
    }

    /**
     * Initialize configuration with default values from registry
     */
    private void initializeDefaults() {
        // Port defaults from registry
        portConfig.setHttpPort(Registry.intValue("devtomcat.default.http.port"));
        portConfig.setJmxPort(Registry.intValue("devtomcat.default.jmx.port"));
        portConfig.setJmxEnabled(Registry.is("devtomcat.enable.jmx.monitoring"));

        // VM defaults
        String xmx = Registry.stringValue("devtomcat.default.xmx");
        String xms = Registry.stringValue("devtomcat.default.xms");
        vmConfig.setVmOptions(String.format("-Xmx%s -Xms%s", xmx, xms));

        // Environment variables
        vmConfig.getEnvironmentVariables().put("JAVA_OPTS",
                String.format("-Xmx%s -Xms%s -Dfile.encoding=UTF-8", xmx, xms));
        vmConfig.getEnvironmentVariables().put("CATALINA_OPTS",
                "-Ddev.mode=true");

        // Deployment defaults
        deploymentConfig.setHotDeploymentEnabled(
                Registry.is("devtomcat.enable.hot.deployment"));

        // Default log files
        logFileConfigurations.add(LogFileConfiguration.createCatalinaLog());
        logFileConfigurations.add(LogFileConfiguration.createLocalhostLog());
    }

    // === NESTED CONFIGURATION CLASSES ===

    /**
     * Port configuration for HTTP, HTTPS, and JMX
     */
    public static class PortConfiguration implements Serializable, Cloneable {
        private static final int MIN_PORT = 1;
        private static final int MAX_PORT = 65535;

        private int httpPort = 8080;
        private int httpsPort = 8443;
        private int jmxPort = 1099;
        private boolean jmxEnabled = false;
        private boolean httpsEnabled = false;

        public int getHttpPort() {
            return httpPort;
        }

        public void setHttpPort(int port) {
            validatePort("HTTP", port);
            this.httpPort = port;
        }

        public int getHttpsPort() {
            return httpsPort;
        }

        public void setHttpsPort(int port) {
            validatePort("HTTPS", port);
            this.httpsPort = port;
        }

        public int getJmxPort() {
            return jmxPort;
        }

        public void setJmxPort(int port) {
            validatePort("JMX", port);
            this.jmxPort = port;
        }

        public boolean isJmxEnabled() {
            return jmxEnabled;
        }

        public void setJmxEnabled(boolean enabled) {
            this.jmxEnabled = enabled;
        }

        public boolean isHttpsEnabled() {
            return httpsEnabled;
        }

        public void setHttpsEnabled(boolean enabled) {
            this.httpsEnabled = enabled;
        }

        private void validatePort(String name, int port) {
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException(
                        String.format("Invalid %s port: %d. Must be between %d and %d",
                                name, port, MIN_PORT, MAX_PORT));
            }
        }

        /**
         * Get all active ports
         */
        @NotNull
        public Set<Integer> getActivePorts() {
            Set<Integer> ports = new HashSet<>();
            ports.add(httpPort);
            if (httpsEnabled) {
                ports.add(httpsPort);
            }
            if (jmxEnabled) {
                ports.add(jmxPort);
            }
            return ports;
        }

        @Override
        public PortConfiguration clone() {
            try {
                return (PortConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    /**
     * VM and environment configuration
     */
    public static class VmConfiguration implements Serializable, Cloneable {
        @NotNull private String vmOptions = "";
        @NotNull private Map<String, String> environmentVariables = new LinkedHashMap<>();
        private boolean passParentEnvs = true;

        @NotNull
        public String getVmOptions() {
            return vmOptions;
        }

        public void setVmOptions(@NotNull String vmOptions) {
            this.vmOptions = StringUtil.notNullize(vmOptions);
        }

        @NotNull
        public Map<String, String> getEnvironmentVariables() {
            return environmentVariables;
        }

        public void setEnvironmentVariables(@NotNull Map<String, String> vars) {
            this.environmentVariables = new LinkedHashMap<>(vars);
        }

        public boolean isPassParentEnvs() {
            return passParentEnvs;
        }

        public void setPassParentEnvs(boolean pass) {
            this.passParentEnvs = pass;
        }

        /**
         * Add or update an environment variable
         */
        public void addEnvironmentVariable(@NotNull String name, @NotNull String value) {
            environmentVariables.put(name, value);
        }

        /**
         * Remove an environment variable
         */
        public void removeEnvironmentVariable(@NotNull String name) {
            environmentVariables.remove(name);
        }

        @Override
        public VmConfiguration clone() {
            try {
                VmConfiguration cloned = (VmConfiguration) super.clone();
                cloned.environmentVariables = new LinkedHashMap<>(this.environmentVariables);
                return cloned;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    /**
     * Browser launch configuration
     */
    public static class BrowserConfiguration implements Serializable, Cloneable {
        private boolean afterLaunchEnabled = false;
        @NotNull private String browserUrl = "http://localhost:8080/";
        @NotNull private String browserName = "System Default";
        private boolean withJavaScriptDebugger = false;

        public boolean isAfterLaunchEnabled() {
            return afterLaunchEnabled;
        }

        public void setAfterLaunchEnabled(boolean enabled) {
            this.afterLaunchEnabled = enabled;
        }

        @NotNull
        public String getBrowserUrl() {
            return browserUrl;
        }

        public void setBrowserUrl(@NotNull String url) {
            this.browserUrl = StringUtil.notNullize(url);
        }

        @NotNull
        public String getBrowserName() {
            return browserName;
        }

        public void setBrowserName(@NotNull String name) {
            this.browserName = StringUtil.notNullize(name);
        }

        public boolean isWithJavaScriptDebugger() {
            return withJavaScriptDebugger;
        }

        public void setWithJavaScriptDebugger(boolean enabled) {
            this.withJavaScriptDebugger = enabled;
        }

        @Override
        public BrowserConfiguration clone() {
            try {
                return (BrowserConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    /**
     * Deployment configuration
     */
    public static class DeploymentConfiguration implements Serializable, Cloneable {
        private boolean hotDeploymentEnabled = true;
        private boolean updateClassesAndResources = true;
        @NotNull private List<DeploymentArtifact> artifacts = new ArrayList<>();

        public boolean isHotDeploymentEnabled() {
            return hotDeploymentEnabled;
        }

        public void setHotDeploymentEnabled(boolean enabled) {
            this.hotDeploymentEnabled = enabled;
        }

        public boolean isUpdateClassesAndResources() {
            return updateClassesAndResources;
        }

        public void setUpdateClassesAndResources(boolean enabled) {
            this.updateClassesAndResources = enabled;
        }

        @NotNull
        public List<DeploymentArtifact> getArtifacts() {
            return artifacts;
        }

        public void setArtifacts(@NotNull List<DeploymentArtifact> artifacts) {
            this.artifacts = new ArrayList<>(artifacts);
        }

        /**
         * Add a deployment artifact
         */
        public void addArtifact(@NotNull DeploymentArtifact artifact) {
            artifacts.add(artifact);
        }

        /**
         * Remove a deployment artifact
         */
        public void removeArtifact(@NotNull DeploymentArtifact artifact) {
            artifacts.remove(artifact);
        }

        /**
         * Get only deployed artifacts
         */
        @NotNull
        public List<DeploymentArtifact> getDeployedArtifacts() {
            return artifacts.stream()
                    .filter(DeploymentArtifact::isDeployed)
                    .collect(Collectors.toList());
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
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    /**
     * Update action configuration
     */
    public static class UpdateConfiguration implements Serializable, Cloneable {
        public static final String ACTION_RESTART = "Restart server";
        public static final String ACTION_REDEPLOY = "Redeploy";
        public static final String ACTION_UPDATE_CLASSES = "Update classes and resources";

        @NotNull private String updateAction = ACTION_RESTART;
        private boolean showDialog = true;

        @NotNull
        public String getUpdateAction() {
            return updateAction;
        }

        public void setUpdateAction(@NotNull String action) {
            this.updateAction = StringUtil.notNullize(action);
        }

        public boolean isShowDialog() {
            return showDialog;
        }

        public void setShowDialog(boolean show) {
            this.showDialog = show;
        }

        @Override
        public UpdateConfiguration clone() {
            try {
                return (UpdateConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    /**
     * UI behavior configuration
     */
    public static class UiConfiguration implements Serializable, Cloneable {
        private boolean activateToolWindow = true;
        private boolean focusToolWindow = false;

        public boolean isActivateToolWindow() {
            return activateToolWindow;
        }

        public void setActivateToolWindow(boolean activate) {
            this.activateToolWindow = activate;
        }

        public boolean isFocusToolWindow() {
            return focusToolWindow;
        }

        public void setFocusToolWindow(boolean focus) {
            this.focusToolWindow = focus;
        }

        @Override
        public UiConfiguration clone() {
            try {
                return (UiConfiguration) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError("Clone not supported", e);
            }
        }
    }

    // === MAIN GETTERS/SETTERS ===

    @Nullable
    public TomcatInfo getTomcatInfo() {
        return tomcatInfo;
    }

    public void setTomcatInfo(@Nullable TomcatInfo info) {
        this.tomcatInfo = info;
    }

    @NotNull
    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(@NotNull String path) {
        this.contextPath = StringUtil.notNullize(path);
    }

    @NotNull
    public PortConfiguration getPortConfig() {
        return portConfig;
    }

    @NotNull
    public VmConfiguration getVmConfig() {
        return vmConfig;
    }

    @NotNull
    public BrowserConfiguration getBrowserConfig() {
        return browserConfig;
    }

    @NotNull
    public DeploymentConfiguration getDeploymentConfig() {
        return deploymentConfig;
    }

    @NotNull
    public UpdateConfiguration getUpdateConfig() {
        return updateConfig;
    }

    @NotNull
    public UiConfiguration getUiConfig() {
        return uiConfig;
    }

    @NotNull
    public List<LogFileConfiguration> getLogFileConfigurations() {
        return logFileConfigurations;
    }

    public void setLogFileConfigurations(@NotNull List<LogFileConfiguration> configs) {
        this.logFileConfigurations = new ArrayList<>(configs);
    }

    @NotNull
    public String getJreSelection() {
        return jreSelection;
    }

    public void setJreSelection(@NotNull String selection) {
        this.jreSelection = StringUtil.notNullize(selection);
    }

    // === VALIDATION ===

    /**
     * Validate the entire configuration
     *
     * @throws ValidationException if validation fails
     */
    public void validate() throws ValidationException {
        // Validate server selection
        if (tomcatInfo == null) {
            throw new ValidationException("No Tomcat server selected");
        }

        // Validate ports
        Set<Integer> usedPorts = new HashSet<>();

        usedPorts.add(portConfig.getHttpPort());

        if (portConfig.isHttpsEnabled()) {
            if (!usedPorts.add(portConfig.getHttpsPort())) {
                throw new ValidationException(
                        "Port conflict: HTTPS port " + portConfig.getHttpsPort() +
                                " is already used by another service");
            }
        }

        if (portConfig.isJmxEnabled()) {
            if (!usedPorts.add(portConfig.getJmxPort())) {
                throw new ValidationException(
                        "Port conflict: JMX port " + portConfig.getJmxPort() +
                                " is already used by another service");
            }
        }

        // Validate context path
        if (!isValidContextPath(contextPath)) {
            throw new ValidationException(
                    "Invalid context path: " + contextPath +
                            ". Must start with '/' or be empty for root context");
        }

        // Validate artifacts
        for (DeploymentArtifact artifact : deploymentConfig.getArtifacts()) {
            try {
                artifact.validate();
            } catch (IllegalStateException e) {
                throw new ValidationException("Invalid artifact: " + e.getMessage());
            }
        }
    }

    /**
     * Check if a context path is valid
     */
    private static boolean isValidContextPath(@NotNull String path) {
        return path.isEmpty() || path.equals("/") ||
                (path.startsWith("/") && !path.contains(" "));
    }

    // === CLONING ===

    @Override
    public TomcatConfigurationData clone() {
        try {
            TomcatConfigurationData cloned = (TomcatConfigurationData) super.clone();

            // Note: sub-configurations are final fields, so they're already cloned
            // Just need to clone collections
            cloned.logFileConfigurations = new ArrayList<>();
            for (LogFileConfiguration config : this.logFileConfigurations) {
                cloned.logFileConfigurations.add(config.clone());
            }

            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone not supported", e);
        }
    }

    /**
     * Custom exception for validation errors
     */
    public static class ValidationException extends Exception {
        public ValidationException(@NotNull String message) {
            super(message);
        }
    }
}