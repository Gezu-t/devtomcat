package com.dev.idea.plugins.tomcat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConstants")
class TomcatConstantsTest {

    @Nested
    @DisplayName("Server modes")
    class ServerModes {

        @Test
        @DisplayName("local mode is 'Local'")
        void localMode() {
            assertEquals("Local", TomcatConstants.MODE_LOCAL);
        }

        @Test
        @DisplayName("remote mode is 'Remote'")
        void remoteMode() {
            assertEquals("Remote", TomcatConstants.MODE_REMOTE);
        }
    }

    @Nested
    @DisplayName("Run modes")
    class RunModes {

        @Test
        @DisplayName("run mode is 'Run'")
        void runMode() {
            assertEquals("Run", TomcatConstants.RUN_MODE);
        }

        @Test
        @DisplayName("debug mode is 'Debug'")
        void debugMode() {
            assertEquals("Debug", TomcatConstants.DEBUG_MODE);
        }

        @Test
        @DisplayName("coverage mode is 'Coverage'")
        void coverageMode() {
            assertEquals("Coverage", TomcatConstants.COVERAGE_MODE);
        }
    }

    @Nested
    @DisplayName("Network defaults")
    class NetworkDefaults {

        @Test
        @DisplayName("default host is localhost")
        void defaultHost() {
            assertEquals("localhost", TomcatConstants.DEFAULT_HOST);
        }

        @Test
        @DisplayName("default port string is 8080")
        void defaultPortString() {
            assertEquals("8080", TomcatConstants.DEFAULT_PORT);
        }

        @Test
        @DisplayName("default port number is 8080")
        void defaultPortNumber() {
            assertEquals(8080, TomcatConstants.DEFAULT_PORT_NUMBER);
        }

        @Test
        @DisplayName("port string and number are consistent")
        void portConsistency() {
            assertEquals(String.valueOf(TomcatConstants.DEFAULT_PORT_NUMBER), TomcatConstants.DEFAULT_PORT);
        }
    }

    @Nested
    @DisplayName("Catalina paths")
    class CatalinaPaths {

        @Test
        @DisplayName("server.xml path")
        void serverXml() {
            assertEquals("conf/server.xml", TomcatConstants.CONFIG_SERVER_XML);
        }

        @Test
        @DisplayName("context XML dir")
        void contextXmlDir() {
            assertEquals("conf/Catalina/localhost", TomcatConstants.CONTEXT_XML_DIR);
        }

        @Test
        @DisplayName("bootstrap jar path")
        void bootstrapJar() {
            assertEquals("bin/bootstrap.jar", TomcatConstants.JAR_BOOTSTRAP);
        }

        @Test
        @DisplayName("tomcat-juli jar path")
        void tomcatJuliJar() {
            assertEquals("bin/tomcat-juli.jar", TomcatConstants.JAR_TOMCAT_JULI);
        }
    }

    @Nested
    @DisplayName("CATALINA_BASE directories")
    class BaseDirs {

        @Test
        @DisplayName("all required dirs are non-empty")
        void allDirsNonEmpty() {
            assertFalse(TomcatConstants.DIR_CONF.isEmpty());
            assertFalse(TomcatConstants.DIR_TEMP.isEmpty());
            assertFalse(TomcatConstants.DIR_LOGS.isEmpty());
            assertFalse(TomcatConstants.DIR_WEBAPPS.isEmpty());
            assertFalse(TomcatConstants.DIR_WORK.isEmpty());
        }
    }

    @Nested
    @DisplayName("Deployment constants")
    class Deployment {

        @Test
        @DisplayName("ROOT context name")
        void rootContextName() {
            assertEquals("ROOT", TomcatConstants.ROOT_CONTEXT_NAME);
        }

        @Test
        @DisplayName("default context path is /")
        void defaultContextPath() {
            assertEquals("/", TomcatConstants.DEFAULT_CONTEXT_PATH);
        }

