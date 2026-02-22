package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;

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
        LOG.info("Starting Tomcat: " + config.getName());

        RunContentDescriptor descriptor = super.doExecute(state, env);

        if (descriptor != null) {
            LOG.info("Tomcat started successfully: " + config.getName());
        }

        return descriptor;
    }
}