package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Integration coverage of {@link WarToExplodedQuickFix#applyAll} against a real
 * run configuration. The unit tests cover candidate detection; these pin the
 * load-bearing parts of the write-back:
 * <ul>
 *   <li>Candidates are matched on the RESOLVED view but the written list starts
 *       from STORAGE — a non-candidate entry the resolver folds (dangling
 *       artifact→module) must keep its artifact provenance after applyAll.</li>
 *   <li>Consumed resolved slots are mirrored so duplicate candidates can't
 *       re-match the same index.</li>
 * </ul>
 */
public class WarToExplodedQuickFixPlatformTest extends BasePlatformTestCase {

    private File workDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        workDir = FileUtil.createTempDirectory("war-to-exploded", null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileUtil.delete(workDir);
        } finally {
            super.tearDown();
        }
    }

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], name);
    }

    /** {@code target/<name>.war} alongside {@code target/<name>/WEB-INF/}. */
    private Path createMavenWebappLayout(String warName) throws Exception {
        Path target = workDir.toPath().resolve("target");
        Files.createDirectories(target.resolve(warName).resolve("WEB-INF"));
        Path warFile = target.resolve(warName + ".war");
        Files.createFile(warFile);
        return warFile;
    }

    public void testApplyAllWritesStoredListAndPreservesNonCandidateProvenance() throws Exception {
        Path warFile = createMavenWebappLayout("app");
        String moduleName = getModule().getName();

        TomcatRunConfiguration cfg = createConfig("ApplyAllStored");
        DeploymentConfig deploymentConfig = cfg.getConfigData().getDeploymentConfig();

        // Slot 0: dangling artifact-backed WAR entry. The resolver folds it to
        // module-backed (stored name == module name), and the folded war shape
        // with a sibling exploded directory makes it a fix candidate.
        Deployment candidateEntry = ArtifactBackedDeployment.ofName(
                getProject(), moduleName, "/app", warFile.toString(), false);
        // Slot 1: dangling artifact-backed entry that also folds in the
        // resolved view but is NOT a candidate (already exploded, module
        // shape). If applyAll persisted the resolved view instead of starting
        // from storage, this entry's artifact provenance would be destroyed.
        Deployment bystander = ArtifactBackedDeployment.ofName(
                getProject(), moduleName, "/other",
                workDir.toPath().resolve("other-out").toString(), true);
        deploymentConfig.setDeployments(List.of(candidateEntry, bystander));

        // Sanity: both entries fold in the resolved view.
        List<Deployment> resolved = deploymentConfig.getDeployments(getProject());
        assertInstanceOf(resolved.get(0), ModuleBackedDeployment.class);
        assertInstanceOf(resolved.get(1), ModuleBackedDeployment.class);

        List<WarToExplodedQuickFix.FixCandidate> candidates =
                WarToExplodedQuickFix.findFixableArtifacts(
                        path -> moduleName, resolved);
        assertEquals(1, candidates.size());

        int applied = WarToExplodedQuickFix.applyAll(cfg, candidates);

        assertEquals(1, applied);
        List<Deployment> stored = deploymentConfig.getDeployments();
        ModuleBackedDeployment flipped =
                assertInstanceOf(stored.get(0), ModuleBackedDeployment.class);
        assertEquals(moduleName, flipped.getModuleName());
        assertEquals(warFile.getParent().resolve("app"), flipped.getOutputPath());
        assertTrue(flipped.isExploded());
        assertEquals("context path carries over from the replaced entry",
                "/app", flipped.getContextPath());
        // The untouched entry keeps its artifact provenance: the resolver's
        // fold must not leak into storage through the quick fix's write-back.
        ArtifactBackedDeployment keptBystander =
                assertInstanceOf(stored.get(1), ArtifactBackedDeployment.class);
        assertEquals(moduleName, keptBystander.getArtifactName());
        assertEquals("/other", keptBystander.getContextPath());
        assertTrue(keptBystander.getLastKnownExploded());
    }

    public void testDuplicateCandidatesConsumeDistinctSlots() throws Exception {
        Path warFile = createMavenWebappLayout("app");
        String moduleName = getModule().getName();

        TomcatRunConfiguration cfg = createConfig("ApplyAllDuplicates");
        DeploymentConfig deploymentConfig = cfg.getConfigData().getDeploymentConfig();

        // Two value-equal external WAR entries. Each yields a candidate; the
        // second candidate's indexOf must not re-match slot 0 after slot 0 is
        // consumed — applyAll mirrors replacements into its resolved snapshot.
        Deployment first = new ExternalFileDeployment(warFile, "/", false);
        Deployment second = new ExternalFileDeployment(warFile, "/", false);
        deploymentConfig.setDeployments(List.of(first, second));

        List<WarToExplodedQuickFix.FixCandidate> candidates =
                WarToExplodedQuickFix.findFixableArtifacts(
                        path -> moduleName, deploymentConfig.getDeployments(getProject()));
        assertEquals(2, candidates.size());

        int applied = WarToExplodedQuickFix.applyAll(cfg, candidates);

        assertEquals(2, applied);
        List<Deployment> stored = deploymentConfig.getDeployments();
        assertInstanceOf(stored.get(0), ModuleBackedDeployment.class);
        // The second candidate must consume the second slot, not re-flip the first.
        assertInstanceOf(stored.get(1), ModuleBackedDeployment.class);
    }
}
