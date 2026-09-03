package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.utils.LaunchPathMapper;
import com.dev.idea.plugins.tomcat.utils.WslPathTranslator;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves that EVERY path DevTomcat hands to the JVM or writes into a context
 * descriptor goes through the {@link LaunchPathMapper} seam. A recording fake
 * is injected into the production emission code and the assertions check both
 * that the recorder saw each path and that the output carries only mapped
 * values. A second pass with the real {@link WslPathTranslator} pins the WSL
 * shape (no {@code C:\}, no UNC, no {@code ;} remnants), and a third pins that
 * {@link LaunchPathMapper#IDENTITY} leaves the host launch byte-identical.
 */
@DisplayName("LaunchPathMapper — completeness of routing")
class LaunchPathMapperCompletenessTest {

    /** Records every host path handed to it; the output is unmistakably marked. */
    static final class RecordingMapper implements LaunchPathMapper {
        final List<String> seen = new ArrayList<>();
        private final String separator;

        RecordingMapper(String separator) {
            this.separator = separator;
        }

        @Override
        public @NotNull String toTarget(@NotNull String hostPath) {
            seen.add(hostPath);
            return "MAPPED{" + hostPath + "}";
        }

        @Override
        public @NotNull String classpathSeparator() {
            return separator;
        }
    }

    private static PortConfig ports() {
        PortConfig p = new PortConfig();
        p.setHttp(8080);
        p.setShutdown(8005);
        p.setJmx(9009);
        p.setHttps(9443);
        return p;
    }

    private static Sdk jdk() {
        Sdk jdk = mock(Sdk.class);
        when(jdk.getVersionString()).thenReturn("17.0.10");
        return jdk;
    }

    private static void assertNoHostRemnants(String s) {
        assertFalse(s.contains("C:\\"), "raw drive path leaked: " + s);
        assertFalse(s.contains("C:/"), "raw drive path leaked: " + s);
        assertFalse(s.toLowerCase().contains("wsl$"), "raw UNC leaked: " + s);
        assertFalse(s.toLowerCase().contains("wsl.localhost"), "raw UNC leaked: " + s);
        assertFalse(s.contains("\\"), "backslash leaked: " + s);
    }

    // =====================================================================
    // VM options (-Dcatalina.home / catalina.base / java.io.tmpdir / logging)
    // =====================================================================

    @Nested
    @DisplayName("TomcatVmOptionsConfigurator")
    class VmOptions {

        @Test
        @DisplayName("every path-valued property is routed through the mapper")
        void allPathPropertiesMapped() {
            RecordingMapper rec = new RecordingMapper(":");
            ParametersList vm = new ParametersList();
            Path base = Paths.get("/host/devtomcat/base");
            Path home = Paths.get("/host/apache-tomcat");

            TomcatVmOptionsConfigurator.configure(vm, null, ports(), false, base, home, jdk(), rec);

            assertEquals("MAPPED{" + home + "}", vm.getPropertyValue("catalina.home"));
            assertEquals("MAPPED{" + base + "}", vm.getPropertyValue("catalina.base"));
            assertEquals("MAPPED{" + base.resolve("temp") + "}", vm.getPropertyValue("java.io.tmpdir"));
            assertEquals("MAPPED{" + base.resolve("conf/logging.properties") + "}",
                    vm.getPropertyValue("java.util.logging.config.file"));

            assertTrue(rec.seen.contains(home.toString()), "catalina.home not routed");
            assertTrue(rec.seen.contains(base.toString()), "catalina.base not routed");
            assertTrue(rec.seen.contains(base.resolve("temp").toString()), "tmpdir not routed");
            assertTrue(rec.seen.contains(base.resolve("conf/logging.properties").toString()),
                    "logging config not routed");
            assertEquals(4, rec.seen.size(), "unexpected extra routing: " + rec.seen);

            // Non-path property untouched.
            assertEquals("org.apache.juli.ClassLoaderLogManager",
                    vm.getPropertyValue("java.util.logging.manager"));
        }

        @Test
        @DisplayName("WSL shape: Windows host base + UNC home become Linux paths, no remnants")
        void wslShape() {
            ParametersList vm = new ParametersList();
            Path base = Paths.get("C:\\Users\\dev\\devtomcat\\base");
            Path home = Paths.get("\\\\wsl$\\Ubuntu\\opt\\apache-tomcat");

            TomcatVmOptionsConfigurator.configure(vm, null, ports(), false, base, home, jdk(),
                    new WslPathTranslator("Ubuntu"));

            assertEquals("/opt/apache-tomcat", vm.getPropertyValue("catalina.home"));
            assertEquals("/mnt/c/Users/dev/devtomcat/base", vm.getPropertyValue("catalina.base"));
            assertEquals("/mnt/c/Users/dev/devtomcat/base/temp", vm.getPropertyValue("java.io.tmpdir"));
            assertEquals("/mnt/c/Users/dev/devtomcat/base/conf/logging.properties",
                    vm.getPropertyValue("java.util.logging.config.file"));
            for (String p : vm.getList()) assertNoHostRemnants(p);
        }

        @Test
        @DisplayName("regression pin: IDENTITY emits the raw host strings exactly as before")
        void identityByteIdentical() {
            ParametersList withMapper = new ParametersList();
            ParametersList legacy = new ParametersList();
            Path base = Paths.get("/tmp/devtomcat/base");
            Path home = Paths.get("/tmp/devtomcat/home");

            TomcatVmOptionsConfigurator.configure(withMapper, "-Xmx512m", ports(), true, base, home, jdk(),
                    LaunchPathMapper.IDENTITY);
            TomcatVmOptionsConfigurator.configure(legacy, "-Xmx512m", ports(), true, base, home, jdk());

            assertEquals(legacy.getList(), withMapper.getList());
            assertEquals(home.toString(), withMapper.getPropertyValue("catalina.home"));
            assertEquals(base.toString(), withMapper.getPropertyValue("catalina.base"));
        }
    }

    // =====================================================================
    // Classpath (bootstrap.jar, tomcat-juli.jar)
    // =====================================================================

    @Nested
    @DisplayName("TomcatJavaParametersBuilder.emitClasspath")
    class Classpath {

        private final Path home = Paths.get("/host/apache-tomcat");
        private final Path bootstrap = home.resolve("bin/bootstrap.jar");
        private final Path juli = home.resolve("bin/tomcat-juli.jar");
        /** A separator that is foreign on ANY host, so the explicit-classpath route is exercised everywhere. */
        private final String foreignSeparator = File.pathSeparator.equals(":") ? ";" : ":";

        /** The classpath the JVM would see, whichever route the mapper's separator selected. */
        private String effectiveClasspath(JavaParameters params, String separator) {
            List<String> vm = params.getVMParametersList().getList();
            int i = vm.indexOf("-classpath");
            if (i >= 0) {
                assertTrue(params.getClassPath().isEmpty(), "both routes used at once");
                return vm.get(i + 1);
            }
            return String.join(separator, params.getClassPath().getPathList());
        }

        @Test
        @DisplayName("host separator: each entry routed, platform-joined classpath, no -classpath VM param")
        void hostSeparatorRoute() {
            RecordingMapper rec = new RecordingMapper(File.pathSeparator);
            JavaParameters params = new JavaParameters();

            TomcatJavaParametersBuilder.emitClasspath(params, List.of(bootstrap, juli), rec);

            assertEquals(List.of("MAPPED{" + bootstrap + "}", "MAPPED{" + juli + "}"),
                    params.getClassPath().getPathList());
            assertEquals(List.of(bootstrap.toString(), juli.toString()), rec.seen);
            assertFalse(params.getVMParametersList().hasParameter("-classpath"));
        }

        @Test
        @DisplayName("foreign separator: each entry routed into an explicit -classpath joined by the mapper")
        void foreignSeparatorRoute() {
            RecordingMapper rec = new RecordingMapper(foreignSeparator);
            JavaParameters params = new JavaParameters();

            TomcatJavaParametersBuilder.emitClasspath(params, List.of(bootstrap, juli), rec);

            List<String> vm = params.getVMParametersList().getList();
            int i = vm.indexOf("-classpath");
            assertTrue(i >= 0 && i + 1 < vm.size(), "explicit -classpath expected: " + vm);
            assertEquals("MAPPED{" + bootstrap + "}" + foreignSeparator + "MAPPED{" + juli + "}", vm.get(i + 1));
            assertTrue(params.getClassPath().isEmpty(),
                    "platform classpath must stay empty so it is not ';'-joined: "
                            + params.getClassPath().getPathList());
            assertEquals(List.of(bootstrap.toString(), juli.toString()), rec.seen);
        }

        @Test
        @DisplayName("WSL shape: UNC jars become Linux paths joined with ':'")
        void wslShape() {
            Path wslHome = Paths.get("\\\\wsl.localhost\\Ubuntu\\opt\\apache-tomcat");
            JavaParameters params = new JavaParameters();

            WslPathTranslator t = new WslPathTranslator("Ubuntu");
            TomcatJavaParametersBuilder.emitClasspath(params,
                    List.of(wslHome.resolve("bin/bootstrap.jar"), wslHome.resolve("bin/tomcat-juli.jar")), t);

            // On a Windows host (';') this is the explicit -classpath route; on a
            // ':' host the platform join is already correct — both must agree.
            String cp = effectiveClasspath(params, t.classpathSeparator());
            assertEquals("/opt/apache-tomcat/bin/bootstrap.jar:/opt/apache-tomcat/bin/tomcat-juli.jar", cp);
            assertFalse(cp.contains(";"));
            assertNoHostRemnants(cp);
            if (!File.pathSeparator.equals(":")) {
                assertTrue(params.getVMParametersList().hasParameter("-classpath"),
                        "a ';' host must not let the platform join a ':' classpath");
            }
        }

        @Test
        @DisplayName("regression pin: IDENTITY adds the raw Path.toString() entries to the platform classpath")
        void identityByteIdentical() {
            JavaParameters params = new JavaParameters();

            TomcatJavaParametersBuilder.emitClasspath(params, List.of(bootstrap, juli), LaunchPathMapper.IDENTITY);

            assertEquals(List.of(bootstrap.toString(), juli.toString()), params.getClassPath().getPathList());
            assertTrue(params.getVMParametersList().getList().isEmpty());
        }
    }

    // =====================================================================
    // Context descriptors (docBase, PreResources/PostResources bases)
    // =====================================================================

    @Nested
    @DisplayName("LocalDeploymentStrategy descriptors")
    class Descriptors {

        @Test
        @DisplayName("buildContextXml routes docBase through the mapper")
        void docBaseRouted(@TempDir Path tempDir) throws IOException {
            Path artifact = Files.createDirectories(tempDir.resolve("web-module"));
            Deployment deployment = new ExternalFileDeployment(artifact, "/", true);
            RecordingMapper rec = new RecordingMapper(":");

            String xml = LocalDeploymentStrategy.buildContextXml(deployment, artifact, false,
                    mock(Project.class), null, null, rec);

            assertTrue(xml.contains("docBase=\"MAPPED{" + artifact + "}\""), xml);
            assertTrue(rec.seen.contains(artifact.toString()), "docBase not routed: " + rec.seen);
            assertFalse(xml.contains("docBase=\"" + artifact + "\""), "raw docBase leaked: " + xml);
        }

        @Test
        @DisplayName("renderExtraResourcesXml routes every PreResources/PostResources base")
        void extraResourceBasesRouted() {
            RecordingMapper rec = new RecordingMapper(":");
            String webapp = "/projects/app/src/main/webapp";
            String jarA = "/projects/app/lib/a-1.0.0.jar";
            String jarB = "/projects/other/lib/b-2.0.0.jar";

            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of(webapp), List.of(jarA, jarB), rec);

            assertTrue(xml.contains("base=\"MAPPED{" + webapp + "}\" webAppMount=\"/\""), xml);
            assertTrue(xml.contains("base=\"MAPPED{" + jarA + "}\" webAppMount=\"/WEB-INF/lib/a-1.0.0.jar\""), xml);
            assertTrue(xml.contains("base=\"MAPPED{" + jarB + "}\" webAppMount=\"/WEB-INF/lib/b-2.0.0.jar\""), xml);
            assertEquals(List.of(webapp, jarA, jarB), rec.seen);
            assertFalse(xml.contains("base=\"" + webapp + "\""));
            assertFalse(xml.contains("base=\"" + jarA + "\""));
        }

        @Test
        @DisplayName("renderPreMounts routes every split-mount base")
        void preMountBasesRouted() {
            RecordingMapper rec = new RecordingMapper(":");
            List<LocalDeploymentStrategy.PreMount> mounts = List.of(
                    new LocalDeploymentStrategy.PreMount("/src/webapp/css", "/css", true),
                    new LocalDeploymentStrategy.PreMount("/src/webapp/index.jsp", "/index.jsp", false));

            String xml = LocalDeploymentStrategy.renderPreMounts(mounts, rec);

            assertTrue(xml.contains("base=\"MAPPED{/src/webapp/css}\" webAppMount=\"/css\""), xml);
            assertTrue(xml.contains("base=\"MAPPED{/src/webapp/index.jsp}\" webAppMount=\"/index.jsp\""), xml);
            assertEquals(List.of("/src/webapp/css", "/src/webapp/index.jsp"), rec.seen);
        }

        @Test
        @DisplayName("WSL shape: Windows bases become /mnt/<drive>/..., mount names stay host file names")
        void wslShape() {
            WslPathTranslator t = new WslPathTranslator("Ubuntu");

            String xml = LocalDeploymentStrategy.renderExtraResourcesXml(
                    List.of("C:\\projects\\app\\src\\main\\webapp"),
                    List.of("C:\\Users\\dev\\.m2\\repository\\lib-1.0.0.jar"), t)
                    + LocalDeploymentStrategy.renderPreMounts(List.of(
                            new LocalDeploymentStrategy.PreMount("C:\\projects\\app\\WebContent\\css", "/css", true)), t);

            assertTrue(xml.contains("base=\"/mnt/c/projects/app/src/main/webapp\""), xml);
            assertTrue(xml.contains("base=\"/mnt/c/Users/dev/.m2/repository/lib-1.0.0.jar\""
                    + " webAppMount=\"/WEB-INF/lib/lib-1.0.0.jar\""), xml);
            assertTrue(xml.contains("base=\"/mnt/c/projects/app/WebContent/css\""), xml);
            assertNoHostRemnants(xml);
        }

        @Test
        @DisplayName("WSL shape: an exploded artifact docBase under the host drive is translated")
        void wslDocBase() {
            Path artifact = Paths.get("C:\\projects\\app\\target\\app-1.0.0");
            Deployment deployment = new ExternalFileDeployment(artifact, "/app", true);

            String xml = LocalDeploymentStrategy.buildContextXml(deployment, artifact, false,
                    mock(Project.class), null, null, new WslPathTranslator("Ubuntu"));

            assertTrue(xml.contains("docBase=\"/mnt/c/projects/app/target/app-1.0.0\""), xml);
            assertNoHostRemnants(xml);
        }

        @Test
        @DisplayName("regression pin: the mapper-less overloads are byte-identical to IDENTITY")
        void identityByteIdentical(@TempDir Path tempDir) throws IOException {
            Path artifact = Files.createDirectories(tempDir.resolve("web-module"));
            Deployment deployment = new ExternalFileDeployment(artifact, "/", true);
            Project project = mock(Project.class);

            assertEquals(
                    LocalDeploymentStrategy.buildContextXml(deployment, artifact, true, project, null, null),
                    LocalDeploymentStrategy.buildContextXml(deployment, artifact, true, project, null, null,
                            LaunchPathMapper.IDENTITY));
            assertEquals(
                    LocalDeploymentStrategy.renderExtraResourcesXml(List.of("/w"), List.of("/l/x.jar")),
                    LocalDeploymentStrategy.renderExtraResourcesXml(List.of("/w"), List.of("/l/x.jar"),
                            LaunchPathMapper.IDENTITY));
            List<LocalDeploymentStrategy.PreMount> mounts =
                    List.of(new LocalDeploymentStrategy.PreMount("/s/css", "/css", true));
            assertEquals(LocalDeploymentStrategy.renderPreMounts(mounts),
                    LocalDeploymentStrategy.renderPreMounts(mounts, LaunchPathMapper.IDENTITY));
        }

        @Test
        @DisplayName("fileName reads the last segment in either separator convention")
        void fileNameEitherSeparator() {
            assertEquals("lib-1.0.0.jar", LocalDeploymentStrategy.fileName("C:\\Users\\dev\\lib-1.0.0.jar"));
            assertEquals("lib-1.0.0.jar", LocalDeploymentStrategy.fileName("/home/dev/lib-1.0.0.jar"));
            assertEquals("lib-1.0.0.jar", LocalDeploymentStrategy.fileName("lib-1.0.0.jar"));
        }
    }

    // =====================================================================
    // Bundled-app descriptors synthesised by CatalinaHomeMirror
    // =====================================================================

    @Nested
    @DisplayName("CatalinaHomeMirror shared-app descriptors")
    class Mirror {

        @Test
        @DisplayName("buildSharedContextXml routes the docBase through the mapper")
        void sharedDocBaseRouted(@TempDir Path tempDir) throws IOException {
            Path app = Files.createDirectories(tempDir.resolve("webapps/examples"));
            RecordingMapper rec = new RecordingMapper(":");

            String xml = CatalinaHomeMirror.buildSharedContextXml(app, rec);

            String absolute = app.toAbsolutePath().toString();
            assertTrue(xml.contains("docBase=\"MAPPED{" + absolute + "}\""), xml);
            assertEquals(List.of(absolute), rec.seen);
        }

        @Test
        @DisplayName("apply(...) writes the mapped docBase into the synthesized descriptor")
        void applyWritesMappedDescriptor(@TempDir Path tempDir) throws IOException {
            Path home = tempDir.resolve("home");
            Path examples = Files.createDirectories(home.resolve("webapps/examples"));
            Files.writeString(examples.resolve("index.html"), "<html/>");
            Path base = Files.createDirectories(tempDir.resolve("base"));
            RecordingMapper rec = new RecordingMapper(":");

            CatalinaHomeMirror.Result r = CatalinaHomeMirror.apply(true, home, base, Set.of(), rec);

            assertEquals(1, r.entriesSynthesized, () -> "warnings: " + r.warnings);
            String xml = Files.readString(base.resolve("conf/Catalina/localhost/examples.xml"));
            String absolute = examples.toAbsolutePath().toString();
            assertTrue(xml.contains("docBase=\"MAPPED{" + absolute + "}\""), xml);
            assertTrue(rec.seen.contains(absolute), "shared docBase not routed: " + rec.seen);
        }

        @Test
        @DisplayName("regression pin: the mapper-less overload is byte-identical to IDENTITY")
        void identityByteIdentical(@TempDir Path tempDir) throws IOException {
            Path app = Files.createDirectories(tempDir.resolve("webapps/examples"));
            assertEquals(CatalinaHomeMirror.buildSharedContextXml(app),
                    CatalinaHomeMirror.buildSharedContextXml(app, LaunchPathMapper.IDENTITY));
        }
    }
}
