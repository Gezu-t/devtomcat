package com.dev.idea.plugins.tomcat.conf;

        import com.dev.idea.plugins.tomcat.model.PortConfig;
        import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
        import com.dev.idea.plugins.tomcat.model.ValidationResult;
        import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
        import com.dev.idea.plugins.tomcat.utils.PortValidator;
        import com.dev.idea.plugins.tomcat.utils.StringUtils;
        import com.intellij.execution.configurations.RuntimeConfigurationException;
        import com.intellij.openapi.diagnostic.Logger;
        import org.jetbrains.annotations.NotNull;

        import java.util.Objects;

        public final class TomcatConfigurationValidator {

            private static final Logger LOG = Logger.getInstance(TomcatConfigurationValidator.class);

            private TomcatConfigurationValidator() {}

            public static void validate(@NotNull TomcatRunConfiguration config) throws RuntimeConfigurationException {
                Objects.requireNonNull(config, "Configuration cannot be null");

                try {
                    LOG.debug("Validating configuration: %s", config.getName());

                    validateConfigurationName(config);
                    TomcatConfigurationData data = validateConfigurationData(config);
                    validateTomcatServer(data, config.getName());
                    validatePortConfiguration(data);
                    validateContextPath(data);

                    LOG.debug("Configuration validation passed: %s", config.getName());
                } catch (RuntimeConfigurationException e) {
                    LOG.debug("Validation failed for: " + config.getName() + " - " + e.getLocalizedMessage());
                    throw e;
                } catch (Exception e) {
                    LOG.error("Unexpected error during validation: " + config.getName(), e);
                    throw new RuntimeConfigurationException("Validation error: " + e.getLocalizedMessage(), e);
                }
            }

            private static void validateConfigurationName(@NotNull TomcatRunConfiguration config) throws RuntimeConfigurationException {
                if (StringUtils.isEmpty(config.getName())) {
                    config.setName("Tomcat");
                    LOG.debug("Configuration name was empty; defaulted to 'Tomcat'");
                }
            }

            @NotNull
            private static TomcatConfigurationData validateConfigurationData(@NotNull TomcatRunConfiguration config) throws RuntimeConfigurationException {
                TomcatConfigurationData data = config.getConfigData();
                if (data == null) {
                    throw new RuntimeConfigurationException("Configuration data is missing for: " + config.getName());
                }
                return data;
            }

            private static void validateTomcatServer(@NotNull TomcatConfigurationData data, @NotNull String configName) throws RuntimeConfigurationException {
                TomcatInfo tomcatInfo = data.getTomcatInfo();
                if (tomcatInfo == null) {
                    throw new RuntimeConfigurationException("No Tomcat server selected. Please configure a Tomcat instance.");
                }
                if (StringUtils.isEmpty(tomcatInfo.getName())) {
                    throw new RuntimeConfigurationException("Tomcat server name is empty");
                }
                if (StringUtils.isEmpty(tomcatInfo.getPath())) {
                    throw new RuntimeConfigurationException("Tomcat server path is not configured for: " + tomcatInfo.getName());
                }
                if (StringUtils.isEmpty(tomcatInfo.getVersion())) {
                    LOG.warn(String.format("Tomcat server version not set for: %s", tomcatInfo.getName()));
                }
            }

            private static void validatePortConfiguration(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
                PortConfig ports = data.getPortConfig();
                if (ports == null) {
                    throw new RuntimeConfigurationException("Port configuration is missing");
                }

                PortValidator.PortConfiguration portConfig = PortValidator.PortConfiguration.builder()
                        .httpPort(ports.getHttp())
                        .shutdownPort(ports.getShutdown())
                        .httpsPort(ports.getHttps())
                        .httpsEnabled(ports.isHttpsEnabled())
                        .jmxPort(ports.getJmx())
                        .jmxEnabled(ports.isJmxEnabled())
                        .build();

                ValidationResult result = PortValidator.validate(portConfig);
                if (result.hasErrors()) {
                    throw new RuntimeConfigurationException(result.getErrorMessage());
                }
            }

            private static void validateContextPath(@NotNull TomcatConfigurationData data) throws RuntimeConfigurationException {
                String contextPath = data.getContextPath();
                if (StringUtils.isEmpty(contextPath)) {
                    data.setContextPath("/");
                    return;
                }
                if (!contextPath.startsWith("/")) {
                    throw new RuntimeConfigurationException("Context path must start with '/': " + contextPath);
                }
                if (contextPath.contains(" ")) {
                    throw new RuntimeConfigurationException("Context path cannot contain spaces: " + contextPath);
                }
                if (contextPath.contains("\\")) {
                    throw new RuntimeConfigurationException("Context path cannot contain backslashes: " + contextPath);
                }
            }

        public static String getValidationError(@NotNull TomcatRunConfiguration config) {
            try {
                validate(config);
                return null;
            } catch (RuntimeConfigurationException e) {
                return e.getLocalizedMessage();
            }
        }
        }
