package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Tomcat Coverage executor. Enables "Run with Coverage" (Ctrl+Shift+F10)
 * for DevTomcat configurations. IntelliJ's coverage engine automatically
 * injects the JaCoCo agent into the Java process parameters.
 */
public class TomcatCoverageRunner extends DefaultJavaProgramRunner {

    private static final Logger LOG = Logger.getInstance(TomcatCoverageRunner.class);
    private static final String RUNNER_ID = "DevTomcatCoverageRunner";
    private static final String COVERAGE_EXECUTOR_ID = "Coverage";

    @NotNull
    @Override
    public String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile runProfile) {
        return COVERAGE_EXECUTOR_ID.equals(executorId)
                && runProfile instanceof TomcatRunConfiguration;
    }

    @Override
    protected RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                             @NotNull ExecutionEnvironment env) throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();

        TomcatRunConfiguration config = (TomcatRunConfiguration) env.getRunProfile();
        LOG.info("Starting Tomcat with coverage: " + config.getName());

        RunContentDescriptor descriptor = super.doExecute(state, env);

        if (descriptor != null) {
            LOG.info("Tomcat coverage session started: " + config.getName());
        }

        return descriptor;
    }
}
