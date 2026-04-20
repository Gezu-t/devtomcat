package com.dev.idea.plugins.tomcat.setting;

import org.junit.jupiter.api.BeforeEach;
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
    @DisplayName("resolve() — reconciling persisted references to registered state")
    class Resolve {

        private TomcatServerManagerState state;
        private TomcatInfo registered;

        @BeforeEach
        void seed() {
            state = new TomcatServerManagerState();
            registered = new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10");
            // Seed a deterministic ID so ID-drift scenarios can be exercised.
            registered.setId("registered-id-1");
            state.setTomcatInfos(List.of(registered));
        }

        @Test
        @DisplayName("null input returns null")
        void nullReturnsNull() {
            assertNull(state.resolve(null));
        }

        @Test
        @DisplayName("ID match returns the registered instance")
        void idMatchReturnsRegistered() {
            TomcatInfo persisted = new TomcatInfo("renamed-locally", "10.1.28", "/opt/other-path");
            persisted.setId("registered-id-1");

            TomcatInfo resolved = state.resolve(persisted);

            assertSame(registered, resolved, "ID hit must return the canonical live instance");
        }

        @Test
        @DisplayName("ID miss + path exact match returns registered")
        void pathExactMatchReturnsRegistered() {
            TomcatInfo persisted = new TomcatInfo("imported", "10.0.0", "/opt/tomcat10");
            persisted.setId("different-id");

            TomcatInfo resolved = state.resolve(persisted);

            assertSame(registered, resolved, "path match must reconcile ID drift");
        }

        @Test
        @DisplayName("ID miss + path differs only by trailing slash matches via normalization")
        void pathNormalizationMatches() {
            TomcatInfo persisted = new TomcatInfo("imported", "10.0.0", "/opt/tomcat10/");
            persisted.setId("different-id");

            TomcatInfo resolved = state.resolve(persisted);

            assertSame(registered, resolved, "normalized-path match must succeed");
        }

        @Test
        @DisplayName("ID miss + path miss + name match returns registered")
        void nameFallbackMatches() {
            TomcatInfo persisted = new TomcatInfo("Tomcat 10", "9.0", "/nowhere/at/all");
            persisted.setId("different-id");

            TomcatInfo resolved = state.resolve(persisted);

            assertSame(registered, resolved, "name fallback must match as last resort");
        }

        @Test
        @DisplayName("full miss returns null — caller treats as dangling")
        void fullMissReturnsNull() {
            TomcatInfo persisted = new TomcatInfo("Unknown Server", "9.0", "/missing");
            persisted.setId("ghost");

            assertNull(state.resolve(persisted));
        }

        @Test
        @DisplayName("empty ID + empty path + matching name still resolves")
        void bareNameReferenceResolves() {
            TomcatInfo persisted = new TomcatInfo();
            persisted.setName("Tomcat 10");
            persisted.setId("");
            persisted.setPath("");

            TomcatInfo resolved = state.resolve(persisted);

            assertSame(registered, resolved);
        }

        @Test
        @DisplayName("ID match takes precedence over path drift")
        void idBeatsPath() {
            TomcatInfo persisted = new TomcatInfo("Irrelevant", "0.0", "/completely/unrelated");
            persisted.setId("registered-id-1");

            assertSame(registered, state.resolve(persisted));
        }

        @Test
        @DisplayName("path match takes precedence over name collision")
        void pathBeatsName() {
            TomcatInfo other = new TomcatInfo("Tomcat 10", "10.0", "/opt/tomcat10-other");
            other.setId("other-id");
            state.setTomcatInfos(List.of(registered, other));

            TomcatInfo persisted = new TomcatInfo("Tomcat 10", "9.0", "/opt/tomcat10-other");
            persisted.setId("unknown");

            // Path match should win over name match — returns `other`, not `registered`.
            assertSame(other, state.resolve(persisted));
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

    @Nested
    @DisplayName("resolveOrAutoRegister() — self-heal for persisted-but-unregistered references")
    class ResolveOrAutoRegister {

        @Test
        @DisplayName("null persisted returns null without side effects")
        void nullPersistedReturnsNull() {
            TomcatServerManagerState state = new TomcatServerManagerState();

            TomcatInfo resolved = state.resolveOrAutoRegister(null);

            assertNull(resolved);
            assertTrue(state.getTomcatInfos().isEmpty());
        }

        @Test
        @DisplayName("already-registered reference resolves without adding a duplicate")
        void alreadyRegisteredResolvesWithoutDuplicate() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            TomcatInfo registered = new TomcatInfo("Tomcat 10", "10.1.28", "/opt/tomcat10");
            state.addTomcatInfo(registered);

            TomcatInfo resolved = state.resolveOrAutoRegister(registered);

            assertSame(registered, resolved);
            assertEquals(1, state.getTomcatInfos().size());
        }

        @Test
        @DisplayName("valid on-disk path not in registry triggers auto-registration preserving the persisted name")
        void autoRegistersWhenPathValid(@TempDir Path tempDir) throws IOException {
            TomcatServerManagerState state = new TomcatServerManagerState();
            Path tomcatHome = createTomcatHome(tempDir, "Apache Tomcat/11.0.15", "11.0.15");
            // A persisted reference whose ID does not match any registered server and
            // whose path was never registered — typical of a fresh IDE / VCS import.
            TomcatInfo persisted = new TomcatInfo("Tomcat", "11.0.15", tomcatHome.toString());
            // Drift: give persisted a wrong registered-id so ID lookup can't possibly
            // match and it has to go through the auto-registration path.
            state.addTomcatInfo(new TomcatInfo("Unrelated", "9.0.82", "/nope"));

            TomcatInfo resolved = state.resolveOrAutoRegister(persisted);

            assertNotNull(resolved);
            assertEquals("Tomcat", resolved.getName(),
                    "Auto-registration must preserve the persisted name so the user keeps their label");
            assertEquals(tomcatHome.toString(), resolved.getPath());
            assertEquals(2, state.getTomcatInfos().size(),
                    "Auto-registration must persist the new server via addTomcatInfo");
        }

        @Test
        @DisplayName("empty path returns null without auto-registering")
        void emptyPathReturnsNull() {
            TomcatServerManagerState state = new TomcatServerManagerState();
            TomcatInfo persisted = new TomcatInfo("Tomcat", "11.0.15", "");

            TomcatInfo resolved = state.resolveOrAutoRegister(persisted);

            assertNull(resolved);
            assertTrue(state.getTomcatInfos().isEmpty(),
                    "An empty path is not a Tomcat install — must not be auto-registered");
        }

        @Test
        @DisplayName("path missing on disk returns null without auto-registering")
        void missingPathReturnsNull(@TempDir Path tempDir) {
            TomcatServerManagerState state = new TomcatServerManagerState();
            TomcatInfo persisted = new TomcatInfo(
                    "Tomcat", "11.0.15", tempDir.resolve("does-not-exist").toString());

            TomcatInfo resolved = state.resolveOrAutoRegister(persisted);

            assertNull(resolved);
            assertTrue(state.getTomcatInfos().isEmpty());
        }

        @Test
        @DisplayName("path exists but is not a Tomcat install returns null without auto-registering")
        void pathExistsButNotTomcat(@TempDir Path tempDir) throws IOException {
            TomcatServerManagerState state = new TomcatServerManagerState();
            Path notTomcat = Files.createDirectory(tempDir.resolve("some-other-dir"));
            TomcatInfo persisted = new TomcatInfo("Tomcat", "11.0.15", notTomcat.toString());

            TomcatInfo resolved = state.resolveOrAutoRegister(persisted);

            assertNull(resolved,
                    "A directory with no catalina.jar is not a Tomcat install — must not be auto-registered");
            assertTrue(state.getTomcatInfos().isEmpty());
        }

        @Test
        @DisplayName("auto-registered entry is resolvable by path on subsequent calls")
        void subsequentCallsResolveWithoutReRegistering(@TempDir Path tempDir) throws IOException {
            TomcatServerManagerState state = new TomcatServerManagerState();
            Path tomcatHome = createTomcatHome(tempDir, "Apache Tomcat/11.0.15", "11.0.15");
            TomcatInfo persisted = new TomcatInfo("Tomcat", "11.0.15", tomcatHome.toString());

            TomcatInfo first = state.resolveOrAutoRegister(persisted);
            TomcatInfo second = state.resolveOrAutoRegister(persisted);

            assertNotNull(first);
            assertSame(first, second, "Second call must resolve the already-auto-registered instance, not add another");
            assertEquals(1, state.getTomcatInfos().size());
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
