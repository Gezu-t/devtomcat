package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat run configuration. Delegates init/serialize/validate/clone to helper classes.
 * Accessor methods provide convenience access to sub-configs in {@link TomcatConfigurationData}.
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

    // =====================================================================
    // IntelliJ Platform overrides
    // =====================================================================

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

    // =====================================================================
    // Core data access
    // =====================================================================

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

    // =====================================================================
    // Port configuration accessors
    // =====================================================================

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
            int port = pc.getHttp();
            if (port > 0) return port;
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
            if (pc.isHttpsEnabled()) {
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
            int port = pc.getShutdown();
            if (port > 0) return port;
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
            if (pc.isJmxEnabled()) {
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

    public boolean isHttpsEnabled() {
        try {
            return configData.getPortConfig().isHttpsEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking HTTPS enabled", e);
            return false;
        }
    }

    public boolean isJmxEnabled() {
        try {
            return configData.getPortConfig().isJmxEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking JMX enabled", e);
            return false;
        }
    }

    // =====================================================================
    // Server & context accessors
    // =====================================================================

    public void setTomcatInfo(@Nullable TomcatInfo info) {
        configData.setTomcatInfo(info);
    }

    @Nullable
    public TomcatInfo getTomcatInfo() {
        return configData.getTomcatInfo();
    }

    @NotNull
    public String getContextPath() {
        return StringUtil.notNullize(configData.getContextPath(), "/");
    }

    public void setContextPath(@Nullable String contextPath) {
        configData.setContextPath(contextPath);
    }

    // =====================================================================
    // VM & environment accessors
    // =====================================================================

    @NotNull
    public String getVmOptions() {
        try {
            return StringUtil.notNullize(configData.getVmConfig().getVmOptions());
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

    // =====================================================================
    // Deployment accessors
    // =====================================================================

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

    // =====================================================================
    // Debug accessors
    // =====================================================================

    public int getDebugPort() {
        try {
            DebugConfig dc = configData.getDebugConfig();
            return dc.getPort();
        } catch (Exception e) {
            LOG.warn("Error getting debug port", e);
            return 5005;
        }
    }

    public void setDebugPort(int port) {
        try {
            configData.getDebugConfig().setPort(port);
        } catch (Exception e) {
            LOG.warn("Error setting debug port", e);
        }
    }

    @NotNull
    public String getDebugTransport() {
        try {
            return configData.getDebugConfig().getTransport();
        } catch (Exception e) {
            LOG.warn("Error getting debug transport", e);
            return "Socket";
        }
    }

    public void setDebugTransport(@NotNull String transport) {
        try {
            configData.getDebugConfig().setTransport(transport);
        } catch (Exception e) {
            LOG.warn("Error setting debug transport", e);
        }
    }

    public boolean isUseModuleClasspath() {
        try {
            return configData.getDebugConfig().isUseModuleClasspath();
        } catch (Exception e) {
            LOG.warn("Error checking module classpath", e);
            return true;
        }
    }

    public void setUseModuleClasspath(boolean useModuleClasspath) {
        try {
            configData.getDebugConfig().setUseModuleClasspath(useModuleClasspath);
        } catch (Exception e) {
            LOG.warn("Error setting use module classpath", e);
        }
    }

    // =====================================================================
    // Browser accessors
    // =====================================================================

    public boolean isAfterLaunchEnabled() {
        try {
            return configData.getBrowserConfig().isAfterLaunchEnabled();
        } catch (Exception e) {
            LOG.warn("Error checking after launch enabled", e);
            return true;
        }
    }

    public void setAfterLaunchEnabled(boolean enabled) {
        try {
            configData.getBrowserConfig().setAfterLaunchEnabled(enabled);
        } catch (Exception e) {
            LOG.warn("Error setting after launch enabled", e);
        }
    }

    @NotNull
    public String getBrowserUrl() {
        try {
            return configData.getBrowserConfig().getBrowserUrl();
        } catch (Exception e) {
            LOG.warn("Error getting browser URL", e);
            return "";
        }
    }

    public void setBrowserUrl(@NotNull String url) {
        try {
            configData.getBrowserConfig().setBrowserUrl(url);
        } catch (Exception e) {
            LOG.warn("Error setting browser URL", e);
        }
    }

    @NotNull
    public String getBrowserName() {
        try {
            return configData.getBrowserConfig().getBrowserName();
        } catch (Exception e) {
            LOG.warn("Error getting browser name", e);
            return "System Default";
        }
    }

    public void setBrowserName(@NotNull String browserName) {
        try {
            configData.getBrowserConfig().setBrowserName(browserName);
        } catch (Exception e) {
            LOG.warn("Error setting browser name", e);
        }
    }

    public boolean isWithJsDebugger() {
        try {
            return configData.getBrowserConfig().isWithJsDebugger();
        } catch (Exception e) {
            LOG.warn("Error checking JS debugger", e);
            return false;
        }
    }

    public void setWithJsDebugger(boolean enabled) {
        try {
            configData.getBrowserConfig().setWithJsDebugger(enabled);
        } catch (Exception e) {
            LOG.warn("Error setting JS debugger", e);
        }
    }

    // =====================================================================
    // UI accessors
    // =====================================================================

    public boolean isActivateToolWindow() {
        try {
            return configData.getUiConfig().isActivateToolWindow();
        } catch (Exception e) {
            LOG.warn("Error checking activate tool window", e);
            return UiConfig.DEFAULT_ACTIVATE_TOOL_WINDOW;
        }
    }

    public void setActivateToolWindow(boolean activate) {
        try {
            configData.getUiConfig().setActivateToolWindow(activate);
        } catch (Exception e) {
            LOG.warn("Error setting activate tool window", e);
        }
    }

    public boolean isShowLogsPage() {
        try {
            return configData.getUiConfig().isShowLogsPage();
        } catch (Exception e) {
            LOG.warn("Error checking show logs page", e);
            return UiConfig.DEFAULT_SHOW_LOGS_PAGE;
        }
    }

    public void setShowLogsPage(boolean show) {
        try {
            configData.getUiConfig().setShowLogsPage(show);
        } catch (Exception e) {
            LOG.warn("Error setting show logs page", e);
        }
    }

    // =====================================================================
    // Log file tabs for Run tool window
    // =====================================================================

    @NotNull
    @Override
    public ArrayList<LogFileOptions> getAllLogFiles() {
        ArrayList<LogFileOptions> result = new ArrayList<>(super.getAllLogFiles());

        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) return result;

        List<String> enabledLogs = getLogFileConfigurations();

        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            boolean enabled = enabledLogs.contains(logFile.getId());
            String pathPattern = logsDir.resolve(logFile.getFilenamePattern()).toString();
            result.add(new LogFileOptions(logFile.getId(), pathPattern, enabled));
        }

        return result;
    }

    // =====================================================================
    // Log & script accessors
    // =====================================================================

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

    public void setStartupScript(String startupScript) {
        LOG.debug("Startup script set to: " + startupScript);
    }

    public void setShutdownScript(String shutdownScript) {
        LOG.debug("Shutdown script set to: " + shutdownScript);
    }
}
