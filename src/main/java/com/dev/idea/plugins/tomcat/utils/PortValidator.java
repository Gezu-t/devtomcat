package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import com.intellij.openapi.diagnostic.Logger;

public class PortValidator {

    private static final Logger LOG = Logger.getInstance(PortValidator.class);

    @NotNull
    public static ValidationResult validate(@NotNull PortConfiguration ports) {
        ValidationResult result = new ValidationResult();

        validatePortRange(ports.httpPort, "HTTP", true, result);
        validatePortRange(ports.shutdownPort, "Shutdown", true, result);
        validatePortRange(ports.httpsPort, "HTTPS", ports.httpsEnabled, result);
        validatePortRange(ports.jmxPort, "JMX", ports.jmxEnabled, result);
        validatePortRange(ports.ajpPort, "AJP", ports.ajpEnabled, result);

        if (result.hasErrors()) {
            return result;
        }

        validatePortConflicts(ports, result);
        validatePortAvailability(ports, result);

        return result;
    }

    private static void validatePortRange(@Nullable Integer port, @NotNull String portName,
                                          boolean required, @NotNull ValidationResult result) {
        if (port == null) {
            if (required) {
                result.addError(portName + " port is required");
            }
            return;
        }

        if (port < PortUtils.MIN_PORT || port > PortUtils.MAX_PORT) {
            result.addError(portName + " port must be between " + PortUtils.MIN_PORT + " and " + PortUtils.MAX_PORT);
        }
    }

    private static void validatePortConflicts(@NotNull PortConfiguration ports,
                                              @NotNull ValidationResult result) {
        Map<Integer, String> portMap = new LinkedHashMap<>();

        addPortToMap(portMap, ports.httpPort, "HTTP", result);
        addPortToMap(portMap, ports.shutdownPort, "Shutdown", result);

        if (ports.httpsEnabled) {
            addPortToMap(portMap, ports.httpsPort, "HTTPS", result);
        }

        if (ports.jmxEnabled) {
            addPortToMap(portMap, ports.jmxPort, "JMX", result);
        }

        if (ports.ajpEnabled) {
            addPortToMap(portMap, ports.ajpPort, "AJP", result);
        }
    }

    private static void addPortToMap(@NotNull Map<Integer, String> portMap, @Nullable Integer port,
                                     @NotNull String portName, @NotNull ValidationResult result) {
        if (port == null) {
            return;
        }

        if (portMap.containsKey(port)) {
            result.addError(portName + " and " + portMap.get(port) + " ports cannot be the same (" + port + ")");
        } else {
            portMap.put(port, portName);
        }
    }

    private static void validatePortAvailability(@NotNull PortConfiguration ports,
                                                 @NotNull ValidationResult result) {
        checkPortAvailable(ports.httpPort, "HTTP", result);
        checkPortAvailable(ports.shutdownPort, "Shutdown", result);

        if (ports.httpsEnabled) {
            checkPortAvailable(ports.httpsPort, "HTTPS", result);
        }

        if (ports.jmxEnabled) {
            checkPortAvailable(ports.jmxPort, "JMX", result);
        }

        if (ports.ajpEnabled) {
            checkPortAvailable(ports.ajpPort, "AJP", result);
        }
    }

    private static void checkPortAvailable(@Nullable Integer port, @NotNull String portName,
                                           @NotNull ValidationResult result) {
        if (port == null) {
            return;
        }

        if (!PortUtils.isAvailable(port)) {
            result.addWarning(portName + " port " + port + " is already in use");

            int alternative = PortUtils.findNextAvailable(port + 1);
            if (alternative != -1) {
                result.addSuggestion("Use " + portName + " port " + alternative + " instead");
            }
        }
    }

    public static void validateOrThrow(@NotNull PortConfiguration ports) throws ConfigurationException {
        ValidationResult result = validate(ports);

        if (result.hasErrors()) {
            throw new ConfigurationException(result.getErrorMessage());
        }

        if (result.hasWarnings()) {
            LOG.debug("Port warnings: " + result.getWarningMessage());
        }
    }

