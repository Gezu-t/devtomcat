package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigExportImport")
class ConfigExportImportTest {

    private TomcatConfigurationData data;

    @BeforeEach
    void setUp() {
        data = new TomcatConfigurationData();
        data.setTomcatInfo(new TomcatInfo("Tomcat 10", "10.1.0", "/opt/tomcat10"));
        data.setContextPath("/myapp");
        data.setServerMode("Local");

        PortConfig ports = data.getPortConfig();
        ports.setHttp(9080);
        ports.setShutdown(9005);
        ports.setHttps(9443);
        ports.setJmx(1199);
        ports.setHttpsEnabled(true);
        ports.setJmxEnabled(true);

        VmConfig vm = data.getVmConfig();
        vm.setVmOptions("-Xmx1024m -Dapp.mode=dev");

        BrowserConfig browser = data.getBrowserConfig();
        browser.setOpenBrowser(true);
        browser.setBrowserUrl("http://localhost:9080/myapp");

        data.setJreSelection("Project default");
    }

    // =========================================================================
    // Round-trip export/import
    // =========================================================================

    @Nested
    @DisplayName("Round-trip export/import")
    class RoundTrip {

        @Test
        @DisplayName("exported then imported data preserves all fields")
        void fullRoundTrip() throws Exception {
            String xml = ConfigExportImport.exportToXml(data);
            assertNotNull(xml);
            assertFalse(xml.isEmpty());

            TomcatConfigurationData imported = ConfigExportImport.importFromXml(xml);

            // Server info
            assertNotNull(imported.getTomcatInfo());
            assertEquals("Tomcat 10", imported.getTomcatInfo().getName());
            assertEquals("10.1.0", imported.getTomcatInfo().getVersion());
            assertEquals("/opt/tomcat10", imported.getTomcatInfo().getPath());

            // Context path
            assertEquals("/myapp", imported.getContextPath());
            assertEquals("Local", imported.getServerMode());

            // Ports
            PortConfig ports = imported.getPortConfig();
            assertEquals(9080, ports.getHttp());
            assertEquals(9005, ports.getShutdown());
            assertEquals(9443, ports.getHttps());
            assertEquals(1199, ports.getJmx());
            assertTrue(ports.isHttpsEnabled());
            assertTrue(ports.isJmxEnabled());

            // VM options
            assertEquals("-Xmx1024m -Dapp.mode=dev", imported.getVmConfig().getVmOptions());

            // Browser config
            assertTrue(imported.getBrowserConfig().isOpenBrowser());
            assertEquals("http://localhost:9080/myapp", imported.getBrowserConfig().getBrowserUrl());

            // JRE
            assertEquals("Project default", imported.getJreSelection());
        }

        @Test
        @DisplayName("minimal config round-trips correctly")
        void minimalConfig() throws Exception {
            TomcatConfigurationData minimal = new TomcatConfigurationData();
            String xml = ConfigExportImport.exportToXml(minimal);
            TomcatConfigurationData imported = ConfigExportImport.importFromXml(xml);

            assertEquals("/", imported.getContextPath());
            assertEquals("Local", imported.getServerMode());
        }

        @Test
        @DisplayName("deployment kind, path, context, and packaging survive the round trip")
        void deploymentProvenanceRoundTrips() throws Exception {
            // The old export shape dropped provenance (everything re-imported as
            // an IntelliJ-artifact reference). The kind attribute pins the fix.
            data.getDeploymentConfig().setDeployments(java.util.List.of(
                    new ModuleBackedDeployment(
                            DeploymentPointers.detachedModulePointer("web-module"),
                            java.nio.file.Path.of("/projects/X/target/web-module"),
                            "/web-module", true),
                    new ExternalFileDeployment(
                            java.nio.file.Path.of("/opt/apps/app-1.0.0.war"), "/app", false)));

            String xml = ConfigExportImport.exportToXml(data);
            TomcatConfigurationData imported = ConfigExportImport.importFromXml(xml);

            java.util.List<Deployment> deployments =
                    imported.getDeploymentConfig().getDeployments();
            assertEquals(2, deployments.size());

            Deployment module = deployments.get(0);
            assertEquals(DeploymentKind.MODULE, module.getKind());
            assertEquals("web-module", module.getDisplayName());
            assertEquals("/projects/X/target/web-module",
                    String.valueOf(module.getResolvedPath()));
            assertEquals("/web-module", module.getContextPath());
            assertTrue(module.isExploded());

            Deployment external = deployments.get(1);
            assertEquals(DeploymentKind.EXTERNAL, external.getKind());
            assertEquals("/opt/apps/app-1.0.0.war",
                    String.valueOf(external.getResolvedPath()));
            assertEquals("/app", external.getContextPath());
            assertFalse(external.isExploded());
        }

