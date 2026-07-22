package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.module.ModulePointerManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Platform pin for the "Rebuild and Deploy" re-verdict contract: after the
 * rebuild chain, {@link TomcatApplicationUpdater#redeployBlockedWithReVerdict}
 * must re-evaluate staleness against the LIVE project model — a still-stale
 * WAR stays blocked; only a genuinely fresh WAR deploys. Swapping the
 * evaluator for a constant (the tautology this test exists to kill) would
 * deploy in the still-stale phase and fail here.
 */
public class TomcatApplicationUpdaterPlatformTest extends BasePlatformTestCase {

    private File outputDir;
    private File deployDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Real-disk compiler output for the fixture module: the staleness walk
        // reads the filesystem, so the in-memory test VFS is not enough.
        outputDir = FileUtil.createTempDirectory("web-module-out", null);
        deployDir = FileUtil.createTempDirectory("webapps", null);
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputDir);
        PsiTestUtil.setCompilerOutputPath(getModule(),
                VfsUtilCore.pathToUrl(outputDir.getAbsolutePath()), false);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileUtil.delete(outputDir);
            FileUtil.delete(deployDir);
        } finally {
            super.tearDown();
        }
    }

    public void testReVerdictBlocksStillStaleWarThenDeploysFreshOne() throws Exception {
        Path classFile = outputDir.toPath().resolve("A.class");
        Files.writeString(classFile, "class-bytes");
        Files.setLastModifiedTime(classFile, FileTime.fromMillis(300_000L));

        Path war = outputDir.toPath().getParent().resolve("app-1.0.0.war");
        Files.writeString(war, "war-bytes");
        Files.setLastModifiedTime(war, FileTime.fromMillis(100_000L));

        Deployment blocked = new ModuleBackedDeployment(
                ModulePointerManager.getInstance(getProject()).create(getModule()),
                war, "/app", false);
        Path webapps = deployDir.toPath();
        TomcatDeploymentLogger logger = new TomcatDeploymentLogger(getProject());

        // Phase 1 — the WAR predates the module's compiled output: the
        // re-verdict path must evaluate it stale and keep it blocked.
        TomcatApplicationUpdater.redeployBlockedWithReVerdict(
                getProject(), List.of(blocked), webapps, logger);
        assertFalse("a WAR still stale after the rebuild must stay blocked",
                Files.exists(webapps.resolve("app.war")));

        // Phase 2 — the WAR now outdates every output file (a real rebuild
        // happened): the same call must evaluate it fresh and deploy it.
        Files.setLastModifiedTime(war, FileTime.fromMillis(500_000L));
        TomcatApplicationUpdater.redeployBlockedWithReVerdict(
                getProject(), List.of(blocked), webapps, logger);
        assertTrue("a genuinely fresh WAR must deploy through the re-verdict path",
                Files.exists(webapps.resolve("app.war")));
        assertEquals("war-bytes", Files.readString(webapps.resolve("app.war")));
    }
}
