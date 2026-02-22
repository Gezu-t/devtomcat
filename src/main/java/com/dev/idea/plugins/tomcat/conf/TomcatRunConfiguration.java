package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import com.dev.idea.plugins.tomcat.TomcatConstants;

/**
 * Tomcat run configuration. Delegates init/serialize/validate/clone to helper classes.
 */
public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfiguration.class);

    private final TomcatConfigurationData configData = new TomcatConfigurationData();
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private List<String> portValidationWarnings = new ArrayList<>();

    public TomcatRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, String name) {
        super(project, factory, name);
        try {
            TomcatConfigurationInitializer.initialize(this);
        } catch (Exception e) {
            LOG.error("Failed to initialize configuration: " + name, e);
        }
    }

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new TomcatConfigurationEditor(getProject());
    }

    @Override
    @Nullable
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) {
        try {
            return new TomcatCommandLineState(env, this);
        } catch (Exception e) {
            LOG.error("Failed to create run profile state for: " + getName(), e);
            return null;
        }
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        try {
            TomcatConfigurationValidator.validate(this);
        } catch (RuntimeConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeConfigurationException("Configuration validation error: " + e.getMessage(), e);
        }
    }

    @Override
    @NotNull
    public RunConfiguration clone() {
        try {
            return TomcatConfigurationCloner.clone(this);
        } catch (Exception e) {
            LOG.error("Failed to clone configuration: " + getName(), e);
            throw new RuntimeException("Cannot clone configuration", e);
        }
    }

    public boolean isLocalMode() {
        String mode = configData.getServerMode();
        return TomcatConstants.MODE_LOCAL.equals(mode);
    }

    public boolean isRemoteMode() {
        String mode = configData.getServerMode();
        return TomcatConstants.MODE_REMOTE.equals(mode);
    }

        @NotNull
    public String getContextPathSafe() {
        String path = configData.getContextPath();
        return StringUtil.notNullize(path, "/");
    }

        public int getHttpPortSafe() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null) {
                int port = pc.getHttp();
                if (port > 0) return port;
            }
        } catch (Exception e) {
            LOG.warn("Error getting HTTP port, using default", e);
        }
        return 8080;
    }

        public int getShutdownPortSafe() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null) {
                int port = pc.getShutdown();
                if (port > 0) return port;
            }
        } catch (Exception e) {
            LOG.warn("Error getting shutdown port, using default", e);
        }
        return 8005;
    }

        public int getHttpsPortSafe() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null && pc.isHttpsEnabled()) {
                int port = pc.getHttps();
                if (port > 0) return port;
            }
        } catch (Exception e) {
            LOG.warn("Error getting HTTPS port, using default", e);
        }
        return 8443;
    }

        public int getJmxPortSafe() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null && pc.isJmxEnabled()) {
                int port = pc.getJmx();
                if (port > 0) return port;
                return 9010;
            }
        } catch (Exception e) {
            LOG.warn("Error getting JMX port", e);
        }
        return 0;
    }

        public boolean isHttpsEnabled() {
        try {
            PortConfig pc = configData.getPortConfig();
            return pc != null && pc.isHttpsEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking HTTPS enabled", e);
            return false;
        }
    }

        public boolean isJmxEnabled() {
        try {
            PortConfig pc = configData.getPortConfig();
            return pc != null && pc.isJmxEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking JMX enabled", e);
            return false;
        }
    }

        public int getDebugPortSafe() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            if (dc != null) {
                int port = dc.getPort();
                if (port > 0) return port;
            }
        } catch (Exception e) {
            LOG.warn("Error getting debug port, using default", e);
        }
        return 5005;
    }

        @NotNull
    public String getDebugTransportSafe() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            if (dc != null) {
                String transport = dc.getTransport();
                if (StringUtil.isNotEmpty(transport)) {
                    return transport;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error getting debug transport, using default", e);
        }
        return "Socket";
    }

        public boolean isUseModuleClasspath() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            return dc != null && dc.isUseModuleClasspath();
        } catch (Exception e) {
            LOG.warn("Error checking module classpath", e);
            return true;
        }
    }

        @NotNull
    public String getManagerUrlSafe() {
        try {
            RemoteConfig rc = configData.getRemoteConfig();
            if (rc != null) {
                String url = rc.getManagerUrl();
                return StringUtil.notNullize(url);
            }
        } catch (Exception e) {
            LOG.warn("Error getting manager URL", e);
        }
        return "";
    }

        @NotNull
    public String getRemoteUsernameSafe() {
        try {
            RemoteConfig rc = configData.getRemoteConfig();
            if (rc != null) {
                String username = rc.getUsername();
                return StringUtil.notNullize(username);
            }
        } catch (Exception e) {
            LOG.warn("Error getting remote username", e);
        }
        return "";
    }

        @NotNull
    public String getRemotePasswordSafe() {
        try {
            RemoteConfig rc = configData.getRemoteConfig();
            if (rc != null) {
                String password = rc.getPassword();
                return StringUtil.notNullize(password);
            }
        } catch (Exception e) {
            LOG.warn("Error getting remote password", e);
        }
        return "";
    }

        public boolean isUseRemoteCredentials() {
        try {
            RemoteConfig rc = configData.getRemoteConfig();
            return rc != null && rc.isUseCredentials();
        } catch (Exception e) {
            LOG.warn("Error checking remote credentials", e);
            return false;
        }
    }

        @NotNull
    public TomcatConfigurationData getConfigData() {
        return configData;
    }

    @Deprecated
    private String docBase = "";

    @Deprecated
    public String getDocBase() {
        return docBase;
    }

    @Deprecated
    public void setDocBase(String docBase) {
        this.docBase = docBase;
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            super.writeExternal(element);

            if (!isUpdating.getAndSet(true)) {
                try {
                    TomcatConfigurationSerializer.write(this, element);
                    LOG.debug("Wrote configuration: " + getName());
                } finally {
                    isUpdating.set(false);
                }
            }
        } catch (WriteExternalException e) {
            LOG.error("Failed to write configuration: " + getName(), e);
            throw e;
        } catch (Exception e) {
            LOG.error("Unexpected error writing configuration: " + getName(), e);
            throw new WriteExternalException("Configuration write error: " + e.getMessage(), e);
        }
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        Objects.requireNonNull(element, "Element cannot be null");

        try {
            super.readExternal(element);
            TomcatConfigurationSerializer.read(this, element);
            TomcatConfigurationInitializer.refresh(this);
            LOG.debug("Read configuration: " + getName());
        } catch (InvalidDataException e) {
            LOG.error("Failed to read configuration: " + getName(), e);
            throw e;
        } catch (Exception e) {
            LOG.error("Unexpected error reading configuration: " + getName(), e);
            throw new InvalidDataException("Configuration read error: " + e.getMessage(), e);
        }
    }

    public void refreshDynamicConfiguration() {
        if (isUpdating.compareAndSet(false, true)) {
            try {
                TomcatConfigurationInitializer.refresh(this);
                LOG.debug("Refreshed dynamic configuration: " + getName());
            } catch (Exception e) {
                LOG.error("Failed to refresh configuration: " + getName(), e);
            } finally {
                isUpdating.set(false);
            }
        } else {
            LOG.debug("Refresh already in progress for: " + getName());
        }
    }

    @NotNull
    public String getConfigurationSummary() {
        try {
            String server = "None";
            try {
                TomcatInfo info = configData.getTomcatInfo();
                if (info != null) {
                    String name = StringUtil.notNullize(info.getName(), "Unknown");
                    String version = StringUtil.notNullize(info.getVersion(), "?");
                    server = name + " (" + version + ")";
                }
            } catch (Exception e) {
                LOG.debug("Error getting Tomcat info for summary", e);
            }

            String portInfo = "N/A";
            try {
                PortConfig pc = configData.getPortConfig();
                if (pc != null) {
                    String httpsStr = pc.isHttpsEnabled() ? String.valueOf(pc.getHttps()) : "off";
                    String jmxStr = pc.isJmxEnabled() ? String.valueOf(pc.getJmx()) : "off";
                    portInfo = String.format("HTTP:%d | HTTPS:%s | JMX:%s", pc.getHttp(), httpsStr, jmxStr);
                }
            } catch (Exception e) {
                LOG.debug("Error getting port info for summary", e);
            }

            String debugStr = "N/A";
            try {
                DebugConfig dc = configData.getDebugConfig();
                if (dc != null) {
                    debugStr = "Port:" + dc.getPort() + ", Transport:" + dc.getTransport();
                }
            } catch (Exception e) {
                LOG.debug("Error getting debug info for summary", e);
            }

            int artifactCount = 0;
            try {
                var deploymentConfig = configData.getDeploymentConfig();
                if (deploymentConfig != null) {
                    List<DeploymentArtifact> artifacts = deploymentConfig.getArtifacts();
                    if (artifacts != null) {
                        artifactCount = artifacts.size();
                    }
                }
            } catch (Exception e) {
                LOG.debug("Error getting artifact count for summary", e);
            }

            String mode = StringUtil.notNullize(configData.getServerMode(), TomcatConstants.MODE_LOCAL);
            String remoteInfo = "";
            try {
                if (isRemoteMode()) {
                    String managerUrl = getManagerUrlSafe();
                    if (StringUtil.isNotEmpty(managerUrl)) {
                        remoteInfo = " | Manager: " + managerUrl;
                    }
                }
            } catch (Exception e) {
                LOG.debug("Error getting remote info for summary", e);
            }

            return String.format(
                    "Tomcat[%s] | Mode:%s%s | %s | Debug:%s | Context:%s | Artifacts:%d",
                    server, mode, remoteInfo,
                    portInfo, debugStr,
                    getContextPathSafe(), artifactCount
            );
        } catch (Exception e) {
            LOG.error("Failed to generate configuration summary for: " + getName(), e);
            return "Configuration Summary: Error";
        }
    }

        public void setPortValidationWarnings(@Nullable List<String> warnings) {
        this.portValidationWarnings = warnings != null ? warnings : new ArrayList<>();
    }

        @NotNull
    public List<String> getPortValidationWarnings() {
        return portValidationWarnings;
    }

        @Nullable
    public Integer getHttpPort() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null) {
                int port = pc.getHttp();
                if (port > 0) return port;
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Error getting HTTP port", e);
            return null;
        }
    }

        @Nullable
    public Integer getHttpsPort() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null && pc.isHttpsEnabled()) {
                int port = pc.getHttps();
                if (port > 0) return port;
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Error getting HTTPS port", e);
            return null;
        }
    }

        @Nullable
    public Integer getShutdownPort() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null) {
                int port = pc.getShutdown();
                if (port > 0) return port;
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Error getting shutdown port", e);
            return null;
        }
    }

        @Nullable
    public Integer getJmxPort() {
        try {
            PortConfig pc = configData.getPortConfig();
            if (pc != null && pc.isJmxEnabled()) {
                int port = pc.getJmx();
                if (port > 0) return port;
            }

            return null;
        } catch (Exception e) {
            LOG.warn("Error getting JMX port", e);
            return null;
        }
    }

        public void setHttpPort(@Nullable Integer port) {
        try {
            if (port != null && port > 0) {
                configData.getPortConfig().setHttp(port);
            }
        } catch (Exception e) {
            LOG.warn("Error setting HTTP port", e);
        }
    }

        public void setHttpsPort(@Nullable Integer port) {
        try {
            if (port != null && port > 0) {
                configData.getPortConfig().setHttps(port);
            }
        } catch (Exception e) {
            LOG.warn("Error setting HTTPS port", e);
        }
    }

        public void setShutdownPort(@Nullable Integer port) {
        try {
            if (port != null && port > 0) {
                configData.getPortConfig().setShutdown(port);
            }
        } catch (Exception e) {
            LOG.warn("Error setting shutdown port", e);
        }
    }

        public void setJmxPort(@Nullable Integer port) {
        try {
            if (port != null && port > 0) {
                configData.getPortConfig().setJmx(port);
            }
        } catch (Exception e) {
            LOG.warn("Error setting JMX port", e);
        }
    }

        public void setTomcatInfo(@Nullable TomcatInfo info) {
        configData.setTomcatInfo(info);
    }

        @Nullable
    public TomcatInfo getTomcatInfo() {
        return configData.getTomcatInfo();
    }

        @NotNull
    public String getVmOptions() {
        try {
            String opts = configData.getVmConfig().getVmOptions();
            return StringUtil.notNullize(opts);
        } catch (Exception e) {
            LOG.warn("Error getting VM options", e);
            return "";
        }
    }

        public void setVmOptions(@Nullable String vmOptions) {
        try {
            configData.getVmConfig().setVmOptions(vmOptions);
        } catch (Exception e) {
            LOG.warn("Error setting VM options", e);
        }
    }

        public boolean isHotDeploymentEnabled() {
        try {
            return configData.getDeploymentConfig().isHotDeploymentEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking hot deployment", e);
            return false;
        }
    }

        public void setHotDeploymentEnabled(boolean enabled) {
        try {
            configData.getDeploymentConfig().setHotDeploymentEnabled(enabled);
        } catch (Exception e) {
            LOG.warn("Error setting hot deployment", e);
        }
    }

        public boolean isPreserveSessions() {
        try {
            return configData.getDeploymentConfig().isPreserveSessions();
        } catch (Exception e) {
            LOG.warn("Error checking preserve sessions", e);
            return false;
        }
    }

        public void setPreserveSessions(boolean preserve) {
        try {
            configData.getDeploymentConfig().setPreserveSessions(preserve);
        } catch (Exception e) {
            LOG.warn("Error setting preserve sessions", e);
        }
    }

        @NotNull
    public String getContextPath() {
        return getContextPathSafe();
    }

        public void setContextPath(@Nullable String contextPath) {
        configData.setContextPath(contextPath);
    }

        @NotNull
    public java.util.List<String> getLogFileConfigurations() {
        try {
            java.util.List<String> logFiles = configData.getLogFileConfig().getLogFiles();
            return logFiles != null ? logFiles : new java.util.ArrayList<>();
        } catch (Exception e) {
            LOG.warn("Error getting log file configurations", e);
            return new java.util.ArrayList<>();
        }
    }

        @NotNull
    public java.util.Map<String, String> getEnvironmentVariables() {
        try {
            java.util.Map<String, String> env = configData.getVmConfig().getEnvironmentVariables();
            return env != null ? env : new java.util.HashMap<>();
        } catch (Exception e) {
            LOG.warn("Error getting environment variables", e);
            return new java.util.HashMap<>();
        }
    }

        public void setEnvironmentVariables(@NotNull java.util.Map<String, String> envVars) {
        try {
            configData.getVmConfig().setEnvironmentVariables(envVars);
        } catch (Exception e) {
            LOG.warn("Error setting environment variables", e);
        }
    }

        public boolean isPassParentEnvs() {
        try {
            return configData.getVmConfig().isPassParentEnvs();
        } catch (Exception e) {
            LOG.warn("Error checking pass parent envs", e);
            return true;
        }
    }

        public void setPassParentEnvs(boolean passParentEnvs) {
        try {
            configData.getVmConfig().setPassParentEnvs(passParentEnvs);
        } catch (Exception e) {
            LOG.warn("Error setting pass parent envs", e);
        }
    }

        public int getDebugPort() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            return dc != null ? dc.getPort() : 5005;
        } catch (Exception e) {
            LOG.warn("Error getting debug port", e);
            return 5005;
        }
    }

        public void setDebugPort(int port) {
        try {
            DebugConfig dc = configData.getDebugConfig();
            if (dc != null) {
                dc.setPort(port);
            }
        } catch (Exception e) {
            LOG.warn("Error setting debug port", e);
        }
    }

        @NotNull
    public String getDebugTransport() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            return dc != null ? dc.getTransport() : "Socket";
        } catch (Exception e) {
            LOG.warn("Error getting debug transport", e);
            return "Socket";
        }
    }

        public void setDebugTransport(@NotNull String transport) {
        try {
            DebugConfig dc = configData.getDebugConfig();
            if (dc != null) {
                dc.setTransport(transport);
            }
        } catch (Exception e) {
            LOG.warn("Error setting debug transport", e);
        }
    }

        public void setUseModuleClasspath(boolean useModuleClasspath) {
        try {
            DebugConfig dc = configData.getDebugConfig();
            if (dc != null) {
                dc.setUseModuleClasspath(useModuleClasspath);
            }
        } catch (Exception e) {
            LOG.warn("Error setting use module classpath", e);
        }
    }

        public void setStartupScript(String startupScript) {
        try {
            LOG.debug("Startup script set to: " + startupScript);
        } catch (Exception e) {
            LOG.warn("Error setting startup script", e);
        }
    }

        public void setShutdownScript(String shutdownScript) {
        try {
            LOG.debug("Shutdown script set to: " + shutdownScript);
        } catch (Exception e) {
            LOG.warn("Error setting shutdown script", e);
        }
    }

        public boolean isUpdateClassesAndResources() {
        try {
            return configData.getDeploymentConfig().isUpdateClassesAndResources();
        } catch (Exception e) {
            LOG.warn("Error checking update classes and resources", e);
            return false;
        }
    }

        public boolean isActivateToolWindow() {
        try {
            UiConfig ui = configData.getUiConfig();
            return ui != null && ui.isActivateToolWindow();
        } catch (Exception e) {
            LOG.warn("Error checking activate tool window", e);
            return UiConfig.DEFAULT_ACTIVATE_TOOL_WINDOW;
        }
    }

        public void setActivateToolWindow(boolean activate) {
        try {
            UiConfig ui = configData.getUiConfig();
            if (ui != null) {
                ui.setActivateToolWindow(activate);
            }
        } catch (Exception e) {
            LOG.warn("Error setting activate tool window", e);
        }
    }

        public boolean isFocusToolWindow() {
        try {
            UiConfig ui = configData.getUiConfig();
            return ui != null && ui.isFocusToolWindow();
        } catch (Exception e) {
            LOG.warn("Error checking focus tool window", e);
            return UiConfig.DEFAULT_FOCUS_TOOL_WINDOW;
        }
    }

        public void setFocusToolWindow(boolean focus) {
        try {
            UiConfig ui = configData.getUiConfig();
            if (ui != null) {
                ui.setFocusToolWindow(focus);
            }
        } catch (Exception e) {
            LOG.warn("Error setting focus tool window", e);
        }
    }

        public boolean isAfterLaunchEnabled() {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            return bc != null && bc.isAfterLaunchEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking after launch enabled", e);
            return true;
        }
    }

        public void setAfterLaunchEnabled(boolean enabled) {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            if (bc != null) {
                bc.setAfterLaunchEnabled(enabled);
            }
        } catch (Exception e) {
            LOG.warn("Error setting after launch enabled", e);
        }
    }

        @NotNull
    public String getBrowserUrl() {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            return bc != null ? bc.getBrowserUrl() : "";
        } catch (Exception e) {
            LOG.warn("Error getting browser URL", e);
            return "";
        }
    }

        public void setBrowserUrl(@NotNull String url) {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            if (bc != null) {
                bc.setBrowserUrl(url);
            }
        } catch (Exception e) {
            LOG.warn("Error setting browser URL", e);
        }
    }

        @NotNull
    public String getBrowserName() {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            return bc != null ? bc.getBrowserName() : "System Default";
        } catch (Exception e) {
            LOG.warn("Error getting browser name", e);
            return "System Default";
        }
    }

        public void setBrowserName(@NotNull String browserName) {
        try {
            BrowserConfig bc = configData.getBrowserConfig();
            if (bc != null) {
                bc.setBrowserName(browserName);
            }
        } catch (Exception e) {
            LOG.warn("Error setting browser name", e);
        }
    }

}