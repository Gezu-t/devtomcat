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

    // -------------------------------------------------------------------------
    // getMavenArtifactId — graceful degradation (no IntelliJ platform needed)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getMavenArtifactId")
    class GetMavenArtifactIdTests {

        /**
         * The Maven plugin (org.jetbrains.idea.maven) is absent in IntelliJ IDEA Community
         * and in plain test classpaths. The method uses reflection and wraps all exceptions,
         * so it must return {@code null} rather than propagating {@code ClassNotFoundException}.
         * Class.forName throws before the Module/Project args are dereferenced — null is safe.
         */
        @Test
        @DisplayName("returns null when Maven plugin classes are absent from classpath")
        void returnsNullWhenMavenPluginAbsent() throws Exception {
            java.lang.reflect.Method m = LocalDeploymentStrategy.class.getDeclaredMethod(
                    "getMavenArtifactId",
                    com.intellij.openapi.module.Module.class,
                    com.intellij.openapi.project.Project.class);
            m.setAccessible(true);

            // Non-null stubs satisfy @NotNull instrumentation; ClassNotFoundException is
            // thrown inside the method before the args are actually dereferenced.
            com.intellij.openapi.module.Module module =
                    org.mockito.Mockito.mock(com.intellij.openapi.module.Module.class);
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            Object result = m.invoke(null, module, project);

            assertNull(result, "Must return null when Maven plugin is absent");
        }
    }

    // -------------------------------------------------------------------------
    // Module name derivation (compound-name stripping)
    // Tests the string logic used in collectModuleDependencyNames when Maven
    // plugin is absent and we fall back to the IntelliJ module name.
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("moduleArtifactNameFromModuleName")
    class ModuleArtifactNameTests {

        /** Simulates the compound-name stripping in collectModuleDependencyNames. */
        private static String stripCompound(String moduleName) {
            int dot = moduleName.lastIndexOf('.');
            return dot >= 0 ? moduleName.substring(dot + 1) : moduleName;
        }

        @Test
        @DisplayName("simple name returned unchanged")
        void simpleName() {
            assertEquals("common", stripCompound("common"));
        }

        @Test
        @DisplayName("compound Maven-style name strips project prefix")
        void mavenCompound() {
            assertEquals("common", stripCompound("devtomcat-test-webapp.common"));
        }

        @Test
        @DisplayName("multi-level compound name returns only last component")
        void multiLevel() {
            assertEquals("api", stripCompound("org.example.myapp.api"));
        }

        @Test
        @DisplayName("name ending with dot returns empty string (edge case)")
        void trailingDot() {
            assertEquals("", stripCompound("myapp."));
        }

        @Test
        @DisplayName("hyphenated simple name returned unchanged")
        void hyphenated() {
            assertEquals("webapp-portal", stripCompound("webapp-portal"));
        }
    }

    // -------------------------------------------------------------------------
    // buildContextXml — Tomcat 7 vs Tomcat 8+ resource-block emission
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildContextXml — version-gated <Resources> emission")
    class TomcatVersionGate {

        /**
         * Reproducer for the bug reported on GitHub: deploying a webapp on Tomcat 7
         * produced
         *   WARNING: No rules found matching 'Context/Resources/PreResources'
         * because PreResources / PostResources are Tomcat-8-only elements that
         * Tomcat 7's Digester does not recognise. The fix is to omit the entire
         * &lt;Resources&gt; block when the configured Tomcat is older than 8.
         */
        @Test
        @DisplayName("Tomcat 7 omits the <Resources> block entirely")
        void tomcat7OmitsResourcesBlock(@TempDir Path tempDir) throws IOException {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat7 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 7", "7.0.109", "/opt/tomcat-7");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, /* preserveSessions */ false,
                    project, tomcat7, /* logger */ null);

            assertFalse(contextXml.contains("<Resources"),
                    "Tomcat 7 must not receive <Resources> — its Digester logs a WARNING for "
                            + "PreResources/PostResources and silently drops the elements");
            assertFalse(contextXml.contains("<PreResources"));
            assertFalse(contextXml.contains("<PostResources"));
            // The shell of the descriptor must still be valid so the deployment itself works.
            assertTrue(contextXml.contains("<Context "),
                    "the <Context> root must still be present so the webapp deploys");
        }

        @Test
        @DisplayName("Tomcat 7 emits a one-time info message explaining the limitation")
        void tomcat7LogsInfoMessage(@TempDir Path tempDir) throws IOException {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat7 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 7", "7.0.109", "/opt/tomcat-7");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);
            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(
                            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger.class);

            LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat7, logger);

            // Pin that we surface the limitation to the user. Without this, a Tomcat 7
            // user with a multi-module project would silently lose classpath additions
            // and have no idea why their webapp can't find its sibling-module classes.
            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("does not support <PreResources>"));
        }

        @Test
        @DisplayName("Null TomcatInfo emits as before — modern shape (regression: must NOT skip when version is unknown)")
        void nullTomcatInfoDoesNotTriggerSkip(@TempDir Path tempDir) throws IOException {
            // The skip path was specifically gated on `tomcatInfo != null` so that
            // callers that haven't yet propagated the parameter (or test fixtures
            // without a real install) keep emitting the modern shape. If a future
            // change inverts that guard, every modern user gets their multi-module
            // classpath silently dropped — pin the contract here.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);
            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(
                            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, /* tomcatInfo */ null, logger);

            // The "tomcat 7 limitation" info message must NOT have fired when the
            // version is simply unknown.
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.never()).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("does not support <PreResources>"));
            // Context shell still present.
            assertTrue(contextXml.contains("<Context "));
        }

        @Test
        @DisplayName("Unparseable version (majorVersion=0) emits as before — not treated as Tomcat 7")
        void unparseableVersionDoesNotTriggerSkip(@TempDir Path tempDir) throws IOException {
            // TomcatInfo.getMajorVersion() returns 0 when the version string can't
            // parse. Treating that as "Tomcat 7" would silently break every user
            // whose install reports an unusual version string. Pin the contract.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo unknown =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat", "snapshot", "/opt/tomcat");
            assertEquals(0, unknown.getMajorVersion(),
                    "precondition: 'snapshot' version string must yield majorVersion=0");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);
            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger logger =
                    org.mockito.Mockito.mock(
                            com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger.class);

            LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, unknown, logger);

            // Same regression guard: unknown version must not fire the Tomcat 7 path.
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.never()).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("does not support <PreResources>"));
        }
    }

    // -------------------------------------------------------------------------
    // buildContextXml — BCEL / module-info JarScanFilter wiring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildContextXml — BCEL/module-info contract: per-context XML must NOT carry modular JARs")
    class BcelModuleInfoJarScanFilter {

        /**
         * Regression: the per-context {@code <JarScanFilter>} approach silently
         * fails on Tomcat 7 / 8.0.x because their {@code ContextRuleSet} has no
         * Digester rule for {@code Context/JarScanner/JarScanFilter}. The fix
         * moved modular JAR injection to {@code catalina.properties}, which is
         * honoured uniformly across every affected version. These tests pin
         * that the per-context XML no longer carries modular JARs in either
         * direction (affected or modern), so the run console no longer fills
         * with "No rules found matching 'Context/JarScanner/JarScanFilter'"
         * warnings on legacy Tomcat. The {@code catalina.properties} channel
         * is exercised separately in {@code BcelModuleInfoCompatTest}.
         */
        @Test
        @DisplayName("Tomcat 7: per-context XML must not carry modular JARs (would emit 'No rules found' warning)")
        void tomcat7DoesNotCarryModularJarsInPerContextXml(@TempDir Path tempDir) throws Exception {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("jackson-core-2.17.0.jar"),
                    "com/fasterxml/jackson/core/JsonParser.class",
                    "META-INF/versions/9/module-info.class");
            writeJar(libDir.resolve("commons-lang3-3.14.0.jar"),
                    "org/apache/commons/lang3/StringUtils.class");

            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat7 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 7", "7.0.109", "/opt/tomcat-7");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat7, null);

            assertFalse(contextXml.contains("jackson-core-2.17.0.jar"),
                    "Modular JAR must NOT be in per-context XML on Tomcat 7 (the rule for "
                            + "Context/JarScanner/JarScanFilter does not exist there). XML:\n" + contextXml);
            assertFalse(contextXml.contains("commons-lang3-3.14.0.jar"),
                    "Plain JAR is not a modular and not container-provided; must not appear in any skip list");
        }

        @Test
        @DisplayName("Tomcat 11: per-context XML still does not carry modular JARs (no BCEL bug, scan runs normally)")
        void tomcat11DoesNotCarryModularJars(@TempDir Path tempDir) throws Exception {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("jackson-core-2.17.0.jar"),
                    "META-INF/versions/9/module-info.class");

            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat11 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 11", "11.0.0", "/opt/tomcat-11");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat11, null);

            assertFalse(contextXml.contains("jackson-core-2.17.0.jar"),
                    "Tomcat 11 has no BCEL bug; modular JAR must remain scannable. XML:\n" + contextXml);
        }

        @Test
        @DisplayName("Boundary 9.0.30 vs 9.0.31: per-context XML never carries modular JARs (channel is catalina.properties)")
        void boundaryViaXmlOnly(@TempDir Path tempDir) throws Exception {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("byte-buddy-1.14.9.jar"),
                    "META-INF/versions/9/module-info.class");

            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            for (String version : new String[]{"9.0.30", "9.0.31"}) {
                com.dev.idea.plugins.tomcat.setting.TomcatInfo info =
                        new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                                "Tomcat 9", version, "/opt/tomcat-9");
                String xml = LocalDeploymentStrategy.buildContextXml(
                        artifact, artifactPath, false, project, info, null);
                assertFalse(xml.contains("byte-buddy-1.14.9.jar"),
                        "Tomcat " + version + " must not carry modular JARs in per-context XML. XML:\n" + xml);
            }
        }

        @Test
        @DisplayName("Container-provided JARs continue to flow through per-context <JarScanFilter>")
        void containerProvidedStillInPerContextXml(@TempDir Path tempDir) throws Exception {
            // The non-modular skip path is independent of the BCEL fix and must
            // keep emitting the per-context filter for container-provided JARs.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("servlet-api-2.5.jar"), "javax/servlet/Servlet.class");

            com.dev.idea.plugins.tomcat.model.DeploymentArtifact artifact =
                    new com.dev.idea.plugins.tomcat.model.DeploymentArtifact(
                            "myapp", artifactPath.toString(),
                            com.dev.idea.plugins.tomcat.model.DeploymentArtifact.TYPE_EXPLODED);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat11 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 11", "11.0.0", "/opt/tomcat-11");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat11, null);
            assertTrue(contextXml.contains("servlet-api-2.5.jar"),
                    "Container-provided servlet-api must remain in pluggabilitySkip. XML:\n" + contextXml);
        }

        /** Writes a minimal JAR (zip) at {@code path} with the given entry names and empty bodies. */
        private void writeJar(Path path, String... entries) throws Exception {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                for (String e : entries) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(e));
                    zos.closeEntry();
                }
            }
            Files.write(path, baos.toByteArray());
        }
    }
}
