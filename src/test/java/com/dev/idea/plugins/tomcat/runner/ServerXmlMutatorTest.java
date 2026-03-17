package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ServerXmlMutator")
class ServerXmlMutatorTest {

    /** Standard Tomcat 9 server.xml skeleton for testing. */
    private static final String STANDARD_SERVER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Listener className="org.apache.catalina.startup.VersionLoggerListener" />
              <Service name="Catalina">
                <Connector port="8080" protocol="HTTP/1.1"
                           connectionTimeout="20000" redirectPort="8443" />
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="webapps"
                        unpackWARs="true" autoDeploy="true" />
                </Engine>
              </Service>
            </Server>
            """;

    /** server.xml with HTTPS connector already present. */
    private static final String SERVER_XML_WITH_HTTPS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Service name="Catalina">
                <Connector port="8080" protocol="HTTP/1.1"
                           connectionTimeout="20000" redirectPort="8443" />
                <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
                           maxThreads="150" SSLEnabled="true" />
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="webapps" />
                </Engine>
              </Service>
            </Server>
            """;

    /** server.xml with AJP connector already present. */
    private static final String SERVER_XML_WITH_AJP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Service name="Catalina">
                <Connector port="8080" protocol="HTTP/1.1"
                           connectionTimeout="20000" redirectPort="8443" />
                <Connector port="8009" protocol="AJP/1.3" secretRequired="false"
                           redirectPort="8443" />
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="webapps" />
                </Engine>
              </Service>
            </Server>
            """;

    /** server.xml with protocol attribute before port (non-standard order). */
    private static final String SERVER_XML_PROTOCOL_FIRST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Service name="Catalina">
                <Connector protocol="HTTP/1.1" port="8080"
                           connectionTimeout="20000" redirectPort="8443" />
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="webapps" />
                </Engine>
              </Service>
            </Server>
            """;

