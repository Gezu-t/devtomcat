package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentAdapter;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.DeploymentStrategy;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatDeploymentPaths;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.update.RunningApplicationUpdater;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.dev.idea.plugins.tomcat.utils.CompilerSupport;
import com.dev.idea.plugins.tomcat.utils.ProcessStopSupport;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;

import java.util.List;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Executes "Update Running Application" actions for DevTomcat.
 *
 * <p>Handles the four update actions configured via {@link UpdateConfig}:
 * <ul>
 *   <li>{@code UPDATE_RESOURCES} / {@code UPDATE_CLASSES_AND_RESOURCES} — compile + auto-reload</li>
 *   <li>{@code REDEPLOY} — compile + force context reload</li>
 *   <li>{@code RESTART_SERVER} — stop process + re-execute configuration</li>
 * </ul>
 *
 * <p>Triggered by Ctrl+F10 (via {@link TomcatRunningApplicationUpdaterProvider})
 * or on frame deactivation (via {@link TomcatFrameDeactivationListener}).
 */
public class TomcatApplicationUpdater implements RunningApplicationUpdater {

    private static final Logger LOG = Logger.getInstance(TomcatApplicationUpdater.class);

    private final Project project;
    private final TomcatProcessHandler processHandler;
    private final TomcatRunConfiguration configuration;
    private final String action;

    public TomcatApplicationUpdater(@NotNull Project project,
                                     @NotNull TomcatProcessHandler processHandler,
                                     @NotNull TomcatRunConfiguration configuration,
                                     @NotNull String action) {
        this.project = project;
        this.processHandler = processHandler;
        this.configuration = configuration;
        this.action = action;
    }

    @Override
    public String getDescription() {
        return switch (action) {
            case UpdateConfig.UPDATE_RESOURCES -> "Update resources";
            case UpdateConfig.UPDATE_CLASSES_AND_RESOURCES -> "Update classes and resources";
            case UpdateConfig.REDEPLOY -> "Redeploy";
            case UpdateConfig.RESTART_SERVER -> "Restart server";
            default -> "Update application";
        };
    }

    @Override
    public String getShortName() {
        return "DevTomcat";
    }

    @Nullable
    @Override
    public Icon getIcon() {
        return switch (action) {
            case UpdateConfig.RESTART_SERVER -> AllIcons.Actions.Restart;
            case UpdateConfig.REDEPLOY       -> AllIcons.Actions.Rerun;
            default                          -> AllIcons.Actions.Compile;
        };
    }

    @Override
    public void performUpdate(@NotNull AnActionEvent event) {
        UpdateConfig updateConfig = configuration.getConfigData().getUpdateConfig();
        if (updateConfig.isShowUpdateDialog()) {
            boolean isLocal = !configuration.isRemoteMode();
            TomcatUpdateDialog dialog = new TomcatUpdateDialog(project, configuration.getName(), action, isLocal);
            if (!dialog.showAndGet()) return;
            executeUpdate(dialog.getSelectedAction());
        } else {
            executeUpdate(action);
        }
    }

    /**
     * Executes the configured update action using the action passed at construction.
     */
    public void executeUpdate() {
        executeUpdate(action);
    }

    public void executeUpdate(@NotNull String selectedAction) {
        TomcatDeploymentLogger logger = processHandler.getDeploymentLogger();
        logger.logServerInfo("Update triggered: " + selectedAction);

        // Save documents — must run on EDT. invokeAndWait deadlocks if already on EDT
        // (e.g. called from TomcatRunner.doExecute), so dispatch appropriately.
        Runnable saveAll = () -> FileDocumentManager.getInstance().saveAllDocuments();
        if (ApplicationManager.getApplication().isDispatchThread()) {
            saveAll.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(saveAll);
        }

        switch (selectedAction) {
            case UpdateConfig.UPDATE_RESOURCES ->
                    doUpdateResourcesOnly(logger);
            case UpdateConfig.UPDATE_CLASSES_AND_RESOURCES ->
                    doUpdateClassesAndResources(logger);
            case UpdateConfig.REDEPLOY ->
                    doRedeploy(logger);
            case UpdateConfig.RESTART_SERVER ->
                    doRestart(logger);
            default ->
                    logger.logServerWarning("Unknown update action: " + selectedAction);
        }
    }

