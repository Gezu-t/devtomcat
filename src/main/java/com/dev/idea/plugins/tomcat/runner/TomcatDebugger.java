package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.update.TomcatApplicationUpdater;
import com.dev.idea.plugins.tomcat.update.TomcatUpdateDialog;
import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
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
 * Tomcat Debug runner. Delegates JDWP agent injection to IntelliJ's debugger runner
 * and attaches to the same resolved port via {@link RemoteConnection}.
 *
 * <h3>Re-run interception</h3>
 * The intercept lives in {@code doExecute()} (not {@code execute()}) because
 * {@link GenericDebuggerRunner#execute} performs async Promise-based setup before
 * calling {@code doExecute()} — overriding {@code execute()} breaks that chain.
 *
 * <p>Two cases are handled:
 * <ol>
 *   <li><b>Same executor (Debug→Debug):</b> once startup is complete, shows the Update
 *       dialog and returns {@code null} so no second descriptor accumulates in the toolbar.
 *       During startup the re-run is silently suppressed.</li>
 *   <li><b>Cross-executor (Run/Coverage→Debug):</b> stops the old process, removes
 *       its descriptor, then re-triggers Debug after termination.</li>
 * </ol>
 *
 * <h3>Data flow (local debug)</h3>
 * <pre>
 *   TomcatCommandLineState.ensurePreLaunchSetup()
 *     → resolvePortConflicts()
 *       → stores resolvedDebugPort (e.g. 5006 if 5005 was busy)
 *
 *   TomcatDebugger.doExecute()
 *     → calls state.getJavaParameters() to ensure pre-launch setup completed
 *     → reads resolvedDebugPort from state (NOT from config)
 *     → creates RemoteConnection with that port
 *     → attachVirtualMachine(...)
 * </pre>
 *
 * <h3>Remote mode</h3>
 * The debug host/port come from {@link RunnerSettings} (Startup/Connection tab).
 */
public class TomcatDebugger extends GenericDebuggerRunner {

    private static final Logger LOG = Logger.getInstance(TomcatDebugger.class);
    private static final String DEBUGGER_ID = "DevTomcatEnterpriseDebugger";

    @Override
    @NotNull
    public String getRunnerId() {
        return DEBUGGER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)
                && profile instanceof TomcatRunConfiguration;
    }

    @Nullable
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

        // Case 2: different executor already running → stop it, then re-launch in Debug mode
        TomcatProcessHandler conflicting = findConflictingExecutorHandler(config, env);
        if (conflicting != null && !conflicting.isProcessTerminated()
                && !conflicting.isProcessTerminating()) {
            LOG.info("Mode switch to Debug: stopping " + conflicting.getExecutorId()
                    + " instance of " + config.getName());
            stopAndRelaunch(conflicting, config, env);
            return null;
        }

        // Fresh start
        boolean isRemote = TomcatConstants.MODE_REMOTE.equals(config.getConfigData().getServerMode());

        String debugHost;
        int debugPort;

        if (isRemote) {
            RunnerSettings rs = config.getConfigData()
                    .getRunnerSettings(DefaultDebugExecutor.EXECUTOR_ID);
            debugHost = rs.getDebugHost().isEmpty() ? "127.0.0.1" : rs.getDebugHost();
            debugPort = rs.getDebugPort() > 0 ? rs.getDebugPort() : DebugConfig.DEFAULT_DEBUG_PORT;
        } else if (state instanceof TomcatCommandLineState tomcatState) {
            tomcatState.getJavaParameters();
            debugHost = "127.0.0.1";
            int resolved = tomcatState.getResolvedDebugPort();
            if (resolved > 0) {
                debugPort = resolved;
            } else {
                DebugConfig dc = config.getConfigData().getDebugConfig();
                debugPort = (dc != null && dc.isValid()) ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;
            }
        } else {
            debugHost = "127.0.0.1";
            DebugConfig dc = config.getConfigData().getDebugConfig();
            debugPort = (dc != null && dc.isValid()) ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;
            LOG.warn("Unexpected RunProfileState type: " + state.getClass().getName()
                    + " — using config debug port " + debugPort);
        }

        LOG.info("Debug session: " + config.getName() + " → " + debugHost + ":" + debugPort);

        RemoteConnection connection = new RemoteConnection(true, debugHost, String.valueOf(debugPort), false);
        RunContentDescriptor descriptor = attachVirtualMachine(state, env, connection, true);

        if (descriptor != null) {
            LOG.info("Debugger attached: " + config.getName() + " on " + debugHost + ":" + debugPort);
        } else {
            LOG.warn("Debugger attach failed: " + config.getName() + " on " + debugHost + ":" + debugPort);
        }

        return descriptor;
    }

    // -------------------------------------------------------------------------
    // Descriptor / handler lookup
    // -------------------------------------------------------------------------

    /** Returns the running descriptor for this config under the same executor (Debug). */
    @Nullable
    private RunContentDescriptor findSameExecutorDescriptor(@NotNull TomcatRunConfiguration config,
                                                             @NotNull ExecutionEnvironment env) {
        for (RunContentDescriptor d : getDescriptorsFor(config, env)) {
            ProcessHandler h = d.getProcessHandler();
            if (h instanceof TomcatProcessHandler th
                    && !th.isProcessTerminated()
                    && DefaultDebugExecutor.EXECUTOR_ID.equals(th.getExecutorId())) {
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
                    && !DefaultDebugExecutor.EXECUTOR_ID.equals(th.getExecutorId())) {
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
