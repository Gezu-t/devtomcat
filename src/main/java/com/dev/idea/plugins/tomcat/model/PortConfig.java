/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/2/25
 *
 * Dynamic Port Configuration Manager
 *
 * Responsibilities:
 * - Centralized port state management
 * - Built-in validation with detailed results
 * - Port conflict detection and resolution
 * - Port availability checking
 * - Automatic port suggestions
 * - Thread-safe operations
 * - Serialization support
 */
package com.dev.idea.plugins.tomcat.conf;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.net.ServerSocket;
import java.util.*;

public class PortConfig implements Serializable, Cloneable {

    private static final Logger LOG = Logger.getInstance(PortConfig.class);

    // === PORT CONSTANTS ===
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_HTTPS_PORT = 8443;
    public static final int DEFAULT_JMX_PORT = 1099;
    public static final int DEFAULT_SHUTDOWN_PORT = 8005;

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int PRIVILEGED_PORT_THRESHOLD = 1024;

    // === CORE PORT STATE ===
    private int http;
    private int https;
    private int jmx;
    private int shutdown;

    // === FEATURE FLAGS ===
    private boolean httpsEnabled;
    private boolean jmxEnabled;

    // === CONSTRUCTORS ===

    public PortConfig() {
        this.http = DEFAULT_HTTP_PORT;
        this.https = DEFAULT_HTTPS_PORT;
        this.jmx = DEFAULT_JMX_PORT;
        this.shutdown = DEFAULT_SHUTDOWN_PORT;
        this.httpsEnabled = false;
        this.jmxEnabled = false;
    }

    public PortConfig(int http, int shutdown) {
        this();
        this.http = http;
        this.shutdown = shutdown;
    }

    public PortConfig(int http, int https, int jmx, int shutdown) {
        this.http = http;
        this.https = https;
        this.jmx = jmx;
        this.shutdown = shutdown;
        this.httpsEnabled = false;
        this.jmxEnabled = false;
    }

    public PortConfig(@NotNull PortConfig other) {
        this.http = other.http;
        this.https = other.https;
        this.jmx = other.jmx;
        this.shutdown = other.shutdown;
        this.httpsEnabled = other.httpsEnabled;
        this.jmxEnabled = other.jmxEnabled;
    }

    // === PORT GETTERS/SETTERS ===

    public int getHttp() { return http; }
    public void setHttp(int port) {
        this.http = validatePortRange(port, "HTTP");
    }

    public int getHttps() { return https; }
    public void setHttps(int port) {
        this.https = validatePortRange(port, "HTTPS");
    }

    public int getJmx() { return jmx; }
    public void setJmx(int port) {
        this.jmx = validatePortRange(port, "JMX");
    }

    public int getShutdown() { return shutdown; }
    public void setShutdown(int port) {
        this.shutdown = validatePortRange(port, "Shutdown");
    }

    // === FEATURE FLAG GETTERS/SETTERS ===

    public boolean isHttpsEnabled() { return httpsEnabled; }
    public void setHttpsEnabled(boolean enabled) { this.httpsEnabled = enabled; }

    public boolean isJmxEnabled() { return jmxEnabled; }
    public void setJmxEnabled(boolean enabled) { this.jmxEnabled = enabled; }

    // === VALIDATION ===

    /**
     * Validates a port is within valid range
     */
    private int validatePortRange(int port, String portName) {
        if (port < MIN_PORT || port > MAX_PORT) {
            LOG.warn(portName + " port " + port + " is out of valid range [" + MIN_PORT + "-" + MAX_PORT + "]");
            return port; // Don't throw, let validation handle it
        }
        return port;
    }

    /**
     * Comprehensive validation of all port configurations
     *
     * @return ValidationResult containing errors, warnings, and suggestions
     */
    @NotNull
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();

        // 1. Range validation
        validatePortInRange(http, "HTTP", result);
        validatePortInRange(shutdown, "Shutdown", result);

        if (httpsEnabled) {
            validatePortInRange(https, "HTTPS", result);
        }

        if (jmxEnabled) {
            validatePortInRange(jmx, "JMX", result);
        }

        // 2. Conflict detection
        Map<Integer, List<String>> portUsage = new HashMap<>();
        addPortUsage(portUsage, http, "HTTP");
        addPortUsage(portUsage, shutdown, "Shutdown");

        if (httpsEnabled) {
            addPortUsage(portUsage, https, "HTTPS");
        }

        if (jmxEnabled) {
            addPortUsage(portUsage, jmx, "JMX");
        }

