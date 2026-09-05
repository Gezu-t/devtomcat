package com.dev.idea.plugins.tomcat.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An unchanged jar is not reopened by a later scan; a replaced one is. */
@DisplayName("WarClasspathDuplicateScanner jar cache")
class WarClasspathDuplicateScannerCacheTest {

    @TempDir Path tmp;

    private static void writeJar(Path lib, String name, String... entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(lib.resolve(name)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) { zip.putNextEntry(new ZipEntry(e)); zip.closeEntry(); }
        }
    }

    @Test
    @DisplayName("second scan of unchanged jars performs no central-directory read")
    void unchangedJarsAreNotReopened() throws IOException {
        Path root = tmp.resolve("app");
        Path lib = Files.createDirectories(root.resolve("WEB-INF/lib"));
        writeJar(lib, "lib-a-1.0.jar", "mappings/User.xml");
        writeJar(lib, "lib-b-2.0.jar", "mappings/User.xml");

        long before = WarClasspathDuplicateScanner.JAR_OPENS.get();
        List<WarClasspathDuplicateScanner.DuplicateGroup> first = WarClasspathDuplicateScanner.scan(root);
        assertEquals(2, WarClasspathDuplicateScanner.JAR_OPENS.get() - before, "both jars read once");
        assertEquals(1, first.size());

        long mid = WarClasspathDuplicateScanner.JAR_OPENS.get();
        List<WarClasspathDuplicateScanner.DuplicateGroup> second = WarClasspathDuplicateScanner.scan(root);
        assertEquals(0, WarClasspathDuplicateScanner.JAR_OPENS.get() - mid, "nothing changed: no jar reopened");
        assertEquals(first, second, "same verdict from the cache");
    }

    @Test
    @DisplayName("a replaced jar (new size, newer mtime) is reopened and its new entries seen")
    void replacedJarIsReopened() throws IOException {
        Path root = tmp.resolve("app");
        Path lib = Files.createDirectories(root.resolve("WEB-INF/lib"));
        writeJar(lib, "lib-a-1.0.jar", "config/one.xml");
        writeJar(lib, "lib-b-2.0.jar", "config/two.xml");
        assertTrue(WarClasspathDuplicateScanner.scan(root).isEmpty());

        writeJar(lib, "lib-b-2.0.jar", "config/one.xml", "config/two.xml", "config/padding-to-change-size.xml");
        Files.setLastModifiedTime(lib.resolve("lib-b-2.0.jar"), FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        long before = WarClasspathDuplicateScanner.JAR_OPENS.get();
        List<WarClasspathDuplicateScanner.DuplicateGroup> dups = WarClasspathDuplicateScanner.scan(root);
        assertEquals(1, WarClasspathDuplicateScanner.JAR_OPENS.get() - before, "only the replaced jar is reopened");
        assertEquals(1, dups.size());
        assertEquals("config/one.xml", dups.get(0).logicalPath());
    }
}
