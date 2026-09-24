package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeployedJarMatcher — which deployed jar packages a module")
class DeployedJarMatcherTest {

    private static final String A = "com/example/common/A.class";
    private static final String B = "com/example/common/B.class";

    private static Path jar(Path app, String name, String... entries) throws IOException {
        Path jar = app.resolve("WEB-INF/lib").resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(1);
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static Path out(Path tmp, String... classes) throws IOException {
        Path root = tmp.resolve("out/common");
        for (String c : classes) {
            Path f = root.resolve(c);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "bytes");
        }
        Files.createDirectories(root);
        return root;
    }

    private static DeployedJarMatcher matcher(Path app) {
        return new DeployedJarMatcher(app, DeployedClassesSync.scanDeployedLibraryJars(app));
    }

    @Test
    @DisplayName("a jar the build named freely is matched by the classes it holds")
    void buildNamedJarMatchedByContent(@TempDir Path tmp) throws IOException {
        jar(tmp, "backend-final.jar", A, B, "META-INF/MANIFEST.MF");
        jar(tmp, "lib-alpha-1.0.jar", "org/alpha/X.class");
        DeployedJarMatcher.Match m = matcher(tmp).jarFor(List.of(out(tmp, A, B)), "common");
        assertEquals("backend-final.jar", m.jar());
        assertTrue(m.byContent());
    }

    @Test
    @DisplayName("the jar holding the classes wins over the jar the name rule points at")
    void contentBeatsName(@TempDir Path tmp) throws IOException {
        jar(tmp, "common-1.0.jar", "org/other/X.class");
        jar(tmp, "core-lib-2.0.jar", A, B);
        assertEquals("core-lib-2.0.jar", matcher(tmp).jarFor(List.of(out(tmp, A, B)), "common").jar());
    }

    @Test
    @DisplayName("a fat jar and the module's own jar both hold the classes: the smaller one is the module's")
    void smallerJarWinsOverFatJar(@TempDir Path tmp) throws IOException {
        List<String> fat = new ArrayList<>(List.of(A, B));
        for (int i = 0; i < 20; i++) fat.add("org/bundled/C" + i + ".class");
        jar(tmp, "all-in-one.jar", fat.toArray(new String[0]));
        jar(tmp, "zz-lib.jar", A, B);
        assertEquals("zz-lib.jar", matcher(tmp).jarFor(List.of(out(tmp, A, B)), "common").jar());
    }

    @Test
    @DisplayName("two jars hold the classes equally: the name-rule jar is preferred")
    void nameBreaksTies(@TempDir Path tmp) throws IOException {
        jar(tmp, "common-1.0.jar", A, B);
        jar(tmp, "common-copy.jar", A, B);
        assertEquals("common-1.0.jar", matcher(tmp).jarFor(List.of(out(tmp, A, B)), "common").jar());
    }

    @Test
    @DisplayName("nothing compiled yet: the name rule decides")
    void nothingCompiledFallsBackToName(@TempDir Path tmp) throws IOException {
        jar(tmp, "common-1.0.jar", "org/other/X.class");
        DeployedJarMatcher.Match m = matcher(tmp).jarFor(List.of(out(tmp)), "common");
        assertEquals("common-1.0.jar", m.jar());
        assertFalse(m.byContent());
        assertNull(matcher(tmp).jarFor(List.of(out(tmp)), "unpackaged").jar());
    }

