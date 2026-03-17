package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
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
 * Tomcat Debug runner. Launches Tomcat with JDWP enabled and attaches
 * IntelliJ's debugger to the configured debug port.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@link TomcatJavaParametersBuilder} adds {@code -agentlib:jdwp=...}
 *       to the Tomcat JVM command line (the JVM listens on the debug port).</li>
 *   <li>This runner creates a matching {@link RemoteConnection} and calls
 *       {@link #attachVirtualMachine} so IntelliJ connects to that port.</li>
 *   <li>{@code pollConnection=true} makes the debugger retry until the
 *       connection succeeds (Tomcat may take a moment to start).</li>
 * </ol>
 *
 * <h3>Remote mode</h3>
 * In remote server mode, the debug host and port come from
 * {@link RunnerSettings#getDebugHost()} and {@link RunnerSettings#getDebugPort()}
 * (configured in the Startup/Connection tab), because the JDWP agent runs
 * on the remote machine — not on localhost.
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

        // Force pre-launch setup (port conflict resolution) BEFORE reading the debug port.
        // TomcatCommandLineState.createJavaParameters() triggers ensurePreLaunchSetup()
        // which may auto-resolve the debug port (e.g. 5005 → 5006 if in use).
        // The resolved port is written back to DebugConfig, so we must trigger this first.
        // JavaCommandLineState.getJavaParameters() caches the result, so the later
        // state.execute() inside attachVirtualMachine reuses the same parameters.
        if (state instanceof TomcatCommandLineState tomcatState) {
            tomcatState.getJavaParameters();
        }

        String debugHost = resolveDebugHost(config);
        int debugPort = resolveDebugPort(config);

        LOG.info("Starting Tomcat debug session: " + config.getName() +
                " — debugger will attach to " + debugHost + ":" + debugPort);

        // useSockets=true  — we use dt_socket transport
        // serverMode=false — the DEBUGGER is the client (Tomcat JVM is the JDWP server)
        RemoteConnection connection = new RemoteConnection(
                true, debugHost, String.valueOf(debugPort), false);

        // pollConnection=true — keep retrying until Tomcat's JDWP agent is ready
        RunContentDescriptor descriptor = attachVirtualMachine(state, env, connection, true);

        if (descriptor != null) {
            LOG.info("Debugger attached to Tomcat: " + config.getName() +
                    " on " + debugHost + ":" + debugPort);
        } else {
            LOG.warn("Failed to attach debugger to Tomcat: " + config.getName() +
                    " on " + debugHost + ":" + debugPort);
        }

        return descriptor;
    }

    /**
     * Resolves the debug host. In remote mode, uses the host from the
     * Startup/Connection tab's RunnerSettings. In local mode, defaults to localhost.
     */
    @NotNull
    private static String resolveDebugHost(@NotNull TomcatRunConfiguration config) {
        if (TomcatConstants.MODE_REMOTE.equals(config.getConfigData().getServerMode())) {
            RunnerSettings rs = config.getConfigData()
                    .getRunnerSettings(DefaultDebugExecutor.EXECUTOR_ID);
            String host = rs.getDebugHost();
            if (!host.isEmpty()) return host;
        }
        return "127.0.0.1";
    }

    /**
     * Resolves the debug port. In remote mode, uses the port from RunnerSettings.
     * In local mode, uses DebugConfig (which may have been auto-resolved by
     * {@link TomcatCommandLineState#resolvePortConflicts()}).
     */
    private static int resolveDebugPort(@NotNull TomcatRunConfiguration config) {
        if (TomcatConstants.MODE_REMOTE.equals(config.getConfigData().getServerMode())) {
            RunnerSettings rs = config.getConfigData()
                    .getRunnerSettings(DefaultDebugExecutor.EXECUTOR_ID);
            int port = rs.getDebugPort();
            if (port > 0) return port;
        }
        DebugConfig debugConfig = config.getConfigData().getDebugConfig();
        if (debugConfig != null && debugConfig.isValid()) {
            return debugConfig.getPort();
        }
        LOG.warn("No valid debug port found, using default " + DebugConfig.DEFAULT_DEBUG_PORT);
        return DebugConfig.DEFAULT_DEBUG_PORT;
    }
}
