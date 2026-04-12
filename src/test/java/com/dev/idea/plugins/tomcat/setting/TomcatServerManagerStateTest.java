package com.dev.idea.plugins.tomcat.setting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatServerManagerState")
class TomcatServerManagerStateTest {

    @Nested
    @DisplayName("state management")
    class StateManagement {

        @Test
        @DisplayName("loadState round-trip preserves configured servers")
        void loadStateRoundTripPreservesConfiguredServers() {
            TomcatServerManagerState original = new TomcatServerManagerState();
            original.setTomcatInfos(List.of(
                    new TomcatInfo("Tomcat 9", "9.0.82", "/opt/tomcat9"),
                    new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10")
            ));

            TomcatServerManagerState restored = new TomcatServerManagerState();
            restored.loadState(original);

            List<TomcatInfo> servers = restored.getTomcatInfos();
            assertEquals(2, servers.size());
            assertEquals("Tomcat 9", servers.get(0).getName());
            assertEquals("Tomcat 10", servers.get(1).getName());
        }

        @Test
        @DisplayName("getTomcatInfos returns immutable snapshot")
        void getTomcatInfosReturnsImmutableSnapshot() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            state.addTomcatInfo(new TomcatInfo("Tomcat 9", "9.0.82", "/opt/tomcat9"));

            List<TomcatInfo> snapshot = state.getTomcatInfos();

            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.add(new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10")));

            state.addTomcatInfo(new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10"));
            assertEquals(1, snapshot.size(), "Snapshot should not reflect later mutations");
        }

        @Test
        @DisplayName("setTomcatInfos replaces prior state")
        void setTomcatInfosReplacesPriorState() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            state.addTomcatInfo(new TomcatInfo("Tomcat 8", "8.5.99", "/opt/tomcat8"));

            TomcatInfo replacement = new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10");
            state.setTomcatInfos(List.of(replacement));

            List<TomcatInfo> servers = state.getTomcatInfos();
            assertEquals(1, servers.size());
            assertEquals("Tomcat 10", servers.get(0).getName());
        }
    }

    @Nested
    @DisplayName("installation detection")
    class InstallationDetection {

        @Test
        @DisplayName("tryCreateTomcatInfo reads version from catalina.jar")
        void tryCreateTomcatInfoReadsVersion(@TempDir Path tempDir) throws IOException {
            Path tomcatHome = createTomcatHome(tempDir, "Apache Tomcat/10.1.28", "10.1.28");

            Optional<TomcatInfo> info = TomcatServerManagerState.tryCreateTomcatInfo(
                    tomcatHome.toString(),
                    ignored -> "Detected Tomcat");

            assertTrue(info.isPresent());
            assertEquals("Detected Tomcat", info.get().getName());
            assertEquals("10.1.28", info.get().getVersion());
            assertEquals(tomcatHome.toString(), info.get().getPath());
        }

        @Test
        @DisplayName("invalid home returns empty")
        void invalidHomeReturnsEmpty(@TempDir Path tempDir) {
            Path missing = tempDir.resolve("missing");

            Optional<TomcatInfo> info = TomcatServerManagerState.tryCreateTomcatInfo(
                    missing.toString(),
                    ignored -> "ignored");

            assertTrue(info.isEmpty());
        }

        @Test
        @DisplayName("missing catalina jar returns empty")
        void missingCatalinaJarReturnsEmpty(@TempDir Path tempDir) throws IOException {
            Path tomcatHome = Files.createDirectory(tempDir.resolve("apache-tomcat"));
            Files.createDirectories(tomcatHome.resolve("lib"));

            Optional<TomcatInfo> info = TomcatServerManagerState.tryCreateTomcatInfo(
                    tomcatHome.toString(),
                    ignored -> "ignored");

            assertTrue(info.isEmpty());
        }
    }

    @Nested
    @DisplayName("unique name generation")
    class UniqueNameGeneration {

        @Test
        @DisplayName("preferred name used when no conflict")
        void preferredNameUsedWhenAvailable() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            state.addTomcatInfo(new TomcatInfo("Tomcat 9", "9.0.82", "/opt/tomcat9"));

            String unique = com.dev.idea.plugins.tomcat.utils.TomcatServerUtils
                    .generateUniqueName(
                            state.getTomcatInfos().stream()
                                    .map(TomcatInfo::getName)
                                    .toList(),
                            "Tomcat 10");

            assertEquals("Tomcat 10", unique);
        }

        @Test
        @DisplayName("numeric suffix appended on conflict")
        void numericSuffixAppendedOnConflict() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            state.addTomcatInfo(new TomcatInfo("Tomcat 9", "9.0.82", "/opt/tomcat9"));

            String unique = com.dev.idea.plugins.tomcat.utils.TomcatServerUtils
                    .generateUniqueName(
                            state.getTomcatInfos().stream()
                                    .map(TomcatInfo::getName)
                                    .toList(),
                            "Tomcat 9");

            assertEquals("Tomcat 9 (1)", unique);
        }

        @Test
        @DisplayName("multiple conflicts increment suffix")
        void multipleConflictsIncrementSuffix() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            state.addTomcatInfo(new TomcatInfo("Tomcat", "9.0", "/opt/1"));
            state.addTomcatInfo(new TomcatInfo("Tomcat (1)", "9.0", "/opt/2"));
            state.addTomcatInfo(new TomcatInfo("Tomcat (2)", "9.0", "/opt/3"));

            String unique = com.dev.idea.plugins.tomcat.utils.TomcatServerUtils
                    .generateUniqueName(
                            state.getTomcatInfos().stream()
                                    .map(TomcatInfo::getName)
                                    .toList(),
                            "Tomcat");

            assertEquals("Tomcat (3)", unique);
        }
    }

    private static Path createTomcatHome(Path tempDir, String serverInfo, String serverNumber) throws IOException {
        Path tomcatHome = Files.createDirectory(tempDir.resolve("apache-tomcat"));
        Path catalinaJar = tomcatHome.resolve("lib").resolve("catalina.jar");
        Files.createDirectories(catalinaJar.getParent());

        Properties properties = new Properties();
        properties.setProperty("server.info", serverInfo);
        properties.setProperty("server.number", serverNumber);

        try (OutputStream output = Files.newOutputStream(catalinaJar);
             JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("org/apache/catalina/util/ServerInfo.properties"));
            properties.store(jar, null);
            jar.closeEntry();
        }

        return tomcatHome;
    }
}
