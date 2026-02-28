package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.util.PortConflictDetector;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the Tomcat process command line and manages process lifecycle.
 * Delegates parameter building to {@link TomcatJavaParametersBuilder}.
 */
public class TomcatCommandLineState extends JavaCommandLineState {

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment,
                                  @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        boolean isDebug = DefaultDebugExecutor.EXECUTOR_ID.equals(
                getEnvironment().getExecutor().getId());
        return new TomcatJavaParametersBuilder(configuration, getEnvironment())
                .setDebugMode(isDebug)
                .build();
    }

    /**
     * Checks for port conflicts before launching Tomcat.
     * Logs warnings to the deployment console for any conflicts found.
     */
    private void checkPortConflicts() {
        List<PortConflictDetector.PortConflict> conflicts =
                PortConflictDetector.detectConflicts(configuration.getConfigData().getPortConfig());

        if (!conflicts.isEmpty()) {
            for (PortConflictDetector.PortConflict conflict : conflicts) {
                deploymentLogger.logServerWarning(
                        "⚠ Port conflict: " + conflict.getServiceName() +
                                " port " + conflict.getPort() + " is in use. " +
                                conflict.getSuggestion());
            }
        }
    }

    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        // Pre-launch port conflict detection (DevTomcat exclusive feature)
        checkPortConflicts();
        
        String executorId = getEnvironment().getExecutor().getId();
        RunnerSettings runnerSettings = configuration.getConfigData().getRunnerSettings(executorId);

        GeneralCommandLine commandLine;
        if (!runnerSettings.isUseDefaultStartup() && !com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces(runnerSettings.getStartupScript())) {
            commandLine = new GeneralCommandLine(runnerSettings.getStartupScript());
            commandLine.withEnvironment(runnerSettings.getEnvironmentVariables());
            commandLine.withParentEnvironmentType(runnerSettings.isPassParentEnvs() ?
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE);
            
            if (configuration.getTomcatInfo() != null) {
                commandLine.withWorkDirectory(configuration.getTomcatInfo().getPath());
            }
        } else {
            commandLine = createJavaParameters().toCommandLine();
        }
        
        Process process = commandLine.createProcess();

        TomcatProcessHandler handler = new TomcatProcessHandler(
                process,
                commandLine.getCommandLineString(),
                StandardCharsets.UTF_8,
                deploymentLogger,
                configuration,
                runnerSettings
        );
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        ConsoleView console = super.createConsole(executor);
        if (console == null) {
            console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(getEnvironment().getProject())
                    .getConsole();
        }
        if (console != null) {
            deploymentLogger.setConsoleView(console);
        }
        return console;
    }

    @NotNull
    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }
}
