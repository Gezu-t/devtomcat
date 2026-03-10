package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized resolver for computed environment variables derived from
 * configuration state. All derivation rules (VM options → JAVA_OPTS, etc.)
 * live here instead of being scattered across UI tabs.
 *
 * <p>Used by:
 * <ul>
 *   <li>StartupConnectionTab — UI display of computed env vars</li>
 *   <li>TomcatConfigurationEditor — recomputation at applyTo time</li>
 *   <li>TomcatJavaParametersBuilder — launch-time env setup</li>
 * </ul>
 *
 * <p>Precedence rules:
 * <ul>
 *   <li>Explicit user env var beats computed env var</li>
 *   <li>Deleted computed var stays deleted until user resets defaults</li>
 *   <li>Computed values are refreshed from the canonical config on each resolve</li>
 * </ul>
 */
public final class RuntimeEnvResolver {

    /** Keys that are auto-derived from other configuration state. */
    public static final Set<String> COMPUTED_KEYS = Set.of("JAVA_OPTS");

    private RuntimeEnvResolver() {}

    /**
     * Computes the default environment variables derived from the given configuration.
     * Returns only the computed entries; does not include user-defined vars.
     *
     * @param configData the canonical configuration data
     * @return computed env vars (insertion-order preserved, may be empty)
     */
    @NotNull
    public static Map<String, String> computeDefaults(@NotNull TomcatConfigurationData configData) {
        Map<String, String> defaults = new LinkedHashMap<>();

        VmConfig vmConfig = configData.getVmConfig();
        if (vmConfig != null && vmConfig.hasVmOptions()) {
            defaults.put("JAVA_OPTS", vmConfig.getVmOptions());
        }

        return defaults;
    }

    /**
     * Merges computed defaults into per-mode runner settings, respecting user overrides.
     * Only updates keys that are still auto-managed (present in computedKeys and not
     * in deletedComputedKeys).
     *
     * @param runnerSettings the per-mode runner settings to update
     * @param defaults       computed defaults from {@link #computeDefaults}
     * @param computedKeys   keys still auto-managed for this mode
     * @param deletedKeys    keys the user explicitly removed
     */
    public static void mergeIntoRunnerSettings(@NotNull RunnerSettings runnerSettings,
                                                @NotNull Map<String, String> defaults,
                                                @NotNull Set<String> computedKeys,
                                                @NotNull Set<String> deletedKeys) {
        Map<String, String> envVars = runnerSettings.getEnvironmentVariables();

        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (computedKeys.contains(key) && !deletedKeys.contains(key)) {
                envVars.put(key, entry.getValue());
            }
        }
        for (String key : new LinkedHashSet<>(computedKeys)) {
            if (!defaults.containsKey(key) && !deletedKeys.contains(key)) {
                envVars.remove(key);
                computedKeys.remove(key);
            }
        }

        runnerSettings.setEnvironmentVariables(envVars);
        runnerSettings.setComputedEnvironmentKeys(computedKeys);
        runnerSettings.setDeletedComputedEnvironmentKeys(deletedKeys);
    }

    /**
     * Ensures computed env vars in the runner settings reflect the current config state.
     * Called at apply/launch time so correctness does not depend on the user visiting
     * the Startup/Connection tab.
     *
     * @param configData the canonical configuration data
     * @param executorId executor mode ("Run", "Debug", etc.)
     */
    public static void ensureComputedEnvVars(@NotNull TomcatConfigurationData configData,
                                              @NotNull String executorId) {
        RunnerSettings rs = configData.getRunnerSettings(executorId);
        Map<String, String> defaults = computeDefaults(configData);
        Map<String, String> envVars = rs.getEnvironmentVariables();
        Set<String> computedKeys = new LinkedHashSet<>(rs.getComputedEnvironmentKeys());
        Set<String> deletedKeys = new LinkedHashSet<>(rs.getDeletedComputedEnvironmentKeys());

        for (String key : COMPUTED_KEYS) {
            String computedValue = defaults.get(key);

            if (deletedKeys.contains(key)) {
                envVars.remove(key);
                computedKeys.remove(key);
                continue;
            }

            if (computedValue != null) {
                if (computedKeys.contains(key) || !envVars.containsKey(key)) {
                    envVars.put(key, computedValue);
                    computedKeys.add(key);
                }
                continue;
            }

            if (computedKeys.contains(key)) {
                envVars.remove(key);
                computedKeys.remove(key);
            }
        }

        rs.setEnvironmentVariables(envVars);
        rs.setComputedEnvironmentKeys(computedKeys);
        rs.setDeletedComputedEnvironmentKeys(deletedKeys);
    }

    /**
     * Checks whether a given key is a computed (auto-derived) key.
     */
    public static boolean isComputedKey(@NotNull String key) {
        return COMPUTED_KEYS.contains(key);
    }
}
