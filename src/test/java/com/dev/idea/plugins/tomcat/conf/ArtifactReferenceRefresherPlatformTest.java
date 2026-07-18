package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ModifiableArtifact;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.artifacts.PlainArtifactType;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Pins the refresher's storage contract: it walks the STORED list, so a
 * dangling artifact-backed entry (which the resolver's read-only view folds to
 * module-backed) must survive a refresh with its artifact provenance intact —
 * only genuinely drifted live entries are rewritten.
 */
public class ArtifactReferenceRefresherPlatformTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // The light fixture reuses the project — drop test artifacts so
            // they can't leak into other tests.
            WriteAction.run(() -> {
                ModifiableArtifactModel model =
                        ArtifactManager.getInstance(getProject()).createModifiableModel();
                for (Artifact artifact : model.getArtifacts()) {
                    model.removeArtifact(artifact);
                }
                model.commit();
            });
        } finally {
            super.tearDown();
        }
    }

    public void testDanglingEntryStaysArtifactBackedWhenAnotherEntryDrifts() {
        String contentRoot = contentRoot();
        Artifact live = addPlainArtifact("app-1.0.0", "/projects/X/live-out");
        String livePath = ReadAction.compute(live::getOutputFilePath);

        DeploymentConfig config = new DeploymentConfig();
        // Dangling entry whose path is under a module content root — the
        // resolved view folds it to module-backed; storage must not.
        Deployment dangling = ArtifactBackedDeployment.ofName(
                getProject(), "missing-artifact", "/dangling",
                contentRoot + "/target/web-module", true);
        Deployment drifted = ArtifactBackedDeployment.ofName(
                getProject(), "app-1.0.0", "/a", "/projects/X/stale-out", false);
        config.setDeployments(List.of(dangling, drifted));

        // Sanity: the resolver really does fold the dangling entry in its view.
        assertInstanceOf(config.getDeployments(getProject()).get(0), ModuleBackedDeployment.class);

        ArtifactReferenceRefresher.RefreshResult result =
                ReadAction.compute(() -> ArtifactReferenceRefresher.refreshInternal(config));

        assertEquals(1, result.getUpdateCount());
        List<Deployment> stored = config.getDeployments();
        ArtifactBackedDeployment keptDangling =
                assertInstanceOf(stored.get(0), ArtifactBackedDeployment.class);
        assertSame("untouched entries must persist unchanged", dangling, keptDangling);
        assertEquals("missing-artifact", keptDangling.getArtifactName());
        ArtifactBackedDeployment refreshed =
                assertInstanceOf(stored.get(1), ArtifactBackedDeployment.class);
        assertEquals(livePath, refreshed.getLastKnownPath());
    }

    public void testPackagingDriftIsRefreshedIntoStorage() {
        // Genuine drift: the live artifact's output is a .war file (the full
        // packaging policy says WAR even for the generic plain type id), but
        // storage still says exploded.
        Artifact live = addPlainArtifact("app-2.0.0", "/projects/X/out/app-2.0.0.war");
        String livePath = ReadAction.compute(live::getOutputFilePath);

        DeploymentConfig config = new DeploymentConfig();
        config.setDeployments(List.of(ArtifactBackedDeployment.ofName(
                getProject(), "app-2.0.0", "/a", livePath, true)));

        ArtifactReferenceRefresher.RefreshResult result =
                ReadAction.compute(() -> ArtifactReferenceRefresher.refreshInternal(config));

        assertEquals(1, result.getUpdateCount());
        ArtifactBackedDeployment refreshed = assertInstanceOf(
                config.getDeployments().get(0), ArtifactBackedDeployment.class);
        assertFalse(refreshed.getLastKnownExploded());
        assertEquals(livePath, refreshed.getLastKnownPath());
    }

    public void testGenericTypedDirectoryArtifactKeepsExplodedPackaging() throws Exception {
        // A plain-typed artifact whose output is a directory carries no
        // packaging signal in its type id. Add-time detection stored
        // exploded=true from the output shape; a refresh judging the live
        // artifact by type id alone would report fake drift and overwrite the
        // stronger persisted verdict with war. The refresher must apply the
        // same policy as add time and leave the entry untouched.
        java.io.File outputDir = com.intellij.openapi.util.io.FileUtil
                .createTempDirectory("plain-artifact-out", null);
        try {
            addPlainArtifact("app-3.0.0", outputDir.getAbsolutePath());

            DeploymentConfig config = new DeploymentConfig();
            Deployment stored = ArtifactBackedDeployment.ofName(
                    getProject(), "app-3.0.0", "/a", outputDir.getAbsolutePath(), true);
            config.setDeployments(List.of(stored));

            ArtifactReferenceRefresher.RefreshResult result =
                    ReadAction.compute(() -> ArtifactReferenceRefresher.refreshInternal(config));

            assertEquals("no fake packaging drift for a live directory-output artifact",
                    0, result.getUpdateCount());
            ArtifactBackedDeployment kept = assertInstanceOf(
                    config.getDeployments().get(0), ArtifactBackedDeployment.class);
            assertSame("clean pass must not churn storage", stored, kept);
            assertTrue(kept.getLastKnownExploded());
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(outputDir);
        }
    }

    private String contentRoot() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                ModuleRootManager.getInstance(getModule()).getContentRoots()[0].getPath());
    }

    private Artifact addPlainArtifact(String name, String outputPath) {
        return WriteAction.compute(() -> {
            ModifiableArtifactModel model =
                    ArtifactManager.getInstance(getProject()).createModifiableModel();
            ModifiableArtifact artifact = model.addArtifact(name, PlainArtifactType.getInstance());
            artifact.setOutputPath(outputPath);
            model.commit();
            return artifact;
        });
    }
}