    public static class PortConfiguration {
        public final Integer httpPort;
        public final Integer shutdownPort;
        public final Integer httpsPort;
        public final boolean httpsEnabled;
        public final Integer jmxPort;
        public final boolean jmxEnabled;
        public final Integer ajpPort;
        public final boolean ajpEnabled;

        public PortConfiguration(@Nullable Integer httpPort,
                                 @Nullable Integer shutdownPort,
                                 @Nullable Integer httpsPort,
                                 boolean httpsEnabled,
                                 @Nullable Integer jmxPort,
                                 boolean jmxEnabled,
                                 @Nullable Integer ajpPort,
                                 boolean ajpEnabled) {
            this.httpPort = httpPort;
            this.shutdownPort = shutdownPort;
            this.httpsPort = httpsPort;
            this.httpsEnabled = httpsEnabled;
            this.jmxPort = jmxPort;
            this.jmxEnabled = jmxEnabled;
            this.ajpPort = ajpPort;
            this.ajpEnabled = ajpEnabled;
        }

        public static PortConfiguration from(@NotNull Object configuration) {
            try {
                Integer httpPort = (Integer) configuration.getClass().getMethod("getHttpPort").invoke(configuration);
                Integer shutdownPort = (Integer) configuration.getClass().getMethod("getShutdownPort").invoke(configuration);
                Integer httpsPort = (Integer) configuration.getClass().getMethod("getHttpsPort").invoke(configuration);
                boolean httpsEnabled = (Boolean) configuration.getClass().getMethod("isHttpsEnabled").invoke(configuration);
                Integer jmxPort = (Integer) configuration.getClass().getMethod("getJmxPort").invoke(configuration);
                boolean jmxEnabled = (Boolean) configuration.getClass().getMethod("isJmxEnabled").invoke(configuration);

                Integer ajpPort = null;
                boolean ajpEnabled = false;
                try {
                    Object portConfig = configuration.getClass().getMethod("getConfigData").invoke(configuration);
                    Object pc = portConfig.getClass().getMethod("getPortConfig").invoke(portConfig);
                    ajpEnabled = (Boolean) pc.getClass().getMethod("isAjpEnabled").invoke(pc);
                    if (ajpEnabled) {
                        ajpPort = (Integer) pc.getClass().getMethod("getAjp").invoke(pc);
                    }
                } catch (Exception ignored) {
                }

                return new PortConfiguration(httpPort, shutdownPort, httpsPort, httpsEnabled, jmxPort, jmxEnabled, ajpPort, ajpEnabled);
            } catch (Exception e) {
                throw new RuntimeException("Failed to extract port configuration", e);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Integer httpPort;
            private Integer shutdownPort;
            private Integer httpsPort;
            private boolean httpsEnabled;
            private Integer jmxPort;
            private boolean jmxEnabled;
            private Integer ajpPort;
            private boolean ajpEnabled;

            public Builder httpPort(Integer port) {
                this.httpPort = port;
                return this;
            }

            public Builder shutdownPort(Integer port) {
                this.shutdownPort = port;
                return this;
            }

            public Builder httpsPort(Integer port) {
                this.httpsPort = port;
                return this;
            }

            public Builder httpsEnabled(boolean enabled) {
                this.httpsEnabled = enabled;
                return this;
            }

            public Builder jmxPort(Integer port) {
                this.jmxPort = port;
                return this;
            }

            public Builder jmxEnabled(boolean enabled) {
                this.jmxEnabled = enabled;
                return this;
            }

            public Builder ajpPort(Integer port) {
                this.ajpPort = port;
                return this;
            }

            public Builder ajpEnabled(boolean enabled) {
                this.ajpEnabled = enabled;
                return this;
            }

            public PortConfiguration build() {
                return new PortConfiguration(httpPort, shutdownPort, httpsPort, httpsEnabled, jmxPort, jmxEnabled, ajpPort, ajpEnabled);
            }
        }
    }
}
