package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Run-profile state for a remote-mode Tomcat configuration.
 *
 * <p>This is the entry point that replaces the previous "fork a local JVM
 * <i>and</i> deploy remotely" behaviour. {@link TomcatRunConfiguration#getState}
 * routes remote configurations here; local configurations still go through
 * {@link TomcatCommandLineState}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #execute} validates the {@link RemoteConfig} and the configured
 *       artifact list, resolves credentials synchronously, then returns an
 *       {@link ExecutionResult} whose handler is a
 *       {@link RemoteDeploymentProcessHandler}.</li>
 *   <li>The handler runs the actual Manager-API deploy on a background pooled
 *       thread (see that class's javadoc for the deploy lifecycle).</li>
 *   <li>Validation failures throw {@link ExecutionException} so the IDE
 *       surfaces them in the standard "run-configuration error" modal —
 *       same UX as a misconfigured local launch.</li>
 * </ul>
 *
 * <h2>What this state does NOT do</h2>
 * <ul>
 *   <li>No local JVM fork. No {@code JavaParameters}. No catalina.base setup.
 *       The remote Tomcat owns its own JVM and filesystem; the IDE's job
 *       ends at the Manager API call.</li>
 *   <li>No port resolution. Remote ports live on the remote machine and are
 *       not detectable from here.</li>
 *   <li>No before-launch artifact rebuild specific to remote — the same
 *       Before Run task chain applies, but our local-only steps (e.g.
 *       {@code DeployedClassesSync}) are no-ops here because there is no
 *       local exploded directory to mirror into.</li>
 * </ul>
 *
 * @see RemoteDeploymentProcessHandler
 * @see TomcatManagerDeployer
 */
public class RemoteDeploymentRunProfileState implements RunProfileState {

    private final ExecutionEnvironment environment;
    private final TomcatRunConfiguration configuration;

    public RemoteDeploymentRunProfileState(@NotNull ExecutionEnvironment environment,
                                            @NotNull TomcatRunConfiguration configuration) {
        this.environment = environment;
        this.configuration = configuration;
    }

    @Override
    public @NotNull ExecutionResult execute(@NotNull Executor executor,
                                            @NotNull ProgramRunner<?> runner) throws ExecutionException {
        validateRemoteConfig();
        validateArtifacts();
        resolveCredentialsOrThrow();

        TomcatDeploymentLogger logger = new TomcatDeploymentLogger(environment.getProject());
        RemoteDeploymentProcessHandler handler = new RemoteDeploymentProcessHandler(
                configuration, environment.getProject(), logger);

        ConsoleView console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(environment.getProject())
                .getConsole();
        console.attachToProcess(handler);
        logger.setConsoleView(console);

        return new DefaultExecutionResult(console, handler);
    }

    /**
     * Visible for tests. Throws {@link ExecutionException} with a clear
     * message when the remote configuration is missing, blank, or
     * structurally invalid.
     */
    void validateRemoteConfig() throws ExecutionException {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig == null || !remoteConfig.isValid()) {
            throw new ExecutionException(
                    "Remote configuration is not valid. Open the run configuration's"
                            + " Server tab and check the Manager URL and credentials.");
        }
        if (StringUtil.isEmpty(remoteConfig.getManagerUrl())) {
            throw new ExecutionException(
                    "Remote manager URL not specified. Set it in the run configuration's"
                            + " Server tab (typically http://host:8080/manager).");
        }
    }

    /**
     * Visible for tests. Per-artifact context-path syntax check. Catches the
     * "user typed {@code foo bar} or {@code ////}" cases that Tomcat Manager
     * would otherwise reject with an opaque 500 error halfway through the
     * deploy.
     */
    void validateArtifacts() throws ExecutionException {
        List<DeploymentArtifact> artifacts = configuration.getDeployedArtifacts();
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;
            try {
                ContextPathUtils.resolveContextName(artifact.getContextPath());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException("Invalid context path on artifact '"
                        + artifact.getDisplayName() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Visible for tests. Resolves credentials synchronously from
     * PasswordSafe and gates the launch when {@code useCredentials} is on
     * but no password was found — failing here is far less confusing than
     * a {@code 401 Unauthorized} mid-deploy.
     */
    void resolveCredentialsOrThrow() throws ExecutionException {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig == null) return;
        CredentialResolver.ensureResolved(remoteConfig);
        if (remoteConfig.isUseCredentials() && remoteConfig.getPassword().isEmpty()) {
            throw new ExecutionException(
                    "Remote deployment requires credentials but no password was found."
                            + " Configure credentials in the run configuration's Server tab"
                            + " (or store them in PasswordSafe).");
        }
    }
}
