package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Strategy for deploying artifacts to a locally-launched Tomcat.
 *
 * <p>Originally split into Local and Remote implementations, but remote-mode
 * launches no longer fork a local Tomcat JVM (they route through
 * {@link RemoteDeploymentRunProfileState} and call the Tomcat Manager API
 * directly). This interface now has a single implementation
 * ({@link LocalDeploymentStrategy}); we keep it as an interface only to
 * preserve the per-strategy seam for any future deployment mode that
 * still launches a JVM (e.g. an embedded-Tomcat variant) and to keep the
 * call sites stable.
 *
 * @see TomcatJavaParametersBuilder#build()
 * @see LocalDeploymentStrategy
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
     * Default no-op; {@link LocalDeploymentStrategy} keeps this as a no-op
     * because the local JVM does not need PasswordSafe lookups.
     */
    default void resolveCredentials(@NotNull TomcatRunConfiguration configuration) {}

    /**
     * Generates a context XML descriptor for an exploded artifact, including
     * {@code <PreResources>} / {@code <PostResources>} for multi-module classpath support.
     * Used by both initial deployment and redeploy to ensure consistent context configuration.
     *
     * <p>The {@code tomcatInfo} parameter gates the {@code <Resources>} block: Tomcat 7's
     * Digester has no rules for {@code <PreResources>} / {@code <PostResources>} (those
     * elements were added in Tomcat 8), so the block is omitted when
     * {@code tomcatInfo.getMajorVersion() < 8}. Callers that don't yet know the version
     * may pass {@code null}; emission then falls back to the modern shape.
     */
    @NotNull
    static String buildContextXml(@NotNull DeploymentArtifact artifact,
                                  @NotNull Path artifactPath,
                                  boolean preserveSessions,
                                  @NotNull Project project,
                                  @Nullable TomcatInfo tomcatInfo,
                                  @Nullable TomcatDeploymentLogger logger) {
        return LocalDeploymentStrategy.buildContextXml(
                artifact, artifactPath, preserveSessions, project, tomcatInfo, logger);
    }
}
