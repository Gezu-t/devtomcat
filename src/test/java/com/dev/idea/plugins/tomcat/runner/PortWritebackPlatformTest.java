package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins the port-writeback contract:
 * {@link LaunchPortClaimer#writeBackResolvedPorts} persists runtime-resolved
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

        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

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
        LaunchPortClaimer.writeBackResolvedPorts(cfg, same);

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

        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

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
        LaunchPortClaimer.writeBackResolvedPorts(cfg, firstResolved);

        // Seed is preserved in parallel mode — this is the invariant under test.
        assertEquals("transient conflict must not ratchet the parallel-run seed",
                Integer.valueOf(8083), cfg.getHttpPort());

        // Second launch after the conflict clears: resolver sees 8083 is free
        // and stays at 8083. If the seed had ratcheted, this would be 8090.
        PortConfig secondResolved = resolvedPorts(8083, 8005, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, secondResolved);

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

        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

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

        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("https port must not be mutated while connector is disabled",
                httpsBefore, target.getHttps());
        assertEquals("ajp port must not be mutated while connector is disabled",
                ajpBefore, target.getAjp());
    }

    public void testDebugPortWritebackSingleInstance() {
        TomcatRunConfiguration cfg = createConfig("DebugWriteback");
        cfg.getConfigData().getDebugConfig().setPort(5005);

        LaunchPortClaimer.writeBackResolvedDebugPort(cfg, 5007);

        assertEquals(5007, cfg.getConfigData().getDebugConfig().getPort());
    }

    public void testDebugPortWritebackSkippedForParallel() {
        // Symmetric with testParallelRunModeSkipsWriteback — same ratchet
        // concern applies to the debug seed, so the skip is preserved.
        TomcatRunConfiguration cfg = createConfig("DebugParallel");
        cfg.getConfigData().getDebugConfig().setPort(5005);
        cfg.setAllowMultipleInstances(true);

        LaunchPortClaimer.writeBackResolvedDebugPort(cfg, 5007);

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
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

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
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

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

    public void testCustomLoopbackUrlPortIsRewrittenOnWriteback() {
        // Regression for the dialog-desync bug: the user typed a deeper path
        // into the "After launch" URL (e.g. http://localhost:8082/connect/
        // common/login), so the stored URL is not auto-managed. Port resolution
        // bumps 8082 → 8083. Writeback must rewrite the stored URL's PORT
        // (preserving the user's custom path) — otherwise the run-config
        // dialog re-opens showing :8082 even though Tomcat now binds :8083,
        // and the HTTP-port field shows :8083. The path component is the
        // user's intent and stays put.
        TomcatRunConfiguration cfg = createConfig("CustomPathLoopbackUrl");
        cfg.setHttpPort(8082);
        cfg.getConfigData().setContextPath("/connect");
        cfg.setBrowserUrl("http://localhost:8082/connect/common/login"); // custom path, stored verbatim

        PortConfig resolved = resolvedPorts(8083, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTP port must reflect the resolved value",
                Integer.valueOf(8083), cfg.getHttpPort());
        assertEquals("stored browser URL must have its port rewritten while keeping the custom path",
                "http://localhost:8083/connect/common/login", cfg.getBrowserUrl());
    }

    public void testHttpsPortRewriteUpdatesHttpsUrl() {
        // Mirror of the HTTP-port writeback path for HTTPS. User stores an
        // https://localhost:8443/... URL; HTTPS port auto-resolves to 8444.
        // The stored URL must follow.
        TomcatRunConfiguration cfg = createConfig("HttpsPortRewrite");
        cfg.setHttpPort(8080);
        cfg.setHttpsPort(8443);
        PortConfig target = cfg.getConfigData().getPortConfig();
        target.setHttpsEnabled(true);
        cfg.setBrowserUrl("https://localhost:8443/secure/login");

        PortConfig resolved = resolvedPorts(8080, 8005, 8444, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTPS port must reflect the resolved value",
                Integer.valueOf(8444), cfg.getHttpsPort());
        assertEquals("stored HTTPS URL must have its port rewritten",
                "https://localhost:8444/secure/login", cfg.getBrowserUrl());
    }

    public void testHttpPortRewriteDoesNotTouchHttpsUrl() {
        // Regression guard. Before this guard, the writeback rewrote ANY
        // loopback URL's port to the new HTTP port — even an HTTPS URL,
        // because the rewrite was scheme-agnostic. With the scheme +
        // previous-port match, an https://localhost:8443/... URL stays put
        // when only the HTTP port shifts.
        TomcatRunConfiguration cfg = createConfig("HttpShiftLeavesHttpsAlone");
        cfg.setHttpPort(8082);
        cfg.setHttpsPort(8443);
        PortConfig target = cfg.getConfigData().getPortConfig();
        target.setHttpsEnabled(true);
        cfg.setBrowserUrl("https://localhost:8443/secure/login");

        PortConfig resolved = resolvedPorts(8083, 8005, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTP port must reflect the resolved value",
                Integer.valueOf(8083), cfg.getHttpPort());
        assertEquals("HTTPS URL must not be mutated by an HTTP-only port change",
                "https://localhost:8443/secure/login", cfg.getBrowserUrl());
    }

    public void testHttpsPortRewriteDoesNotTouchHttpUrl() {
        // Symmetric guard: an HTTP URL stays put when only HTTPS port shifts.
        TomcatRunConfiguration cfg = createConfig("HttpsShiftLeavesHttpAlone");
        cfg.setHttpPort(8080);
        cfg.setHttpsPort(8443);
        PortConfig target = cfg.getConfigData().getPortConfig();
        target.setHttpsEnabled(true);
        cfg.setBrowserUrl("http://localhost:8080/myapp");

        PortConfig resolved = resolvedPorts(8080, 8005, 8444, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTPS port must reflect the resolved value",
                Integer.valueOf(8444), cfg.getHttpsPort());
        assertEquals("HTTP URL must not be mutated by an HTTPS-only port change",
                "http://localhost:8080/myapp", cfg.getBrowserUrl());
    }

    public void testWritebackSkippedWhenHttpsDisabled() {
        // HTTPS-port writeback only fires when the HTTPS connector is
        // enabled — mirrors the existing port-value writeback gate. With
        // HTTPS disabled, both the port mutation and the URL rewrite stay
        // out. A user with a stored https-shaped URL keeps it as-is.
        TomcatRunConfiguration cfg = createConfig("HttpsDisabledNoRewrite");
        cfg.setHttpPort(8080);
        PortConfig target = cfg.getConfigData().getPortConfig();
        target.setHttpsEnabled(false);
        target.setHttps(8443);
        cfg.setBrowserUrl("https://localhost:8443/secure");

        PortConfig resolved = resolvedPorts(8080, 8005, 8444, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTPS port stays at the user's seed when the connector is disabled",
                8443, target.getHttps());
        assertEquals("Stored URL untouched because the HTTPS branch did not run",
                "https://localhost:8443/secure", cfg.getBrowserUrl());
    }

    public void testCustomLoopbackUrlIsNormalisedBackToAutoWhenPortRewriteMakesItMatch() {
        // Boundary: a stored URL that's "auto-shaped except for the stale port"
        // becomes auto after the rewrite. setBrowserUrl(...) normalises a
        // value that matches autoBrowserUrl() back to the empty stored form
        // so the single-source-of-truth invariant holds — every later read
        // of getBrowserUrl() recomputes from the live port.
        TomcatRunConfiguration cfg = createConfig("AutoShapedAfterRewrite");
        cfg.setHttpPort(8082);
        cfg.getConfigData().setContextPath("/app");
        // Custom-stored URL that becomes the auto form once port is rewritten to 8083.
        cfg.getConfigData().getBrowserConfig().setBrowserUrl("http://localhost:8082/app");

        PortConfig resolved = resolvedPorts(8083, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("getBrowserUrl must reflect the resolved port",
                "http://localhost:8083/app", cfg.getBrowserUrl());
        assertEquals("rewritten value matching auto-form must be normalised to empty stored",
                "", cfg.getConfigData().getBrowserConfig().getUrl());
    }

    public void testCustomProxyUrlPreservedAtWriteback() {
        // User pointed at a reverse proxy / port-forward — deliberate. The
        // writeback-time rewrite must not touch non-loopback URLs even when
        // the HTTP port changes. (The runtime safety net in the launch path
        // also leaves these alone — pinned in testCustomProxyUrlNotRewritten
        // AtRuntime below.)
        TomcatRunConfiguration cfg = createConfig("ProxyPreservedOnWriteback");
        cfg.setHttpPort(8082);
        cfg.setBrowserUrl("http://proxy.example.com:9090/route");

        PortConfig resolved = resolvedPorts(8083, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("HTTP port writeback still applies",
                Integer.valueOf(8083), cfg.getHttpPort());
        assertEquals("non-loopback URL must survive writeback untouched",
                "http://proxy.example.com:9090/route", cfg.getBrowserUrl());
    }

    public void testWritebackWithUnchangedHttpPortLeavesUrlAlone() {
        // Idempotency for URL rewrite: when the resolved HTTP port matches
        // the configured value, the stored URL stays exactly as stored, even
        // if it happens to be loopback. The rewrite is gated on port change,
        // not on URL shape.
        TomcatRunConfiguration cfg = createConfig("UrlIdempotent");
        cfg.setHttpPort(8083);
        cfg.getConfigData().setContextPath("/app");
        cfg.setBrowserUrl("http://localhost:8083/app/deep/page");

        PortConfig resolved = resolvedPorts(8083, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("stored URL must be untouched when the resolved port matches",
                "http://localhost:8083/app/deep/page", cfg.getBrowserUrl());
    }

    public void testWritebackSkippedInParallelLeavesStoredUrlAlone() {
        // Parallel-run mode skips port writeback (seed-preservation), so the
        // stored URL also stays at the seed value. The launch-time rewrite
        // path handles per-instance port bridging — already pinned in
        // testParallelRunAutoUrlRewrittenAtRuntime.
        TomcatRunConfiguration cfg = createConfig("ParallelUrlSkip");
        cfg.setHttpPort(8082);
        cfg.getConfigData().setContextPath("/connect");
        cfg.setAllowMultipleInstances(true);
        cfg.setBrowserUrl("http://localhost:8082/connect/common/login");

        PortConfig resolved = resolvedPorts(8083, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        assertEquals("parallel-run skip applies to URL rewrite as well",
                "http://localhost:8082/connect/common/login", cfg.getBrowserUrl());
    }

    public void testCustomProxyUrlNotRewrittenAtRuntime() {
        // A user pointing their browser URL at a reverse proxy, CDN, or port-forward
        // chose that port deliberately. Even at launch time the rewrite must NOT
        // touch it — the safety net is for localhost URLs only.
        TomcatRunConfiguration cfg = createConfig("ProxyUrlPreserved");
        cfg.setHttpPort(8083);
        cfg.setBrowserUrl("http://proxy.example.com:9090/route");

        PortConfig resolved = resolvedPorts(8087, 8009, 8443, 1099, 8009);
        LaunchPortClaimer.writeBackResolvedPorts(cfg, resolved);

        // Config-level: custom URL preserved verbatim (single-source-of-truth contract).
        assertEquals("http://proxy.example.com:9090/route", cfg.getBrowserUrl());

        // Runtime: rewrite must pass through unchanged because the host is not loopback.
        String launched = TomcatProcessHandler.rewritePortIfNeeded(cfg.getBrowserUrl(), 8087);
        assertEquals("custom proxy URL must survive the runtime rewrite untouched",
                "http://proxy.example.com:9090/route", launched);
    }
}
