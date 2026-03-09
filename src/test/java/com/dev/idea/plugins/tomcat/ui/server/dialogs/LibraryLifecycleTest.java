package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the library lifecycle as experienced through the Application Servers dialog.
 *
 * <p>Since TomcatServerConfigurationDialog requires a live IntelliJ Project,
 * these tests exercise the same state transitions through TomcatInfo directly,
 * simulating the dialog's load → edit → home-change → persist flow.
 */
@DisplayName("Library lifecycle (dialog-level)")
class LibraryLifecycleTest {

    private TomcatInfo server;
    private String confirmedHome;

    @BeforeEach
    void setUp() {
        server = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat10");
        confirmedHome = server.getPath();
    }

    // =========================================================================
    // Helpers that mirror dialog behavior
    // =========================================================================

    /**
     * Simulates what updateLibrariesTree does: returns the library paths
     * the tree would show for the given home.
     */
    private List<String> resolveLibraries(String tomcatHome) {
        if (tomcatHome == null || tomcatHome.isBlank()) {
            return List.of();
        }
        if (server.hasCustomLibraries()) {
            return server.getLibraries();
        }
        return TomcatInfo.filterDefaultLibraries(new File(tomcatHome, "lib"));
    }

    /**
     * Simulates what updateSelectedServerFromDetails does when detection succeeds
     * and home changes.
     */
    private void simulateHomeChange(String newHome, boolean validDetection) {
        server.setPath(newHome);
        if (TomcatInfo.shouldResetLibraries(validDetection, newHome, confirmedHome)) {
            server.setLibraries(null);
            confirmedHome = newHome;
        }
    }

    /**
     * Simulates adding a JAR via the tree toolbar (persistTreeToSelectedServer).
     */
    private void simulateAddLibrary(String jarPath) {
        List<String> current = server.hasCustomLibraries()
                ? new ArrayList<>(server.getLibraries())
                : new ArrayList<>(resolveLibraries(server.getPath()));
        current.add(jarPath);
        server.setLibraries(current);
    }

    /**
     * Simulates removing a JAR via the tree toolbar.
     */
    private void simulateRemoveLibrary(String jarPath) {
        if (!server.hasCustomLibraries()) return;
        List<String> current = new ArrayList<>(server.getLibraries());
        current.remove(jarPath);
        server.setLibraries(current);
    }

    // =========================================================================
    // Default library display
    // =========================================================================

    @Nested
    @DisplayName("default library display")
    class DefaultDisplay {

