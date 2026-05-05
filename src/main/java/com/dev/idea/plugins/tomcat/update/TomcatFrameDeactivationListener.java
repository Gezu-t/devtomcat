package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for IDE frame deactivation (user switches away from IntelliJ)
 * and triggers the configured "On frame deactivation" update action
 * for any running Tomcat instances.
 *
 * <p>Registered declaratively in plugin.xml so the class is only loaded
 * when the first deactivation event fires.
 */
public class TomcatFrameDeactivationListener implements ApplicationActivationListener {

    private static final Logger LOG = Logger.getInstance(TomcatFrameDeactivationListener.class);

    /** Guards against re-entrant dialog display: showing the confirmation dialog
     *  activates the frame, and dismissing it may fire another deactivation event. */
    private final AtomicBoolean updating = new AtomicBoolean(false);

    @Override
    public void applicationDeactivated(@NotNull IdeFrame ideFrame) {
        Project project = ideFrame.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }

        for (ProcessHandler handler : ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (!(handler instanceof TomcatProcessHandler tomcatHandler)) continue;
            // Passive trigger (frame deactivation is not a user gesture) — skip
            // silently when the shared startup-gate blocks restart. The three
            // user-gesture surfaces surface the reason themselves.
            if (tomcatHandler.getRestartBlockReason() != null) continue;

            TomcatRunConfiguration config = tomcatHandler.getConfiguration();
            String action = config.getConfigData().getUpdateConfig().getOnFrameDeactivation();
            if (UpdateConfig.DO_NOTHING.equals(action)) continue;

            UpdateConfig updateConfig = config.getConfigData().getUpdateConfig();
            if (updateConfig.isShowFrameDeactivationDialog()) {
                if (!updating.compareAndSet(false, true)) continue;
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        int result = Messages.showYesNoDialog(
                                project,
                                "Apply '" + TomcatApplicationUpdater.mapActionToDisplay(action)
                                        + "' to " + config.getName() + "?",
                                "DevTomcat: Frame Deactivation",
                                "Update", "Skip",
                                Messages.getQuestionIcon());
                        if (result == Messages.YES) {
                            LOG.info("Frame deactivated — user confirmed update for: " + config.getName());
                            new TomcatApplicationUpdater(project, tomcatHandler, config, action).executeUpdate();
                        }
                    } finally {
                        updating.set(false);
                    }
                });
            } else {
                LOG.info("Frame deactivated — triggering update for: " + config.getName());
                ApplicationManager.getApplication().invokeLater(() ->
                        new TomcatApplicationUpdater(project, tomcatHandler, config, action).executeUpdate());
            }
        }
    }
}
