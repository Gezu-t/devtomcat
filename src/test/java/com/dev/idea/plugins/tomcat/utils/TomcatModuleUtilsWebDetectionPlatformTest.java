package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Platform-fixture coverage of {@link TomcatModuleUtils#isWebModule} build-config
 * detection after the structural rewrite. The contract:
 *
 * <ul>
 *   <li><b>Structural Spring-MVC signal</b> — a {@code spring-webmvc} library
 *       resolved onto the module's runtime classpath makes the module web, with no
 *       web root and no build-file present. A non-web library does not.</li>
 *   <li><b>Cold-project text fallback</b> — a project the IDE has not imported has
 *       no resolved Maven model and no classpath, so a {@code war}-packaged
 *       {@code pom.xml} is still detected via the raw build-file scan. The fixture
 *       module carries no external-system id, so this also exercises the
 *       "unknown id scans both build tools" routing branch.</li>
 * </ul>
 *
 * <p>Every signal is asserted structurally with synthetic, minimal inputs — no
 * reference to any specific real-world project, library version, or path.
 */
public class TomcatModuleUtilsWebDetectionPlatformTest extends BasePlatformTestCase {

    private File tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = FileUtil.createTempDirectory("devtomcat-webdetect", null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // The light fixture reuses one module across test methods and does not
            // reset module-level libraries, so a JAR added by one test would leak
            // onto the next test's classpath. Strip them here to keep each test's
            // structural signals isolated.
            removeModuleLibraries();
            if (tempDir != null) {
                FileUtil.delete(tempDir);
            }
        } finally {
            super.tearDown();
        }
    }

    private void removeModuleLibraries() {
        WriteAction.runAndWait(() -> {
            ModifiableRootModel model = ModuleRootManager.getInstance(getModule()).getModifiableModel();
            try {
                for (OrderEntry entry : model.getOrderEntries()) {
                    if (entry instanceof LibraryOrderEntry loe && loe.isModuleLevel()) {
                        model.removeOrderEntry(entry);
                    }
                }
                model.commit();
            } catch (RuntimeException | Error e) {
                model.dispose();
                throw e;
            }
        });
    }

    public void testSpringMvcLibraryOnClasspathMakesModuleWeb() throws Exception {
        addModuleLibrary("spring-webmvc-6.1.0.jar");

        assertTrue("a resolved spring-webmvc library on the runtime classpath should mark the module web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testNonWebLibraryDoesNotMakeModuleWeb() throws Exception {
        addModuleLibrary("commons-lang3-3.14.0.jar");

        assertFalse("a non-web library must not mark the module web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testNonServletSpringWebLibraryDoesNotMakeModuleWeb() throws Exception {
        // spring-web is the HTTP-client base (RestTemplate/WebClient), not a servlet
        // webapp. The classpath signal is scoped to spring-webmvc precisely so a
        // REST-client/reactive module is not mis-detected as a deployable web app.
        addModuleLibrary("spring-web-6.1.0.jar");

        assertFalse("a non-servlet spring-web (HTTP client) library must not mark the module web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testPlainModuleWithNoSignalsIsNotWeb() {
        assertFalse("a module with no web root, no classpath web lib, and no build file is not web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testWarPackagingPomDetectedViaColdProjectTextFallback() {
        // war packaging, no aggregator <packaging>pom</packaging>, and no web root —
        // so detection can only come from the raw build-file text fallback.
        myFixture.addFileToProject("pom.xml",
                "<project><packaging>war</packaging></project>");

        assertTrue("a war-packaged pom with no resolved model should be detected via the text fallback",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testPomPackagingPomIsNotWeb() {
        // An aggregator/parent module: pom packaging is never a deployable web app.
        myFixture.addFileToProject("pom.xml",
                "<project><packaging>pom</packaging></project>");

        assertFalse("a pom-packaged aggregator module must not be detected as web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    /**
     * Creates a minimal valid JAR named {@code jarFileName} in {@link #tempDir} and
     * wires it into the fixture module as a compile-scope module-level library, so
     * it lands on the module's runtime classpath enumeration.
     */
    private void addModuleLibrary(String jarFileName) throws IOException {
        File jar = new File(tempDir, jarFileName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("marker"));
            zos.write(new byte[]{0});
            zos.closeEntry();
        }
        // Make the new file visible to the VFS so the JAR content root resolves;
        // OrderEnumerator silently drops roots whose VirtualFile is unresolved.
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar);
        String jarRootUrl = VirtualFileManager.constructUrl(
                JarFileSystem.PROTOCOL,
                FileUtil.toSystemIndependentName(jar.getAbsolutePath()) + JarFileSystem.JAR_SEPARATOR);
        assertNotNull("test setup: JAR content root must resolve in the VFS",
                VirtualFileManager.getInstance().refreshAndFindFileByUrl(jarRootUrl));

        WriteAction.runAndWait(() -> {
            ModifiableRootModel model = ModuleRootManager.getInstance(getModule()).getModifiableModel();
            try {
                Library library = model.getModuleLibraryTable().createLibrary(jarFileName);
                Library.ModifiableModel libraryModel = library.getModifiableModel();
                libraryModel.addRoot(jarRootUrl, OrderRootType.CLASSES);
                libraryModel.commit();
                model.commit();
            } catch (RuntimeException | Error e) {
                model.dispose();
                throw e;
            }
        });
    }
}
