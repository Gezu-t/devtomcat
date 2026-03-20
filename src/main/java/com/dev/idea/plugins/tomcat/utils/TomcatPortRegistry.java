package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level port registry for DevTomcat instances.
 *
 * <p>Closes the race window that exists when multiple Tomcat run configurations
 * start simultaneously. A simple OS availability check ("is port X free?") is
 * not atomic: two instances can both observe a port as free before either JVM
 * has bound to it, so both end up using the same port.
 *
 * <p>This registry performs an atomic claim-or-skip operation before the
 * process starts.  The OS availability check and the in-registry reservation
 * happen together under the registry lock, so two concurrent launchers can
 * never be assigned the same port.
 *
 * <p>Usage:
 * <pre>
 *   // At pre-launch time (before the process starts):
 *   int resolvedPort = registry.claimPort(requestedPort, configName);
 *
 *   // At process termination:
 *   registry.releaseAllFor(configName);
 * </pre>
 *
 * @author Gezahegn Lemma (Gezu)
 */
@Service(Service.Level.APP)
public final class TomcatPortRegistry {

    private static final Logger LOG = Logger.getInstance(TomcatPortRegistry.class);
    private static final int MAX_SEARCH_RANGE = 100;

    /**
     * Maps claimed port → configuration name that owns it.
     * ConcurrentHashMap for visibility, but all claim/release mutations
     * are guarded by {@code synchronized(this)} for atomicity.
     */
    private final ConcurrentHashMap<Integer, String> claimedPorts = new ConcurrentHashMap<>();

    public static TomcatPortRegistry getInstance() {
        return ApplicationManager.getApplication().getService(TomcatPortRegistry.class);
    }

    /**
     * Atomically claims a port for the given configuration.
     *
     * <p>Checks both the in-registry state (claimed by another DevTomcat instance)
     * and OS availability (in use by any other process). If the requested port
     * fails either check, searches upward for the next port that passes both,
     * then claims it atomically before returning.
     *
     * @param requestedPort the port this configuration wants to use
     * @param configName    the run configuration name (for logging and bulk release)
     * @return the actually claimed port (may differ from {@code requestedPort})
     */
    public synchronized int claimPort(int requestedPort, @NotNull String configName) {
        int limit = Math.min(requestedPort + MAX_SEARCH_RANGE, 65535);
        for (int port = requestedPort; port <= limit; port++) {
            if (!claimedPorts.containsKey(port) && PortConflictDetector.isPortAvailable(port)) {
                claimedPorts.put(port, configName);
                if (port != requestedPort) {
                    LOG.info("PortRegistry: '" + configName + "' requested " + requestedPort
                            + ", claimed " + port + " (requested was taken)");
                } else {
                    LOG.debug("PortRegistry: '" + configName + "' claimed " + port);
                }
                return port;
            }
        }
        // No port found in range — return original and let Tomcat fail with a clear error
        LOG.warn("PortRegistry: '" + configName + "' could not claim any port near " + requestedPort);
        return requestedPort;
    }

    /**
     * Claims multiple ports at once for the same configuration.
     * Ports are claimed in order; each claim sees the already-reserved ports
     * from earlier in the same call, preventing intra-configuration collisions.
     *
     * @param requestedPorts list of ports this configuration wants
     * @param configName     the run configuration name
     * @return list of actually claimed ports, in the same order as the input
     */
    public synchronized List<Integer> claimAll(@NotNull List<Integer> requestedPorts,
                                               @NotNull String configName) {
        List<Integer> claimed = new ArrayList<>(requestedPorts.size());
        for (int requested : requestedPorts) {
            claimed.add(claimPort(requested, configName));
        }
        return claimed;
    }

    /**
     * Releases a single port previously claimed by this registry.
     *
     * @param port the port to release
     */
    public synchronized void releasePort(int port) {
        String owner = claimedPorts.remove(port);
        if (owner != null) {
            LOG.debug("PortRegistry: released port " + port + " (was owned by '" + owner + "')");
        }
    }

    /**
     * Releases all ports owned by the given configuration name.
     * Call this from {@code processTerminated()} to clean up automatically.
     *
     * @param configName the run configuration name whose ports should be freed
     */
    public synchronized void releaseAllFor(@NotNull String configName) {
        List<Integer> toRelease = new ArrayList<>();
        claimedPorts.forEach((port, owner) -> {
            if (configName.equals(owner)) toRelease.add(port);
        });
        toRelease.forEach(claimedPorts::remove);
        if (!toRelease.isEmpty()) {
            LOG.info("PortRegistry: released ports " + toRelease + " for '" + configName + "'");
        }
    }

    /**
     * Returns the number of currently claimed ports (for diagnostics / tests).
     */
    public int claimedCount() {
        return claimedPorts.size();
    }
}
