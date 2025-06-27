package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Refactored Tomcat Command Line State
 * Uses reusable components for better maintainability
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatCommandLineState extends JavaCommandLineState {

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final long creationTime;

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
        this.creationTime = System.currentTimeMillis();
    }

    // =====================================================================
    // COMMAND LINE CREATION
    // =====================================================================

    @Override
    protected GeneralCommandLine createCommandLine() throws ExecutionException {
        GeneralCommandLine commandLine = super.createCommandLine();

        // Apply environment configuration
        commandLine = TomcatEnvironmentBuilder.create()
                .withJdkOptions(true)
                .withPassParentEnvs(configuration.isPassParentEnvs())
                .withEnvironmentVariables(configuration.getEnvironmentVariables())
                .applyTo(commandLine);

        return commandLine;
    }

    // =====================================================================
    // PROCESS MANAGEMENT
    // =====================================================================

    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        // Create process handler
        TomcatProcessHandler handler = new TomcatProcessHandler(
                createCommandLine().createProcess(),
                StringUtil.notNullize(createCommandLine().getCommandLineString()),
                createCommandLine().getCharset(),
                deploymentLogger,
                configuration
        );

        // Configure handler
        handler.setShouldKillProcessSoftly(!DebuggerSettings.getInstance().KILL_PROCESS_IMMEDIATELY);
        ProcessTerminatedListener.attach(handler);

        // Setup console integration
        setupConsoleIntegration(handler);

        return handler;
    }

    // =====================================================================
    // JAVA PARAMETERS
    // =====================================================================

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        // Use the reusable builder
        return TomcatJavaParametersBuilder.create(configuration).build();
    }

    // =====================================================================
    // CONSOLE INTEGRATION
    // =====================================================================

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        ConsoleView console = super.createConsole(executor);

        // Setup deployment logger immediately if console is available
        if (console != null) {
            deploymentLogger.setConsoleView(console);
        }

        return console;
    }

    /**
     * Setup console integration with deployment logger
     */
    private void setupConsoleIntegration(@NotNull TomcatProcessHandler handler) {
        // Try immediate setup
        RunContentManager contentManager = RunContentManager.getInstance(getEnvironment().getProject());
        ConsoleView console = findConsoleView(contentManager);

        if (console != null) {
            deploymentLogger.setConsoleView(console);
            deploymentLogger.logDeploymentStarted();
        } else {
            // Fall back to delayed setup
            setupConsoleIntegrationDelayed();
        }
    }

    /**
     * Find console view from run content manager
     */
    @Nullable
    private ConsoleView findConsoleView(@NotNull RunContentManager contentManager) {
        return contentManager.getAllDescriptors().stream()
                .map(RunContentDescriptor::getExecutionConsole)
                .filter(c -> c instanceof ConsoleView)
                .map(c -> (ConsoleView) c)
                .findFirst()
                .orElse(null);
    }

    /**
     * Setup console integration with retry
     */
    private void setupConsoleIntegrationDelayed() {
        ApplicationManager.getApplication().invokeLater(() -> {
            RunContentManager contentManager = RunContentManager.getInstance(getEnvironment().getProject());
            ConsoleView console = findConsoleView(contentManager);

            if (console != null) {
                deploymentLogger.setConsoleView(console);
                deploymentLogger.logDeploymentStarted();
            } else {
                // Retry once more after a delay
                ApplicationManager.getApplication().invokeLater(
                        this::setupConsoleIntegrationFinal,
                        project -> project.isDisposed()
                );
            }
        });
    }

    /**
     * Final attempt to setup console integration
     */
    private void setupConsoleIntegrationFinal() {
        RunContentManager contentManager = RunContentManager.getInstance(getEnvironment().getProject());
        ConsoleView console = findConsoleView(contentManager);

        if (console != null) {
            deploymentLogger.setConsoleView(console);
            deploymentLogger.logDeploymentStarted();
        } else {
            deploymentLogger.logWarning("Could not integrate with console view");
        }
    }

    // =====================================================================
    // GETTERS
    // =====================================================================

    /**
     * Get the deployment logger
     */
    @NotNull
    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    /**
     * Get creation time
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Get uptime in milliseconds
     */
    public long getUptime() {
        return System.currentTimeMillis() - creationTime;
    }

    /**
     * Get configuration
     */
    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }
}