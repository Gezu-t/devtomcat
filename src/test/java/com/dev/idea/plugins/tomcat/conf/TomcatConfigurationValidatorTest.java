package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentPointers;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationValidator")
class TomcatConfigurationValidatorTest {

    private TomcatConfigurationData data;

    /** Headless typed fixture — name-only module pointer, no platform services. */
    private static ModuleBackedDeployment deployment(String name, String path,
                                                     String contextPath, boolean exploded) {
        return new ModuleBackedDeployment(
                DeploymentPointers.detachedModulePointer(name), Path.of(path), contextPath, exploded);
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        data = new TomcatConfigurationData();
        // A real directory on disk — the validator now rejects a missing path
        // as an error (previously a log warning), so the baseline must point
        // at something that actually exists.
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", tempDir.toString());
        data.setTomcatInfo(info);
        PortConfig ports = data.getPortConfig();
        ports.setHttp(8080);
        ports.setShutdown(8005);

        // The validator warns when the deployment list is empty (so the editor
        // shows a yellow stripe instead of letting Tomcat launch with nothing
        // to serve). Seed the baseline with one valid deployment so tests that
        // focus on other concerns (server, port, context path) stay readable
        // — tests that specifically exercise empty-list behaviour can clear
        // this back out.
        Path baselineArtifact = Files.createTempDirectory(tempDir, "baseline-app");
        data.getDeploymentConfig().addDeployment(
                deployment("baseline-app", baselineArtifact.toString(), "/baseline-app", true));
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("valid configuration passes without exception")
    void validConfigPasses() {
        assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
    }

    // =========================================================================
    // Tomcat server validation
    // =========================================================================

    @Nested
    @DisplayName("Tomcat server validation")
    class TomcatServerValidation {

        @Test
        @DisplayName("null TomcatInfo throws")
        void nullTomcatInfo() {
            data.setTomcatInfo(null);
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("No Tomcat server selected"));
        }

        @Test
        @DisplayName("empty server name throws")
        void emptyServerName() {
            data.getTomcatInfo().setName("");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("server name is empty"));
        }

