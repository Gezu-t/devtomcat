package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The container index is memoised per install and invalidated when lib/ or bin/ changes. */
@DisplayName("resolveContainerLibs memo")
class ContainerLibKeysMemoTest {

    @TempDir Path home;

    private TomcatInfo info() {
        TomcatInfo info = mock(TomcatInfo.class);
        when(info.getPath()).thenReturn(home.toString());
        return info;
    }

    @Test
    @DisplayName("repeat calls return the memoised set; adding a jar invalidates it")
    void memoisedUntilTheInstallChanges() throws IOException {
        LocalDeploymentStrategy.forgetContainerLibs();
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(lib.resolve("servlet-api.jar"), "x");

        ContainerLibs first = LocalDeploymentStrategy.resolveContainerLibs(info());
        assertTrue(first.keys().contains("servlet-api"));
        assertSame(first, LocalDeploymentStrategy.resolveContainerLibs(info()), "unchanged install: same memo");

        Files.writeString(lib.resolve("extra-lib.jar"), "y");
        Files.setLastModifiedTime(lib, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        ContainerLibs after = LocalDeploymentStrategy.resolveContainerLibs(info());
        assertTrue(after.keys().contains("extra-lib"), "a changed lib/ is re-read");
        assertFalse(first.keys().contains("extra-lib"), "the old memo was not mutated");
    }

    @Test
    @DisplayName("a jar rewritten in place is re-read even though the directory time is unchanged")
    void rewrittenJarInvalidates() throws IOException {
        LocalDeploymentStrategy.forgetContainerLibs();
        Path lib = Files.createDirectories(home.resolve("lib"));
        Path jar = lib.resolve("core.jar");
        writeZip(jar, "org/example/A.class");
        FileTime dirTime = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(lib, dirTime);
        ContainerLibs first = LocalDeploymentStrategy.resolveContainerLibs(info());
        assertTrue(first.provides(webappJar("copy.jar", "org/example/A.class")));

        writeZip(jar, "org/other/B.class", "org/other/C.class");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(2_000_000_000_000L));
        Files.setLastModifiedTime(lib, dirTime);
        ContainerLibs after = LocalDeploymentStrategy.resolveContainerLibs(info());
        assertFalse(after.provides(webappJar("copy2.jar", "org/example/A.class")), "the old class list is gone");
        assertTrue(after.provides(webappJar("copy3.jar", "org/other/B.class")));
    }

    private Path webappJar(String name, String... entries) throws IOException {
        Path jar = home.resolve("webapp/WEB-INF/lib/" + name);
        writeZip(jar, entries);
        return jar;
    }

    private static void writeZip(Path zipFile, String... entries) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (String e : entries) {
                zip.putNextEntry(new java.util.zip.ZipEntry(e));
                zip.closeEntry();
            }
        }
    }
}