    /**
     * Syncs static resources (JSPs, HTML, CSS, XML, etc.) to the deployed artifact.
     *
     * <p>Triggers an incremental build via {@link CompilerManager#make} so IntelliJ
     * copies changed resource files from the source tree to the artifact output directory.
     * If only resource files changed (no Java), the compiler finds nothing to recompile
     * and the build completes in milliseconds.
     *
     * <p>For exploded artifacts Tomcat serves files directly from {@code docBase}, so
     * once the resource is in the artifact output directory it is live on the next request.
     * WAR artifacts are not re-copied since the incremental build does not repackage them;
     * use "Redeploy" for WAR-based deployments.
     */
    private void doUpdateResourcesOnly(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(deployments, logger);
        CompilerSupport.compileAndThen(project, logger,
                "Syncing resources...",
                "Build aborted; resource sync cancelled",
                "Build failed",
                warnings -> {
                    logger.logServerInfo("Resources synced" + warningSuffix(warnings));
                    // Mirror module output (which includes resource roots — Maven
                    // puts src/main/resources/* into target/classes/, Gradle puts
                    // them into build/resources/main/) into each exploded
                    // deployment's WEB-INF/classes/. Without this hook the Make
                    // task above produces fresh resource files in target/classes/
                    // but Tomcat keeps serving the previous mvn-package'd copy
                    // from target/<war>/WEB-INF/classes/ — exactly the
                    // ".properties files sometimes stale" symptom.
                    DeployedClassesSync.syncDeployments(project, deployments, logger);
                    // Mirror webapp source files (JSP, JS, CSS, HTML, images,
                    // taglibs) into the exploded artifact root. IntelliJ's Make
                    // task doesn't copy src/main/webapp/ — only Maven's
                    // prepare-package does, which Make never triggers — so
                    // without this step JSP edits silently never reach Tomcat.
                    WebResourcesSync.syncDeployments(project, deployments, logger);
                });
    }

    /**
     * Triggers incremental compilation then applies changes to the running server.
     *
     * <p>For exploded artifacts, resource changes (JSP, HTML, CSS) are served directly
     * from {@code docBase} on the next request. Java class changes require a classloader
     * reload — achieved by touching the context XML descriptor, which Tomcat's deployer
     * watches and triggers an undeploy/redeploy cycle with a fresh classloader.
     *
     * <p>For WAR artifacts the WAR file is re-copied to webapps after compilation.
     */
    private void doUpdateClassesAndResources(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(deployments, logger);
        CompilerSupport.compileAndThen(project, logger,
                "Compiling project...",
                "Compilation aborted",
                "Compilation failed",
                warnings -> {
                    logger.logServerInfo("Compilation successful" + warningSuffix(warnings));
                    // Mirror fresh class output into each exploded deployment's
                    // WEB-INF/classes/ BEFORE touching context.xml — the deployer's
                    // reload trigger should see the new bytes already in place.
                    // See DeployedClassesSync javadoc for the Maven target/ rationale.
                    DeployedClassesSync.syncDeployments(project, deployments, logger);
                    // See doUpdateResourcesOnly above for why this is needed
                    // alongside the class sync — JSP/JS/CSS edits otherwise
                    // would not reach the exploded artifact.
                    WebResourcesSync.syncDeployments(project, deployments, logger);
                    redeployWarArtifacts(logger);
                    touchExplodedContextXml(logger);
                });
    }

