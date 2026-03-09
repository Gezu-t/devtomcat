package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentStrategy")
class DeploymentStrategyTest {

    @Nested
    @DisplayName("forMode dispatch")
    class FactoryDispatchTests {

        @Test
        @DisplayName("returns LocalDeploymentStrategy for Local mode")
        void localMode() {
            DeploymentStrategy strategy = DeploymentStrategy.forMode(TomcatConstants.MODE_LOCAL);
            assertInstanceOf(LocalDeploymentStrategy.class, strategy);
        }

        @Test
        @DisplayName("returns RemoteDeploymentStrategy for Remote mode")
        void remoteMode() {
            DeploymentStrategy strategy = DeploymentStrategy.forMode(TomcatConstants.MODE_REMOTE);
            assertInstanceOf(RemoteDeploymentStrategy.class, strategy);
        }

        @Test
        @DisplayName("returns LocalDeploymentStrategy for null mode (default)")
        void nullMode() {
            DeploymentStrategy strategy = DeploymentStrategy.forMode(null);
            assertInstanceOf(LocalDeploymentStrategy.class, strategy);
        }

        @Test
        @DisplayName("returns LocalDeploymentStrategy for unknown mode")
        void unknownMode() {
            DeploymentStrategy strategy = DeploymentStrategy.forMode("Embedded");
            assertInstanceOf(LocalDeploymentStrategy.class, strategy);
        }

        @Test
        @DisplayName("returns LocalDeploymentStrategy for empty string")
        void emptyMode() {
            DeploymentStrategy strategy = DeploymentStrategy.forMode("");
            assertInstanceOf(LocalDeploymentStrategy.class, strategy);
        }
    }

    @Nested
    @DisplayName("LocalDeploymentStrategy")
    class LocalTests {

        @Test
        @DisplayName("resolveCredentials is inherited no-op (not overridden)")
        void resolveCredentialsIsInheritedNoOp() throws Exception {
            var method = LocalDeploymentStrategy.class.getMethod("resolveCredentials",
                    com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration.class);
            assertEquals(DeploymentStrategy.class, method.getDeclaringClass(),
                    "LocalDeploymentStrategy should NOT override resolveCredentials");
        }
    }

    @Nested
    @DisplayName("RemoteDeploymentStrategy")
    class RemoteTests {

        @Test
        @DisplayName("overrides resolveCredentials")
        void overridesResolveCredentials() throws Exception {
            var method = RemoteDeploymentStrategy.class.getMethod("resolveCredentials",
                    com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration.class);
            assertEquals(RemoteDeploymentStrategy.class, method.getDeclaringClass(),
                    "RemoteDeploymentStrategy must override resolveCredentials");
        }

        @Test
        @DisplayName("resolveCredentials preserves password when remoteConfig has credentials disabled")
        void resolveCredentialsNoOpWhenDisabled() {
            RemoteDeploymentStrategy strategy = new RemoteDeploymentStrategy();

            // RemoteDeploymentStrategy.resolveCredentials reads config.getConfigData().getRemoteConfig()
            // We can't pass a real TomcatRunConfiguration, but we can verify the underlying
            // CredentialResolver behavior that the method delegates to.
            // When useCredentials is false, ensureResolved is a no-op.
            RemoteConfig remoteConfig = new RemoteConfig();
            remoteConfig.setUseCredentials(false);
            remoteConfig.setPassword("existing");

            com.dev.idea.plugins.tomcat.utils.CredentialResolver.ensureResolved(remoteConfig);

            assertEquals("existing", remoteConfig.getPassword(),
                    "Password should be preserved when credentials are not used");
        }

        @Test
        @DisplayName("resolveCredentials preserves password when PasswordSafe is unavailable")
        void resolveCredentialsPreservesPassword() {
            RemoteConfig remoteConfig = new RemoteConfig(
                    "http://localhost:8080/manager", "admin", "mypassword", true);

            // Without IntelliJ Application, PasswordSafe throws — CredentialResolver catches it
            com.dev.idea.plugins.tomcat.utils.CredentialResolver.ensureResolved(remoteConfig);

            assertEquals("mypassword", remoteConfig.getPassword(),
                    "Existing password should be preserved when PasswordSafe is unavailable");
        }
    }
}
