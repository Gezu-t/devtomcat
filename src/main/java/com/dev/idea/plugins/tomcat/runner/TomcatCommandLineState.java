package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
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
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Paths;
import java.util.Map;


public class TomcatCommandLineState extends JavaCommandLineState {
    private static final Logger LOG = Logger.getInstance(TomcatCommandLineState.class);

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final long creationTime;

    public TomcatCommandLineState(@NotNull ExecutionEnvironment environment, @NotNull TomcatRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.deploymentLogger = new TomcatDeploymentLogger(environment.getProject());
        this.creationTime = System.currentTimeMillis();
    }

    @Override
    protected GeneralCommandLine createCommandLine() throws ExecutionException {
        GeneralCommandLine commandLine = super.createCommandLine();

        // === ENVIRONMENT VARIABLES ===
        applyEnvironmentVariables(commandLine);

        // === SERVER MODE ===
        String serverMode = configuration.getConfigData().getServerMode();
        if ("Remote".equals(serverMode)) {
            configureRemoteCommandLine(commandLine);
        } else {
            configureLocalCommandLine(commandLine);
        }

        return commandLine;
    }

    private void applyEnvironmentVariables(GeneralCommandLine commandLine) {
        // Dynamic environment
        Map<String, String> dynamicEnv = DynamicTomcatEnvironment.buildEnvironmentVariables();
        dynamicEnv.forEach(commandLine::withEnvironment);

        // Configuration-specific environment
        Map<String, String> configEnv = configuration.getConfigData().getVmConfig().getEnvironmentVariables();
        configEnv.forEach(commandLine::withEnvironment);

        // Parent environment
        boolean passParentEnvs = configuration.getConfigData().getVmConfig().isPassParentEnvs();
        commandLine.withParentEnvironmentType(
                passParentEnvs ? GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE
        );
    }

