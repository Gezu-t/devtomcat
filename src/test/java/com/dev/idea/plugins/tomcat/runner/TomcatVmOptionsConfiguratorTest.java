package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.projectRoots.Sdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TomcatVmOptionsConfigurator")
class TomcatVmOptionsConfiguratorTest {

    @Test
    @DisplayName("configure adds user options optional properties and module opens on JDK 9+")
    void configureAddsExpectedOptionsOnModernJdk() {
        ParametersList vmParams = new ParametersList();
        PortConfig ports = ports();
        Sdk jdk = mock(Sdk.class);
        when(jdk.getVersionString()).thenReturn("17.0.10");

        TomcatVmOptionsConfigurator.configure(
                vmParams,
                "-Xmx512m -Dcustom=true",
                ports,
                true,
                true,
                Path.of("/tmp/devtomcat/base"),
                Path.of("/tmp/devtomcat/home"),
                jdk
        );

        assertTrue(vmParams.hasParameter("-Xmx512m"));
        assertEquals("true", vmParams.getPropertyValue("custom"));
        assertEquals("9009", vmParams.getPropertyValue("com.sun.management.jmxremote.port"));
        assertEquals("9443", vmParams.getPropertyValue("tomcat.https.port"));
        assertEquals("/tmp/devtomcat/home", vmParams.getPropertyValue("catalina.home"));
        assertEquals("8080", vmParams.getPropertyValue("server.port"));
        assertTrue(vmParams.getList().contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
    }

    @Test
    @DisplayName("configure omits optional flags on JDK 8 when features are disabled")
    void configureOmitsOptionalFlagsOnLegacyJdk() {
        ParametersList vmParams = new ParametersList();
        PortConfig ports = ports();
        Sdk jdk = mock(Sdk.class);
        when(jdk.getVersionString()).thenReturn("1.8.0_402");

        TomcatVmOptionsConfigurator.configure(
                vmParams,
                null,
                ports,
                false,
                false,
                Path.of("/tmp/devtomcat/base"),
                Path.of("/tmp/devtomcat/home"),
                jdk
        );

        assertFalse(vmParams.hasProperty("com.sun.management.jmxremote.port"));
        assertFalse(vmParams.hasProperty("tomcat.https.port"));
        assertFalse(vmParams.getList().stream().anyMatch(option -> option.startsWith("--add-opens=")));
        assertEquals("/tmp/devtomcat/base", vmParams.getPropertyValue("catalina.base"));
    }

    private static PortConfig ports() {
        PortConfig ports = new PortConfig();
        ports.setHttp(8080);
        ports.setShutdown(8005);
        ports.setJmx(9009);
        ports.setHttps(9443);
        return ports;
    }
}
