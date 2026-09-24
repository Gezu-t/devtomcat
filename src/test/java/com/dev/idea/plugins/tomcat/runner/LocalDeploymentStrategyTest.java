package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    @DisplayName("isUnderOrEquals")
    class IsUnderOrEqualsTests {

        @Test
        @DisplayName("a path equal to the base is contained")
        void equalIsContained() {
            assertTrue(LocalDeploymentStrategy.isUnderOrEquals("/a/web", "/a/web"));
        }

        @Test
        @DisplayName("a path strictly under the base (separator boundary) is contained")
        void strictlyUnderIsContained() {
            assertTrue(LocalDeploymentStrategy.isUnderOrEquals("/a/web/WEB-INF/lib/x.jar", "/a/web"));
        }

        @Test
        @DisplayName("a sibling sharing only a name prefix is NOT contained")
        void prefixSiblingNotContained() {
            // The bug this guards: raw startsWith would wrongly treat web-shared as
            // inside web, dropping its jars from the classpath overlay.
            assertFalse(LocalDeploymentStrategy.isUnderOrEquals("/a/web-shared/x.jar", "/a/web"));
            assertFalse(LocalDeploymentStrategy.isUnderOrEquals("/a/web2/x.jar", "/a/web"));
        }
    }

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
    @DisplayName("isContainerProvidedJar — decided from what the configured Tomcat ships")
    class IsContainerProvidedJarTests {

        private static void zip(Path path, String... entries) throws IOException {
            Files.createDirectories(path.getParent());
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
                for (String e : entries) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(e));
                    zos.closeEntry();
                }
            }
        }

        private static final String[] SERVLET = {
                "jakarta/servlet/Servlet.class", "jakarta/servlet/Filter.class", "jakarta/servlet/http/HttpServlet.class"};
        private static final String[] EL = {"jakarta/el/ELContext.class", "jakarta/el/ExpressionFactory.class"};

        /** A Tomcat home shipping the spec APIs under Tomcat's bare names, plus its own internals. */
        private static ContainerLibs tomcat(Path home) throws IOException {
            zip(home.resolve("lib/servlet-api.jar"), SERVLET);
            zip(home.resolve("lib/jsp-api.jar"), "jakarta/servlet/jsp/JspPage.class");
            zip(home.resolve("lib/el-api.jar"), EL);
            zip(home.resolve("lib/catalina.jar"), "org/apache/catalina/Context.class");
            zip(home.resolve("lib/tomcat-i18n-fr.jar"), "org/apache/catalina/LocalStrings_fr.properties");
            zip(home.resolve("bin/tomcat-juli.jar"), "org/apache/juli/ClassLoaderLogManager.class");
            LocalDeploymentStrategy.forgetContainerLibs();
            return LocalDeploymentStrategy.resolveContainerLibs(
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo("T", "11.0.0", home.toString()));
        }

        private static Path webappJar(Path tmp, String name, String... entries) throws IOException {
            Path jar = tmp.resolve("webapp/WEB-INF/lib/" + name);
            zip(jar, entries);
            return jar;
        }

        @Test
        @DisplayName("a spec API under its Maven name is container-provided: the container ships its classes")
        void specApiUnderMavenName(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.servlet-api-6.1.0.jar", SERVLET), libs));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.el-api-6.0.0.jar", EL), libs));
        }

        @Test
        @DisplayName("Tomcat's own copy of a spec API differs in helper classes: the reference jar is still container-provided")
        void containerHelpersDifferFromReferenceJar(@TempDir Path tmp) throws IOException {
            Path home = tmp.resolve("tomcat");
            zip(home.resolve("lib/el-api.jar"), "jakarta/el/ELContext.class", "jakarta/el/ExpressionFactory.class",
                    "jakarta/el/FactoryFinder.class", "jakarta/el/ELUtil.class");
            LocalDeploymentStrategy.forgetContainerLibs();
            ContainerLibs libs = LocalDeploymentStrategy.resolveContainerLibs(
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo("T", "11.0.0", home.toString()));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.el-api-5.0.1.jar", "jakarta/el/ELContext.class", "jakarta/el/ExpressionFactory.class",
                            "jakarta/el/ExpressionFactoryCache.class", "jakarta/el/Util.class", "jakarta/el/Cache.class"), libs));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.el-api-6.1.0.jar", "jakarta/el/Extra.class"), libs),
                    "a class in a package the container owns is the container's");
        }

        @Test
        @DisplayName("a jar with a shipped jar's key is container-provided even with nothing to sample")
        void shippedKeyWithoutClasses(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "tomcat-i18n-fr-11.0.0.jar", "org/apache/catalina/LocalStrings_fr.properties"), libs));
            Path unreadable = tmp.resolve("webapp/WEB-INF/lib/catalina-11.0.jar");
            Files.createDirectories(unreadable.getParent());
            Files.writeString(unreadable, "not a zip");
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(unreadable, libs));
        }

        @Test
        @DisplayName("a renamed or forked container jar is recognised by its classes")
        void renamedContainerJar(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "core-fork.jar", "org/apache/catalina/Context.class"), libs));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "logging-copy.jar", "org/apache/juli/ClassLoaderLogManager.class"), libs),
                    "bin/ jars are container-provided too");
        }

        @Test
        @DisplayName("JSTL shares the servlet API's name head but not its classes: app-provided")
        void jstlStaysAppProvided(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.servlet.jsp.jstl-3.0.1.jar",
                            "jakarta/servlet/jsp/jstl/core/Config.class", "org/apache/taglibs/standard/Version.class"), libs));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.servlet.jsp.jstl-api-3.0.0.jar",
                            "jakarta/servlet/jsp/jstl/core/Config.class"), libs));
        }

        @Test
        @DisplayName("an app library that bundles a couple of API classes stays app-provided")
        void bundledApiClassesDoNotTip(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            java.util.List<String> entries = new java.util.ArrayList<>(java.util.List.of(SERVLET[0], SERVLET[1]));
            for (int i = 0; i < 20; i++) entries.add("org/example/app/C" + i + ".class");
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "app-core-6.2.3.jar", entries.toArray(new String[0])), libs));
        }

        @Test
        @DisplayName("alternative implementations, resource-only and plain app jars stay app-provided")
        void appJarsStayAppProvided(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "ws-impl-2.1.5.jar", "org/example/ws/Server.class", "org/example/ws/Session.class"), libs));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "app-config.jar", "config/app.properties"), libs));
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "lib-alpha-1.0.jar", "org/alpha/X.class"), libs));
        }

        @Test
        @DisplayName("unknown Tomcat home: nothing is container-provided")
        void unknownHome(@TempDir Path tmp) throws IOException {
            assertTrue(LocalDeploymentStrategy.resolveContainerLibs(null).isEmpty());
            assertTrue(LocalDeploymentStrategy.resolveContainerLibs(
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo("T", "10", "")).isEmpty());
            assertFalse(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "jakarta.servlet-api-6.1.0.jar", SERVLET), ContainerLibs.EMPTY));
        }

        @Test
        @DisplayName("resolveContainerLibs reads lib/ and bin/ by key and by classes")
        void readsLibAndBin(@TempDir Path tmp) throws IOException {
            ContainerLibs libs = tomcat(tmp.resolve("tomcat"));
            assertTrue(libs.keys().containsAll(java.util.Set.of("catalina", "servlet-api", "tomcat-juli", "tomcat-i18n-fr")));
            assertTrue(LocalDeploymentStrategy.isContainerProvidedJar(
                    webappJar(tmp, "coyote-renamed-9.0.0.jar", "org/apache/catalina/Context.class"), libs));
        }
    }

    // -------------------------------------------------------------------------
    // Module name derivation (compound-name stripping)
    // Pins the string convention used by
    // DeployedClassesSync#collectDependencyArtifactNames when neither the Maven
    // model nor a Gradle external-system id is available and the artifact name
    // falls back to the IntelliJ module name. That stem is what a dependency root
    // is matched against in the deployed WEB-INF/lib/. Graceful degradation of the
    // Maven lookup itself is covered by MavenModelProviderTest.
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("moduleArtifactNameFromModuleName")
    class ModuleArtifactNameTests {

        /** Simulates the compound-name stripping in DeployedClassesSync#collectDependencyArtifactNames. */
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
         * Tomcat 7's Digester does not recognise. The fix is to omit the
         * entire &lt;Resources&gt; block when the configured Tomcat is older
         * than 8 — neither PreResources nor PostResources may appear.
         */
        @Test
        @DisplayName("Tomcat 7 omits the <Resources> block entirely")
        void tomcat7OmitsResourcesBlock(@TempDir Path tempDir) throws IOException {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
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
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
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
            // user with library JARs that aren't packaged in WEB-INF/lib would silently
            // lose those classpath additions and have no idea why their webapp can't
            // find them.
            org.mockito.Mockito.verify(logger).logServerInfo(
                    org.mockito.ArgumentMatchers.contains("does not support <PreResources>"));
        }

        @Test
        @DisplayName("Null TomcatInfo emits as before — modern shape (regression: must NOT skip when version is unknown)")
        void nullTomcatInfoDoesNotTriggerSkip(@TempDir Path tempDir) throws IOException {
            // The skip path was specifically gated on `tomcatInfo != null` so that
            // callers that haven't yet propagated the parameter (or test fixtures
            // without a real install) keep emitting the modern shape. If a future
            // change inverts that guard, every modern user gets their library-JAR
            // PostResources silently dropped — pin the contract here.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
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
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
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

        @Test
        @DisplayName("Tomcat 8 always emits <Resources allowLinking=\"true\"> even with no extra resources")
        void tomcat8AlwaysEmitsResourcesForSymlinks(@TempDir Path tempDir) throws IOException {
            // Pin the symlink-friendly default. Tomcat 8+ disables symlink traversal
            // by default (CVE-2014-0033 hardening), so a docBase that is a symlink
            // — common in Maven multi-module projects where target/<module>/ is
            // linked from a staging dir — silently fails to deploy without an
            // explicit allowLinking="true". Previously the <Resources> block was
            // emitted only when extra PostResources were attached, so users with
            // no extras lost symlink support invisibly.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat8 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 8", "8.5.81", "/opt/tomcat-8.5");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, /* preserveSessions */ false,
                    project, tomcat8, /* logger */ null);

            assertTrue(contextXml.contains("<Resources allowLinking=\"true\">"),
                    "Tomcat 8 must emit <Resources allowLinking=\"true\"> so symlinked "
                            + "docBases deploy. Output:\n" + contextXml);
            assertTrue(contextXml.contains("</Resources>"),
                    "the Resources block must be well-formed even when no extra "
                            + "PostResources are attached");
        }

        @Test
        @DisplayName("Tomcat 10 emits <Resources allowLinking=\"true\"> (modern release path)")
        void tomcat10AlwaysEmitsResourcesForSymlinks(@TempDir Path tempDir) throws IOException {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat10 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 10.1", "10.1.18", "/opt/tomcat-10.1");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat10, null);

            assertTrue(contextXml.contains("<Resources allowLinking=\"true\">"),
                    "Tomcat 10 must emit <Resources allowLinking=\"true\">");
        }

        @Test
        @DisplayName("Null TomcatInfo emits Resources block — treats unknown as modern (8+)")
        void nullTomcatInfoEmitsResources(@TempDir Path tempDir) throws IOException {
            // The null-TomcatInfo path defaults to the modern shape (Resources
            // block always emitted) so callers / test fixtures without a real
            // install do not regress symlink support.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, /* tomcatInfo */ null, null);

            assertTrue(contextXml.contains("<Resources allowLinking=\"true\">"),
                    "null TomcatInfo must default to the modern shape with <Resources>");
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
            writeJar(libDir.resolve("lib-alpha-2.17.0.jar"),
                    "com/example/lib/alpha/Parser.class",
                    "META-INF/versions/9/module-info.class");
            writeJar(libDir.resolve("commons-lang3-3.14.0.jar"),
                    "org/apache/commons/lang3/StringUtils.class");

            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat7 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 7", "7.0.109", "/opt/tomcat-7");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat7, null);

            assertFalse(contextXml.contains("lib-alpha-2.17.0.jar"),
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
            writeJar(libDir.resolve("lib-alpha-2.17.0.jar"),
                    "META-INF/versions/9/module-info.class");

            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat11 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 11", "11.0.0", "/opt/tomcat-11");
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat11, null);

            assertFalse(contextXml.contains("lib-alpha-2.17.0.jar"),
                    "Tomcat 11 has no BCEL bug; modular JAR must remain scannable. XML:\n" + contextXml);
        }

        @Test
        @DisplayName("Boundary 9.0.30 vs 9.0.31: per-context XML never carries modular JARs (channel is catalina.properties)")
        void boundaryViaXmlOnly(@TempDir Path tempDir) throws Exception {
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("lib-gamma-1.14.9.jar"),
                    "META-INF/versions/9/module-info.class");

            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            for (String version : new String[]{"9.0.30", "9.0.31"}) {
                com.dev.idea.plugins.tomcat.setting.TomcatInfo info =
                        new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                                "Tomcat 9", version, "/opt/tomcat-9");
                String xml = LocalDeploymentStrategy.buildContextXml(
                        artifact, artifactPath, false, project, info, null);
                assertFalse(xml.contains("lib-gamma-1.14.9.jar"),
                        "Tomcat " + version + " must not carry modular JARs in per-context XML. XML:\n" + xml);
            }
        }

        @Test
        @DisplayName("Modern Tomcat: container-provided JARs flow through per-context <JarScanFilter>")
        void containerProvidedInPerContextXmlOnModernTomcat(@TempDir Path tempDir) throws Exception {
            // On 8.5+ the Digester recognises Context/JarScanner/JarScanFilter,
            // so we keep emitting it for container-provided JARs (the cleaner
            // per-webapp channel).
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("servlet-api-2.5.jar"), "javax/servlet/Servlet.class");
            Path home = Files.createDirectories(tempDir.resolve("tomcat-11/lib")).getParent();
            writeJar(home.resolve("lib/servlet-api.jar"), "javax/servlet/Servlet.class");
            LocalDeploymentStrategy.forgetContainerLibs();

            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat11 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 11", "11.0.0", home.toString());
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat11, null);
            assertTrue(contextXml.contains("servlet-api-2.5.jar"),
                    "Container-provided servlet-api must remain in pluggabilitySkip on modern Tomcat. XML:\n"
                            + contextXml);
        }

        @Test
        @DisplayName("Tomcat 7: container-provided JARs are NOT in per-context XML (would log 'No rules found')")
        void containerProvidedNotInPerContextXmlOnAffectedTomcat(@TempDir Path tempDir) throws Exception {
            // The Tomcat 7 ContextRuleSet has no rule for
            // Context/JarScanner/JarScanFilter, so emitting the element here
            // causes a "No rules found" Digester warning AND the skip never
            // takes effect. The configureDeployment path routes container-
            // provided JARs through catalina.properties on affected Tomcats
            // instead, so the per-context XML must not duplicate them. This
            // test pins that buildContextXml omits the JarScanner/JarScanFilter
            // element entirely on affected Tomcats.
            Path artifactPath = Files.createDirectories(tempDir.resolve("webapp"));
            Path libDir = Files.createDirectories(artifactPath.resolve("WEB-INF").resolve("lib"));
            writeJar(libDir.resolve("servlet-api-2.5.jar"), "javax/servlet/Servlet.class");
            writeJar(libDir.resolve("jsp-api-2.2.jar"), "javax/servlet/jsp/JspPage.class");
            Path home = Files.createDirectories(tempDir.resolve("tomcat-7/lib")).getParent();
            writeJar(home.resolve("lib/servlet-api.jar"), "javax/servlet/Servlet.class");
            writeJar(home.resolve("lib/jsp-api.jar"), "javax/servlet/jsp/JspPage.class");
            LocalDeploymentStrategy.forgetContainerLibs();

            com.dev.idea.plugins.tomcat.model.Deployment artifact =
                    new com.dev.idea.plugins.tomcat.model.ExternalFileDeployment(
                            artifactPath, "/", /* exploded */ true);
            com.dev.idea.plugins.tomcat.setting.TomcatInfo tomcat7 =
                    new com.dev.idea.plugins.tomcat.setting.TomcatInfo(
                            "Tomcat 7", "7.0.109", home.toString());
            com.intellij.openapi.project.Project project =
                    org.mockito.Mockito.mock(com.intellij.openapi.project.Project.class);

            String contextXml = LocalDeploymentStrategy.buildContextXml(
                    artifact, artifactPath, false, project, tomcat7, null);

            assertFalse(contextXml.contains("<JarScanner>"),
                    "Affected Tomcat must omit <JarScanner> entirely. XML:\n" + contextXml);
            assertFalse(contextXml.contains("servlet-api-2.5.jar"),
                    "Container-provided JARs must not appear in per-context XML on affected Tomcat. XML:\n"
                            + contextXml);
            assertFalse(contextXml.contains("jsp-api-2.2.jar"));
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

    // -------------------------------------------------------------------------
    // renderExtraResourcesXml — webapp-root / JAR overlay emission
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("renderExtraResourcesXml — overlay shape and ordering")
    class RenderExtraResourcesXml {

        /** PreResources fragment exactly as the emitter formats it. */
        private String preResource(String base, String mount) {
            return "\n    <PreResources className=\"org.apache.catalina.webresources.DirResourceSet\""
                    + "\n                   base=\"" + base + "\" webAppMount=\"" + mount + "\" />";
        }

        /** PostResources fragment exactly as the emitter formats it. */
        private String postResource(String base, String mount) {
            return "\n    <PostResources className=\"org.apache.catalina.webresources.FileResourceSet\""
                    + "\n                    base=\"" + base + "\" webAppMount=\"" + mount + "\" />";
        }

        @Test
        @DisplayName("Webapp source dir mounts at the web-app root as a DirResourceSet PreResource")
        void webappDirMountsAtRoot() {
            String webappDir = "/projects/X/src/main/webapp";
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(webappDir), List.of());

            assertTrue(xml.contains(preResource(webappDir, "/")),
                    "webapp source must mount at '/' via DirResourceSet PreResources so source "
                            + "files shadow the deployed copy. XML:\n" + xml);
            // It is a PreResource (searched before docBase), never a PostResource.
            assertFalse(xml.contains("<PostResources"),
                    "a webapp overlay alone must not emit any PostResources. XML:\n" + xml);
        }

        @Test
        @DisplayName("Regression guard: the overlay never mounts anything at /WEB-INF/classes")
        void neverMountsAtClassesPath() {
            // The deployed WEB-INF/classes/ is the single source of truth for compiled
            // output (kept fresh by DeployedClassesSync). The overlay must never mount a
            // directory there: doing so makes the same logical resource reachable at two
            // classpath URIs (overlay + deployed copy), which strict-classpath libraries
            // reject ("found N files with the same path"). The emitter only mounts webapp
            // source dirs at "/" and library JARs at "/WEB-INF/lib"; it has no class-dir
            // parameter, so a /WEB-INF/classes mount is structurally impossible — pin that.
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of("/projects/X/src/main/webapp"),
                    List.of("/projects/X/libs/dep-1.0.0.jar"));

            assertFalse(xml.contains("/WEB-INF/classes"),
                    "no overlay entry may mount at /WEB-INF/classes. XML:\n" + xml);
            assertFalse(xml.contains("webAppMount=\"/WEB-INF/classes\""),
                    "no PreResources entry may target the /WEB-INF/classes mount. XML:\n" + xml);
        }

        @Test
        @DisplayName("Webapp PreResources precede JAR PostResources")
        void preResourcesPrecedePostResources() {
            String webappDir = "/projects/X/src/main/webapp";
            String jar = "/projects/X/libs/dep-1.0.0.jar";
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(webappDir), List.of(jar));

            int webappIdx = xml.indexOf("base=\"" + webappDir + "\" webAppMount=\"/\"");
            int jarIdx = xml.indexOf("base=\"" + jar + "\"");
            assertTrue(webappIdx >= 0 && jarIdx >= 0, "expected both entries. XML:\n" + xml);
            assertTrue(webappIdx < jarIdx,
                    "webapp PreResource must precede the JAR PostResource. XML:\n" + xml);
            // The JAR keeps the established lib mount and FileResourceSet shape.
            assertTrue(xml.contains(postResource(jar, "/WEB-INF/lib/dep-1.0.0.jar")),
                    "library JAR must mount at /WEB-INF/lib/<name> via FileResourceSet PostResources. XML:\n" + xml);
        }

        @Test
        @DisplayName("Multiple webapp source dirs preserve resolution order")
        void multipleWebappDirsPreserveOrder() {
            String first = "/projects/X/src/main/webapp";
            String second = "/projects/X/src/main/extra-web";
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(first, second), List.of());

            int firstIdx = xml.indexOf("base=\"" + first + "\" webAppMount=\"/\"");
            int secondIdx = xml.indexOf("base=\"" + second + "\" webAppMount=\"/\"");
            assertTrue(firstIdx >= 0 && secondIdx >= 0, "both webapp dirs must emit. XML:\n" + xml);
            assertTrue(firstIdx < secondIdx,
                    "webapp dirs must keep their resolution order so the authoritative "
                            + "root is searched first. XML:\n" + xml);
        }

        @Test
        @DisplayName("Webapp base attribute is XML-escaped")
        void webappBaseIsEscaped() {
            String webappDir = "/projects/a&b/src/main/webapp";
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(webappDir), List.of());

            assertTrue(xml.contains("base=\"/projects/a&amp;b/src/main/webapp\" webAppMount=\"/\""),
                    "ampersand in the webapp path must be escaped so the descriptor stays "
                            + "well-formed. XML:\n" + xml);
            assertFalse(xml.contains("a&b/src"),
                    "raw unescaped ampersand must not appear. XML:\n" + xml);
        }

        @Test
        @DisplayName("No resources of any kind yields the empty fragment")
        void emptyInputsYieldEmpty() {
            assertEquals("", LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(), List.of()),
                    "with nothing to mount the fragment must be empty so the caller can "
                            + "still emit a bare <Resources allowLinking=\"true\"> block");
        }

        @Test
        @DisplayName("Same-filename JARs from different paths mount at distinct lib paths (neither dropped)")
        void sameFilenameJarsGetDistinctMounts() {
            // Two genuinely different libraries that happen to share a filename
            // (same artifactId+version, different groupId). Both must reach the
            // classloader: dropping either would hide that library's classes.
            String jarA = "/projects/X/lib-a/shared-1.0.0.jar";
            String jarB = "/projects/X/lib-b/shared-1.0.0.jar";
            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(), List.of(jarA, jarB));

            // Both physical JARs are mounted (base= points at the real path).
            assertTrue(xml.contains(postResource(jarA, "/WEB-INF/lib/shared-1.0.0.jar")),
                    "first JAR keeps its real lib mount. XML:\n" + xml);
            assertTrue(xml.contains(postResource(jarB, "/WEB-INF/lib/shared-1.0.0__2.jar")),
                    "the colliding JAR mounts at a distinct, still-scanned .jar path. XML:\n" + xml);
            // The shared filename resolves to exactly one mount path; the collider
            // is renamed, so Tomcat never sees two files at one web path.
            assertEquals(1, countOccurrences(xml, "webAppMount=\"/WEB-INF/lib/shared-1.0.0.jar\""),
                    "the shared filename must map to exactly one mount path. XML:\n" + xml);
        }

        private int countOccurrences(String haystack, String needle) {
            int count = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                count++;
            }
            return count;
        }
    }

    // -------------------------------------------------------------------------
    // uniqueMountName — collision-free /WEB-INF/lib mount names
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("uniqueMountName — collision-free lib mount names")
    class UniqueMountName {

        @Test
        @DisplayName("first use returns the name unchanged")
        void firstUseUnchanged() {
            assertEquals("dep-1.0.0.jar",
                    LocalDeploymentStrategy.uniqueMountName("dep-1.0.0.jar", new HashSet<>()));
        }

        @Test
        @DisplayName("collisions get a counter before the extension, preserving .jar")
        void collisionsGetCounter() {
            Set<String> used = new HashSet<>();
            assertEquals("dep.jar", LocalDeploymentStrategy.uniqueMountName("dep.jar", used));
            assertEquals("dep__2.jar", LocalDeploymentStrategy.uniqueMountName("dep.jar", used));
            assertEquals("dep__3.jar", LocalDeploymentStrategy.uniqueMountName("dep.jar", used));
        }

        @Test
        @DisplayName("extensionless names still disambiguate")
        void extensionlessNames() {
            Set<String> used = new HashSet<>();
            assertEquals("noext", LocalDeploymentStrategy.uniqueMountName("noext", used));
            assertEquals("noext__2", LocalDeploymentStrategy.uniqueMountName("noext", used));
        }

        @Test
        @DisplayName("a disambiguated candidate that already exists is skipped")
        void skipsPreexistingDisambiguated() {
            Set<String> used = new HashSet<>(Set.of("dep.jar", "dep__2.jar"));
            assertEquals("dep__3.jar", LocalDeploymentStrategy.uniqueMountName("dep.jar", used));
        }
    }

    // ---------------------------------------------------------------------
    // expandSourceRootMounts / renderPreMounts — a committed WEB-INF/lib or
    // WEB-INF/classes under source must never overlay (shadow) docBase.
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("expandSourceRootMounts — build-output subtrees excluded")
    class ExpandSourceRootMounts {

        @Test
        @DisplayName("overlays every entry except WEB-INF/lib and WEB-INF/classes")
        void excludesBuildOutput(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("index.jsp"), "x");
            Files.createDirectory(dir.resolve("css"));
            Path webInf = Files.createDirectory(dir.resolve("WEB-INF"));
            Files.writeString(webInf.resolve("web.xml"), "<web-app/>");
            Files.createDirectory(webInf.resolve("jsp"));
            Files.createDirectory(webInf.resolve("lib"));      // must be excluded
            Files.createDirectory(webInf.resolve("classes"));  // must be excluded

            List<LocalDeploymentStrategy.PreMount> mounts = new ArrayList<>();
            LocalDeploymentStrategy.expandSourceRootMounts(new File(dir.toString()), mounts);

            Map<String, Boolean> byMount = new HashMap<>();
            for (LocalDeploymentStrategy.PreMount m : mounts) byMount.put(m.webAppMount(), m.isDirectory());

            assertEquals(Boolean.FALSE, byMount.get("/index.jsp"), "top-level file overlaid as a file");
            assertEquals(Boolean.TRUE, byMount.get("/css"), "top-level dir overlaid as a directory");
            assertTrue(byMount.containsKey("/WEB-INF/web.xml"), "WEB-INF descriptor still overlaid");
            assertTrue(byMount.containsKey("/WEB-INF/jsp"), "WEB-INF jsp dir still overlaid");
            assertFalse(byMount.containsKey("/WEB-INF/lib"), "WEB-INF/lib must NOT be overlaid (shadows docBase)");
            assertFalse(byMount.containsKey("/WEB-INF/classes"), "WEB-INF/classes must NOT be overlaid");
            // The whole-root "/" mount is never emitted for a split root.
            assertFalse(byMount.containsKey("/"), "split root must not also mount wholesale at /");
        }
    }

    @Nested
    @DisplayName("renderPreMounts — Dir vs File resource sets")
    class RenderPreMounts {

        @Test
        @DisplayName("directories emit DirResourceSet, files emit FileResourceSet, at their own mount")
        void emitsCorrectResourceSets() {
            String xml = LocalDeploymentStrategy.renderPreMounts(List.of(
                    new LocalDeploymentStrategy.PreMount("/src/css", "/css", true),
                    new LocalDeploymentStrategy.PreMount("/src/index.jsp", "/index.jsp", false)));

            assertTrue(xml.contains("DirResourceSet"), "directory mount uses DirResourceSet");
            assertTrue(xml.contains("FileResourceSet"), "file mount uses FileResourceSet");
            assertTrue(xml.contains("webAppMount=\"/css\""));
            assertTrue(xml.contains("webAppMount=\"/index.jsp\""));
            assertFalse(xml.contains("/WEB-INF/lib"));
            assertFalse(xml.contains("/WEB-INF/classes"));
        }
    }

    @Nested
    @DisplayName("classifyWebappSourceRoots — common layout wholesale, WebContent layout split")
    class ClassifyWebappSourceRoots {

        @Test
        @DisplayName("a common source root (no committed WEB-INF/lib|classes) mounts wholesale at /")
        void plainRootWholesale(@TempDir Path tmp) throws IOException {
            Path root = Files.createDirectories(tmp.resolve("src/main/webapp"));
            Files.writeString(Files.createDirectories(root.resolve("WEB-INF")).resolve("web.xml"), "<web-app/>");

            List<String> wholesale = new ArrayList<>();
            List<LocalDeploymentStrategy.PreMount> split = new ArrayList<>();
            LocalDeploymentStrategy.classifyWebappSourceRoots(
                    List.of(root.toString()), "/nonexistent/docbase", wholesale, split);

            assertEquals(List.of(root.toString().replace('/', File.separatorChar)), wholesale,
                    "a common layout mounts wholesale at /");
            assertTrue(split.isEmpty(), "no split mounts for a common layout");
        }

        @Test
        @DisplayName("a WebContent root with committed WEB-INF/lib is split, excluding WEB-INF/lib")
        void webContentRootSplitExcludesLib(@TempDir Path tmp) throws IOException {
            Path root = Files.createDirectories(tmp.resolve("WebContent"));
            Files.writeString(root.resolve("index.jsp"), "x");
            Path webInf = Files.createDirectories(root.resolve("WEB-INF"));
            Files.writeString(webInf.resolve("web.xml"), "<web-app/>");
            Files.createDirectory(webInf.resolve("lib"));   // committed jars — the shadow risk

            List<String> wholesale = new ArrayList<>();
            List<LocalDeploymentStrategy.PreMount> split = new ArrayList<>();
            LocalDeploymentStrategy.classifyWebappSourceRoots(
                    List.of(root.toString()), "/nonexistent/docbase", wholesale, split);

            assertTrue(wholesale.isEmpty(), "a WebContent root with committed WEB-INF/lib is NOT mounted wholesale");
            Set<String> mounts = new HashSet<>();
            for (LocalDeploymentStrategy.PreMount m : split) mounts.add(m.webAppMount());
            assertTrue(mounts.contains("/index.jsp"), "top-level content still overlaid");
            assertTrue(mounts.contains("/WEB-INF/web.xml"), "WEB-INF descriptor still overlaid");
            assertFalse(mounts.contains("/WEB-INF/lib"), "committed WEB-INF/lib must NOT be overlaid (shadow prevented)");
        }

        @Test
        @DisplayName("a root already under docBase is skipped (no self-remount)")
        void rootUnderDocBaseSkipped(@TempDir Path tmp) throws IOException {
            Path docBase = Files.createDirectories(tmp.resolve("out/app"));
            Path rootUnder = Files.createDirectories(docBase.resolve("WEB-INF"));

            List<String> wholesale = new ArrayList<>();
            List<LocalDeploymentStrategy.PreMount> split = new ArrayList<>();
            LocalDeploymentStrategy.classifyWebappSourceRoots(
                    List.of(rootUnder.toString()), docBase.toString(), wholesale, split);

            assertTrue(wholesale.isEmpty() && split.isEmpty(), "roots under docBase are skipped");
        }
    }
}