    private void configureLocalCommandLine(GeneralCommandLine commandLine) throws ExecutionException {
        TomcatInfo tomcatInfo = configuration.getConfigData().getTomcatInfo();
        if (tomcatInfo == null || StringUtil.isEmpty(tomcatInfo.getPath())) {
            throw new ExecutionException("Tomcat home directory is not configured.");
        }

        String tomcatHome = tomcatInfo.getPath();
        String javaHome = getJavaHome();
        String javaExe = Paths.get(javaHome, "bin", "java").toString();
        commandLine.setExePath(javaExe);
        commandLine.setWorkDirectory(tomcatHome);

        // === VM OPTIONS ===
        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        if (!StringUtil.isEmpty(vmOptions)) {
            commandLine.addParameters(StringUtil.split(vmOptions, " "));
        }

        // === TOMCAT BOOTSTRAP ===
        commandLine.addParameter("-jar");
        commandLine.addParameter(Paths.get(tomcatHome, "lib", "catalina.jar").toString());

        // === PORTS ===
        int httpPort = configuration.getHttpPortSafe();
        if (httpPort > 0) {
            commandLine.addParameter("-Dserver.port=" + httpPort);
        }

        if (configuration.isJmxEnabled()) {
            int jmxPort = configuration.getJmxPortSafe();
            if (jmxPort > 0) {
                commandLine.addParameter("-Dcom.sun.management.jmxremote.port=" + jmxPort);
                commandLine.addParameter("-Dcom.sun.management.jmxremote=true");
                commandLine.addParameter("-Dcom.sun.management.jmxremote.authenticate=false");
                commandLine.addParameter("-Dcom.sun.management.jmxremote.ssl=false");
            }
        }

        if (configuration.isHttpsEnabled()) {
            int httpsPort = configuration.getHttpsPortSafe();
            if (httpsPort > 0) {
                commandLine.addParameter("-Dserver.ssl.enabled=true");
                commandLine.addParameter("-Dserver.port.https=" + httpsPort);
            }
        }

        // === CONTEXT PATH ===
        commandLine.addParameter("-Dcontext.path=" + configuration.getContextPathSafe());

        // === DEPLOYMENT ARTIFACTS ===
        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getArtifacts()) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile file = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);
                if (file != null) {
                    commandLine.addParameter("-Dwebapp.path=" + file.getPath());
                    deploymentLogger.logDeploymentStart(artifact.getName());
                } else {
                    LOG.warn("Artifact not found: {}" + artifact.getPath());
                    deploymentLogger.logWarning("Artifact not found: " + artifact.getName());
                }
            }
        }

        LOG.debug("Local command line: {}", commandLine.getCommandLineString());
    }

    private void configureRemoteCommandLine(GeneralCommandLine commandLine) throws ExecutionException {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();

        if (remoteConfig == null || !remoteConfig.isValid()) {
            throw new ExecutionException("Remote configuration is not valid.");
        }

        String managerUrl = remoteConfig.getManagerUrl();
        if (StringUtil.isEmpty(managerUrl)) {
            throw new ExecutionException("Remote manager URL not configured.");
        }

        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        commandLine.setExePath(javaExe);
        commandLine.addParameter("-Dremote.manager.url=" + managerUrl);

        if (remoteConfig.isUseCredentials()) {
            commandLine.addParameter("-Dremote.username=" + remoteConfig.getUsername());
            commandLine.addParameter("-Dremote.password=" + remoteConfig.getPassword());
        }

        // Remote deployment artifacts (placeholder — extend with JMX/REST later)
        for (DeploymentArtifact artifact : configuration.getConfigData().getDeploymentConfig().getArtifacts()) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile file = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);
                if (file != null) {
                    commandLine.addParameter("-Dwebapp.remote.path=" + file.getPath());
                    deploymentLogger.logInfo("Queued remote deployment: " + artifact.getName());
                } else {
                    deploymentLogger.logWarning("Artifact not found: " + artifact.getName());
                }
            }
        }

        LOG.debug("Remote command line: {}", commandLine.getCommandLineString());
    }

    private String getJavaHome() {
        String jreSelection = configuration.getConfigData().getJreSelection();
        return "Project default".equals(jreSelection) ? System.getProperty("java.home") : jreSelection;
    }

    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        Process process = createCommandLine().createProcess();
        String cmd = createCommandLine().getCommandLineString();

        TomcatProcessHandler handler = new TomcatProcessHandler(
                process,
                cmd,
                createCommandLine().getCharset(),
                deploymentLogger,
                configuration
        );

        handler.setShouldKillProcessSoftly(!DebuggerSettings.getInstance().KILL_PROCESS_IMMEDIATELY);
        ProcessTerminatedListener.attach(handler);

        setupConsoleIntegration();

        return handler;
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        return new TomcatJavaParametersBuilder(configuration).build();
    }

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        ConsoleView console = super.createConsole(executor);
        if (console != null) {
            deploymentLogger.setConsoleView(console);
        }
        return console;
    }

    private void setupConsoleIntegration() {
        Project project = getEnvironment().getProject();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;

            ConsoleView console = findConsoleView();
            if (console != null) {
                deploymentLogger.setConsoleView(console);
                deploymentLogger.logDeploymentStarted();
            } else {
                retryConsoleSetup(project);
            }
        });
    }

    private void retryConsoleSetup(@NotNull Project project) {
        // Use Swing Timer for delay
        Timer timer = new Timer(500, e -> {
            if (project.isDisposed()) return;

            ConsoleView console = findConsoleView();
            if (console != null) {
                deploymentLogger.setConsoleView(console);
                deploymentLogger.logDeploymentStarted();
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    @Nullable
    private ConsoleView findConsoleView() {
        RunContentManager manager = RunContentManager.getInstance(getEnvironment().getProject());
        for (RunContentDescriptor desc : manager.getAllDescriptors()) {
            if (desc.getExecutionConsole() instanceof ConsoleView) {
                return (ConsoleView) desc.getExecutionConsole();
            }
        }
        return null;
    }

    @NotNull
    public TomcatDeploymentLogger getDeploymentLogger() {
        return deploymentLogger;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getUptime() {
        return System.currentTimeMillis() - creationTime;
    }

    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return configuration;
    }
}