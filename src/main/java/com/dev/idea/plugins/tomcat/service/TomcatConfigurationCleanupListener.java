package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Listens for Tomcat run configuration removal and cleans up associated
 * live status data, then forces a Services tool window refresh.
 * Without this listener, deleted configurations leave stale entries in the dashboard.
 */
public final class TomcatConfigurationCleanupListener implements RunManagerListener {

    private static final Logger LOG = Logger.getInstance(TomcatConfigurationCleanupListener.class);

    private final Project project;

    public TomcatConfigurationCleanupListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void runConfigurationRemoved(@NotNull RunnerAndConfigurationSettings settings) {
        if (!(settings.getConfiguration() instanceof TomcatRunConfiguration)) {
            return;
        }

        String configName = settings.getName();
        LOG.info("DevTomcat: Cleaning up status for removed configuration: " + configName);

        // Clear live status data
        TomcatDeploymentStatusService statusService =
                TomcatDeploymentStatusService.getInstance(project);
        statusService.remove(configName);

        // Force the Services tool window to refresh so the stale node disappears
        if (!project.isDisposed()) {
            RunDashboardManager.getInstance(project).updateDashboard(true);
        }
    }
}