        @Test
        @DisplayName("WEB-INF path composition")
        void webInfPaths() {
            assertEquals("WEB-INF/classes", TomcatConstants.WEB_INF_CLASSES_PATH);
            assertEquals("WEB-INF/lib", TomcatConstants.WEB_INF_LIB_PATH);
        }
    }

    @Nested
    @DisplayName("JDWP constants")
    class Jdwp {

        @Test
        @DisplayName("socket transport identifier")
        void socketTransport() {
            assertEquals("dt_socket", TomcatConstants.JDWP_TRANSPORT_SOCKET);
        }

        @Test
        @DisplayName("shared memory transport identifier")
        void shmemTransport() {
            assertEquals("dt_shmem", TomcatConstants.JDWP_TRANSPORT_SHMEM);
        }

        @Test
        @DisplayName("connection format contains placeholders")
        void connectionFormat() {
            assertTrue(TomcatConstants.JDWP_CONNECTION_FORMAT.contains("%s"));
            assertTrue(TomcatConstants.JDWP_CONNECTION_FORMAT.contains("%d"));
        }

        @Test
        @DisplayName("agent prefix starts with -agentlib")
        void agentPrefix() {
            assertTrue(TomcatConstants.JDWP_AGENT_PREFIX.startsWith("-agentlib:jdwp"));
        }
    }

    @Nested
    @DisplayName("Artifact suffixes")
    class ArtifactSuffixes {

        @Test
        @DisplayName("war exploded suffix")
        void warExploded() {
            assertEquals(":war exploded", TomcatConstants.ARTIFACT_SUFFIX_WAR_EXPLODED);
        }

        @Test
        @DisplayName("war suffix")
        void war() {
            assertEquals(":war", TomcatConstants.ARTIFACT_SUFFIX_WAR);
        }

        @Test
        @DisplayName("ear exploded suffix")
        void earExploded() {
            assertEquals(":ear exploded", TomcatConstants.ARTIFACT_SUFFIX_EAR_EXPLODED);
        }
    }

    @Nested
    @DisplayName("Catalina commands")
    class CatalinaCommands {

        @Test
        @DisplayName("catalina script name")
        void scriptName() {
            assertEquals("catalina", TomcatConstants.CATALINA_SCRIPT);
        }

        @Test
        @DisplayName("run command")
        void runCommand() {
            assertEquals("run", TomcatConstants.CATALINA_RUN);
        }

        @Test
        @DisplayName("stop command")
        void stopCommand() {
            assertEquals("stop", TomcatConstants.CATALINA_STOP);
        }

        @Test
        @DisplayName("shutdown command")
        void shutdownCommand() {
            assertEquals("SHUTDOWN", TomcatConstants.SHUTDOWN_COMMAND);
        }
    }

    @Nested
    @DisplayName("Environment variable names")
    class EnvVarNames {

        @Test
        @DisplayName("all env port names start with TOMCAT_")
        void allStartWithTomcat() {
            assertTrue(TomcatConstants.ENV_HTTP_PORT.startsWith("TOMCAT_"));
            assertTrue(TomcatConstants.ENV_SHUTDOWN_PORT.startsWith("TOMCAT_"));
            assertTrue(TomcatConstants.ENV_HTTPS_PORT.startsWith("TOMCAT_"));
            assertTrue(TomcatConstants.ENV_JMX_PORT.startsWith("TOMCAT_"));
            assertTrue(TomcatConstants.ENV_AJP_PORT.startsWith("TOMCAT_"));
        }

        @Test
        @DisplayName("all env port names end with _PORT")
        void allEndWithPort() {
            assertTrue(TomcatConstants.ENV_HTTP_PORT.endsWith("_PORT"));
            assertTrue(TomcatConstants.ENV_SHUTDOWN_PORT.endsWith("_PORT"));
            assertTrue(TomcatConstants.ENV_HTTPS_PORT.endsWith("_PORT"));
            assertTrue(TomcatConstants.ENV_JMX_PORT.endsWith("_PORT"));
            assertTrue(TomcatConstants.ENV_AJP_PORT.endsWith("_PORT"));
        }
    }
}
