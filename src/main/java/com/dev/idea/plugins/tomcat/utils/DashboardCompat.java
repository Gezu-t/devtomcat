package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single-file boundary for reading settings off a
 * {@link RunDashboardRunConfigurationNode}. Every call to
 * {@code getConfigurationSettings()} goes through here so the eventual
 * platform migration is a one-file diff.
 *
 * <h3>Why this is still a direct call rather than a builder-based flow</h3>
 *
 * <p>The 2026.1 replacement for {@code getConfigurationSettings()} is not a
 * new getter. The platform removed the accessor entirely and now hands the
 * {@link RunnerAndConfigurationSettings} to customizers as an argument to a
 * new {@code updatePresentation(RunDashboardCustomizationBuilder, settings,
 * descriptor)} overload. That builder type exists only on 2026.1+, so we
 * cannot reference it while our compile target is below 2026.1 —
 * doing so would break class loading on 2024.1/2025.1 before any runtime
 * guard could run, not merely fail at call time.
 *
 * <p>Until the plugin's minimum platform reaches 2026.1, the call below is
 * the only source-compatible way to read the settings from inside the
 * legacy {@code updatePresentation(PresentationData, Node)} override. An
 * earlier version of this class tried reflection for speculatively-named
 * replacement methods ({@code getSettings},
 * {@code getRunnerAndConfigurationSettings}); those methods never landed
 * on the interface — the real replacement is the parameter-based flow
 * described above — so the reflection was dead weight.
 *
 * <p>The call is not annotated with {@code @SuppressWarnings("deprecation")}
 * because {@code getConfigurationSettings()} is not marked deprecated on
 * our current compile target (2024.1/2025.1). The Plugin Verifier flags it
 * when running the plugin bytecode against 2026.1 because the method is
 * deprecated-for-removal there; this is expected and documented as accepted
 * tech debt until the floor bump.
 *
 * <h3>Migration plan (when the minimum platform reaches 2026.1)</h3>
 *
 * <ol>
 *   <li>Delete this class.</li>
 *   <li>In {@code TomcatRunDashboardCustomizer}, replace the deprecated
 *       {@code updatePresentation(PresentationData, RunDashboardRunConfigurationNode)}
 *       override with the new
 *       {@code updatePresentation(RunDashboardCustomizationBuilder,
 *       RunnerAndConfigurationSettings, RunContentDescriptor)} overload
 *       and consume the {@code settings} parameter directly.</li>
 *   <li>Rewrite the existing {@code PresentationData}-based customisation
 *       as fluent builder calls.</li>
 * </ol>
 */
public final class DashboardCompat {

    private DashboardCompat() {}

    /** Gets the {@link RunnerAndConfigurationSettings} attached to a dashboard node. */
    @Nullable
    public static RunnerAndConfigurationSettings getSettings(@NotNull RunDashboardRunConfigurationNode node) {
        return node.getConfigurationSettings();
    }

    /** Convenience accessor — delegates to {@link #getSettings}. */
    @Nullable
    public static RunConfiguration getConfiguration(@NotNull RunDashboardRunConfigurationNode node) {
        RunnerAndConfigurationSettings settings = getSettings(node);
        return settings != null ? settings.getConfiguration() : null;
    }
}
