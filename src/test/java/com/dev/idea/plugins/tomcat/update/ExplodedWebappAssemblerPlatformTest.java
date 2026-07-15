package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pins {@link ExplodedWebappAssembler} — DevTomcat building the exploded webapp
 * itself when no build output exists (the IntelliJ Community / not-yet-packaged
 * case). Drives the assemblability decision and skeleton creation against real
 * temp paths; the mirror-copy population it delegates to is covered by the
 * {@code DeployedClassesSync} / {@code WebResourcesSync} tests.
 */
public class ExplodedWebappAssemblerPlatformTest extends BasePlatformTestCase {

    private Path tempRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VirtualFile sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("src/main/java");
        ApplicationManager.getApplication().runWriteAction((Runnable) () ->
                PsiTestUtil.addSourceRoot(getModule(), sourceRoot));
        tempRoot = Files.createTempDirectory("devtomcat-assembler-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (tempRoot != null) deleteRecursively(tempRoot);
        } finally {
            super.tearDown();
        }
    }

    public void testAssemblesSkeletonForMissingModuleExplodedDeployment() {
        Path deployPath = tempRoot.resolve("target/demo-1.0");
        assertFalse(Files.exists(deployPath));
        Deployment d = moduleDeployment(deployPath, /* exploded */ true);

        ExplodedWebappAssembler.assembleMissing(
                getProject(), List.of(d), new TomcatDeploymentLogger(getProject()));

        assertTrue("docBase must be created", Files.isDirectory(deployPath));
        assertTrue("WEB-INF/classes skeleton must be created",
                Files.isDirectory(deployPath.resolve("WEB-INF/classes")));
    }

    public void testAssemblableTrueForMissingModuleExploded() {
        Path deployPath = tempRoot.resolve("target/app-2.0");
        assertTrue(isAssemblable(moduleDeployment(deployPath, true)));
    }

    public void testNotAssemblableWhenPathAlreadyExists() throws Exception {
        Path deployPath = tempRoot.resolve("target/built-1.0");
        Files.createDirectories(deployPath);
        assertFalse("an existing build output is the normal flow, not assembly",
                isAssemblable(moduleDeployment(deployPath, true)));
    }

    public void testNotAssemblableForWarDeployment() {
        Path deployPath = tempRoot.resolve("target/thing-1.0");
        assertFalse("WAR needs real packaging, never assembled",
                isAssemblable(moduleDeployment(deployPath, /* exploded */ false)));
    }

    public void testNotAssemblableForExternalFileDeployment() {
        Deployment external = new ExternalFileDeployment(
                tempRoot.resolve("vendor/app"), "/vendor", true);
        assertFalse("external deployments resolve to no module", isAssemblable(external));
    }

    private Deployment moduleDeployment(Path path, boolean exploded) {
        return ModuleBackedDeployment.ofName(getProject(), getModule().getName(), path, "/ctx", exploded);
    }

    private boolean isAssemblable(Deployment d) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<Boolean>) () -> ExplodedWebappAssembler.isAssemblable(getProject(), d));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }
}
