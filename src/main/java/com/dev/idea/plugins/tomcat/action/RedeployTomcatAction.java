package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Redeploys artifacts on a running Tomcat from the Services tool window.
 * Saves documents, triggers a project build, then restarts the server
 * to pick up fresh artifacts — matching the "Redeploy" action from Ctrl+F10.
 */
public class RedeployTomcatAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RedeployTomcatAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        if (project == null || config == null || !ServiceActionUtils.isRunning(handler)) return;

        RunnerAndConfigurationSettings settings = ServiceActionUtils.findSettings(project, config);
        if (settings == null) return;

        String executorId = handler instanceof TomcatProcessHandler tomcatHandler
                ? tomcatHandler.getExecutorId()
                : DefaultRunExecutor.EXECUTOR_ID;

        // Save all documents, then compile, then restart
        FileDocumentManager.getInstance().saveAllDocuments();

        CompilerManager.getInstance(project).make((aborted, errors, warnings, ctx) -> {
            if (aborted || errors > 0) {
                LOG.info("Redeploy cancelled: build " + (aborted ? "aborted" : "had " + errors + " errors"));
                return;
            }
            // Build succeeded — restart to pick up new artifacts
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            Executor executor = ExecutorRegistry.getInstance().getExecutorById(executorId);
                            if (executor == null) {
                                executor = DefaultRunExecutor.getRunExecutorInstance();
                            }
                            ProgramRunnerUtil.executeConfiguration(settings, executor);
                        } catch (Exception ex) {
                            LOG.warn("Redeploy restart failed: " + config.getName(), ex);
                        }
                    });
                }
            });
            handler.destroyProcess();
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        e.getPresentation().setEnabledAndVisible(config != null && ServiceActionUtils.isRunning(handler));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
