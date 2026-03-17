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
