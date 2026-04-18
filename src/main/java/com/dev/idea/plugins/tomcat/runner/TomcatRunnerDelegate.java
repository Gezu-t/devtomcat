package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.dev.idea.plugins.tomcat.utils.ProcessStopSupport;
import com.dev.idea.plugins.tomcat.utils.TomcatNotifier;
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
     *
     * <p>When the configuration has "Allow parallel run" enabled, the intercept
     * is skipped — the platform proceeds with a fresh launch and
     * {@code TomcatCommandLineState} assigns a new {@code runId} so the new
     * instance gets its own isolated {@code CATALINA_BASE} and auto-bumped ports.
     */
    public boolean handleSameExecutorRerun(@NotNull TomcatRunConfiguration config,
                                            @NotNull ExecutionEnvironment env) {
        if (config.isAllowMultipleInstances()) {
            return false;
        }
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
        // Match descriptors by the RunnerAndConfigurationSettings they were
        // launched from — the stable identity IntelliJ uses internally:
        //   - survives cross-executor switches (Run→Debug uses same settings)
        //   - survives config clones (settings object is the same)
        //   - survives renames (settings reference is stable across name changes)
        //   - distinguishes two configs that happen to share the same display name
        //
        // Fall back to raw config reference for handlers launched outside the
        // normal editor flow (no settings object available).
        RunnerAndConfigurationSettings targetSettings = env.getRunnerAndConfigurationSettings();
        List<RunContentDescriptor> result = new ArrayList<>();
        for (RunContentDescriptor d :
                RunContentManager.getInstance(env.getProject()).getAllDescriptors()) {
            if (d.getProcessHandler() instanceof TomcatProcessHandler th
                    && belongsTo(th, config, targetSettings)) {
                result.add(d);
            }
        }
        return result;
    }

    /**
     * Returns true when the given handler was launched from the same run
     * configuration identity as the target environment.
     */
    private static boolean belongsTo(@NotNull TomcatProcessHandler handler,
                                      @NotNull TomcatRunConfiguration config,
                                      @Nullable RunnerAndConfigurationSettings targetSettings) {
        RunnerAndConfigurationSettings handlerSettings = handler.getLaunchSettings();
        if (targetSettings != null && handlerSettings != null) {
            return targetSettings == handlerSettings;
        }
        // Fallback for handlers launched without a settings object (rare —
        // e.g. programmatic execution paths that bypass RunManager).
        return handler.getConfiguration() == config;
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

        // Capture before destroy — see ProcessStopSupport javadoc for race rationale
        Executor oldExecutor = ExecutorRegistry.getInstance().getExecutorById(conflicting.getExecutorId());
        RunContentDescriptor oldDescriptor = ProcessStopSupport.findDescriptor(project, conflicting);
        // Capture the old process's resolved ports so the relaunch can re-use them.
        // Without this, the new launch would re-run port conflict detection, see the
        // just-released socket in OS TIME_WAIT state, and bump the port upward —
        // leading to the user-visible "port keeps increasing" behavior.
        com.dev.idea.plugins.tomcat.model.PortConfig carriedPorts = conflicting.getResolvedPorts();

        ProcessStopSupport.stopCleanAndThen(project, conflicting, oldDescriptor, oldExecutor, () -> {
            RunnerAndConfigurationSettings settings = env.getRunnerAndConfigurationSettings();
            if (settings == null) {
                settings = RunManager.getInstance(project).findSettings(config);
            }
            if (settings != null) {
                try {
                    ExecutionEnvironmentBuilder builder =
                            ExecutionEnvironmentBuilder.create(currentExecutor, settings);
                    ExecutionEnvironment newEnv = builder.build();
                    if (carriedPorts != null) {
                        newEnv.putUserData(TomcatCommandLineState.CARRIED_PORTS_KEY, carriedPorts);
                    }
                    newEnv.getRunner().execute(newEnv);
                    LOG.info("Relaunched " + config.getName()
                            + " in " + currentExecutor.getActionName() + " mode"
                            + (carriedPorts != null ? " (reusing ports)" : ""));
                } catch (ExecutionException ex) {
                    LOG.warn("Failed to relaunch " + config.getName(), ex);
                    notifyRelaunchFailed(project, config.getName(), ex.getMessage());
                }
            } else {
                LOG.warn("Could not find run settings for relaunch: " + config.getName());
                notifyRelaunchFailed(project, config.getName(), "Run configuration not found");
            }
        });
    }

    /**
     * Shows a balloon notification when a relaunch fails after the old process has already
     * been stopped. The old process is dead at this point and no rollback is possible —
     * the balloon ensures the user knows they must start the configuration manually.
     */
    private static void notifyRelaunchFailed(@NotNull Project project,
                                             @NotNull String configName,
                                             @Nullable String reason) {
        String content = "Tomcat '" + configName + "' stopped but could not relaunch" +
                (reason != null ? ": " + reason : ".") +
                " Start the configuration manually to resume.";
        TomcatNotifier.error(project, "Relaunch Failed", content);
    }
}
