package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

public final class TomcatConfigurationUtils {
    private TomcatConfigurationUtils() {}

    @NotNull
    public static ValidationUtils.Result validateInstallation(@NotNull String installPath) {
        return TomcatServerValidator.validateInstallation(Objects.requireNonNull(installPath));
    }

    public static boolean isValidInstallation(@NotNull String installPath) {
        return TomcatServerValidator.isValidInstallation(Objects.requireNonNull(installPath));
    }

    @NotNull
    public static String detectVersion(@NotNull String installPath) {
        return TomcatServerValidator.detectVersion(Objects.requireNonNull(installPath));
    }

    @Nullable
    public static Path getCatalinaBase(@NotNull TomcatRunConfiguration config) {
        return TomcatProjectUtils.getCatalinaBase(Objects.requireNonNull(config));
    }

    @NotNull
    public static String sanitizeName(@Nullable String name) {
        return StringUtils.sanitizeFileName(name);
    }

    /**
     * Apply all dynamic defaults to a Tomcat configuration.
     *
     * @param config the run configuration to configure
     */
    public static void applyAllDynamicDefaults(@NotNull TomcatRunConfiguration config) {
        Objects.requireNonNull(config, "Configuration cannot be null");

        TomcatConfigurationData data = config.getConfigData();
        if (data == null) {
            return;
        }

        // Set default context path if empty
        if (StringUtils.isEmpty(data.getContextPath())) {
            data.setContextPath("/");
        }

        // Set default ports if not configured
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
    }
}