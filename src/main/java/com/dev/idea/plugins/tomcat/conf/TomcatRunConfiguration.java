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

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.RunManagerEx;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider;

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
            syncTomcatLogFiles();
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
                    // Ensure the Before Launch tasks list contains the deployed artifacts right before saving
                    syncBeforeLaunchWithDeployments();
                    TomcatConfigurationSerializer.write(this, element);
                    LOG.debug("Wrote configuration: " + getName());
                } finally {
                    isUpdating.set(false);
                }
            } else {
                LOG.warn("Skipped writing configuration '" + getName() + "' — concurrent update in progress");
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
            syncTomcatLogFiles();
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
            // Deprecated global fetch: default to the 'Run' profile for backwards compatibility
            // if we are simply reading the configuration globally
            java.util.Map<String, String> env = configData.getRunnerSettings("Run").getEnvironmentVariables();
            return env != null ? env : new java.util.HashMap<>();
        } catch (Exception e) {
            LOG.warn("Error getting environment variables", e);
            return new java.util.HashMap<>();
        }
    }

    public void setEnvironmentVariables(@NotNull java.util.Map<String, String> envVars) {
        try {
            configData.getRunnerSettings("Run").setEnvironmentVariables(envVars);
        } catch (Exception e) {
            LOG.warn("Error setting environment variables", e);
        }
    }

    public boolean isPassParentEnvs() {
        try {
            return configData.getRunnerSettings("Run").isPassParentEnvs();
        } catch (Exception e) {
            LOG.warn("Error checking pass parent envs", e);
            return true;
        }
    }

    public void setPassParentEnvs(boolean passParentEnvs) {
        try {
            configData.getRunnerSettings("Run").setPassParentEnvs(passParentEnvs);
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

    /**
     * Synchronizes "Build Artifact" Before Launch tasks with the current deployment artifacts.
     * When an IntelliJ artifact is added to the Deployment tab, this ensures a corresponding
     * "Build Artifact" task appears in Before Launch. When removed, the task is cleaned up.
     */
    @SuppressWarnings("unchecked")
    public void syncBeforeLaunchWithDeployments() {
        try {
            Project project = getProject();
            RunManagerEx runManager = RunManagerEx.getInstanceEx(project);

            ArtifactManager artifactManager;
            try {
                artifactManager = ArtifactManager.getInstance(project);
            } catch (Exception e) {
                LOG.debug("ArtifactManager not available, skipping Before Launch sync");
                return;
            }

            // Get the current Before Launch tasks (raw type from IntelliJ API)
            @SuppressWarnings("rawtypes")
            List<BeforeRunTask> currentTasks = new ArrayList<>(runManager.getBeforeRunTasks(this));

            // Remove all existing BuildArtifactsBeforeRunTask entries (we'll re-add the needed ones)
            currentTasks.removeIf(task -> task instanceof BuildArtifactsBeforeRunTask);

            // For each deployment artifact, find the matching IntelliJ artifact and add a Build task
            List<DeploymentArtifact> deploymentArtifacts = configData.getDeploymentConfig().getArtifacts();
            if (deploymentArtifacts != null) {
                for (DeploymentArtifact deploymentArtifact : deploymentArtifacts) {
                    Artifact matchedArtifact = findMatchingArtifact(artifactManager, deploymentArtifact);
                    if (matchedArtifact != null) {
                        BuildArtifactsBeforeRunTaskProvider provider =
                                new BuildArtifactsBeforeRunTaskProvider(project);
                        BuildArtifactsBeforeRunTask buildTask = provider.createTask(this);
                        if (buildTask != null) {
                            buildTask.addArtifact(matchedArtifact);
                            buildTask.setEnabled(true);
                            currentTasks.add(buildTask);
                            LOG.debug("Added Build Artifact task for: " + matchedArtifact.getName());
                        }
                    }
                }
            }

            runManager.setBeforeRunTasks(this, currentTasks);
            LOG.info("Synced Before Launch tasks: " + currentTasks.size() + " total");

        } catch (Exception e) {
            LOG.warn("Error syncing Before Launch tasks: " + e.getMessage(), e);
        }
    }

    /**
     * Finds an IntelliJ Artifact that matches the given DeploymentArtifact by name.
     */
    @Nullable
    private Artifact findMatchingArtifact(@NotNull ArtifactManager artifactManager,
                                          @NotNull DeploymentArtifact deploymentArtifact) {
        String name = deploymentArtifact.getName();
        if (name.isEmpty()) return null;

        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (name.equals(artifact.getName())) {
                return artifact;
            }
        }
        return null;
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

    /**
     * Syncs Tomcat log file entries into the parent's internal log file list.
     * Called after readExternal() and during initialization so that IntelliJ's
     * framework finds them in myLogFiles and creates the Run tool window tabs.
     *
     * Only adds entries that don't already exist (by name) to prevent duplicate
     * accumulation across repeated calls. The {@link #getAllLogFiles()} override
     * ensures correct path/enabled state regardless of what's in myLogFiles.
     */
    void syncTomcatLogFiles() {
        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) return;

        // Check which log names are already registered in the internal list
        java.util.Set<String> alreadyRegistered = new java.util.HashSet<>();
        for (LogFileOptions opt : super.getAllLogFiles()) {
            alreadyRegistered.add(opt.getName());
        }

        // Only add entries that are missing — avoids duplicate accumulation
        List<String> enabledLogs = getLogFileConfigurations();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            if (alreadyRegistered.contains(logFile.getId())) continue;
            boolean enabled = enabledLogs.contains(logFile.getId()) || logFile.isEnabledByDefault();
            String pathPattern = logsDir.resolve(logFile.getFilenamePattern()).toString();
            addLogFile(pathPattern, logFile.getId(), enabled);
        }
    }

    @NotNull
    @Override
    public ArrayList<LogFileOptions> getAllLogFiles() {
        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) {
            return super.getAllLogFiles();
        }

        java.util.Set<String> tomcatLogIds = new java.util.HashSet<>();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            tomcatLogIds.add(logFile.getId());
        }

        ArrayList<LogFileOptions> result = new ArrayList<>();
        for (LogFileOptions opt : super.getAllLogFiles()) {
            if (!tomcatLogIds.contains(opt.getName())) {
                result.add(opt);
            }
        }

        List<String> enabledLogs = getLogFileConfigurations();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            boolean enabled = enabledLogs.contains(logFile.getId()) || logFile.isEnabledByDefault();
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
        configData.getRunnerSettings("Run").setStartupScript(startupScript);
    }

    public void setShutdownScript(String shutdownScript) {
        configData.getRunnerSettings("Run").setShutdownScript(shutdownScript);
    }
}
