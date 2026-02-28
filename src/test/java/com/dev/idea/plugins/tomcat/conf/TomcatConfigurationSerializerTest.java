package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.jdom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationSerializer")
class TomcatConfigurationSerializerTest {

    /**
     * Core round-trip test: write a fully-populated config, read it back,
     * and verify every field survives the trip.
     */
    @Test
    @DisplayName("full round-trip preserves all fields")
    void fullRoundTrip() {
        TomcatConfigurationData original = createFullConfig();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        // Port config
        assertEquals(original.getPortConfig().getHttp(), restored.getPortConfig().getHttp());
        assertEquals(original.getPortConfig().getShutdown(), restored.getPortConfig().getShutdown());
        assertEquals(original.getPortConfig().getHttps(), restored.getPortConfig().getHttps());
        assertEquals(original.getPortConfig().isHttpsEnabled(), restored.getPortConfig().isHttpsEnabled());
        assertEquals(original.getPortConfig().getJmx(), restored.getPortConfig().getJmx());
        assertEquals(original.getPortConfig().isJmxEnabled(), restored.getPortConfig().isJmxEnabled());
        assertEquals(original.getPortConfig().getAjp(), restored.getPortConfig().getAjp());
        assertEquals(original.getPortConfig().isAjpEnabled(), restored.getPortConfig().isAjpEnabled());

        // Basic settings
        assertEquals(original.getContextPath(), restored.getContextPath());
        assertEquals(original.getServerMode(), restored.getServerMode());
        assertEquals(original.getCatalinaBase(), restored.getCatalinaBase());
        assertEquals(original.getJreSelection(), restored.getJreSelection());
        assertEquals(original.isStoreAsProjectFile(), restored.isStoreAsProjectFile());
        assertEquals(original.isAllowMultipleInstances(), restored.isAllowMultipleInstances());

        // VM config
        assertEquals(original.getVmConfig().getVmOptions(), restored.getVmConfig().getVmOptions());
        assertEquals(original.getRunnerSettings("Run").isPassParentEnvs(), restored.getRunnerSettings("Run").isPassParentEnvs());
        assertEquals(original.getRunnerSettings("Run").getEnvironmentVariables(), restored.getRunnerSettings("Run").getEnvironmentVariables());

        // Browser config
        assertEquals(original.getBrowserConfig().getBrowserUrl(), restored.getBrowserConfig().getBrowserUrl());
        assertEquals(original.getBrowserConfig().isAfterLaunchEnabled(), restored.getBrowserConfig().isAfterLaunchEnabled());
        assertEquals(original.getBrowserConfig().isWithJsDebugger(), restored.getBrowserConfig().isWithJsDebugger());

        // Deployment config
        assertEquals(original.getDeploymentConfig().isHotDeploymentEnabled(), restored.getDeploymentConfig().isHotDeploymentEnabled());
        assertEquals(original.getDeploymentConfig().isUpdateClassesAndResources(), restored.getDeploymentConfig().isUpdateClassesAndResources());
        assertEquals(original.getDeploymentConfig().isPreserveSessions(), restored.getDeploymentConfig().isPreserveSessions());

        // Update config
        assertEquals(original.getUpdateConfig().getOnUpdate(), restored.getUpdateConfig().getOnUpdate());
        assertEquals(original.getUpdateConfig().getOnFrameDeactivation(), restored.getUpdateConfig().getOnFrameDeactivation());
        assertEquals(original.getUpdateConfig().isShowUpdateDialog(), restored.getUpdateConfig().isShowUpdateDialog());
        assertEquals(original.getUpdateConfig().isShowFrameDeactivationDialog(), restored.getUpdateConfig().isShowFrameDeactivationDialog());

        // UI config
        assertEquals(original.getUiConfig().isActivateToolWindow(), restored.getUiConfig().isActivateToolWindow());
        assertEquals(original.getUiConfig().isShowLogsPage(), restored.getUiConfig().isShowLogsPage());

        // Debug config
        assertEquals(original.getDebugConfig().getPort(), restored.getDebugConfig().getPort());
        assertEquals(original.getDebugConfig().getTransport(), restored.getDebugConfig().getTransport());
        assertEquals(original.getDebugConfig().isUseModuleClasspath(), restored.getDebugConfig().isUseModuleClasspath());

        // Remote config
        assertEquals(original.getRemoteConfig().getManagerUrl(), restored.getRemoteConfig().getManagerUrl());
        assertEquals(original.getRemoteConfig().getUsername(), restored.getRemoteConfig().getUsername());
        assertEquals(original.getRemoteConfig().getPassword(), restored.getRemoteConfig().getPassword());
        assertEquals(original.getRemoteConfig().isUseCredentials(), restored.getRemoteConfig().isUseCredentials());

        // TomcatInfo
        assertNotNull(restored.getTomcatInfo());
        assertEquals(original.getTomcatInfo().getName(), restored.getTomcatInfo().getName());
        assertEquals(original.getTomcatInfo().getVersion(), restored.getTomcatInfo().getVersion());
        assertEquals(original.getTomcatInfo().getPath(), restored.getTomcatInfo().getPath());
    }

