package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.util.List;

public class ProjectArtifactDetectorPlatformTest extends BasePlatformTestCase {

    public void testWebModuleTierWinsOverWarScan() throws Exception {
        VirtualFile webRoot = createWebRoot();
        myFixture.addFileToProject("build/libs/" + getModule().getName() + ".war", "war");

        List<DeploymentArtifact> detected = ProjectArtifactDetector.detect(getProject());

        assertFalse(detected.isEmpty());
        assertEquals(DeploymentArtifact.TYPE_EXPLODED, detected.get(0).getType());
        assertEquals(webRoot.getPath(), detected.get(0).getPath());
    }

    public void testDetectWebModulesReturnsEmptyForNonWebProject() {
        myFixture.addFileToProject("src/main/java/com/example/App.java", "package com.example; class App {}");

        List<DeploymentArtifact> detected = ProjectArtifactDetector.detectWebModules(getProject());

        assertTrue(detected.isEmpty());
    }

    public void testDetectWebModulesReturnsNormalizedContextPath() throws Exception {
        createWebRoot();

        List<DeploymentArtifact> detected = ProjectArtifactDetector.detectWebModules(getProject());

        assertEquals(1, detected.size());
        assertEquals(TomcatModuleUtils.extractContextPath(getModule()), detected.get(0).getContextPath());
        assertEquals(DeploymentArtifact.TYPE_EXPLODED, detected.get(0).getType());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    private VirtualFile createWebRoot() throws IOException {
        myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");
        return myFixture.findFileInTempDir("src/main/webapp");
    }
}
