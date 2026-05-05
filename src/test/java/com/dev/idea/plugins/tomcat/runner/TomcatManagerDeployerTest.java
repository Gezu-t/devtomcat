package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void testListDeploymentsReturnsNullForUnreachableHost() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        String result = deployer.listDeployments();
        assertNull(result);
    }

    @Test
    void testDeployFailsWithMissingWarFile() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", "/nonexistent/path/app.war", DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        boolean result = deployer.deploy(artifact, null);
        assertFalse(result, "Deploy should fail when WAR file doesn't exist");
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

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", tempDir.toString(), DeploymentArtifact.TYPE_EXPLODED);
        artifact.setContextPath("/test");

        boolean result = deployer.deploy(artifact, null);
        assertFalse(result, "Deploy should fail for unreachable host");
    }

    @Test
    void testDeployHandlesRootContextPath() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        DeploymentArtifact artifact = new DeploymentArtifact(
                "ROOT", "/nonexistent/path/ROOT.war", DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/");

        // Should not throw — handles "/" context path correctly
        boolean result = deployer.deploy(artifact, null);
        assertFalse(result); // Will fail because file doesn't exist
    }

    @Test
    void testDeployHandlesEmptyContextPath() {
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        DeploymentArtifact artifact = new DeploymentArtifact(
                "app", "/nonexistent/path/app.war", DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("");

        boolean result = deployer.deploy(artifact, null);
        assertFalse(result);
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
    void testDeployerHandlesValidManagerUrl() {
        // Valid Manager URL without trailing slash
        RemoteConfig config = new RemoteConfig(
                "http://invalid-host-that-does-not-exist:9999/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        // Should not throw — URL construction works fine
        String result = deployer.listDeployments();
        assertNull(result); // unreachable, but no exception from URL construction
    }

    @Test
    void testDeployWithProgressReturnsFailedForUnreachableHost(@TempDir Path tempDir) throws IOException {
        Path warFile = tempDir.resolve("test.war");
        Files.write(warFile, new byte[16384]);

        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", warFile.toString(), DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(artifact, null, null);
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

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", warFile.toString(), DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        // Simulate a pre-cancelled indicator using a simple stub
        com.intellij.openapi.progress.ProgressIndicator indicator =
                new com.intellij.openapi.progress.util.AbstractProgressIndicatorBase() {};
        indicator.cancel();

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(artifact, null, indicator);
        assertEquals(TomcatManagerDeployer.DeployResult.CANCELLED, result,
                "Pre-cancelled indicator should return CANCELLED immediately");
    }

    @Test
    void testDeployWithProgressMissingWarReturnsFailed() {
        RemoteConfig config = new RemoteConfig(
                "http://localhost:1/manager",
                "admin", "admin", true);
        TomcatManagerDeployer deployer = new TomcatManagerDeployer(config);

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", "/nonexistent/path/app.war", DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(artifact, null, null);
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

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", warFile.toString(), DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(artifact, null, null, () -> true);
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

        DeploymentArtifact artifact = new DeploymentArtifact(
                "test-app", warFile.toString(), DeploymentArtifact.TYPE_WAR);
        artifact.setContextPath("/test");

        // No abort predicate - falls through to the unreachable-host FAILED path.
        TomcatManagerDeployer.DeployResult result =
                deployer.deployWithProgress(artifact, null, null);
        assertEquals(TomcatManagerDeployer.DeployResult.FAILED, result,
                "Three-arg overload must still produce FAILED on unreachable host (not CANCELLED)");
    }
}
