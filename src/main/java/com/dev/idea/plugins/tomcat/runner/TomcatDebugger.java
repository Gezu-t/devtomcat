package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tomcat Debug runner. Delegates JDWP agent injection to IntelliJ's debugger runner
 * and attaches to the same resolved port via {@link RemoteConnection}.
 *
 * <h3>Re-run interception</h3>
 * <ol>
 *   <li><b>Same executor (Debug→Debug):</b> shows Update dialog via
 *       {@link TomcatRunnerDelegate#handleSameExecutorRerun}.</li>
 *   <li><b>Cross-executor (Run/Coverage→Debug):</b> stops + relaunches via
 *       {@link TomcatRunnerDelegate#handleCrossExecutorConflict}.</li>
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

    private final TomcatRunnerDelegate delegate =
            new TomcatRunnerDelegate(DefaultDebugExecutor.EXECUTOR_ID, LOG);

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

        if (delegate.handleSameExecutorRerun(config, env)) return null;
        if (delegate.handleCrossExecutorConflict(config, env)) return null;

        // Fresh start — resolve debug host/port and attach debugger
        boolean isRemote = config.isRemoteMode();

        String debugHost;
        int debugPort;

        if (isRemote) {
            RunnerSettings rs = config.getConfigData()
                    .getRunnerSettings(DefaultDebugExecutor.EXECUTOR_ID);
            debugHost = resolveDebugHostForAttach(true, rs);
            debugPort = resolveDebugPortForAttach(true, rs, -1, null);
        } else if (state instanceof TomcatCommandLineState tomcatState) {
            tomcatState.getJavaParameters();
            debugHost = resolveDebugHostForAttach(false, null);
            debugPort = resolveDebugPortForAttach(
                    false, null, tomcatState.getResolvedDebugPort(), config.getConfigData().getDebugConfig());
        } else {
            debugHost = resolveDebugHostForAttach(false, null);
            DebugConfig dc = config.getConfigData().getDebugConfig();
            debugPort = resolveDebugPortForAttach(false, null, -1, dc);
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

    static @NotNull String resolveDebugHostForAttach(boolean isRemote,
                                                     @Nullable RunnerSettings runnerSettings) {
        if (!isRemote) {
            return "127.0.0.1";
        }
        if (runnerSettings == null || runnerSettings.getDebugHost().isEmpty()) {
            return "127.0.0.1";
        }
        return runnerSettings.getDebugHost();
    }

    static int resolveDebugPortForAttach(boolean isRemote,
                                         @Nullable RunnerSettings runnerSettings,
                                         int resolvedDebugPort,
                                         @Nullable DebugConfig debugConfig) {
        if (isRemote) {
            return runnerSettings != null && runnerSettings.getDebugPort() > 0
                    ? runnerSettings.getDebugPort()
                    : DebugConfig.DEFAULT_DEBUG_PORT;
        }
        if (resolvedDebugPort > 0) {
            return resolvedDebugPort;
        }
        return debugConfig != null && debugConfig.isValid()
                ? debugConfig.getPort()
                : DebugConfig.DEFAULT_DEBUG_PORT;
    }
}
