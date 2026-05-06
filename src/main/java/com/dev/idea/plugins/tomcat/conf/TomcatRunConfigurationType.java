package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationSingletonPolicy;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.icons.AllIcons;
import javax.swing.*;
import java.util.List;
import java.util.Objects;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/** Registers Tomcat as a run configuration type, provides Local/Remote factories. */
public class TomcatRunConfigurationType implements ConfigurationType {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationType.class);

    public static final String ID = "com.dev.idea.plugins.tomcat";
    public static final String DISPLAY_NAME = "Dev Tomcat";
    public static final String DESCRIPTION = "Apache Tomcat server integration for web application development";

    private static final String ICON_PATH_SVG = "/icon/tomcat.svg";
    /**
     * Path of the Tomcat icon that the IntelliJ platform itself ships in
     * {@code app-client.jar} under Apache 2.0. Loading from this path uses the
     * platform's own brand mark — the same SVG that renders next to a Tomcat
     * run configuration in the run-config dropdown of paid editions — and
     * automatically picks up the platform's light/dark variants and
     * 16x16-native rendering hints. Falls back to {@link #ICON_PATH_SVG}
     * (our bundled copy) if a future platform version moves or removes it.
     */
    private static final String PLATFORM_TOMCAT_ICON_PATH = "/runConfigurations/tomcat.svg";
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
        return tomcatIcon();
    }

    /**
     * Single source of truth for the DevTomcat run-config icon. Used by the
     * configuration type itself and by both Local and Remote factories so the
     * Run-config dropdown, Edit Run Configurations dialog, and Services tree
     * always show the same Tomcat brand mark regardless of deployment target.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link #PLATFORM_TOMCAT_ICON_PATH} — the platform's own
     *       Apache-2.0-licensed Tomcat brand mark, designed for 16x16 toolbar
     *       rendering with proper light/dark variants. Resolved via
     *       {@code AllIcons.class.getResource} so we only use it when the
     *       platform actually exposes the resource on the classpath; a missing
     *       URL means a future platform version moved the file.</li>
     *   <li>{@link #ICON_PATH_SVG} — our bundled fallback copy.</li>
     *   <li>{@link #DEFAULT_ICON} — a generic Java application icon. Never
     *       returns {@code null} so callers can use the result directly
     *       without a null-check.</li>
     * </ol>
     */
    @NotNull
    static Icon tomcatIcon() {
        try {
            if (AllIcons.class.getResource(PLATFORM_TOMCAT_ICON_PATH) != null) {
                Icon platformIcon = IconLoader.getIcon(PLATFORM_TOMCAT_ICON_PATH, AllIcons.class);
                if (platformIcon != null) {
                    return platformIcon;
                }
            }
        } catch (Exception e) {
            LOG.debug("Platform Tomcat icon unreachable, falling back to bundled SVG: " + e.getMessage());
        }
        try {
            Icon icon = IconLoader.getIcon(ICON_PATH_SVG, TomcatRunConfigurationType.class);
            return icon != null ? icon : DEFAULT_ICON;
        } catch (Exception e) {
            LOG.debug("Failed to load Tomcat icon, using default: " + e.getMessage());
            return DEFAULT_ICON;
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

        /**
         * Declare this configuration type as always allowing multiple running instances.
         * This bypasses IntelliJ's "Stop and Rerun" dialog (which fires when the policy
         * is {@code SINGLE_INSTANCE}, the platform default) and instead calls
         * {@code doExecute()} directly on {@link com.dev.idea.plugins.tomcat.runner.TomcatRunner}
         * / {@link com.dev.idea.plugins.tomcat.runner.TomcatDebugger}, where we detect
         * the existing process and show our own Update dialog.
         */
        @Override
        @NotNull
        public RunConfigurationSingletonPolicy getSingletonPolicy() {
            return RunConfigurationSingletonPolicy.MULTIPLE_INSTANCE_ONLY;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public void configureBeforeRunTaskDefaults(Key<? extends BeforeRunTask> providerID,
                                                    BeforeRunTask task) {
            // Keep the default "Build" (Make) task enabled so new configurations
            // always have at least a compile step in Before Launch.
            super.configureBeforeRunTaskDefaults(providerID, task);
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

                    // Deployment tab starts empty — users add artifacts via "+" button,
                    // matching IntelliJ Ultimate behavior.
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
                    if (StringUtil.isEmpty(data.getContextPath())) {
                        data.setContextPath("/");
                        LOG.debug("Context path defaulted to: /");
                    }
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

                // Environment variables are NOT pre-filled — users add them manually
                // via the Startup/Connection tab (matching IntelliJ Ultimate behavior).
                // passParentEnvs defaults to true in RunnerSettings.
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
                            return compareSemanticVersions(s1.getVersion(), s2.getVersion());
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

            // Do NOT call checkConfiguration() here — validation is the IDE's responsibility
            // when the user clicks Run/Debug. Calling it here produces noisy false-alarm logs
            // (e.g., "no Tomcat server selected") that autoFixConfiguration cannot resolve.

            return newConfig;
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
            return tomcatIcon();
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
            // Same Tomcat brand mark as the Local factory — Remote and Local
            // are both Tomcat run configurations; only the deployment target
            // differs. Showing AllIcons.Nodes.Deploy here previously made
            // Remote configs look like an unrelated configuration type in the
            // Run-config dropdown and Services tree.
            return tomcatIcon();
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
