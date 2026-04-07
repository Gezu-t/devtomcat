package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.DeploymentStrategy;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.update.RunningApplicationUpdater;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
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
            boolean isLocal = !com.dev.idea.plugins.tomcat.TomcatConstants.MODE_REMOTE
                    .equals(configuration.getConfigData().getServerMode());
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
        logger.logServerInfo("Syncing resources...");
        CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning("Build aborted — resource sync cancelled");
                return;
            }
            if (errors > 0) {
                logger.logServerError("Build failed with " + errors + " error(s) — resource sync cancelled");
                return;
            }
            logger.logServerInfo("Resources synced" +
                    (warnings > 0 ? " (" + warnings + " warning(s))" : ""));
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
        logger.logServerInfo("Compiling project...");
        CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning("Compilation aborted");
                return;
            }
            if (errors > 0) {
                logger.logServerError("Compilation failed with " + errors + " error(s)");
                return;
            }
            logger.logServerInfo("Compilation successful" +
                    (warnings > 0 ? " (" + warnings + " warning(s))" : ""));

            redeployWarArtifacts(logger);
            touchExplodedContextXml(logger);
        });
    }

    /**
     * Triggers compilation then forces redeployment of all artifacts:
     * rewrites context.xml for exploded dirs, re-copies WAR files.
     */
    private void doRedeploy(@NotNull TomcatDeploymentLogger logger) {
        logger.logServerInfo("Compiling and redeploying...");
        CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning("Compilation aborted");
                return;
            }
            if (errors > 0) {
                logger.logServerError("Compilation failed with " + errors + " error(s)");
                return;
            }
            logger.logServerInfo("Compilation successful, redeploying artifacts...");
            redeployAllArtifacts(logger);
        });
    }

    /**
     * Compiles the project, then stops and re-executes the run configuration so
     * the restarted Tomcat picks up the latest class files.
     */
    private void doRestart(@NotNull TomcatDeploymentLogger logger) {
        logger.logServerInfo("Compiling before restart...");
        String originalExecutorId = processHandler.getExecutorId();

        CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                logger.logServerWarning("Compilation aborted — restart cancelled");
                return;
            }
            if (errors > 0) {
                logger.logServerError("Compilation failed with " + errors + " error(s) — restart cancelled");
                return;
            }
            logger.logServerInfo("Compilation successful, restarting Tomcat...");

            // Capture the executor and descriptor NOW — before destroyProcess() — so the
            // callback holds a stable reference. Querying ExecutionManager inside
            // processTerminated/invokeLater is a race: the entry may already be deregistered
            // from ExecutionManager's runningConfigurations by the time the callback runs.
            Executor resolvedExecutor = ExecutorRegistry.getInstance().getExecutorById(originalExecutorId);
            if (resolvedExecutor == null) {
                LOG.warn("Executor '" + originalExecutorId + "' not found in registry — falling back to Run mode");
                logger.logServerWarning("Could not restore executor '" + originalExecutorId + "' — restarting in Run mode");
                resolvedExecutor = DefaultRunExecutor.getRunExecutorInstance();
            }
            com.intellij.execution.ui.RunContentDescriptor capturedDescriptor = null;
            for (com.intellij.execution.ui.RunContentDescriptor d :
                    com.intellij.execution.ui.RunContentManager.getInstance(project)
                            .getAllDescriptors()) {
                if (d.getProcessHandler() == processHandler) {
                    capturedDescriptor = d;
                    break;
                }
            }
            final Executor capturedExecutor = resolvedExecutor;
            final com.intellij.execution.ui.RunContentDescriptor descriptorToRemove = capturedDescriptor;

            processHandler.addProcessListener(new ProcessListener() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            if (project.isDisposed()) return;

                            // Remove the old entry using the pre-captured descriptor reference.
                            if (descriptorToRemove != null) {
                                com.intellij.execution.ui.RunContentManager
                                        .getInstance(project)
                                        .removeRunContent(capturedExecutor, descriptorToRemove);
                                com.intellij.execution.dashboard.RunDashboardManager
                                        .getInstance(project).updateDashboard(true);
                            }

                            RunnerAndConfigurationSettings settings =
                                    RunManager.getInstance(project).findSettings(configuration);
                            if (settings != null) {
                                com.intellij.execution.runners.ExecutionEnvironmentBuilder
                                        .create(capturedExecutor, settings).buildAndExecute();
                                LOG.info("Tomcat restarted in " + capturedExecutor.getActionName() + " mode");
                            } else {
                                logger.logServerError("Could not find run configuration settings for restart");
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to restart Tomcat: " + configuration.getName(), e);
                            logger.logServerError("Failed to restart: " + e.getMessage());
                            // Tomcat is no longer running — notify prominently so the user
                            // knows they must start the configuration manually.
                            notifyRestartFailed(project, configuration.getName(), e.getMessage());
                        }
                    });
                }
            });

            processHandler.destroyProcess();
        });
    }

    /**
     * Re-copies WAR artifacts to the webapps directory after compilation.
     * Exploded artifacts are skipped — Tomcat handles their reload automatically.
     */
    private void redeployWarArtifacts(@NotNull TomcatDeploymentLogger logger) {
        Path webappsDir = TomcatProjectUtils.getWebappsDirectory(configuration);
        if (webappsDir == null) {
            LOG.warn("No webapps directory resolved; WAR artifacts will not be updated");
            logger.logServerWarning("Cannot locate webapps directory — WAR update skipped");
            return;
        }

        List<DeploymentArtifact> artifacts = configuration.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;
            if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) continue;

            try {
                String contextName = resolveContextName(artifact.getContextPath());
                Path source = Path.of(artifact.getPath());
                Path target = webappsDir.resolve(contextName + ".war");
                TomcatProjectUtils.atomicCopy(source, target);
                logger.logServerInfo("Re-deployed WAR: " + artifact.getDisplayName());
            } catch (IOException e) {
                LOG.warn("Failed to re-deploy WAR: " + artifact.getPath(), e);
                logger.logServerError("Failed to re-deploy WAR '" +
                        artifact.getDisplayName() + "': " + e.getMessage());
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
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration);
        if (catalinaBase == null) return;

        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);
        List<DeploymentArtifact> artifacts = configuration.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;
            if (!DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) continue;

            String contextName = resolveContextName(artifact.getContextPath());
            Path contextFile = contextXmlDir.resolve(contextName + ".xml");
            if (Files.exists(contextFile)) {
                try {
                    Files.setLastModifiedTime(contextFile,
                            FileTime.fromMillis(System.currentTimeMillis()));
                    logger.logServerInfo("Context reload triggered: " + artifact.getDisplayName());
                } catch (IOException e) {
                    LOG.warn("Failed to touch context XML: " + contextFile, e);
                    logger.logServerWarning("Could not trigger context reload for " +
                            artifact.getDisplayName());
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
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration);
        if (catalinaBase == null) {
            logger.logServerError("Could not determine CATALINA_BASE directory");
            return;
        }

        Path webappsDir = catalinaBase.resolve(DIR_WEBAPPS);
        Path contextXmlDir = catalinaBase.resolve(CONTEXT_XML_DIR);
        boolean preserveSessions = configuration.getConfigData()
                .getDeploymentConfig().isPreserveSessions();

        List<DeploymentArtifact> artifacts = configuration.getConfigData()
                .getDeploymentConfig().getDeployedArtifacts();

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;

            String contextName = resolveContextName(artifact.getContextPath());

            try {
                if (DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())) {
                    // Generate full context XML with PreResources/PostResources,
                    // matching initial deployment so multi-module classpath is preserved
                    Path artifactPath = Path.of(artifact.getPath());
                    Path contextFile = contextXmlDir.resolve(contextName + ".xml");
                    String contextXml = DeploymentStrategy.buildContextXml(
                            artifact, artifactPath, preserveSessions, project, logger);
                    Files.writeString(contextFile, contextXml);
                    logger.logServerInfo("Redeployed (context rewrite): " + artifact.getDisplayName());
                } else {
                    Path source = Path.of(artifact.getPath());
                    Path target = webappsDir.resolve(contextName + ".war");
                    TomcatProjectUtils.atomicCopy(source, target);
                    logger.logServerInfo("Redeployed WAR: " + artifact.getDisplayName());
                }
            } catch (IOException e) {
                LOG.warn("Failed to redeploy: " + artifact.getPath(), e);
                logger.logServerError("Failed to redeploy '" +
                        artifact.getDisplayName() + "': " + e.getMessage());
            }
        }
    }

    @NotNull
    private static String resolveContextName(@Nullable String contextPath) {
        try {
            return ContextPathUtils.resolveContextName(contextPath);
        } catch (IllegalArgumentException e) {
            // In the updater context, log and fall back — the deployment strategy
            // already validated at launch time.
            LOG.warn("Invalid context path during update: " + e.getMessage());
            return ROOT_CONTEXT_NAME;
        }
    }

    /**
     * Shows the Update dialog and executes the selected action.
     * Shared entry point for Services panel actions and re-run interception in runners.
     */
    public static void showDialogAndExecute(@NotNull Project project,
                                             @NotNull TomcatProcessHandler handler,
                                             @NotNull TomcatRunConfiguration config) {
        String defaultAction = config.getConfigData().getUpdateConfig().getOnUpdate();
        boolean isLocal = !MODE_REMOTE.equals(config.getConfigData().getServerMode());
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
        try {
            String content = "Tomcat '" + configName + "' stopped but could not restart" +
                    (errorMessage != null ? ": " + errorMessage : ".") +
                    " Start the configuration manually to resume.";
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("Restart Failed", content, NotificationType.ERROR)
                    .notify(project);
        } catch (Exception e) {
            LOG.debug("Could not show restart-failure notification: " + e.getMessage());
        }
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
