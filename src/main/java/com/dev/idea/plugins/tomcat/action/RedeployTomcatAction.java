package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Redeploys artifacts on a running Tomcat from the Services tool window.
 * Delegates to {@link TomcatApplicationUpdater} with the REDEPLOY action —
 * compiles, then hot-reloads the context (rewrites context.xml for exploded
 * dirs, re-copies WAR files) without stopping the server.
 */
public class RedeployTomcatAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        TomcatProcessHandler tomcatHandler = ServiceActionUtils.findTomcatProcessHandler(e);
        if (project == null || config == null || tomcatHandler == null
                || tomcatHandler.isProcessTerminated() || tomcatHandler.isProcessTerminating()) return;

        new TomcatApplicationUpdater(project, tomcatHandler, config, UpdateConfig.REDEPLOY)
                .executeUpdate();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        TomcatProcessHandler handler = ServiceActionUtils.findTomcatProcessHandler(e);
        boolean running = config != null
                && handler != null
                && !handler.isProcessTerminated()
                && !handler.isProcessTerminating()
                && handler.isServerStartupDetected();
        e.getPresentation().setEnabledAndVisible(running);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
