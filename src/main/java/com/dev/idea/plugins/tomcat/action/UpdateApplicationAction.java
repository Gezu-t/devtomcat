package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.NotNull;

/**
 * "Update Application" action for the Services tool window.
 *
 * <p>Shows the same Update dialog as the Run toolbar re-run intercept and Ctrl+F10,
 * letting the user choose between Update Resources, Update Classes and Resources,
 * Redeploy, or Restart Server — matching IntelliJ Ultimate's Services panel behavior.
 *
 * <p>Only enabled when a Tomcat process is running and server startup has been detected
 * (i.e. Tomcat is fully up, not still initialising).
 */
public class UpdateApplicationAction extends AnAction {

    public UpdateApplicationAction() {
        // BuildAutoReloadChanges literally means "build + reload changes",
        // which is exactly what this action does: compile changed classes
        // and resources and push them to the running Tomcat without a restart.
        // ForceRefresh worked but conveyed a heavier "reload everything"
        // connotation than the action actually performs.
        super("Update Application", "Update the running Tomcat application",
                AllIcons.Actions.BuildAutoReloadChanges);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        TomcatProcessHandler handler = ServiceActionUtils.findTomcatProcessHandler(e);

        if (project == null || config == null || handler == null) return;
        if (handler.getRestartBlockReason() != null) return;

        TomcatApplicationUpdater.showDialogAndExecute(project, handler, config);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatRunConfiguration config = ServiceActionUtils.findTomcatConfiguration(e);
        TomcatProcessHandler handler = ServiceActionUtils.findTomcatProcessHandler(e);
        ServiceActionUtils.applyStartupGate(e.getPresentation(), config, handler, "Update the running Tomcat application");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
