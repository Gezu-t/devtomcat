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

    private boolean inContent(VirtualFile file) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<Boolean>) () -> DeploymentSafety.isInsideProjectContent(getProject(), file));
    }
}
