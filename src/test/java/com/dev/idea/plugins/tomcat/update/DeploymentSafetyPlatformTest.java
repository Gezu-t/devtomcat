package com.dev.idea.plugins.tomcat.update;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins {@link DeploymentSafety#isInsideProjectContent} — the discriminator that
 * lets the sync pipeline write into build outputs but refuse the user's source
 * tree. The whole source-corruption guard rests on this returning the right
 * answer for each project-model category. (Uses the {@link VirtualFile} overload
 * because the light fixture stores files in an in-memory VFS that
 * {@code LocalFileSystem} cannot resolve; the path overload's resolution is a
 * standard platform idiom exercised in production.)
 */
public class DeploymentSafetyPlatformTest extends BasePlatformTestCase {

    public void testSourceWebRootIsInsideContent() throws Exception {
        // src/main/webapp is under the content root and not a JPS source root —
        // exactly the path the guard must refuse (isInSourceContent would miss it).
        myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");
        VirtualFile webRoot = myFixture.findFileInTempDir("src/main/webapp");

        assertTrue(inContent(webRoot));
    }

    public void testExcludedBuildOutputIsNotInsideContent() throws Exception {
        // target/<finalName> lives under an excluded folder → safe to write into.
        myFixture.getTempDirFixture().findOrCreateDir("target/demo-1.0/WEB-INF/classes");
        VirtualFile excluded = myFixture.findFileInTempDir("target");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addExcludedRoot(getModule(), excluded));
        VirtualFile buildOutput = myFixture.findFileInTempDir("target/demo-1.0/WEB-INF/classes");

        assertFalse(inContent(buildOutput));
    }

    public void testUnresolvablePathIsNotInsideContent() {
        // The path overload resolves via LocalFileSystem; a path with no VirtualFile
        // (external / not-yet-created build output) is treated as safe-to-write.
        boolean result = ApplicationManager.getApplication().runReadAction(
                (Computable<Boolean>) () -> DeploymentSafety.isInsideProjectContent(
                        getProject(), java.nio.file.Path.of("/nonexistent/devtomcat/build/app/WEB-INF/classes")));
        assertFalse(result);
    }

    public void testWouldCreateWalksToNearestExistingAncestor() throws Exception {
        // A to-be-created path is classified by its nearest EXISTING ancestor, not by
        // itself (which findFileByNioFile can't resolve). A deep nonexistent path
        // under an existing real directory outside the project resolves that ancestor
        // and classifies it as not-in-content → safe. (The in-content rejection this
        // guards is the composition of this walk with the isInContent classification
        // pinned above; the light fixture's in-memory VFS can't host a real on-disk
        // content root to exercise it end to end.)
        java.nio.file.Path realDir = java.nio.file.Files.createTempDirectory("devtomcat-safety");
        try {
            java.nio.file.Path nonexistent = realDir.resolve("a/b/c/WEB-INF/classes");
            assertFalse(java.nio.file.Files.exists(nonexistent));
            boolean result = ApplicationManager.getApplication().runReadAction(
                    (Computable<Boolean>) () -> DeploymentSafety.wouldCreateInsideProjectContent(
                            getProject(), nonexistent));
            assertFalse(result);
        } finally {
            java.nio.file.Files.deleteIfExists(realDir);
        }
    }

    private boolean inContent(VirtualFile file) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<Boolean>) () -> DeploymentSafety.isInsideProjectContent(getProject(), file));
    }
}