    @Test
    @DisplayName("round-trip preserves deployment artifacts")
    void roundTripArtifacts() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        DeploymentArtifact art1 = new DeploymentArtifact("myapp", "/path/to/myapp.war", "war");
        art1.setContextPath("/myapp");
        art1.setDeployed(true);
        DeploymentArtifact art2 = new DeploymentArtifact("api", "/path/to/api.war", "war");
        art2.setContextPath("/api");
        art2.setDeployed(false);
        original.getDeploymentConfig().addArtifact(art1);
        original.getDeploymentConfig().addArtifact(art2);

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        List<DeploymentArtifact> artifacts = restored.getDeploymentConfig().getArtifacts();
        assertEquals(2, artifacts.size());
        assertEquals("myapp", artifacts.get(0).getName());
        assertEquals("/myapp", artifacts.get(0).getContextPath());
        assertTrue(artifacts.get(0).isDeployed());
        assertEquals("api", artifacts.get(1).getName());
        assertEquals("/api", artifacts.get(1).getContextPath());
        assertFalse(artifacts.get(1).isDeployed());
    }

    @Test
    @DisplayName("round-trip preserves environment variables")
    void roundTripEnvironmentVariables() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getRunnerSettings("Run").setEnvironmentVariables(Map.of(
                "JAVA_HOME", "/usr/lib/jvm/java-17",
                "CATALINA_OPTS", "-Xmx1024m"
        ));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        Map<String, String> env = restored.getRunnerSettings("Run").getEnvironmentVariables();
        assertEquals("/usr/lib/jvm/java-17", env.get("JAVA_HOME"));
        assertEquals("-Xmx1024m", env.get("CATALINA_OPTS"));
    }

    @Test
    @DisplayName("round-trip preserves log file config")
    void roundTripLogFiles() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getLogFileConfig().addLogFile("/var/log/catalina.out");
        original.getLogFileConfig().addLogFile("/var/log/localhost.log");

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        List<String> logFiles = restored.getLogFileConfig().getLogFiles();
        assertEquals(2, logFiles.size());
        assertTrue(logFiles.contains("/var/log/catalina.out"));
        assertTrue(logFiles.contains("/var/log/localhost.log"));
    }

    @Test
    @DisplayName("round-trip preserves coverage config")
    void roundTripCoverageConfig() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getCoverageConfig().setIncludePatterns(List.of("com.example.*", "com.app.*"));
        original.getCoverageConfig().setExcludePatterns(List.of("com.example.test.*"));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals(List.of("com.example.*", "com.app.*"), restored.getCoverageConfig().getIncludePatterns());
        assertEquals(List.of("com.example.test.*"), restored.getCoverageConfig().getExcludePatterns());
    }

    @Test
    @DisplayName("read empty element produces defaults")
    void readEmptyElement() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.read(data, element);

        // Should have defaults, not crash
        assertEquals("/", data.getContextPath());
        assertNull(data.getTomcatInfo());
        assertEquals(0, data.getDeploymentConfig().getArtifactCount());
    }

    @Test
    @DisplayName("write-read with default config produces equivalent defaults")
    void defaultConfigRoundTrip() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals(original.getPortConfig(), restored.getPortConfig());
        assertEquals(original.getContextPath(), restored.getContextPath());
        assertEquals(original.getServerMode(), restored.getServerMode());
    }

    private TomcatConfigurationData createFullConfig() {
        TomcatConfigurationData data = new TomcatConfigurationData();

        // Ports
        PortConfig pc = data.getPortConfig();
        pc.setHttp(9090);
        pc.setShutdown(9005);
        pc.setHttps(9443);
        pc.setHttpsEnabled(true);
        pc.setJmx(9099);
        pc.setJmxEnabled(true);
        pc.setAjp(9009);
        pc.setAjpEnabled(true);

        // Basic
        data.setContextPath("/myapp");
        data.setServerMode("Remote");
        data.setCatalinaBase("/opt/catalina-base");
        data.setJreSelection("/usr/lib/jvm/java-17");
        data.setStoreAsProjectFile(true);
        data.setAllowMultipleInstances(true);

        // VM
        data.getVmConfig().setVmOptions("-Xmx1024m -Xms512m");
        data.getRunnerSettings("Run").setPassParentEnvs(false);
        data.getRunnerSettings("Run").setEnvironmentVariables(Map.of("JAVA_HOME", "/usr/lib/jvm/java-17"));

        // Browser
        data.getBrowserConfig().setBrowserUrl("http://localhost:9090/myapp");
        data.getBrowserConfig().setAfterLaunchEnabled(false);
        data.getBrowserConfig().setWithJsDebugger(true);

        // Deployment
        data.getDeploymentConfig().setHotDeploymentEnabled(true);
        data.getDeploymentConfig().setUpdateClassesAndResources(true);
        data.getDeploymentConfig().setPreserveSessions(true);

        // Update
        data.getUpdateConfig().setOnUpdate("redeploy");
        data.getUpdateConfig().setOnFrameDeactivation("update_resources");
        data.getUpdateConfig().setShowUpdateDialog(false);
        data.getUpdateConfig().setShowFrameDeactivationDialog(false);

        // UI
        data.getUiConfig().setActivateToolWindow(false);

        // Debug
        data.getDebugConfig().setPort(5006);
        data.getDebugConfig().setTransport("Socket");
        data.getDebugConfig().setUseModuleClasspath(true);

        // Remote
        data.getRemoteConfig().setManagerUrl("http://remote:8080/manager");
        data.getRemoteConfig().setUsername("deployer");
        data.getRemoteConfig().setPassword("secret123");
        data.getRemoteConfig().setUseCredentials(true);

        // TomcatInfo
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
        data.setTomcatInfo(info);

        // Coverage
        data.getCoverageConfig().setIncludePatterns(List.of("com.example.*"));
        data.getCoverageConfig().setExcludePatterns(List.of("com.example.test.*"));

        return data;
    }
}
