package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.update.RunningApplicationUpdater;
import com.intellij.execution.update.RunningApplicationUpdaterProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides the "Update Running Application" (Ctrl+F10) integration for DevTomcat.
 *
 * <p>When a Tomcat process is running and the user triggers an update,
 * the platform calls {@link #createUpdater} to get the appropriate updater
 * based on the run configuration's {@link UpdateConfig} settings.
 */
public class TomcatRunningApplicationUpdaterProvider implements RunningApplicationUpdaterProvider {

    @Override
    @Nullable
    public RunningApplicationUpdater createUpdater(@NotNull Project project,
                                                    @NotNull ProcessHandler processHandler) {
        if (!(processHandler instanceof TomcatProcessHandler tomcatHandler)) {
            return null;
        }

        if (tomcatHandler.isProcessTerminating() || tomcatHandler.isProcessTerminated()) {
            return null;
        }

        if (!tomcatHandler.isServerStartupDetected()) {
            return null;
        }

        TomcatRunConfiguration config = tomcatHandler.getConfiguration();
        UpdateConfig updateConfig = config.getConfigData().getUpdateConfig();
        String action = updateConfig.getOnUpdate();

        if (UpdateConfig.DO_NOTHING.equals(action)) {
            return null;
        }

        return new TomcatApplicationUpdater(project, tomcatHandler, config, action);
    }
}
