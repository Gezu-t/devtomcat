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

    // -- source-tree heal ----------------------------------------------------
    // The rule, with the two project-model lookups injected: on a light fixture the
    // temp filesystem is invisible to the local VFS, so the real classification
    // cannot run here; it is the same expression DeploymentSafety already tests.

    private VirtualFile dir(String rel) throws java.io.IOException {
        return myFixture.getTempDirFixture().findOrCreateDir(rel);
    }

    private ModuleBackedDeployment storedAt(VirtualFile path) {
        return new ModuleBackedDeployment(ModuleRef.of(getProject(), getModule()),
                Path.of(path.getPath()), "/web", true, "web-module");
    }

    /** "Inside content" = under the content root and not under target/ — what the importer's exclusion yields. */
    private java.util.function.BiPredicate<Module, Path> contentRule() {
        Path root = Path.of(contentRoot());
        return (module, p) -> p.startsWith(root) && !p.startsWith(root.resolve("target"));
    }

    public void testProductionClassificationUsesRootsNotTheFileIndex() throws Exception {
        VirtualFile webapp = dir("src/main/webapp");
        VirtualFile target = dir("target/web-module-1.0");
        VirtualFile targetRoot = dir("target");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addExcludedRoot(getModule(), targetRoot));

        assertTrue("a web root is content", readAction(() ->
                DeploymentResolver.isInsideModuleContent(getModule(), Path.of(webapp.getPath()))));
        assertFalse("an excluded build output is not", readAction(() ->
                DeploymentResolver.isInsideModuleContent(getModule(), Path.of(target.getPath()))));
        assertFalse("outside every content root is not", readAction(() ->
                DeploymentResolver.isInsideModuleContent(getModule(), Path.of("/elsewhere/app"))));
    }

    public void testSourceTreePathIsRepointedAtBuildOutput() throws Exception {
        DeploymentResolver.forgetHeals();
        VirtualFile webapp = dir("src/main/webapp");
        VirtualFile target = dir("target/web-module-1.0");

        ModuleBackedDeployment stored = storedAt(webapp);
        ModuleBackedDeployment healed = readAction(() -> DeploymentResolver.healSourceTreePath(
                getProject(), stored, module -> Path.of(target.getPath()), contentRule()));

        assertEquals(Path.of(target.getPath()), healed.getResolvedPath());
        assertEquals("/web", healed.getContextPath());
        assertEquals(stored.getDisplayName(), healed.getDisplayName());
        assertEquals("the persisted name is carried across the heal", "web-module", healed.getLegacyName());

        // Memoised: the build-file derivation is not consulted again for this stored path.
        int[] derivations = {0};
        ModuleBackedDeployment again = readAction(() -> DeploymentResolver.healSourceTreePath(
                getProject(), stored, module -> { derivations[0]++; return Path.of(target.getPath()); }, contentRule()));
        assertEquals(Path.of(target.getPath()), again.getResolvedPath());
        assertEquals("second heal must come from the memo", 0, derivations[0]);
    }

    public void testBuildOutputPathIsLeftAlone() throws Exception {
        DeploymentResolver.forgetHeals();
        VirtualFile target = dir("target/web-module-1.0");
        ModuleBackedDeployment stored = storedAt(target);
        ModuleBackedDeployment result = readAction(() -> DeploymentResolver.healSourceTreePath(
                getProject(), stored, module -> Path.of(target.getPath()), contentRule()));
        assertSame(stored, result);
    }

    public void testNoDeterminableBuildOutputLeavesTheEntryAlone() throws Exception {
        DeploymentResolver.forgetHeals();
        ModuleBackedDeployment stored = storedAt(dir("src/main/webapp"));
        ModuleBackedDeployment result = readAction(() -> DeploymentResolver.healSourceTreePath(
                getProject(), stored, module -> null, contentRule()));
        assertSame(stored, result);
    }

    public void testNeverRepointsAtAnotherSourceTreePath() throws Exception {
        DeploymentResolver.forgetHeals();
        VirtualFile webapp = dir("src/main/webapp");
        VirtualFile other = dir("src/main/other");
        ModuleBackedDeployment stored = storedAt(webapp);
        ModuleBackedDeployment result = readAction(() -> DeploymentResolver.healSourceTreePath(
                getProject(), stored, module -> Path.of(other.getPath()), contentRule()));
        assertSame(stored, result);
    }
}
