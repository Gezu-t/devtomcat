package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Remote deployment strategy. Validates the remote configuration and the
 * artifact list before launch, and ensures PasswordSafe credentials are
 * synchronously resolved by {@link #resolveCredentials}.
 *
 * <h2>What this class does NOT do</h2>
 * It does not configure JVM system properties for remote deployment. The
 * remote deployment work runs <i>post</i>-startup via
 * {@link TomcatProcessHandler#triggerRemoteDeploymentIfNeeded()}, which
 * reads {@code configuration.getConfigData().getRemoteConfig()} and the
 * configured artifacts directly from the in-process configuration object.
 *
 * <p>An earlier revision emitted four
 * {@code tomcat.remote.manager.url} / {@code tomcat.webapp.path.N} /
 * {@code tomcat.webapp.context.N} / {@code tomcat.webapp.count} JVM
 * properties at launch. They had:
 * <ol>
 *   <li><b>Zero consumers.</b> No code in the plugin (or in standalone
 *       Tomcat) reads them — {@link TomcatManagerDeployer} pulls everything
 *       from {@link RemoteConfig} in-process.</li>
 *   <li><b>An information-disclosure surface.</b> Manager URLs and the
 *       full filesystem paths of every deployed artifact appeared on the
 *       JVM command line, visible in {@code ps aux} output, IDE diagnostic
 *       captures, and crash dumps — for no functional reason.</li>
 * </ol>
 * Both consequences are removed by leaving the JVM command line untouched.
 * The validation logic that <i>was</i> useful (catching invalid context paths
 * and missing artifact files before launch) is preserved here.
 *
 * <p>Mirrors the same fix applied to
 * {@code TomcatVmOptionsConfigurator.configureHttps()} — both modules now
 * follow the rule "Tomcat configuration belongs in {@code server.xml} and
 * the in-process config object, never in the JVM command line".
 */
final class RemoteDeploymentStrategy implements DeploymentStrategy {

    private static final Logger LOG = Logger.getInstance(RemoteDeploymentStrategy.class);

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

        // Validate every configured artifact: context-path shape and
        // existence on disk. Failures surface as ExecutionException at
        // launch, before the JVM forks — so the user sees a clear preflight
        // error instead of a silent post-startup deployment failure.
        List<DeploymentArtifact> artifacts = configuration.getDeployedArtifacts();
        int validated = 0;
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;

            try {
                ContextPathUtils.resolveContextName(artifact.getContextPath());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException(e.getMessage());
            }

            VirtualFile artifactFile = VfsUtil.findFileByIoFile(new File(artifact.getPath()), true);
            if (artifactFile == null) {
                LOG.warn("Deployment artifact not found: " + artifact.getPath());
                throw new ExecutionException("Deployment artifact not found: " + artifact.getPath());
            }
            LOG.info("Remote artifact validated [" + validated + "]: "
                    + artifactFile.getPath() + " with context: " + artifact.getContextPath());
            validated++;
        }
        // Credentials are intentionally NOT injected as JVM system properties — that
        // would expose them verbatim in the command line. TomcatProcessHandler reads
        // credentials at deployment time directly from the in-process RemoteConfig.
    }

    @Override
    public void resolveCredentials(@NotNull TomcatRunConfiguration configuration) {
        RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
        if (remoteConfig != null) {
            CredentialResolver.ensureResolved(remoteConfig);
        }
    }
}
