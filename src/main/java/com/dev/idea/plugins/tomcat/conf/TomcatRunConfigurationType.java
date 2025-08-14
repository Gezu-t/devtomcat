package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Dev Tomcat Run Configuration Type
 *
 * Defines the Dev Tomcat configuration type for IntelliJ IDEA, providing
 * integration for Apache Tomcat server management within the IDE.
 *
 * This implementation uses dynamic configuration values from the registry
 * instead of hardcoded values, making it more flexible and maintainable.
 *
 * @author Dev Tomcat Team
 * @see ConfigurationType
 */
public class TomcatRunConfigurationType implements ConfigurationType {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfigurationType.class);

    // Configuration constants
    public static final String ID = "com.dev.idea.plugins.tomcat";
    public static final String DISPLAY_NAME = "Dev Tomcat";
    public static final String DESCRIPTION = "Apache Tomcat server integration for web application development";

    // Icon paths
    private static final String ICON_PATH_SVG = "/icons/tomcat.svg";
    private static final String ICON_PATH_PNG = "/icons/tomcat.png";

    // Thread-safe lazy initialization
    private volatile TomcatConfigurationFactory factory;

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
        // Try loading icons in order of preference
        Icon icon = loadIcon(ICON_PATH_SVG);
        if (icon == null) {
            icon = loadIcon(ICON_PATH_PNG);
        }
        return icon;
    }

    /**
     * Safely load an icon from the specified path
     *
     * @param path The icon resource path
     * @return The loaded icon, or null if loading fails
     */
    @Nullable
    private Icon loadIcon(String path) {
        try {
            return IconLoader.getIcon(path, getClass());
        } catch (Exception e) {
            LOG.debug("Failed to load icon from path: " + path, e);
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
        // Thread-safe lazy initialization
        if (factory == null) {
            synchronized (this) {
                if (factory == null) {
                    factory = new TomcatConfigurationFactory(this);
                    LOG.debug("Dev Tomcat configuration factory initialized");
                }
            }
        }
        return new ConfigurationFactory[]{factory};
    }

    /**
     * Configuration Factory for Dev Tomcat Run Configurations
     *
     * Creates and manages Tomcat run configurations with dynamic defaults
     * loaded from the IDE registry.
     */
    public static class TomcatConfigurationFactory extends ConfigurationFactory {

        // Registry key constants
        private static final String REG_AUTO_CONFIG = "devtomcat.enable.auto.configuration";
        private static final String REG_HOT_DEPLOYMENT = "devtomcat.enable.hot.deployment";
        private static final String REG_JMX_ENABLED = "devtomcat.enable.jmx.monitoring";
        private static final String REG_DEFAULT_HTTP_PORT = "devtomcat.default.http.port";
        private static final String REG_DEFAULT_JMX_PORT = "devtomcat.default.jmx.port";
        private static final String REG_DEFAULT_XMX = "devtomcat.default.xmx";
        private static final String REG_DEFAULT_XMS = "devtomcat.default.xms";
        private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";
        private static final String REG_FAST_SHUTDOWN = "devtomcat.dev.fast.shutdown";

        protected TomcatConfigurationFactory(@NotNull ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            LOG.debug("Creating Dev Tomcat configuration for project: " + project.getName());

            TomcatRunConfiguration configuration = new TomcatRunConfiguration(project, this, "");

            // Apply dynamic defaults from registry
            applyDynamicDefaults(configuration, project);

            return configuration;
        }

        /**
         * Apply configuration defaults using dynamic values from the registry
         *
         * @param configuration The configuration to initialize
         * @param project The current project
         */
        private void applyDynamicDefaults(TomcatRunConfiguration configuration, Project project) {
            // Port configuration
            configuration.setPort(Registry.intValue(REG_DEFAULT_HTTP_PORT));
            configuration.setJmxPort(Registry.intValue(REG_DEFAULT_JMX_PORT));
            configuration.setJmxEnabled(Registry.is(REG_JMX_ENABLED));

            // Development features
            configuration.setHotDeploymentEnabled(Registry.is(REG_HOT_DEPLOYMENT));
            configuration.setUpdateClassesAndResources(Registry.is(REG_HOT_DEPLOYMENT));
            configuration.setPassParentEnvs(true);

            // Context path
            configuration.setContextPath("/");

            // VM options
            String vmOptions = buildDynamicVmOptions();
            configuration.setVmOptions(vmOptions);

            // Environment variables
            setupDynamicEnvironmentVariables(configuration);

            // Auto-select server if enabled
            if (Registry.is(REG_AUTO_CONFIG)) {
                autoSelectTomcatServer(configuration);
            }

            LOG.debug("Applied dynamic defaults to Dev Tomcat configuration");
        }

        /**
         * Build VM options using dynamic values from registry
         *
         * @return VM options string
         */
        private String buildDynamicVmOptions() {
            StringBuilder options = new StringBuilder();

            // Memory settings from registry
            String xmx = Registry.stringValue(REG_DEFAULT_XMX);
            String xms = Registry.stringValue(REG_DEFAULT_XMS);

            options.append("-Xmx").append(xmx).append(" ");
            options.append("-Xms").append(xms).append(" ");

            // Standard options
            options.append("-Dfile.encoding=UTF-8 ");

            // Development mode
            options.append("-Ddev.mode=true ");

            // Fast shutdown if enabled
            if (Registry.is(REG_FAST_SHUTDOWN)) {
                options.append("-Dorg.apache.catalina.startup.EXIT_ON_INIT_FAILURE=true ");
            }

            // Enable assertions for development
            options.append("-ea ");

            return options.toString().trim();
        }

        /**
         * Set up environment variables with dynamic values
         *
         * @param configuration The configuration to update
         */
        private void setupDynamicEnvironmentVariables(TomcatRunConfiguration configuration) {
            // Basic JAVA_OPTS
            configuration.getEnvironmentVariables().put("JAVA_OPTS",
                    "-Dfile.encoding=UTF-8 -Duser.timezone=UTC");

            // Development mode indicator
            configuration.getEnvironmentVariables().put("DEV_MODE", "true");

            // Logging configuration
            if (Registry.is(REG_SHOW_TIMESTAMPS)) {
                configuration.getEnvironmentVariables().put("CATALINA_OPTS",
                        "-Djava.util.logging.SimpleFormatter.format=[%1$tF %1$tT] [%4$-7s] %5$s %n");
            }
        }

        /**
         * Automatically select the most appropriate Tomcat server
         *
         * @param configuration The configuration to update
         */
        private void autoSelectTomcatServer(TomcatRunConfiguration configuration) {
            try {
                List<TomcatInfo> servers = TomcatServerManagerState.getInstance().getTomcatInfos();

                if (!servers.isEmpty()) {
                    // Select the newest version by default
                    TomcatInfo selectedServer = servers.stream()
                            .filter(Objects::nonNull)
                            .max(Comparator.comparing(TomcatInfo::getVersion,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                            .orElse(servers.get(0));

                    configuration.setTomcatInfo(selectedServer);
                    LOG.info("Auto-selected Tomcat server: " + selectedServer.getName() +
                            " (version " + selectedServer.getVersion() + ")");
                } else {
                    LOG.debug("No Tomcat servers configured for auto-selection");
                }
            } catch (Exception e) {
                LOG.warn("Failed to auto-select Tomcat server", e);
            }
        }

        @Override
        @NotNull
        public String getName() {
            return DISPLAY_NAME;
        }

        @Override
        @NotNull
        public String getId() {
            return ID;
        }

        @Override
        public boolean isApplicable(@NotNull Project project) {
            // Dev Tomcat is applicable to all Java projects
            return true;
        }

        @Override
        public @NotNull RunConfiguration createConfiguration(@Nullable String name,
                                                             @NotNull RunConfiguration template) {
            if (!(template instanceof TomcatRunConfiguration)) {
                // Fallback to creating new configuration
                LOG.debug("Template is not TomcatRunConfiguration, creating new instance");
                return createTemplateConfiguration(template.getProject());
            }

            TomcatRunConfiguration cloned = (TomcatRunConfiguration) template.clone();

            if (name != null && !name.trim().isEmpty()) {
                cloned.setName(name);
            }

            // Validate configuration integrity
            validateConfiguration(cloned);

            LOG.debug("Created Dev Tomcat configuration: " +
                    (name != null ? name : "unnamed"));
            return cloned;
        }

        /**
         * Validate and fix configuration if needed
         *
         * @param configuration The configuration to validate
         */
        private void validateConfiguration(TomcatRunConfiguration configuration) {
            // Ensure critical settings are present
            if (configuration.getTomcatInfo() == null) {
                autoSelectTomcatServer(configuration);
            }

            // Ensure hot deployment is configured according to registry
            if (configuration.isHotDeploymentEnabled() != Registry.is(REG_HOT_DEPLOYMENT)) {
                LOG.debug("Updating hot deployment setting to match registry");
                configuration.setHotDeploymentEnabled(Registry.is(REG_HOT_DEPLOYMENT));
            }

            // Validate port ranges
            int httpPort = configuration.getPort();
            if (httpPort < 1 || httpPort > 65535) {
                LOG.warn("Invalid HTTP port " + httpPort + ", resetting to default");
                configuration.setPort(Registry.intValue(REG_DEFAULT_HTTP_PORT));
            }

            // Ensure environment variables are set
            if (configuration.getEnvironmentVariables().isEmpty()) {
                setupDynamicEnvironmentVariables(configuration);
            }
        }
    }
}