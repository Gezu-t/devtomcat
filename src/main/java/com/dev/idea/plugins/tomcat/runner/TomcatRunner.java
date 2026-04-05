package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.dev.idea.plugins.tomcat.update.TomcatUpdateDialog;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tomcat Run executor. Saves documents before launch and delegates
 * to {@link DefaultJavaProgramRunner}.
 */
public class TomcatRunner extends DefaultJavaProgramRunner {

    private static final Logger LOG = Logger.getInstance(TomcatRunner.class);
    private static final String RUNNER_ID = "DevTomcatEnterpriseRunner";

    @NotNull
    @Override
    public String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile runProfile) {
        return DefaultRunExecutor.EXECUTOR_ID.equals(executorId)
                && runProfile instanceof TomcatRunConfiguration;
    }

    @Override
    protected RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                             @NotNull ExecutionEnvironment env) throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();

        TomcatRunConfiguration config = (TomcatRunConfiguration) env.getRunProfile();

        // If a Tomcat instance for this configuration is already running, intercept
        // the re-run and show the Update dialog — mirrors IntelliJ Ultimate behavior.
        RunContentDescriptor existing = findRunningDescriptor(config, env);
        if (existing != null) {
            ProcessHandler handler = existing.getProcessHandler();
            if (handler instanceof TomcatProcessHandler tomcatHandler
                    && !tomcatHandler.isProcessTerminated()
                    && !tomcatHandler.isProcessTerminating()
                    && tomcatHandler.isServerStartupDetected()) {

                String defaultAction = config.getConfigData().getUpdateConfig().getOnUpdate();
                TomcatUpdateDialog dialog = new TomcatUpdateDialog(
                        env.getProject(), config.getName(), defaultAction);

                // Must show dialog on EDT
                boolean[] proceed = {false};
                String[] selectedAction = {defaultAction};
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    if (dialog.showAndGet()) {
                        proceed[0] = true;
                        selectedAction[0] = dialog.getSelectedAction();
                    }
                });

                if (!proceed[0]) return null;

                TomcatApplicationUpdater updater = new TomcatApplicationUpdater(
                        env.getProject(), tomcatHandler, config, selectedAction[0]);
                updater.executeUpdate(selectedAction[0]);
                return existing;
            }
        }

        LOG.info("Starting Tomcat: " + config.getName());
        RunContentDescriptor descriptor = super.doExecute(state, env);
        if (descriptor != null) LOG.info("Tomcat started: " + config.getName());
        return descriptor;
    }

    @Nullable
    private RunContentDescriptor findRunningDescriptor(@NotNull TomcatRunConfiguration config,
                                                        @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor descriptor : ExecutionManager.getInstance(env.getProject())
                .getRunningDescriptors(settings -> config.equals(settings.getConfiguration()))) {
            ProcessHandler handler = descriptor.getProcessHandler();
            if (handler != null && !handler.isProcessTerminated()) {
                return descriptor;
            }
        }
        return null;
    }
}