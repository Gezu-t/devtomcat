package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.*;

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
     * Check if a specific port is available for binding on both wildcard and localhost.
     * Checks both addresses because Tomcat's shutdown socket binds to {@code localhost}
     * which on macOS/IPv6 resolves to {@code ::1}, a different address family from
     * the wildcard {@code 0.0.0.0}.
     *
     * @param port the port to check
     * @return true if the port is available on all relevant addresses
     */
    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) return false;
        // Check wildcard address (0.0.0.0)
        if (!tryBind(port, null)) return false;
        // Check localhost (may resolve to ::1 on macOS/IPv6)
        if (!tryBind(port, "localhost")) return false;
        return true;
    }

    /**
     * Probes whether {@code port} is bindable on {@code address}.
     *
     * <p>The probe sets {@code SO_REUSEADDR} before binding. Without it, a port
     * that Tomcat just released sits in the OS's {@code TIME_WAIT} state for
     * 30–120 seconds — long enough that the user's next rerun would have
     * treated it as occupied and bumped to the next port. Combined with the
     * port writeback ({@code LaunchPortClaimer.writeBackResolvedPorts}),
     * that bump became permanent and the configured HTTP port ratcheted upward
     * every restart (8083 → 8084 → 8085 …) even when nothing else was actually
     * listening.
     *
     * <p>With {@code SO_REUSEADDR} the probe succeeds over a {@code TIME_WAIT}
     * socket (which Tomcat's own bind will also succeed through) but still
     * fails if another process is <em>actively listening</em> — which is the
     * real conflict we want to detect. Net effect: the probe stops reporting
     * false positives on just-released ports while remaining strict about
     * genuine contention.
     */
    private static boolean tryBind(int port, String address) {
        try {
            InetAddress addr = address != null ? InetAddress.getByName(address) : null;
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(addr, port), 1);
                return true;
            }
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

    /**
     * Result of port conflict resolution, containing the resolved config and
     * descriptions of what was changed.
     */
    public static final class PortResolution {
        private final PortConfig resolvedConfig;
        private final List<String> changes;

        PortResolution(@NotNull PortConfig resolvedConfig, @NotNull List<String> changes) {
            this.resolvedConfig = resolvedConfig;
            this.changes = changes;
        }

        @NotNull public PortConfig getResolvedConfig() { return resolvedConfig; }
        @NotNull public List<String> getChanges() { return changes; }
        public boolean hasChanges() { return !changes.isEmpty(); }
    }

    /**
     * Detects port conflicts and auto-resolves them by finding next available ports.
     * Returns a resolved PortConfig that is safe to use for launch, plus a log
     * of what was changed so the caller can inform the user.
     *
     * <p>Resolution priority (first service keeps its port):
     * HTTP &rarr; Shutdown &rarr; HTTPS &rarr; JMX &rarr; AJP
     *
     * @param portConfig the original port configuration
     * @return resolution result with conflict-free ports and change descriptions
     */
    @NotNull
    public static PortResolution resolveConflicts(@NotNull PortConfig portConfig) {
        PortConfig resolved = portConfig.clone();
        List<String> changes = new ArrayList<>();
        Set<Integer> allocated = new LinkedHashSet<>();

        // Pre-collect every peer's preferred port so a service being resolved
        // first does not displace a later peer by grabbing the peer's configured
        // value as its own alternate. Peers are removed from this set as they
        // are processed (their port is either kept or the slot freed for reuse).
        Set<Integer> peerPreferred = new LinkedHashSet<>();
        peerPreferred.add(resolved.getHttp());
        peerPreferred.add(resolved.getShutdown());
        if (resolved.isHttpsEnabled()) peerPreferred.add(resolved.getHttps());
        if (resolved.isJmxEnabled()) peerPreferred.add(resolved.getJmx());
        if (resolved.isAjpEnabled()) peerPreferred.add(resolved.getAjp());

        resolved.setHttp(resolvePort("HTTP", resolved.getHttp(), allocated, peerPreferred, changes));
        resolved.setShutdown(resolvePort("Shutdown", resolved.getShutdown(), allocated, peerPreferred, changes));
        if (resolved.isHttpsEnabled()) {
            resolved.setHttps(resolvePort("HTTPS", resolved.getHttps(), allocated, peerPreferred, changes));
        }
        if (resolved.isJmxEnabled()) {
            resolved.setJmx(resolvePort("JMX", resolved.getJmx(), allocated, peerPreferred, changes));
        }
        if (resolved.isAjpEnabled()) {
            resolved.setAjp(resolvePort("AJP", resolved.getAjp(), allocated, peerPreferred, changes));
        }

        return new PortResolution(resolved, changes);
    }

    /**
     * Resolves port conflicts including the JDWP debug port.
     * The debug port is checked for external conflicts and internal collisions
     * with the Tomcat service ports. Returns the resolved debug port.
     *
     * @param portConfig the Tomcat port configuration
     * @param debugPort  the configured JDWP debug port (e.g. 5005)
     * @return resolution result; call {@link DebugPortResolution#getDebugPort()} for the resolved port
     */
    @NotNull
    public static DebugPortResolution resolveConflictsWithDebug(@NotNull PortConfig portConfig, int debugPort) {
        PortResolution baseResolution = resolveConflicts(portConfig);
        PortConfig resolved = baseResolution.getResolvedConfig();
        List<String> changes = new ArrayList<>(baseResolution.getChanges());

        // Collect all allocated Tomcat ports so the debug port doesn't collide
        Set<Integer> allocated = new LinkedHashSet<>();
        allocated.add(resolved.getHttp());
        allocated.add(resolved.getShutdown());
        if (resolved.isHttpsEnabled()) allocated.add(resolved.getHttps());
        if (resolved.isJmxEnabled()) allocated.add(resolved.getJmx());
        if (resolved.isAjpEnabled()) allocated.add(resolved.getAjp());

        // Debug port is the last service resolved; no future peers to reserve.
        int resolvedDebugPort = resolvePort("Debug (JDWP)", debugPort, allocated, java.util.Collections.emptySet(), changes);

        return new DebugPortResolution(new PortResolution(resolved, changes), resolvedDebugPort);
    }

    /**
     * Resolution result that includes both Tomcat ports and the debug port.
     */
    public static final class DebugPortResolution {
        private final PortResolution base;
        private final int debugPort;

        DebugPortResolution(@NotNull PortResolution base, int debugPort) {
            this.base = base;
            this.debugPort = debugPort;
        }

        @NotNull public PortConfig getResolvedConfig() { return base.getResolvedConfig(); }
        @NotNull public List<String> getChanges() { return base.getChanges(); }
        public boolean hasChanges() { return base.hasChanges(); }
        public int getDebugPort() { return debugPort; }
    }

    /**
     * Resolves a single port: checks for internal conflicts (duplicate among our own services)
     * and external conflicts (port in use by another process).
     *
     * <p>The {@code peerPreferred} set carries the configured ports of services that have
     * not yet been resolved; the search avoids them so an early-resolved service does not
     * grab a peer's preferred port as its own alternate. The current service's preferred
     * port is removed from this set on entry so it is not treated as its own peer.
     */
    private static int resolvePort(@NotNull String serviceName, int port,
                                   @NotNull Set<Integer> allocated,
                                   @NotNull Set<Integer> peerPreferred,
                                   @NotNull List<String> changes) {
        // This service's own preferred value is no longer a peer reservation.
        peerPreferred.remove(port);

        boolean internalConflict = !allocated.add(port);
        boolean externalConflict = !isPortAvailable(port);

        if (internalConflict || externalConflict) {
            String reason = internalConflict
                    ? "conflicts with another configured service"
                    : "is in use by another process";
            int next = findNextAvailableExcluding(port, allocated, peerPreferred);
            if (next > 0) {
                if (!internalConflict) {
                    // External conflict only: this service's port was added by allocated.add(port)
                    // above but is being replaced, so remove it. Don't remove on internal conflict;
                    // the port belongs to a previously resolved service and must stay reserved.
                    allocated.remove(port);
                }
                allocated.add(next);
                changes.add(serviceName + " port " + port + " " + reason + ", resolved to " + next);
                LOG.info("Port auto-resolved: " + serviceName + " " + port + " -> " + next);
                return next;
            }
            // If no port found, keep original and let Tomcat fail with clear error
            changes.add(serviceName + " port " + port + " " + reason + ", no alternative found");
            LOG.warn("Port conflict unresolved: " + serviceName + " port " + port);
        }
        return port;
    }

    /**
     * Finds the next port in the [startPort+1, startPort+100] range that is OS-available,
     * not in the {@code allocated} set, and not in the {@code peerPreferred} set.
     * Skipping peer-preferred ports prevents the first-resolved service from displacing
     * a later peer by claiming the peer's configured value as its own alternate.
     */
    private static int findNextAvailableExcluding(int startPort,
                                                  @NotNull Set<Integer> allocated,
                                                  @NotNull Set<Integer> peerPreferred) {
        for (int port = startPort + 1; port <= Math.min(startPort + 100, 65535); port++) {
            if (allocated.contains(port)) continue;
            if (peerPreferred.contains(port)) continue;
            if (isPortAvailable(port)) return port;
        }
        return -1;
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
