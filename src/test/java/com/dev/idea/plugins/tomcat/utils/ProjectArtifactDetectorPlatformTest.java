package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.util.List;

public class ProjectArtifactDetectorPlatformTest extends BasePlatformTestCase {

    public void testDetectWebModulesReturnsEmptyForNonWebProject() {
        myFixture.addFileToProject("src/main/java/com/example/App.java", "package com.example; class App {}");

        List<Deployment> detected = ProjectArtifactDetector.detectWebModules(getProject());

        assertTrue(detected.isEmpty());
    }

    public void testDetectWebModulesReturnsNormalizedContextPath() throws Exception {
        createWebRoot();

        List<Deployment> detected = ProjectArtifactDetector.detectWebModules(getProject());

        assertEquals(1, detected.size());
        assertEquals(TomcatModuleUtils.extractContextPath(getModule()), detected.get(0).getContextPath());
        assertTrue(detected.get(0).isExploded());
    }

    public void testDetectWebModulesEmitsBuildOutputPathNotSourceForWarModule() throws Exception {
        // A WAR module with a source web root: the emitted deployment path must be
        // the exploded BUILD OUTPUT (target/<finalName>), never the source webapp —
        // the sync pipeline writes WEB-INF/classes under the deployment path.
        VirtualFile webRoot = createWebRoot();
        myFixture.addFileToProject("pom.xml", """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo-webapp</artifactId>
                    <version>3.1</version>
                    <packaging>war</packaging>
                </project>
                """);

        List<Deployment> detected = ProjectArtifactDetector.detectWebModules(getProject());

        assertEquals(1, detected.size());
        String path = detected.get(0).getResolvedPath().toString();
        assertTrue("expected a target/ build-output path, got: " + path,
                path.endsWith("/target/demo-webapp-3.1"));
        assertFalse("must not deploy the source web root", path.equals(webRoot.getPath()));
        assertTrue(detected.get(0).isExploded());
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