    /** server.xml with custom attributes on connectors. */
    private static final String SERVER_XML_CUSTOM_ATTRS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Service name="Catalina">
                <Connector port="8080" protocol="HTTP/1.1"
                           maxThreads="200" minSpareThreads="10"
                           acceptCount="100" connectionTimeout="20000"
                           redirectPort="8443" URIEncoding="UTF-8" />
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="webapps" />
                </Engine>
              </Service>
            </Server>
            """;

    @Nested
    @DisplayName("shutdown port")
    class ShutdownPort {

        @Test
        @DisplayName("updates Server port attribute")
        void updatesShutdownPort() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 0, false, 0, false);

            assertTrue(result.getXml().contains("port=\"9005\""),
                    "Shutdown port should be updated to 9005");
            assertFalse(result.hasWarnings());
        }
    }

    @Nested
    @DisplayName("HTTP connector")
    class HttpConnector {

        @Test
        @DisplayName("updates HTTP connector port")
        void updatesHttpPort() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 9090, 0, false, 0, false);

            assertTrue(result.getXml().contains("port=\"9090\""),
                    "HTTP port should be updated to 9090");
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("handles protocol-before-port attribute order")
        void handlesProtocolFirstOrder() {
            var result = ServerXmlMutator.customize(SERVER_XML_PROTOCOL_FIRST,
                    8005, 9090, 0, false, 0, false);

            assertTrue(result.getXml().contains("port=\"9090\""),
                    "HTTP port should be updated even with non-standard attribute order");
        }

        @Test
        @DisplayName("preserves custom attributes on HTTP connector")
        void preservesCustomAttributes() {
            var result = ServerXmlMutator.customize(SERVER_XML_CUSTOM_ATTRS,
                    8005, 9090, 0, false, 0, false);

            String xml = result.getXml();
            assertTrue(xml.contains("port=\"9090\""));
            assertTrue(xml.contains("maxThreads=\"200\""), "Custom maxThreads should be preserved");
            assertTrue(xml.contains("URIEncoding=\"UTF-8\""), "Custom URIEncoding should be preserved");
            assertTrue(xml.contains("acceptCount=\"100\""), "Custom acceptCount should be preserved");
        }
    }

    @Nested
    @DisplayName("HTTPS connector")
    class HttpsConnector {

        @Test
        @DisplayName("updates existing HTTPS connector port")
        void updatesExistingHttpsPort() {
            var result = ServerXmlMutator.customize(SERVER_XML_WITH_HTTPS,
                    8005, 8080, 9443, true, 0, false);

            assertTrue(result.getXml().contains("port=\"9443\""),
                    "HTTPS port should be updated to 9443");
        }

        @Test
        @DisplayName("injects HTTPS connector when not present")
        void injectsHttpsConnector() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 9443, true, 0, false);

            String xml = result.getXml();
            assertTrue(xml.contains("port=\"9443\""),
                    "Injected HTTPS connector should have port 9443");
            assertTrue(xml.contains("SSLEnabled=\"true\""),
                    "Injected HTTPS connector should have SSLEnabled");
        }

        @Test
        @DisplayName("updates HTTP redirectPort when HTTPS enabled")
        void updatesRedirectPort() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 9443, true, 0, false);

            assertTrue(result.getXml().contains("redirectPort=\"9443\""),
                    "HTTP connector's redirectPort should be updated to match HTTPS port");
        }

        @Test
        @DisplayName("does not inject HTTPS connector when disabled")
        void noHttpsWhenDisabled() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 9443, false, 0, false);

            // Should not contain a second Connector with SSLEnabled
            assertFalse(result.getXml().contains("SSLEnabled"),
                    "Should not inject HTTPS connector when disabled");
        }
    }

    @Nested
    @DisplayName("AJP connector")
    class AjpConnector {

        @Test
        @DisplayName("updates existing AJP connector port")
        void updatesExistingAjpPort() {
            var result = ServerXmlMutator.customize(SERVER_XML_WITH_AJP,
                    8005, 8080, 0, false, 9009, true);

            assertTrue(result.getXml().contains("port=\"9009\""),
                    "AJP port should be updated to 9009");
        }

        @Test
        @DisplayName("injects AJP connector when not present")
        void injectsAjpConnector() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 0, false, 9009, true);

            String xml = result.getXml();
            assertTrue(xml.contains("port=\"9009\""),
                    "Injected AJP connector should have port 9009");
            assertTrue(xml.contains("protocol=\"AJP/1.3\""),
                    "Injected AJP connector should have AJP protocol");
            assertTrue(xml.contains("secretRequired=\"false\""),
                    "Injected AJP connector should have secretRequired=false");
        }

        @Test
        @DisplayName("does not inject AJP connector when disabled")
        void noAjpWhenDisabled() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 0, false, 9009, false);

            assertFalse(result.getXml().contains("AJP/1.3"),
                    "Should not inject AJP connector when disabled");
        }

        @Test
        @DisplayName("injected AJP uses resolved HTTPS port for redirectPort when HTTPS enabled")
        void injectedAjpUsesResolvedHttpsPort() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 9443, true, 9009, true);

            String xml = result.getXml();
            assertTrue(xml.contains("protocol=\"AJP/1.3\""), "AJP connector should be injected");
            assertTrue(xml.contains("port=\"9009\""), "AJP port should be 9009");
            // Critical: redirectPort must match the resolved HTTPS port, not a hardcoded default
            assertTrue(xml.contains("redirectPort=\"9443\""),
                    "AJP redirectPort should use the resolved HTTPS port (9443), not a hardcoded default");
        }

        @Test
        @DisplayName("injected AJP inherits HTTP redirectPort when HTTPS disabled")
        void injectedAjpUsesHttpRedirectPortWhenNoHttps() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    8005, 8080, 0, false, 9009, true);

            String xml = result.getXml();
            assertTrue(xml.contains("protocol=\"AJP/1.3\""));
            // HTTP connector in STANDARD_SERVER_XML has redirectPort="8443"
            assertTrue(xml.contains("redirectPort=\"8443\""),
                    "AJP redirectPort should inherit from HTTP connector's redirectPort when HTTPS is disabled");
        }
    }

    @Nested
    @DisplayName("combined mutations")
    class CombinedMutations {

        @Test
        @DisplayName("updates all ports simultaneously")
        void updatesAllPorts() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 9443, true, 9009, true);

            String xml = result.getXml();
            assertTrue(xml.contains("port=\"9005\""), "Shutdown port");
            assertTrue(xml.contains("port=\"9080\""), "HTTP port");
            assertTrue(xml.contains("port=\"9443\""), "HTTPS port");
            assertTrue(xml.contains("port=\"9009\""), "AJP port");
            assertFalse(result.hasWarnings(), "No warnings expected for standard server.xml");
        }

        @Test
        @DisplayName("preserves Listener elements")
        void preservesListeners() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 0, false, 0, false);

            assertTrue(result.getXml().contains("VersionLoggerListener"),
                    "Listener elements should be preserved");
        }

        @Test
        @DisplayName("preserves Engine and Host structure, disables autoDeploy")
        void preservesEngineHost() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 0, false, 0, false);

            String xml = result.getXml();
            assertTrue(xml.contains("Engine"), "Engine element should be preserved");
            assertTrue(xml.contains("defaultHost=\"localhost\""), "Engine attributes should be preserved");
            assertTrue(xml.contains("autoDeploy=\"false\""),
                    "autoDeploy should be set to false — DevTomcat manages deployments");
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns original XML on parse failure")
        void returnsOriginalOnParseFailure() {
            String broken = "not valid xml at all <>";
            var result = ServerXmlMutator.customize(broken,
                    9005, 9080, 0, false, 0, false);

            assertEquals(broken, result.getXml(), "Should return original XML on parse failure");
            assertTrue(result.hasWarnings(), "Should report warnings on parse failure");
        }

        @Test
        @DisplayName("warns when no Server element found")
        void warnsNoServerElement() {
            String noServer = "<?xml version=\"1.0\"?><Root><Service/></Root>";
            var result = ServerXmlMutator.customize(noServer,
                    9005, 9080, 0, false, 0, false);

            assertTrue(result.hasWarnings(), "Should warn when no Server element found");
        }

        @Test
        @DisplayName("warns when no HTTP connector found")
        void warnsNoHttpConnector() {
            String noConnector = """
                    <?xml version="1.0"?>
                    <Server port="8005" shutdown="SHUTDOWN">
                      <Service name="Catalina">
                        <Engine name="Catalina" defaultHost="localhost" />
                      </Service>
                    </Server>
                    """;
            var result = ServerXmlMutator.customize(noConnector,
                    9005, 9080, 0, false, 0, false);

            assertTrue(result.hasWarnings(), "Should warn when no HTTP connector found");
        }
    }

    @Nested
    @DisplayName("MutationResult")
    class MutationResultTests {

        @Test
        @DisplayName("no warnings for clean mutation")
        void noWarningsForCleanMutation() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 0, false, 0, false);

            assertFalse(result.hasWarnings());
            assertNotNull(result.getXml());
            assertFalse(result.getXml().isEmpty());
        }

        @Test
        @DisplayName("getWarnings returns modifiable list")
        void warningsListNotNull() {
            var result = ServerXmlMutator.customize(STANDARD_SERVER_XML,
                    9005, 9080, 0, false, 0, false);

            assertNotNull(result.getWarnings());
        }
    }
}
