package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.StringUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Handles initialization and dynamic default configuration for run configs. */
public class TomcatConfigurationInitializer {

    private static final Logger LOG = Logger.getInstance(TomcatConfigurationInitializer.class);

    public static void initialize(@NotNull TomcatRunConfiguration config) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        try {
            applyDynamicDefaults(config);
            LOG.debug("Initialized configuration: {}", config.getName());
        } catch (Exception e) {
            LOG.error("Failed to initialize: " + config.getName(), e);
        }
    }

    public static void refresh(@NotNull TomcatRunConfiguration config) {
        initialize(config);
    }

    private static void applyDynamicDefaults(@NotNull TomcatRunConfiguration config) {
        TomcatConfigurationData data = config.getConfigData();
        if (data == null) return;

        if (StringUtils.isEmpty(data.getContextPath())) {
            data.setContextPath("/");
        }

        PortConfig ports = data.getPortConfig();
        if (ports == null) {
            ports = new PortConfig();
            data.setPortConfig(ports);
        }
        if (ports.getHttp() <= 0) {
            ports.setHttp(PortUtils.DEFAULT_HTTP);
        }
        if (ports.getShutdown() <= 0) {
            ports.setShutdown(PortUtils.DEFAULT_SHUTDOWN);
        }
        if (ports.getHttps() <= 0) {
            ports.setHttps(PortUtils.DEFAULT_HTTPS);
        }
        if (ports.getJmx() <= 0) {
            ports.setJmx(PortUtils.DEFAULT_JMX);
        }
        if (ports.getAjp() <= 0) {
            ports.setAjp(PortConfig.DEFAULT_AJP_PORT);
        }
    }
}
