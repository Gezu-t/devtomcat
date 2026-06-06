package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the ratchet-break seed logic: single-instance launches must resolve ports
 * from the user's PREFERRED intent, not the possibly-bumped current value, so a
 * port that drifted upward on a transient conflict heals back down once the
 * conflict clears instead of climbing every launch.
 */
@DisplayName("LaunchPortClaimer.seedFromPreferred")
class LaunchPortClaimerSeedTest {

    @Test
    @DisplayName("no drift: seed equals the configured ports")
    void noDrift() {
        PortConfig clean = new PortConfig(); // http=8080, shutdown=8005, no snapshot
        PortConfig seed = LaunchPortClaimer.seedFromPreferred(clean);
        assertEquals(8080, seed.getHttp());
        assertEquals(8005, seed.getShutdown());
    }

    @Test
    @DisplayName("after a persisted bump, seed heals back to the preferred ports")
    void healsBackToPreferred() {
        PortConfig drifted = new PortConfig();
        drifted.setHttpResolved(8083);     // 8080 -> 8083, snapshots preferredHttp=8080
        drifted.setShutdownResolved(8007); // 8005 -> 8007, snapshots preferredShutdown=8005

        PortConfig seed = LaunchPortClaimer.seedFromPreferred(drifted);

        assertEquals(8080, seed.getHttp(), "seed must heal HTTP back to preferred");
        assertEquals(8005, seed.getShutdown(), "seed must heal shutdown back to preferred");
        // The original config is not mutated by seeding.
        assertEquals(8083, drifted.getHttp());
        assertEquals(8007, drifted.getShutdown());
    }

    @Test
    @DisplayName("an explicit user change becomes the new intent (no heal to the old value)")
    void explicitUserChangeIsNewIntent() {
        PortConfig cfg = new PortConfig();
        cfg.setHttpResolved(8083); // drift first (preferredHttp snapshots 8080)
        cfg.setHttp(9000);         // user explicitly picks 9000 -> clears the snapshot

        PortConfig seed = LaunchPortClaimer.seedFromPreferred(cfg);

        assertEquals(9000, seed.getHttp(), "explicit user value is the intent to seed from");
    }
}
