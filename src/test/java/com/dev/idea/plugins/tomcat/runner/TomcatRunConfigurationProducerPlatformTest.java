package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentKind;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.List;

/**
 * Pins the producer's contract:
 *
 * <ul>
 *   <li><b>Creation</b> builds a configuration in the modern deployment model —
 *       an exploded {@link ModuleBackedDeployment} at the
 *       module's WAR build output — never the legacy docBase shape, and never
 *       a source web root as a deployment path. No WAR build output → no
 *       configuration.</li>
 *   <li><b>Matching</b> recognizes an existing configuration that already
 *       deploys the context module (typed model) or that stored the context's
 *       web root in the legacy docBase field. Without this the platform mints
 *       duplicate temporary configurations in the run widget.</li>
 * </ul>
 */
public class TomcatRunConfigurationProducerPlatformTest extends BasePlatformTestCase {

    private static final String POM_WAR = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>demo-webapp</artifactId>
                <version>2.0</version>
                <packaging>war</packaging>
            </project>
            """;

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

    public void testCreatesModernConfigurationForWarModuleWebContext() {
        addConfiguredServer();
        myFixture.addFileToProject("pom.xml", POM_WAR);
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        boolean created = producer.setupConfigurationFromContext(
                configuration, new ConfigurationContext(jsp), Ref.create((PsiElement) jsp));

        assertTrue(created);
        assertNotNull(configuration.getTomcatInfo());
        assertEquals(TomcatConstants.MODE_LOCAL, configuration.getConfigData().getServerMode());
        // Name derives from the module's own Maven identity — no framework or
        // build-tool suffix taxonomy.
        assertEquals("DevTomcat: demo-webapp", configuration.getName());

        List<Deployment> deployments =
                configuration.getConfigData().getDeploymentConfig().getDeployments(getProject());
        assertEquals(1, deployments.size());
        Deployment deployment = deployments.get(0);
        assertEquals(DeploymentKind.MODULE, deployment.getKind());
        assertTrue(deployment.isExploded());
        assertNotNull(deployment.getResolvedPath());
        assertEquals(contentRootPath() + "/target/demo-webapp-2.0",
                deployment.getResolvedPath().toString());
        assertEquals("/demo-webapp", deployment.getContextPath());

        // The produced config is launch-ready with the standard defaults (local
        // mode, 8080/8005 auto-bump) — the producer inherits them from the config
        // template rather than re-seeding hardcoded ports.
        assertEquals(8080, configuration.getConfigData().getPortConfig().getHttp());
        assertEquals(8005, configuration.getConfigData().getPortConfig().getShutdown());
        assertEquals(com.dev.idea.plugins.tomcat.model.PortStrategy.AUTO_BUMP,
                configuration.getConfigData().getPortConfig().getStrategy());

        // The legacy single-webapp field stays untouched — deployment state
        // lives exclusively in the artifact model.
        assertEmpty(configuration.getDocBase());
    }

    public void testCreationUsesChildPomCoordinatesNotParents() {
        // Standard multi-module child pom: the <parent> block declares
        // coordinates BEFORE the module's own — the build output must be named
        // by the module's artifactId, never the parent's.
        addConfiguredServer();
        myFixture.addFileToProject("pom.xml", """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-aggregator</artifactId>
                        <version>7.0</version>
                    </parent>
                    <artifactId>demo-webapp</artifactId>
                    <version>2.0</version>
                    <packaging>war</packaging>
                </project>
                """);
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        assertTrue(producer.setupConfigurationFromContext(
                configuration, new ConfigurationContext(jsp), Ref.create((PsiElement) jsp)));

        Deployment deployment =
                configuration.getConfigData().getDeploymentConfig().getDeployments(getProject()).get(0);
        assertEquals(DeploymentKind.MODULE, deployment.getKind());
        assertNotNull(deployment.getResolvedPath());
        assertEquals(contentRootPath() + "/target/demo-webapp-2.0",
                deployment.getResolvedPath().toString());
        assertEquals("/demo-webapp", deployment.getContextPath());
    }

    public void testMatchesConfigurationSavedBeforeAutoDetectedProvenance() {
        // Configs persisted by older plugin versions carry INTELLIJ_ARTIFACT
        // provenance with a dangling pointer (no live IntelliJ artifact by that
        // name). Their deployment still identifies its webapp by path — the
        // matcher's content-root-ownership fallback must claim them, or the
        // duplicate-widget symptom persists for every upgrader.
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        // Artifact-backed with a dangling pointer — the typed shape an old
        // INTELLIJ_ARTIFACT record deserializes to when no live artifact matches.
        ArtifactBackedDeployment deployment = ArtifactBackedDeployment.ofName(
                getProject(), "demo-webapp", "/demo-webapp",
                contentRootPath() + "/target/demo-webapp-2.0", true);
        configuration.getConfigData().getDeploymentConfig().setDeployments(List.of(deployment));

        assertTrue(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    public void testReturnsFalseForWebContextWithoutWarBuildOutput() {
        // A web root alone is not deployable: a source directory must never
        // become a deployment path (class sync writes beneath it).
        addConfiguredServer();
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        boolean created = producer.setupConfigurationFromContext(
                configuration, new ConfigurationContext(jsp), Ref.create((PsiElement) jsp));

        assertFalse(created);
        assertEmpty(configuration.getConfigData().getDeploymentConfig().getDeployments(getProject()));
    }

    public void testReturnsFalseForNonWebJavaContext() {
        addConfiguredServer();
        myFixture.addFileToProject("pom.xml", POM_WAR);
        PsiFile javaFile = myFixture.addFileToProject(
                "src/main/java/com/example/Main.java",
                "package com.example; public class Main { public static void main(String[] args) {} }"
        );

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        boolean created = producer.setupConfigurationFromContext(
                configuration, new ConfigurationContext(javaFile), Ref.create((PsiElement) javaFile));

        assertFalse(created);
    }

    public void testReturnsFalseWhenNoTomcatServerIsConfigured() {
        myFixture.addFileToProject("pom.xml", POM_WAR);
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        boolean created = producer.setupConfigurationFromContext(
                configuration, new ConfigurationContext(jsp), Ref.create((PsiElement) jsp));

        assertFalse(created);
    }

    public void testMatchesExistingConfigurationByLegacyDocBase() {
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");
        VirtualFile webRoot = myFixture.findFileInTempDir("src/main/webapp");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);
        configuration.setDocBase(webRoot.getPath());

        assertTrue(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    public void testMatchesExistingConfigurationDeployingContextModule() {
        // The duplicate-widget regression: an artifact-based configuration that
        // already deploys this module MUST be recognized, or the platform mints
        // a temporary duplicate on every run-from-context.
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        // Pointer name deliberately carries the build-output name, not the module
        // name — matching falls through to content-root ownership of the path.
        ModuleBackedDeployment deployment = ModuleBackedDeployment.ofName(
                getProject(), "demo-webapp",
                Path.of(contentRootPath() + "/target/demo-webapp-2.0"),
                "/demo-webapp", true);
        configuration.getConfigData().getDeploymentConfig().setDeployments(List.of(deployment));

        assertTrue(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    public void testDoesNotMatchConfigurationDeployingExternalFile() {
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        ExternalFileDeployment deployment = new ExternalFileDeployment(
                Path.of("/opt/vendor/vendor-app.war"), "/", false);
        configuration.getConfigData().getDeploymentConfig().setDeployments(List.of(deployment));

        assertFalse(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    public void testDoesNotMatchConfigurationWithNoDeployments() {
        PsiFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html>Hello</html>");

        TomcatRunConfigurationProducer producer = new TomcatRunConfigurationProducer();
        TomcatRunConfiguration configuration = newConfiguration(producer);

        assertFalse(producer.isConfigurationFromContext(configuration, new ConfigurationContext(jsp)));
    }

    private TomcatRunConfiguration newConfiguration(TomcatRunConfigurationProducer producer) {
        return new TomcatRunConfiguration(getProject(), producer.getConfigurationFactory(), "Tomcat");
    }

    private String contentRootPath() {
        return ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<String>) () ->
                        ModuleRootManager.getInstance(getModule()).getContentRoots()[0].getPath());
    }

    private void addConfiguredServer() {
        TomcatServerManagerState.getInstance().setTomcatInfos(List.of(
                new TomcatInfo("Test Tomcat", "10.1.28", "/tmp/test-tomcat")
        ));
    }
}
