package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@link DeploymentStrategy} interface and its sole
 * implementation, {@link LocalDeploymentStrategy}.
 *
 * <p>The previous remote-strategy variants and the {@code forMode} factory
 * were removed when remote-mode launches stopped forking a local JVM —
 * remote configurations now route through
 * {@link RemoteDeploymentRunProfileState} entirely, never building Java
 * parameters. The strategy seam is preserved for any future deployment
 * mode that still launches a JVM, but for now there is only one impl.
 */
@DisplayName("DeploymentStrategy")
class DeploymentStrategyTest {

    @Nested
    @DisplayName("LocalDeploymentStrategy")
    class LocalTests {

        @Test
        @DisplayName("resolveCredentials is inherited no-op (not overridden)")
        void resolveCredentialsIsInheritedNoOp() throws Exception {
            var method = LocalDeploymentStrategy.class.getMethod("resolveCredentials",
                    com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration.class);
            assertEquals(DeploymentStrategy.class, method.getDeclaringClass(),
                    "LocalDeploymentStrategy should NOT override resolveCredentials —"
                            + " the local JVM doesn't need PasswordSafe lookups");
        }
    }
}