        @Test
        @DisplayName("empty server path throws")
        void emptyServerPath() {
            data.getTomcatInfo().setPath("");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("path is not configured"));
        }

        @Test
        @DisplayName("empty version is warning, not error")
        void emptyVersionIsWarning() {
            data.getTomcatInfo().setVersion("");
            // Should not throw — version is a warning, not a blocking error
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("missing Tomcat home directory throws")
        void missingHomeDirectoryThrows() {
            // The validator used to only log a warning when the path didn't exist,
            // letting toolbar Run through to fail at execution time. Now it throws
            // up-front with the same wording the UI and runtime use, so the three
            // gates stay in sync on path validity.
            data.getTomcatInfo().setPath("/definitely/does/not/exist/on/disk");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("does not exist"));
        }
    }

    // =========================================================================
    // Context path validation
    // =========================================================================

    @Nested
    @DisplayName("Context path validation")
    class ContextPathValidation {

        @Test
        @DisplayName("empty context path defaults to /")
        void emptyContextPathDefaults() throws RuntimeConfigurationException {
            data.setContextPath("");
            TomcatConfigurationValidator.validate(data);
            assertEquals("/", data.getContextPath());
        }

        @Test
        @DisplayName("null context path defaults to /")
        void nullContextPathDefaults() throws RuntimeConfigurationException {
            data.setContextPath(null);
            TomcatConfigurationValidator.validate(data);
            assertEquals("/", data.getContextPath());
        }

        @Test
        @DisplayName("valid context path /myapp passes")
        void validContextPath() {
            data.setContextPath("/myapp");
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("root context path / passes")
        void rootContextPath() {
            data.setContextPath("/");
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("context path without leading slash is canonicalized by the setter")
        void missingLeadingSlashCanonicalized() {
            // Contract: TomcatConfigurationData.setContextPath now canonicalizes via
            // ContextPathUtils.normalizeContextPath at the model boundary, so a
            // programmatic 'myapp' becomes '/myapp' before the validator runs.
            // Previously the validator caught this case ("must start with '/'"),
            // but the saved value stayed broken — autoBrowserUrl produced
            // 'http://host:portmyapp'. Now the setter fixes it proactively.
            data.setContextPath("myapp");
            assertEquals("/myapp", data.getContextPath());
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("context path with spaces throws")
        void spacesInContextPath() {
            data.setContextPath("/my app");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("cannot contain spaces"));
        }

        @Test
        @DisplayName("context path with backslashes throws")
        void backslashesInContextPath() {
            data.setContextPath("/my\\app");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("cannot contain backslashes"));
        }
    }

    // =========================================================================
    // Port validation
    // =========================================================================

    @Nested
    @DisplayName("Port validation")
    class PortValidation {

        @Test
        @DisplayName("duplicate HTTP and shutdown ports throws")
        void duplicatePorts() {
            data.getPortConfig().setHttp(8080);
            data.getPortConfig().setShutdown(8080);
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().toLowerCase().contains("same"));
        }

        @Test
        @DisplayName("out-of-range HTTP port throws")
        void outOfRangePort() {
            data.getPortConfig().setHttp(70000);
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("HTTP"));
        }

        @Test
        @DisplayName("negative port throws")
        void negativePort() {
            data.getPortConfig().setHttp(-1);
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("HTTP"));
        }

        @Test
        @DisplayName("valid distinct ports pass")
        void validDistinctPorts() {
            data.getPortConfig().setHttp(8080);
            data.getPortConfig().setShutdown(8005);
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("enabled HTTPS with conflicting port throws")
        void httpsConflict() {
            data.getPortConfig().setHttpsEnabled(true);
            data.getPortConfig().setHttps(8080); // same as HTTP
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().toLowerCase().contains("same"));
        }

        @Test
        @DisplayName("disabled HTTPS with conflicting port does not throw")
        void disabledHttpsIgnored() {
            data.getPortConfig().setHttpsEnabled(false);
            data.getPortConfig().setHttps(8080); // same as HTTP, but disabled
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }
    }

    @Nested
    @DisplayName("Deployment validation")
    class DeploymentValidation {

        @Test
        @DisplayName("empty deployment list blocks launch")
        void emptyDeploymentsBlockLaunch() {
            // Clear the baseline deployment seeded in setUp to exercise the
            // empty-list code path. Previously the validator silently returned
            // here, so the editor showed no stripe and the user only learned
            // post-launch (via LocalDeploymentStrategy's runtime warning) that
            // Tomcat started with nothing to serve. We then upgraded to a
            // RuntimeConfigurationWarning (yellow stripe, Run still proceeds),
            // but the user-reported feedback was that the warning was visible
            // yet Tomcat still launched. Now this is a blocking
            // RuntimeConfigurationException — red stripe, launch refused —
            // because launching with zero deployments is never the happy path.
            data.getDeploymentConfig().setDeployments(java.util.Collections.emptyList());

            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            // Pin that it's the blocking variant, not its RuntimeConfigurationWarning subclass.
            assertFalse(ex instanceof RuntimeConfigurationWarning,
                    "empty list must block launch, not just warn");
            assertTrue(ex.getLocalizedMessage().contains("No deployments configured"),
                    "expected empty-list message, got: " + ex.getLocalizedMessage());
            assertTrue(ex.getLocalizedMessage().contains("Deployment tab"),
                    "expected actionable next-step in message: " + ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("missing exploded path does not warn — a before-launch step builds it")
        void missingExplodedPathDoesNotWarn() {
            // Community-first: an exploded deployment's directory is produced by a
            // before-launch step (DevTomcat assembles it; the platform builds it on
            // Ultimate), so a not-yet-built path must not raise the old confusing
            // "Artifact output not found" warning.
            data.getDeploymentConfig().setDeployments(java.util.Collections.emptyList());
            data.getDeploymentConfig().addDeployment(deployment(
                    "web-module", "/does/not/exist/target/web-module-1.0",
                    "/web-module", true));

            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("missing WAR path still warns — only a real package step produces it")
        void missingWarPathWarns() {
            data.getDeploymentConfig().setDeployments(java.util.Collections.emptyList());
            data.getDeploymentConfig().addDeployment(deployment(
                    "web-module", "/does/not/exist/target/web-module-1.0.war",
                    "/web-module", false));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("WAR not found"),
                    "expected WAR-not-found message, got: " + ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("duplicate artifact output paths throw warning")
        void duplicateArtifactPathsThrow() throws Exception {
            Path exploded = Files.createTempDirectory("devtomcat-artifact");

            data.getDeploymentConfig().addDeployment(
                    deployment("webapp-one", exploded.toString(), "/webapp-one", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("webapp-other", exploded.toString(), "/webapp-other", true));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("same artifact output"));
        }

        @Test
        @DisplayName("duplicate base module names throw warning")
        void duplicateBaseNamesThrow() throws Exception {
            Path firstPath = Files.createTempDirectory("devtomcat-artifact-a");
            Path secondPath = Files.createTempDirectory("devtomcat-artifact-b");

            data.getDeploymentConfig().addDeployment(
                    deployment("webapp-two", firstPath.toString(), "/webapp-two", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("webapp-two_war_exploded", secondPath.toString(), "/webapp-two-2", true));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Duplicate deployment for module"));
        }

        @Test
        @DisplayName("identical raw context paths collide")
        void identicalContextPathsCollide() throws Exception {
            Path aPath = Files.createTempDirectory("devtomcat-art-a");
            Path bPath = Files.createTempDirectory("devtomcat-art-b");
            data.getDeploymentConfig().addDeployment(
                    deployment("artifact-alpha", aPath.toString(), "/myapp", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("artifact-beta", bPath.toString(), "/myapp", true));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Duplicate context path"));
            assertTrue(ex.getLocalizedMessage().contains("/myapp"));
            // Error message should name BOTH conflicting artifacts so the user
            // can locate them in the Deployment tab without guessing.
            assertTrue(ex.getLocalizedMessage().contains("artifact-alpha"),
                    "expected first artifact name in message: " + ex.getLocalizedMessage());
            assertTrue(ex.getLocalizedMessage().contains("artifact-beta"),
                    "expected second artifact name in message: " + ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("trailing slash variant collides via normalization (/foo vs /foo/)")
        void trailingSlashVariantCollides() throws Exception {
            Path aPath = Files.createTempDirectory("devtomcat-trail-a");
            Path bPath = Files.createTempDirectory("devtomcat-trail-b");
            data.getDeploymentConfig().addDeployment(
                    deployment("trail-app-a", aPath.toString(), "/foo", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("trail-app-b", bPath.toString(), "/foo/", true));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Duplicate context path"));
            assertTrue(ex.getLocalizedMessage().contains("/foo"));
        }

        @Test
        @DisplayName("empty vs default both resolve to ROOT and collide")
        void emptyAndDefaultBothCollideAsRoot() throws Exception {
            Path aPath = Files.createTempDirectory("devtomcat-root-a");
            Path bPath = Files.createTempDirectory("devtomcat-root-b");
            data.getDeploymentConfig().addDeployment(
                    deployment("root-app-a", aPath.toString(), "", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("root-app-b", bPath.toString(), "/", true));

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Duplicate context path"));
            // ROOT label should be present so users grasp the canonical form.
            assertTrue(ex.getLocalizedMessage().contains("ROOT") || ex.getLocalizedMessage().contains("/"),
                    "expected ROOT label or '/' in message: " + ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("distinct context paths pass validation")
        void distinctContextPathsPass() throws Exception {
            Path aPath = Files.createTempDirectory("devtomcat-distinct-a");
            Path bPath = Files.createTempDirectory("devtomcat-distinct-b");
            data.getDeploymentConfig().addDeployment(
                    deployment("distinct-a", aPath.toString(), "/alpha", true));
            data.getDeploymentConfig().addDeployment(
                    deployment("distinct-b", bPath.toString(), "/beta", true));

            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
        }

        @Test
        @DisplayName("invalid context path with .. throws hard exception")
        void invalidContextPathThrowsHardException() throws Exception {
            Path aPath = Files.createTempDirectory("devtomcat-invalid");
            data.getDeploymentConfig().addDeployment(
                    deployment("invalid-app", aPath.toString(), "/foo/../bar", true));

            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Invalid context path"));
            assertTrue(ex.getLocalizedMessage().contains("invalid-app"),
                    "expected offending artifact name in message: " + ex.getLocalizedMessage());
        }
    }

    // =========================================================================
    // WSL-hosted Tomcat (experimental WSL mode)
    // =========================================================================

    @Nested
    @DisplayName("WSL-hosted Tomcat")
    class WslHome {

        @Test
        @DisplayName("a WSL UNC home yields a non-blocking warning, not an exception")
        void wslHomeWarns() {
            data.setTomcatInfo(new TomcatInfo("Tomcat 10", "10.1.20", "\\\\wsl$\\Ubuntu\\opt\\apache-tomcat"));

            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));

            assertTrue(ex instanceof RuntimeConfigurationWarning,
                    "WSL mode must be runnable — expected the warning subclass, got " + ex.getClass());
            assertTrue(ex.getLocalizedMessage().contains("WSL mode"), ex.getLocalizedMessage());
            assertTrue(ex.getLocalizedMessage().contains("Ubuntu"), ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("the directory-existence check is bypassed for a WSL home")
        void directoryCheckBypassed() {
            // The UNC does not exist on this machine; a plain File.isDirectory()
            // would reject it as 'does not exist'. Only the warning may surface.
            data.setTomcatInfo(new TomcatInfo("Tomcat 10", "10.1.20", "\\\\wsl.localhost\\Debian\\opt\\apache-tomcat"));

            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));

            assertTrue(ex instanceof RuntimeConfigurationWarning, ex.getClass().getName());
            assertFalse(ex.getLocalizedMessage().contains("does not exist"), ex.getLocalizedMessage());
        }

        @Test
        @DisplayName("blocking errors still take precedence over the WSL warning")
        void errorsBeforeWarning() {
            data.setTomcatInfo(new TomcatInfo("Tomcat 10", "10.1.20", "\\\\wsl$\\Ubuntu\\opt\\apache-tomcat"));
            data.getDeploymentConfig().setDeployments(java.util.Collections.emptyList());

            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));

            assertFalse(ex instanceof RuntimeConfigurationWarning,
                    "an empty deployment list is a blocking error and must not be downgraded");
            assertTrue(ex.getLocalizedMessage().contains("No deployments"), ex.getLocalizedMessage());
        }
    }

    // =========================================================================
    // Null data
    // =========================================================================

    @Test
    @DisplayName("null data throws NullPointerException")
    void nullDataThrows() {
        assertThrows(Exception.class,
                () -> TomcatConfigurationValidator.validate((TomcatConfigurationData) null));
    }
}
