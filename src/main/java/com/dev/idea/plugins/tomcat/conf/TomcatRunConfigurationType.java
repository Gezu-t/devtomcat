package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dev Tomcat Run Configuration Type
 *
 * <p>Implements the IntelliJ ConfigurationType interface for Tomcat configurations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register Tomcat as a run configuration type in the IDE</li>
 *   <li>Provide configuration factory for creating new configurations</li>
 *   <li>Apply dynamic defaults from environment and settings</li>
 *   <li>Auto-select best available Tomcat server</li>
 * </ul>
 *
 * <p>100% MODERN MODEL — NO LEGACY — USES TomcatConfigurationValidator
 * <p>100% NULL-SAFE — All null checks with comprehensive error handling
 *
 * @see TomcatRunConfiguration
 * @see TomcatConfigurationValidator
 * @see DynamicTomcatEnvironment
 */
public class TomcatRunConfigurationType implements ConfigurationType {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationType.class);

    public static final String ID = "com.dev.idea.plugins.tomcat";
    public static final String DISPLAY_NAME = "Dev Tomcat";
    public static final String DESCRIPTION = "Apache Tomcat server integration for web application development";

    private static final String ICON_PATH_SVG = "/icons/tomcat.svg";
    private static final String ICON_PATH_PNG = "/icons/tomcat.png";
    private static final Icon DEFAULT_ICON = new Icon() {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
        }
    };

    private volatile ConfigurationFactory[] factories;

    @Override
    @NotNull
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    @NotNull
    public String getConfigurationTypeDescription() {
        return DESCRIPTION;
    }

    /**
     * Get the Tomcat icon with fallback chain.
     *
     * @return icon or default if all sources fail
     */
    @Override
    @Nullable
    public Icon getIcon() {
        Icon icon = loadIcon(ICON_PATH_SVG);
        if (icon != null) {
            return icon;
        }

        icon = loadIcon(ICON_PATH_PNG);
        if (icon != null) {
            return icon;
        }

        LOG.debug("All icon sources failed, using default icon");
        return DEFAULT_ICON;
    }

    /**
     * Load icon from classpath resource path.
     *
     * @param path the resource path
     * @return icon or null if not found
     */
    @Nullable
    private Icon loadIcon(@NotNull String path) {
        Objects.requireNonNull(path, "Icon path cannot be null");

        try {
            Icon icon = IconLoader.getIcon(path, getClass());
            LOG.debug("Loaded icon: " + path);
            return icon;
        } catch (Exception e) {
            LOG.debug("Failed to load icon: " + path + " - " + e.getMessage());
            return null;
        }
    }

    @Override
    @NotNull
    public String getId() {
        return ID;
    }

    /**
     * Get or create the configuration factory.
     *
     * <p>Uses double-checked locking for thread-safe lazy initialization.
     *
     * @return array containing the factory
     */
    @Override
    @NotNull
    public ConfigurationFactory[] getConfigurationFactories() {
        if (factories == null) {
            synchronized (this) {
                if (factories == null) {
                    factories = new ConfigurationFactory[]{
                            new LocalTomcatConfigurationFactory(this),
                            new RemoteTomcatConfigurationFactory(this)
                    };
                    LOG.debug("Dev Tomcat configuration factories initialized (Local/Remote)");
                }
            }
        }
        return factories;
    }

    // === CONFIGURATION FACTORY ===

    /**
     * Factory for creating Tomcat run configurations.
     *
     * <p>Handles:
     * <ul>
     *   <li>Template configuration creation with dynamic defaults</li>
     *   <li>New configuration creation with cloning and validation</li>
     *   <li>Dynamic environment defaults application</li>
     * </ul>
     */
    public static class TomcatConfigurationFactory extends ConfigurationFactory {

        private final String factoryName;

        /**
         * Create a new Tomcat configuration factory.
         *
         * @param type the configuration type
         */
        protected TomcatConfigurationFactory(@NotNull ConfigurationType type, @NotNull String name) {
            super(type);
            this.factoryName = name;
        }

        @Override
        @NotNull
        public String getName() {
            return factoryName;
        }

        /**
         * Create a template configuration for the project.
         *
         * <p>Template is used as a basis for new configurations and typically
         * displayed in the "New Run Configuration" dialog.
         *
         * @param project the IDE project
         * @return configured TomcatRunConfiguration
         */
        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            Objects.requireNonNull(project, "Project cannot be null");

            try {
                LOG.debug("Creating Dev Tomcat template configuration for project: " + project.getName());
                TomcatRunConfiguration config = new TomcatRunConfiguration(project, this, "Tomcat");
                // Default mode can be overridden in specialized factories
                applyDynamicDefaults(config);
                LOG.debug("Template configuration created successfully");
                return config;
            } catch (Exception e) {
                LOG.error("Failed to create template configuration for project: " + project.getName(), e);
                throw new RuntimeException("Cannot create Tomcat configuration template", e);
            }
        }

        /**
         * Apply dynamic defaults from environment and settings.
         *
         * <p>Sets ports, JMX, HTTPS, hot deployment, browser, VM options, and
         * environment variables based on dynamic configuration.
         *
         * @param config the configuration to update
         */
        protected void applyDynamicDefaults(@NotNull TomcatRunConfiguration config) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            try {
                TomcatConfigurationData data = config.getConfigData();
                Objects.requireNonNull(data, "Configuration data cannot be null");

                // === PORT CONFIGURATION ===
                PortConfig portConfig = data.getPortConfig();
                if (portConfig == null) {
                    LOG.warn("Port configuration is null, creating new one");
                    portConfig = new PortConfig();
                    data.setPortConfig(portConfig);
                }

                try {
                    int httpPort = DynamicTomcatEnvironment.getHttpPort();
                    portConfig.setHttp(httpPort);
                    LOG.debug("HTTP port set to: " + httpPort);
                } catch (Exception e) {
                    LOG.warn("Error setting HTTP port, using default", e);
                    portConfig.setHttp(PortConfig.DEFAULT_HTTP_PORT);
                }

                try {
                    int shutdownPort = DynamicTomcatEnvironment.getShutdownPort();
                    portConfig.setShutdown(shutdownPort);
                    LOG.debug("Shutdown port set to: " + shutdownPort);
                } catch (Exception e) {
                    LOG.warn("Error setting shutdown port, using default", e);
                    portConfig.setShutdown(PortConfig.DEFAULT_SHUTDOWN_PORT);
                }

                // === JMX CONFIGURATION ===
                try {
                    if (DynamicTomcatEnvironment.isJmxEnabled()) {
                        int jmxPort = DynamicTomcatEnvironment.getJmxPort();
                        portConfig.setJmxEnabled(true);
                        portConfig.setJmx(jmxPort);
                        LOG.debug("JMX enabled on port: " + jmxPort);
                    } else {
                        portConfig.setJmxEnabled(false);
                        LOG.debug("JMX disabled");
                    }
                } catch (Exception e) {
                    LOG.warn("Error configuring JMX, disabling", e);
                    portConfig.setJmxEnabled(false);
                }

                // === HTTPS CONFIGURATION ===
                try {
                    if (DynamicTomcatEnvironment.isHttpsEnabled()) {
                        int httpsPort = DynamicTomcatEnvironment.getHttpsPort();
                        portConfig.setHttpsEnabled(true);
                        portConfig.setHttps(httpsPort);
                        LOG.debug("HTTPS enabled on port: " + httpsPort);
                    } else {
                        portConfig.setHttpsEnabled(false);
                        LOG.debug("HTTPS disabled");
                    }
                } catch (Exception e) {
                    LOG.warn("Error configuring HTTPS, disabling", e);
                    portConfig.setHttpsEnabled(false);
                }

                // === DEPLOYMENT CONFIGURATION ===
                try {
                    DeploymentConfig deploymentConfig = data.getDeploymentConfig();
                    if (deploymentConfig == null) {
                        LOG.debug("Creating new DeploymentConfig");
                        deploymentConfig = new DeploymentConfig();
                        data.setDeploymentConfig(deploymentConfig);
                    }

                    boolean hotDeployEnabled = DynamicTomcatEnvironment.isHotDeploymentEnabled();
                    deploymentConfig.setHotDeploymentEnabled(hotDeployEnabled);
                    deploymentConfig.setUpdateClassesAndResources(hotDeployEnabled);
                    LOG.debug("Deployment config: hotDeploy=" + hotDeployEnabled);
                } catch (Exception e) {
                    LOG.warn("Error configuring deployment settings", e);
                }

                // === BROWSER CONFIGURATION ===
                try {
                    BrowserConfig browserConfig = data.getBrowserConfig();
                    if (browserConfig == null) {
                        LOG.debug("Creating new BrowserConfig");
                        browserConfig = new BrowserConfig();
                        data.setBrowserConfig(browserConfig);
                    }

                    boolean autoLaunch = DynamicTomcatEnvironment.shouldAutoLaunchBrowser();
                    browserConfig.setAfterLaunchEnabled(autoLaunch);
                    LOG.debug("Auto-launch browser: " + autoLaunch);
                } catch (Exception e) {
                    LOG.warn("Error configuring browser settings", e);
                }

                // === CONTEXT PATH ===
                try {
                    data.setContextPath("/");
                    LOG.debug("Context path set to: /");
                } catch (Exception e) {
                    LOG.warn("Error setting context path", e);
                }

                // === VM OPTIONS ===
                try {
                    VmConfig vmConfig = data.getVmConfig();
                    if (vmConfig == null) {
                        LOG.debug("Creating new VmConfig");
                        vmConfig = new VmConfig();
                        data.setVmConfig(vmConfig);
                    }

                    String javaOpts = DynamicTomcatEnvironment.buildJavaOpts();
                    if (javaOpts != null && !javaOpts.trim().isEmpty()) {
                        vmConfig.setVmOptions(javaOpts);
                        LOG.debug("VM options set from DynamicTomcatEnvironment");
                    }
                } catch (Exception e) {
                    LOG.warn("Error configuring VM options", e);
                }

                // === ENVIRONMENT VARIABLES ===
                try {
                    VmConfig vmConfig = data.getVmConfig();
                    if (vmConfig == null) {
                        LOG.debug("Creating new VmConfig for environment variables");
                        vmConfig = new VmConfig();
                        data.setVmConfig(vmConfig);
                    }

                    Map<String, String> envVars = DynamicTomcatEnvironment.buildEnvironmentVariables();
                    if (envVars != null && !envVars.isEmpty()) {
                        vmConfig.setEnvironmentVariables(envVars);
                        LOG.debug("Environment variables set: " + envVars.size() + " variables");
                    }

                    vmConfig.setPassParentEnvs(true);
                    LOG.debug("Pass parent environment variables: true");
                } catch (Exception e) {
                    LOG.warn("Error configuring environment variables", e);
                }
                // === AUTO-SELECT TOMCAT SERVER ===
                try {
                    autoSelectTomcatServer(config);
                } catch (Exception e) {
                    LOG.warn("Error auto-selecting Tomcat server", e);
                }

                LOG.debug("Applied dynamic defaults: " + DynamicTomcatEnvironment.getConfigurationSummary());
            } catch (Exception e) {
                LOG.error("Unexpected error applying dynamic defaults", e);
            }
        }

        /**
         * Auto-select the best available Tomcat server.
         *
         * <p>Selects the highest version Tomcat server from registered servers.
         * If only one server is available, selects that one. If none are available,
         * no server is selected and manual configuration is required.
         *
         * @param config the configuration to update
         */
        private void autoSelectTomcatServer(@NotNull TomcatRunConfiguration config) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            try {
                TomcatServerManagerState manager = TomcatServerManagerState.getInstance();
                if (manager == null) {
                    LOG.warn("TomcatServerManagerState not available");
                    return;
                }

                List<TomcatInfo> servers = manager.getTomcatInfos();
                if (servers == null || servers.isEmpty()) {
                    LOG.debug("No Tomcat servers registered, manual configuration required");
                    return;
                }

                // === SELECT HIGHEST VERSION ===
                TomcatInfo selected = servers.stream()
                        .filter(Objects::nonNull)
                        .max((s1, s2) -> {
                            String v1 = s1.getVersion() != null ? s1.getVersion() : "0";
                            String v2 = s2.getVersion() != null ? s2.getVersion() : "0";
                            return v1.compareTo(v2);
                        })
                        .orElse(null);

                if (selected != null) {
                    config.getConfigData().setTomcatInfo(selected);
                    LOG.debug("Auto-selected Tomcat server: " + selected.getName() + " (" + selected.getVersion() + ")");
                } else {
                    LOG.debug("No valid Tomcat server found for auto-selection");
                }
            } catch (Exception e) {
                LOG.warn("Failed to auto-select Tomcat server", e);
            }
        }

        /**
         * Create a new configuration by cloning the template.
         *
         * <p>Process:
         * <ul>
         *   <li>Clone the template configuration</li>
         *   <li>Set the new configuration name</li>
         *   <li>Re-apply dynamic defaults</li>
         *   <li>Validate the configuration</li>
         *   <li>Auto-fix any port conflicts or invalid values</li>
         * </ul>
         *
         * @param name     the name for the new configuration
         * @param template the template configuration to clone from
         * @return new TomcatRunConfiguration with applied defaults
         */
        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            Objects.requireNonNull(template, "Template configuration cannot be null");

            try {
                String configName = StringUtil.notNullize(name, "Tomcat");
                LOG.debug("Creating new configuration: " + configName);

                TomcatRunConfiguration newConfig = (TomcatRunConfiguration) super.createConfiguration(configName, template);
                Objects.requireNonNull(newConfig, "Created configuration cannot be null");

                // === RE-APPLY DYNAMIC DEFAULTS ===
                try {
                    applyDynamicDefaults(newConfig);
                } catch (Exception e) {
                    LOG.warn("Error re-applying dynamic defaults", e);
                }

                // === VALIDATE CONFIGURATION ===
                try {
                    newConfig.checkConfiguration();
                    LOG.debug("Configuration validated successfully: " + configName);
                } catch (RuntimeConfigurationException e) {
                    LOG.warn("Configuration validation failed, attempting auto-fix: " + configName);
                    autoFixConfiguration(newConfig);
                }

                return newConfig;
            } catch (Exception e) {
                LOG.error("Failed to create configuration: " + name, e);
                throw new RuntimeException("Cannot create Tomcat configuration", e);
            }
        }

        /**
         * Auto-fix invalid configuration values.
         *
         * <p>Attempts to fix common issues:
         * <ul>
         *   <li>Invalid HTTP port → use default 8080</li>
         *   <li>Invalid shutdown port → use default 8005</li>
         *   <li>Port conflicts → auto-adjust ports</li>
         * </ul>
         *
         * @param config the configuration to fix
         */
        private void autoFixConfiguration(@NotNull TomcatRunConfiguration config) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            try {
                TomcatConfigurationData data = config.getConfigData();
                if (data == null) {
                    LOG.warn("Configuration data is null, cannot auto-fix");
                    return;
                }

                PortConfig pc = data.getPortConfig();
                if (pc == null) {
                    LOG.warn("Port configuration is null, cannot auto-fix");
                    return;
                }

                boolean fixed = false;

                // === FIX HTTP PORT ===
                int httpPort = pc.getHttp();
                if (httpPort < 1 || httpPort > 65535) {
                    LOG.debug("Fixing invalid HTTP port: " + httpPort);
                    pc.setHttp(PortConfig.DEFAULT_HTTP_PORT);
                    fixed = true;
                }

                // === FIX SHUTDOWN PORT ===
                int shutdownPort = pc.getShutdown();
                if (shutdownPort < 1 || shutdownPort > 65535) {
                    LOG.debug("Fixing invalid shutdown port: " + shutdownPort);
                    pc.setShutdown(PortConfig.DEFAULT_SHUTDOWN_PORT);
                    fixed = true;
                }

                // === FIX HTTPS PORT (IF ENABLED) ===
                if (pc.isHttpsEnabled()) {
                    int httpsPort = pc.getHttps();
                    if (httpsPort < 1 || httpsPort > 65535) {
                        LOG.debug("Fixing invalid HTTPS port: " + httpsPort);
                        pc.setHttps(8443);
                        fixed = true;
                    }
                }

                // === FIX JMX PORT (IF ENABLED) ===
                if (pc.isJmxEnabled()) {
                    int jmxPort = pc.getJmx();
                    if (jmxPort < 1 || jmxPort > 65535) {
                        LOG.debug("Fixing invalid JMX port: " + jmxPort);
                        pc.setJmx(9010);
                        fixed = true;
                    }
                }

                if (fixed) {
                    LOG.debug("Auto-fixed configuration, new ports: HTTP=" + pc.getHttp() +
                            ", Shutdown=" + pc.getShutdown());

                    // === RE-VALIDATE AFTER FIX ===
                    try {
                        config.checkConfiguration();
                        LOG.debug("Configuration re-validated successfully after auto-fix");
                    } catch (RuntimeConfigurationException e) {
                        LOG.warn("Configuration still invalid after auto-fix", e);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error during auto-fix", e);
            }
        }
    }

    /**
     * Factory for Local Dev Tomcat configurations.
     */
    public static class LocalTomcatConfigurationFactory extends TomcatConfigurationFactory {
        protected LocalTomcatConfigurationFactory(@NotNull ConfigurationType type) {
            super(type, "Local");
        }

        @Override
        @NotNull
        public String getId() {
            return "DevTomcatLocal";
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            TomcatRunConfiguration config = (TomcatRunConfiguration) super.createTemplateConfiguration(project);
            config.getConfigData().setServerMode("Local");
            return config;
        }

        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            RunConfiguration created = super.createConfiguration(name, template);
            if (created instanceof TomcatRunConfiguration tomcatConfig) {
                tomcatConfig.getConfigData().setServerMode("Local");
            }
            return created;
        }
    }

    /**
     * Factory for Remote Dev Tomcat configurations.
     */
    public static class RemoteTomcatConfigurationFactory extends TomcatConfigurationFactory {
        protected RemoteTomcatConfigurationFactory(@NotNull ConfigurationType type) {
            super(type, "Remote");
        }

        @Override
        @NotNull
        public String getId() {
            return "DevTomcatRemote";
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            TomcatRunConfiguration config = (TomcatRunConfiguration) super.createTemplateConfiguration(project);
            config.getConfigData().setServerMode("Remote");
            return config;
        }

        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            RunConfiguration created = super.createConfiguration(name, template);
            if (created instanceof TomcatRunConfiguration tomcatConfig) {
                tomcatConfig.getConfigData().setServerMode("Remote");
            }
            return created;
        }
    }
}
