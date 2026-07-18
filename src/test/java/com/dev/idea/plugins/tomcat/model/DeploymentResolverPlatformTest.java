package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

/**
 * Platform-level coverage for {@link DeploymentResolver}: the Community
 * artifact→module fold and the three {@code resolveOwningModule} strategies.
 *
 * <p>On Community there are no web artifacts, so an artifact-backed deployment
 * with no live artifact behind it (unresolved pointer) whose stored path is
 * owned by a project module must resolve as module-backed so DevTomcat can
 * assemble/sync it. A path outside every module stays artifact-backed — a live
 * artifact (Ultimate) would too, since the platform builds that one.
 */
public class DeploymentResolverPlatformTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    public void testDanglingArtifactUnderModuleFoldsToModuleBacked() {
        String contentRoot = contentRoot();
        // Unresolved (detached) pointer stands in for "no live artifact"; the
        // last-known path points under the project module's content root.
        ArtifactBackedDeployment dangling = new ArtifactBackedDeployment(
                DeploymentPointers.detachedArtifactPointer("web-module"),
                "/web", contentRoot + "/target/web-module-1.0", true);

        Deployment resolved = DeploymentResolver.resolve(getProject(), dangling);

        assertInstanceOf(resolved, ModuleBackedDeployment.class);
    }

    public void testArtifactOutsideProjectStaysArtifactBacked() {
        ArtifactBackedDeployment outside = new ArtifactBackedDeployment(
                DeploymentPointers.detachedArtifactPointer("vendor-app"),
                "/vendor", "/opt/vendor/vendor-app", true);

        Deployment resolved = DeploymentResolver.resolve(getProject(), outside);

        assertInstanceOf(resolved, ArtifactBackedDeployment.class);
    }

    public void testResolveOwningModuleByDirectName() {
        Module owner = readAction(() ->
                DeploymentResolver.resolveOwningModule(getProject(), getModule().getName(), Path.of("/nowhere")));
        assertEquals(getModule(), owner);
    }

    public void testResolveOwningModuleBySuffixStrippedName() {
        // Common Maven/Gradle case: the stored name is "<moduleName>.war".
        Module owner = readAction(() ->
                DeploymentResolver.resolveOwningModule(
                        getProject(), getModule().getName() + ".war", Path.of("/nowhere")));
        assertEquals(getModule(), owner);
    }

    public void testResolveOwningModuleByContentRootContainment() {
        String contentRoot = contentRoot();
        // Name matches nothing; only the output path being inside the content
        // root can identify the module (Maven finalName / version-suffix case).
        Module owner = readAction(() ->
                DeploymentResolver.resolveOwningModule(
                        getProject(), "unrelated-final-name", Path.of(contentRoot + "/target/build-output")));
        assertEquals(getModule(), owner);
    }

    private String contentRoot() {
        return readAction(() ->
                ModuleRootManager.getInstance(getModule()).getContentRoots()[0].getPath());
    }

    private <T> T readAction(Computable<T> body) {
        return ApplicationManager.getApplication().runReadAction(body);
    }
}
