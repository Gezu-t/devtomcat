package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;

public final class PortUtils {
    private static final Logger LOG = Logger.getInstance(PortUtils.class);

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int DEFAULT_HTTP = 8080;
    public static final int DEFAULT_HTTPS = 8443;
    public static final int DEFAULT_SHUTDOWN = 8005;
    public static final int DEFAULT_JMX = 9010;

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

    public static boolean isAvailable(int port) {
        if (!isValid(port)) return false;
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            LOG.debug("Port " + port + " is not available");
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
}