    /**
     * Triggers compilation then forces redeployment of all artifacts:
     * rewrites context.xml for exploded dirs, re-copies WAR files.
     */
    private void doRedeploy(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(deployments, logger);
        CompilerSupport.compileAndThen(project, logger,
                "Compiling and redeploying...",
                "Compilation aborted",
                "Compilation failed",
                warnings -> {
                    logger.logServerInfo("Compilation successful" + warningSuffix(warnings) + ", redeploying artifacts...");
                    // Mirror fresh classes into each exploded deployment so the
                    // forced redeploy (context.xml rewrite below) lands a fresh
                    // classloader on top of fresh bytes, not the previous build's.
                    DeployedClassesSync.syncDeployments(project, deployments, logger);
                    WebResourcesSync.syncDeployments(project, deployments, logger);
                    redeployAllArtifacts(logger);
                });
    }

    /**
     * Compiles the project, then stops and re-executes the run configuration so
     * the restarted Tomcat picks up the latest class files.
     */
    private void doRestart(@NotNull TomcatDeploymentLogger logger) {
        List<Deployment> deployments = configuration.getDeployments();
        warnAboutWarDeploymentsIfPresent(deployments, logger);
        String originalExecutorId = processHandler.getExecutorId();

        CompilerSupport.compileAndThen(project, logger,
                "Compiling before restart...",
                "Compilation aborted; restart cancelled",
                "Compilation failed; restart cancelled",
                warnings -> {
            logger.logServerInfo("Compilation successful" + warningSuffix(warnings) + ", restarting Tomcat...");

            // Mirror fresh classes into each exploded deployment BEFORE we stop
            // the current process. The relaunch's Before Launch tasks re-run Make
            // but never repackage a Maven target/<warname>/ exploded layout, so
            // without this step the restarted Tomcat would serve the same stale
            // bytes as before the restart — exactly the "I have to mvn clean
            // install every time" pain. See DeployedClassesSync javadoc.
            DeployedClassesSync.syncDeployments(project, deployments, logger);
            WebResourcesSync.syncDeployments(project, deployments, logger);

            // Capture before destroy — see ProcessStopSupport javadoc for race rationale
            Executor resolvedExecutor = ExecutorRegistry.getInstance().getExecutorById(originalExecutorId);
            if (resolvedExecutor == null) {
                LOG.warn("Executor '" + originalExecutorId + "' not found — falling back to Run mode");
                logger.logServerWarning("Could not restore executor '" + originalExecutorId + "'. Restarting in Run mode.");
                resolvedExecutor = DefaultRunExecutor.getRunExecutorInstance();
            }
            RunContentDescriptor descriptor = ProcessStopSupport.findDescriptor(project, processHandler);
            final Executor capturedExecutor = resolvedExecutor;

            // Carry the running process's resolved ports (and JDWP port, if debugging) to
            // the relaunch. Without this the new launch re-runs port conflict detection,
            // sees the just-released socket in OS TIME_WAIT state, and bumps the port
            // upward — the same "port keeps increasing" symptom the rerun path already
            // guards against in TomcatRunnerDelegate.stopAndRelaunch.
            com.dev.idea.plugins.tomcat.model.PortConfig carriedPorts = processHandler.getResolvedPorts();
            int carriedDebugPort = processHandler.getResolvedDebugPort();
            // Prefer the handler's original launch settings, the same stable identity the
            // runner delegate uses for descriptor lookup. findSettings(configuration) is a
            // fallback for handlers launched outside the normal editor flow.
            RunnerAndConfigurationSettings preferredSettings = processHandler.getLaunchSettings();

            ProcessStopSupport.stopCleanAndThen(project, processHandler, descriptor, capturedExecutor, () -> {
                try {
                    RunnerAndConfigurationSettings settings = preferredSettings != null
                            ? preferredSettings
                            : RunManager.getInstance(project).findSettings(configuration);
                    if (settings != null) {
                        com.intellij.execution.runners.ExecutionEnvironment newEnv =
                                com.intellij.execution.runners.ExecutionEnvironmentBuilder
                                        .create(capturedExecutor, settings).build();
                        if (carriedPorts != null) {
                            newEnv.putUserData(
                                    com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState.CARRIED_PORTS_KEY,
                                    carriedPorts);
                        }
                        if (carriedDebugPort > 0) {
                            newEnv.putUserData(
                                    com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState.CARRIED_DEBUG_PORT_KEY,
                                    carriedDebugPort);
                        }
                        newEnv.getRunner().execute(newEnv);
                        LOG.info("Tomcat restarted in " + capturedExecutor.getActionName() + " mode"
                                + (carriedPorts != null ? " (reusing ports)" : ""));
                    } else {
                        logger.logServerError("Could not find run configuration settings for restart");
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to restart Tomcat: " + configuration.getName(), e);
                    com.dev.idea.plugins.tomcat.utils.ProcessStopSupport
                            .purgeTerminatedDescriptors(project);
                    logger.logServerError("Failed to restart: " + e.getMessage());
                    notifyRestartFailed(project, configuration.getName(), e.getMessage());
                }
            });
        }); // CompilerSupport.compileAndThen
    }

    /**
     * Re-copies WAR artifacts to the webapps directory after compilation.
     * Exploded artifacts are skipped — Tomcat handles their reload automatically.
     */
    private void redeployWarArtifacts(@NotNull TomcatDeploymentLogger logger) {
        Path webappsDir = TomcatProjectUtils.getWebappsDirectory(configuration, processHandler.getRunId());
        if (webappsDir == null) {
            LOG.warn("No webapps directory resolved; WAR artifacts will not be updated");
            logger.logServerWarning("Cannot locate webapps directory; WAR update skipped");
            return;
        }

        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || deployment.isExploded()) continue;
            Path source = deployment.getResolvedPath();
            if (source == null) continue;

            try {
                String contextName = resolveContextName(deployment.getContextPath());
                Path target = TomcatDeploymentPaths.warFile(webappsDir, contextName);
                TomcatProjectUtils.atomicCopy(source, target);
                logger.logServerInfo("Re-deployed WAR: " + deployment.getDisplayName());
            } catch (IOException e) {
                LOG.warn("Failed to re-deploy WAR: " + source, e);
                logger.logServerError("Failed to re-deploy WAR '" +
                        deployment.getDisplayName() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Touches context XML descriptors for exploded artifacts, triggering Tomcat's
     * deployer to undeploy and redeploy with a fresh classloader. This makes
     * compiled Java class changes visible without a full server restart.
     *
     * <p>WAR artifacts are skipped — their reload is handled by {@link #redeployWarArtifacts}.
     */
    private void touchExplodedContextXml(@NotNull TomcatDeploymentLogger logger) {
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration, processHandler.getRunId());
        if (catalinaBase == null) return;

        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);

        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid() || !deployment.isExploded()) continue;

            String contextName = resolveContextName(deployment.getContextPath());
            Path contextFile = TomcatDeploymentPaths.contextDescriptor(contextXmlDir, contextName);
            if (Files.exists(contextFile)) {
                try {
                    Files.setLastModifiedTime(contextFile,
                            FileTime.fromMillis(System.currentTimeMillis()));
                    logger.logServerInfo("Context reload triggered: " + deployment.getDisplayName());
                } catch (IOException e) {
                    LOG.warn("Failed to touch context XML: " + contextFile, e);
                    logger.logServerWarning("Could not trigger context reload for " +
                            deployment.getDisplayName());
                }
            }
        }
    }

