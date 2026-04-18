package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins the port-writeback contract:
 * {@link TomcatCommandLineState#writeBackResolvedPorts} must persist runtime-
 * resolved ports back into the authoritative {@link PortConfig} so the next
 * read (UI dialog, serializer, Services panel, browser-URL derivation) sees
 * runtime reality — except in parallel-run mode where the config represents
 * the seed value for each fresh launch.
 */
public class PortWritebackPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    private static PortConfig resolvedPorts(int http, int shutdown, int https, int jmx, int ajp) {
        PortConfig rp = new PortConfig();
        rp.setHttp(http);
        rp.setShutdown(shutdown);
        rp.setHttps(https);
        rp.setJmx(jmx);
        rp.setAjp(ajp);
        return rp;
    }

    public void testSingleInstanceModeWritesBackHttpAndShutdown() {
        TomcatRunConfiguration cfg = createConfig("Writeback");
        cfg.setHttpPort(8083);
        cfg.setShutdownPort(8005);

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);

        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTP port must reflect the resolved value",
                Integer.valueOf(8087), cfg.getHttpPort());
        assertEquals("Shutdown port must reflect the resolved value",
                Integer.valueOf(8009), cfg.getShutdownPort());
    }

    public void testParallelRunModeSkipsWriteback() {
        TomcatRunConfiguration cfg = createConfig("ParallelSkip");
        cfg.setHttpPort(8083);
        cfg.setShutdownPort(8005);
        cfg.setAllowMultipleInstances(true);
        // No pinned base → isParallelRunEffective() returns true.

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);

        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        assertEquals("parallel-run mode must leave HTTP port as the user's seed",
                Integer.valueOf(8083), cfg.getHttpPort());
        assertEquals("parallel-run mode must leave Shutdown port as the user's seed",
                Integer.valueOf(8005), cfg.getShutdownPort());
    }

    public void testPinnedBaseTreatsAsSingleInstance() {
        // Pinned CATALINA_BASE disables parallel isolation (isParallelRunEffective()
        // is false even with the checkbox on) — so writeback MUST still run here,
        // otherwise the dialog would stay out of sync for pinned configs too.
        TomcatRunConfiguration cfg = createConfig("PinnedWriteback");
        cfg.setHttpPort(8083);
        cfg.setAllowMultipleInstances(true);
        cfg.getConfigData().setCatalinaBase("/tmp/pinned-example");

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);

        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        assertEquals("pin + checkbox-on still behaves as single-instance for writeback",
                Integer.valueOf(8087), cfg.getHttpPort());
    }

    public void testDisabledConnectorsDoNotWriteback() {
        // HTTPS/JMX/AJP are "enabled" flags on PortConfig; writeback must respect
        // the user's enable state rather than turning off ports back on.
        TomcatRunConfiguration cfg = createConfig("DisabledConnectors");
        cfg.setHttpPort(8083);
        // Default state: httpsEnabled=false, jmxEnabled=true, ajpEnabled=false
        PortConfig target = cfg.getConfigData().getPortConfig();
        target.setHttpsEnabled(false);
        target.setAjpEnabled(false);
        int httpsBefore = target.getHttps();
        int ajpBefore = target.getAjp();

        PortConfig resolved = resolvedPorts(8087, 8009, 9443, 1099, 9009);

        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        assertEquals("https port must not be mutated while connector is disabled",
                httpsBefore, target.getHttps());
        assertEquals("ajp port must not be mutated while connector is disabled",
                ajpBefore, target.getAjp());
    }

    public void testDebugPortWritebackSingleInstance() {
        TomcatRunConfiguration cfg = createConfig("DebugWriteback");
        cfg.getConfigData().getDebugConfig().setPort(5005);

        TomcatCommandLineState.writeBackResolvedDebugPort(cfg, 5007);

        assertEquals(5007, cfg.getConfigData().getDebugConfig().getPort());
    }

    public void testDebugPortWritebackSkippedForParallel() {
        TomcatRunConfiguration cfg = createConfig("DebugParallel");
        cfg.getConfigData().getDebugConfig().setPort(5005);
        cfg.setAllowMultipleInstances(true);

        TomcatCommandLineState.writeBackResolvedDebugPort(cfg, 5007);

        assertEquals("parallel-run must leave debug port at the seed value",
                5005, cfg.getConfigData().getDebugConfig().getPort());
    }

    public void testBrowserUrlFollowsWritebackInSingleInstance() {
        // End-to-end: before writeback, port = 8083, URL = auto.
        // Resolution produces 8087, writeback commits.
        // getBrowserUrl() must NOW return the 8087 URL without any extra step.
        TomcatRunConfiguration cfg = createConfig("UrlFollowsWriteback");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");
        cfg.setBrowserUrl("http://localhost:8083/app"); // auto, stored as empty

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        assertEquals("browser URL must reflect the resolved port after writeback",
                "http://localhost:8087/app", cfg.getBrowserUrl());
    }
}
