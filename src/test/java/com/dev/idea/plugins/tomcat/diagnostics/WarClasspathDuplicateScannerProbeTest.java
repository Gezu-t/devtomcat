package com.dev.idea.plugins.tomcat.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("WarClasspathDuplicateScanner — containment probes")
class WarClasspathDuplicateScannerProbeTest {

    @Test
    @DisplayName("counts the asked-for entries the jar holds; META-INF metadata is not an entry")
    void countsContainedEntries(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("lib.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String e : List.of("com/a/B.class", "com/a/A.class", "META-INF/MANIFEST.MF", "com/a/")) {
                zip.putNextEntry(new ZipEntry(e));
                zip.closeEntry();
            }
        }
        assertEquals(2, WarClasspathDuplicateScanner.countContained(jar,
                List.of("com/a/A.class", "com/a/B.class", "com/a/C.class", "META-INF/MANIFEST.MF")));
        assertEquals(2, WarClasspathDuplicateScanner.entryCount(jar));
    }

    @Test
    @DisplayName("an unreadable jar answers -1 to both probes")
    void unreadableJar(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("broken.jar");
        Files.writeString(jar, "not a zip");
        assertEquals(-1, WarClasspathDuplicateScanner.countContained(jar, List.of("com/a/A.class")));
        assertEquals(-1, WarClasspathDuplicateScanner.entryCount(jar));
        assertEquals(-1, WarClasspathDuplicateScanner.entryCount(tmp.resolve("missing.jar")));
    }
}
