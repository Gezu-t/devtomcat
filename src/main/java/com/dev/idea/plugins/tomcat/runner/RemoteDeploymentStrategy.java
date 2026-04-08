package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Remote deployment strategy: configures VM properties for the Tomcat Manager API
 * and ensures PasswordSafe credentials are synchronously resolved before launch.
 *
 * <p>Artifact deployment to the remote server happens post-startup via
 * {@link TomcatProcessHandler#triggerRemoteDeploymentIfNeeded()},
 * which uses {@link TomcatManagerDeployer}.
 */
final class RemoteDeploymentStrategy implements DeploymentStrategy {

    private static final Logger LOG = Logger.getInstance(RemoteDeploymentStrategy.class);

    private static final String PARAM_REMOTE_MANAGER_URL = "tomcat.remote.manager.url";
    private static final String PARAM_WEBAPP_PATH = "tomcat.webapp.path";
    private static final String PARAM_WEBAPP_CONTEXT = "tomcat.webapp.context";
    private static final String PARAM_WEBAPP_COUNT = "tomcat.webapp.count";

    @Override
    public void configureDeployment(@NotNull JavaParameters params,
                                    @NotNull Path catalinaBase,
                                    @NotNull TomcatRunConfiguration configuration,
                                    @NotNull Project project,
                                    @Nullable TomcatDeploymentLogger logger) throws ExecutionException {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();

        if (remoteConfig == null || !remoteConfig.isValid()) {
            throw new ExecutionException("Remote configuration is not valid");
        }

        String managerUrl = remoteConfig.getManagerUrl();
        if (StringUtil.isEmpty(managerUrl)) {
            throw new ExecutionException("Remote manager URL not specified");
        }

        ParametersList vmParams = params.getVMParametersList();
        vmParams.addProperty(PARAM_REMOTE_MANAGER_URL, managerUrl);

        // Credentials are NOT injected as JVM system properties — doing so would expose
        // them verbatim in the process command line (visible in `ps aux`, IDE diagnostics,
        // and log dumps). TomcatProcessHandler reads credentials at deployment time directly
        // from configuration.getConfigData().getRemoteConfig(), which holds them in-process.

        List<DeploymentArtifact> artifacts = configuration.getDeployedArtifacts();
        int index = 0;
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact != null && artifact.isValid()) {
                VirtualFile artifactFile = VfsUtil.findFileByIoFile(new java.io.File(artifact.getPath()), true);

                if (artifactFile != null) {
                    // Validate context path before sending to remote Tomcat Manager
                    try {
                        ContextPathUtils.resolveContextName(artifact.getContextPath());
                    } catch (IllegalArgumentException e) {
                        throw new ExecutionException(e.getMessage());
                    }
                    String contextPath = artifact.getContextPath();
                    vmParams.addProperty(PARAM_WEBAPP_PATH + "." + index, artifactFile.getPath());
                    vmParams.addProperty(PARAM_WEBAPP_CONTEXT + "." + index, contextPath);
                    LOG.info("Deploying artifact remotely [" + index + "]: " + artifactFile.getPath() + " with context: " + contextPath);
                    index++;
                } else {
                    LOG.warn("Deployment artifact not found: " + artifact.getPath());
                    throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
                }
            }
        }
        vmParams.addProperty(PARAM_WEBAPP_COUNT, String.valueOf(index));
    }

    @Override
    public void resolveCredentials(@NotNull TomcatRunConfiguration configuration) {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig != null) {
            CredentialResolver.ensureResolved(remoteConfig);
        }
    }
}
