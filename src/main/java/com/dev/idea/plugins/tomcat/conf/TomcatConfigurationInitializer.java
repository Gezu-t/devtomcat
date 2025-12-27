package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.utils.TomcatConfigurationUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Handles initialization and dynamic default configuration.
 *
 * <p>100% MODERN MODEL — NO LEGACY — FULLY COMPATIBLE WITH int-based PortConfig
 * <p>Safe null handling, proper validation, comprehensive error handling.
 */


public class TomcatConfigurationInitializer {
    private static final Logger LOG = Logger.getInstance(TomcatConfigurationInitializer.class);

    public static void initialize(@NotNull TomcatRunConfiguration config) {
        Objects.requireNonNull(config, "Configuration cannot be null");
        try {
            TomcatConfigurationUtils.applyAllDynamicDefaults(config);
            LOG.debug("Initialized configuration: {}", config.getName());
        } catch (Exception e) {
            LOG.error("Failed to initialize: " + config.getName(), e);
        }
    }

    public static void refresh(@NotNull TomcatRunConfiguration config) {
        initialize(config); // Same logic
    }
}
