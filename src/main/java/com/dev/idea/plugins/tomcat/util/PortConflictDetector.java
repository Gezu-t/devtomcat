package com.dev.idea.plugins.tomcat.util;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-launch port conflict detector.
 *
 * Checks all configured ports before Tomcat startup and provides
 * clear, actionable messages about which ports are in use.
 * This is a DevTomcat-exclusive feature — IntelliJ Ultimate only
 * shows a cryptic bind error after launch fails.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class PortConflictDetector {

    private static final Logger LOG = Logger.getInstance(PortConflictDetector.class);

    private PortConflictDetector() {}

    /**
     * Represents a single port conflict with details.
     */
    public static final class PortConflict {
        private final String serviceName;
        private final int port;
        private final String suggestion;

        public PortConflict(@NotNull String serviceName, int port, @NotNull String suggestion) {
            this.serviceName = serviceName;
            this.port = port;
            this.suggestion = suggestion;
        }

        public @NotNull String getServiceName() { return serviceName; }
        public int getPort() { return port; }
        public @NotNull String getSuggestion() { return suggestion; }

        @Override
        public String toString() {
            return serviceName + " port " + port + " is already in use. " + suggestion;
        }
    }

    /**
     * Check all configured ports for conflicts before launch.
     *
     * @param portConfig the port configuration to check
     * @return list of detected conflicts (empty if no conflicts)
     */
    @NotNull
    public static List<PortConflict> detectConflicts(@NotNull PortConfig portConfig) {
        List<PortConflict> conflicts = new ArrayList<>();

        // Collect all active ports with their service names
        Map<String, Integer> portsToCheck = new LinkedHashMap<>();
        portsToCheck.put("HTTP", portConfig.getHttp());
        portsToCheck.put("Shutdown", portConfig.getShutdown());
        if (portConfig.isHttpsEnabled()) {
            portsToCheck.put("HTTPS", portConfig.getHttps());
        }
        if (portConfig.isJmxEnabled()) {
            portsToCheck.put("JMX", portConfig.getJmx());
        }
        if (portConfig.isAjpEnabled()) {
            portsToCheck.put("AJP", portConfig.getAjp());
        }

        for (Map.Entry<String, Integer> entry : portsToCheck.entrySet()) {
            String service = entry.getKey();
            int port = entry.getValue();

            if (!isPortAvailable(port)) {
                String suggestion = getSuggestion(service, port);
                conflicts.add(new PortConflict(service, port, suggestion));
                LOG.info("Port conflict detected: " + service + " port " + port);
            }
        }

        return conflicts;
    }

    /**
     * Check if a specific port is available for binding.
     *
     * @param port the port to check
     * @return true if the port is available
     */
    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) return false;
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Find the next available port starting from the given port.
     *
     * @param startPort the port to start searching from
     * @return the next available port, or -1 if none found within 100 ports
     */
    public static int findNextAvailable(int startPort) {
        for (int port = startPort + 1; port <= Math.min(startPort + 100, 65535); port++) {
            if (isPortAvailable(port)) return port;
        }
        return -1;
    }

    /**
     * Format all conflicts into a user-friendly notification message.
     *
     * @param conflicts the list of port conflicts
     * @return formatted message suitable for display in a notification dialog
     */
    @NotNull
    public static String formatConflictMessage(@NotNull List<PortConflict> conflicts) {
        if (conflicts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Port conflict").append(conflicts.size() > 1 ? "s" : "").append(" detected:\n\n");
        for (PortConflict conflict : conflicts) {
            sb.append("  • ").append(conflict.getServiceName())
                    .append(" port ").append(conflict.getPort())
                    .append(" is in use\n");
            sb.append("    → ").append(conflict.getSuggestion()).append("\n\n");
        }
        sb.append("Would you like to continue anyway?");
        return sb.toString();
    }

    /**
     * Check whether any conflicts exist (convenience method).
     *
     * @param portConfig the port configuration to check
     * @return true if there are any port conflicts
     */
    public static boolean hasConflicts(@NotNull PortConfig portConfig) {
        return !detectConflicts(portConfig).isEmpty();
    }

    private static String getSuggestion(String service, int port) {
        int nextAvailable = findNextAvailable(port);
        String base = "Another process is using port " + port + ".";
        if (nextAvailable > 0) {
            return base + " Try port " + nextAvailable + " instead.";
        }
        return base + " Stop the other process or choose a different port.";
    }
}
