package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class TomcatRunConfigurationProducerPlatformTest extends BasePlatformTestCase {

    private List<TomcatInfo> originalServers;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalServers = TomcatServerManagerState.getInstance().getTomcatInfos();
        TomcatServerManagerState.getInstance().setTomcatInfos(List.of());

        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            TomcatServerManagerState.getInstance().setTomcatInfos(originalServers);
        } finally {
            super.tearDown();
        }
    }

    public void testCreatesConfigurationForValidWebContext() {
        addConfiguredServer();
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");
        VirtualFile webRoot = myFixture.findFileInTempDir("src/main/webapp");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = new TomcatRunConfiguration(
                getProject(),
                producer.getConfigurationFactory(),
                "Tomcat"
        );

        boolean created = producer.setupConfigurationFromContext(
                configuration,
                new ConfigurationContext(jsp),
                Ref.create((PsiElement) jsp)
        );

        assertTrue(created);
        assertEquals(webRoot.getPath(), configuration.getDocBase());
        assertNotNull(configuration.getTomcatInfo());
        assertTrue(configuration.getContextPath().startsWith("/"));
    }

    public void testReturnsFalseForNonWebJavaContext() {
        addConfiguredServer();
        PsiFile javaFile = myFixture.addFileToProject(
                "src/main/java/com/example/Main.java",
                "package com.example; public class Main { public static void main(String[] args) {} }"
        );

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = new TomcatRunConfiguration(
                getProject(),
                producer.getConfigurationFactory(),
                "Tomcat"
        );

        boolean created = producer.setupConfigurationFromContext(
                configuration,
                new ConfigurationContext(javaFile),
                Ref.create((PsiElement) javaFile)
        );

        assertFalse(created);
    }

    public void testReturnsFalseWhenNoTomcatServerIsConfigured() {
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = new TomcatRunConfiguration(
                getProject(),
                producer.getConfigurationFactory(),
                "Tomcat"
        );

        boolean created = producer.setupConfigurationFromContext(
                configuration,
                new ConfigurationContext(jsp),
                Ref.create((PsiElement) jsp)
        );

        assertFalse(created);
    }

    public void testMatchesExistingConfigurationByDocBase() {
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");
        VirtualFile webRoot = myFixture.findFileInTempDir("src/main/webapp");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = new TomcatRunConfiguration(
                getProject(),
                producer.getConfigurationFactory(),
                "Tomcat"
        );
        configuration.setDocBase(webRoot.getPath());

        assertTrue(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    private void addConfiguredServer() {
        TomcatServerManagerState.getInstance().setTomcatInfos(List.of(
                new TomcatInfo("Test Tomcat", "10.1.28", "/tmp/test-tomcat")
        ));
    }
}
