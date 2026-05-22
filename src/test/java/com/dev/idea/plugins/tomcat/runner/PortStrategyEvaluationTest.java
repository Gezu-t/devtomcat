package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.PortStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the STRICT / RECLAIM_THEN_FAIL refusal contract independently of the
 * full {@link LaunchPortClaimer#claim()} pipeline. Covers the bug where the
 * strategy check used to run before the {@code TomcatPortRegistry} could bump
 * the port — a registry-mediated bump would silently bypass STRICT.
 *
 * <p>The fix moves {@code enforcePortStrategy} to after {@code claimAndTrack},
 * so {@code resolved} reflects both detector AND registry bumps. These tests
 * exercise the pure logic at that single decision point.
 */
@DisplayName("LaunchPortClaimer.evaluatePortStrategy")
class PortStrategyEvaluationTest {

    private static PortConfig seed(int http, int shutdown, PortStrategy strategy) {
        PortConfig pc = new PortConfig();
        pc.setHttp(http);
        pc.setShutdown(shutdown);
        pc.setStrategy(strategy);
        return pc;
    }

    private static PortConfig resolved(int http, int shutdown) {
        PortConfig pc = new PortConfig();
        pc.setHttp(http);
        pc.setShutdown(shutdown);
        return pc;
    }

    @Test
    @DisplayName("AUTO_BUMP always permits launch even when port shifted")
    void autoBumpAllowsBump() {
        PortConfig s = seed(8081, 8005, PortStrategy.AUTO_BUMP);
        PortConfig r = resolved(8082, 8006);
        assertNull(LaunchPortClaimer.evaluatePortStrategy(s, r),
                "AUTO_BUMP must always permit launch");
    }

    @Test
    @DisplayName("STRICT permits launch when neither port shifted")
    void strictAllowsWhenNothingBumped() {
        PortConfig s = seed(8081, 8005, PortStrategy.STRICT);
        PortConfig r = resolved(8081, 8005);
        assertNull(LaunchPortClaimer.evaluatePortStrategy(s, r));
    }

    @Test
    @DisplayName("STRICT refuses when HTTP was bumped (detector OR registry)")
    void strictRefusesHttpBump() {
        PortConfig s = seed(8081, 8005, PortStrategy.STRICT);
        PortConfig r = resolved(8082, 8005);
        String msg = LaunchPortClaimer.evaluatePortStrategy(s, r);
        assertNotNull(msg, "STRICT must refuse on HTTP bump");
        assertTrue(msg.contains("HTTP 8081 is busy"), msg);
        assertTrue(msg.contains("STRICT"), msg);
    }

    @Test
    @DisplayName("STRICT refuses when shutdown port was bumped")
    void strictRefusesShutdownBump() {
        PortConfig s = seed(8081, 8005, PortStrategy.STRICT);
        PortConfig r = resolved(8081, 8006);
        String msg = LaunchPortClaimer.evaluatePortStrategy(s, r);
        assertNotNull(msg);
        assertTrue(msg.contains("shutdown 8005 is busy"), msg);
    }

    @Test
    @DisplayName("STRICT reports both bumps in one message when both shifted")
    void strictReportsBothBumps() {
        PortConfig s = seed(8081, 8005, PortStrategy.STRICT);
        PortConfig r = resolved(8082, 8006);
        String msg = LaunchPortClaimer.evaluatePortStrategy(s, r);
        assertNotNull(msg);
        assertTrue(msg.contains("HTTP 8081 is busy"), msg);
        assertTrue(msg.contains("shutdown 8005 is busy"), msg);
    }

    @Test
    @DisplayName("RECLAIM_THEN_FAIL refuses on bump the same way STRICT does")
    void reclaimThenFailRefusesBump() {
        PortConfig s = seed(8081, 8005, PortStrategy.RECLAIM_THEN_FAIL);
        PortConfig r = resolved(8082, 8005);
        String msg = LaunchPortClaimer.evaluatePortStrategy(s, r);
        assertNotNull(msg);
        assertTrue(msg.contains("RECLAIM_THEN_FAIL"), msg);
    }

    @Test
    @DisplayName("registry-mediated bump scenario: detector kept port but registry shifted it")
    void registryBumpAlsoRefusedUnderStrict() {
        // Simulates the exact bug the fix targets: detector said 8081 is free,
        // so resolved came back at 8081. Then claimAndTrack consulted
        // TomcatPortRegistry which had 8081 already claimed by a sibling
        // DevTomcat config, so the registry bumped to 8082 and updated rp.
        // After moving enforcePortStrategy to AFTER claimAndTrack, seed (8081)
        // vs resolved (8082) shows the bump and STRICT refuses.
        PortConfig s = seed(8081, 8005, PortStrategy.STRICT);
        PortConfig r = resolved(8082, 8005); // mutated by claimAndTrack
        assertNotNull(LaunchPortClaimer.evaluatePortStrategy(s, r),
                "STRICT must refuse the registry-mediated bump");
    }
}
