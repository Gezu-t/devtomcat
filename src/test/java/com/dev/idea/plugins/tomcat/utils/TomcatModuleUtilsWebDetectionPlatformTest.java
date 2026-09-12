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
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link TomcatModuleUtils#isWebModule}: a Maven module is what its packaging says (resolved, else the pom's
 * literal element; absent = jar). Other modules need a webapp root (WEB-INF or a webapp-convention name, not
 * static, public, www) or a build-file war marker. A classpath jar is never a signal.
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

    public void testServletContainerInitializerOnClasspathDoesNotMakeModuleWeb() throws Exception {
        // A dependency is not a packaging decision, whatever hook its jar declares.
        addModuleLibrary("app-web-1.0.jar",
                "META-INF/services/jakarta.servlet.ServletContainerInitializer");

        assertFalse("a servlet bootstrap hook on the classpath is a dependency, not a webapp",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testLegacyServletContainerInitializerOnClasspathDoesNotMakeModuleWeb() throws Exception {
        // Same for the javax-era spelling used by projects and Tomcat releases up to 9.
        addModuleLibrary("legacy-web-1.0.jar",
                "META-INF/services/javax.servlet.ServletContainerInitializer");

        assertFalse("the javax-era hook is likewise a dependency, not a webapp",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testNonWebLibraryDoesNotMakeModuleWeb() throws Exception {
        addModuleLibrary("lib-alpha-3.14.0.jar");

        assertFalse("a plain library must not mark the module web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testStaticResourceDirectoryDoesNotMakeModuleWeb() {
        // findWebRoots admits this asset directory; the deployability gate must not.
        myFixture.addFileToProject("src/main/resources/static/app.js", "console.log(1);");

        assertFalse("bundled static assets must not make a library module a deployable webapp",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testAssetDirectoryNamesDoNotMakeModuleWeb() {
        // "public" and "www" are on the web-root convention list but name a bundled
        // asset directory, which a library module has as readily as a webapp.
        myFixture.addFileToProject("public/index.html", "<html/>");
        myFixture.addFileToProject("www/page.html", "<html/>");

        assertFalse("asset-directory conventions are not webapp roots",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testWebappRootWithoutWebInfMakesModuleWeb() {
        // No WEB-INF: with failOnMissingWebXml=false a war's sources often have none.
        myFixture.addFileToProject("src/main/webapp/index.jsp", "<html/>");

        assertTrue("src/main/webapp is a webapp root whether or not WEB-INF exists yet",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testWebInfHoldingWebRootMakesModuleWeb() {
        myFixture.addFileToProject("src/main/webapp/WEB-INF/web.xml", "<web-app/>");

        assertTrue("a webapp root holding WEB-INF is a deployable web application",
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

    public void testIsUnderWebRootForConventionalWebapp() {
        myFixture.addFileToProject("src/main/webapp/WEB-INF/web.xml", "<web-app/>");
        VirtualFile jsp = myFixture.addFileToProject("src/main/webapp/index.jsp", "<html/>").getVirtualFile();

        assertTrue("a file under src/main/webapp must be recognized as under a web root",
                ReadAction.compute(() -> TomcatModuleUtils.isUnderWebRoot(jsp, getModule())));
    }

    public void testIsUnderWebRootForCustomNamedDirectory() {
        // A webapp directory the convention lists do NOT name, identified purely by
        // the WEB-INF it holds — the custom-layout case. Any file type under it
        // counts (here a .html), regardless of the directory's name.
        myFixture.addFileToProject("myCustomWebDir/WEB-INF/web.xml", "<web-app/>");
        VirtualFile page = myFixture.addFileToProject("myCustomWebDir/page.html", "<html/>").getVirtualFile();

        assertTrue("a file under a custom-named WEB-INF-holding directory must be recognized as under a web root",
                ReadAction.compute(() -> TomcatModuleUtils.isUnderWebRoot(page, getModule())));
    }

    public void testFileOutsideAnyWebRootIsNotUnderWebRoot() {
        VirtualFile src = myFixture.addFileToProject("src/main/java/com/example/App.java",
                "package com.example; class App {}").getVirtualFile();

        assertFalse("a plain source file outside any web root must not be considered under a web root",
                ReadAction.compute(() -> TomcatModuleUtils.isUnderWebRoot(src, getModule())));
    }

    public void testPomPackagingPomIsNotWeb() {
        // An aggregator/parent module: pom packaging is never a deployable web app.
        myFixture.addFileToProject("pom.xml",
                "<project><packaging>pom</packaging></project>");

        assertFalse("a pom-packaged aggregator module must not be detected as web",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testJarPackagedMavenModuleWithWebappRootIsNotWeb() {
        myFixture.addFileToProject("pom.xml", "<project><packaging>jar</packaging></project>");
        myFixture.addFileToProject("src/main/webapp/legacy.html", "<html/>");

        assertFalse("Maven never packages a jar module's src/main/webapp",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testMavenModuleWithoutPackagingIsJarEvenWithWebInf() {
        myFixture.addFileToProject("pom.xml", "<project><artifactId>lib</artifactId></project>");
        myFixture.addFileToProject("src/main/webapp/WEB-INF/web.xml", "<web-app/>");

        assertFalse("no <packaging> element is Maven's default, jar",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testCommentedOutWarPackagingIsIgnored() {
        myFixture.addFileToProject("pom.xml",
                "<project><!-- <packaging>war</packaging> --><packaging>jar</packaging></project>");
        myFixture.addFileToProject("src/main/webapp/index.jsp", "<html/>");

        assertFalse("a commented-out element is not the packaging",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    public void testUnresolvedPackagingPropertyDefersToWebappRoot() {
        myFixture.addFileToProject("pom.xml", "<project><packaging>${packaging.type}</packaging></project>");
        myFixture.addFileToProject("src/main/webapp/index.jsp", "<html/>");

        assertTrue("an unresolved property gives no verdict; the webapp root decides",
                ReadAction.compute(() -> TomcatModuleUtils.isWebModule(getModule())));
    }

    /**
     * Creates a minimal valid JAR named {@code jarFileName} in {@link #tempDir} and
     * wires it into the fixture module as a compile-scope module-level library, so
     * it lands on the module's runtime classpath enumeration.
     */
    /** Writes a jar holding a marker plus {@code entries} (empty files), then adds it as a module library. */
    private void addModuleLibrary(String jarFileName, String... entries) throws IOException {
        File jar = new File(tempDir, jarFileName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("marker"));
            zos.write(new byte[]{0});
            zos.closeEntry();
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.closeEntry();
            }
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
