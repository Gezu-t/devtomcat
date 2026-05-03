package com.dev.idea.plugins.tomcat.model;

        import com.dev.idea.plugins.tomcat.TomcatConstants;
        import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
        import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
        import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
        import com.dev.idea.plugins.tomcat.utils.TomcatStrings;
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

            @NotNull public Map<String, RunnerSettings> getRunnerSettingsMap() { return java.util.Collections.unmodifiableMap(runnerSettingsMap); }
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
                    runnerSettingsMap.put(runnerId, rs);
                }
                return rs;
            }

            @Nullable public String getCatalinaBase() { return catalinaBase; }
            public void setCatalinaBase(@Nullable String catalinaBase) { this.catalinaBase = catalinaBase; }

            @Nullable public TomcatInfo getTomcatInfo() { return tomcatInfo; }
            public void setTomcatInfo(@Nullable TomcatInfo info) { this.tomcatInfo = info; }

            @NotNull public String getContextPath() {
                return TomcatStrings.defaultIfBlank(contextPath, DEFAULT_CONTEXT_PATH);
            }
            public void setContextPath(@Nullable String path) {
                // Canonicalize at the model boundary — guarantees slash-prefix, no
                // double slashes, no trailing slash (except root). Mirrors the
                // 1.0.9 DeploymentArtifact.setContextPath fix so non-UI callers
                // (XML deserializer, imported configs, programmatic edits) cannot
                // produce broken URLs in autoBrowserUrl().
                this.contextPath = com.dev.idea.plugins.tomcat.utils.ContextPathUtils
                        .normalizeContextPath(path);
            }

            @NotNull public PortConfig getPortConfig() { return portConfig; }
            public void setPortConfig(@NotNull PortConfig config) { this.portConfig = Objects.requireNonNull(config); }

            @NotNull public DeploymentConfig getDeploymentConfig() { return deploymentConfig; }
            public void setDeploymentConfig(@NotNull DeploymentConfig config) { this.deploymentConfig = Objects.requireNonNull(config); }

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

            @NotNull public String getJreSelection() { return TomcatStrings.defaultIfBlank(jreSelection, DEFAULT_JRE_SELECTION); }
            public void setJreSelection(@Nullable String jre) { this.jreSelection = TomcatStrings.defaultIfBlank(jre, DEFAULT_JRE_SELECTION); }

            public boolean isAllowMultipleInstances() { return uiConfig.isAllowMultipleInstances(); }
            public void setAllowMultipleInstances(boolean allow) { uiConfig.setAllowMultipleInstances(allow); }

            public boolean isStoreAsProjectFile() { return storeAsProjectFile; }
            public void setStoreAsProjectFile(boolean store) { this.storeAsProjectFile = store; }

            @NotNull public String getServerMode() { return TomcatStrings.defaultIfBlank(serverMode, DEFAULT_SERVER_MODE); }
            public void setServerMode(@Nullable String mode) { this.serverMode = TomcatStrings.defaultIfBlank(mode, DEFAULT_SERVER_MODE); }

            @NotNull
            public TomcatConfigurationData clone() {
                TomcatConfigurationData c = new TomcatConfigurationData();
                c.tomcatInfo = this.tomcatInfo != null ? this.tomcatInfo.clone() : null;
                c.contextPath = this.contextPath;
                c.portConfig = this.portConfig.clone();
                c.deploymentConfig = this.deploymentConfig.clone();
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

            /**
             * Copies portable configuration fields from the given source.
             * Used by Config Import to merge an external file into the current config.
             *
             * <p>The following fields are intentionally excluded because they are
             * local/machine-specific and should not travel with shared configs:
             * <ul>
             *   <li>{@code debugConfig} — debug ports depend on the local environment</li>
             *   <li>{@code uiConfig} — tool window preferences are per-user</li>
             *   <li>{@code coverageConfig} — coverage patterns are project-specific</li>
             *   <li>{@code allowMultipleInstances} — local IDE preference</li>
             *   <li>{@code storeAsProjectFile} — IDE storage preference</li>
             * </ul>
             *
             * <p><b>Maintainer note:</b> when adding a new field to this class, decide
             * whether it should be included here (portable) or excluded (machine-local).
             */
            public void copyFrom(@NotNull TomcatConfigurationData source) {
                this.tomcatInfo = source.tomcatInfo != null ? source.tomcatInfo.clone() : null;
                this.contextPath = source.contextPath;
                this.serverMode = source.serverMode;
                this.portConfig = source.portConfig.clone();
                this.deploymentConfig = source.deploymentConfig.clone();
                this.vmConfig = source.vmConfig.clone();
                this.browserConfig = source.browserConfig.clone();
                this.updateConfig = source.updateConfig.clone();
                this.remoteConfig = source.remoteConfig.clone();
                this.jreSelection = source.jreSelection;
                this.catalinaBase = source.catalinaBase;
                // Merge runner settings (env vars, startup scripts) from source
                for (Map.Entry<String, RunnerSettings> entry : source.runnerSettingsMap.entrySet()) {
                    this.runnerSettingsMap.put(entry.getKey(), entry.getValue().clone());
                }
            }

            @Override
            public String toString() {
                return "TomcatConfigurationData{tomcatInfo=" + (tomcatInfo != null ? tomcatInfo.getName() : "null") +
                        ", contextPath='" + getContextPath() + "', serverMode='" + getServerMode() + "'}";
            }

        }