    @Test
    @DisplayName("no readable jar: the name rule decides")
    void unreadableJarsFallBackToName(@TempDir Path tmp) throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("WEB-INF/lib"));
        Files.writeString(lib.resolve("common-1.0.jar"), "not a zip");
        DeployedJarMatcher.Match m = matcher(tmp).jarFor(List.of(out(tmp, A)), "common");
        assertEquals("common-1.0.jar", m.jar());
        assertFalse(m.byContent());
    }

    @Test
    @DisplayName("readable jars hold none of the classes: the name rule is the last word, else unpackaged")
    void noJarHoldsTheClasses(@TempDir Path tmp) throws IOException {
        jar(tmp, "common-1.0.jar", "org/renamed/X.class");
        jar(tmp, "lib-alpha-1.0.jar", "org/alpha/Y.class");
        DeployedJarMatcher.Match named = matcher(tmp).jarFor(List.of(out(tmp, A, B)), "common");
        assertEquals("common-1.0.jar", named.jar(), "a jar can predate a package rename");
        assertFalse(named.byContent());

        DeployedJarMatcher.Match unnamed = matcher(tmp).jarFor(List.of(out(tmp, A, B)), "unpackaged");
        assertNull(unnamed.jar());
        assertTrue(unnamed.byContent());
    }

    @Test
    @DisplayName("every jar on disk is a candidate, not just one per library key")
    void leftoverVersionIsACandidate(@TempDir Path tmp) throws IOException {
        jar(tmp, "common-1.0.jar", "org/old/X.class");
        jar(tmp, "common-2.0.jar", A, B);
        java.util.Map<String, String> oneKey = new java.util.HashMap<>(java.util.Map.of("common", "common-1.0.jar"));
        DeployedJarMatcher.Match m = new DeployedJarMatcher(tmp, oneKey).jarFor(List.of(out(tmp, A, B)), "common");
        assertEquals("common-2.0.jar", m.jar());
        assertTrue(m.byContent());
    }

    @Test
    @DisplayName("a module split into a classes root and a resources root is matched over both")
    void splitRootsMatchedTogether(@TempDir Path tmp) throws IOException {
        jar(tmp, "backend-final.jar", A, "config/app.properties");
        Path classes = out(tmp, A);
        Path resources = tmp.resolve("out/resources");
        Files.createDirectories(resources.resolve("config"));
        Files.writeString(resources.resolve("config/app.properties"), "k=v");
        DeployedJarMatcher.Match m = matcher(tmp).jarFor(List.of(resources, classes), "common");
        assertEquals("backend-final.jar", m.jar());
        assertTrue(m.byContent());
    }

    @Test
    @DisplayName("a module that grew since the jar was built still matches on the half it shares")
    void grownModuleStillMatches(@TempDir Path tmp) throws IOException {
        String[] twelve = new String[12];
        for (int i = 0; i < 12; i++) twelve[i] = "com/example/common/C" + (char) ('a' + i) + ".class";
        jar(tmp, "common-old.jar", java.util.Arrays.copyOf(twelve, 6));
        assertEquals("common-old.jar", matcher(tmp).jarFor(List.of(out(tmp, twelve)), "common").jar());

        Path other = tmp.resolve("other");
        jar(other, "common-older.jar", java.util.Arrays.copyOf(twelve, 5));
        Path grown = other.resolve("out/common");
        for (String c : twelve) { Files.createDirectories(grown.resolve(c).getParent()); Files.writeString(grown.resolve(c), "b"); }
        assertNull(matcher(other).jarFor(List.of(grown), "common").jar(), "fewer than half shared is not a cover");
    }

    @Test
    @DisplayName("the sample spreads over a large output and skips module-info and package-info")
    void sampleSpreadsAndSkipsDescriptors(@TempDir Path tmp) throws IOException {
        String[] many = new String[300];
        for (int i = 0; i < 300; i++) many[i] = String.format("com/example/p%02d/K%03d.class", i % 7, i);
        Path root = out(tmp, many);
        Files.writeString(root.resolve("module-info.class"), "m");
        Files.createDirectories(root.resolve("com/example/p00"));
        Files.writeString(root.resolve("com/example/p00/package-info.class"), "p");

        List<String> sample = DeployedJarMatcher.sampleClassPaths(List.of(root));

        assertEquals(DeployedJarMatcher.SAMPLE_SIZE, sample.size());
        assertEquals(sample.size(), new java.util.HashSet<>(sample).size(), "distinct");
        assertTrue(sample.stream().noneMatch(p -> p.endsWith("-info.class")), sample.toString());
        assertTrue(sample.stream().allMatch(p -> p.endsWith(".class")), sample.toString());
        assertEquals(new java.util.TreeSet<>(sample).first(), sample.get(0), "sorted");
    }

    @Test
    @DisplayName("no deployed jars: nothing to match")
    void noJars(@TempDir Path tmp) throws IOException {
        assertEquals(DeployedJarMatcher.Match.NONE, matcher(tmp).jarFor(List.of(out(tmp, A)), "common"));
    }
}
