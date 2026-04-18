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

    public void testIsolatedBaseIsUnderRunsSubtree() {
        TomcatRunConfiguration cfg = createConfig("Shape");
        Path p = TomcatProjectUtils.getCatalinaBase(cfg, "run-xyz");
        assertNotNull(p);
        Path parent = p.getParent();
        assertNotNull("per-run base must have a parent", parent);
        assertEquals("parent segment must be the PARALLEL_RUNS_SUBDIR constant",
                TomcatProjectUtils.PARALLEL_RUNS_SUBDIR, parent.getFileName().toString());
    }

    public void testPinnedBaseParentIsNotRunsSubtree() throws IOException {
        // This is the contract that prevents the data-loss bug: if the user pinned
        // a CATALINA_BASE, the resolved path's parent must NOT look like the
        // isolated ".runs" subtree, so TomcatProcessHandler.cleanupParallelRunBase()
        // refuses to delete it.
        TomcatRunConfiguration cfg = createConfig("PinCheck");
        Path pinned = Files.createTempDirectory("devtomcat-pin-check-");
        cfg.getConfigData().setCatalinaBase(pinned.toString());

        Path resolved = TomcatProjectUtils.getCatalinaBase(cfg, "run-abc");
        assertEquals(pinned, resolved);
        Path parent = resolved.getParent();
        if (parent != null && parent.getFileName() != null) {
            assertFalse("pinned base's parent must not be the '.runs' isolation subtree",
                    TomcatProjectUtils.PARALLEL_RUNS_SUBDIR.equals(parent.getFileName().toString()));
        }
    }

    public void testWebappsDirectoryIsolatedPerRun() {
        TomcatRunConfiguration cfg = createConfig("Webapps");
        Path shared = TomcatProjectUtils.getWebappsDirectory(cfg, null);
        Path isolated = TomcatProjectUtils.getWebappsDirectory(cfg, "run-1");

        assertNotNull(shared);
        assertNotNull(isolated);
        assertTrue("shared webapps must end with /webapps", shared.endsWith("webapps"));
        assertTrue("isolated webapps must also end with /webapps", isolated.endsWith("webapps"));
        assertTrue("isolated webapps must sit under the .runs isolation subtree",
                isolated.toString().contains("/" + TomcatProjectUtils.PARALLEL_RUNS_SUBDIR + "/"));
        assertFalse("isolated and shared webapps must differ",
                isolated.equals(shared));
    }

    public void testLogsAndConfAndWorkAlsoIsolatedPerRun() {
        TomcatRunConfiguration cfg = createConfig("Dirs");
        Path logsShared = TomcatProjectUtils.getLogsDirectory(cfg, null);
        Path logsPerRun = TomcatProjectUtils.getLogsDirectory(cfg, "run-1");
        Path workShared = TomcatProjectUtils.getWorkDirectory(cfg, null);
        Path workPerRun = TomcatProjectUtils.getWorkDirectory(cfg, "run-1");
        Path confShared = TomcatProjectUtils.getConfDirectory(cfg, null);
        Path confPerRun = TomcatProjectUtils.getConfDirectory(cfg, "run-1");

        assertFalse(logsPerRun.equals(logsShared));
        assertFalse(workPerRun.equals(workShared));
        assertFalse(confPerRun.equals(confShared));
    }

    // =========================================================================
    // isParallelRunEffective() — the single authoritative predicate
    // =========================================================================

    public void testParallelRunEffectiveOnlyWhenCheckboxOnAndBaseNotPinned() throws IOException {
        TomcatRunConfiguration cfg = createConfig("Predicate");

        // Default: checkbox off, no pin → not effective
        assertFalse("checkbox off => not effective", cfg.isParallelRunEffective());

        // Checkbox on, no pin → effective
        cfg.setAllowMultipleInstances(true);
        assertTrue("checkbox on + no pin => effective", cfg.isParallelRunEffective());

        // Checkbox on, base pinned → NOT effective (isolation impossible)
        Path pinned = Files.createTempDirectory("devtomcat-pred-pin-");
        cfg.getConfigData().setCatalinaBase(pinned.toString());
        assertFalse("checkbox on + pin => not effective", cfg.isParallelRunEffective());

        // Checkbox on, base pinned to empty string → treat as no pin
        cfg.getConfigData().setCatalinaBase("");
        assertTrue("checkbox on + empty-string pin => effective",
                cfg.isParallelRunEffective());

        // Checkbox on, base pinned to whitespace → treat as no pin
        cfg.getConfigData().setCatalinaBase("   ");
        assertTrue("checkbox on + whitespace-only pin => effective",
                cfg.isParallelRunEffective());

        // Checkbox off, base pinned → not effective regardless of pin
        cfg.getConfigData().setCatalinaBase(pinned.toString());
        cfg.setAllowMultipleInstances(false);
        assertFalse("checkbox off => not effective even if pinned",
                cfg.isParallelRunEffective());
    }
}
