package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class BrowserLaunchSectionPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    public void testResetFromTreatsLegacyLocalhostUrlAsAuto() {
        TomcatRunConfiguration cfg = createConfig("LegacyAutoUi");
        cfg.setHttpPort(8091);
        cfg.getConfigData().setContextPath("/app");
        cfg.getConfigData().getBrowserConfig().setBrowserUrl("http://localhost:8083/app");

        BrowserLaunchSection section = new BrowserLaunchSection(getProject());
        section.createPanel();
        section.resetFrom(cfg);

        assertEquals("http://localhost:8091/app", section.getUrl());
        assertFalse("resetFrom should not leave the editor immediately dirty",
                section.isModified(cfg));
    }

    public void testResetFromPreservesCustomLocalhostUrlWhenPathDiffers() {
        TomcatRunConfiguration cfg = createConfig("CustomLocalhostUi");
        cfg.setHttpPort(8091);
        cfg.getConfigData().setContextPath("/app");
        cfg.getConfigData().getBrowserConfig().setBrowserUrl("http://localhost:8083/custom");

        BrowserLaunchSection section = new BrowserLaunchSection(getProject());
        section.createPanel();
        section.resetFrom(cfg);

        assertEquals("http://localhost:8083/custom", section.getUrl());
        assertFalse("a preserved custom URL should still compare cleanly after reset",
                section.isModified(cfg));
    }
}
