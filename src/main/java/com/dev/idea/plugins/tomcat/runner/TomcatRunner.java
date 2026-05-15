package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Tomcat Run executor. Saves documents before launch and delegates
 * to {@link DefaultJavaProgramRunner}.
 *
 * <p>Same-executor reruns are handled by the platform's standard
 * stop-and-restart flow because {@link TomcatRunConfiguration} declares
 * the {@code SINGLE_INSTANCE} singleton policy. Hot reload is reachable
 * via Ctrl+F10 through {@code TomcatRunningApplicationUpdaterProvider}.
 *
 * <p>Cross-executor switches (Run→Debug, Debug→Run, Coverage→Run, etc.)
 * are intercepted here because the platform does not auto-stop the
 * previous-executor process when the executor changes — see
 * {@link TomcatRunnerDelegate#handleCrossExecutorConflict}.
 */
public class TomcatRunner extends DefaultJavaProgramRunner {

    private static final Logger LOG = Logger.getInstance(TomcatRunner.class);
    private static final String RUNNER_ID = "DevTomcatEnterpriseRunner";

    private final TomcatRunnerDelegate delegate =
            new TomcatRunnerDelegate(DefaultRunExecutor.EXECUTOR_ID, LOG);

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

        if (delegate.handleCrossExecutorConflict(config, env)) return null;

        LOG.info("Starting Tomcat: " + config.getName());
        RunContentDescriptor descriptor = super.doExecute(state, env);
        if (descriptor != null) LOG.info("Tomcat started: " + config.getName());
        return descriptor;
    }
}
