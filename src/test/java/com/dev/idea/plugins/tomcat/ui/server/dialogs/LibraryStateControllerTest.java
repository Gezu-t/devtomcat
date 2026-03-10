package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link LibraryStateController} — the extracted controller that the
 * Application Servers dialog delegates to for all library state transitions.
 *
 * <p>Unlike model-level tests, these exercise the same production code paths
 * the dialog uses: {@code onServerLoaded}, {@code onHomeChanged},
 * {@code resolveLibraries}, {@code addLibraries}, {@code removeLibrary},
 * and {@code persistLibraries}.
 */
@DisplayName("LibraryStateController")
class LibraryStateControllerTest {

    private LibraryStateController controller;
    private TomcatInfo server;

    @BeforeEach
    void setUp() {
        controller = new LibraryStateController();
        server = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat10");
        controller.onServerLoaded(server);
    }

    // =========================================================================
    // onServerLoaded
    // =========================================================================

    @Nested
    @DisplayName("onServerLoaded")
    class OnServerLoaded {

        @Test
        @DisplayName("sets confirmed home to server path")
        void setsConfirmedHome() {
            assertEquals("/opt/tomcat10", controller.getConfirmedHome());
        }

        @Test
        @DisplayName("switching servers updates confirmed home")
        void switchingUpdatesConfirmedHome() {
            TomcatInfo server2 = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
            controller.onServerLoaded(server2);
            assertEquals("/opt/tomcat9", controller.getConfirmedHome());
        }
    }

    // =========================================================================
    // onHomeChanged
    // =========================================================================

    @Nested
    @DisplayName("onHomeChanged")
    class OnHomeChanged {

        @Test
        @DisplayName("valid new home clears custom libraries")
        void validNewHomeClears() {
            server.setLibraries(List.of("/custom/a.jar"));
            controller.onHomeChanged(server, "/opt/tomcat11", true);

            assertFalse(server.hasCustomLibraries());
            assertEquals("/opt/tomcat11", controller.getConfirmedHome());
        }

        @Test
        @DisplayName("failed detection does not clear custom libraries")
        void failedDetectionDoesNotClear() {
            server.setLibraries(List.of("/custom/a.jar"));
            controller.onHomeChanged(server, "/opt/tom", false);

            assertTrue(server.hasCustomLibraries());
            assertEquals("/opt/tomcat10", controller.getConfirmedHome());
        }

        @Test
        @DisplayName("same home with valid detection does not clear")
        void sameHomeDoesNotClear() {
            server.setLibraries(List.of("/custom/a.jar"));
            controller.onHomeChanged(server, "/opt/tomcat10", true);

            assertTrue(server.hasCustomLibraries());
        }

        @Test
        @DisplayName("rapid keystrokes with failed detection preserve libraries")
        void rapidKeystrokesPreserve() {
            server.setLibraries(List.of("/custom/important.jar"));

            for (String partial : List.of("/o", "/op", "/opt", "/opt/", "/opt/n",
                    "/opt/ne", "/opt/new")) {
                controller.onHomeChanged(server, partial, false);
            }

            assertTrue(server.hasCustomLibraries());
            assertEquals(List.of("/custom/important.jar"), server.getLibraries());
            assertEquals("/opt/tomcat10", controller.getConfirmedHome());
        }

        @Test
        @DisplayName("consecutive valid home changes each reset libraries")
        void consecutiveValidChanges() {
            controller.onHomeChanged(server, "/opt/tomcat11", true);
            server.setLibraries(List.of("/curated.jar"));

            controller.onHomeChanged(server, "/opt/tomcat9", true);
            assertFalse(server.hasCustomLibraries());
            assertEquals("/opt/tomcat9", controller.getConfirmedHome());
        }
    }

    // =========================================================================
    // resolveLibraries
    // =========================================================================

    @Nested
    @DisplayName("resolveLibraries")
    class ResolveLibraries {

