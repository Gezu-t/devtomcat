package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.history.StartupTimeTrendDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the Startup Time Trends dialog from the Services tool window context menu.
 * Only visible when a DevTomcat configuration is selected.
 */
public class ServiceStartupTrendsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        if (project == null || config == null) return;
        new StartupTimeTrendDialog(project, config.getName()).show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        e.getPresentation().setEnabledAndVisible(config != null && e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
