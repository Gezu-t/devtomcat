package com.dev.idea.plugins.tomcat.environment;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Thin wrapper around IntelliJ's {@link Registry} that eliminates try/catch
 * boilerplate by providing null-safe accessors with sensible defaults.
 */
final class RegistryHelper {

    private static final Logger LOG = Logger.getInstance(RegistryHelper.class);

    private RegistryHelper() {}

    static int getInt(@NotNull String key, int defaultValue) {
        try {
            return Registry.intValue(key);
        } catch (Exception e) {
            LOG.debug(key + " not in Registry, using default: " + defaultValue);
            return defaultValue;
        }
    }

    static boolean getBool(@NotNull String key, boolean defaultValue) {
        try {
            return Registry.is(key);
        } catch (Exception e) {
            LOG.debug(key + " not in Registry, using default: " + defaultValue);
            return defaultValue;
        }
    }

    @NotNull
    static String getString(@NotNull String key, @NotNull String defaultValue) {
        try {
            String value = Registry.stringValue(key);
            if (!StringUtil.isEmpty(value)) {
                return value;
            }
        } catch (Exception e) {
            LOG.debug(key + " not in Registry, using default: " + defaultValue);
        }
        return defaultValue;
    }
}
