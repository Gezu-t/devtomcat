package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.dashboard.RunDashboardManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for Tomcat runner re-run interception and cross-executor mode switching.
 *
 * <p>Extracted from {@link TomcatRunner}, {@link TomcatDebugger}, and
 * {@link TomcatCoverageRunner} which share identical descriptor lookup,
 * conflict detection, and stop-and-relaunch code but cannot share a
 * base class (different IntelliJ SDK superclasses).
 *
 * <p>Each runner creates a delegate parameterized by its executor ID and
 * calls through for the two re-run cases:
 * <ol>
 *   <li><b>Same executor:</b> shows Update dialog (compile, redeploy, restart)</li>
 *   <li><b>Cross executor:</b> stops old process, removes descriptor, relaunches in new mode</li>
 * </ol>
 */
public final class TomcatRunnerDelegate {

    private final String executorId;
    private final Logger LOG;

    public TomcatRunnerDelegate(@NotNull String executorId, @NotNull Logger logger) {
        this.executorId = executorId;
        this.LOG = logger;
    }

    // -------------------------------------------------------------------------
    // Re-run interception (called from doExecute in each runner)
    // -------------------------------------------------------------------------

    /**
     * Handles Case 1: same executor already running → Update dialog.
     * Returns {@code true} if the re-run was intercepted (caller should return null).
     */
    public boolean handleSameExecutorRerun(@NotNull TomcatRunConfiguration config,
                                            @NotNull ExecutionEnvironment env) {
        RunContentDescriptor existing = findSameExecutorDescriptor(config, env);
        if (existing != null) {
            ProcessHandler handler = existing.getProcessHandler();
            if (handler instanceof TomcatProcessHandler tomcatHandler
                    && !tomcatHandler.isProcessTerminated()
                    && !tomcatHandler.isProcessTerminating()) {

                if (!tomcatHandler.isServerStartupDetected()) {
                    return true; // suppress during startup
                }

                TomcatApplicationUpdater.showDialogAndExecute(
                        env.getProject(), tomcatHandler, config);
                return true;
            }
        }
        return false;
    }

    /**
     * Handles Case 2: different executor already running → stop + relaunch.
     * Returns {@code true} if a conflict was handled (caller should return null).
     */
    public boolean handleCrossExecutorConflict(@NotNull TomcatRunConfiguration config,
                                                @NotNull ExecutionEnvironment env) {
        TomcatProcessHandler conflicting = findConflictingExecutorHandler(config, env);
        if (conflicting != null && !conflicting.isProcessTerminated()
                && !conflicting.isProcessTerminating()) {
            LOG.info("Mode switch: stopping " + conflicting.getExecutorId()
                    + " instance of " + config.getName());
            stopAndRelaunch(conflicting, config, env);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Descriptor / handler lookup
    // -------------------------------------------------------------------------

    /** Returns the running descriptor for this config under the same executor. */
    @Nullable
    public RunContentDescriptor findSameExecutorDescriptor(@NotNull TomcatRunConfiguration config,
                                                            @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && executorId.equals(th.getExecutorId())) {
                return d;
            }
        }
        return null;
    }

    /** Returns a running handler for this config under a DIFFERENT executor. */
    @Nullable
    public TomcatProcessHandler findConflictingExecutorHandler(@NotNull TomcatRunConfiguration config,
                                                                @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && !executorId.equals(th.getExecutorId())) {
                return th;
            }
        }
        return null;
    }

    @NotNull
    public List<RunContentDescriptor> getDescriptorsFor(@NotNull TomcatRunConfiguration config,
                                                         @NotNull ExecutionEnvironment env) {
        // Use RunContentManager.getAllDescriptors() — the public API that covers both the
        // Run tool-window and the Services panel — then filter by TomcatProcessHandler identity.
        // Reference equality on the configuration instance is immune to duplicate display names.
        List<RunContentDescriptor> result = new ArrayList<>();
        for (RunContentDescriptor d :
                RunContentManager.getInstance(env.getProject()).getAllDescriptors()) {
            if (d.getProcessHandler() instanceof TomcatProcessHandler th
                    && th.getConfiguration() == config) {
                result.add(d);
            }
        }
        return result;
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
     * {@link #getDescriptorsFor} (backed by {@link RunContentManager#getAllDescriptors})
     * which covers both the Run tool-window and the Services panel, then pass it directly to
     * {@link RunContentManager#removeRunContent}.
     */
    public void stopAndRelaunch(@NotNull TomcatProcessHandler conflicting,
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

    /** Finds the {@link RunContentDescriptor} whose process handler is {@code handler}. */
    @Nullable
    public RunContentDescriptor findDescriptorForHandler(@NotNull TomcatProcessHandler handler,
                                                          @NotNull TomcatRunConfiguration config,
                                                          @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            if (d.getProcessHandler() == handler) return d;
        }
        return null;
    }
}
