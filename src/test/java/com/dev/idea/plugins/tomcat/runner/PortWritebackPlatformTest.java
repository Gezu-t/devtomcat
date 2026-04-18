package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins the port-writeback contract:
 * {@link TomcatCommandLineState#writeBackResolvedPorts} persists runtime-resolved
 * ports back into the authoritative {@link PortConfig} so the next read (UI dialog,
 * serializer, Services panel, browser-URL derivation) sees runtime reality — but
 * only in single-instance mode.
 *
 * <p><b>Parallel-run mode is intentionally skipped.</b> Writing back in parallel
 * mode would ratchet the user's seed away from intent after any transient conflict
 * ({@code 8083 → 8090} would become the new base even after 8083 frees up).
 * Per-launch ports live on the handler via {@code CARRIED_PORTS_KEY}; the Services
 * panel reads from there, not from the config seed.
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

    public void testWritebackIsIdempotent() {
        // Writing back the same values the config already holds must not mutate
        // anything. Downstream observers (Services panel refresh, serializer
        // write-on-exit, modified-flag) can skip their work when nothing
        // actually changed. Second-launch semantics rely on this: the config
        // is already at the resolved port from the first launch, so re-running
        // writeback on the same values is a no-op.
        TomcatRunConfiguration cfg = createConfig("Idempotent");
        cfg.setHttpPort(8087);
        cfg.setShutdownPort(8009);

        PortConfig same = resolvedPorts(8087, 8009, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, same);

        // Still the same values. The real invariant here is that the code path
        // detects "nothing changed" and avoids the dashboard refresh — the
        // visible side-effect we care about is absence of thrash.
        assertEquals(Integer.valueOf(8087), cfg.getHttpPort());
        assertEquals(Integer.valueOf(8009), cfg.getShutdownPort());
    }

    public void testParallelRunModeSkipsWriteback() {
        // Parallel mode must leave the user's seed alone. Writing back would
        // ratchet the seed permanently away from intent after any transient
        // conflict (see testWritebackDoesNotRatchetSeedOnTransientConflict).
        // Per-launch ports live on the handler via CARRIED_PORTS_KEY.
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

    public void testWritebackDoesNotRatchetSeedOnTransientConflict() {
        // Regression guard for the parallel-run ratchet: a one-off transient
        // conflict must NOT promote the resolved port to the permanent seed.
        // User's original intent is 8083. First launch bumps to 8090 because
        // 8083 was busy at that moment. When 8083 frees up, the next launch
        // MUST still start conflict-resolution from 8083 — not from 8090.
        TomcatRunConfiguration cfg = createConfig("RatchetGuard");
        cfg.setHttpPort(8083);
        cfg.setAllowMultipleInstances(true);

        PortConfig firstResolved = resolvedPorts(8090, 8005, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, firstResolved);

        // Seed is preserved in parallel mode — this is the invariant under test.
        assertEquals("transient conflict must not ratchet the parallel-run seed",
                Integer.valueOf(8083), cfg.getHttpPort());

        // Second launch after the conflict clears: resolver sees 8083 is free
        // and stays at 8083. If the seed had ratcheted, this would be 8090.
        PortConfig secondResolved = resolvedPorts(8083, 8005, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, secondResolved);

        assertEquals("the second launch must still see the user's original seed",
                Integer.valueOf(8083), cfg.getHttpPort());
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
        // Symmetric with testParallelRunModeSkipsWriteback — same ratchet
        // concern applies to the debug seed, so the skip is preserved.
        TomcatRunConfiguration cfg = createConfig("DebugParallel");
        cfg.getConfigData().getDebugConfig().setPort(5005);
        cfg.setAllowMultipleInstances(true);

        TomcatCommandLineState.writeBackResolvedDebugPort(cfg, 5007);

        assertEquals("parallel-run must leave the debug seed alone",
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

    public void testParallelRunAutoUrlRewrittenAtRuntime() {
        // Parallel-run mode deliberately skips writeback so the seed stays
        // stable across transient conflicts. The cost is that getBrowserUrl()
        // keeps returning the seed-port URL after launch — the handler's
        // runtime rewrite is the safety net that lets the browser reach
        // *this* specific parallel instance. This test pins the runtime half
        // of that contract.
        TomcatRunConfiguration cfg = createConfig("ParallelRuntimeRewrite");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");
        cfg.setAllowMultipleInstances(true);
        cfg.setBrowserUrl("http://localhost:8083/app"); // auto, stored empty

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        // Writeback was skipped — config still holds the seed value.
        assertEquals("parallel-run config must NOT be mutated by writeback",
                Integer.valueOf(8083), cfg.getHttpPort());
        assertEquals("config-level browser URL still reflects the seed in parallel mode",
                "http://localhost:8083/app", cfg.getBrowserUrl());

        // Runtime rewrite brings it in line with THIS instance's actual port.
        String launched = TomcatProcessHandler.rewritePortIfNeeded(cfg.getBrowserUrl(), 8087);
        assertEquals("runtime rewrite must bridge config seed → this instance's port",
                "http://localhost:8087/app", launched);
    }

    public void testCustomProxyUrlNotRewrittenAtRuntime() {
        // A user pointing their browser URL at a reverse proxy, CDN, or port-forward
        // chose that port deliberately. Even at launch time the rewrite must NOT
        // touch it — the safety net is for localhost URLs only.
        TomcatRunConfiguration cfg = createConfig("ProxyUrlPreserved");
        cfg.setHttpPort(8083);
        cfg.setBrowserUrl("http://proxy.example.com:9090/route");

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);
        TomcatCommandLineState.writeBackResolvedPorts(cfg, resolved);

        // Config-level: custom URL preserved verbatim (single-source-of-truth contract).
        assertEquals("http://proxy.example.com:9090/route", cfg.getBrowserUrl());

        // Runtime: rewrite must pass through unchanged because the host is not loopback.
        String launched = TomcatProcessHandler.rewritePortIfNeeded(cfg.getBrowserUrl(), 8087);
        assertEquals("custom proxy URL must survive the runtime rewrite untouched",
                "http://proxy.example.com:9090/route", launched);
    }
}
