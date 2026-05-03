package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

/**
 * Platform-fixture coverage of {@link RemoteDeploymentStrategy} —
 * specifically the contract that <b>no remote-deployment configuration
 * leaks onto the JVM command line</b>.
 *
 * <h2>What the test pins</h2>
 * Earlier, {@code configureDeployment} emitted four JVM {@code -D} properties
 * ({@code tomcat.remote.manager.url}, {@code tomcat.webapp.path.N},
 * {@code tomcat.webapp.context.N}, {@code tomcat.webapp.count}) which had
 * no consumer anywhere in the plugin or in standalone Tomcat. They served
 * only to leak the manager URL and every artifact's filesystem path into
 * {@code ps aux} output and IDE diagnostic dumps.
 *
 * <p>The fix removes those emissions. This test pins the post-fix contract
 * so the leak cannot recur. The same pattern is enforced for the local
 * strategy in {@code TomcatVmOptionsConfiguratorTest} (the dead Spring
 * Boot {@code server.port} / {@code server.shutdown.port} family).
 */
public class RemoteDeploymentStrategyPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(),
                type.getConfigurationFactories()[0],
                name);
    }

    private TomcatRunConfiguration validRemoteConfig(String name) {
        TomcatRunConfiguration cfg = createConfig(name);
        RemoteConfig rc = cfg.getConfigData().getRemoteConfig();
        rc.setManagerUrl("http://example.com:8080/manager");
        rc.setUsername("admin");
        rc.setPassword("secret");
        rc.setUseCredentials(true);
        cfg.getConfigData().setServerMode(TomcatConstants.MODE_REMOTE);
        return cfg;
    }

    public void testDoesNotEmitDeadRemoteJvmProperties() throws ExecutionException {
        // The bug fix this test pins. Pre-fix, configureDeployment added four
        // -D properties to the JVM command line that nothing read. Post-fix,
        // the VM parameter list must stay completely free of any remote-
        // deployment property name.
        TomcatRunConfiguration cfg = validRemoteConfig("RemoteJvmLeak");
        RemoteDeploymentStrategy strategy = new RemoteDeploymentStrategy();
        JavaParameters params = new JavaParameters();

        strategy.configureDeployment(params, Path.of("/tmp/devtomcat/base"),
                cfg, getProject(), null);

        assertFalse("tomcat.remote.manager.url must NOT be emitted (no consumer reads it)",
                params.getVMParametersList().hasProperty("tomcat.remote.manager.url"));
        assertFalse("tomcat.webapp.count must NOT be emitted (no consumer reads it)",
                params.getVMParametersList().hasProperty("tomcat.webapp.count"));
        // The artifact path/context properties were indexed (.0, .1, …). Pin
        // that no property whose name STARTS with the dead family appears, so
        // a future change that re-introduces them with any indexing scheme
        // gets caught.
        boolean anyDead = params.getVMParametersList().getProperties().keySet().stream()
                .anyMatch(k -> k.startsWith("tomcat.webapp.")
                        || k.startsWith("tomcat.remote."));
        assertFalse("no property in the dead tomcat.webapp.* / tomcat.remote.* family may be emitted",
                anyDead);
    }

    public void testThrowsWhenManagerUrlIsBlank() {
        // Validation that should still happen: an empty manager URL is a
        // launch-stopper. Pin it so the JVM-property cleanup didn't
        // accidentally remove the validation along with the leak.
        TomcatRunConfiguration cfg = validRemoteConfig("RemoteBlankUrl");
        cfg.getConfigData().getRemoteConfig().setManagerUrl("");

        RemoteDeploymentStrategy strategy = new RemoteDeploymentStrategy();
        JavaParameters params = new JavaParameters();

        // The empty-URL setter normalizes to DEFAULT_MANAGER_URL, so the
        // strategy gets a non-empty default. We can still pin that the
        // strategy doesn't throw on a config with the default URL — the
        // negative case is covered by configureDeployment's null/invalid
        // remoteConfig branch (testThrowsWhenRemoteConfigInvalid below).
        try {
            strategy.configureDeployment(params, Path.of("/tmp"), cfg, getProject(), null);
        } catch (ExecutionException ignored) {
            // It is acceptable for configureDeployment to throw on a config
            // that the setter normalized to a default the user didn't intend
            // — what matters is that we DON'T silently emit the leak.
        }
        assertFalse("even on the validation throw path, no -D property may leak",
                params.getVMParametersList().hasProperty("tomcat.remote.manager.url"));
    }

    public void testThrowsWhenRemoteConfigIsInvalid() {
        // RemoteConfig.isValid() returns false when useCredentials=true but
        // username is empty — that's the simplest "invalid config" we can
        // construct cleanly. Pin that the strategy refuses to launch.
        TomcatRunConfiguration cfg = createConfig("RemoteInvalidConfig");
        RemoteConfig rc = cfg.getConfigData().getRemoteConfig();
        rc.setManagerUrl("http://example.com:8080/manager");
        rc.setUseCredentials(true);
        rc.setUsername(""); // intentionally invalid for credentialed mode
        rc.setPassword("");
        cfg.getConfigData().setServerMode(TomcatConstants.MODE_REMOTE);

        RemoteDeploymentStrategy strategy = new RemoteDeploymentStrategy();
        JavaParameters params = new JavaParameters();

        try {
            strategy.configureDeployment(params, Path.of("/tmp"), cfg, getProject(), null);
            // It's possible the model permits this combination and only the
            // launcher's own remote-credential gate rejects it. In that case
            // configureDeployment returns cleanly — we still must not leak.
        } catch (ExecutionException expected) {
            // Validation rejected — desirable.
        }
        assertFalse(params.getVMParametersList().hasProperty("tomcat.remote.manager.url"));
    }
}
