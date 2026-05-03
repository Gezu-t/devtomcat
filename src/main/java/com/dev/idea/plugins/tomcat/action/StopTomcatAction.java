package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

/**
 * Stops a running Tomcat instance from the Services tool window context menu.
 * Delegates to the process handler's graceful shutdown (SHUTDOWN command on shutdown port).
 */
public class StopTomcatAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        if (handler != null && ServiceActionUtils.isRunning(handler)) {
            // Off-EDT: same EDT-contract reason as ProcessStopSupport.stopCleanAndThen —
            // Debug-mode destroyProcess fires a debugger listener that uses sync progress.
            ApplicationManager.getApplication().executeOnPooledThread(handler::destroyProcess);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        ProcessHandler handler = ServiceActionUtils.findProcessHandler(e);
        e.getPresentation().setEnabledAndVisible(config != null && ServiceActionUtils.isRunning(handler));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
