package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationValidator")
class TomcatConfigurationValidatorTest {

    private TomcatConfigurationData data;

    @BeforeEach
    void setUp() {
        data = new TomcatConfigurationData();
        // Set up a valid baseline so individual tests can break one thing at a time
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
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
            assertTrue(ex.getMessage().contains("No Tomcat server selected"));
        }

        @Test
        @DisplayName("empty server name throws")
        void emptyServerName() {
            data.getTomcatInfo().setName("");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("server name is empty"));
        }

        @Test
        @DisplayName("empty server path throws")
        void emptyServerPath() {
            data.getTomcatInfo().setPath("");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("path is not configured"));
        }

        @Test
        @DisplayName("empty version is warning, not error")
        void emptyVersionIsWarning() {
            data.getTomcatInfo().setVersion("");
            // Should not throw — version is a warning, not a blocking error
            assertDoesNotThrow(() -> TomcatConfigurationValidator.validate(data));
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
        @DisplayName("context path without leading slash throws")
        void missingLeadingSlash() {
            data.setContextPath("myapp");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("must start with '/'"));
        }

        @Test
        @DisplayName("context path with spaces throws")
        void spacesInContextPath() {
            data.setContextPath("/my app");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("cannot contain spaces"));
        }

        @Test
        @DisplayName("context path with backslashes throws")
        void backslashesInContextPath() {
            data.setContextPath("/my\\app");
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("cannot contain backslashes"));
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
            assertTrue(ex.getMessage().contains("HTTP"));
        }

        @Test
        @DisplayName("negative port throws")
        void negativePort() {
            data.getPortConfig().setHttp(-1);
            RuntimeConfigurationException ex = assertThrows(
                    RuntimeConfigurationException.class,
                    () -> TomcatConfigurationValidator.validate(data));
            assertTrue(ex.getMessage().contains("HTTP"));
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
