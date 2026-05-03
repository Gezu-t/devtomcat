package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
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

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        data = new TomcatConfigurationData();
        // A real directory on disk — the validator now rejects a missing path
        // as an error (previously a log warning), so the baseline must point
        // at something that actually exists.
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", tempDir.toString());
        data.setTomcatInfo(info);
        PortConfig ports = data.getPortConfig();
        ports.setHttp(8080);
        ports.setShutdown(8005);
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
        @DisplayName("duplicate artifact output paths throw warning")
        void duplicateArtifactPathsThrow() throws Exception {
            Path exploded = Files.createTempDirectory("devtomcat-artifact");

            DeploymentArtifact first = new DeploymentArtifact("webapp-one", exploded.toString(), DeploymentArtifact.TYPE_EXPLODED);
            first.setContextPath("/webapp-one");
            DeploymentArtifact second = new DeploymentArtifact("webapp-other", exploded.toString(), DeploymentArtifact.TYPE_EXPLODED);
            second.setContextPath("/webapp-other");

            data.getDeploymentConfig().addArtifact(first);
            data.getDeploymentConfig().addArtifact(second);

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

            DeploymentArtifact first = new DeploymentArtifact("webapp-two", firstPath.toString(), DeploymentArtifact.TYPE_EXPLODED);
            first.setContextPath("/webapp-two");
            DeploymentArtifact second = new DeploymentArtifact("webapp-two_war_exploded", secondPath.toString(), DeploymentArtifact.TYPE_EXPLODED);
            second.setContextPath("/webapp-two-2");

            data.getDeploymentConfig().addArtifact(first);
            data.getDeploymentConfig().addArtifact(second);

            RuntimeConfigurationWarning ex = assertThrows(
                    RuntimeConfigurationWarning.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getLocalizedMessage().contains("Duplicate deployment for module"));
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
