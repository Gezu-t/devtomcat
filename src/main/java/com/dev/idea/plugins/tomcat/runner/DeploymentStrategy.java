package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Strategy for deploying artifacts to Tomcat, separating local filesystem
 * deployment from remote Manager API deployment.
 *
 * <p>Implementations handle mode-specific concerns:
 * <ul>
 *   <li>{@link LocalDeploymentStrategy} — context XML generation, WAR copying,
 *       PostResources for multi-module classpath</li>
 *   <li>{@link RemoteDeploymentStrategy} — VM properties for Manager API,
 *       credential resolution via PasswordSafe</li>
 * </ul>
 *
 * @see TomcatJavaParametersBuilder#build()
 */
public interface DeploymentStrategy {

    /**
     * Configures deployment artifacts in the JavaParameters (VM properties, filesystem setup).
     * Called during {@link TomcatJavaParametersBuilder#build()} before process launch.
     *
     * @param params        the Java parameters being built
     * @param catalinaBase  the CATALINA_BASE directory
     * @param configuration the run configuration
     * @param project       the current project
     * @param logger        deployment logger (may be null in headless/test contexts)
     * @throws ExecutionException if deployment setup fails
     */
    void configureDeployment(@NotNull JavaParameters params,
                             @NotNull Path catalinaBase,
                             @NotNull TomcatRunConfiguration configuration,
                             @NotNull Project project,
                             @Nullable TomcatDeploymentLogger logger) throws ExecutionException;

    /**
     * Synchronously resolves credentials needed for deployment.
     * Only meaningful for remote mode (PasswordSafe is async);
     * local mode is a no-op.
     */
    default void resolveCredentials(@NotNull TomcatRunConfiguration configuration) {}

    /**
     * Creates the appropriate strategy based on the configuration's server mode.
     */
    @NotNull
    static DeploymentStrategy create(@NotNull TomcatRunConfiguration configuration) {
        return forMode(configuration.getConfigData().getServerMode());
    }

    /**
     * Creates the appropriate strategy for the given server mode string.
     * Package-visible for testing.
     */
    @NotNull
    static DeploymentStrategy forMode(@Nullable String mode) {
        return TomcatConstants.MODE_REMOTE.equals(mode)
                ? new RemoteDeploymentStrategy()
                : new LocalDeploymentStrategy();
    }
}
