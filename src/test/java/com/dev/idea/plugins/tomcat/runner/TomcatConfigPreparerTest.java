package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigPreparer")
class TomcatConfigPreparerTest {

    @Nested
    @DisplayName("createDirectories")
    class CreateDirectories {

        @Test
        @DisplayName("creates all required CATALINA_BASE subdirectories")
        void createsAllDirs(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("catalina-base");

            TomcatConfigPreparer.createDirectories(catalinaBase);

            assertTrue(Files.isDirectory(catalinaBase.resolve("temp")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("logs")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("webapps")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("work")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("conf")));
        }

        @Test
        @DisplayName("is idempotent — re-running does not fail")
        void idempotent(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("catalina-base");

            TomcatConfigPreparer.createDirectories(catalinaBase);
            assertDoesNotThrow(() -> TomcatConfigPreparer.createDirectories(catalinaBase));
        }
    }

    @Nested
    @DisplayName("customizeServerXml")
    class CustomizeServerXml {

        @Test
        @DisplayName("generates minimal server.xml when source does not exist")
        void generatesMinimalWhenSourceMissing(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase.resolve("conf"));

            // No server.xml in catalinaHome
            List<String> warnings = TomcatConfigPreparer.customizeServerXml(
                    catalinaHome, catalinaBase, 8080, 8005, 8443, false, 8009, false);

            Path targetXml = catalinaBase.resolve("conf/server.xml");
            assertTrue(Files.exists(targetXml), "server.xml should be generated");

            String content = Files.readString(targetXml);
            assertTrue(content.contains("port=\"8080\""), "Should contain HTTP port");
            assertTrue(content.contains("port=\"8005\""), "Should contain shutdown port");
            assertTrue(warnings.isEmpty(), "No warnings for generated minimal config");
        }

        @Test
        @DisplayName("mutates existing server.xml via DOM")
        void mutatesExistingServerXml(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase.resolve("conf"));

