package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PortConflictDetector")
class PortConflictDetectorTest {

    @Nested
    @DisplayName("Port availability checks")
    class PortAvailability {

        @Test
        @DisplayName("available port returns true")
        void availablePort() {
            // Port 0 is special — the OS assigns a random available port
            // We use a high port that's unlikely to be in use
            int testPort = PortConflictDetector.findNextAvailable(49152);
            if (testPort > 0) {
                assertTrue(PortConflictDetector.isPortAvailable(testPort));
            }
        }

        @Test
        @DisplayName("invalid port returns false")
        void invalidPort() {
            assertFalse(PortConflictDetector.isPortAvailable(-1));
            assertFalse(PortConflictDetector.isPortAvailable(0));
            assertFalse(PortConflictDetector.isPortAvailable(70000));
        }

        @Test
        @DisplayName("actively-bound port returns false; idle closed port returns true")
        void activeListenerReportedUnavailable() throws IOException {
            // Guards the invariant that SO_REUSEADDR in tryBind does not hide
            // genuine conflicts: a port with an active listener must still be
            // reported as unavailable. Also verifies that a port closed without
            // ever accepting a connection (no TIME_WAIT) is immediately free.
            // Note: this does not simulate a real TIME_WAIT socket — that state
            // requires a completed TCP handshake and cannot be created reliably
            // in a unit test without raw socket access.
            int port;
            try (ServerSocket occupying = boundWithReuse(0)) {
                port = occupying.getLocalPort();
                assertFalse(PortConflictDetector.isPortAvailable(port),
                        "probe must detect an actively-bound port as unavailable");
            }
            assertTrue(PortConflictDetector.isPortAvailable(port),
                    "probe must see a just-closed idle port as available");
        }

        @Test
        @DisplayName("probe does not leak TIME_WAIT state between calls")
        void probeIsSafeToCallRepeatedly() throws IOException {
            // The probe binds briefly and closes without ever accepting a
            // connection, so no TIME_WAIT is created. Back-to-back probes on
            // the same free port must all return true. A regression here
            // would mean the probe's own cycling creates false negatives.
            int port;
            try (ServerSocket reserved = boundWithReuse(0)) {
                port = reserved.getLocalPort();
            }
            for (int i = 0; i < 5; i++) {
                assertTrue(PortConflictDetector.isPortAvailable(port),
                        "iteration " + i + ": probe must report free port as available");
            }
        }

