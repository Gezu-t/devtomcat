package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.coverage.CoverageAgentAttacher;
import com.intellij.coverage.CoverageDataManager;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Tomcat Coverage executor. Enables "Run with Coverage" (Ctrl+Shift+F10)
 * for DevTomcat configurations.
 *
 * <p>The coverage pipeline engages in three places:
 * <ol>
 *   <li>{@link com.dev.idea.plugins.tomcat.coverage.TomcatJavaCoverageEngineExtension}
 *       tells the platform that {@link TomcatRunConfiguration} is coverage-capable
 *       (required because the config extends {@code LocatableConfigurationBase},
 *       not {@code CommonJavaRunConfigurationParameters}).</li>
 *   <li>{@link com.dev.idea.plugins.tomcat.coverage.CoverageAgentAttacher}
 *       synchronises DevTomcat's include/exclude patterns into
 *       {@code JavaCoverageEnabledConfiguration} and appends the coverage
 *       {@code -javaagent} argument during JVM parameter construction.</li>
 *   <li>{@link CoverageDataManager#attachToProcess} below, which registers the
 *       post-run report loader so the IDE picks up the {@code .ec} output
 *       when Tomcat exits.</li>
 * </ol>
 *
 * <p>Same re-run interception strategy as {@link TomcatRunner} and
 * {@link TomcatDebugger}, delegated to {@link TomcatRunnerDelegate}.
 */
public class TomcatCoverageRunner extends DefaultJavaProgramRunner {

    private static final Logger LOG = Logger.getInstance(TomcatCoverageRunner.class);
    private static final String RUNNER_ID = "DevTomcatCoverageRunner";
    private static final String COVERAGE_EXECUTOR_ID = "Coverage";

    private final TomcatRunnerDelegate delegate =
            new TomcatRunnerDelegate(COVERAGE_EXECUTOR_ID, LOG);

    @NotNull
    @Override
    public String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile runProfile) {
        if (!COVERAGE_EXECUTOR_ID.equals(executorId)) return false;
        if (!(runProfile instanceof TomcatRunConfiguration config)) return false;
        return !config.isRemoteMode();
    }

    @Override
    protected RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                             @NotNull ExecutionEnvironment env) throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();

        TomcatRunConfiguration config = (TomcatRunConfiguration) env.getRunProfile();

        if (delegate.handleSameExecutorRerun(config, env)) return null;
        if (delegate.handleCrossExecutorConflict(config, env)) return null;

        LOG.info("Starting Tomcat with coverage: " + config.getName());
        RunContentDescriptor descriptor = super.doExecute(state, env);
        if (descriptor != null) {
            ProcessHandler handler = descriptor.getProcessHandler();
            if (handler != null) {
                // The pre-launch agent path already selected the current suite.
                // Attach the process listener directly so we don't reset the
                // suite again on the EDT and lose identity/file-path alignment.
                if (CoverageAgentAttacher.ensureCoverageSuite(config) != null) {
                    CoverageDataManager.getInstance(config.getProject())
                            .attachToProcess(handler, config, env.getRunnerSettings());
                } else {
                    LOG.warn("Coverage suite missing after launch for '" + config.getName()
                            + "' — coverage results may not be collected");
                }
            }
            LOG.info("Tomcat coverage session started: " + config.getName());
        }
        return descriptor;
    }
}
