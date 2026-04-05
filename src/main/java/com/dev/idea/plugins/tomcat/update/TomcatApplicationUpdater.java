package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.ProgramRunnerUtil;
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
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            TomcatUpdateDialog dialog = new TomcatUpdateDialog(project, configuration.getName(), action);
            if (!dialog.showAndGet()) return;
            executeUpdate(dialog.getSelectedAction());
        } else {
            executeUpdate(action);
        }
    }

    /**
     * Executes the configured update action.
     * Package-visible so {@link TomcatFrameDeactivationListener} can invoke it
     * without needing an {@link AnActionEvent}.
     */
    void executeUpdate() {
        executeUpdate(action);
    }

    void executeUpdate(@NotNull String selectedAction) {
        TomcatDeploymentLogger logger = processHandler.getDeploymentLogger();
        logger.logServerInfo("Update triggered: " + selectedAction);

        ApplicationManager.getApplication().invokeAndWait(
                () -> FileDocumentManager.getInstance().saveAllDocuments());

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
     * Updates only static resources (JSPs, HTML, CSS, XML, etc.) without compilation.
     * Copies resource files from source to the deployed artifact location.
     * For exploded artifacts Tomcat detects the changes automatically.
     * For WAR artifacts the WAR is re-copied to webapps.
     */
    private void doUpdateResourcesOnly(@NotNull TomcatDeploymentLogger logger) {
        logger.logServerInfo("Updating resources (no compilation)...");

        // Save all documents to disk so resource files are up-to-date
        // (already done in executeUpdate, but ensure it's complete)
        redeployWarArtifacts(logger);
        logger.logServerInfo("Resources updated — exploded artifacts reload automatically");
    }

    /**
     * Triggers incremental compilation then updates resources.
     * For exploded artifacts the updated classes are served directly from the output directory;
     * Tomcat picks up JSP and static resource changes on the next request.
     * For WAR artifacts the WAR file is re-copied to webapps after compilation succeeds.
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

            processHandler.addProcessListener(new ProcessListener() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            RunnerAndConfigurationSettings settings =
                                    RunManager.getInstance(project).findSettings(configuration);
                            if (settings != null) {
                                Executor executor = ExecutorRegistry.getInstance().getExecutorById(originalExecutorId);
                                if (executor == null) executor = DefaultRunExecutor.getRunExecutorInstance();
                                ProgramRunnerUtil.executeConfiguration(settings, executor);
                                logger.logServerInfo("Tomcat restarted (" + executor.getActionName() + " mode)");
                            } else {
                                logger.logServerError("Could not find run configuration settings for restart");
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to restart Tomcat", e);
                            logger.logServerError("Failed to restart: " + e.getMessage());
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
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                logger.logServerInfo("Re-deployed WAR: " + artifact.getDisplayName());
            } catch (IOException e) {
                LOG.warn("Failed to re-deploy WAR: " + artifact.getPath(), e);
                logger.logServerError("Failed to re-deploy WAR '" +
                        artifact.getDisplayName() + "': " + e.getMessage());
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
                    // Rewrite context.xml → Tomcat detects the change and redeploys
                    Path contextFile = contextXmlDir.resolve(contextName + ".xml");
                    String contextXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<Context docBase=\"" + escapeXmlAttribute(artifact.getPath())
                            + "\" reloadable=\"false\">"
                            + (preserveSessions ? "\n  <Manager pathname=\"SESSIONS.ser\" />" : "")
                            + "\n</Context>\n";
                    Files.writeString(contextFile, contextXml);
                    logger.logServerInfo("Redeployed (context rewrite): " + artifact.getDisplayName());
                } else {
                    Path source = Path.of(artifact.getPath());
                    Path target = webappsDir.resolve(contextName + ".war");
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
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
        if (contextPath == null || contextPath.isEmpty() || DEFAULT_CONTEXT_PATH.equals(contextPath)) {
            return ROOT_CONTEXT_NAME;
        }
        return contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
    }

    @NotNull
    private static String escapeXmlAttribute(@NotNull String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
