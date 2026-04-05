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
import com.intellij.execution.executors.DefaultRunExecutor;
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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Tomcat Run executor. Saves documents before launch and delegates
 * to {@link DefaultJavaProgramRunner}.
 *
 * <h3>Re-run interception</h3>
 * Intercept lives in {@link #doExecute}. Two cases are handled:
 * <ol>
 *   <li><b>Same executor (Run→Run):</b> once startup is complete, shows the Update dialog
 *       and returns {@code null} so no second descriptor accumulates in the toolbar.
 *       During startup the re-run is silently suppressed.</li>
 *   <li><b>Cross-executor (Debug/Coverage→Run):</b> stops the old process, removes its
 *       descriptor, then re-triggers this executor after termination — toolbar ends up
 *       with exactly one entry in the new mode.</li>
 * </ol>
 *
 * <p>The lookup uses {@link ExecutionManager#getRunningDescriptors} — the only API
 * authoritative for ALL running configurations, including those shown in the Services panel.
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

        // Case 2: different executor already running → stop it, then re-launch in this mode
        TomcatProcessHandler conflicting = findConflictingExecutorHandler(config, env);
        if (conflicting != null && !conflicting.isProcessTerminated()
                && !conflicting.isProcessTerminating()) {
            LOG.info("Mode switch to Run: stopping " + conflicting.getExecutorId()
                    + " instance of " + config.getName());
            stopAndRelaunch(conflicting, config, env);
            return null;
        }

        LOG.info("Starting Tomcat: " + config.getName());
        RunContentDescriptor descriptor = super.doExecute(state, env);
        if (descriptor != null) LOG.info("Tomcat started: " + config.getName());
        return descriptor;
    }

    // -------------------------------------------------------------------------
    // Descriptor / handler lookup
    // -------------------------------------------------------------------------

    /** Returns the running descriptor for this config under the same executor (Run). */
    @Nullable
    private RunContentDescriptor findSameExecutorDescriptor(@NotNull TomcatRunConfiguration config,
                                                             @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && DefaultRunExecutor.EXECUTOR_ID.equals(th.getExecutorId())) {
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
                    && !DefaultRunExecutor.EXECUTOR_ID.equals(th.getExecutorId())) {
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

    /**
     * Stops {@code conflicting}, removes its descriptor from both the toolbar and the
     * Services panel, then re-executes this configuration under the current executor
     * once termination is confirmed.
     *
     * <p>{@link RunContentManager#findContentDescriptor} only scans executor tool-window
     * tabs and misses Services-panel entries. We resolve the descriptor via
     * {@link #getDescriptorsFor} (backed by {@link ExecutionManager#getRunningDescriptors})
     * which is authoritative for all entries, then pass it directly to
     * {@link RunContentManager#removeRunContent}.
     */
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
                    // Remove the old toolbar / Services entry, then force a dashboard refresh.
                    // Without the explicit refresh, the Services panel re-renders from the
                    // processTerminated() status update (which fires before this invokeLater)
                    // and shows the stale entry until the next organic repaint.
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

                    // Re-launch in the new mode
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

    /** Finds the {@link RunContentDescriptor} whose process handler is {@code handler}
     *  using the Services-panel-aware {@link #getDescriptorsFor} registry. */
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
