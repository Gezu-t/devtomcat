package com.dev.idea.plugins.tomcat.model;

        import com.dev.idea.plugins.tomcat.TomcatConstants;
        import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
        import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
        import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
        import com.intellij.openapi.util.text.StringUtil;
        import org.jetbrains.annotations.NotNull;
        import org.jetbrains.annotations.Nullable;

        import java.util.Objects;
        import java.util.Map;
        import java.util.LinkedHashMap;

        /**
         * Central configuration model for Dev Tomcat Plugin.
         *
         * Consolidates all configuration into dedicated config objects.
         * No field duplication - delegates to sub-configs.
         */
        public class TomcatConfigurationData implements Cloneable {

            public static final String DEFAULT_CONTEXT_PATH = TomcatConstants.DEFAULT_CONTEXT_PATH;
            public static final String DEFAULT_JRE_SELECTION = TomcatConstants.JRE_PROJECT_DEFAULT;
            public static final String DEFAULT_SERVER_MODE = TomcatConstants.MODE_LOCAL;

            @Nullable private TomcatInfo tomcatInfo;
            @NotNull private String contextPath = DEFAULT_CONTEXT_PATH;
            @NotNull private PortConfig portConfig = new PortConfig();
            @NotNull private DeploymentConfig deploymentConfig = new DeploymentConfig();
            @NotNull private LogFileConfig logFileConfig = new LogFileConfig();
            @NotNull private VmConfig vmConfig = new VmConfig();
            @NotNull private BrowserConfig browserConfig = new BrowserConfig();
            @NotNull private UpdateConfig updateConfig = new UpdateConfig();
            @NotNull private UiConfig uiConfig = new UiConfig();
            @NotNull private DebugConfig debugConfig = new DebugConfig();
            @NotNull private RemoteConfig remoteConfig = new RemoteConfig();
            @NotNull private CoverageConfig coverageConfig = new CoverageConfig();
            @NotNull private String jreSelection = DEFAULT_JRE_SELECTION;
            private boolean storeAsProjectFile;
            @NotNull private String serverMode = DEFAULT_SERVER_MODE;
            @Nullable private String catalinaBase;
            @NotNull private Map<String, RunnerSettings> runnerSettingsMap = new LinkedHashMap<>();

            @NotNull public Map<String, RunnerSettings> getRunnerSettingsMap() { return runnerSettingsMap; }
            public void setRunnerSettingsMap(@Nullable Map<String, RunnerSettings> map) {
                if (map != null) {
                    this.runnerSettingsMap = new LinkedHashMap<>(map);
                } else {
                    this.runnerSettingsMap.clear();
                }
            }
            
            @NotNull
            public RunnerSettings getRunnerSettings(@NotNull String runnerId) {
                RunnerSettings rs = runnerSettingsMap.get(runnerId);
                if (rs == null) {
                    rs = new RunnerSettings();
                    if ("Run".equals(runnerId) && !runnerSettingsMap.isEmpty()) {
                        // Fallback logic for backward compatibility if we only had debug/coverage saved
                        RunnerSettings first = runnerSettingsMap.values().iterator().next();
                        rs = first.clone();
                    }
                    runnerSettingsMap.put(runnerId, rs);
                }
                return rs;
            }

            @Nullable public String getCatalinaBase() { return catalinaBase; }
            public void setCatalinaBase(@Nullable String catalinaBase) { this.catalinaBase = catalinaBase; }

            @Nullable public TomcatInfo getTomcatInfo() { return tomcatInfo; }
            public void setTomcatInfo(@Nullable TomcatInfo info) { this.tomcatInfo = info; }

            @NotNull public String getContextPath() { return StringUtil.notNullize(contextPath, DEFAULT_CONTEXT_PATH); }
            public void setContextPath(@Nullable String path) { this.contextPath = StringUtil.notNullize(path, DEFAULT_CONTEXT_PATH); }

            @NotNull public PortConfig getPortConfig() { return portConfig; }
            public void setPortConfig(@NotNull PortConfig config) { this.portConfig = Objects.requireNonNull(config); }

            @NotNull public DeploymentConfig getDeploymentConfig() { return deploymentConfig; }
            public void setDeploymentConfig(@NotNull DeploymentConfig config) { this.deploymentConfig = Objects.requireNonNull(config); }

            @NotNull public LogFileConfig getLogFileConfig() { return logFileConfig; }
            public void setLogFileConfig(@NotNull LogFileConfig config) { this.logFileConfig = Objects.requireNonNull(config); }

            @NotNull public VmConfig getVmConfig() { return vmConfig; }
            public void setVmConfig(@NotNull VmConfig config) { this.vmConfig = Objects.requireNonNull(config); }

            @NotNull public BrowserConfig getBrowserConfig() { return browserConfig; }
            public void setBrowserConfig(@NotNull BrowserConfig config) { this.browserConfig = Objects.requireNonNull(config); }

            @NotNull public UpdateConfig getUpdateConfig() { return updateConfig; }
            public void setUpdateConfig(@NotNull UpdateConfig config) { this.updateConfig = Objects.requireNonNull(config); }

            @NotNull public UiConfig getUiConfig() { return uiConfig; }
            public void setUiConfig(@NotNull UiConfig config) { this.uiConfig = Objects.requireNonNull(config); }

            @NotNull public DebugConfig getDebugConfig() { return debugConfig; }
            public void setDebugConfig(@NotNull DebugConfig config) { this.debugConfig = Objects.requireNonNull(config); }

            @NotNull public RemoteConfig getRemoteConfig() { return remoteConfig; }
            public void setRemoteConfig(@NotNull RemoteConfig config) { this.remoteConfig = Objects.requireNonNull(config); }

            @NotNull public CoverageConfig getCoverageConfig() { return coverageConfig; }
            public void setCoverageConfig(@NotNull CoverageConfig config) { this.coverageConfig = Objects.requireNonNull(config); }

            @NotNull public String getJreSelection() { return StringUtil.notNullize(jreSelection, DEFAULT_JRE_SELECTION); }
            public void setJreSelection(@Nullable String jre) { this.jreSelection = StringUtil.notNullize(jre, DEFAULT_JRE_SELECTION); }

            public boolean isAllowMultipleInstances() { return uiConfig.isAllowMultipleInstances(); }
            public void setAllowMultipleInstances(boolean allow) { uiConfig.setAllowMultipleInstances(allow); }

            public boolean isStoreAsProjectFile() { return storeAsProjectFile; }
            public void setStoreAsProjectFile(boolean store) { this.storeAsProjectFile = store; }

            @NotNull public String getServerMode() { return StringUtil.notNullize(serverMode, DEFAULT_SERVER_MODE); }
            public void setServerMode(@Nullable String mode) { this.serverMode = StringUtil.notNullize(mode, DEFAULT_SERVER_MODE); }

            @NotNull
            public TomcatConfigurationData clone() {
                TomcatConfigurationData c = new TomcatConfigurationData();
                c.tomcatInfo = this.tomcatInfo;
                c.contextPath = this.contextPath;
                c.portConfig = this.portConfig.clone();
                c.deploymentConfig = this.deploymentConfig.clone();
                c.logFileConfig = this.logFileConfig.clone();
                c.vmConfig = this.vmConfig.clone();
                c.browserConfig = this.browserConfig.clone();
                c.updateConfig = this.updateConfig.clone();
                c.uiConfig = this.uiConfig.clone();
                c.debugConfig = this.debugConfig.clone();
                c.remoteConfig = this.remoteConfig.clone();
                c.coverageConfig = this.coverageConfig.clone();
                c.jreSelection = this.jreSelection;
                c.storeAsProjectFile = this.storeAsProjectFile;
                c.serverMode = this.serverMode;
                c.catalinaBase = this.catalinaBase;
                
                for (Map.Entry<String, RunnerSettings> entry : this.runnerSettingsMap.entrySet()) {
                    c.runnerSettingsMap.put(entry.getKey(), entry.getValue().clone());
                }
                
                return c;
            }

            @Override
            public String toString() {
                return "TomcatConfigurationData{tomcatInfo=" + (tomcatInfo != null ? tomcatInfo.getName() : "null") +
                        ", contextPath='" + getContextPath() + "', serverMode='" + getServerMode() + "'}";
            }

        }