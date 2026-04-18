package com.dev.idea.plugins.tomcat.conf;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins the single-source-of-truth contract for the browser URL.
 *
 * <p>Previously the URL was stored with a baked-in port and could drift out of
 * sync with the actual HTTP port (auto-resolution, parallel-run isolation,
 * or even UI-typed edits). This test class enforces the new invariant:
 * {@link TomcatRunConfiguration#getBrowserUrl()} is derived from
 * {@link TomcatRunConfiguration#getHttpPort()} whenever the URL is
 * auto-generated, so a single change to the port propagates automatically.
 */
public class BrowserUrlSingleSourcePlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    public void testAutoUrlMatchesCurrentPortAndContext() {
        TomcatRunConfiguration cfg = createConfig("Auto");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");

        assertEquals("http://localhost:8083/app", cfg.autoBrowserUrl());
    }

    public void testSettingAutoPatternStoresEmpty() {
        TomcatRunConfiguration cfg = createConfig("AutoStore");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");

        // A URL that exactly matches the auto pattern is really just "auto".
        // Store it as empty so future port changes don't need a second rewrite.
        cfg.setBrowserUrl("http://localhost:8083/app");

        assertEquals("raw storage must be empty when URL matches the auto pattern",
                "", cfg.getConfigData().getBrowserConfig().getUrl());
    }

    public void testAutoUrlFollowsPortChange() {
        TomcatRunConfiguration cfg = createConfig("Follow");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");
        cfg.setBrowserUrl("http://localhost:8083/app"); // normalised to empty

        // Port change must flow through every read path without a rewrite step.
        cfg.setHttpPort(8087);

        assertEquals("getBrowserUrl must recompute live from the new port",
                "http://localhost:8087/app", cfg.getBrowserUrl());
    }

    public void testCustomUrlIsPreservedVerbatim() {
        TomcatRunConfiguration cfg = createConfig("Custom");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");

        // Different host, different path, different scheme — all mark the URL
        // as user-customised. Stored verbatim, read back verbatim.
        cfg.setBrowserUrl("https://staging.example.com/dashboard");

        assertEquals("https://staging.example.com/dashboard",
                cfg.getConfigData().getBrowserConfig().getUrl());
        assertEquals("https://staging.example.com/dashboard",
                cfg.getBrowserUrl());
    }

    public void testCustomUrlDoesNotFollowPortChange() {
        TomcatRunConfiguration cfg = createConfig("CustomStable");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");
        cfg.setBrowserUrl("http://custom.example.com:9090/route");

        cfg.setHttpPort(8087);

        // User's intent takes precedence — their URL doesn't mutate when the
        // port changes. The runtime's port-rewrite safety net in
        // TomcatProcessHandler still adjusts the port used to OPEN the browser.
        assertEquals("http://custom.example.com:9090/route", cfg.getBrowserUrl());
    }

    public void testEmptyStoredReturnsComputedFallback() {
        TomcatRunConfiguration cfg = createConfig("Empty");
        cfg.setHttpPort(8083);

        // Simulate a freshly-created config with no URL ever set.
        cfg.getConfigData().getBrowserConfig().setBrowserUrl("");

        assertEquals("getBrowserUrl must fall back to the auto pattern when stored is empty",
                cfg.autoBrowserUrl(), cfg.getBrowserUrl());
    }
}
