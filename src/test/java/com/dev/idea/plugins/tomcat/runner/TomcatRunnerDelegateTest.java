package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.RunnerAndConfigurationSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TomcatRunnerDelegate#belongsTo} — the matching
 * predicate that decides which running descriptor a toolbar rerun click
 * targets.
 *
 * <p>This is the gate behind the entire rerun-on-toolbar UX: when the user
 * clicks Run while Tomcat is already running, the delegate scans every
 * RunContentDescriptor and asks {@code belongsTo} "is this the same logical
 * run configuration?". A regression in either direction has visible damage:
 * <ul>
 *   <li><b>Too narrow</b> — the running instance isn't recognised as the
 *       click's target, so the toolbar spawns a SECOND instance of the same
 *       config (or a fresh launch races the live one for ports).</li>
 *   <li><b>Too broad</b> — clicking Run on config A intercepts config B's
 *       descriptor, opening config B's Update dialog or stopping it.</li>
 * </ul>
 *
 * <p>The contract has two layers:
 * <ol>
 *   <li><b>Settings reference equality</b> — when both the handler's
 *       launch settings and the target environment's settings are present,
 *       reference equality is the source of truth. IntelliJ's RunManager
 *       keeps the same {@link RunnerAndConfigurationSettings} reference
 *       across renames, clones, and cross-executor switches.</li>
 *   <li><b>Configuration reference fallback</b> — for handlers launched
 *       outside the normal editor flow (programmatic execution paths that
 *       bypass RunManager) the fallback uses {@code TomcatRunConfiguration}
 *       reference equality.</li>
 * </ol>
 */
@DisplayName("TomcatRunnerDelegate.belongsTo")
class TomcatRunnerDelegateTest {

    @Nested
    @DisplayName("settings-reference path (primary)")
    class SettingsReference {

        @Test
        @DisplayName("matches when both settings references are the same instance")
        void sameSettingsReference() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration config = mock(TomcatRunConfiguration.class);
            RunnerAndConfigurationSettings settings = mock(RunnerAndConfigurationSettings.class);

            when(handler.getLaunchSettings()).thenReturn(settings);

            assertTrue(TomcatRunnerDelegate.belongsTo(handler, config, settings),
                    "same RunnerAndConfigurationSettings reference must match");
        }

        @Test
        @DisplayName("does not match when settings references are different instances")
        void differentSettingsReferences() {
            // Two different settings objects = two different run configurations
            // from IntelliJ's perspective. The toolbar must NOT mistake them
            // for each other when scanning descriptors for the rerun target.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration config = mock(TomcatRunConfiguration.class);
            RunnerAndConfigurationSettings handlerSettings = mock(RunnerAndConfigurationSettings.class);
            RunnerAndConfigurationSettings targetSettings = mock(RunnerAndConfigurationSettings.class);

            when(handler.getLaunchSettings()).thenReturn(handlerSettings);

            assertFalse(TomcatRunnerDelegate.belongsTo(handler, config, targetSettings),
                    "different settings references must NOT match");
        }

        @Test
        @DisplayName("settings-equality wins over config-reference fallback")
        void settingsRulesOverConfigFallback() {
            // Pin the precedence: when settings identity matches, the same-
            // config check is irrelevant. This protects against scenarios
            // where two configs share a TomcatRunConfiguration but have
            // distinct RunnerAndConfigurationSettings (rare but legal in
            // programmatic test setups).
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration sameConfig = mock(TomcatRunConfiguration.class);
            RunnerAndConfigurationSettings settings = mock(RunnerAndConfigurationSettings.class);

            when(handler.getLaunchSettings()).thenReturn(settings);
            when(handler.getConfiguration()).thenReturn(sameConfig);

            // Settings match; no need to consult getConfiguration().
            assertTrue(TomcatRunnerDelegate.belongsTo(handler, sameConfig, settings));
        }
    }

    @Nested
    @DisplayName("configuration-reference fallback")
    class ConfigurationFallback {

        @Test
        @DisplayName("when targetSettings is null, falls back to config reference equality (match)")
        void nullTargetSettings_sameConfig_matches() {
            // Handlers launched programmatically (outside the editor flow)
            // pass a null env.getRunnerAndConfigurationSettings() — see the
            // delegate's getDescriptorsFor() comment. The fallback must
            // still recognise them by config reference so toolbar rerun
            // works for those handlers too.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration config = mock(TomcatRunConfiguration.class);

            when(handler.getLaunchSettings()).thenReturn(mock(RunnerAndConfigurationSettings.class));
            when(handler.getConfiguration()).thenReturn(config);

            assertTrue(TomcatRunnerDelegate.belongsTo(handler, config, null),
                    "null target settings must fall back to config reference equality");
        }

        @Test
        @DisplayName("when handler has no launch settings, falls back to config reference (match)")
        void nullHandlerSettings_sameConfig_matches() {
            // The fallback is symmetric: either side missing settings means
            // we skip the settings-equality branch and go straight to the
            // config check. Pin both directions of the OR.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration config = mock(TomcatRunConfiguration.class);
            RunnerAndConfigurationSettings settings = mock(RunnerAndConfigurationSettings.class);

            when(handler.getLaunchSettings()).thenReturn(null);
            when(handler.getConfiguration()).thenReturn(config);

            assertTrue(TomcatRunnerDelegate.belongsTo(handler, config, settings));
        }

        @Test
        @DisplayName("fallback rejects when configurations are different")
        void fallback_differentConfig_doesNotMatch() {
            // The full negative case: both settings paths blocked, configs
            // differ — must NOT match. Without this rejection, a programmatic
            // launch of config A could be mis-attributed to config B's
            // toolbar click.
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration handlerConfig = mock(TomcatRunConfiguration.class);
            TomcatRunConfiguration targetConfig = mock(TomcatRunConfiguration.class);

            when(handler.getLaunchSettings()).thenReturn(null);
            when(handler.getConfiguration()).thenReturn(handlerConfig);

            assertFalse(TomcatRunnerDelegate.belongsTo(handler, targetConfig, null),
                    "different configs through the fallback must NOT match");
        }

        @Test
        @DisplayName("both settings null and different config rejects via fallback")
        void bothNullSettings_differentConfig_doesNotMatch() {
            TomcatProcessHandler handler = mock(TomcatProcessHandler.class);
            TomcatRunConfiguration handlerConfig = mock(TomcatRunConfiguration.class);
            TomcatRunConfiguration targetConfig = mock(TomcatRunConfiguration.class);

            when(handler.getLaunchSettings()).thenReturn(null);
            when(handler.getConfiguration()).thenReturn(handlerConfig);

            assertFalse(TomcatRunnerDelegate.belongsTo(handler, targetConfig, null));
        }
    }
}
