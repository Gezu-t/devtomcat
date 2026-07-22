package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Delivery;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Report;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Shape;
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

/**
 * Platform pin for {@link DeploymentFreshnessReport#build}: driven by a real
 * module with a real-disk compiler output, the report must read the LIVE
 * project model — a served copy older than the output shows STALE; touching
 * the served copy up to the output's mtime flips the same call to CURRENT.
 */
public class DeploymentFreshnessReportPlatformTest extends BasePlatformTestCase {

    private File outputDir;
    private File deployDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Real-disk compiler output: the freshness walk reads the filesystem,
        // so the in-memory test VFS is not enough.
        outputDir = FileUtil.createTempDirectory("web-module-out", null);
        deployDir = FileUtil.createTempDirectory("exploded-app", null);
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

    public void testStaleServedCopyThenTouchedCurrent() throws Exception {
        Path output = outputDir.toPath().resolve("App.class");
        Files.writeString(output, "class-bytes");
        Files.setLastModifiedTime(output, FileTime.fromMillis(300_000L));

        Path served = deployDir.toPath().resolve("WEB-INF/classes/App.class");
        Files.createDirectories(served.getParent());
        Files.writeString(served, "old-bytes");
        Files.setLastModifiedTime(served, FileTime.fromMillis(100_000L));

        Deployment deployment = new ModuleBackedDeployment(
                ModulePointerManager.getInstance(getProject()).create(getModule()),
                deployDir.toPath(), "/app", true);

        // Phase 1 — the served copy predates the compiled output: STALE, with
        // the moment the unserved change landed as the evidence.
        Report stale = DeploymentFreshnessReport.build(getProject(), deployment, null);
        assertEquals(Shape.EXPLODED, stale.shape());
        assertEquals(1, stale.rows().size());
        assertEquals(Delivery.LOOSE_CLASSES, stale.rows().get(0).delivery());
        assertTrue("an older served copy must report STALE",
                stale.rows().get(0).freshness().stale());
        assertEquals(300_000L, stale.rows().get(0).freshness().staleSinceMillis());

        // Phase 2 — the served copy catches up to the output: the same call
        // must re-read the disk and report CURRENT.
        Files.setLastModifiedTime(served, FileTime.fromMillis(300_000L));
        Report current = DeploymentFreshnessReport.build(getProject(), deployment, null);
        assertFalse("a caught-up served copy must report CURRENT",
                current.rows().get(0).freshness().stale());
        assertTrue(current.rows().get(0).freshness().verified());
    }

    /**
     * Pins the JAR half of the production builder: a deployed
     * {@code WEB-INF/lib} JAR must be discovered and classified through the
     * real seams. Dropping the jar scan (or the covered-set derivation) in
     * {@code build} would silently report every jarred module as loose
     * classes — the classification the whole view is built on.
     */
    public void testDeployedJarIsDiscoveredAndClassifiedByTheBuilder() throws Exception {
        Path output = outputDir.toPath().resolve("App.class");
        Files.writeString(output, "class-bytes");
        Files.setLastModifiedTime(output, FileTime.fromMillis(500_000L));

        // A JAR named for this module's build artifact identity, deployed in
        // the exploded app — and no overlay copy for it.
        String artifactName = com.intellij.openapi.application.ReadAction.compute(
                () -> DeployedClassesSync.libraryArtifactNameFor(getModule()));
        Path jar = deployDir.toPath().resolve("WEB-INF/lib/" + artifactName + "-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-bytes");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));

        Deployment deployment = new ModuleBackedDeployment(
                ModulePointerManager.getInstance(getProject()).create(getModule()),
                deployDir.toPath(), "/app", true);

        Report report = DeploymentFreshnessReport.build(getProject(), deployment, null);

        DeploymentFreshnessReport.ModuleRow row = report.rows().stream()
                .filter(r -> r.moduleName().equals(getModule().getName()))
                .findFirst().orElseThrow();
        assertNotSame("a deployed JAR must not be reported as loose classes",
                Delivery.LOOSE_CLASSES, row.delivery());
        assertTrue("the module's own compiled output outranks the older JAR",
                row.freshness().stale());
        assertEquals(500_000L, row.freshness().staleSinceMillis());
    }
}
