package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compatibility layer for {@link RunDashboardRunConfigurationNode} API.
 *
 * <p>{@code getConfigurationSettings()} is scheduled for removal in a future
 * IntelliJ release. This class centralizes all calls so the migration is a
 * single-file change when the replacement API ships.
 *
 * <p>Resolution order (reflection-first, deprecated fallback):
 * <ol>
 *   <li>{@code getSettings()} — likely future replacement</li>
 *   <li>{@code getRunnerAndConfigurationSettings()} — alternative name</li>
 *   <li>{@code getConfigurationSettings()} — current deprecated method</li>
 * </ol>
 */
public final class DashboardCompat {

    private static final Logger LOG = Logger.getInstance(DashboardCompat.class);

    /** Cached flag: once we find that the deprecated method is the only option, stop trying reflection. */
    private static volatile boolean useDeprecatedDirectly = false;

    private DashboardCompat() {}

    /**
     * Gets the {@link RunnerAndConfigurationSettings} from a dashboard node,
     * using the best available API.
     */
    @Nullable
    public static RunnerAndConfigurationSettings getSettings(@NotNull RunDashboardRunConfigurationNode node) {
        if (!useDeprecatedDirectly) {
            RunnerAndConfigurationSettings result = tryReflection(node);
            if (result != null) return result;
            // Reflection found nothing new — fall through to deprecated and cache the decision
            useDeprecatedDirectly = true;
            LOG.debug("DashboardCompat: no replacement API found, using deprecated getConfigurationSettings()");
        }
        return node.getConfigurationSettings();
    }

    /**
     * Gets the {@link RunConfiguration} from a dashboard node.
     * Convenience method — delegates to {@link #getSettings(RunDashboardRunConfigurationNode)}.
     */
    @Nullable
    public static RunConfiguration getConfiguration(@NotNull RunDashboardRunConfigurationNode node) {
        RunnerAndConfigurationSettings settings = getSettings(node);
        return settings != null ? settings.getConfiguration() : null;
    }

    /**
     * Tries known candidate method names that may replace {@code getConfigurationSettings()}
     * in future IntelliJ versions.
     */
    @Nullable
    private static RunnerAndConfigurationSettings tryReflection(@NotNull RunDashboardRunConfigurationNode node) {
        for (String methodName : new String[]{"getSettings", "getRunnerAndConfigurationSettings"}) {
            try {
                var method = node.getClass().getMethod(methodName);
                Object result = method.invoke(node);
                if (result instanceof RunnerAndConfigurationSettings settings) {
                    LOG.info("DashboardCompat: using new API method '" + methodName + "' instead of deprecated getConfigurationSettings()");
                    return settings;
                }
            } catch (NoSuchMethodException ignored) {
                // Expected on older platform versions
            } catch (Exception e) {
                LOG.debug("DashboardCompat: reflection failed for " + methodName, e);
            }
        }
        return null;
    }
}