        @Test
        @DisplayName("old export without kind maps external type to ExternalFileDeployment, rest to artifact-backed")
        void importWithoutKindUsesLegacyMapping() throws Exception {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<devtomcat-config version=\"1.0\" exportedAt=\"2025-01-01T00:00:00\">" +
                    "<deployment>" +
                    "<artifact><name>app-1.0.0.war</name><sourcePath>/opt/apps/app-1.0.0.war</sourcePath>" +
                    "<contextPath>/app</contextPath><type>external</type></artifact>" +
                    "<artifact><name>web-module</name><sourcePath>/projects/X/out/web-module</sourcePath>" +
                    "<contextPath>/web-module</contextPath><type>exploded</type></artifact>" +
                    "</deployment>" +
                    "</devtomcat-config>";
            TomcatConfigurationData imported = ConfigExportImport.importFromXml(xml);

            java.util.List<Deployment> deployments =
                    imported.getDeploymentConfig().getDeployments();
            assertEquals(2, deployments.size());
            assertEquals(DeploymentKind.EXTERNAL, deployments.get(0).getKind());
            assertEquals(DeploymentKind.ARTIFACT, deployments.get(1).getKind());
            assertEquals("web-module", deployments.get(1).getDisplayName());
            assertTrue(deployments.get(1).isExploded());
        }
    }

    // =========================================================================
    // Export format
    // =========================================================================

    @Nested
    @DisplayName("Export format")
    class ExportFormat {

        @Test
        @DisplayName("export produces valid XML with root element")
        void validXmlFormat() {
            String xml = ConfigExportImport.exportToXml(data);
            assertTrue(xml.contains("<devtomcat-config"));
            assertTrue(xml.contains("version=\"1.0\""));
            assertTrue(xml.contains("exportedAt="));
        }

        @Test
        @DisplayName("export includes all config sections")
        void includesAllSections() {
            String xml = ConfigExportImport.exportToXml(data);
            assertTrue(xml.contains("<server>"));
            assertTrue(xml.contains("<ports>"));
            assertTrue(xml.contains("<vm>"));
            assertTrue(xml.contains("<browser>"));
            assertTrue(xml.contains("<deployment"), "Expected deployment section in XML");
        }
    }

    // =========================================================================
    // Import error handling
    // =========================================================================

    @Nested
    @DisplayName("Import error handling")
    class ImportErrors {

        @Test
        @DisplayName("invalid root element throws")
        void invalidRoot() {
            String xml = "<wrong-root><name>test</name></wrong-root>";
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigExportImport.importFromXml(xml));
        }

        @Test
        @DisplayName("invalid XML throws")
        void invalidXml() {
            assertThrows(Exception.class,
                    () -> ConfigExportImport.importFromXml("not xml at all"));
        }

        @Test
        @DisplayName("missing sections use defaults")
        void missingDefaults() throws Exception {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<devtomcat-config version=\"1.0\" exportedAt=\"2025-01-01T00:00:00\">" +
                    "</devtomcat-config>";
            TomcatConfigurationData imported = ConfigExportImport.importFromXml(xml);

            assertEquals("/", imported.getContextPath());
            assertEquals("Local", imported.getServerMode());
            assertEquals(8080, imported.getPortConfig().getHttp());
        }
    }
}
