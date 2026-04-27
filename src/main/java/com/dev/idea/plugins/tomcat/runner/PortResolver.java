package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves Tomcat connector ports into a conflict-free {@link PortConfig}.
 *
 * <p>Three-stage algorithm:
 * <ol>
 *   <li>Fill defaults for unset or invalid config values.</li>
 *   <li>Resolve intra-config duplicates (same port assigned to multiple connectors).</li>
 *   <li>Resolve external conflicts (port already bound by another process).</li>
 * </ol>
 *
 * <p>Prefer the pre-resolved {@link PortConfig} path from {@code TomcatCommandLineState}
 * — that one uses {@code TomcatPortRegistry} to reserve ports atomically across concurrent
 * launches. The fallback resolution in this class is non-atomic and may race if two
 * configs launch simultaneously with overlapping port sets.
 */
final class PortResolver {

    private static final Logger LOG = Logger.getInstance(PortResolver.class);

    private final TomcatRunConfiguration configuration;
    @Nullable private final PortConfig preResolved;
    @Nullable private final TomcatDeploymentLogger deploymentLogger;

    PortResolver(@NotNull TomcatRunConfiguration configuration,
                 @Nullable PortConfig preResolved,
                 @Nullable TomcatDeploymentLogger deploymentLogger) {
        this.configuration = configuration;
        this.preResolved = preResolved;
        this.deploymentLogger = deploymentLogger;
    }

    @NotNull
    PortConfig resolve() throws ExecutionException {
        if (preResolved != null) {
            LOG.info("Using pre-resolved ports: HTTP=" + preResolved.getHttp()
                    + ", shutdown=" + preResolved.getShutdown());
            return preResolved;
        }

        int httpPort     = getConfigPort(configuration.getHttpPort(),     PortUtils.DEFAULT_HTTP);
        int shutdownPort = getConfigPort(configuration.getShutdownPort(), PortUtils.DEFAULT_SHUTDOWN);
        int jmxPort      = getConfigPort(configuration.getJmxPort(),      PortUtils.DEFAULT_JMX);
        int httpsPort    = getConfigPort(configuration.getHttpsPort(),    PortUtils.DEFAULT_HTTPS);
        int ajpPort      = getConfigPort(configuration.getAjpPort(),      PortUtils.DEFAULT_AJP);

        // Resolve internal conflicts first (same value assigned to multiple connectors)
        Set<Integer> assigned = new HashSet<>();
        assigned.add(httpPort);
        if (assigned.contains(shutdownPort)) { shutdownPort = PortUtils.findNextAvailableExcluding(shutdownPort, assigned); }
        assigned.add(shutdownPort);
        if (assigned.contains(jmxPort))      { jmxPort      = PortUtils.findNextAvailableExcluding(jmxPort, assigned); }
        assigned.add(jmxPort);
        if (assigned.contains(httpsPort))    { httpsPort    = PortUtils.findNextAvailableExcluding(httpsPort, assigned); }
        assigned.add(httpsPort);
        if (assigned.contains(ajpPort))      { ajpPort      = PortUtils.findNextAvailableExcluding(ajpPort, assigned); }

        // Resolve external conflicts (port already bound by another process)
        httpPort     = resolvePortWithLogging("HTTP",     httpPort,     true);
        shutdownPort = resolvePortWithLogging("Shutdown", shutdownPort, true);
        jmxPort      = resolvePortWithLogging("JMX",      jmxPort,      configuration.isJmxEnabled());
        httpsPort    = resolvePortWithLogging("HTTPS",    httpsPort,    configuration.isHttpsEnabled());
        ajpPort      = resolvePortWithLogging("AJP",      ajpPort,      configuration.isAjpEnabled());

        if (httpPort <= 0 || shutdownPort <= 0
                || (configuration.isJmxEnabled()   && jmxPort   <= 0)
                || (configuration.isHttpsEnabled() && httpsPort <= 0)
                || (configuration.isAjpEnabled()   && ajpPort   <= 0)) {
            throw new ExecutionException("Unable to find available ports for Tomcat run configuration");
        }

        PortConfig ports = new PortConfig();
        ports.setHttp(httpPort);
        ports.setShutdown(shutdownPort);
        ports.setJmx(jmxPort);
        ports.setHttps(httpsPort);
        ports.setAjp(ajpPort);
        return ports;
    }

    private static int getConfigPort(@Nullable Integer configValue, int defaultValue) {
        if (configValue != null && PortUtils.isValid(configValue)) {
            return configValue;
        }
        return defaultValue;
    }

    private int resolvePortWithLogging(@NotNull String serviceName, int port, boolean enabled) {
        if (!enabled) return port;
        if (PortUtils.isAvailable(port)) return port;

        int resolved = PortUtils.findNextAvailable(port);
        if (resolved > 0) {
            String msg = serviceName + " port " + port + " in use, auto-resolved to " + resolved;
            LOG.info(msg);
            if (deploymentLogger != null) {
                deploymentLogger.logServerWarning(msg);
            }
            return resolved;
        }
        // -1 signals "no port found" so the guard in resolve() catches it and throws
        // a clear ExecutionException instead of passing a conflicted port to Tomcat.
        String msg = serviceName + " port " + port + " in use and no available port could be found";
        LOG.warn(msg);
        if (deploymentLogger != null) {
            deploymentLogger.logServerError(msg);
        }
        return -1;
    }
}
