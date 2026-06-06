package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.DependencyScope;
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Platform-fixture coverage of
 * {@link LocalDeploymentStrategy#collectRuntimeClasspathRoots} — the scope gate
 * that decides which library JARs the context.xml {@code <PostResources>}
 * overlay is allowed to mount onto Tomcat's webapp classloader.
 *
 * <p>The contract: the overlay must mirror exactly what the build packages into
 * {@code WEB-INF/lib/} — compile- and runtime-scope dependencies — and must
 * never include test- or provided-scope libraries. A provided-scope dependency
 * is one the container supplies; a test-scope one never ships in the artifact
 * at all. Mounting either onto the running webapp would put a library on the
 * deployed classpath that the real build omits, which is how phantom classpath
 * entries (duplicate or shadowed classes, broken classpath-audit frameworks,
 * ambiguous type-based dependency resolution) creep in.
 *
 * <p>Each scope is exercised with a real, minimal JAR on disk wired in as a
 * module-level library at that scope. The assertion is purely structural —
 * compile and runtime survive, test and provided are filtered — with no
 * reference to any specific real-world library.
 */
public class LocalDeploymentStrategyScopePlatformTest extends BasePlatformTestCase {

    private static final String COMPILE_JAR = "compile-dep-1.0.0.jar";
    private static final String RUNTIME_JAR = "runtime-dep-1.0.0.jar";
    private static final String PROVIDED_JAR = "provided-dep-1.0.0.jar";
    private static final String TEST_JAR = "test-dep-1.0.0.jar";

    private File tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = FileUtil.createTempDirectory("devtomcat-scope", null);

        addScopedModuleLibrary(COMPILE_JAR, DependencyScope.COMPILE);
        addScopedModuleLibrary(RUNTIME_JAR, DependencyScope.RUNTIME);
        addScopedModuleLibrary(PROVIDED_JAR, DependencyScope.PROVIDED);
        addScopedModuleLibrary(TEST_JAR, DependencyScope.TEST);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (tempDir != null) {
                FileUtil.delete(tempDir);
            }
        } finally {
            super.tearDown();
        }
    }

    public void testOverlayIncludesOnlyCompileAndRuntimeScopeJars() {
        List<String> roots = ReadAction.compute(
                () -> LocalDeploymentStrategy.collectRuntimeClasspathRoots(getModule()));

        assertTrue("compile-scope JAR must reach the WEB-INF/lib overlay (the build packages it)",
                containsJar(roots, COMPILE_JAR));
        assertTrue("runtime-scope JAR must reach the WEB-INF/lib overlay (the build packages it)",
                containsJar(roots, RUNTIME_JAR));
        assertFalse("provided-scope JAR must NOT be mounted — the build never packages it into WEB-INF/lib",
                containsJar(roots, PROVIDED_JAR));
        assertFalse("test-scope JAR must NOT be mounted — the build never packages it into WEB-INF/lib",
                containsJar(roots, TEST_JAR));
    }

    private static boolean containsJar(List<String> roots, String jarFileName) {
        for (String root : roots) {
            if (root.endsWith("/" + jarFileName) || root.equals(jarFileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a minimal valid JAR named {@code jarFileName} in {@link #tempDir}
     * and wires it into the fixture module as a module-level library at the
     * given {@code scope}.
     */
    private void addScopedModuleLibrary(String jarFileName, DependencyScope scope) throws IOException {
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
                for (OrderEntry entry : model.getOrderEntries()) {
                    if (entry instanceof LibraryOrderEntry loe
                            && jarFileName.equals(loe.getLibraryName())) {
                        loe.setScope(scope);
                    }
                }
                model.commit();
            } catch (RuntimeException | Error e) {
                model.dispose();
                throw e;
            }
        });
    }
}
