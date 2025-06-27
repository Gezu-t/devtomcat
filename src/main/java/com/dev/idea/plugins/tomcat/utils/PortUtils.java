package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Port Management Utility for DevTomcat
 * Handles port conflict detection and resolution
 *
 * @author Gezahegn Lemma (Gezu)
 * @version 1.0
 */
public class PortUtils {

    // Common ports that might conflict
    private static final int[] COMMON_PORTS = {8080, 8081, 8082, 8083, 8084, 8085};
    private static final int[] ADMIN_PORTS = {8005, 8006, 8007, 8008, 8009, 8010};
    private static final int[] JMX_PORTS = {1099, 1098, 1097, 1096, 1095, 1094};

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
     * Find the next available port starting from the given port
     */
    public static int findAvailablePort(int startPort) {
        for (int port = startPort; port <= startPort + 100; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1; // No available port found
    }

    /**
     * Get suggested available HTTP ports
     */
    public static List<Integer> getAvailableHttpPorts() {
        List<Integer> availablePorts = new ArrayList<>();
        for (int port : COMMON_PORTS) {
            if (isPortAvailable(port)) {
                availablePorts.add(port);
            }
        }
        return availablePorts;
    }

    /**
     * Get suggested available admin ports
     */
    public static List<Integer> getAvailableAdminPorts() {
        List<Integer> availablePorts = new ArrayList<>();
        for (int port : ADMIN_PORTS) {
            if (isPortAvailable(port)) {
                availablePorts.add(port);
            }
        }
        return availablePorts;
    }

    /**
     * Get suggested available JMX ports
     */
    public static List<Integer> getAvailableJmxPorts() {
        List<Integer> availablePorts = new ArrayList<>();
        for (int port : JMX_PORTS) {
            if (isPortAvailable(port)) {
                availablePorts.add(port);
            }
        }
        return availablePorts;
    }

    /**
     * Validate and suggest alternative ports if conflicts exist
     */
    public static PortValidationResult validatePorts(int httpPort, int adminPort, int jmxPort) {
        List<String> conflicts = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        // Check HTTP port
        if (!isPortAvailable(httpPort)) {
            conflicts.add("HTTP port " + httpPort + " is already in use");
            int alternativeHttp = findAvailablePort(httpPort + 1);
            if (alternativeHttp != -1) {
                suggestions.add("Use HTTP port " + alternativeHttp + " instead");
            }
        }

        // Check admin port
        if (!isPortAvailable(adminPort)) {
            conflicts.add("Admin port " + adminPort + " is already in use");
            int alternativeAdmin = findAvailablePort(adminPort + 1);
            if (alternativeAdmin != -1) {
                suggestions.add("Use admin port " + alternativeAdmin + " instead");
            }
        }

        // Check JMX port
        if (jmxPort > 0 && !isPortAvailable(jmxPort)) {
            conflicts.add("JMX port " + jmxPort + " is already in use");
            int alternativeJmx = findAvailablePort(jmxPort + 1);
            if (alternativeJmx != -1) {
                suggestions.add("Use JMX port " + alternativeJmx + " instead");
            }
        }

        return new PortValidationResult(conflicts, suggestions);
    }

    /**
     * Auto-fix port conflicts by finding alternatives
     */
    public static PortConfiguration autoFixPorts(int httpPort, int adminPort, int jmxPort) {
        int fixedHttpPort = isPortAvailable(httpPort) ? httpPort : findAvailablePort(httpPort + 1);
        int fixedAdminPort = isPortAvailable(adminPort) ? adminPort : findAvailablePort(adminPort + 1);
        int fixedJmxPort = (jmxPort > 0 && !isPortAvailable(jmxPort)) ? findAvailablePort(jmxPort + 1) : jmxPort;

        return new PortConfiguration(fixedHttpPort, fixedAdminPort, fixedJmxPort);
    }

    /**
     * Show port conflict resolution dialog
     */
    public static boolean showPortConflictDialog(@NotNull String conflictMessage, @NotNull String suggestions) {
        String message = "Port Conflict Detected:\n\n" + conflictMessage + "\n\nSuggestions:\n" + suggestions +
                "\n\nWould you like to automatically use alternative ports?";

        int result = Messages.showYesNoDialog(
                message,
                "Port Conflict",
                "Auto-Fix Ports",
                "Cancel",
                Messages.getWarningIcon()
        );

        return result == Messages.YES;
    }

    /**
     * Get process using a specific port (macOS/Linux)
     */
    public static String getProcessUsingPort(int port) {
        try {
            Process process = Runtime.getRuntime().exec("lsof -i :" + port);
            // Parse output to get process info
            return "Process using port " + port; // Simplified for now
        } catch (IOException e) {
            return "Unknown process";
        }
    }

    /**
     * Port validation result
     */
    public static class PortValidationResult {
        private final List<String> conflicts;
        private final List<String> suggestions;

        public PortValidationResult(List<String> conflicts, List<String> suggestions) {
            this.conflicts = conflicts;
            this.suggestions = suggestions;
        }

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        public List<String> getConflicts() {
            return conflicts;
        }

        public List<String> getSuggestions() {
            return suggestions;
        }

        public String getConflictMessage() {
            return String.join("\n", conflicts);
        }

        public String getSuggestionMessage() {
            return String.join("\n", suggestions);
        }
    }

    /**
     * Port configuration result
     */
    public static class PortConfiguration {
        private final int httpPort;
        private final int adminPort;
        private final int jmxPort;

        public PortConfiguration(int httpPort, int adminPort, int jmxPort) {
            this.httpPort = httpPort;
            this.adminPort = adminPort;
            this.jmxPort = jmxPort;
        }

        public int getHttpPort() { return httpPort; }
        public int getAdminPort() { return adminPort; }
        public int getJmxPort() { return jmxPort; }
    }
}