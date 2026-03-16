package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
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
 * Restarts a running Tomcat in Debug mode from the Services tool window.
 * If already in Debug mode, the action is hidden (use Restart instead).
 * If in Run/Coverage mode, stops the server and re-launches with the debugger.
 */
public class DebugTomcatAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(DebugTomcatAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        if (project == null || config == null || !ServiceActionUtils.isRunning(handler)) return;

        RunnerAndConfigurationSettings settings = ServiceActionUtils.findSettings(project, config);
        if (settings == null) return;

        LOG.info("Restarting Tomcat in Debug mode: " + config.getName());

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        Executor executor = ExecutorRegistry.getInstance()
                                .getExecutorById(DefaultDebugExecutor.EXECUTOR_ID);
                        if (executor == null) {
                            executor = DefaultDebugExecutor.getDebugExecutorInstance();
                        }
                        ProgramRunnerUtil.executeConfiguration(settings, executor);
                    } catch (Exception ex) {
                        LOG.warn("Failed to restart Tomcat in Debug mode: " + config.getName(), ex);
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

        boolean running = config != null && ServiceActionUtils.isRunning(handler);
        boolean alreadyDebug = handler instanceof TomcatProcessHandler tomcatHandler
                && DefaultDebugExecutor.EXECUTOR_ID.equals(tomcatHandler.getExecutorId());

        // Show only when running in non-debug mode
        e.getPresentation().setEnabledAndVisible(running && !alreadyDebug);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
