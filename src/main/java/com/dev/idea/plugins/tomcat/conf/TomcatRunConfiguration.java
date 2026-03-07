package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.*;
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

import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.RunManagerEx;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.packaging.elements.CompositePackagingElement;
import com.intellij.packaging.elements.PackagingElementFactory;
import com.intellij.packaging.impl.artifacts.PlainArtifactType;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    public java.util.Map<String, String> getEnvironmentVariables() {
        return configData.getRunnerSettings(TomcatConstants.RUN_MODE).getEnvironmentVariables();
    }

    public void setEnvironmentVariables(@NotNull java.util.Map<String, String> envVars) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setEnvironmentVariables(envVars);
    }

    public boolean isPassParentEnvs() { return configData.getRunnerSettings(TomcatConstants.RUN_MODE).isPassParentEnvs(); }

    public void setPassParentEnvs(boolean passParentEnvs) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setPassParentEnvs(passParentEnvs);
    }

    // =====================================================================
    // Deployment accessors
    // =====================================================================

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

            // 2. Sync Build Artifact tasks with deployment artifacts
            currentTasks.removeIf(task -> task instanceof BuildArtifactsBeforeRunTask);

            ArtifactManager artifactManager;
            try {
                artifactManager = ArtifactManager.getInstance(project);
            } catch (Exception e) {
                LOG.info("DevTomcat: ArtifactManager not available, skipping artifact sync");
                runManager.setBeforeRunTasks(this, currentTasks);
                return;
            }

            Artifact[] allArtifacts = artifactManager.getArtifacts();
            LOG.info("DevTomcat: IntelliJ ArtifactManager has " + allArtifacts.length + " artifacts: " +
                    java.util.Arrays.stream(allArtifacts).map(Artifact::getName)
                            .collect(java.util.stream.Collectors.joining(", ")));

            List<DeploymentArtifact> deploymentArtifacts = configData.getDeploymentConfig().getDeployedArtifacts();
            LOG.info("DevTomcat: Deployment config has " +
                    (deploymentArtifacts != null ? deploymentArtifacts.size() : 0) + " artifacts" +
                    (deploymentArtifacts != null ? ": " + deploymentArtifacts.stream()
                            .map(DeploymentArtifact::getName)
                            .collect(java.util.stream.Collectors.joining(", ")) : ""));

            if (deploymentArtifacts != null && !deploymentArtifacts.isEmpty()) {
                // Consolidate all deployment artifacts into a single BuildArtifactsBeforeRunTask
                // so the Before Launch list shows "Build N artifacts" instead of separate entries
                BuildArtifactsBeforeRunTask buildTask = new BuildArtifactsBeforeRunTask(project);
                for (DeploymentArtifact deploymentArtifact : deploymentArtifacts) {
                    Artifact matchedArtifact = findMatchingArtifact(artifactManager, deploymentArtifact);

                    // If no match, auto-create an IntelliJ artifact for this deployment
                    if (matchedArtifact == null) {
                        matchedArtifact = autoCreateArtifactForDeployment(artifactManager, deploymentArtifact);
                    }

                    if (matchedArtifact != null) {
                        buildTask.addArtifact(matchedArtifact);
                        LOG.info("DevTomcat: Added artifact to Build task: " + matchedArtifact.getName());
                    } else {
                        LOG.warn("DevTomcat: No matching IntelliJ artifact for deployment '" +
                                deploymentArtifact.getName() + "' and could not auto-create one");
                    }
                }
                if (!buildTask.getArtifactPointers().isEmpty()) {
                    buildTask.setEnabled(true);
                    currentTasks.add(buildTask);
                }
            }

            // Set via RunManagerEx — BeforeRunStepsPanel.doReset() reads from here
            runManager.setBeforeRunTasks(this, currentTasks);
            // Also set directly on the config object for platforms that read from it
            setBeforeRunTasks((List) new ArrayList<>(currentTasks));
            LOG.info("DevTomcat: Before Launch sync complete — " + currentTasks.size() + " total tasks");

        } catch (Exception e) {
            LOG.warn("DevTomcat: Error syncing Before Launch tasks: " + e.getMessage(), e);
        }
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
        return com.dev.idea.plugins.tomcat.utils.ContextPathUtils.extractBaseModuleName(name);
    }

    /**
     * Auto-creates an IntelliJ Artifact for a deployment that has no existing match.
     * Finds the web module in the project and creates a WAR-exploded artifact structure
     * so that "Build Artifact" can appear in Before Launch.
     */
    @Nullable
    private Artifact autoCreateArtifactForDeployment(@NotNull ArtifactManager artifactManager,
                                                      @NotNull DeploymentArtifact deployment) {
        Project project = getProject();
        Module module = findModuleForDeployment(project, deployment);
        if (module == null) {
            LOG.info("DevTomcat: No module found for deployment '" + deployment.getName() +
                    "', cannot auto-create artifact");
            return null;
        }

        try {
            // Choose artifact type: prefer exploded-war (Ultimate) > plain (Community)
            ArtifactType artifactType = ArtifactType.findById("exploded-war");
            if (artifactType == null) artifactType = ArtifactType.findById("exploded");
            if (artifactType == null) artifactType = PlainArtifactType.getInstance();

            String artifactName = module.getName() + TomcatConstants.ARTIFACT_SUFFIX_WAR_EXPLODED;

            // Check if this artifact already exists (maybe created earlier)
            for (Artifact a : artifactManager.getArtifacts()) {
                if (artifactName.equals(a.getName())) {
                    LOG.info("DevTomcat: Found existing artifact: " + artifactName);
                    return a;
                }
            }

            PackagingElementFactory factory = PackagingElementFactory.getInstance();
            CompositePackagingElement<?> root = factory.createArtifactRootElement();

            // WEB-INF/classes ← module compiled output
            CompositePackagingElement<?> classesDir = factory.getOrCreateDirectory(root, TomcatConstants.WEB_INF_CLASSES_PATH);
            classesDir.addOrFindChild(factory.createModuleOutput(module));

            // WEB-INF/lib ← module library dependencies
            CompositePackagingElement<?> libDir = factory.getOrCreateDirectory(root, TomcatConstants.WEB_INF_LIB_PATH);
            OrderEnumerator.orderEntries(module)
                    .withoutSdk()
                    .withoutModuleSourceEntries()
                    .forEachLibrary(library -> {
                        if (library != null) {
                            try {
                                for (com.intellij.packaging.elements.PackagingElement<?> element :
                                        factory.createLibraryElements(library)) {
                                    libDir.addOrFindChild(element);
                                }
                            } catch (Exception e) {
                                LOG.debug("DevTomcat: Skipping library: " + library.getName());
                            }
                        }
                        return true;
                    });

            // Add web resource root content (e.g. src/main/webapp)
            List<VirtualFile> webRoots = TomcatModuleUtils.findWebRoots(module);
            for (VirtualFile webRoot : webRoots) {
                root.addOrFindChild(factory.createDirectoryCopyWithParentDirectories(webRoot.getPath(), ""));
            }

            String basePath = project.getBasePath();
            if (basePath == null) {
                LOG.warn("Project has no base path, cannot create artifact output directory");
                return null;
            }
            String outputPath = basePath + "/out/artifacts/" +
                    module.getName().replace(':', '_') + "_war_exploded";

            final ArtifactType finalType = artifactType;
            final Artifact[] result = new Artifact[1];

            WriteAction.runAndWait(() -> {
                ModifiableArtifactModel model = artifactManager.createModifiableModel();
                com.intellij.packaging.artifacts.ModifiableArtifact modArtifact =
                        model.addArtifact(artifactName, finalType, root);
                modArtifact.setOutputPath(outputPath);
                model.commit();
                for (Artifact a : artifactManager.getArtifacts()) {
                    if (artifactName.equals(a.getName())) {
                        result[0] = a;
                        break;
                    }
                }
            });

            if (result[0] != null) {
                LOG.info("DevTomcat: Auto-created IntelliJ artifact: " + artifactName +
                        " [type=" + finalType.getId() + ", output=" + outputPath + "]");
            }
            return result[0];

        } catch (Exception e) {
            LOG.warn("DevTomcat: Failed to auto-create artifact for " +
                    deployment.getName() + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Finds the IntelliJ Module that corresponds to a deployment artifact.
     * Tries multiple strategies: exact name, name without .war, path-based,
     * single web module fallback, and partial name matching.
     */
    @Nullable
    private Module findModuleForDeployment(@NotNull Project project,
                                            @NotNull DeploymentArtifact deployment) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        String name = deployment.getName();

        // 1. Exact name match
        Module module = moduleManager.findModuleByName(name);
        if (module != null) return module;

        // 2. Strip .war extension and parenthetical suffixes
        String baseName = name.replaceAll("\\.war$", "").replaceAll("\\s*\\(.*\\)$", "").trim();
        module = moduleManager.findModuleByName(baseName);
        if (module != null) return module;

        // 3. Path-based: find web module whose content root contains the deployment path
        String deploymentPath = deployment.getPath();
        if (deploymentPath != null && !deploymentPath.isEmpty()) {
            for (Module m : moduleManager.getModules()) {
                for (VirtualFile contentRoot : ModuleRootManager.getInstance(m).getContentRoots()) {
                    if (deploymentPath.startsWith(contentRoot.getPath())) {
                        if (TomcatModuleUtils.isWebModule(m)) {
                            LOG.info("DevTomcat: Matched module '" + m.getName() +
                                    "' by path for deployment '" + name + "'");
                            return m;
                        }
                    }
                }
            }
        }

        // 4. Single web module fallback — if there's only one web module, use it
        List<Module> webModules = new ArrayList<>();
        for (Module m : moduleManager.getModules()) {
            if (TomcatModuleUtils.isWebModule(m)) {
                webModules.add(m);
            }
        }
        if (webModules.size() == 1) {
            LOG.info("DevTomcat: Using single web module '" + webModules.get(0).getName() +
                    "' for deployment '" + name + "'");
            return webModules.get(0);
        }

        // 5. Partial name matching against web modules
        for (Module m : webModules) {
            String mName = m.getName().toLowerCase();
            String lowerBase = baseName.toLowerCase();
            if (mName.contains(lowerBase) || lowerBase.contains(mName)) {
                LOG.info("DevTomcat: Matched module '" + m.getName() +
                        "' by partial name for deployment '" + name + "'");
                return m;
            }
        }

        return null;
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
    /**
     * Syncs our config data flags to the platform's built-in fields
     * (e.g. allowRunningInParallel, which is final and can't be overridden).
     */
    void syncPlatformFlags() {
        setAllowRunningInParallel(configData.isAllowMultipleInstances());
    }

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
        var logFileConfig = configData.getLogFileConfig();
        for (TomcatLogFile logFile : TomcatLogFile.getStandardLogFiles()) {
            boolean enabled = enabledLogs.contains(logFile.getId()) || logFile.isEnabledByDefault();
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
    public java.util.List<String> getLogFileConfigurations() {
        java.util.List<String> logFiles = configData.getLogFileConfig().getLogFiles();
        return logFiles != null ? logFiles : new java.util.ArrayList<>();
    }

    public void setStartupScript(String startupScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setStartupScript(startupScript);
    }

    public void setShutdownScript(String shutdownScript) {
        configData.getRunnerSettings(TomcatConstants.RUN_MODE).setShutdownScript(shutdownScript);
    }
}
