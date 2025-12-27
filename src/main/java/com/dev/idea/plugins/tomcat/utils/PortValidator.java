package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;

/**
 * Port Validator
 *
 * Centralized port validation utility for all Tomcat configuration operations.
 * Eliminates duplication across ServerConfigurationTab, TomcatConfigurationData, and PortUtils.
 *
 * @author Dev Tomcat Team
 */
public class PortValidator {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Validate a complete port configuration
     */
    @NotNull
    public static ValidationResult validate(@NotNull PortConfiguration ports) {
        ValidationResult result = new ValidationResult();

        // Range validation
        validatePortRange(ports.httpPort, "HTTP", true, result);
        validatePortRange(ports.shutdownPort, "Shutdown", true, result);
        validatePortRange(ports.httpsPort, "HTTPS", ports.httpsEnabled, result);
        validatePortRange(ports.jmxPort, "JMX", ports.jmxEnabled, result);
        validatePortRange(ports.ajpPort, "AJP", ports.ajpEnabled, result);

        // Stop if range validation failed
        if (result.hasErrors()) {
            return result;
        }

        // Conflict validation
        validatePortConflicts(ports, result);

        // Availability validation
        validatePortAvailability(ports, result);

        return result;
    }

    /**
     * Validate port range
     */
    private static void validatePortRange(@Nullable Integer port, @NotNull String portName,
                                          boolean required, @NotNull ValidationResult result) {
        if (port == null) {
            if (required) {
                result.addError(portName + " port is required");
            }
            return;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            result.addError(portName + " port must be between " + MIN_PORT + " and " + MAX_PORT);
        }
    }

    /**
     * Validate port conflicts (no two ports can be the same)
     */
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

    /**
     * Add port to map and detect conflicts
     */
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

    /**
     * Validate port availability
     */
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

    /**
     * Check if a specific port is available
     */
    private static void checkPortAvailable(@Nullable Integer port, @NotNull String portName,
                                           @NotNull ValidationResult result) {
        if (port == null) {
            return;
        }

        if (!isPortAvailable(port)) {
            result.addWarning(portName + " port " + port + " is already in use");

            int alternative = findNextAvailablePort(port + 1);
            if (alternative != -1) {
                result.addSuggestion("Use " + portName + " port " + alternative + " instead");
            }
        }
    }

    /**
     * Check if a port is available
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Find next available port starting from a given port
     */
    public static int findNextAvailablePort(int startPort) {
        for (int port = startPort; port <= MAX_PORT; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Quick validation for UI - throws ConfigurationException
     */
    public static void validateOrThrow(@NotNull PortConfiguration ports) throws ConfigurationException {
        ValidationResult result = validate(ports);

        if (result.hasErrors()) {
            throw new ConfigurationException(result.getErrorMessage());
        }

        // Warnings don't block, but could be logged
        if (result.hasWarnings()) {
            System.out.println("Port warnings: " + result.getWarningMessage());
        }
    }

    /**
     * Port Configuration Container
     */
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

        /**
         * Create from TomcatRunConfiguration
         */
        public static PortConfiguration from(@NotNull Object configuration) {
            try {
                Integer httpPort = (Integer) configuration.getClass().getMethod("getHttpPort").invoke(configuration);
                Integer shutdownPort = (Integer) configuration.getClass().getMethod("getShutdownPort").invoke(configuration);
                Integer httpsPort = (Integer) configuration.getClass().getMethod("getHttpsPort").invoke(configuration);
                boolean httpsEnabled = (Boolean) configuration.getClass().getMethod("isHttpsEnabled").invoke(configuration);
                Integer jmxPort = (Integer) configuration.getClass().getMethod("getJmxPort").invoke(configuration);
                boolean jmxEnabled = (Boolean) configuration.getClass().getMethod("isJmxEnabled").invoke(configuration);

                // Try to get AJP port (may not exist in older configs)
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
                    // AJP not supported in this configuration
                }

                return new PortConfiguration(httpPort, shutdownPort, httpsPort, httpsEnabled, jmxPort, jmxEnabled, ajpPort, ajpEnabled);
            } catch (Exception e) {
                throw new RuntimeException("Failed to extract port configuration", e);
            }
        }

        /**
         * Builder for easier construction
         */
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

    /**
     * Validation Result Container
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> suggestions = new ArrayList<>();

        public void addError(@NotNull String error) {
            errors.add(error);
        }

        public void addWarning(@NotNull String warning) {
            warnings.add(warning);
        }

        public void addSuggestion(@NotNull String suggestion) {
            suggestions.add(suggestion);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public boolean hasSuggestions() {
            return !suggestions.isEmpty();
        }

        @NotNull
        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        @NotNull
        public List<String> getWarnings() {
            return Collections.unmodifiableList(warnings);
        }

        @NotNull
        public List<String> getSuggestions() {
            return Collections.unmodifiableList(suggestions);
        }

        @NotNull
        public String getErrorMessage() {
            return String.join("\n", errors);
        }

        @NotNull
        public String getWarningMessage() {
            return String.join("\n", warnings);
        }

        @NotNull
        public String getSuggestionMessage() {
            return String.join("\n", suggestions);
        }

        public boolean isValid() {
            return !hasErrors();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (hasErrors()) {
                sb.append("Errors:\n").append(getErrorMessage()).append("\n");
            }
            if (hasWarnings()) {
                sb.append("Warnings:\n").append(getWarningMessage()).append("\n");
            }
            if (hasSuggestions()) {
                sb.append("Suggestions:\n").append(getSuggestionMessage());
            }
            return sb.toString();
        }
    }
}