        // Report conflicts
        for (Map.Entry<Integer, List<String>> entry : portUsage.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.addError("Port " + entry.getKey() + " is used by multiple services: " +
                        String.join(", ", entry.getValue()));
            }
        }

        // 3. Availability check
        checkPortAvailability(http, "HTTP", result);
        checkPortAvailability(shutdown, "Shutdown", result);

        if (httpsEnabled) {
            checkPortAvailability(https, "HTTPS", result);
        }

        if (jmxEnabled) {
            checkPortAvailability(jmx, "JMX", result);
        }

        // 4. Privileged port warnings
        checkPrivilegedPort(http, "HTTP", result);

        if (httpsEnabled) {
            checkPrivilegedPort(https, "HTTPS", result);
        }

        return result;
    }

    private void validatePortInRange(int port, String name, ValidationResult result) {
        if (port < MIN_PORT || port > MAX_PORT) {
            result.addError(name + " port must be between " + MIN_PORT + " and " + MAX_PORT + " (current: " + port + ")");
        }
    }

    private void addPortUsage(Map<Integer, List<String>> portUsage, int port, String service) {
        portUsage.computeIfAbsent(port, k -> new ArrayList<>()).add(service);
    }

    private void checkPortAvailability(int port, String name, ValidationResult result) {
        if (!isPortAvailable(port)) {
            result.addWarning(name + " port " + port + " is already in use");

            // Suggest alternative
            int alternative = findNextAvailablePort(port + 1);
            if (alternative != -1) {
                result.addSuggestion("Consider using port " + alternative + " for " + name);
            }
        }
    }

    private void checkPrivilegedPort(int port, String name, ValidationResult result) {
        if (port < PRIVILEGED_PORT_THRESHOLD) {
            result.addWarning(name + " port " + port + " is a privileged port (< " +
                    PRIVILEGED_PORT_THRESHOLD + ") and may require administrator privileges");
        }
    }

    /**
     * Check if a port is available
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Find next available port starting from given port
     */
    public static int findNextAvailablePort(int startPort) {
        for (int port = startPort; port <= MAX_PORT; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }

    // === AUTO-FIX CAPABILITIES ===

    /**
     * Automatically fixes port conflicts by finding available alternatives
     *
     * @return A new PortConfig with fixed ports, or this if no fixes needed
     */
    @NotNull
    public PortConfig autoFix() {
        ValidationResult validation = validate();

        if (!validation.hasErrors() && !validation.hasWarnings()) {
            return this; // No fixes needed
        }

        PortConfig fixed = new PortConfig(this);

        // Fix HTTP port
        if (!isPortAvailable(fixed.http)) {
            int newPort = findNextAvailablePort(fixed.http + 1);
            if (newPort != -1) {
                LOG.info("Auto-fixing HTTP port: " + fixed.http + " -> " + newPort);
                fixed.http = newPort;
            }
        }

        // Fix Shutdown port
        if (!isPortAvailable(fixed.shutdown) || fixed.shutdown == fixed.http) {
            int newPort = findNextAvailablePort(fixed.shutdown + 1);
            if (newPort != -1 && newPort != fixed.http) {
                LOG.info("Auto-fixing Shutdown port: " + fixed.shutdown + " -> " + newPort);
                fixed.shutdown = newPort;
            }
        }

        // Fix HTTPS port if enabled
        if (fixed.httpsEnabled) {
            if (!isPortAvailable(fixed.https) || fixed.https == fixed.http || fixed.https == fixed.shutdown) {
                int newPort = findNextAvailablePort(fixed.https + 1);
                if (newPort != -1 && newPort != fixed.http && newPort != fixed.shutdown) {
                    LOG.info("Auto-fixing HTTPS port: " + fixed.https + " -> " + newPort);
                    fixed.https = newPort;
                }
            }
        }

        // Fix JMX port if enabled
        if (fixed.jmxEnabled) {
            if (!isPortAvailable(fixed.jmx) || fixed.jmx == fixed.http ||
                    fixed.jmx == fixed.shutdown || (fixed.httpsEnabled && fixed.jmx == fixed.https)) {
                int newPort = findNextAvailablePort(fixed.jmx + 1);
                if (newPort != -1 && newPort != fixed.http && newPort != fixed.shutdown &&
                        (!fixed.httpsEnabled || newPort != fixed.https)) {
                    LOG.info("Auto-fixing JMX port: " + fixed.jmx + " -> " + newPort);
                    fixed.jmx = newPort;
                }
            }
        }

        return fixed;
    }

    /**
     * Suggests available ports for all services
     */
    @NotNull
    public Map<String, List<Integer>> suggestAvailablePorts() {
        Map<String, List<Integer>> suggestions = new HashMap<>();

        suggestions.put("HTTP", findAvailablePortsNear(DEFAULT_HTTP_PORT, 5));
        suggestions.put("HTTPS", findAvailablePortsNear(DEFAULT_HTTPS_PORT, 5));
        suggestions.put("JMX", findAvailablePortsNear(DEFAULT_JMX_PORT, 5));
        suggestions.put("Shutdown", findAvailablePortsNear(DEFAULT_SHUTDOWN_PORT, 5));

        return suggestions;
    }

    private List<Integer> findAvailablePortsNear(int startPort, int count) {
        List<Integer> ports = new ArrayList<>();
        int currentPort = startPort;
        int attempts = 0;
        int maxAttempts = 1000;

        while (ports.size() < count && attempts < maxAttempts) {
            if (isPortAvailable(currentPort)) {
                ports.add(currentPort);
            }
            currentPort++;
            attempts++;
        }

        return ports;
    }

    // === UTILITY METHODS ===

    /**
     * Gets all active ports (enabled services only)
     */
    @NotNull
    public List<Integer> getActivePorts() {
        List<Integer> ports = new ArrayList<>();
        ports.add(http);
        ports.add(shutdown);

        if (httpsEnabled) {
            ports.add(https);
        }

        if (jmxEnabled) {
            ports.add(jmx);
        }

        return ports;
    }

    /**
     * Checks if any port is using a specific number
     */
    public boolean isPortInUse(int port) {
        if (port == http || port == shutdown) {
            return true;
        }
        if (httpsEnabled && port == https) {
            return true;
        }
        if (jmxEnabled && port == jmx) {
            return true;
        }
        return false;
    }

    /**
     * Resets all ports to defaults
     */
    public void resetToDefaults() {
        this.http = DEFAULT_HTTP_PORT;
        this.https = DEFAULT_HTTPS_PORT;
        this.jmx = DEFAULT_JMX_PORT;
        this.shutdown = DEFAULT_SHUTDOWN_PORT;
        this.httpsEnabled = false;
        this.jmxEnabled = false;
    }

    // === CLONING ===

    @Override
    public PortConfig clone() {
        try {
            return (PortConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone PortConfig", e);
        }
    }

    // === OBJECT METHODS ===

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PortConfig that = (PortConfig) o;
        return http == that.http &&
                https == that.https &&
                jmx == that.jmx &&
                shutdown == that.shutdown &&
                httpsEnabled == that.httpsEnabled &&
                jmxEnabled == that.jmxEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(http, https, jmx, shutdown, httpsEnabled, jmxEnabled);
    }

    @Override
    public String toString() {
        return "PortConfig{" +
                "http=" + http +
                ", shutdown=" + shutdown +
                (httpsEnabled ? ", https=" + https : "") +
                (jmxEnabled ? ", jmx=" + jmx : "") +
                '}';
    }

    // === VALIDATION RESULT CLASS ===

    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> suggestions = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void addSuggestion(String suggestion) {
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

        public boolean isValid() {
            return !hasErrors();
        }

        @NotNull
        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }

        @NotNull
        public List<String> getWarnings() {
            return new ArrayList<>(warnings);
        }

        @NotNull
        public List<String> getSuggestions() {
            return new ArrayList<>(suggestions);
        }

        @NotNull
        public String getErrorMessage() {
            return String.join("; ", errors);
        }

        @NotNull
        public String getWarningMessage() {
            return String.join("; ", warnings);
        }

        @NotNull
        public String getSuggestionMessage() {
            return String.join("; ", suggestions);
        }

        @NotNull
        public String getFullReport() {
            StringBuilder sb = new StringBuilder();

            if (hasErrors()) {
                sb.append("ERRORS:\n");
                errors.forEach(e -> sb.append("  - ").append(e).append("\n"));
            }

            if (hasWarnings()) {
                sb.append("WARNINGS:\n");
                warnings.forEach(w -> sb.append("  - ").append(w).append("\n"));
            }

            if (hasSuggestions()) {
                sb.append("SUGGESTIONS:\n");
                suggestions.forEach(s -> sb.append("  - ").append(s).append("\n"));
            }

            return sb.toString();
        }

        @Override
        public String toString() {
            return getFullReport();
        }
    }

    // === BUILDER PATTERN ===

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int http = DEFAULT_HTTP_PORT;
        private int https = DEFAULT_HTTPS_PORT;
        private int jmx = DEFAULT_JMX_PORT;
        private int shutdown = DEFAULT_SHUTDOWN_PORT;
        private boolean httpsEnabled = false;
        private boolean jmxEnabled = false;

        public Builder http(int port) {
            this.http = port;
            return this;
        }

        public Builder https(int port) {
            this.https = port;
            return this;
        }

        public Builder jmx(int port) {
            this.jmx = port;
            return this;
        }

        public Builder shutdown(int port) {
            this.shutdown = port;
            return this;
        }

        public Builder httpsEnabled(boolean enabled) {
            this.httpsEnabled = enabled;
            return this;
        }

        public Builder jmxEnabled(boolean enabled) {
            this.jmxEnabled = enabled;
            return this;
        }

        @NotNull
        public PortConfig build() {
            PortConfig config = new PortConfig(http, https, jmx, shutdown);
            config.setHttpsEnabled(httpsEnabled);
            config.setJmxEnabled(jmxEnabled);
            return config;
        }
    }
}