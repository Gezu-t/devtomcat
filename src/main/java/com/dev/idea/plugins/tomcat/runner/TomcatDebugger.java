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
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tomcat Debug runner. Delegates JDWP agent injection to IntelliJ's debugger runner
 * and attaches to the same resolved port via {@link RemoteConnection}.
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
 *         → GenericDebuggerRunner injects one matching -agentlib:jdwp arg
 *         → starts process and attaches the debugger to the same port
 * </pre>
 *
 * <h3>Remote mode</h3>
 * The debug host/port come from {@link RunnerSettings} (Startup/Connection tab)
 * because the JDWP agent runs on the remote machine.
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

        // Intercept re-run while already running — show Update dialog instead of "Stop and Rerun"
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

        boolean isRemote = TomcatConstants.MODE_REMOTE.equals(config.getConfigData().getServerMode());

        String debugHost;
        int debugPort;

        if (isRemote) {
            // Remote mode: host/port come from Startup/Connection tab (user-configured)
            RunnerSettings rs = config.getConfigData()
                    .getRunnerSettings(DefaultDebugExecutor.EXECUTOR_ID);
            debugHost = rs.getDebugHost().isEmpty() ? "127.0.0.1" : rs.getDebugHost();
            debugPort = rs.getDebugPort() > 0 ? rs.getDebugPort() : DebugConfig.DEFAULT_DEBUG_PORT;
        } else if (state instanceof TomcatCommandLineState tomcatState) {
            // Local mode: trigger pre-launch setup which resolves port conflicts,
            // then read the resolved port directly from the state — not from config.
            // getJavaParameters() is idempotent (cached after first call).
            tomcatState.getJavaParameters();

            debugHost = "127.0.0.1";
            int resolved = tomcatState.getResolvedDebugPort();
            if (resolved > 0) {
                debugPort = resolved;
            } else {
                // Fallback: no conflict detected, use config directly
                DebugConfig dc = config.getConfigData().getDebugConfig();
                debugPort = (dc != null && dc.isValid()) ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;
            }
        } else {
            // Defensive fallback for unexpected state types
            debugHost = "127.0.0.1";
            DebugConfig dc = config.getConfigData().getDebugConfig();
            debugPort = (dc != null && dc.isValid()) ? dc.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;
            LOG.warn("Unexpected RunProfileState type: " + state.getClass().getName() +
                    " — using config debug port " + debugPort);
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
