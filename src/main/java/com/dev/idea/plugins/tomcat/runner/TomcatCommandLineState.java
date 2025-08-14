package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tomcat Command Line State
 * Manages Tomcat process execution and console integration
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
        // Create the actual process
        Process process = createCommandLine().createProcess();
        String commandLineString = createCommandLine().getCommandLineString();

        // Create process handler
        TomcatProcessHandler handler = new TomcatProcessHandler(
                process,
                commandLineString,
                createCommandLine().getCharset(),
                deploymentLogger,
                configuration
        );

        // Configure handler
        handler.setShouldKillProcessSoftly(!DebuggerSettings.getInstance().KILL_PROCESS_IMMEDIATELY);
        ProcessTerminatedListener.attach(handler);

        // Setup console integration
        setupConsoleIntegration();

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
    private void setupConsoleIntegration() {
        final Project project = getEnvironment().getProject();

        // Try immediate setup
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }

            RunContentManager contentManager = RunContentManager.getInstance(project);
            ConsoleView console = findConsoleView(contentManager);

            if (console != null) {
                deploymentLogger.setConsoleView(console);
                deploymentLogger.logDeploymentStarted();
            } else {
                // Retry after a short delay
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        retryConsoleSetup(project);
                    }
                });
            }
        });
    }

    /**
     * Retry console setup
     */
    private void retryConsoleSetup(@NotNull Project project) {
        RunContentManager contentManager = RunContentManager.getInstance(project);
        ConsoleView console = findConsoleView(contentManager);

        if (console != null) {
            deploymentLogger.setConsoleView(console);
            deploymentLogger.logDeploymentStarted();
        }
        // If still no console, deployment logger will fallback to System.out
    }

    /**
     * Find console view from run content manager
     */
    @Nullable
    private ConsoleView findConsoleView(@NotNull RunContentManager contentManager) {
        for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
            if (descriptor.getExecutionConsole() instanceof ConsoleView) {
                return (ConsoleView) descriptor.getExecutionConsole();
            }
        }
        return null;
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