        @Test
        @DisplayName("returns custom libraries when set")
        void returnsCustomLibraries() {
            server.setLibraries(List.of("/my/a.jar", "/my/b.jar"));
            List<String> result = controller.resolveLibraries(server, "/opt/tomcat10");

            assertEquals(List.of("/my/a.jar", "/my/b.jar"), result);
        }

        @Test
        @DisplayName("returns filtered defaults when no custom libraries")
        void returnsFilteredDefaults(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("servlet-api.jar"));
            Files.createFile(libDir.resolve("jsp-api.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            List<String> result = controller.resolveLibraries(server, tempDir.toString());

            assertEquals(2, result.size());
            assertTrue(result.get(0).endsWith("jsp-api.jar"));
            assertTrue(result.get(1).endsWith("servlet-api.jar"));
        }

        @Test
        @DisplayName("returns Jakarta defaults for Tomcat 10+")
        void returnsJakartaDefaults(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("jakarta.servlet-api-6.0.0.jar"));
            Files.createFile(libDir.resolve("jakarta.servlet.jsp-api-3.1.1.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            List<String> result = controller.resolveLibraries(server, tempDir.toString());

            assertEquals(2, result.size());
            assertTrue(result.get(0).endsWith("jakarta.servlet-api-6.0.0.jar"));
            assertTrue(result.get(1).endsWith("jakarta.servlet.jsp-api-3.1.1.jar"));
        }

        @Test
        @DisplayName("returns empty for null home")
        void returnsEmptyForNullHome() {
            assertEquals(List.of(), controller.resolveLibraries(server, null));
        }

        @Test
        @DisplayName("returns empty for blank home")
        void returnsEmptyForBlankHome() {
            assertEquals(List.of(), controller.resolveLibraries(server, "  "));
        }

        @Test
        @DisplayName("after home change reset, returns new defaults")
        void afterResetReturnsNewDefaults(@TempDir Path tempDir) throws IOException {
            server.setLibraries(List.of("/old/custom.jar"));

            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("jakarta.servlet-api-6.0.jar"));
            Files.createFile(libDir.resolve("catalina.jar"));

            controller.onHomeChanged(server, tempDir.toString(), true);

            List<String> result = controller.resolveLibraries(server, tempDir.toString());
            assertEquals(1, result.size());
            assertTrue(result.get(0).endsWith("jakarta.servlet-api-6.0.jar"));
        }
    }

    // =========================================================================
    // addLibraries / removeLibrary / persistLibraries
    // =========================================================================

    @Nested
    @DisplayName("add, remove, and persist")
    class AddRemovePersist {

        @Test
        @DisplayName("addLibraries marks server as custom")
        void addMarksCustom() {
            assertFalse(server.hasCustomLibraries());
            controller.addLibraries(server, "/opt/tomcat10", List.of("/extra.jar"));
            assertTrue(server.hasCustomLibraries());
        }

        @Test
        @DisplayName("addLibraries merges with current resolved list")
        void addMerges(@TempDir Path tempDir) throws IOException {
            Path libDir = Files.createDirectory(tempDir.resolve("lib"));
            Files.createFile(libDir.resolve("servlet-api.jar"));

            controller.addLibraries(server, tempDir.toString(), List.of("/extra.jar"));

            List<String> libs = server.getLibraries();
            assertEquals(2, libs.size());
            assertTrue(libs.get(0).endsWith("servlet-api.jar"));
            assertEquals("/extra.jar", libs.get(1));
        }

        @Test
        @DisplayName("removeLibrary updates persisted list")
        void removeUpdates() {
            server.setLibraries(List.of("/a.jar", "/b.jar", "/c.jar"));
            controller.removeLibrary(server, server.getPath(), "/b.jar");

            assertEquals(List.of("/a.jar", "/c.jar"), server.getLibraries());
        }

        @Test
        @DisplayName("removeLibrary on last jar leaves empty custom list")
        void removeLastLeavesEmpty() {
            server.setLibraries(List.of("/only.jar"));
            controller.removeLibrary(server, server.getPath(), "/only.jar");

            assertTrue(server.hasCustomLibraries());
            assertTrue(server.getLibraries().isEmpty());
        }

