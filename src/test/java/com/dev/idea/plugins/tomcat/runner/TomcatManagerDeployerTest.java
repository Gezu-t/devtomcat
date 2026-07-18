package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TomcatManagerDeployer}.
 * These tests verify URL construction, context normalization, and error handling
 * without requiring a real Tomcat Manager instance.
 */
class TomcatManagerDeployerTest {

    @Test
    void testConnectionFailsWithInvalidHost() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        String error = deployer.testConnection();

        assertNotNull(error, "Should return error for unreachable host");
        assertTrue(error.contains("Connection") || error.contains("Unknown host") || error.contains("timed out"),
                "Error should mention connection issue: " + error);
    }

    @Test
    void testUndeployReturnsGracefullyForUnreachableHost() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        // Should not throw — returns false gracefully
        boolean result = deployer.undeploy("/test", null);
        assertFalse(result);
    }

    @Test
    void testDeployFailsForExplodedWithUnreachableHost(@TempDir Path tempDir) throws IOException {
        // Create a fake exploded directory
        Path webInf = tempDir.resolve("WEB-INF");
        Files.createDirectories(webInf);

        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(tempDir, "/test", true);

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result,
                "Deploy should fail for unreachable host");
    }

    @Test
    void testDeployHandlesRootContextPath() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(
                Path.of("/nonexistent/path/ROOT.war"), "/", false);

        // Should not throw — handles "/" context path correctly
        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result); // fails: file doesn't exist
    }

    @Test
    void testDeployHandlesEmptyContextPath() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        // Empty context path folds to "/" at the typed-model boundary.
        Deployment deployment = new ExternalFileDeployment(
                Path.of("/nonexistent/path/app.war"), "", false);

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result);
    }

    @Test
    void testDeployWithoutCredentials() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "", "", false);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        String error = deployer.testConnection();
        assertNotNull(error, "Should return error for unreachable host even without credentials");
    }

    @Test
    void testDeployWithProgressReturnsFailedForUnreachableHost(@TempDir Path tempDir) throws IOException {
        Path warFile = tempDir.resolve("test.war");
        Files.write(warFile, new byte[16384]);

        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(warFile, "/test", false);

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result,
                "Unreachable host should return FAILED, not CANCELLED");
    }

    @Test
    void testDeployWithProgressReturnsCancelledWhenIndicatorPreCancelled(@TempDir Path tempDir) throws IOException {
        Path warFile = tempDir.resolve("test.war");
        Files.write(warFile, new byte[1024]);

        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(warFile, "/test", false);

        // Simulate a pre-cancelled indicator using a simple stub
        com.intellij.openapi.progress.ProgressIndicator indicator =
                new com.intellij.openapi.progress.util.AbstractProgressIndicatorBase() {};
        indicator.cancel();

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, indicator);
        assertEquals(TomcatManagerDeployer.DeployResult.CANCELLED, result,
                "Pre-cancelled indicator should return CANCELLED immediately");
    }

    @Test
    void testDeployWithProgressMissingWarReturnsFailed() {
        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(
                Path.of("/nonexistent/path/app.war"), "/test", false);

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result,
                "Missing WAR file should return FAILED");
    }

    @Test
    void testDeployResultEnumValues() {
        // Ensure all three states exist
        assertEquals(3, TomcatManagerDeployer.DeployResult.values().length);
        assertNotNull(TomcatManagerDeployer.DeployResult.SUCCESS);
        assertNotNull(TomcatManagerDeployer.DeployResult.FAILED);
        assertNotNull(TomcatManagerDeployer.DeployResult.CANCELLED);
    }

    @Test
    void testDeployWithProgressReturnsCancelledWhenAbortCheckTrueUpFront(@TempDir Path tempDir) throws IOException {
        // Regression for the 1.0.10 fix: deployWithProgress now accepts a
        // BooleanSupplier polled both before the upload starts and inside
        // the chunk loop, so the local Tomcat process terminating
        // mid-upload aborts the in-flight transfer instead of running it
        // to completion. The before-upload check is exercised here; the
        // mid-upload check is exercised against an unreachable host below.
        Path warFile = tempDir.resolve("test.war");
        Files.write(warFile, new byte[1024]);

        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(warFile, "/test", false);

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null, () -> true);
        assertEquals(TomcatManagerDeployer.DeployResult.CANCELLED, result,
                "Abort predicate returning true before upload starts must yield CANCELLED, not FAILED");
    }

    @Test
    void testDeployWithProgressNoArgOverloadStillWorks(@TempDir Path tempDir) throws IOException {
        // The three-arg overload preserved for backward compatibility must
        // still route to a valid no-op abort predicate.
        Path warFile = tempDir.resolve("test.war");
        Files.write(warFile, new byte[16384]);

        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        Deployment deployment = new ExternalFileDeployment(warFile, "/test", false);

        // No abort predicate - falls through to the unreachable-host FAILED path.
        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(deployment, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result,
                "Three-arg overload must still produce FAILED on unreachable host (not CANCELLED)");
    }

    @Test
    void testDeployWithProgressReturnsCancelledWhenAbortFlipsMidUpload(@TempDir Path tempDir) throws IOException {
        // Regression: aborting the WAR upload AFTER the first chunk has been
        // written must report CANCELLED, not FAILED. Under fixed-length
        // streaming, closing the half-written stream throws
        // "insufficient data written"; that expected close exception must not
        // be reclassified as a deployment failure. The existing () -> true test
        // only covers the pre-upload abort, never this in-flight close path.
        Path warFile = tempDir.resolve("big.war");
        Files.write(warFile, new byte[64 * 1024]); // several 8 KB chunks

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Drain whatever the client sends before it aborts, then reply.
            try (InputStream body = exchange.getRequestBody()) {
                byte[] sink = new byte[8192];
                while (body.read(sink) != -1) { /* consume until the client stops */ }
            } catch (IOException ignored) {
                // Client aborted mid-stream — expected.
            }
            try {
                byte[] ok = "OK - deployed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } catch (IOException ignored) {
                // Connection may already be gone after the client abort.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            RemoteConfig config = new RemoteConfig(
                    "http://127.0.0.1:" + port + "/manager", "admin", "admin", true);
            TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

            Deployment deployment = new ExternalFileDeployment(warFile, "/test", false);

            // Abort predicate polls: index 0 is the pre-upload check in
            // deployWithProgress, index 1 is the first in-loop poll (lets the
            // first chunk go out), index >= 2 aborts the in-flight upload.
            AtomicInteger polls = new AtomicInteger();
            TomcatManagerDeployer.DeployResult result =
                    deployer.deployWithProgress(deployment, null, null,
                            () -> polls.getAndIncrement() >= 2);

            assertEquals(TomcatManagerDeployer.DeployResult.CANCELLED, result,
                    "Aborting mid-upload must yield CANCELLED, not FAILED");
        } finally {
            server.stop(0);
        }
    }
}
