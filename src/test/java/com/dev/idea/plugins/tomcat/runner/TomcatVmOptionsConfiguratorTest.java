package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.projectRoots.Sdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TomcatVmOptionsConfigurator}.
 *
 * <p>The standalone-Tomcat port-property contract is enforced here: HTTP,
 * HTTPS, AJP, and shutdown ports are configured via {@code server.xml}
 * connectors (written by {@link ServerXmlMutator}), <b>not</b> via JVM
 * {@code -D} flags. The dead-property regression guards in
 * {@link DeadSpringBootPropertyRegressionGuards} ensure we never
 * reintroduce the misleading flags that the launcher previously emitted.
 */
@DisplayName("TomcatVmOptionsConfigurator")
class TomcatVmOptionsConfiguratorTest {

    private static PortConfig ports() {
        PortConfig p = new PortConfig();
        p.setHttp(8080);
        p.setShutdown(8005);
        p.setJmx(9009);
        p.setHttps(9443);
        return p;
    }

    private static Sdk jdk(String version) {
        Sdk jdk = mock(Sdk.class);
        when(jdk.getVersionString()).thenReturn(version);
        return jdk;
    }

    @Nested
    @DisplayName("legitimate Tomcat properties")
    class LegitimateTomcatProperties {

        @Test
        @DisplayName("sets catalina.home and catalina.base from the resolved paths")
        void setsCatalinaPaths() {
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertEquals("/tmp/devtomcat/home", vmParams.getPropertyValue("catalina.home"));
            assertEquals("/tmp/devtomcat/base", vmParams.getPropertyValue("catalina.base"));
        }

        @Test
        @DisplayName("sets java.io.tmpdir under catalina.base/temp")
        void setsTempDirUnderBase() {
            // Tomcat's internal temp area must live under catalina.base, not
            // the system tmp dir, so per-launch isolation works in parallel
            // mode. Pin the path composition rule.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertEquals(Path.of("/tmp/devtomcat/base/temp").toString(),
                    vmParams.getPropertyValue("java.io.tmpdir"));
        }

        @Test
        @DisplayName("sets java.util.logging.config.file to catalina.base/conf/logging.properties")
        void setsLoggingConfig() {
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertEquals(Path.of("/tmp/devtomcat/base/conf/logging.properties").toString(),
                    vmParams.getPropertyValue("java.util.logging.config.file"));
        }

        @Test
        @DisplayName("sets java.util.logging.manager to Tomcat's ClassLoaderLogManager")
        void setsLoggingManager() {
            // Tomcat needs its own log manager to handle per-webapp logging
            // namespaces. Without this property the JVM uses the default
            // and Tomcat's juli output gets garbled.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertEquals("org.apache.juli.ClassLoaderLogManager",
                    vmParams.getPropertyValue("java.util.logging.manager"));
        }
    }

    @Nested
    @DisplayName("user VM options + JMX")
    class UserOptionsAndJmx {

        @Test
        @DisplayName("user VM options are forwarded verbatim")
        void userOptionsAreForwarded() {
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, "-Xmx512m -Dcustom=true", ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertTrue(vmParams.hasParameter("-Xmx512m"));
            assertEquals("true", vmParams.getPropertyValue("custom"));
        }

        @Test
        @DisplayName("JMX flags are emitted only when JMX is enabled")
        void jmxFlagsGatedByEnabled() {
            ParametersList enabled = new ParametersList();
            ParametersList disabled = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    enabled, null, ports(), true,
                    Path.of("/tmp/devtomcat/base"), Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));
            TomcatVmOptionsConfigurator.configure(
                    disabled, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"), Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertEquals("9009", enabled.getPropertyValue("com.sun.management.jmxremote.port"));
            assertEquals("false", enabled.getPropertyValue("com.sun.management.jmxremote.ssl"));
            assertEquals("false", enabled.getPropertyValue("com.sun.management.jmxremote.authenticate"));
            assertEquals("false", enabled.getPropertyValue("com.sun.management.jmxremote.local.only"));
            assertFalse(disabled.hasProperty("com.sun.management.jmxremote.port"),
                    "JMX flags must not be emitted when disabled");
        }
    }

    @Nested
    @DisplayName("JPMS module-opens (JDK 9+)")
    class JpmsModuleOpens {

        @Test
        @DisplayName("module-opens are emitted on JDK 9+")
        void moduleOpensOnModernJdk() {
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertTrue(vmParams.getList().contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
            assertTrue(vmParams.getList().contains("--add-opens=java.base/java.util=ALL-UNNAMED"));
        }

        @Test
        @DisplayName("module-opens are NOT emitted on JDK 8")
        void noModuleOpensOnLegacyJdk() {
            // JDK 8 doesn't have the JPMS module system. Adding --add-opens
            // there causes "Unrecognized option" and the JVM refuses to start.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), false,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("1.8.0_402"));

            assertFalse(vmParams.getList().stream().anyMatch(opt -> opt.startsWith("--add-opens=")),
                    "module-opens must not be emitted on pre-JPMS JDKs");
        }
    }

    @Nested
    @DisplayName("dead Spring-Boot-property regression guards")
    class DeadSpringBootPropertyRegressionGuards {
        // These tests pin the post-fix contract: standalone Tomcat reads its
        // ports from server.xml, so the JVM properties below have no effect
        // and must NOT appear on the command line.
        //
        // The same policy is enforced in DynamicTomcatEnvironment.buildCatalinaOpts()
        // — see the matching DynamicTomcatEnvironmentTest. Two separate test
        // sites were drifting apart on this contract; both are now consistent.

        @Test
        @DisplayName("does not set -Dserver.port (Spring Boot — ignored by standalone Tomcat)")
        void serverPortNotSet() {
            // server.port is org.springframework.boot.autoconfigure.web.ServerProperties.
            // Setting it on a standalone Tomcat JVM is a confusing no-op.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), true,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertFalse(vmParams.hasProperty("server.port"),
                    "Spring Boot's server.port must not be set on the standalone Tomcat command line");
        }

        @Test
        @DisplayName("does not set -Dserver.shutdown.port (Spring Boot — ignored by standalone Tomcat)")
        void serverShutdownPortNotSet() {
            // server.shutdown.port is also Spring Boot. The actual shutdown
            // port comes from server.xml's <Server port="..."> attribute,
            // written by ServerXmlMutator.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), true,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertFalse(vmParams.hasProperty("server.shutdown.port"),
                    "Spring Boot's server.shutdown.port must not be set on the standalone Tomcat command line");
        }

        @Test
        @DisplayName("does not set -Dtomcat.https.port (no Tomcat release reads this)")
        void tomcatHttpsPortNotSet() {
            // Despite the "tomcat." prefix, no Tomcat release defines or
            // reads tomcat.https.port. The HTTPS port is configured via
            // server.xml's <Connector SSLEnabled="true" port="..."> element,
            // injected by ServerXmlMutator.setOrInjectHttpsConnector.
            ParametersList vmParams = new ParametersList();

            TomcatVmOptionsConfigurator.configure(
                    vmParams, null, ports(), true,
                    Path.of("/tmp/devtomcat/base"),
                    Path.of("/tmp/devtomcat/home"),
                    jdk("17.0.10"));

            assertFalse(vmParams.hasProperty("tomcat.https.port"),
                    "tomcat.https.port is a fictitious property and must not be set");
        }
    }
}
