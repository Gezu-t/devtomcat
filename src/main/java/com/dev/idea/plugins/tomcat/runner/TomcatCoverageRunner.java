package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.dev.idea.plugins.tomcat.update.TomcatUpdateDialog;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Tomcat Coverage executor. Enables "Run with Coverage" (Ctrl+Shift+F10)
 * for DevTomcat configurations. IntelliJ's coverage engine automatically
 * injects the JaCoCo agent into the Java process parameters.
 *
 * <p>Same re-run interception and cross-executor mode-switch strategy as
 * {@link TomcatRunner} and {@link TomcatDebugger}.
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
        if (!COVERAGE_EXECUTOR_ID.equals(executorId)) return false;
        if (!(runProfile instanceof TomcatRunConfiguration config)) return false;
        return !TomcatConstants.MODE_REMOTE.equals(config.getConfigData().getServerMode());
    }

    @Override
    protected RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                             @NotNull ExecutionEnvironment env) throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();

        TomcatRunConfiguration config = (TomcatRunConfiguration) env.getRunProfile();

        // Case 1: same executor already running → Update dialog
        RunContentDescriptor existing = findSameExecutorDescriptor(config, env);
        if (existing != null) {
            ProcessHandler handler = existing.getProcessHandler();
            if (handler instanceof TomcatProcessHandler tomcatHandler
                    && !tomcatHandler.isProcessTerminated()
                    && !tomcatHandler.isProcessTerminating()) {

                if (!tomcatHandler.isServerStartupDetected()) {
                    return null;
                }

                String defaultAction = config.getConfigData().getUpdateConfig().getOnUpdate();
                boolean isLocal = !TomcatConstants.MODE_REMOTE
                        .equals(config.getConfigData().getServerMode());
                TomcatUpdateDialog dialog = new TomcatUpdateDialog(
                        env.getProject(), config.getName(), defaultAction, isLocal);

                if (!dialog.showAndGet()) return null;
                String selectedAction = dialog.getSelectedAction();

                new TomcatApplicationUpdater(env.getProject(), tomcatHandler, config, selectedAction)
                        .executeUpdate(selectedAction);
                return null;
            }
        }

        // Case 2: different executor already running → stop it, then re-launch in Coverage mode
        TomcatProcessHandler conflicting = findConflictingExecutorHandler(config, env);
        if (conflicting != null && !conflicting.isProcessTerminated()
                && !conflicting.isProcessTerminating()) {
            LOG.info("Mode switch to Coverage: stopping " + conflicting.getExecutorId()
                    + " instance of " + config.getName());
            stopAndRelaunch(conflicting, config, env);
            return null;
        }

        LOG.info("Starting Tomcat with coverage: " + config.getName());
        RunContentDescriptor descriptor = super.doExecute(state, env);
        if (descriptor != null) LOG.info("Tomcat coverage session started: " + config.getName());
        return descriptor;
    }

    // -------------------------------------------------------------------------
    // Descriptor / handler lookup
    // -------------------------------------------------------------------------

    /** Returns the running descriptor for this config under the same executor (Coverage). */
    @Nullable
    private RunContentDescriptor findSameExecutorDescriptor(@NotNull TomcatRunConfiguration config,
                                                             @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && COVERAGE_EXECUTOR_ID.equals(th.getExecutorId())) {
                return d;
            }
        }
        return null;
    }

    /** Returns a running handler for this config under a DIFFERENT executor. */
    @Nullable
    private TomcatProcessHandler findConflictingExecutorHandler(@NotNull TomcatRunConfiguration config,
                                                                 @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && !COVERAGE_EXECUTOR_ID.equals(th.getExecutorId())) {
                return th;
            }
        }
        return null;
    }

    private List<RunContentDescriptor> getDescriptorsFor(@NotNull TomcatRunConfiguration config,
                                                          @NotNull ExecutionEnvironment env) {
        // Use reference equality: env.getRunProfile() is the same instance RunManager holds,
        // so this is exact identity — immune to duplicate display names.
        return ExecutionManager.getInstance(env.getProject())
                .getRunningDescriptors(settings ->
                        settings != null && settings.getConfiguration() == config);
    }

    // -------------------------------------------------------------------------
    // Cross-executor mode switch
    // -------------------------------------------------------------------------

    private void stopAndRelaunch(@NotNull TomcatProcessHandler conflicting,
                                  @NotNull TomcatRunConfiguration config,
                                  @NotNull ExecutionEnvironment env) {
        Project project = env.getProject();
        Executor currentExecutor = env.getExecutor();
        String oldExecutorId = conflicting.getExecutorId();

        // Capture the descriptor now, while the process is still registered
        RunContentDescriptor oldDescriptor = findDescriptorForHandler(conflicting, config, env);

        conflicting.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (oldDescriptor != null) {
                        Executor oldExecutor = ExecutorRegistry.getInstance().getExecutorById(oldExecutorId);
                        if (oldExecutor != null) {
                            RunContentManager.getInstance(project)
                                    .removeRunContent(oldExecutor, oldDescriptor);
                            if (!project.isDisposed()) {
                                RunDashboardManager.getInstance(project).updateDashboard(true);
                            }
                        }
                    }

                    RunnerAndConfigurationSettings settings =
                            RunManager.getInstance(project).findSettings(config);
                    if (settings != null) {
                        ProgramRunnerUtil.executeConfiguration(settings, currentExecutor);
                        LOG.info("Relaunched " + config.getName()
                                + " in " + currentExecutor.getActionName() + " mode");
                    } else {
                        LOG.warn("Could not find run settings for relaunch: " + config.getName());
                    }
                });
            }
        });

        conflicting.destroyProcess();
    }

    @Nullable
    private RunContentDescriptor findDescriptorForHandler(@NotNull TomcatProcessHandler handler,
                                                           @NotNull TomcatRunConfiguration config,
                                                           @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            if (d.getProcessHandler() == handler) return d;
        }
        return null;
    }
}
