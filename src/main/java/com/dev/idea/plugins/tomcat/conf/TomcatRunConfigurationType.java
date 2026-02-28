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

import com.intellij.icons.AllIcons;
import javax.swing.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/** Registers Tomcat as a run configuration type, provides Local/Remote factories. */
public class TomcatRunConfigurationType implements ConfigurationType {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationType.class);

    public static final String ID = "com.dev.idea.plugins.tomcat";
    public static final String DISPLAY_NAME = "Dev Tomcat";
    public static final String DESCRIPTION = "Apache Tomcat server integration for web application development";

    private static final String ICON_PATH_SVG = "/icon/tomcat.svg";
    private static final String ICON_PATH_PNG = "/icon/tomcat.png";
    private static final Icon DEFAULT_ICON = AllIcons.RunConfigurations.Application;

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

    public static class TomcatConfigurationFactory extends ConfigurationFactory {

        private final String factoryName;

                protected TomcatConfigurationFactory(@NotNull ConfigurationType type, @NotNull String name) {
            super(type);
            this.factoryName = name;
        }

        @Override
        @NotNull
        public String getName() {
            return factoryName;
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            Objects.requireNonNull(project, "Project cannot be null");

            TomcatRunConfiguration config = new TomcatRunConfiguration(project, this, "Tomcat");
            try {
                applyDynamicDefaults(config);
            } catch (Exception e) {
                LOG.error("Failed to apply defaults for project: " + project.getName(), e);
            }
            return config;
        }

        @Override
        public boolean isApplicable(@NotNull Project project) {
            return true;
        }

        protected void applyDynamicDefaults(@NotNull TomcatRunConfiguration config) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            try {
                TomcatConfigurationData data = config.getConfigData();
                Objects.requireNonNull(data, "Configuration data cannot be null");

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

                try {
                    data.setContextPath("/");
                    LOG.debug("Context path set to: /");
                } catch (Exception e) {
                    LOG.warn("Error setting context path", e);
                }

                try {
                    VmConfig vmConfig = data.getVmConfig();
                    if (vmConfig == null) {
                        LOG.debug("Creating new VmConfig");
                        vmConfig = new VmConfig();
                        data.setVmConfig(vmConfig);
                    }
                } catch (Exception e) {
                    LOG.warn("Error configuring VM options", e);
                }

                try {
                    VmConfig vmConfig = data.getVmConfig();
                    if (vmConfig == null) {
                        LOG.debug("Creating new VmConfig for environment variables");
                        vmConfig = new VmConfig();
                        data.setVmConfig(vmConfig);
                    }

                    Map<String, String> envVars = DynamicTomcatEnvironment.buildEnvironmentVariables();
                    if (envVars != null && !envVars.isEmpty()) {
                        data.getRunnerSettings("Run").setEnvironmentVariables(envVars);
                        LOG.debug("Environment variables set: " + envVars.size() + " variables");
                    }

                    data.getRunnerSettings("Run").setPassParentEnvs(true);
                    LOG.debug("Pass parent environment variables: true");
                } catch (Exception e) {
                    LOG.warn("Error configuring environment variables", e);
                }
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

                TomcatInfo selected = servers.stream()
                        .filter(Objects::nonNull)
                        .max((s1, s2) -> {
                            String v1 = s1.getVersion() != null ? s1.getVersion() : "0";
                            String v2 = s2.getVersion() != null ? s2.getVersion() : "0";
                            return compareSemanticVersions(v1, v2);
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

        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            Objects.requireNonNull(template, "Template configuration cannot be null");

            String configName = StringUtil.notNullize(name, "Tomcat");
            LOG.debug("Creating new configuration: " + configName);

            TomcatRunConfiguration newConfig = (TomcatRunConfiguration) super.createConfiguration(configName, template);

            try {
                applyDynamicDefaults(newConfig);
            } catch (Exception e) {
                LOG.warn("Error re-applying dynamic defaults", e);
            }

            try {
                newConfig.checkConfiguration();
                LOG.debug("Configuration validated successfully: " + configName);
            } catch (RuntimeConfigurationException e) {
                LOG.warn("Configuration validation failed, attempting auto-fix: " + configName);
                autoFixConfiguration(newConfig);
            }

            return newConfig;
        }

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

                int httpPort = pc.getHttp();
                if (httpPort < 1 || httpPort > 65535) {
                    LOG.debug("Fixing invalid HTTP port: " + httpPort);
                    pc.setHttp(PortConfig.DEFAULT_HTTP_PORT);
                    fixed = true;
                }

                int shutdownPort = pc.getShutdown();
                if (shutdownPort < 1 || shutdownPort > 65535) {
                    LOG.debug("Fixing invalid shutdown port: " + shutdownPort);
                    pc.setShutdown(PortConfig.DEFAULT_SHUTDOWN_PORT);
                    fixed = true;
                }

                if (pc.isHttpsEnabled()) {
                    int httpsPort = pc.getHttps();
                    if (httpsPort < 1 || httpsPort > 65535) {
                        LOG.debug("Fixing invalid HTTPS port: " + httpsPort);
                        pc.setHttps(8443);
                        fixed = true;
                    }
                }

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

        private static int compareSemanticVersions(@NotNull String v1, @NotNull String v2) {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            int maxLen = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < maxLen; i++) {
                int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
                int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
                if (num1 != num2) return Integer.compare(num1, num2);
            }
            return 0;
        }

        private static int parseVersionPart(@NotNull String part) {
            try {
                return Integer.parseInt(part.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

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
        public Icon getIcon() {
            try {
                Icon icon = IconLoader.getIcon(ICON_PATH_SVG, TomcatRunConfigurationType.class);
                return icon != null ? icon : DEFAULT_ICON;
            } catch (Exception e) {
                return DEFAULT_ICON;
            }
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            TomcatRunConfiguration config = (TomcatRunConfiguration) super.createTemplateConfiguration(project);
            config.getConfigData().setServerMode(TomcatConstants.MODE_LOCAL);
            return config;
        }

        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            RunConfiguration created = super.createConfiguration(name, template);
            if (created instanceof TomcatRunConfiguration tomcatConfig) {
                tomcatConfig.getConfigData().setServerMode(TomcatConstants.MODE_LOCAL);
            }
            return created;
        }
    }

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
        public Icon getIcon() {
            return AllIcons.Nodes.Deploy;
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            TomcatRunConfiguration config = (TomcatRunConfiguration) super.createTemplateConfiguration(project);
            config.getConfigData().setServerMode(TomcatConstants.MODE_REMOTE);
            return config;
        }

        @Override
        @NotNull
        public RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            RunConfiguration created = super.createConfiguration(name, template);
            if (created instanceof TomcatRunConfiguration tomcatConfig) {
                tomcatConfig.getConfigData().setServerMode(TomcatConstants.MODE_REMOTE);
            }
            return created;
        }
    }
}
