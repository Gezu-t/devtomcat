package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Platform-level tests for parallel-run CATALINA_BASE isolation.
 *
 * <p>Verifies that {@link TomcatProjectUtils#getCatalinaBase(TomcatRunConfiguration, String)}
 * returns isolated paths per {@code runId} and honours explicit overrides.
 * Uses {@link BasePlatformTestCase} so real {@link com.intellij.openapi.project.Project}
 * scaffolding is available for the path-hash derivation.
 */
public class ParallelRunBasePlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    public void testNullRunIdReturnsSharedBase() {
        TomcatRunConfiguration cfg = createConfig("Shared");
        Path shared = TomcatProjectUtils.getCatalinaBase(cfg, null);
        Path viaSingleArg = TomcatProjectUtils.getCatalinaBase(cfg);
        assertEquals("null runId must match single-arg form", viaSingleArg, shared);
    }

    public void testRunIdProducesIsolatedSubdirectory() {
        TomcatRunConfiguration cfg = createConfig("Iso");
        Path shared = TomcatProjectUtils.getCatalinaBase(cfg, null);
        Path perRun = TomcatProjectUtils.getCatalinaBase(cfg, "run-abc");

        assertNotNull(shared);
        assertNotNull(perRun);
        assertTrue("per-run base must be nested under shared",
                perRun.toString().startsWith(shared.toString()));
        assertTrue("per-run base must contain .runs segment",
                perRun.toString().contains(".runs"));
        assertTrue("per-run base must contain the sanitized runId",
                perRun.getFileName().toString().contains("run-abc"));
    }

    public void testDifferentRunIdsYieldDifferentDirectories() {
        TomcatRunConfiguration cfg = createConfig("Multi");
        Path a = TomcatProjectUtils.getCatalinaBase(cfg, "run-1");
        Path b = TomcatProjectUtils.getCatalinaBase(cfg, "run-2");
        assertFalse("each runId must resolve to its own directory", a.equals(b));
    }

    public void testExplicitCatalinaBaseOverridesRunId() throws IOException {
        TomcatRunConfiguration cfg = createConfig("Pinned");
        Path pinned = Files.createTempDirectory("devtomcat-pinned-base-");
        cfg.getConfigData().setCatalinaBase(pinned.toString());

        Path resolved = TomcatProjectUtils.getCatalinaBase(cfg, "run-ignored");
        assertEquals("explicit pin must win over runId", pinned, resolved);
    }

    public void testRunIdSanitization() {
        TomcatRunConfiguration cfg = createConfig("Sanitize");
        Path p = TomcatProjectUtils.getCatalinaBase(cfg, "run id/with*bad:chars");
        assertNotNull(p);
        String last = p.getFileName().toString();
        // sanitizeFileName strips path separators and special characters
        assertFalse("runId directory must not leak '/' into filename", last.contains("/"));
        assertFalse("runId directory must not leak '*'", last.contains("*"));
        assertFalse("runId directory must not leak ':'", last.contains(":"));
    }
}
