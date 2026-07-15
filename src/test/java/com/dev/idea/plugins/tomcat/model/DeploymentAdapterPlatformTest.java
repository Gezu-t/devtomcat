package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins {@link DeploymentAdapter#toTyped} normalization of a dangling
 * {@code INTELLIJ_ARTIFACT} deployment. On Community there are no web artifacts,
 * so a deployment saved (or upgraded from 1.4.2) with {@code INTELLIJ_ARTIFACT}
 * provenance has no live artifact behind it — it must resolve as module-backed by
 * its path so DevTomcat can assemble/sync it. A path outside every module stays
 * artifact-backed.
 */
public class DeploymentAdapterPlatformTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    public void testDanglingIntellijArtifactUnderModuleNormalizesToModuleBacked() {
        String contentRoot = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () ->
                        ModuleRootManager.getInstance(getModule()).getContentRoots()[0].getPath());
        DeploymentArtifact legacy = new DeploymentArtifact(
                "web-module", contentRoot + "/target/web-module-1.0", DeploymentArtifact.TYPE_EXPLODED);
        legacy.setSource(DeploymentArtifact.Source.INTELLIJ_ARTIFACT);

        Deployment typed = toTyped(legacy);

        assertInstanceOf(typed, ModuleBackedDeployment.class);
    }

    public void testIntellijArtifactOutsideProjectStaysArtifactBacked() {
        DeploymentArtifact legacy = new DeploymentArtifact(
                "vendor-app", "/opt/vendor/vendor-app", DeploymentArtifact.TYPE_EXPLODED);
        legacy.setSource(DeploymentArtifact.Source.INTELLIJ_ARTIFACT);

        Deployment typed = toTyped(legacy);

        assertInstanceOf(typed, ArtifactBackedDeployment.class);
    }

    private Deployment toTyped(DeploymentArtifact legacy) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<Deployment>) () -> DeploymentAdapter.toTyped(getProject(), legacy));
    }
}
