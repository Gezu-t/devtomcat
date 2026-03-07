package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class PortUtils {
    private static final Logger LOG = Logger.getInstance(PortUtils.class);

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int DEFAULT_HTTP = 8080;
    public static final int DEFAULT_HTTPS = 8443;
    public static final int DEFAULT_SHUTDOWN = 8005;
    public static final int DEFAULT_JMX = 1099;
    public static final int DEFAULT_AJP = 8009;

    private PortUtils() {
    }

    public static boolean isValid(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public static boolean isValid(@Nullable String portStr) {
        if (portStr == null || portStr.isEmpty()) return false;
        try {
            return isValid(Integer.parseInt(portStr));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Checks if a port is available for binding on both wildcard and localhost addresses.
     *
     * <p>Must check both because Tomcat binds different services to different addresses:
     * HTTP connector binds to all interfaces (0.0.0.0), while the shutdown socket binds
     * to {@code localhost}. On macOS with IPv6, {@code localhost} resolves to {@code ::1}
     * which is a different address family from {@code 0.0.0.0} — checking only the wildcard
     * can miss conflicts on the loopback interface, leading to BindException at Tomcat startup.
     */
    public static boolean isAvailable(int port) {
        if (!isValid(port)) return false;
        // Check wildcard (0.0.0.0) — how HTTP connectors bind
        if (!tryBind(port, null)) return false;
        // Check localhost — how Tomcat's shutdown socket binds.
        // On macOS/IPv6, localhost (::1) differs from 0.0.0.0.
        if (!tryBind(port, "localhost")) return false;
        return true;
    }

    private static boolean tryBind(int port, String address) {
        try {
            InetAddress addr = address != null ? InetAddress.getByName(address) : null;
            try (ServerSocket socket = new ServerSocket(port, 1, addr)) {
                return true;
            }
        } catch (IOException e) {
            LOG.debug("Port " + port + " not available on " + (address != null ? address : "0.0.0.0"));
            return false;
        }
    }

    public static int findAvailable(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            if (isAvailable(port)) return port;
        }
        return -1;
    }

    public static int findNextAvailable(int preferredPort) {
        if (isAvailable(preferredPort)) return preferredPort;
        return findAvailable(preferredPort + 1, MAX_PORT);
    }

    @Nullable
    public static Integer parsePort(String text, String portName) throws ConfigurationException {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(portName + " port must be a valid number");
        }
    }
}