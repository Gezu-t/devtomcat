package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.ui.freshness.DeploymentFreshnessDialog;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the Deployment Freshness dialog from the Services tool window context
 * menu (config node or artifact child node). The report itself is computed off
 * the EDT by the dialog via {@link DeploymentFreshnessReport#buildAll}.
 */
public class ServiceDeploymentFreshnessAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        if (project == null || config == null) return;
        // A packed WAR's served state is the copy under the RUNNING instance's
        // webapps/, so pass that instance's runId when the server is up; a
        // stopped server yields null and those rows report unverified.
        TomcatProcessHandler handler = ServiceActionUtils.findTomcatProcessHandler(e);
        java.nio.file.Path webappsDir = TomcatProjectUtils.getWebappsDirectory(
                config, handler == null ? null : handler.getRunId());
        // The supplier runs on a pooled thread; deployment resolution takes its
        // own read action there, file walks stay outside it.
        new DeploymentFreshnessDialog(project, config.getName(),
                () -> DeploymentFreshnessReport.buildAll(project,
                        TomcatReadActions.compute(config::getDeployments), webappsDir)).show();
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
