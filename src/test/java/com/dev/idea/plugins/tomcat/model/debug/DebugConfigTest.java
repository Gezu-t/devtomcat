package com.dev.idea.plugins.tomcat.model.debug;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DebugConfig")
class DebugConfigTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("default port is 5005")
        void defaultPort() {
            assertEquals(5005, new DebugConfig().getPort());
            assertEquals(5005, DebugConfig.DEFAULT_DEBUG_PORT);
        }

        @Test
        @DisplayName("default transport is Socket")
        void defaultTransport() {
            assertEquals(TomcatConstants.TRANSPORT_SOCKET, new DebugConfig().getTransport());
        }

        @Test
        @DisplayName("useModuleClasspath false by default")
        void defaultModuleClasspath() {
            assertFalse(new DebugConfig().isUseModuleClasspath());
        }
    }

    @Nested
    @DisplayName("3-arg constructor")
    class ThreeArgConstructor {

        @Test
        @DisplayName("sets all fields")
        void setsAll() {
            DebugConfig config = new DebugConfig(8000, TomcatConstants.TRANSPORT_SHARED_MEMORY, true);
            assertEquals(8000, config.getPort());
            assertEquals(TomcatConstants.TRANSPORT_SHARED_MEMORY, config.getTransport());
            assertTrue(config.isUseModuleClasspath());
        }
    }

    @Nested
    @DisplayName("setPort validation")
    class SetPort {

        @Test
        @DisplayName("valid port is accepted")
        void validPortAccepted() {
            DebugConfig config = new DebugConfig();
            config.setPort(8000);
            assertEquals(8000, config.getPort());
        }

        @Test
        @DisplayName("port below 1024 falls back to default")
        void belowMinFallsBack() {
            DebugConfig config = new DebugConfig();
            config.setPort(500);
            assertEquals(DebugConfig.DEFAULT_DEBUG_PORT, config.getPort());
        }

        @Test
        @DisplayName("port above 65535 falls back to default")
        void aboveMaxFallsBack() {
            DebugConfig config = new DebugConfig();
            config.setPort(70000);
            assertEquals(DebugConfig.DEFAULT_DEBUG_PORT, config.getPort());
        }

        @Test
        @DisplayName("port 1024 is accepted (boundary)")
        void minBoundary() {
            DebugConfig config = new DebugConfig();
            config.setPort(1024);
            assertEquals(1024, config.getPort());
        }

        @Test
        @DisplayName("port 65535 is accepted (boundary)")
        void maxBoundary() {
            DebugConfig config = new DebugConfig();
            config.setPort(65535);
            assertEquals(65535, config.getPort());
        }
    }

    @Nested
    @DisplayName("setTransport validation")
    class SetTransport {

        @Test
        @DisplayName("Socket accepted")
        void socketAccepted() {
            DebugConfig config = new DebugConfig();
            config.setTransport(TomcatConstants.TRANSPORT_SOCKET);
            assertEquals(TomcatConstants.TRANSPORT_SOCKET, config.getTransport());
        }

        @Test
        @DisplayName("Shared Memory accepted")
        void sharedMemoryAccepted() {
            DebugConfig config = new DebugConfig();
            config.setTransport(TomcatConstants.TRANSPORT_SHARED_MEMORY);
            assertEquals(TomcatConstants.TRANSPORT_SHARED_MEMORY, config.getTransport());
        }

        @Test
        @DisplayName("invalid transport falls back to Socket")
        void invalidFallsBack() {
            DebugConfig config = new DebugConfig();
            config.setTransport("invalid_transport");
            assertEquals(TomcatConstants.TRANSPORT_SOCKET, config.getTransport());
        }

        @Test
        @DisplayName("case-insensitive matching")
        void caseInsensitive() {
            DebugConfig config = new DebugConfig();
            config.setTransport("socket");
            assertEquals("socket", config.getTransport());
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("default config is valid")
        void defaultIsValid() {
            assertTrue(new DebugConfig().isValid());
        }

        @Test
        @DisplayName("valid custom config")
        void validCustom() {
            DebugConfig config = new DebugConfig(8000, TomcatConstants.TRANSPORT_SOCKET, true);
            assertTrue(config.isValid());
        }
    }

    @Nested
    @DisplayName("JDWP strings")
    class JdwpStrings {

        @Test
        @DisplayName("getJdwpConnectionString for socket")
        void jdwpSocket() {
            DebugConfig config = new DebugConfig(5005, TomcatConstants.TRANSPORT_SOCKET, false);
            String jdwp = config.getJdwpConnectionString();
            assertTrue(jdwp.contains("dt_socket"));
            assertTrue(jdwp.contains("5005"));
            assertTrue(jdwp.contains("server=y"));
            assertTrue(jdwp.contains("suspend=n"));
        }

        @Test
        @DisplayName("getJdwpConnectionString for shared memory")
        void jdwpShmem() {
            DebugConfig config = new DebugConfig(5005, TomcatConstants.TRANSPORT_SHARED_MEMORY, false);
            String jdwp = config.getJdwpConnectionString();
            assertTrue(jdwp.contains("dt_shmem"));
        }

        @Test
        @DisplayName("getDebugVmArgument includes -agentlib prefix")
        void debugVmArg() {
            DebugConfig config = new DebugConfig();
            String vmArg = config.getDebugVmArgument();
            assertTrue(vmArg.startsWith("-agentlib:jdwp=transport="));
            assertTrue(vmArg.contains("dt_socket"));
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            DebugConfig original = new DebugConfig(8000, TomcatConstants.TRANSPORT_SOCKET, true);
            DebugConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            DebugConfig original = new DebugConfig();
            DebugConfig cloned = original.clone();
            cloned.setPort(9000);
            assertEquals(DebugConfig.DEFAULT_DEBUG_PORT, original.getPort());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same state is equal")
        void sameStateEqual() {
            DebugConfig a = new DebugConfig();
            DebugConfig b = new DebugConfig();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different port not equal")
        void differentPortNotEqual() {
            DebugConfig a = new DebugConfig();
            DebugConfig b = new DebugConfig();
            b.setPort(9000);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different transport not equal")
        void differentTransportNotEqual() {
            DebugConfig a = new DebugConfig();
            DebugConfig b = new DebugConfig();
            b.setTransport(TomcatConstants.TRANSPORT_SHARED_MEMORY);
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes port and transport")
        void includesFields() {
            DebugConfig config = new DebugConfig();
            String str = config.toString();
            assertTrue(str.contains("5005"));
            assertTrue(str.contains("Socket"));
        }
    }
}
