package com.dev.idea.plugins.tomcat.runner;

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
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
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
 * <p>We create the {@link RemoteConnection} explicitly because our configuration
 * does not register {@code GenericDebuggerRunnerSettings}, so the default
 * {@code super.doExecute()} cannot determine the debug port.
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
        int debugPort = resolveDebugPort(config);

        LOG.info("Starting Tomcat debug session: " + config.getName() +
                " — debugger will attach to 127.0.0.1:" + debugPort);

        // useSockets=true  — we use dt_socket transport
        // hostName          — connect to localhost
        // address           — the JDWP port
        // serverMode=false  — the DEBUGGER is the client (Tomcat JVM is the JDWP server)
        RemoteConnection connection = new RemoteConnection(
                true, "127.0.0.1", String.valueOf(debugPort), false);

        // pollConnection=true — keep retrying until Tomcat's JDWP agent is ready
        RunContentDescriptor descriptor = attachVirtualMachine(state, env, connection, true);

        if (descriptor != null) {
            LOG.info("Debugger attached to Tomcat: " + config.getName() + " on port " + debugPort);
        } else {
            LOG.warn("Failed to attach debugger to Tomcat: " + config.getName() + " on port " + debugPort);
        }

        return descriptor;
    }

    /**
     * Resolves the debug port from the configuration, falling back to the default.
     * This must return the same port that {@link TomcatJavaParametersBuilder} uses
     * for the {@code -agentlib:jdwp} argument.
     */
    private static int resolveDebugPort(@NotNull TomcatRunConfiguration config) {
        DebugConfig debugConfig = config.getConfigData().getDebugConfig();
        if (debugConfig != null && debugConfig.isValid()) {
            return debugConfig.getPort();
        }
        LOG.warn("No valid DebugConfig found, using default debug port " + DebugConfig.DEFAULT_DEBUG_PORT);
        return DebugConfig.DEFAULT_DEBUG_PORT;
    }
}
