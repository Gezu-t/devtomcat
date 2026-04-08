package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationData")
class TomcatConfigurationDataTest {

    @Test
    @DisplayName("defaults match constants")
    void defaultValues() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        assertEquals("/", data.getContextPath());
        assertEquals(TomcatConstants.JRE_PROJECT_DEFAULT, data.getJreSelection());
        assertEquals(TomcatConstants.MODE_LOCAL, data.getServerMode());
        assertNull(data.getTomcatInfo());
        assertNull(data.getCatalinaBase());
        assertFalse(data.isStoreAsProjectFile());
        assertFalse(data.isAllowMultipleInstances());
    }

    @Test
    @DisplayName("sub-configs are never null")
    void subConfigsNeverNull() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        assertNotNull(data.getPortConfig());
        assertNotNull(data.getDeploymentConfig());
        assertNotNull(data.getLogFileConfig());
        assertNotNull(data.getVmConfig());
        assertNotNull(data.getBrowserConfig());
        assertNotNull(data.getUpdateConfig());
        assertNotNull(data.getUiConfig());
        assertNotNull(data.getDebugConfig());
        assertNotNull(data.getRemoteConfig());
        assertNotNull(data.getCoverageConfig());
    }

    @Test
    @DisplayName("setters reject null for sub-configs")
    void settersRejectNull() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        // IntelliJ @NotNull instrumentation throws IllegalArgumentException at runtime
        assertThrows(Exception.class, () -> data.setPortConfig(null));
        assertThrows(Exception.class, () -> data.setDeploymentConfig(null));
        assertThrows(Exception.class, () -> data.setLogFileConfig(null));
        assertThrows(Exception.class, () -> data.setVmConfig(null));
        assertThrows(Exception.class, () -> data.setBrowserConfig(null));
        assertThrows(Exception.class, () -> data.setUpdateConfig(null));
        assertThrows(Exception.class, () -> data.setUiConfig(null));
        assertThrows(Exception.class, () -> data.setDebugConfig(null));
        assertThrows(Exception.class, () -> data.setRemoteConfig(null));
        assertThrows(Exception.class, () -> data.setCoverageConfig(null));
    }

    @Test
    @DisplayName("contextPath defaults to / on null or empty")
    void contextPathDefault() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        data.setContextPath(null);
        assertEquals("/", data.getContextPath());
        // Empty string is also normalized to "/" — prevents stale "" from XML deserialization
        data.setContextPath("");
        assertEquals("/", data.getContextPath());
        data.setContextPath("/myapp");
        assertEquals("/myapp", data.getContextPath());
    }

    @Test
    @DisplayName("serverMode defaults to Local on null or empty")
    void serverModeDefault() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        data.setServerMode(null);
        assertEquals(TomcatConstants.MODE_LOCAL, data.getServerMode());
        data.setServerMode("Remote");
        assertEquals("Remote", data.getServerMode());
    }

    @Test
    @DisplayName("jreSelection defaults to Project default on null")
    void jreSelectionDefault() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        data.setJreSelection(null);
        assertEquals(TomcatConstants.JRE_PROJECT_DEFAULT, data.getJreSelection());
    }

    @Test
    @DisplayName("allowMultipleInstances delegates to UiConfig")
    void allowMultipleInstancesDelegation() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        assertFalse(data.isAllowMultipleInstances());
        data.setAllowMultipleInstances(true);
        assertTrue(data.isAllowMultipleInstances());
        assertTrue(data.getUiConfig().isAllowMultipleInstances());
    }

    @Test
    @DisplayName("clone produces independent deep copy")
    void cloneDeepCopy() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.setContextPath("/myapp");
        original.setServerMode("Remote");
        original.getPortConfig().setHttp(9090);
        original.getDeploymentConfig().addArtifact(
                new DeploymentArtifact("app", "/path", "war"));
        original.getDebugConfig().setPort(5006);
        TomcatInfo info = new TomcatInfo("MyTomcat", "9.0", "/opt/tomcat");
        original.setTomcatInfo(info);

        TomcatConfigurationData cloned = original.clone();

        // Values match
        assertEquals("/myapp", cloned.getContextPath());
        assertEquals("Remote", cloned.getServerMode());
        assertEquals(9090, cloned.getPortConfig().getHttp());
        assertEquals(1, cloned.getDeploymentConfig().getArtifactCount());
        assertEquals(5006, cloned.getDebugConfig().getPort());
        assertNotNull(cloned.getTomcatInfo());

        // Sub-configs are independent
        cloned.getPortConfig().setHttp(7070);
        assertEquals(9090, original.getPortConfig().getHttp());

        cloned.getDeploymentConfig().clearArtifacts();
        assertEquals(1, original.getDeploymentConfig().getArtifactCount());

        cloned.setContextPath("/other");
        assertEquals("/myapp", original.getContextPath());
    }

    @Test
    @DisplayName("tomcatInfo can be set and cleared")
    void tomcatInfoSetAndClear() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        assertNull(data.getTomcatInfo());

        TomcatInfo info = new TomcatInfo("Test", "10.1", "/path");
        data.setTomcatInfo(info);
        assertEquals("Test", data.getTomcatInfo().getName());

        data.setTomcatInfo(null);
        assertNull(data.getTomcatInfo());
    }

    @Test
    @DisplayName("copyFrom copies portable fields but leaves UI/debug/coverage/log untouched")
    void copyFromSelectiveFields() {
        TomcatConfigurationData source = new TomcatConfigurationData();
        source.setContextPath("/imported");
        source.setServerMode("Remote");
        source.setJreSelection("Java 17");
        source.getPortConfig().setHttp(9999);
        source.getDeploymentConfig().addArtifact(
                new DeploymentArtifact("importedApp", "/imp", "war"));
        source.getVmConfig().setVmOptions("-Xmx2g");
        source.getBrowserConfig().setUrl("http://imported:9999/");
        TomcatInfo srcInfo = new TomcatInfo("ImportedTomcat", "10.1.5", "/imported/path");
        source.setTomcatInfo(srcInfo);

        TomcatConfigurationData target = new TomcatConfigurationData();
        target.getDebugConfig().setPort(7777);
        target.getUiConfig().setAllowMultipleInstances(true);
        target.getLogFileConfig().addLogFile("/var/log/custom.log");

        target.copyFrom(source);

        // Portable fields copied
        assertEquals("/imported", target.getContextPath());
        assertEquals("Remote", target.getServerMode());
        assertEquals("Java 17", target.getJreSelection());
        assertEquals(9999, target.getPortConfig().getHttp());
        assertEquals(1, target.getDeploymentConfig().getArtifactCount());
        assertEquals("-Xmx2g", target.getVmConfig().getVmOptions());
        assertNotNull(target.getTomcatInfo());
        assertEquals("ImportedTomcat", target.getTomcatInfo().getName());

        // Non-portable fields untouched
        assertEquals(7777, target.getDebugConfig().getPort());
        assertTrue(target.getUiConfig().isAllowMultipleInstances());
        assertTrue(target.getLogFileConfig().hasLogFiles());

        // Source independence (deep copy)
        source.getPortConfig().setHttp(1111);
        assertEquals(9999, target.getPortConfig().getHttp());
    }

    @Test
    @DisplayName("copyFrom with null tomcatInfo clears target info")
    void copyFromNullTomcatInfo() {
        TomcatConfigurationData target = new TomcatConfigurationData();
        target.setTomcatInfo(new TomcatInfo("Old", "9.0", "/old"));

        TomcatConfigurationData source = new TomcatConfigurationData();

        target.copyFrom(source);
        assertNull(target.getTomcatInfo());
    }

    @Test
    @DisplayName("runnerSettingsMap is cloned independently")
    void runnerSettingsMapClone() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getRunnerSettings("Run").setEnvironmentVariables(
                java.util.Map.of("KEY", "VALUE"));

        TomcatConfigurationData cloned = original.clone();
        assertEquals("VALUE", cloned.getRunnerSettings("Run")
                .getEnvironmentVariables().get("KEY"));

        // Independence
        assertNotSame(original.getRunnerSettingsMap(), cloned.getRunnerSettingsMap());
    }

    @Test
    @DisplayName("toString contains key fields")
    void toStringContainsFields() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        data.setContextPath("/myapp");
        String str = data.toString();
        assertTrue(str.contains("/myapp"));
        assertTrue(str.contains("Local"));
    }
}