            // Write a standard server.xml to catalinaHome
            String sourceXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Server port="8005" shutdown="SHUTDOWN">
                      <Service name="Catalina">
                        <Connector port="8080" protocol="HTTP/1.1"
                                   connectionTimeout="20000" redirectPort="8443" />
                        <Engine name="Catalina" defaultHost="localhost">
                          <Host name="localhost" appBase="webapps" />
                        </Engine>
                      </Service>
                    </Server>
                    """;
            Files.writeString(catalinaHome.resolve("conf/server.xml"), sourceXml);

            List<String> warnings = TomcatConfigPreparer.customizeServerXml(
                    catalinaHome, catalinaBase, 9090, 9005, 0, false, 0, false);

            String result = Files.readString(catalinaBase.resolve("conf/server.xml"));
            assertTrue(result.contains("port=\"9090\""), "HTTP port should be updated");
            assertTrue(result.contains("port=\"9005\""), "Shutdown port should be updated");
            assertFalse(warnings.stream().anyMatch(w -> w.contains("parsing failed")),
                    "DOM parsing should succeed");
        }
    }

    @Nested
    @DisplayName("copyConfDirectory")
    class CopyConfDirectory {

        @Test
        @DisplayName("copies all files from CATALINA_HOME/conf to CATALINA_BASE/conf")
        void copiesAllFiles(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase);

            Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app/>");
            Files.writeString(catalinaHome.resolve("conf/context.xml"), "<Context/>");
            Files.writeString(catalinaHome.resolve("conf/catalina.properties"), "key=value");
            Files.writeString(catalinaHome.resolve("conf/tomcat-users.xml"), "<tomcat-users/>");
            Files.writeString(catalinaHome.resolve("conf/logging.properties"), "handler=console");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase);

            assertTrue(Files.exists(catalinaBase.resolve("conf/web.xml")));
            assertTrue(Files.exists(catalinaBase.resolve("conf/context.xml")));
            assertTrue(Files.exists(catalinaBase.resolve("conf/catalina.properties")));
            assertTrue(Files.exists(catalinaBase.resolve("conf/tomcat-users.xml")));
            assertTrue(Files.exists(catalinaBase.resolve("conf/logging.properties")));
            assertEquals("handler=console",
                    Files.readString(catalinaBase.resolve("conf/logging.properties")),
                    "logging.properties should be the original from CATALINA_HOME");
        }

        @Test
        @DisplayName("copies subdirectories recursively")
        void copiesSubdirectories(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf/Catalina/localhost"));
            Files.createDirectories(catalinaBase);

            Files.writeString(catalinaHome.resolve("conf/Catalina/localhost/manager.xml"),
                    "<Context docBase=\"manager\"/>");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase);

            assertTrue(Files.exists(catalinaBase.resolve("conf/Catalina/localhost/manager.xml")));
            assertEquals("<Context docBase=\"manager\"/>",
                    Files.readString(catalinaBase.resolve("conf/Catalina/localhost/manager.xml")));
        }

        @Test
        @DisplayName("overwrites existing files in base with fresh copies from home")
        void overwritesExisting(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase.resolve("conf"));

            Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app>home</web-app>");
            Files.writeString(catalinaBase.resolve("conf/web.xml"), "<web-app>stale</web-app>");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase);

            assertEquals("<web-app>home</web-app>",
                    Files.readString(catalinaBase.resolve("conf/web.xml")),
                    "Each run should get a fresh copy from CATALINA_HOME");
        }

        @Test
        @DisplayName("removes stale files and directories not present in home conf")
        void removesStaleFiles(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf/Catalina"));
            Files.createDirectories(catalinaBase.resolve("conf/obsolete/subdir"));

            Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app>home</web-app>");
            Files.writeString(catalinaBase.resolve("conf/obsolete/subdir/stale.txt"), "stale");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase);

            assertTrue(Files.exists(catalinaBase.resolve("conf/web.xml")));
            assertFalse(Files.exists(catalinaBase.resolve("conf/obsolete")),
                    "Old files from previous runs should not survive the fresh conf copy");
        }

        @Test
        @DisplayName("handles missing CATALINA_HOME/conf gracefully")
        void handlesMissingConf(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome); // no conf subdirectory
            Files.createDirectories(catalinaBase);

            assertDoesNotThrow(() -> TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase));
        }
    }

    @Nested
    @DisplayName("cleanWorkDirectory")
    class CleanWorkDirectory {

        @Test
        @DisplayName("removes stale compiled files from work directory")
        void removesStaleFiles(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path workDir = catalinaBase.resolve("work/Catalina/localhost/ROOT");
            Files.createDirectories(workDir);
            Files.writeString(workDir.resolve("index_jsp.class"), "compiled");
            Files.writeString(workDir.resolve("index_jsp.java"), "generated");

            TomcatConfigPreparer.cleanWorkDirectory(catalinaBase);

            assertTrue(Files.isDirectory(catalinaBase.resolve("work")),
                    "work/ directory itself should survive");
            assertFalse(Files.exists(workDir.resolve("index_jsp.class")),
                    "Stale class files should be removed");
        }

        @Test
        @DisplayName("is safe when work directory does not exist")
        void safeWhenMissing(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaBase);

            assertDoesNotThrow(() -> TomcatConfigPreparer.cleanWorkDirectory(catalinaBase));
        }
    }

    @Nested
    @DisplayName("applyConfOverlay")
    class ApplyConfOverlay {

        @Test
        @DisplayName("overlay files overwrite CATALINA_HOME defaults")
        void overlayOverwrites(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path overlay = tempDir.resolve("overlay");
            Files.createDirectories(catalinaBase.resolve("conf"));
            Files.createDirectories(overlay);

            // Simulate conf already copied from CATALINA_HOME
            Files.writeString(catalinaBase.resolve("conf/context.xml"), "<Context>default</Context>");
            // User overlay with custom Realm
            Files.writeString(overlay.resolve("context.xml"),
                    "<Context><Realm className=\"custom\"/></Context>");

            TomcatConfigPreparer.applyConfOverlay(overlay, catalinaBase);

            assertEquals("<Context><Realm className=\"custom\"/></Context>",
                    Files.readString(catalinaBase.resolve("conf/context.xml")),
                    "Overlay should replace the CATALINA_HOME default");
        }

        @Test
        @DisplayName("overlay adds new files not present in CATALINA_HOME")
        void overlayAddsNew(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path overlay = tempDir.resolve("overlay");
            Files.createDirectories(catalinaBase.resolve("conf"));
            Files.createDirectories(overlay);

            Files.writeString(overlay.resolve("custom-valve.xml"), "<Valve/>");

            TomcatConfigPreparer.applyConfOverlay(overlay, catalinaBase);

            assertTrue(Files.exists(catalinaBase.resolve("conf/custom-valve.xml")));
        }

        @Test
        @DisplayName("overlay handles subdirectories")
        void overlaySubdirs(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path overlay = tempDir.resolve("overlay");
            Files.createDirectories(catalinaBase.resolve("conf"));
            Files.createDirectories(overlay.resolve("Catalina/localhost"));

            Files.writeString(overlay.resolve("Catalina/localhost/manager.xml"),
                    "<Context docBase=\"custom-manager\"/>");

            TomcatConfigPreparer.applyConfOverlay(overlay, catalinaBase);

            assertEquals("<Context docBase=\"custom-manager\"/>",
                    Files.readString(catalinaBase.resolve("conf/Catalina/localhost/manager.xml")));
        }

        @Test
        @DisplayName("no-op when overlay directory does not exist")
        void noOpWhenMissing(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path overlay = tempDir.resolve("nonexistent");
            Files.createDirectories(catalinaBase.resolve("conf"));

            assertDoesNotThrow(() -> TomcatConfigPreparer.applyConfOverlay(overlay, catalinaBase));
        }
    }

    @Nested
    @DisplayName("validateConf")
    class ValidateConf {

        @Test
        @DisplayName("returns no warnings when all required files exist")
        void noWarningsWhenComplete(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaBase.resolve("conf"));
            Files.writeString(catalinaBase.resolve("conf/server.xml"), "<Server/>");
            Files.writeString(catalinaBase.resolve("conf/web.xml"), "<web-app/>");
            Files.writeString(catalinaBase.resolve("conf/catalina.properties"), "");

            List<String> warnings = TomcatConfigPreparer.validateConf(catalinaBase);

            assertTrue(warnings.isEmpty(), "No warnings expected for complete conf");
        }

        @Test
        @DisplayName("warns about each missing required file")
        void warnsAboutMissing(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaBase.resolve("conf"));
            // Only create server.xml, skip web.xml and catalina.properties
            Files.writeString(catalinaBase.resolve("conf/server.xml"), "<Server/>");

            List<String> warnings = TomcatConfigPreparer.validateConf(catalinaBase);

            assertEquals(2, warnings.size(), "Should warn about 2 missing files");
            assertTrue(warnings.stream().anyMatch(w -> w.contains("web.xml")));
            assertTrue(warnings.stream().anyMatch(w -> w.contains("catalina.properties")));
        }

        @Test
        @DisplayName("all warnings include actionable message")
        void warningsAreActionable(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaBase.resolve("conf"));

            List<String> warnings = TomcatConfigPreparer.validateConf(catalinaBase);

            for (String warning : warnings) {
                assertTrue(warning.contains("Tomcat may fail to start"),
                        "Warning should explain the consequence: " + warning);
            }
        }
    }

    @Nested
    @DisplayName("generateMinimalServerXml")
    class GenerateMinimalServerXml {

        @Test
        @DisplayName("generates valid XML with HTTP connector")
        void generatesWithHttp() {
            String xml = TomcatConfigPreparer.generateMinimalServerXml(
                    8080, 8005, 8443, false, 8009, false);

            assertTrue(xml.contains("port=\"8080\""));
            assertTrue(xml.contains("port=\"8005\""));
            assertTrue(xml.contains("protocol=\"HTTP/1.1\""));
            assertFalse(xml.contains("SSLEnabled"));
            assertFalse(xml.contains("AJP"));
        }

        @Test
        @DisplayName("includes HTTPS connector when enabled")
        void includesHttpsWhenEnabled() {
            String xml = TomcatConfigPreparer.generateMinimalServerXml(
                    8080, 8005, 9443, true, 8009, false);

            assertTrue(xml.contains("port=\"9443\""));
            assertTrue(xml.contains("SSLEnabled=\"true\""));
        }

        @Test
        @DisplayName("includes AJP connector when enabled")
        void includesAjpWhenEnabled() {
            String xml = TomcatConfigPreparer.generateMinimalServerXml(
                    8080, 8005, 8443, false, 9009, true);

            assertTrue(xml.contains("port=\"9009\""));
            assertTrue(xml.contains("protocol=\"AJP/1.3\""));
            assertTrue(xml.contains("secretRequired=\"false\""));
        }

        @Test
        @DisplayName("includes all connectors when all enabled")
        void includesAllConnectors() {
            String xml = TomcatConfigPreparer.generateMinimalServerXml(
                    9080, 9005, 9443, true, 9009, true);

            assertTrue(xml.contains("port=\"9080\""), "HTTP port");
            assertTrue(xml.contains("port=\"9005\""), "Shutdown port");
            assertTrue(xml.contains("port=\"9443\""), "HTTPS port");
            assertTrue(xml.contains("port=\"9009\""), "AJP port");
        }

        @Test
        @DisplayName("generates parseable XML")
        void generatesParseableXml() {
            String xml = TomcatConfigPreparer.generateMinimalServerXml(
                    8080, 8005, 8443, true, 8009, true);

            assertDoesNotThrow(() -> ServerXmlMutator.parse(xml),
                    "Generated XML should be parseable by DOM parser");
        }
    }

    @Nested
    @DisplayName("parse failure fallback")
    class ParseFailureFallback {

        @Test
        @DisplayName("falls back to minimal config when source server.xml is unparseable")
        void fallsBackToMinimalOnParseFailure(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase.resolve("conf"));

            // Write broken XML to catalinaHome
            Files.writeString(catalinaHome.resolve("conf/server.xml"), "not valid xml at all <>");

            List<String> warnings = TomcatConfigPreparer.customizeServerXml(
                    catalinaHome, catalinaBase, 9090, 9005, 9443, true, 0, false);

            // Should report the parse failure
            assertTrue(warnings.stream().anyMatch(w -> w.contains("XML parsing failed")),
                    "Should warn about parse failure");

            // The written file should be the minimal generated config, not the broken original
            String content = Files.readString(catalinaBase.resolve("conf/server.xml"));
            assertFalse(content.contains("not valid xml"),
                    "Should not write the broken original XML to CATALINA_BASE");
            assertTrue(content.contains("port=\"9090\""),
                    "Fallback config should contain the requested HTTP port");
            assertTrue(content.contains("port=\"9005\""),
                    "Fallback config should contain the requested shutdown port");
            assertTrue(content.contains("port=\"9443\""),
                    "Fallback config should contain the requested HTTPS port when enabled");
        }
    }

    @Nested
    @DisplayName("prepare (integration)")
    class PrepareIntegration {

        @Test
        @DisplayName("full prepare creates directory structure and config files")
        void fullPrepare(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));

            // Write source config files
            String sourceXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Server port="8005" shutdown="SHUTDOWN">
                      <Service name="Catalina">
                        <Connector port="8080" protocol="HTTP/1.1" redirectPort="8443" />
                        <Engine name="Catalina" defaultHost="localhost">
                          <Host name="localhost" appBase="webapps" />
                        </Engine>
                      </Service>
                    </Server>
                    """;
            Files.writeString(catalinaHome.resolve("conf/server.xml"), sourceXml);
            Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app/>");
            Files.writeString(catalinaHome.resolve("conf/catalina.properties"), "key=value");
            Files.writeString(catalinaHome.resolve("conf/logging.properties"), "handler=console");

