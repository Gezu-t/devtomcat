package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
import com.dev.idea.plugins.tomcat.utils.ArtifactMatchingUtils;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tomcat run configuration. Delegates init/serialize/validate/clone to helper classes.
 * Accessor methods provide convenience access to sub-configs in {@link TomcatConfigurationData}.
 */
public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunConfiguration.class);

    private final TomcatConfigurationData configData = new TomcatConfigurationData();

    /**
     * True once the Tomcat default log entries have been seeded (or the
     * configuration was loaded from persisted XML that already declared a list).
     * Distinguishes "fresh config — seed defaults" from "user emptied the list —
     * respect their choice" so seeding never reverts an intentional delete-all.
     * Persisted via {@link TomcatConfigurationSerializer}.
     */
    private boolean logsSeeded = false;

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
            // Persists the coverage-enabled flag, runner selection, and per-config
            // suite metadata that JavaCoverageEnabledConfiguration owns. Our
            // TomcatConfigurationSerializer handles DevTomcat's CoverageConfig
            // (include/exclude strings); this sibling call persists the platform
            // side so coverage-session continuity survives IDE restarts. Uses the
            // public CoverageEnabledConfiguration API — the internal
            // CoverageHelper.doWriteExternal shortcut was flagged by Plugin
            // Verifier and could be removed in any IntelliJ patch release.
            CoverageEnabledConfiguration.getOrCreate(this).writeExternal(element);
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
            // Companion to writeExternal above — reloads the platform coverage
            // state (runner id, suite metadata) so that the "Run with Coverage"
            // button on a restarted IDE resumes with the same settings the user
            // last committed to disk. Public API instead of CoverageHelper's
            // @ApiStatus.Internal wrapper (see writeExternal above).
            CoverageEnabledConfiguration.getOrCreate(this).readExternal(element);
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
                // ArtifactManager.getInstance() and getArtifacts() both access the
                // project model and require a read action. The read action covers
                // only model access; list mutations happen outside.
                List<Artifact> matchedArtifacts = ReadAction.compute(() -> {
                    ArtifactManager artifactManager = ArtifactManager.getInstance(project);
                    if (deploymentArtifacts == null || deploymentArtifacts.isEmpty()) {
                        return Collections.<Artifact>emptyList();
                    }
                    List<Artifact> matched = new ArrayList<>();
                    for (DeploymentArtifact deploymentArtifact : deploymentArtifacts) {
                        Artifact a = findMatchingArtifact(artifactManager, deploymentArtifact);
                        if (a != null) {
                            matched.add(a);
                        }
                    }
                    return matched;
                });

                currentTasks.removeIf(task -> task instanceof BuildArtifactsBeforeRunTask);

                if (!matchedArtifacts.isEmpty()) {
                    BuildArtifactsBeforeRunTask buildTask = new BuildArtifactsBeforeRunTask(project);
                    for (Artifact matched : matchedArtifacts) {
                        buildTask.addArtifact(matched);
                        LOG.info("DevTomcat: Linked artifact '" + matched.getName() + "' to Build task");
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
     * Uses the same cascade as {@link ArtifactReferenceRefresher} so renames that
     * preserve the output path or base module name are still matched.
     *
     * <p><b>Must be called under a read action</b> (getArtifacts() accesses project model).
     */
    @Nullable
    private Artifact findMatchingArtifact(@NotNull ArtifactManager artifactManager,
                                          @NotNull DeploymentArtifact deploymentArtifact) {
        return ArtifactMatchingUtils.findMatchingArtifact(artifactManager.getArtifacts(), deploymentArtifact, LOG);
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

    /**
     * Returns the effective browser URL for this run configuration.
     *
     * <p>When the user has customised the URL it is stored verbatim and
     * returned unchanged. When the URL is auto-generated (matches the
     * {@code http://localhost:{port}{contextPath}} pattern for the current
     * port and context), {@link BrowserConfig#getUrl()} holds an empty string
     * and the URL is <em>computed live</em> from the current
     * {@link #getHttpPort()} and {@link #getContextPath()}.
     *
     * <p>This is the <b>single source of truth</b> for the browser URL. The
     * previous design stored the URL with a baked-in port and drifted out of
     * sync whenever the port changed at runtime (auto-resolution, parallel-run
     * isolation). With the URL computed, any change to the authoritative port
     * is reflected everywhere — config dialog, launch-time browser open,
     * Services panel — without a secondary "rewrite" step.
     */
    @NotNull public String getBrowserUrl() {
        String stored = configData.getBrowserConfig().getUrl();
        if (!stored.isEmpty()) {
            return stored;
        }
        return autoBrowserUrl();
    }

    /**
     * Stores the browser URL, normalising to "auto" semantics when the value
     * matches the current auto-generated pattern.
     *
     * <p>Storing an empty string for auto-generated URLs keeps a single source
     * of truth: later reads of {@link #getBrowserUrl()} recompute from the
     * current port, so runtime port changes (auto-resolution, parallel-run
     * isolation) flow through automatically without a rewrite step.
     */
    public void setBrowserUrl(@NotNull String url) {
        String normalized = url == null ? "" : url.trim();
        if (!normalized.isEmpty() && normalized.equals(autoBrowserUrl())) {
            configData.getBrowserConfig().setBrowserUrl("");
        } else {
            configData.getBrowserConfig().setBrowserUrl(normalized);
        }
    }

    /**
     * Computes the auto-generated browser URL from the current port and
     * context path. Exposed so UI sections can mirror the same pattern when
     * deciding whether a user-typed URL should be treated as custom or
     * normalised back to auto.
     */
    @NotNull
    public String autoBrowserUrl() {
        Integer port = getHttpPort();
        String effectivePort = port != null ? port.toString() : TomcatConstants.DEFAULT_PORT;
        String context = getContextPath();
        String effectiveContext = (context != null && !context.isEmpty())
                ? context
                : TomcatConstants.DEFAULT_CONTEXT_PATH;
        return "http://" + TomcatConstants.DEFAULT_HOST + ":" + effectivePort + effectiveContext;
    }
    @NotNull public String getBrowserName()                   { return configData.getBrowserConfig().getBrowserName(); }
    public void setBrowserName(@NotNull String browserName)   { configData.getBrowserConfig().setBrowserName(browserName); }
    public boolean isWithJsDebugger()                         { return configData.getBrowserConfig().isWithJsDebugger(); }
    public void setWithJsDebugger(boolean enabled)            { configData.getBrowserConfig().setWithJsDebugger(enabled); }

    // =====================================================================
    // UI accessors
    // =====================================================================

    public boolean isActivateToolWindow()                  { return configData.getUiConfig().isActivateToolWindow(); }
    public void setActivateToolWindow(boolean activate)    { configData.getUiConfig().setActivateToolWindow(activate); }

    public boolean isAllowMultipleInstances()              { return configData.isAllowMultipleInstances(); }
    public void setAllowMultipleInstances(boolean allow)   { configData.setAllowMultipleInstances(allow); }

    /**
     * Returns {@code true} when "Allow parallel run" is checked <em>and</em>
     * per-run isolation is actually achievable — i.e. the user has not pinned
     * an explicit {@code CATALINA_BASE}.
     *
     * <p>This is the authoritative predicate for every code path that decides
     * whether to treat this configuration as "parallel". A naive check against
     * {@link #isAllowMultipleInstances()} alone would skip the same-executor
     * rerun intercept and spawn a second process against the pinned base —
     * sharing {@code conf/}, {@code work/}, {@code webapps/}, and {@code logs/}
     * between the two live instances. Port auto-bumping masks the symptom until
     * the two processes collide on a file system resource.
     *
     * <p>Callers use this instead of {@link #isAllowMultipleInstances()} so the
     * pinned-base guard is applied once, consistently, across the launch and
     * rerun-interception paths.
     */
    public boolean isParallelRunEffective() {
        if (!isAllowMultipleInstances()) {
            return false;
        }
        String pinned = configData.getCatalinaBase();
        return pinned == null || pinned.trim().isEmpty();
    }


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
     * Seeds the default Tomcat log entries into {@code myLogFiles} exactly once
     * per configuration lifetime — when the seeded flag is still {@code false}.
     * Ensures fresh configs get sensible defaults while respecting every user
     * action that follows (toggle, delete single, delete all).
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@code logsSeeded == true}: no-op. The list is authoritative; an
     *       empty list means "user removed everything".</li>
     *   <li>{@code logsSeeded == false} and list non-empty: mark seeded without
     *       touching entries. Happens when a legacy XML populated the list
     *       before the flag existed.</li>
     *   <li>{@code logsSeeded == false} and list empty: add the six Tomcat
     *       defaults, then mark seeded.</li>
     * </ul>
     */
    public void syncTomcatLogFiles() {
        if (logsSeeded) return;

        if (!super.getLogFiles().isEmpty()) {
            logsSeeded = true;
            return;
        }

        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir == null) return;

        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            String path = logFile.resolveFullPath(logsDir);
            addLogFile(path, logFile.getId(), logFile.isEnabledByDefault());
        }
        logsSeeded = true;
    }

    public boolean isLogsSeeded() {
        return logsSeeded;
    }

    public void setLogsSeeded(boolean seeded) {
        this.logsSeeded = seeded;
    }

    @NotNull
    @Override
    public ArrayList<LogFileOptions> getAllLogFiles() {
        // Ensure Tomcat log entries exist in the internal list for fresh configs.
        // No-op after the one-time seed; empty list after seed means the user
        // removed every entry and we must not resurrect them.
        syncTomcatLogFiles();

        // Refresh dated Tomcat log paths (catalina.YYYY-MM-DD.log etc.) to today's
        // filename so the console tab opens the right file on disk. Skip any
        // entry whose path the user has customised — a path that no longer
        // matches the plugin-managed shape is a deliberate override and must
        // not be overwritten.
        Path logsDir = TomcatProjectUtils.getLogsDirectory(this);
        if (logsDir != null) {
            Map<String, TomcatLogFile> standardById = new HashMap<>();
            for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
                standardById.put(logFile.getId(), logFile);
            }
            for (LogFileOptions opt : super.getAllLogFiles()) {
                TomcatLogFile logFile = standardById.get(opt.getName());
                if (logFile == null) continue;
                if (isPluginManagedPath(opt.getPathPattern(), logsDir, logFile)) {
                    opt.setPathPattern(logFile.resolveFullPath(logsDir));
                }
            }
        }

        return super.getAllLogFiles();
    }

    /**
     * Tests whether a stored log path conforms to the plugin-managed filename
     * shape for a given {@link TomcatLogFile}. Matches on the <b>filename</b>
     * only (e.g. {@code catalina.*.log}) rather than {@code logsDir + filename}
     * so that a path persisted with a different IDE instance's system dir —
     * a sandbox path surviving into a real-IDE install or vice versa — is
     * still recognised as ours and realigned on the next launch. User
     * customisations that rename the file fall outside the filename pattern
     * and are preserved verbatim.
     *
     * <p>The {@code logsDir} parameter is retained for API stability but no
     * longer participates in the decision. Callers may pass any value.
     */
    private static boolean isPluginManagedPath(@Nullable String currentPath,
                                               @NotNull Path logsDir,
                                               @NotNull TomcatLogFile logFile) {
        if (currentPath == null || currentPath.isEmpty()) return true;

        int lastSep = currentPath.lastIndexOf(java.io.File.separator);
        String filename = lastSep < 0 ? currentPath : currentPath.substring(lastSep + 1);
        String pattern = logFile.getFilenamePattern();
        int wildcardIdx = pattern.indexOf('*');
        if (wildcardIdx < 0) {
            return filename.equals(pattern);
        }

        String prefix = pattern.substring(0, wildcardIdx);
        String suffix = pattern.substring(wildcardIdx + 1);
        return filename.length() >= prefix.length() + suffix.length()
                && filename.startsWith(prefix)
                && filename.endsWith(suffix);
    }

    // =====================================================================
    // Script accessors
    // =====================================================================

    public void setStartupScript(String startupScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setStartupScript(startupScript);
    }

    public void setShutdownScript(String shutdownScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setShutdownScript(shutdownScript);
    }
}
