package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
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
        assertTrue(
                restored.getRemoteConfig().getPassword().isEmpty()
                        || original.getRemoteConfig().getPassword().equals(restored.getRemoteConfig().getPassword()),
                "Password may be externalized to PasswordSafe during serialization"
        );
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
        DeploymentArtifact art2 = new DeploymentArtifact("api", "/path/to/api.war", "war");
        art2.setContextPath("/api");
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
        assertEquals("api", artifacts.get(1).getName());
        assertEquals("/api", artifacts.get(1).getContextPath());
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

    @Test
    @DisplayName("round-trip preserves per-runner settings (startup/shutdown scripts, env vars)")
    void roundTripRunnerSettings() {
        TomcatConfigurationData original = new TomcatConfigurationData();

        RunnerSettings runSettings = original.getRunnerSettings("Run");
        runSettings.setUseDefaultStartup(false);
        runSettings.setStartupScript("/opt/scripts/start.sh");
        runSettings.setUseDefaultShutdown(false);
        runSettings.setShutdownScript("/opt/scripts/stop.sh");
        runSettings.setPassParentEnvs(false);
        runSettings.setEnvironmentVariables(Map.of("CATALINA_OPTS", "-Xmx2g"));
        runSettings.setComputedEnvironmentKeys(java.util.Set.of("JAVA_OPTS"));
        runSettings.setDeletedComputedEnvironmentKeys(java.util.Set.of("CATALINA_OPTS"));

        RunnerSettings debugSettings = original.getRunnerSettings("Debug");
        debugSettings.setUseDefaultStartup(true);
        debugSettings.setStartupScript("");
        debugSettings.setPassParentEnvs(true);
        debugSettings.setEnvironmentVariables(Map.of("DEBUG", "true"));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        // Verify Run profile
        RunnerSettings restoredRun = restored.getRunnerSettings("Run");
        assertFalse(restoredRun.isUseDefaultStartup());
        assertEquals("/opt/scripts/start.sh", restoredRun.getStartupScript());
        assertFalse(restoredRun.isUseDefaultShutdown());
        assertEquals("/opt/scripts/stop.sh", restoredRun.getShutdownScript());
        assertFalse(restoredRun.isPassParentEnvs());
        assertEquals("-Xmx2g", restoredRun.getEnvironmentVariables().get("CATALINA_OPTS"));
        assertTrue(restoredRun.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        assertTrue(restoredRun.getDeletedComputedEnvironmentKeys().contains("CATALINA_OPTS"));

        // Verify Debug profile
        RunnerSettings restoredDebug = restored.getRunnerSettings("Debug");
        assertTrue(restoredDebug.isUseDefaultStartup());
        assertTrue(restoredDebug.isPassParentEnvs());
        assertEquals("true", restoredDebug.getEnvironmentVariables().get("DEBUG"));
    }

    @Test
    @DisplayName("backward compat: old JS debugger attribute name is read correctly")
    void backwardCompatJsDebugger() {
        Element element = new Element("configuration");
        // Old attribute name (pre-rename)
        element.setAttribute("withJavaScriptDebugger", "true");
        element.setAttribute("afterLaunchEnabled", "true");

        TomcatConfigurationData data = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(data, element);

        assertTrue(data.getBrowserConfig().isWithJsDebugger());
    }

    @Test
    @DisplayName("backward compat: old updateAction attribute maps to onUpdate")
    void backwardCompatUpdateAction() {
        Element element = new Element("configuration");
        element.setAttribute("updateAction", "redeploy");

        TomcatConfigurationData data = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(data, element);

        assertEquals("redeploy", data.getUpdateConfig().getOnUpdate());
    }

    @Test
    @DisplayName("write then read with null TomcatInfo does not crash")
    void nullTomcatInfoRoundTrip() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.setTomcatInfo(null);

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertNull(restored.getTomcatInfo());
    }

    @Test
    @DisplayName("write with special characters in context path survives round-trip")
    void specialCharsInContextPath() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.setContextPath("/my-app_v2.0");
        original.getVmConfig().setVmOptions("-Dfoo=\"bar & baz\"");

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals("/my-app_v2.0", restored.getContextPath());
        assertEquals("-Dfoo=\"bar & baz\"", restored.getVmConfig().getVmOptions());
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
