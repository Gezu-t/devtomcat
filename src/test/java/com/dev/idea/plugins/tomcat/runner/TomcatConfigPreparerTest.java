package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
    @DisplayName("prepare — base==home data-loss guard")
    class BaseEqualsHomeGuard {

        @Test
        @DisplayName("refuses base==home before any destructive step, leaving work/ and temp/ intact")
        void refusesBaseEqualsHomeWithoutWiping(@TempDir Path tempDir) throws IOException {
            Path home = tempDir.resolve("tomcat");
            Files.createDirectories(home.resolve("conf"));
            Files.createDirectories(home.resolve("work"));
            Files.createDirectories(home.resolve("temp"));
            // Sentinels the (otherwise) destructive cleanup would remove: a work/
            // file, and a temp/ entry matching the *lock* heuristic.
            Path workSentinel = Files.writeString(home.resolve("work/keep.txt"), "x");
            Path tempLock = Files.writeString(home.resolve("temp/app.lock"), "x");

            IOException ex = assertThrows(IOException.class, () ->
                    TomcatConfigPreparer.prepare(home, home, 8080, 8005, 0, false, 0, false,
                            null, false, Set.of()));

            assertTrue(ex.getMessage().contains("same path"),
                    "error must explain base==home: " + ex.getMessage());
            assertTrue(Files.exists(workSentinel),
                    "work/ must be untouched when base==home is refused");
            assertTrue(Files.exists(tempLock),
                    "temp/ must be untouched when base==home is refused");
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
        @DisplayName("copies subdirectories recursively but skips Catalina/localhost (owned by mirror)")
        void copiesSubdirectories(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf/Catalina/localhost"));
            Files.createDirectories(catalinaHome.resolve("conf/extras"));
            Files.createDirectories(catalinaBase);

            Files.writeString(catalinaHome.resolve("conf/Catalina/localhost/manager.xml"),
                    "<Context docBase=\"manager\"/>");
            Files.writeString(catalinaHome.resolve("conf/extras/valve.xml"),
                    "<Valve/>");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase);

            // Regular subdirectories still copy.
            assertTrue(Files.exists(catalinaBase.resolve("conf/extras/valve.xml")));
            // Catalina/localhost is reserved for CatalinaHomeMirror.
            assertFalse(Files.exists(catalinaBase.resolve("conf/Catalina/localhost/manager.xml")),
                    "Catalina/localhost should not be copied here; the mirror gates that subtree");
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
        @DisplayName("pinned base (ideManaged=false) preserves user files and never overwrites them")
        void pinnedBasePreservesUserConf(@TempDir Path tempDir) throws IOException {
            Path catalinaHome = tempDir.resolve("home");
            Path catalinaBase = tempDir.resolve("base");
            Files.createDirectories(catalinaHome.resolve("conf"));
            Files.createDirectories(catalinaBase.resolve("conf"));

            // CATALINA_HOME defaults.
            Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app>home</web-app>");
            Files.writeString(catalinaHome.resolve("conf/catalina.properties"), "home=1");

            // The user's pinned base already holds a customized web.xml and a file
            // that exists only in their base (e.g. a keystore / hand-edited config).
            Files.writeString(catalinaBase.resolve("conf/web.xml"), "<web-app>MINE</web-app>");
            Files.writeString(catalinaBase.resolve("conf/keystore.jks"), "SECRET");

            TomcatConfigPreparer.copyConfDirectory(catalinaHome, catalinaBase, false);

            // User-only file survives untouched (the data-loss the wipe caused).
            assertTrue(Files.exists(catalinaBase.resolve("conf/keystore.jks")),
                    "user-only conf file must be preserved in a pinned base");
            assertEquals("SECRET", Files.readString(catalinaBase.resolve("conf/keystore.jks")));
            // Existing user file is NOT overwritten by the CATALINA_HOME default.
            assertEquals("<web-app>MINE</web-app>",
                    Files.readString(catalinaBase.resolve("conf/web.xml")),
                    "pinned base must not overwrite the user's own conf files");
            // An absent default is still filled in so Tomcat has what it needs.
            assertEquals("home=1", Files.readString(catalinaBase.resolve("conf/catalina.properties")),
                    "absent defaults should still be filled from CATALINA_HOME");
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

        @Test
        @DisplayName("refuses same-path case so the user's conf/ is never wiped")
        void refusesSamePathCase(@TempDir Path tempDir) throws IOException {
            // Regression: when CATALINA_BASE equals CATALINA_HOME (a natural
            // misconfiguration when a user pins the base to their registered
            // Tomcat install), recreateDirectory(targetConf) deleted sourceConf
            // before the walk, wiping server.xml / tomcat-users.xml / policies.
            // Refuse instead so destructive work never runs.
            Path tomcat = tempDir.resolve("tomcat");
            Files.createDirectories(tomcat.resolve("conf"));
            Path serverXml = tomcat.resolve("conf").resolve("server.xml");
            Files.writeString(serverXml, "<Server/>");

            IOException ex = assertThrows(IOException.class,
                    () -> TomcatConfigPreparer.copyConfDirectory(tomcat, tomcat));
            assertTrue(ex.getMessage().contains("same path as CATALINA_HOME conf"),
                    "Expected same-path error: " + ex.getMessage());
            // User's conf must still be intact after the refusal.
            assertTrue(Files.exists(serverXml), "Existing server.xml must not be deleted");
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
    @DisplayName("cleanStaleTempState")
    class CleanStaleTempState {

        @Test
        @DisplayName("removes stale cache/lock directories from temp/")
        void removesStaleCacheDirectories(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path tempPath = catalinaBase.resolve("temp");
            Files.createDirectories(tempPath);
            Path cacheDir = Files.createDirectory(tempPath.resolve("cache-data"));
            Files.writeString(cacheDir.resolve(".lock"), "");

            TomcatConfigPreparer.cleanStaleTempState(catalinaBase);

            assertFalse(Files.exists(cacheDir), "Stale cache directory should be removed");
        }

        @Test
        @DisplayName("removes stale lock files from temp/")
        void removesStaleLockFiles(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path tempPath = catalinaBase.resolve("temp");
            Files.createDirectories(tempPath);
            Path lockFile = tempPath.resolve("app.lck");
            Files.writeString(lockFile, "");

            TomcatConfigPreparer.cleanStaleTempState(catalinaBase);

            assertFalse(Files.exists(lockFile), "Stale lock file should be removed");
        }

        @Test
        @DisplayName("is a no-op when temp directory is missing")
        void noOpWhenTempMissing(@TempDir Path tempDir) {
            Path catalinaBase = tempDir.resolve("base");

            assertDoesNotThrow(() -> TomcatConfigPreparer.cleanStaleTempState(catalinaBase));
        }

        @Test
        @DisplayName("preserves ordinary temp files that do not look like persistent state")
        void preservesOrdinaryFiles(@TempDir Path tempDir) throws IOException {
            Path catalinaBase = tempDir.resolve("base");
            Path tempPath = catalinaBase.resolve("temp");
            Files.createDirectories(tempPath);
            Path regular = tempPath.resolve("random.tmp");
            Files.writeString(regular, "scratch");

            TomcatConfigPreparer.cleanStaleTempState(catalinaBase);

            assertTrue(Files.exists(regular), "Ordinary temp file should be preserved");
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

    @Nested
    @DisplayName("routeContainerLogsToConsole")
    class RouteContainerLogsToConsole {

        private static final String KEY =
                "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].handlers";
        private static final String CONSOLE = "java.util.logging.ConsoleHandler";

        private Path writeLoggingProps(Path catalinaBase, String content) throws IOException {
            Path conf = catalinaBase.resolve("conf");
            Files.createDirectories(conf);
            Path file = conf.resolve("logging.properties");
            Files.writeString(file, content);
            return file;
        }

        @Test
        @DisplayName("appends ConsoleHandler to the container logger's own handler list")
        void appendsConsoleHandler(@TempDir Path base) throws IOException {
            Path file = writeLoggingProps(base,
                    KEY + " = 2localhost.org.apache.juli.AsyncFileHandler\n");

            assertTrue(TomcatConfigPreparer.routeContainerLogsToConsole(base));

            String patched = Files.readString(file);
            assertTrue(patched.contains("2localhost.org.apache.juli.AsyncFileHandler, " + CONSOLE),
                    "ConsoleHandler should be appended to the existing handler: " + patched);
        }

        @Test
        @DisplayName("leaves every other line byte-for-byte unchanged")
        void preservesOtherLines(@TempDir Path base) throws IOException {
            String original = "# Console handler\n"
                    + "java.util.logging.ConsoleHandler.level = FINE\n"
                    + "java.util.logging.ConsoleHandler.encoding = UTF-8\n"
                    + KEY + " = 2localhost.org.apache.juli.AsyncFileHandler\n"
                    + "org.apache.catalina.core.ContainerBase.[Catalina].[localhost].level = INFO\n";
            Path file = writeLoggingProps(base, original);

            assertTrue(TomcatConfigPreparer.routeContainerLogsToConsole(base));

            List<String> lines = Files.readAllLines(file);
            assertEquals("# Console handler", lines.get(0));
            assertEquals("java.util.logging.ConsoleHandler.level = FINE", lines.get(1));
            assertEquals("java.util.logging.ConsoleHandler.encoding = UTF-8", lines.get(2));
            assertEquals(KEY + " = 2localhost.org.apache.juli.AsyncFileHandler, " + CONSOLE,
                    lines.get(3));
            assertEquals("org.apache.catalina.core.ContainerBase.[Catalina].[localhost].level = INFO",
                    lines.get(4));
        }

        @Test
        @DisplayName("no-op when the container logger declares no handlers of its own")
        void noOpWhenKeyAbsent(@TempDir Path base) throws IOException {
            String original = "handlers = java.util.logging.ConsoleHandler\n";
            Path file = writeLoggingProps(base, original);

            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base),
                    "without its own handlers the logger already propagates to the console");
            assertEquals(original, Files.readString(file));
        }

        @Test
        @DisplayName("no-op when ConsoleHandler is already listed — re-running does not duplicate it")
        void idempotent(@TempDir Path base) throws IOException {
            Path file = writeLoggingProps(base,
                    KEY + " = 2localhost.org.apache.juli.AsyncFileHandler\n");

            assertTrue(TomcatConfigPreparer.routeContainerLogsToConsole(base));
            String afterFirst = Files.readString(file);

            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base),
                    "second run should find ConsoleHandler already present");
            assertEquals(afterFirst, Files.readString(file));
        }

        @Test
        @DisplayName("fills an empty handler value rather than emitting a leading comma")
        void fillsEmptyValue(@TempDir Path base) throws IOException {
            Path file = writeLoggingProps(base, KEY + " =\n");

            assertTrue(TomcatConfigPreparer.routeContainerLogsToConsole(base));

            String patched = Files.readString(file).strip();
            assertEquals(KEY + " = " + CONSOLE, patched);
        }

        @Test
        @DisplayName("leaves a line-continuation value alone rather than corrupting it")
        void skipsLineContinuation(@TempDir Path base) throws IOException {
            String original = KEY + " = 2localhost.org.apache.juli.AsyncFileHandler, \\\n"
                    + "    3manager.org.apache.juli.AsyncFileHandler\n";
            Path file = writeLoggingProps(base, original);

            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base));
            assertEquals(original, Files.readString(file));
        }

        @Test
        @DisplayName("ignores a commented-out declaration")
        void ignoresComments(@TempDir Path base) throws IOException {
            String original = "#" + KEY + " = 2localhost.org.apache.juli.AsyncFileHandler\n";
            Path file = writeLoggingProps(base, original);

            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base));
            assertEquals(original, Files.readString(file));
        }

        @Test
        @DisplayName("does not match a longer key that merely starts with the same text")
        void rejectsPrefixMatch(@TempDir Path base) throws IOException {
            String original = KEY + "Extra = 2localhost.org.apache.juli.AsyncFileHandler\n";
            Path file = writeLoggingProps(base, original);

            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base));
            assertEquals(original, Files.readString(file));
        }

        @Test
        @DisplayName("returns false without throwing when logging.properties is absent")
        void missingFile(@TempDir Path base) {
            assertFalse(TomcatConfigPreparer.routeContainerLogsToConsole(base));
        }
    }
}
