package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatPortRegistry")
class TomcatPortRegistryTest {

    // Direct instantiation bypasses ApplicationManager — no platform context needed.
    private TomcatPortRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TomcatPortRegistry();
    }

    // =========================================================================
    // claimPort — basic behaviour
    // =========================================================================

    @Nested
    @DisplayName("claimPort basic behaviour")
    class ClaimPortBasic {

        @Test
        @DisplayName("claims a high ephemeral port that is available")
        void claimsAvailablePort() {
            // Port 59200 is very unlikely to be in use on CI or developer machines.
            int claimed = registry.claimPort(59200, "cfgA");
            assertTrue(claimed >= 59200,
                    "Should claim the requested port or a nearby one, got: " + claimed);
            assertEquals(1, registry.claimedCount());
        }

        @Test
        @DisplayName("two configs cannot share the same port")
        void twoConfigsDifferentPorts() {
            int portA = registry.claimPort(59210, "cfgA");
            int portB = registry.claimPort(59210, "cfgB");

            assertTrue(portA > 0, "cfgA should get a valid port");
            assertTrue(portB > 0, "cfgB should get a valid port");
            assertNotEquals(portA, portB, "Two configs must not share the same port");
        }
    }

    // =========================================================================
    // claimPort — exhaustion returns -1
    // =========================================================================

    @Nested
    @DisplayName("claimPort exhaustion")
    class ClaimPortExhaustion {

        /**
         * Regression test for the bug where an exhausted search range returned the
         * original (unclaimed) requested port instead of -1.
         *
         * <p>Strategy: pre-claim every port in the 100-port search window
         * (requestedPort … requestedPort + MAX_SEARCH_RANGE) for a "filler" config,
         * then attempt to claim the start port for a second config. All 101 ports are
         * already in the registry, so the search exhausts and must return -1.
         */
        @Test
        @DisplayName("returns -1 when all ports in search range are already claimed")
        void returnsMinusOneWhenRangeFull() {
            // MAX_SEARCH_RANGE is 100, so the window is [startPort, startPort + 100].
            // Use high ephemeral ports that are highly unlikely to be bound by the OS,
            // so the only failure reason is the in-registry occupancy check.
            final int startPort = 59300;
            final int maxSearchRange = 100; // mirrors TomcatPortRegistry.MAX_SEARCH_RANGE

            // Fill the entire search window with "filler" config claims.
            for (int port = startPort; port <= startPort + maxSearchRange; port++) {
                registry.claimPort(port, "filler");
            }

            // Now the window is fully claimed. A second config requesting startPort
            // must receive -1 — not the unclaimed startPort.
            int result = registry.claimPort(startPort, "cfgExhausted");

            assertEquals(-1, result,
                    "claimPort() must return -1 when the entire search range is occupied; " +
                    "returning the requested port would be incorrect because it is not in the registry");
        }
    }

    // =========================================================================
    // releasePort
    // =========================================================================

    @Nested
    @DisplayName("releasePort")
    class ReleasePort {

        @Test
        @DisplayName("released port becomes reclaimable")
        void releasedPortIsReclaimable() {
            int first = registry.claimPort(59400, "cfgA");
            assertTrue(first > 0);

            registry.releasePort(first);
            assertEquals(0, registry.claimedCount());

            int second = registry.claimPort(first, "cfgB");
            assertEquals(first, second, "Released port should be available again");
        }

        @Test
        @DisplayName("releasing an unclaimed port is a no-op")
        void releaseUnclaimed_noOp() {
            assertDoesNotThrow(() -> registry.releasePort(59410));
            assertEquals(0, registry.claimedCount());
        }
    }

    // =========================================================================
    // releaseAllFor
    // =========================================================================

    @Nested
    @DisplayName("releaseAllFor")
    class ReleaseAllFor {

        @Test
        @DisplayName("releases only ports owned by the named config")
        void releasesOnlyOwnedPorts() {
            int portA1 = registry.claimPort(59500, "cfgA");
            int portA2 = registry.claimPort(59501, "cfgA");
            int portB  = registry.claimPort(59502, "cfgB");

            assertTrue(portA1 > 0);
            assertTrue(portA2 > 0);
            assertTrue(portB > 0);

            registry.releaseAllFor("cfgA");

            assertEquals(1, registry.claimedCount(),
                    "Only cfgB's port should remain after releasing cfgA");
        }

        @Test
        @DisplayName("releasing unknown config name is a no-op")
        void releaseUnknownConfig_noOp() {
            registry.claimPort(59510, "cfgA");
            registry.releaseAllFor("nonExistent");
            assertEquals(1, registry.claimedCount());
        }
    }

    // =========================================================================
    // claimAll
    // =========================================================================

    @Nested
    @DisplayName("claimAll")
    class ClaimAll {

        @Test
        @DisplayName("claims all requested ports in order")
        void claimsAllPorts() {
            List<Integer> claimed = registry.claimAll(List.of(59600, 59601, 59602), "cfgA");

            assertEquals(3, claimed.size());
            assertEquals(3, registry.claimedCount());
            // Each returned port must be within valid range and distinct
            long distinct = claimed.stream().filter(p -> p > 0).distinct().count();
            assertEquals(3, distinct, "All claimed ports must be distinct and valid");
        }

        @Test
        @DisplayName("intra-configuration collision is prevented: same port requested twice yields two different ports")
        void preventsIntraConfigCollision() {
            List<Integer> claimed = registry.claimAll(List.of(59610, 59610), "cfgA");

            assertEquals(2, claimed.size());
            assertNotEquals(claimed.get(0), claimed.get(1),
                    "Requesting the same port twice in one claimAll must yield two distinct ports");
        }
    }
}