        @Test
        @DisplayName("persistLibraries stores exact tree state")
        void persistStoresExactState() {
            List<String> treePaths = List.of("/x.jar", "/y.jar", "/z.jar");
            controller.persistLibraries(server, treePaths);

            assertEquals(treePaths, server.getLibraries());
        }
    }

    // =========================================================================
    // Server selection switching
    // =========================================================================

    @Nested
    @DisplayName("server switching")
    class ServerSwitching {

        @Test
        @DisplayName("switching servers resets confirmed home and preserves each server's libraries")
        void switchPreservesIndependentState() {
            TomcatInfo server1 = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
            server1.setLibraries(List.of("/server1/custom.jar"));

            TomcatInfo server2 = new TomcatInfo("Tomcat 10", "10.1.2", "/opt/tomcat10");
            server2.setLibraries(List.of("/server2/a.jar", "/server2/b.jar"));

            // Load server1
            controller.onServerLoaded(server1);
            assertEquals("/opt/tomcat9", controller.getConfirmedHome());
            assertEquals(List.of("/server1/custom.jar"),
                    controller.resolveLibraries(server1, "/opt/tomcat9"));

            // Switch to server2
            controller.onServerLoaded(server2);
            assertEquals("/opt/tomcat10", controller.getConfirmedHome());
            assertEquals(List.of("/server2/a.jar", "/server2/b.jar"),
                    controller.resolveLibraries(server2, "/opt/tomcat10"));

            // Server1's libraries remain unchanged
            assertTrue(server1.hasCustomLibraries());
            assertEquals(List.of("/server1/custom.jar"), server1.getLibraries());
        }

        @Test
        @DisplayName("home change on server A does not affect server B")
        void homeChangeIsolated() {
            TomcatInfo serverA = new TomcatInfo("A", "10", "/opt/a");
            serverA.setLibraries(List.of("/a/custom.jar"));
            TomcatInfo serverB = new TomcatInfo("B", "10", "/opt/b");
            serverB.setLibraries(List.of("/b/custom.jar"));

            controller.onServerLoaded(serverA);
            controller.onHomeChanged(serverA, "/opt/a-new", true);

            // A's libraries cleared
            assertFalse(serverA.hasCustomLibraries());
            // B's libraries untouched
            assertTrue(serverB.hasCustomLibraries());
            assertEquals(List.of("/b/custom.jar"), serverB.getLibraries());
        }

        @Test
        @DisplayName("invalid edit, switch away, switch back, revert to valid home preserves libraries")
        void invalidEditSwitchBackRevertPreservesLibraries() {
            TomcatInfo serverA = new TomcatInfo("A", "10", "/opt/tomcat-a");
            serverA.setLibraries(List.of("/a/custom.jar"));
            TomcatInfo serverB = new TomcatInfo("B", "10", "/opt/tomcat-b");

            // Load server A — confirmed home is /opt/tomcat-a
            controller.onServerLoaded(serverA);
            assertEquals("/opt/tomcat-a", controller.getConfirmedHome());

            // User types partial invalid path (failed detection, no reset)
            controller.onHomeChanged(serverA, "/opt/tom", false);
            assertTrue(serverA.hasCustomLibraries());

            // Simulate dialog storing invalid path into model (as the real dialog does)
            serverA.setPath("/opt/tom");

            // Switch to server B
            controller.onServerLoaded(serverB);
            assertEquals("/opt/tomcat-b", controller.getConfirmedHome());

            // Switch back to server A — confirmed home should be /opt/tomcat-a (not /opt/tom)
            controller.onServerLoaded(serverA);
            assertEquals("/opt/tomcat-a", controller.getConfirmedHome());

            // User reverts to valid original home — same as confirmed, so no reset
            controller.onHomeChanged(serverA, "/opt/tomcat-a", true);
            assertTrue(serverA.hasCustomLibraries(),
                    "Custom libraries should be preserved when reverting to the original confirmed home");
            assertEquals(List.of("/a/custom.jar"), serverA.getLibraries());
        }
    }
}
