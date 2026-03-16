package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Restarts a running Tomcat instance from the Services tool window context menu.
 * Gracefully stops the current process and re-executes in the same mode (Run/Debug/Coverage).
 */
public class RestartTomcatAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RestartTomcatAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        if (project == null || config == null || !ServiceActionUtils.isRunning(handler)) return;

        RunnerAndConfigurationSettings settings = ServiceActionUtils.findSettings(project, config);
        if (settings == null) return;

        // Determine executor mode from process handler
        String executorId = handler instanceof TomcatProcessHandler tomcatHandler
                ? tomcatHandler.getExecutorId()
                : DefaultRunExecutor.EXECUTOR_ID;

        handler.addProcessListener(new ProcessListener() {
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
                        LOG.warn("Failed to restart Tomcat: " + config.getName(), ex);
                    }
                });
            }
        });

        handler.destroyProcess();
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