    /**
     * Forces redeployment of all artifacts.
     * <ul>
     *   <li>Exploded: rewrites context.xml to force Tomcat undeploy + redeploy</li>
     *   <li>WAR: re-copies the WAR file to webapps</li>
     * </ul>
     */
    private void redeployAllArtifacts(@NotNull TomcatDeploymentLogger logger) {
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration, processHandler.getRunId());
        if (catalinaBase == null) {
            logger.logServerError("Could not determine CATALINA_BASE directory");
            return;
        }

        Path webappsDir = catalinaBase.resolve(DIR_WEBAPPS);
        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);
        boolean preserveSessions = configuration.getConfigData()
                .getDeploymentConfig().isPreserveSessions();

        for (Deployment deployment : configuration.getDeployments()) {
            if (!deployment.isValid()) continue;
            Path artifactPath = deployment.getResolvedPath();
            if (artifactPath == null) continue;

            String contextName = resolveContextName(deployment.getContextPath());

            try {
                if (deployment.isExploded()) {
                    // Generate full context XML with PreResources/PostResources,
                    // matching initial deployment so multi-module classpath is preserved.
                    // Pass the configured TomcatInfo so the generator can omit the
                    // <Resources> block on Tomcat 7 (PreResources is a Tomcat 8 feature).
                    Path contextFile = TomcatDeploymentPaths.contextDescriptor(contextXmlDir, contextName);
                    // DeploymentStrategy.buildContextXml still consumes a legacy
                    // artifact; adapt at the call boundary (Phase 4d migrates it).
                    DeploymentArtifact legacy = DeploymentAdapter.toLegacy(deployment);
                    String contextXml = DeploymentStrategy.buildContextXml(
                            legacy, artifactPath, preserveSessions, project,
                            configuration.getTomcatInfo(), logger);
                    TomcatProjectUtils.atomicWriteString(contextFile, contextXml);
                    logger.logServerInfo("Redeployed (context rewrite): " + deployment.getDisplayName());
                } else {
                    Path target = TomcatDeploymentPaths.warFile(webappsDir, contextName);
                    TomcatProjectUtils.atomicCopy(artifactPath, target);
                    logger.logServerInfo("Redeployed WAR: " + deployment.getDisplayName());
                }
            } catch (IOException e) {
                LOG.warn("Failed to redeploy: " + artifactPath, e);
                logger.logServerError("Failed to redeploy '" +
                        deployment.getDisplayName() + "': " + e.getMessage());
            }
        }
    }

    @NotNull
    private static String resolveContextName(@Nullable String contextPath) {
        return ContextPathUtils.resolveContextNameSafe(contextPath, LOG);
    }

    /**
     * Shows the Update dialog and executes the selected action.
     * Shared entry point for Services panel actions and re-run interception in runners.
     */
    public static void showDialogAndExecute(@NotNull Project project,
                                             @NotNull TomcatProcessHandler handler,
                                             @NotNull TomcatRunConfiguration config) {
        String defaultAction = config.getConfigData().getUpdateConfig().getOnUpdate();
        boolean isLocal = !config.isRemoteMode();
        TomcatUpdateDialog dialog = new TomcatUpdateDialog(project, config.getName(), defaultAction, isLocal);
        if (!dialog.showAndGet()) return;
        new TomcatApplicationUpdater(project, handler, config, dialog.getSelectedAction()).executeUpdate();
    }

    /**
     * Shows a balloon notification when a restart fails after the old process has already
     * been stopped. The console log entry may be scrolled past — a balloon ensures the user
     * sees that Tomcat is no longer running and must be started manually.
     */
    private static void notifyRestartFailed(@NotNull Project project,
                                            @NotNull String configName,
                                            @Nullable String errorMessage) {
        // Short balloon — old text repeated the config name verbatim and ended
        // with a wordy "start the configuration manually to resume" sentence.
        // The user already knows which config they were on (it's in the run
        // toolbar), so the balloon just needs to flag the stop-without-restart
        // state. Console has the full stack.
        String content = errorMessage != null
                ? "Stopped without restart: " + errorMessage
                : "Stopped without restart. Start manually.";
        TomcatNotifier.error(project, "Restart failed", content);
    }

    /** Returns {@code " (N warning(s))"} when warnings &gt; 0, empty string otherwise. */
    @NotNull
    private static String warningSuffix(int warnings) {
        return warnings > 0 ? " (" + warnings + " warning(s))" : "";
    }

    /**
     * Logs a single aggregated warning when one or more deployment artifacts in
     * the config are WAR-packaged (not exploded). Hot class/web sync skips WAR
     * artifacts because their content lives inside a ZIP that the plugin cannot
     * safely mutate — so without this warning, users editing JSP/Java would see
     * "skipped: type is war" lines scroll past per-artifact and wonder why
     * their change didn't take effect.
     *
     * <p>One line per launch / per update action, fires only when at least
     * one WAR artifact is present, names every offender, and offers the two
     * concrete ways forward (build-tool repackage, or switch the artifact to
     * exploded in the Deployment tab). Public + static so the launch path
     * ({@code TomcatJavaParametersBuilder}) can call it too.
     */
    /**
     * @deprecated Use {@link #warnAboutWarDeploymentsIfPresent}; this overload
     * adapts via {@link DeploymentAdapter#toTyped} for the few callers still
     * holding legacy lists.
     */
    @Deprecated(forRemoval = true)
    public static void warnAboutWarArtifactsIfPresent(@NotNull List<DeploymentArtifact> artifacts,
                                                      @NotNull TomcatDeploymentLogger logger) {
        // toTyped needs a Project for ARTIFACT/MODULE sources; for the WAR-name
        // scan we only need legacy fields, so do the filtering here without
        // going through the adapter.
        java.util.List<String> warNames = new java.util.ArrayList<>();
        for (DeploymentArtifact a : artifacts) {
            if (a != null && DeploymentArtifact.TYPE_WAR.equals(a.getType())) {
                warNames.add(a.getDisplayName());
            }
        }
        emitWarArtifactsWarning(warNames, logger);
    }

    /**
     * Logs one aggregated warning when any deployment in the list is WAR-packaged.
     * Public + static so the launch path ({@code TomcatJavaParametersBuilder})
     * can call it.
     */
    public static void warnAboutWarDeploymentsIfPresent(@NotNull List<Deployment> deployments,
                                                        @NotNull TomcatDeploymentLogger logger) {
        java.util.List<String> warNames = new java.util.ArrayList<>();
        for (Deployment d : deployments) {
            if (!d.isExploded()) warNames.add(d.getDisplayName());
        }
        emitWarArtifactsWarning(warNames, logger);
    }

    private static void emitWarArtifactsWarning(@NotNull List<String> warNames,
                                                @NotNull TomcatDeploymentLogger logger) {
        if (warNames.isEmpty()) return;
        String plural = warNames.size() == 1 ? "artifact is" : "artifacts are";
        logger.logServerWarning(
                warNames.size() + " WAR " + plural + " in this run config: "
                        + String.join(", ", warNames)
                        + ". Hot class/resource sync (Ctrl+F10) only applies to exploded deployments,"
                        + " so changes to these won't take effect until you rebuild the WAR with"
                        + " 'mvn package' / 'gradle war' — or change the artifact type to 'exploded'"
                        + " in the Deployment tab (the exploded directory lives at"
                        + " target/<finalName>/ for Maven, build/libs/exploded/ for Gradle).");
    }

    /**
     * Maps an {@link UpdateConfig} action constant to a user-visible display string.
     */
    @NotNull
    public static String mapActionToDisplay(@NotNull String action) {
        return switch (action) {
            case UpdateConfig.UPDATE_RESOURCES -> "Update resources";
            case UpdateConfig.UPDATE_CLASSES_AND_RESOURCES -> "Update classes and resources";
            case UpdateConfig.REDEPLOY -> "Redeploy";
            case UpdateConfig.RESTART_SERVER -> "Restart server";
            default -> action;
        };
    }
}