            List<String> warnings = TomcatConfigPreparer.prepare(
                    catalinaBase, catalinaHome, 9090, 9005,
                    9443, true, 0, false);

            // No parse failures expected for standard server.xml
            assertTrue(warnings.stream().noneMatch(w -> w.contains("parsing failed")),
                    "DOM parsing should succeed on standard server.xml");

            // Verify directory structure
            assertTrue(Files.isDirectory(catalinaBase.resolve("temp")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("logs")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("webapps")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("work")));
            assertTrue(Files.isDirectory(catalinaBase.resolve("conf")));

            // Verify full conf was copied
            assertTrue(Files.exists(catalinaBase.resolve("conf/web.xml")),
                    "web.xml should be copied from CATALINA_HOME");
            assertTrue(Files.exists(catalinaBase.resolve("conf/catalina.properties")),
                    "catalina.properties should be copied from CATALINA_HOME");
            assertEquals("handler=console",
                    Files.readString(catalinaBase.resolve("conf/logging.properties")),
                    "logging.properties should be the original from CATALINA_HOME, not a bundled override");

            // Verify server.xml was mutated with updated ports (overwrites the copied original)
            Path serverXml = catalinaBase.resolve("conf/server.xml");
            assertTrue(Files.exists(serverXml));
            String content = Files.readString(serverXml);
            assertTrue(content.contains("port=\"9090\""), "HTTP port should be 9090");
            assertTrue(content.contains("port=\"9005\""), "Shutdown port should be 9005");
        }
    }
}
