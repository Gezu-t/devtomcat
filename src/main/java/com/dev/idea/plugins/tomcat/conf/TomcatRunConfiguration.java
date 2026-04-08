package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
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

import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.RunManagerEx;
import com.intellij.openapi.application.ReadAction;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tomcat run configuration. Delegates init/serialize/validate/clone to helper classes.
 * Accessor methods provide convenience access to sub-configs in {@link TomcatConfigurationData}.
 */
public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfiguration.class);

    private final TomcatConfigurationData configData = new TomcatConfigurationData();
    public TomcatRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, String name) {
        super(project, factory, name);
        try {
            TomcatConfigurationInitializer.initialize(this);
            syncTomcatLogFiles();
            syncPlatformFlags();
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
            TomcatConfigurationSerializer.write(this, element);
            LOG.debug("Wrote configuration: " + getName());
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
            syncPlatformFlags();
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

    private String docBase = "";

    public String getDocBase() {
        return docBase;
    }

    public void setDocBase(String docBase) {
        this.docBase = docBase;
    }

    // =====================================================================
    // Port configuration accessors
    // =====================================================================

    @Nullable public Integer getHttpPort()     { return positiveOrNull(configData.getPortConfig().getHttp()); }
    @Nullable public Integer getShutdownPort() { return positiveOrNull(configData.getPortConfig().getShutdown()); }

    @Nullable public Integer getHttpsPort() {
        PortConfig pc = configData.getPortConfig();
        return pc.isHttpsEnabled() ? positiveOrNull(pc.getHttps()) : null;
    }

    @Nullable public Integer getJmxPort() {
        PortConfig pc = configData.getPortConfig();
        return pc.isJmxEnabled() ? positiveOrNull(pc.getJmx()) : null;
    }

    @Nullable public Integer getAjpPort() {
        PortConfig pc = configData.getPortConfig();
        return pc.isAjpEnabled() ? positiveOrNull(pc.getAjp()) : null;
    }

    public void setHttpPort(@Nullable Integer port)     { if (port != null && port > 0) configData.getPortConfig().setHttp(port); }
    public void setHttpsPort(@Nullable Integer port)    { if (port != null && port > 0) configData.getPortConfig().setHttps(port); }
    public void setShutdownPort(@Nullable Integer port) { if (port != null && port > 0) configData.getPortConfig().setShutdown(port); }
    public void setJmxPort(@Nullable Integer port)      { if (port != null && port > 0) configData.getPortConfig().setJmx(port); }
    public void setAjpPort(@Nullable Integer port)      { if (port != null && port > 0) configData.getPortConfig().setAjp(port); }

    public boolean isHttpsEnabled() { return configData.getPortConfig().isHttpsEnabled(); }
    public boolean isJmxEnabled()   { return configData.getPortConfig().isJmxEnabled(); }
    public boolean isAjpEnabled()   { return configData.getPortConfig().isAjpEnabled(); }

    @Nullable
    private static Integer positiveOrNull(int port) { return port > 0 ? port : null; }

    // =====================================================================
    // Server mode accessors
    // =====================================================================

    @NotNull
    public String getServerMode() { return configData.getServerMode(); }

    public boolean isRemoteMode() {
        return TomcatConstants.MODE_REMOTE.equalsIgnoreCase(getServerMode());
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
    public String getVmOptions() { return StringUtil.notNullize(configData.getVmConfig().getVmOptions()); }

    public void setVmOptions(@Nullable String vmOptions) { configData.getVmConfig().setVmOptions(vmOptions); }

    @NotNull
    public Map<String, String> getEnvironmentVariables() {
        return configData.getRunnerSettings(TomcatConstants.RUN_MODE).getEnvironmentVariables();
    }

    public void setEnvironmentVariables(@NotNull Map<String, String> envVars) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setEnvironmentVariables(envVars);
    }

    public boolean isPassParentEnvs() { return configData.getRunnerSettings(TomcatConstants.RUN_MODE).isPassParentEnvs(); }

    public void setPassParentEnvs(boolean passParentEnvs) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setPassParentEnvs(passParentEnvs);
    }

    // =====================================================================
    // Deployment accessors
    // =====================================================================

    @NotNull
    public List<DeploymentArtifact> getDeployedArtifacts() {
        return configData.getDeploymentConfig().getDeployedArtifacts();
    }

    public boolean isHotDeploymentEnabled() { return configData.getDeploymentConfig().isHotDeploymentEnabled(); }
    public void setHotDeploymentEnabled(boolean enabled) { configData.getDeploymentConfig().setHotDeploymentEnabled(enabled); }
    public boolean isPreserveSessions()     { return configData.getDeploymentConfig().isPreserveSessions(); }
    public void setPreserveSessions(boolean preserve)    { configData.getDeploymentConfig().setPreserveSessions(preserve); }

    /**
     * Synchronizes "Build Artifact" Before Launch tasks with the current deployment artifacts.
     * When an IntelliJ artifact is added to the Deployment tab, this ensures a corresponding
     * "Build Artifact" task appears in Before Launch. When removed, the task is cleaned up.
     *
     * <p>Also ensures a default "Build" (Make) task is present so the project
     * is compiled before launch, matching IntelliJ Ultimate's Tomcat behaviour.</p>
     *
     * <p><b>Important:</b> Call this only from {@code resetEditorFrom()} —
     * NOT from {@code applyEditorTo()} (panel's doApply overwrites) or
     * {@code writeExternal()} (serialization should be read-only).</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void syncBeforeLaunchWithDeployments() {
        try {
            Project project = getProject();
            RunManagerEx runManager = RunManagerEx.getInstanceEx(project);

            // Get current tasks from RunManager (this is what BeforeRunStepsPanel reads)
            List<BeforeRunTask> currentTasks = new ArrayList<>(runManager.getBeforeRunTasks(this));

            // 1. Ensure a "Build" (Make) task exists — standard for all Java run configs
            boolean hasMake = currentTasks.stream()
                    .anyMatch(t -> t instanceof CompileStepBeforeRun.MakeBeforeRunTask);
            if (!hasMake) {
                CompileStepBeforeRun.MakeBeforeRunTask makeTask =
                        new CompileStepBeforeRun.MakeBeforeRunTask();
                makeTask.setEnabled(true);
                currentTasks.add(0, makeTask);
                LOG.info("DevTomcat: Added default Build (Make) task to Before Launch");
            }

            List<DeploymentArtifact> deploymentArtifacts = getDeployedArtifacts();
            List<String> artifactDisplayNames = deploymentArtifacts == null
                    ? Collections.emptyList()
                    : deploymentArtifacts.stream()
                            .filter(a -> a != null && !a.getDisplayName().isBlank())
                            .map(DeploymentArtifact::getDisplayName)
                            .collect(Collectors.toList());

            // 2a. Ultimate: sync BuildArtifactsBeforeRunTask via ArtifactManager.
            // ArtifactManager.getInstance() requires a read action — syncBeforeLaunchWithDeployments()
            // may be called from background threads (e.g. configuration panel sync on non-EDT).
            boolean ultimateArtifactTaskAdded = false;
            try {
                ArtifactManager artifactManager = ReadAction.compute(
                        () -> ArtifactManager.getInstance(project));
                currentTasks.removeIf(task -> task instanceof BuildArtifactsBeforeRunTask);

                if (deploymentArtifacts != null && !deploymentArtifacts.isEmpty()) {
                    BuildArtifactsBeforeRunTask buildTask = new BuildArtifactsBeforeRunTask(project);
                    for (DeploymentArtifact deploymentArtifact : deploymentArtifacts) {
                        Artifact matched = findMatchingArtifact(artifactManager, deploymentArtifact);
                        if (matched != null) {
                            buildTask.addArtifact(matched);
                            LOG.info("DevTomcat: Linked artifact '" + matched.getName() + "' to Build task");
                        }
                    }
                    if (!buildTask.getArtifactPointers().isEmpty()) {
                        buildTask.setEnabled(true);
                        currentTasks.add(buildTask);
                        ultimateArtifactTaskAdded = true;
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {
                // ArtifactManager not available on Community Edition — fall through
            }

            // 2b. On Community (no ArtifactManager), add our task for visibility and validation.
            //     On Ultimate, BuildArtifactsBeforeRunTask already covers this — skip ours
            //     to avoid duplicate entries in the Before Launch panel.
            if (!ultimateArtifactTaskAdded) {
                syncTomcatBuildArtifactsTask(currentTasks, artifactDisplayNames);
            } else {
                currentTasks.removeIf(t -> t instanceof TomcatBuildArtifactsTask);
            }

            // Set via RunManagerEx — BeforeRunStepsPanel.doReset() reads from here
            runManager.setBeforeRunTasks(this, currentTasks);
            // Also set directly on the config object for platforms that read from it
            setBeforeRunTasks((List) new ArrayList<>(currentTasks));
            LOG.info("DevTomcat: Before Launch sync complete — " + currentTasks.size() + " task(s)"
                    + (ultimateArtifactTaskAdded ? " (Ultimate BuildArtifacts included)" : ""));

        } catch (Exception e) {
            LOG.warn("DevTomcat: Error syncing Before Launch tasks: " + e.getMessage(), e);
        }
    }

    /**
     * Adds or refreshes the {@link TomcatBuildArtifactsTask} in the Before Launch list.
     * If a task already exists its artifact names are updated in place; otherwise a new
     * task is appended. This keeps the "Build N artifact(s)" label current whenever the
     * Deployment tab is saved.
     */
    @SuppressWarnings("rawtypes")
    private void syncTomcatBuildArtifactsTask(@NotNull List<BeforeRunTask> tasks,
                                               @NotNull List<String> artifactNames) {
        for (BeforeRunTask task : tasks) {
            if (task instanceof TomcatBuildArtifactsTask buildTask) {
                buildTask.setArtifactNames(artifactNames);
                buildTask.setEnabled(true);
                return;
            }
        }
        // Not present yet — create and append
        TomcatBuildArtifactsTask newTask = new TomcatBuildArtifactsTask(TomcatBuildArtifactsTaskProvider.ID);
        newTask.setArtifactNames(artifactNames);
        newTask.setEnabled(true);
        tasks.add(newTask);
    }

    /**
     * Finds an IntelliJ Artifact that matches the given DeploymentArtifact.
     * Tries exact name match first, then falls back to case-insensitive matching.
     */
    @Nullable
    private Artifact findMatchingArtifact(@NotNull ArtifactManager artifactManager,
                                          @NotNull DeploymentArtifact deploymentArtifact) {
        String name = deploymentArtifact.getName();
        if (name.isEmpty()) return null;

        // Exact match
        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (name.equals(artifact.getName())) {
                return artifact;
            }
        }

        // Case-insensitive fallback
        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (name.equalsIgnoreCase(artifact.getName())) {
                LOG.info("DevTomcat: Matched artifact by case-insensitive name: " + artifact.getName());
                return artifact;
            }
        }

        // Base module name match (e.g. "webapp-one_war_exploded" matches "webapp-one:war exploded")
        String deployBase = extractBaseModuleName(name);
        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (deployBase.equals(extractBaseModuleName(artifact.getName()))) {
                LOG.info("DevTomcat: Matched artifact by base module name: " + artifact.getName());
                return artifact;
            }
        }

        return null;
    }

    private static String extractBaseModuleName(String name) {
        return ContextPathUtils.extractBaseModuleName(name);
    }

    // =====================================================================
    // Debug accessors
    // =====================================================================

    public int getDebugPort()                              { return configData.getDebugConfig().getPort(); }
    public void setDebugPort(int port)                     { configData.getDebugConfig().setPort(port); }
    @NotNull public String getDebugTransport()             { return configData.getDebugConfig().getTransport(); }
    public void setDebugTransport(@NotNull String transport) { configData.getDebugConfig().setTransport(transport); }
    public boolean isUseModuleClasspath()                  { return configData.getDebugConfig().isUseModuleClasspath(); }
    public void setUseModuleClasspath(boolean use)         { configData.getDebugConfig().setUseModuleClasspath(use); }

    // =====================================================================
    // Browser accessors
    // =====================================================================

    public boolean isAfterLaunchEnabled()                     { return configData.getBrowserConfig().isAfterLaunchEnabled(); }
    public void setAfterLaunchEnabled(boolean enabled)        { configData.getBrowserConfig().setAfterLaunchEnabled(enabled); }
    @NotNull public String getBrowserUrl()                    { return configData.getBrowserConfig().getBrowserUrl(); }
    public void setBrowserUrl(@NotNull String url)            { configData.getBrowserConfig().setBrowserUrl(url); }
    @NotNull public String getBrowserName()                   { return configData.getBrowserConfig().getBrowserName(); }
    public void setBrowserName(@NotNull String browserName)   { configData.getBrowserConfig().setBrowserName(browserName); }
    public boolean isWithJsDebugger()                         { return configData.getBrowserConfig().isWithJsDebugger(); }
    public void setWithJsDebugger(boolean enabled)            { configData.getBrowserConfig().setWithJsDebugger(enabled); }

    // =====================================================================
    // UI accessors
    // =====================================================================

    public boolean isActivateToolWindow()                  { return configData.getUiConfig().isActivateToolWindow(); }
    public void setActivateToolWindow(boolean activate)    { configData.getUiConfig().setActivateToolWindow(activate); }
    public boolean isShowLogsPage()                        { return configData.getUiConfig().isShowLogsPage(); }
    public void setShowLogsPage(boolean show)              { configData.getUiConfig().setShowLogsPage(show); }

    public boolean isAllowMultipleInstances()              { return configData.isAllowMultipleInstances(); }
    public void setAllowMultipleInstances(boolean allow)   { configData.setAllowMultipleInstances(allow); }


    // =====================================================================
    // Log file tabs for Run tool window
    // =====================================================================

    /**
     * Always calls {@code setAllowRunningInParallel(true)} so IntelliJ skips the
     * "Stop and Rerun" dialog and calls {@code doExecute()} while the old process
     * is still alive. {@link com.dev.idea.plugins.tomcat.runner.TomcatRunner} and
     * {@link com.dev.idea.plugins.tomcat.runner.TomcatDebugger} intercept that call
     * and show the Update dialog instead of launching a parallel instance.
     *
     * <p>Called from the constructor and {@code readExternal()} to ensure the flag
     * is always set regardless of serialization or clone order.
     * {@code isAllowRunningInParallel()} is {@code final} in {@link RunConfigurationBase}
     * so it cannot be overridden — this setter approach is the only valid way.
     */
    void syncPlatformFlags() {
        setAllowRunningInParallel(true);
    }

    /**
     * Syncs Tomcat log file entries into the parent's internal log file list.
     * Called after readExternal() and during initialization so that IntelliJ's
     * framework finds them in myLogFiles and creates the Run tool window tabs.
     *
     * Only adds entries that don't already exist (by name) to prevent duplicate
     * accumulation across repeated calls. The {@link #getAllLogFiles()} override
     * ensures correct path/enabled state regardless of what's in myLogFiles.
     */
    public void syncTomcatLogFiles() {
        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) return;

        // Check which log names are already registered in the internal list
        Set<String> alreadyRegistered = new HashSet<>();
        for (LogFileOptions opt : super.getAllLogFiles()) {
            alreadyRegistered.add(opt.getName());
        }

        // Only add entries that are missing — avoids duplicate accumulation
        List<String> enabledLogs = getLogFileConfigurations();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            if (alreadyRegistered.contains(logFile.getId())) continue;
            boolean enabled = enabledLogs.contains(logFile.getId()) || logFile.isEnabledByDefault();
            String path = logFile.resolveFullPath(logsDir);
            addLogFile(path, logFile.getId(), enabled);
        }
    }

    @NotNull
    @Override
    public ArrayList<LogFileOptions> getAllLogFiles() {
        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) {
            return super.getAllLogFiles();
        }

        Set<String> tomcatLogIds = new HashSet<>();
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
        var logFileConfig = configData.getLogFileConfig();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            boolean enabled = enabledLogs.contains(logFile.getId()) || logFile.isEnabledByDefault();
            // Use today's concrete filename (e.g. "catalina.2026-03-08.log") — NOT the glob
            // pattern, because IntelliJ creates a separate tab for every file matching a glob.
            String path = logFile.resolveFullPath(logsDir);
            boolean skipContent = logFileConfig.isSkipContent(logFile.getId());
            result.add(new LogFileOptions(logFile.getId(), path, enabled, skipContent, true));
        }
        return result;
    }

    // =====================================================================
    // Log & script accessors
    // =====================================================================

    @NotNull
    public List<String> getLogFileConfigurations() {
        List<String> logFiles = configData.getLogFileConfig().getLogFiles();
        return logFiles != null ? logFiles : new ArrayList<>();
    }

    public void setStartupScript(String startupScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setStartupScript(startupScript);
    }

    public void setShutdownScript(String shutdownScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setShutdownScript(shutdownScript);
    }
}
