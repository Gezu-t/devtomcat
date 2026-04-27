package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PortResolver")
class PortResolverTest {

    @Test
    @DisplayName("pre-resolved ports are used unchanged")
    void preResolvedPortsUsedUnchanged() throws Exception {
        PortConfig resolved = new PortConfig();
        resolved.setHttp(62080);
        resolved.setShutdown(62005);

        PortConfig result = new PortResolver(
                configuration(61080, 61005, null, null, null, false, false, false),
                resolved,
                null
        ).resolve();

        assertSame(resolved, result);
    }

    @Test
    @DisplayName("internal port conflicts are auto-resolved")
    void internalPortConflictsAutoResolved() throws Exception {
        PortConfig result = new PortResolver(
                configuration(61080, 61080, null, null, null, false, false, false),
                null,
                null
        ).resolve();

        assertEquals(61080, result.getHttp());
        assertNotEquals(61080, result.getShutdown());
        assertTrue(result.getShutdown() > 0);
    }

    @Test
    @DisplayName("externally busy port is auto-resolved")
    void externallyBusyPortAutoResolved() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            int busyPort = socket.getLocalPort();

            PortConfig result = new PortResolver(
                    configuration(busyPort, 61005, null, null, null, false, false, false),
                    null,
                    null
            ).resolve();

            assertNotEquals(busyPort, result.getHttp());
            assertEquals(61005, result.getShutdown());
            assertTrue(result.getHttp() > 0);
        }
    }

    @Test
    @DisplayName("JMX port resolved when enabled")
    void jmxPortResolvedWhenEnabled() throws Exception {
        PortConfig result = new PortResolver(
                configuration(61080, 61005, 61099, null, null, true, false, false),
                null,
                null
        ).resolve();

        assertEquals(61099, result.getJmx());
    }

    @Test
    @DisplayName("HTTPS port resolved when enabled")
    void httpsPortResolvedWhenEnabled() throws Exception {
        PortConfig result = new PortResolver(
                configuration(61080, 61005, null, 61443, null, false, true, false),
                null,
                null
        ).resolve();

        assertEquals(61443, result.getHttps());
    }

    @Test
    @DisplayName("AJP port resolved when enabled")
    void ajpPortResolvedWhenEnabled() throws Exception {
        PortConfig result = new PortResolver(
                configuration(61080, 61005, null, null, 61009, false, false, true),
                null,
                null
        ).resolve();

        assertEquals(61009, result.getAjp());
    }

    @Test
    @DisplayName("disabled optional ports get default values without conflict check")
    void disabledOptionalPortsGetDefaults() throws Exception {
        PortConfig result = new PortResolver(
                configuration(61080, 61005, null, null, null, false, false, false),
                null,
                null
        ).resolve();

        // Ports are resolved to defaults even when disabled — they just
        // won't be used in VM options or server.xml connectors.
        assertTrue(result.getJmx() > 0);
        assertTrue(result.getHttps() > 0);
        assertTrue(result.getAjp() > 0);
    }

    private static TomcatRunConfiguration configuration(Integer httpPort,
                                                        Integer shutdownPort,
                                                        Integer jmxPort,
                                                        Integer httpsPort,
                                                        Integer ajpPort,
                                                        boolean jmxEnabled,
                                                        boolean httpsEnabled,
                                                        boolean ajpEnabled) throws IOException {
        TomcatRunConfiguration configuration = mock(TomcatRunConfiguration.class);
        when(configuration.getProject()).thenReturn(mock(Project.class));
        when(configuration.getHttpPort()).thenReturn(httpPort);
        when(configuration.getShutdownPort()).thenReturn(shutdownPort);
        when(configuration.getJmxPort()).thenReturn(jmxPort);
        when(configuration.getHttpsPort()).thenReturn(httpsPort);
        when(configuration.getAjpPort()).thenReturn(ajpPort);
        when(configuration.isJmxEnabled()).thenReturn(jmxEnabled);
        when(configuration.isHttpsEnabled()).thenReturn(httpsEnabled);
        when(configuration.isAjpEnabled()).thenReturn(ajpEnabled);
        return configuration;
    }
}
