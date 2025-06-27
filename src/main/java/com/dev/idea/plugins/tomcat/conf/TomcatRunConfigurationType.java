package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

/**
 * DevTomcat Run Configuration Type
 * Provides professional Tomcat server integration
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatRunConfigurationType implements ConfigurationType {

    public static final String ID = "com.dev.idea.plugins.tomcat";
    public static final String DISPLAY_NAME = "DevTomcat";
    public static final String DESCRIPTION = "Professional Tomcat server with comprehensive development features";

    // Thread-safe factory instance
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
        try {
            return IconLoader.getIcon("/icon/tomcat.svg", TomcatRunConfigurationType.class);
        } catch (Exception e) {
            System.err.println("DevTomcat: Could not load Tomcat icon: " + e.getMessage());
            try {
                return IconLoader.getIcon("/icons/tomcat.png", TomcatRunConfigurationType.class);
            } catch (Exception e2) {
                System.err.println("DevTomcat: Alternative icon also not found, using default");
                return null;
            }
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
        if (factory == null) {
            synchronized (this) {
                if (factory == null) {
                    factory = new TomcatConfigurationFactory(this);
                    System.out.println("DevTomcat: Professional configuration factory initialized");
                }
            }
        }
        return new ConfigurationFactory[]{factory};
    }

    /**
     * Professional Configuration Factory
     * Handles creation of Tomcat run configurations with professional features
     */
    public static class TomcatConfigurationFactory extends ConfigurationFactory {

        protected TomcatConfigurationFactory(@NotNull ConfigurationType type) {
            super(type);
        }

        @Override
        @NotNull
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            try {
                System.out.println("DevTomcat: Creating professional configuration for project: " + project.getName());

                TomcatRunConfiguration configuration = new TomcatRunConfiguration(project, this, "");

                // Apply professional defaults
                applyProfessionalDefaults(configuration, project);

                System.out.println("DevTomcat: Professional configuration created successfully");
                return configuration;

            } catch (Exception e) {
                System.err.println("DevTomcat: ERROR creating professional configuration: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to create DevTomcat configuration", e);
            }
        }

        /**
         * Apply professional default settings to new configurations
         */
        private void applyProfessionalDefaults(TomcatRunConfiguration configuration, Project project) {
            try {
                // Basic server configuration
                configuration.setPort(8080);

                // JMX configuration (enabled by default for professional monitoring)
                configuration.setJmxEnabled(true);
                configuration.setJmxPort(1099);

                // Development optimization defaults
                configuration.setHotDeploymentEnabled(true);
                configuration.setUpdateClassesAndResources(true);
                configuration.setPassParentEnvs(true);

                // Professional VM options
                String vmOptions = buildProfessionalVmOptions();
                configuration.setVmOptions(vmOptions);

                // Context path configuration
                configuration.setContextPath("/");

                // Environment variables for development
                setupDevelopmentEnvironmentVariables(configuration);

                // Server selection if available
                setupServerSelection(configuration);

                System.out.println("DevTomcat: Applied comprehensive professional defaults");

            } catch (Exception e) {
                System.err.println("DevTomcat: Warning - Could not apply all defaults: " + e.getMessage());
            }
        }

        /**
         * Build professional VM options for optimal development
         */
        private String buildProfessionalVmOptions() {
            StringBuilder vmOptions = new StringBuilder();

            // Professional encoding and locale settings
            vmOptions.append("-Dfile.encoding=UTF-8 ");
            vmOptions.append("-Duser.timezone=GMT ");
            vmOptions.append("-Djava.awt.headless=true ");

            // Memory optimization for development
            vmOptions.append("-Xmx1024m -Xms512m ");

            // Garbage collection optimization
            vmOptions.append("-XX:+UseG1GC ");
            vmOptions.append("-XX:+UseStringDeduplication ");

            // Development flags
            vmOptions.append("-Ddevelopment=true ");
            vmOptions.append("-Dspring.profiles.active=dev ");

            return vmOptions.toString().trim();
        }

        /**
         * Setup professional development environment variables
         */
        private void setupDevelopmentEnvironmentVariables(TomcatRunConfiguration configuration) {
            // Professional Java development options
            configuration.getEnvironmentVariables().put("JAVA_OPTS",
                    "-Xmx1024m -Xms512m -XX:+UseG1GC -Dfile.encoding=UTF-8");

            // Professional Catalina development options
            configuration.getEnvironmentVariables().put("CATALINA_OPTS",
                    "-Ddevelopment=true -Dspring.profiles.active=dev");

            // Development flags
            configuration.getEnvironmentVariables().put("DEVTOMCAT_MODE", "development");

            System.out.println("DevTomcat: Professional development environment variables configured");
        }

        /**
         * Setup professional server selection with intelligent defaults
         */
        private void setupServerSelection(TomcatRunConfiguration configuration) {
            try {
                List<TomcatInfo> servers = TomcatServerManagerState.getInstance().getTomcatInfos();

                if (!servers.isEmpty()) {
                    // Server selection strategy - prefer latest version
                    TomcatInfo selectedServer = servers.stream()
                            .max((s1, s2) -> s1.getVersion().compareTo(s2.getVersion()))
                            .orElse(servers.get(0));

                    configuration.setTomcatInfo(selectedServer);
                    System.out.println("DevTomcat: Professional server selected - " +
                            selectedServer.getName() + " " + selectedServer.getVersion());
                } else {
                    System.out.println("DevTomcat: No Tomcat servers configured - configuration will require server setup");
                }
            } catch (Exception e) {
                System.err.println("DevTomcat: Could not setup server selection: " + e.getMessage());
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
            // Available for all projects with web development potential
            return true;
        }

        @Override
        public @NotNull RunConfiguration createConfiguration(@Nullable String name, @NotNull RunConfiguration template) {
            try {
                if (template instanceof TomcatRunConfiguration) {
                    // Professional configuration cloning
                    TomcatRunConfiguration cloned = (TomcatRunConfiguration) template.clone();

                    if (name != null && !name.trim().isEmpty()) {
                        cloned.setName(name);
                    }

                    // Ensure professional defaults are maintained
                    validateConfiguration(cloned);

                    System.out.println("DevTomcat: Professional configuration cloned - " +
                            (name != null ? name : "unnamed"));
                    return cloned;

                } else {
                    // Create new professional configuration
                    System.out.println("DevTomcat: Creating new configuration from non-Tomcat template");
                    return createTemplateConfiguration(template.getProject());
                }
            } catch (Exception e) {
                System.err.println("DevTomcat: Error creating configuration from template: " + e.getMessage());
                return createTemplateConfiguration(template.getProject());
            }
        }

        /**
         * Validate configuration standards
         */
        private void validateConfiguration(TomcatRunConfiguration configuration) {
            try {
                // Professional validation checks
                if (!configuration.isJmxEnabled()) {
                    System.out.println("DevTomcat: JMX not enabled - consider enabling for professional monitoring");
                }

                if (!configuration.isHotDeploymentEnabled()) {
                    System.out.println("DevTomcat: Hot deployment not enabled - enabling for development productivity");
                    configuration.setHotDeploymentEnabled(true);
                }

                if (configuration.getEnvironmentVariables().isEmpty()) {
                    System.out.println("DevTomcat: No environment variables - applying professional defaults");
                    setupDevelopmentEnvironmentVariables(configuration);
                }

                System.out.println("DevTomcat: Professional configuration validation completed");

            } catch (Exception e) {
                System.err.println("DevTomcat: Configuration validation error: " + e.getMessage());
            }
        }

        /**
         * Get professional configuration summary for diagnostics
         */
        public String getConfigurationSummary(TomcatRunConfiguration configuration) {
            StringBuilder summary = new StringBuilder();
            summary.append("DevTomcat Professional Configuration:\n");
            summary.append("- Name: ").append(configuration.getName()).append("\n");
            summary.append("- JMX: ").append(configuration.isJmxEnabled() ?
                    "Enabled(" + configuration.getJmxPort() + ")" : "Disabled").append("\n");
            summary.append("- Hot Deploy: ").append(configuration.isHotDeploymentEnabled() ? "Enabled" : "Disabled").append("\n");
            summary.append("- Server: ").append(configuration.getTomcatInfo() != null ?
                    configuration.getTomcatInfo().getName() : "Not Selected").append("\n");
            summary.append("- Environment Variables: ").append(configuration.getEnvironmentVariables().size()).append("\n");

            return summary.toString();
        }

        /**
         * Check if configuration meets professional standards
         */
        public boolean meetsProfessionalStandards(TomcatRunConfiguration configuration) {
            return configuration.isHotDeploymentEnabled() &&
                    !configuration.getEnvironmentVariables().isEmpty() &&
                    configuration.getTomcatInfo() != null;
        }
    }
}