package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Covers {@link TomcatLifecycleListener#configurationStillExists} against a real
 * {@code RunManager} — the guard that stops the status/history consumers from
 * re-recording (resurrecting) state for a run configuration deleted while its
 * server was still running. Pins both directions so an inverted guard fails here:
 * a still-present config returns true; an unknown or since-removed config returns false.
 */
public class TomcatLifecycleListenerConfigExistsTest extends BasePlatformTestCase {

    private RunnerAndConfigurationSettings addTomcatConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        TomcatRunConfiguration cfg = new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], name);
        RunManager runManager = RunManager.getInstance(getProject());
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(cfg, cfg.getFactory());
        runManager.addConfiguration(settings);
        return settings;
    }

    public void testExistingConfigReturnsTrue() {
        addTomcatConfig("MyTomcat");
        assertTrue("a Tomcat config present in RunManager must be reported as existing",
                TomcatLifecycleListener.configurationStillExists(getProject(), "MyTomcat"));
    }

    public void testUnknownConfigReturnsFalse() {
        assertFalse("a name absent from RunManager must not be reported as existing",
                TomcatLifecycleListener.configurationStillExists(getProject(), "NeverAdded"));
    }

    public void testRemovedConfigReturnsFalse() {
        RunnerAndConfigurationSettings settings = addTomcatConfig("Temp");
        assertTrue(TomcatLifecycleListener.configurationStillExists(getProject(), "Temp"));

        RunManager.getInstance(getProject()).removeConfiguration(settings);
        assertFalse("a config removed while its server ran must not resurrect its history",
                TomcatLifecycleListener.configurationStillExists(getProject(), "Temp"));
    }
}
