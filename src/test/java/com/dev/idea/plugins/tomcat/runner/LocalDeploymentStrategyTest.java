package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalDeploymentStrategy")
class LocalDeploymentStrategyTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a JAR at {@code dest} containing the given entry names (empty content). */
    private static Path makeJar(Path dest, String... entries) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(dest))) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.closeEntry();
            }
        }
        return dest;
    }

    /** Writes a minimal pom.properties entry for the given coordinates. */
    private static String pomPath(String groupId, String artifactId) {
        return "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
    }

    // -------------------------------------------------------------------------
    // escapeXmlAttribute
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("escapeXmlAttribute")
    class EscapeXmlAttributeTests {

        @Test
        @DisplayName("escapes ampersand")
        void escapesAmpersand() {
            assertEquals("a&amp;b", LocalDeploymentStrategy.escapeXmlAttribute("a&b"));
        }

        @Test
        @DisplayName("escapes less than")
        void escapesLessThan() {
            assertEquals("a&lt;b", LocalDeploymentStrategy.escapeXmlAttribute("a<b"));
        }

        @Test
        @DisplayName("escapes greater than")
        void escapesGreaterThan() {
            assertEquals("a&gt;b", LocalDeploymentStrategy.escapeXmlAttribute("a>b"));
        }

        @Test
        @DisplayName("escapes double quotes")
        void escapesDoubleQuotes() {
            assertEquals("a&quot;b", LocalDeploymentStrategy.escapeXmlAttribute("a\"b"));
        }

        @Test
        @DisplayName("escapes single quotes")
        void escapesSingleQuotes() {
            assertEquals("a&apos;b", LocalDeploymentStrategy.escapeXmlAttribute("a'b"));
        }

        @Test
        @DisplayName("handles multiple special characters")
        void handlesMultiple() {
            assertEquals("&amp;&lt;&gt;&quot;&apos;",
                    LocalDeploymentStrategy.escapeXmlAttribute("&<>\"'"));
        }

        @Test
        @DisplayName("returns plain string unchanged")
        void plainStringUnchanged() {
            assertEquals("/home/user/project", LocalDeploymentStrategy.escapeXmlAttribute("/home/user/project"));
        }

        @Test
        @DisplayName("handles empty string")
        void handlesEmpty() {
            assertEquals("", LocalDeploymentStrategy.escapeXmlAttribute(""));
        }

        @Test
        @DisplayName("handles Windows paths with backslashes")
        void handlesWindowsPaths() {
            assertEquals("C:\\Users\\test\\webapp",
                    LocalDeploymentStrategy.escapeXmlAttribute("C:\\Users\\test\\webapp"));
        }

        @Test
        @DisplayName("escapes ampersand before other entities to avoid double-escaping")
        void ampersandFirst() {
            String result = LocalDeploymentStrategy.escapeXmlAttribute("&<");
            assertEquals("&amp;&lt;", result);
        }
    }

    // -------------------------------------------------------------------------
    // isContainerProvidedJar
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isContainerProvidedJar")
    class IsContainerProvidedJarTests {

        @Test
        @DisplayName("detects Tomcat Jasper jars as container-provided")
        void detectsTomcatJasperJars() {
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("tomcat-jasper-10.1.44.jar"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("tomcat-embed-jasper-10.1.50.jar"));
        }

        @Test
        @DisplayName("detects servlet and jsp api jars as container-provided")
        void detectsServletApis() {
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("jakarta.servlet-api-6.1.0.jar"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar("jsp-api-2.3.3.jar"));
        }

        @Test
        @DisplayName("does not flag regular application libraries")
        void allowsApplicationLibraries() {
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar("spring-core-6.2.3.jar"));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar("my-company-shared.jar"));
        }
    }

    // -------------------------------------------------------------------------
    // stripJarVersion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("stripJarVersion")
    class StripJarVersionTests {

        @Test
        @DisplayName("strips standard Maven release version")
        void stripsReleaseVersion() {
            assertEquals("common", LocalDeploymentStrategy.stripJarVersion("common-1.0.jar"));
        }

        @Test
        @DisplayName("strips SNAPSHOT version")
        void stripsSnapshot() {
            assertEquals("common", LocalDeploymentStrategy.stripJarVersion("common-1.0-SNAPSHOT.jar"));
        }

        @Test
        @DisplayName("strips multi-part version from multi-segment artifact name")
        void stripsMultiSegment() {
            assertEquals("spring-core", LocalDeploymentStrategy.stripJarVersion("spring-core-6.2.3.jar"));
            assertEquals("log4j-api", LocalDeploymentStrategy.stripJarVersion("log4j-api-2.17.1.jar"));
        }

        @Test
        @DisplayName("handles JAR with no version suffix")
        void noVersion() {
            assertEquals("common", LocalDeploymentStrategy.stripJarVersion("common.jar"));
        }

        @Test
        @DisplayName("returns null for non-JAR extension")
        void nonJarExtension() {
            assertNull(LocalDeploymentStrategy.stripJarVersion("common-1.0.war"));
            assertNull(LocalDeploymentStrategy.stripJarVersion("common-1.0.zip"));
        }

        @Test
        @DisplayName("strips bare SNAPSHOT suffix")
        void bareSnapshot() {
            assertEquals("common", LocalDeploymentStrategy.stripJarVersion("common-SNAPSHOT.jar"));
        }

        @Test
        @DisplayName("preserves non-version alphabetic suffix")
        void preservesAlphabeticSuffix() {
            // "common-api" — 'a' is not a digit, not SNAPSHOT → suffix is kept
            assertEquals("common-api", LocalDeploymentStrategy.stripJarVersion("common-api.jar"));
        }
    }

    // -------------------------------------------------------------------------
    // extractModuleName
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("extractModuleName")
    class ExtractModuleNameTests {

        @Test
        @DisplayName("extracts from Maven target/classes path")
        void mavenPath() {
            assertEquals("common",
                    LocalDeploymentStrategy.extractModuleName("/home/user/project/common/target/classes"));
        }

        @Test
        @DisplayName("extracts from nested Maven module path")
        void nestedMavenPath() {
            assertEquals("webapp-portal",
                    LocalDeploymentStrategy.extractModuleName("/home/user/myapp/webapp-portal/target/classes"));
        }

        @Test
        @DisplayName("extracts from Gradle Java main output")
        void gradleJavaMain() {
            assertEquals("common",
                    LocalDeploymentStrategy.extractModuleName("/home/user/project/common/build/classes/java/main"));
        }

        @Test
        @DisplayName("extracts from Gradle Kotlin main output")
        void gradleKotlinMain() {
            assertEquals("shared",
                    LocalDeploymentStrategy.extractModuleName("/home/user/project/shared/build/classes/kotlin/main"));
        }

        @Test
        @DisplayName("extracts from IntelliJ out/production layout")
        void intellijOutProduction() {
            assertEquals("common",
                    LocalDeploymentStrategy.extractModuleName("/home/user/project/out/production/common"));
        }

        @Test
        @DisplayName("extracts from IntelliJ out/production with trailing content")
        void intellijOutProductionWithSubdir() {
            assertEquals("common",
                    LocalDeploymentStrategy.extractModuleName("/home/user/project/out/production/common/subdir"));
        }

        @Test
        @DisplayName("returns null for unrecognised path pattern")
        void unrecognisedPath() {
            assertNull(LocalDeploymentStrategy.extractModuleName("/home/user/something/arbitrary"));
        }

        @Test
        @DisplayName("handles Windows-style backslash paths for Maven")
        void windowsMavenPath() {
            assertEquals("common",
                    LocalDeploymentStrategy.extractModuleName("C:\\Users\\user\\project\\common\\target\\classes"));
        }
    }

    // -------------------------------------------------------------------------
    // scanJar
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("scanJar")
    class ScanJarTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("collects all entry paths from JAR")
        void collectsEntryPaths() throws IOException {
            Path jar = makeJar(tempDir.resolve("lib-1.0.jar"),
                    "org/example/Foo.class",
                    "org/example/Bar.class",
                    "resources/config.xml");

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(jar, "lib");

            assertEquals("lib", meta.baseName);
            assertTrue(meta.entryPaths.contains("org/example/Foo.class"));
            assertTrue(meta.entryPaths.contains("org/example/Bar.class"));
            assertTrue(meta.entryPaths.contains("resources/config.xml"));
        }

        @Test
        @DisplayName("extracts Maven artifactId from pom.properties")
        void extractsPomArtifactId() throws IOException {
            Path jar = makeJar(tempDir.resolve("common-1.0.jar"),
                    "org/example/Common.class",
                    pomPath("org.example", "common"));

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(jar, "common");

            assertTrue(meta.pomArtifacts.contains("common"));
            assertFalse(meta.pomArtifacts.contains("org.example")); // groupId not stored
        }

        @Test
        @DisplayName("handles multiple pom.properties entries (fat JAR scenario)")
        void multiplePomProperties() throws IOException {
            Path jar = makeJar(tempDir.resolve("uber-1.0.jar"),
                    pomPath("org.example", "core"),
                    pomPath("org.example", "utils"));

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(jar, "uber");

            assertTrue(meta.pomArtifacts.contains("core"));
            assertTrue(meta.pomArtifacts.contains("utils"));
        }

        @Test
        @DisplayName("uses filename as baseName when baseName param is null")
        void derivesBaseNameFromFilename() throws IOException {
            Path jar = makeJar(tempDir.resolve("mylib-2.3.jar"));

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(jar, null);

            assertEquals("mylib-2.3", meta.baseName); // no version to strip without the hint
        }

        @Test
        @DisplayName("returns empty JarMeta for corrupted/non-existent JAR without throwing")
        void gracefulOnCorruptJar() throws IOException {
            Path notAJar = tempDir.resolve("bad.jar");
            Files.writeString(notAJar, "not a zip");

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(notAJar, "bad");

            assertEquals("bad", meta.baseName);
            assertTrue(meta.entryPaths.isEmpty());
            assertTrue(meta.pomArtifacts.isEmpty());
        }

        @Test
        @DisplayName("returns empty JarMeta for empty JAR")
        void emptyJar() throws IOException {
            Path jar = makeJar(tempDir.resolve("empty-1.0.jar") /* no entries */);

            LocalDeploymentStrategy.JarMeta meta = LocalDeploymentStrategy.scanJar(jar, "empty");

            assertTrue(meta.entryPaths.isEmpty());
            assertTrue(meta.pomArtifacts.isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // findCoveringJar
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("findCoveringJar")
    class FindCoveringJarTests {

        @TempDir
        Path tempDir;

        // --- setup helpers ---

        private Path moduleOutput(String name) throws IOException {
            return Files.createDirectories(tempDir.resolve(name));
        }

        private void addFile(Path dir, String relativePath) throws IOException {
            Path target = dir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "content");
        }

        private LocalDeploymentStrategy.JarMeta meta(String baseName, String... entries)
                throws IOException {
            Path jar = makeJar(tempDir.resolve(baseName + "-1.0.jar"), entries);
            return LocalDeploymentStrategy.scanJar(jar, baseName);
        }

        // --- tests ---

        @Test
        @DisplayName("returns null for empty jarIndex")
        void emptyIndex() throws IOException {
            Path out = moduleOutput("common");
            addFile(out, "org/example/Common.class");

            assertNull(LocalDeploymentStrategy.findCoveringJar(out.toString(), "common", List.of()));
        }

        @Test
        @DisplayName("returns null when artifactName is null and output is empty")
        void nullArtifactAndEmptyOutput() throws IOException {
            Path out = moduleOutput("empty");

            assertNull(LocalDeploymentStrategy.findCoveringJar(out.toString(), null,
                    List.of(meta("irrelevant", "some/Entry.class"))));
        }

        @Test
        @DisplayName("matches by content: sampled file found in JAR entries")
        void contentMatch() throws IOException {
            Path out = moduleOutput("common");
            addFile(out, "org/example/Common.class");
            addFile(out, "db/changelog/master.xml");

            // JAR that actually contains these files
            LocalDeploymentStrategy.JarMeta covering = meta("common",
                    "org/example/Common.class",
                    "db/changelog/master.xml");
            // Another JAR that doesn't
            LocalDeploymentStrategy.JarMeta other = meta("other",
                    "com/other/Thing.class");

            String result = LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(other, covering));

            assertEquals("common", result);
        }

        @Test
        @DisplayName("matches by pom.properties metadata when output directory is empty")
        void metadataMatchEmptyOutput() throws IOException {
            Path out = moduleOutput("common"); // empty — not compiled yet
            Path jar = makeJar(tempDir.resolve("custom-name-1.0.jar"),
                    pomPath("org.example", "common"),
                    "org/example/Common.class");
            LocalDeploymentStrategy.JarMeta covering = LocalDeploymentStrategy.scanJar(jar, "custom-name");

            String result = LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(covering));

            assertEquals("custom-name", result);
        }

        @Test
        @DisplayName("matches by pom.properties when JAR has a custom name different from module")
        void metadataMatchCustomJarName() throws IOException {
            Path out = moduleOutput("common");
            addFile(out, "org/example/Common.class");

            // JAR named "my-shared-lib" but pom.properties says artifactId=common
            Path jar = makeJar(tempDir.resolve("my-shared-lib-1.0.jar"),
                    pomPath("org.example", "common"),
                    "org/example/Common.class");
            LocalDeploymentStrategy.JarMeta covering = LocalDeploymentStrategy.scanJar(jar, "my-shared-lib");

            String result = LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(covering));

            assertEquals("my-shared-lib", result);
        }

        @Test
        @DisplayName("returns null when no JAR matches by content or metadata")
        void noMatch() throws IOException {
            Path out = moduleOutput("common");
            addFile(out, "org/example/Common.class");

            LocalDeploymentStrategy.JarMeta unrelated = meta("spring-core",
                    "org/springframework/core/SpringVersion.class");

            assertNull(LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(unrelated)));
        }

        @Test
        @DisplayName("returns first matching JAR when multiple JARs contain the module's files")
        void returnsFirstMatch() throws IOException {
            Path out = moduleOutput("shared");
            addFile(out, "org/example/Shared.class");

            LocalDeploymentStrategy.JarMeta first = meta("shared-a", "org/example/Shared.class");
            LocalDeploymentStrategy.JarMeta second = meta("shared-b", "org/example/Shared.class");

            String result = LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "shared", List.of(first, second));

            assertEquals("shared-a", result); // first match wins
        }

        @Test
        @DisplayName("metadata check does not use groupId: different groupIds with same artifactId both match")
        void metadataIgnoresGroupId() throws IOException {
            Path out = moduleOutput("common"); // empty

            // Two JARs both claiming artifactId=common (different groupIds)
            Path jar1 = makeJar(tempDir.resolve("jar1-1.0.jar"), pomPath("org.example", "common"));
            Path jar2 = makeJar(tempDir.resolve("jar2-1.0.jar"), pomPath("com.other", "common"));
            LocalDeploymentStrategy.JarMeta meta1 = LocalDeploymentStrategy.scanJar(jar1, "jar1");
            LocalDeploymentStrategy.JarMeta meta2 = LocalDeploymentStrategy.scanJar(jar2, "jar2");

            // Both match — first one returned
            assertEquals("jar1", LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(meta1, meta2)));
        }

        @Test
        @DisplayName("handles non-existent module output directory gracefully")
        void nonExistentOutputDir() throws IOException {
            Path out = tempDir.resolve("does-not-exist"); // not created

            LocalDeploymentStrategy.JarMeta covering = meta("common",
                    pomPath("org.example", "common"));

            // Content check fails (no dir), metadata check should still work
            String result = LocalDeploymentStrategy.findCoveringJar(
                    out.toString(), "common", List.of(covering));

            assertEquals("common", result);
        }
    }
}