        @Test
        @DisplayName("new server shows filtered defaults from disk")
        void newServerShowsFilteredDefaults(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("servlet-api.jar"));
            Files.createFile(libDir.resolve("jsp-api.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            server.setPath(tempDir.toString());
            List<String> displayed = resolveLibraries(tempDir.toString());

            assertEquals(2, displayed.size());
            assertTrue(displayed.stream().allMatch(p ->
                    p.endsWith("servlet-api.jar") || p.endsWith("jsp-api.jar")));
        }

        @Test
        @DisplayName("Jakarta-era Tomcat shows jakarta API jars by default")
        void jakartaDefaultsShown(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("jakarta.servlet-api-6.0.0.jar"));
            Files.createFile(libDir.resolve("jakarta.servlet.jsp-api-3.1.1.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            server.setPath(tempDir.toString());
            List<String> displayed = resolveLibraries(tempDir.toString());

            assertEquals(2, displayed.size());
            assertTrue(displayed.get(0).endsWith("jakarta.servlet-api-6.0.0.jar"));
            assertTrue(displayed.get(1).endsWith("jakarta.servlet.jsp-api-3.1.1.jar"));
        }
    }

    // =========================================================================
    // Add / remove persistence
    // =========================================================================

    @Nested
    @DisplayName("add and remove libraries")
    class AddRemove {

        @Test
        @DisplayName("adding a JAR marks libraries as custom")
        void addingJarMarksCustom() {
            assertFalse(server.hasCustomLibraries());
            simulateAddLibrary("/extra/lib/commons-io.jar");
            assertTrue(server.hasCustomLibraries());
        }

        @Test
        @DisplayName("added JAR appears in persisted list")
        void addedJarPersisted() {
            simulateAddLibrary("/extra/lib/commons-io.jar");
            assertTrue(server.getLibraries().contains("/extra/lib/commons-io.jar"));
        }

        @Test
        @DisplayName("removing a JAR updates persisted list")
        void removingJarUpdatesPersistedList() {
            server.setLibraries(List.of("/a.jar", "/b.jar", "/c.jar"));
            simulateRemoveLibrary("/b.jar");
            assertEquals(List.of("/a.jar", "/c.jar"), server.getLibraries());
        }

        @Test
        @DisplayName("removing all JARs keeps custom flag with empty list")
        void removingAllKeepsCustomFlag() {
            server.setLibraries(List.of("/only.jar"));
            simulateRemoveLibrary("/only.jar");
            assertTrue(server.hasCustomLibraries());
            assertTrue(server.getLibraries().isEmpty());
        }
    }

    // =========================================================================
    // Home change and library reset
    // =========================================================================

    @Nested
    @DisplayName("home change resets custom libraries")
    class HomeChangeReset {

        @Test
        @DisplayName("changing to a valid new home clears custom libraries")
        void validNewHomeClears() {
            server.setLibraries(List.of("/custom/a.jar"));
            assertTrue(server.hasCustomLibraries());

            simulateHomeChange("/opt/tomcat11", true);

            assertFalse(server.hasCustomLibraries());
            assertEquals("/opt/tomcat11", confirmedHome);
        }

        @Test
        @DisplayName("typing a partial/invalid path does NOT clear custom libraries")
        void partialPathDoesNotClear() {
            server.setLibraries(List.of("/custom/a.jar"));

            simulateHomeChange("/opt/tom", false);

            assertTrue(server.hasCustomLibraries());
            assertEquals(1, server.getLibraries().size());
        }

        @Test
        @DisplayName("reverting to the same confirmed home does NOT clear")
        void revertToSameHomeDoesNotClear() {
            server.setLibraries(List.of("/custom/a.jar"));

            simulateHomeChange(confirmedHome, true);

            assertTrue(server.hasCustomLibraries());
        }

        @Test
        @DisplayName("after reset, defaults are shown again from new home")
        void afterResetDefaultsShownFromNewHome(@TempDir Path tempDir) throws IOException {
            server.setLibraries(List.of("/old/custom.jar"));

            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("jakarta.servlet-api-6.0.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            String newHome = tempDir.toString();
            simulateHomeChange(newHome, true);

            List<String> displayed = resolveLibraries(newHome);
            assertEquals(1, displayed.size());
            assertTrue(displayed.get(0).endsWith("jakarta.servlet-api-6.0.jar"));
        }

        @Test
        @DisplayName("multiple valid home changes each reset libraries")
        void multipleHomeChanges() {
            // First change
            simulateHomeChange("/opt/tomcat11", true);
            server.setLibraries(List.of("/curated.jar"));
            assertTrue(server.hasCustomLibraries());

            // Second change
            simulateHomeChange("/opt/tomcat9", true);
            assertFalse(server.hasCustomLibraries());
            assertEquals("/opt/tomcat9", confirmedHome);
        }

        @Test
        @DisplayName("rapid keystroke simulation does not wipe libraries")
        void rapidKeystrokeSimulation() {
            server.setLibraries(List.of("/custom/important.jar"));

            // Simulates typing "/opt/newpath" one character at a time
            for (String partial : List.of("/o", "/op", "/opt", "/opt/", "/opt/n",
                    "/opt/ne", "/opt/new", "/opt/newp", "/opt/newpa",
                    "/opt/newpat", "/opt/newpath")) {
                simulateHomeChange(partial, false);
            }

            // Libraries survive all keystrokes
            assertTrue(server.hasCustomLibraries());
            assertEquals(List.of("/custom/important.jar"), server.getLibraries());
        }
    }

    // =========================================================================
    // Server selection switching
    // =========================================================================

    @Nested
    @DisplayName("server selection switching")
    class ServerSwitching {

        @Test
        @DisplayName("switching servers preserves each server's custom libraries")
        void switchingPreservesLibraries() {
            TomcatInfo server1 = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
            server1.setLibraries(List.of("/server1/custom.jar"));

            TomcatInfo server2 = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat10");
            server2.setLibraries(List.of("/server2/a.jar", "/server2/b.jar"));

            // Simulate switching: each server retains its own libraries
            assertEquals(1, server1.getLibraries().size());
            assertEquals(2, server2.getLibraries().size());
            assertTrue(server1.getLibraries().contains("/server1/custom.jar"));
            assertTrue(server2.getLibraries().contains("/server2/a.jar"));
        }

        @Test
        @DisplayName("server without custom libraries shows defaults, server with customs shows customs")
        void mixedDefaultAndCustom(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("servlet-api.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            TomcatInfo defaultServer = new TomcatInfo("Default", "10", tempDir.toString());
            TomcatInfo customServer = new TomcatInfo("Custom", "10", tempDir.toString());
            customServer.setLibraries(List.of("/my/special.jar"));

            // Default server uses filtered defaults
            assertFalse(defaultServer.hasCustomLibraries());
            List<String> defaultLibs = TomcatInfo.filterDefaultLibraries(
                    new File(tempDir.toString(), "lib"));
            assertEquals(1, defaultLibs.size());
            assertTrue(defaultLibs.get(0).endsWith("servlet-api.jar"));

            // Custom server uses its own list
            assertTrue(customServer.hasCustomLibraries());
            assertEquals(List.of("/my/special.jar"), customServer.getLibraries());
        }
    }
}