        private static ServerSocket boundWithReuse(int port) throws IOException {
            // Matches PortConflictDetector.tryBind's binding style: set
            // SO_REUSEADDR before bind so tests don't accidentally leave
            // ports unreachable to subsequent runs.
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(port));
            return s;
        }
    }

    @Nested
    @DisplayName("Conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("no conflicts with default ports on clean system")
        void noConflictsDefaultPorts() {
            PortConfig config = new PortConfig();
            // Use high ports unlikely to be in use
            config.setHttp(59080);
            config.setShutdown(59005);
            List<PortConflictDetector.PortConflict> conflicts =
                    PortConflictDetector.detectConflicts(config);
            assertTrue(conflicts.isEmpty(),
                    "Expected no conflicts on high ports, got: " + conflicts);
        }

        @Test
        @DisplayName("disabled HTTPS/JMX/AJP ports are not checked")
        void disabledPortsNotChecked() {
            PortConfig config = new PortConfig();
            config.setHttp(59081);
            config.setShutdown(59006);
            config.setHttpsEnabled(false);
            config.setHttps(1); // Would conflict if checked
            config.setJmxEnabled(false);
            config.setAjpEnabled(false);
            List<PortConflictDetector.PortConflict> conflicts =
                    PortConflictDetector.detectConflicts(config);
            assertTrue(conflicts.isEmpty());
        }

        @Test
        @DisplayName("enabled HTTPS port is checked")
        void enabledHttpsIsChecked() {
            PortConfig config = new PortConfig();
            config.setHttp(59082);
            config.setShutdown(59007);
            config.setHttpsEnabled(true);
            config.setHttps(59443);
            // Should not error — just verify it runs without exception
            List<PortConflictDetector.PortConflict> conflicts =
                    PortConflictDetector.detectConflicts(config);
            assertNotNull(conflicts);
        }
    }

    @Nested
    @DisplayName("Conflict message formatting")
    class MessageFormatting {

        @Test
        @DisplayName("empty conflicts produce empty message")
        void emptyMessage() {
            assertEquals("", PortConflictDetector.formatConflictMessage(List.of()));
        }

        @Test
        @DisplayName("single conflict formats correctly")
        void singleConflict() {
            var conflict = new PortConflictDetector.PortConflict("HTTP", 8080, "Try port 8081");
            String msg = PortConflictDetector.formatConflictMessage(List.of(conflict));
            assertTrue(msg.contains("HTTP"));
            assertTrue(msg.contains("8080"));
            assertTrue(msg.contains("Try port 8081"));
            assertFalse(msg.contains("conflicts")); // singular
        }

        @Test
        @DisplayName("multiple conflicts use plural")
        void multipleConflicts() {
            var c1 = new PortConflictDetector.PortConflict("HTTP", 8080, "Try 8081");
            var c2 = new PortConflictDetector.PortConflict("Shutdown", 8005, "Try 8006");
            String msg = PortConflictDetector.formatConflictMessage(List.of(c1, c2));
            assertTrue(msg.contains("conflicts")); // plural
        }
    }

    @Nested
    @DisplayName("hasConflicts convenience method")
    class HasConflicts {

        @Test
        @DisplayName("returns false when no conflicts")
        void noConflicts() {
            PortConfig config = new PortConfig();
            config.setHttp(59083);
            config.setShutdown(59008);
            assertFalse(PortConflictDetector.hasConflicts(config));
        }
    }

    @Nested
    @DisplayName("findNextAvailable")
    class FindNext {

        @Test
        @DisplayName("finds available port after start port")
        void findsNextPort() {
            int next = PortConflictDetector.findNextAvailable(49152);
            assertTrue(next > 49152 || next == -1,
                    "Should find a port > 49152 or return -1");
        }
    }

    @Nested
    @DisplayName("Debug port resolution")
    class DebugPortResolution {

        @Test
        @DisplayName("first alternate already taken by peer service does not abort the search")
        void peerAllocationDoesNotAbortSearch() throws IOException {
            // Regression: findNextAvailable was unaware of the per-resolution
            // allocated set, so when the first alternate (e.g. 8081) was already
            // claimed by Shutdown, resolvePort gave up with "no alternative found"
            // even though 8082, 8083, ... were free. The fix is a peer-aware
            // findNextAvailableExcluding inside resolvePort.
            try (ServerSocket occupied = new ServerSocket(0)) {
                int busyHttp = occupied.getLocalPort();
                // Pick a Shutdown port at busyHttp+1 so the naive search would
                // return it and trip on the allocated-set check.
                int peerShutdown = busyHttp + 1;

                PortConfig config = new PortConfig();
                config.setHttp(busyHttp);
                config.setShutdown(peerShutdown);

                PortConflictDetector.PortResolution resolution =
                        PortConflictDetector.resolveConflicts(config);

                assertNotEquals(busyHttp, resolution.getResolvedConfig().getHttp(),
                        "HTTP must move off the externally-bound port");
                assertNotEquals(peerShutdown, resolution.getResolvedConfig().getHttp(),
                        "HTTP must not collide with the peer's Shutdown allocation");
                assertEquals(peerShutdown, resolution.getResolvedConfig().getShutdown(),
                        "Shutdown should keep its original port");
                assertTrue(resolution.getChanges().stream().anyMatch(c ->
                                c.contains("HTTP") && c.contains("resolved to")),
                        "Expected HTTP resolution to include a 'resolved to N' message, not 'no alternative found': "
                                + resolution.getChanges());
            }
        }

        @Test
        @DisplayName("busy JDWP port is auto-resolved away from configured port")
        void busyDebugPortIsAutoResolved() throws IOException {
            PortConfig config = new PortConfig();
            config.setHttp(59084);
            config.setShutdown(59009);

            try (ServerSocket occupied = new ServerSocket(0)) {
                int busyPort = occupied.getLocalPort();
                PortConflictDetector.DebugPortResolution resolution =
                        PortConflictDetector.resolveConflictsWithDebug(config, busyPort);

                assertEquals(59084, resolution.getResolvedConfig().getHttp());
                assertEquals(59009, resolution.getResolvedConfig().getShutdown());
                assertNotEquals(busyPort, resolution.getDebugPort());
                assertTrue(resolution.getDebugPort() > 0,
                        "Resolved debug port should move off the busy configured port");
                assertTrue(resolution.getChanges().stream().anyMatch(change ->
                                change.contains("Debug (JDWP)") && change.contains(String.valueOf(busyPort))),
                        "Expected conflict message mentioning the original JDWP port");
            }
        }
    }
}
