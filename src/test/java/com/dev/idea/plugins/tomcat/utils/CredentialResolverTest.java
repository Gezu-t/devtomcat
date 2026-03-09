package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CredentialResolver")
class CredentialResolverTest {

    @Nested
    @DisplayName("ensureResolved")
    class EnsureResolved {

        @Test
        @DisplayName("skips resolution when useCredentials is false")
        void skipsWhenCredentialsNotUsed() {
            RemoteConfig config = new RemoteConfig();
            config.setUseCredentials(false);
            config.setPassword("original");

            // Should not throw even without Application context, because it returns early
            CredentialResolver.ensureResolved(config);

            assertEquals("original", config.getPassword(), "Password should remain unchanged");
        }

        @Test
        @DisplayName("preserves existing password when PasswordSafe is unavailable")
        void preservesPasswordWhenPasswordSafeUnavailable() {
            RemoteConfig config = new RemoteConfig(
                    "http://localhost:8080/manager", "admin", "secret", true);

            // Without IntelliJ Application context, retrievePassword will throw.
            // CredentialResolver should catch the exception and fall through,
            // leaving the existing password intact.
            CredentialResolver.ensureResolved(config);

            assertEquals("secret", config.getPassword(),
                    "Existing password should be preserved when PasswordSafe is unavailable");
        }

        @Test
        @DisplayName("does not clear password on resolution failure")
        void doesNotClearPasswordOnFailure() {
            RemoteConfig config = new RemoteConfig();
            config.setUseCredentials(true);
            config.setPassword("fallback-password");
            config.setManagerUrl("http://localhost:8080/manager");

            // This will fail gracefully (no Application context in tests)
            CredentialResolver.ensureResolved(config);

            assertEquals("fallback-password", config.getPassword(),
                    "Password must not be cleared on resolution failure");
        }

        @Test
        @DisplayName("handles empty manager URL gracefully")
        void handlesEmptyManagerUrl() {
            RemoteConfig config = new RemoteConfig();
            config.setUseCredentials(true);
            config.setPassword("test");

            // Default manager URL is set by RemoteConfig, but resolution should not crash
            assertDoesNotThrow(() -> CredentialResolver.ensureResolved(config));
            assertEquals("test", config.getPassword());
        }

        @Test
        @DisplayName("handles null password in config gracefully")
        void handlesEmptyPasswordInConfig() {
            RemoteConfig config = new RemoteConfig();
            config.setUseCredentials(true);
            config.setPassword("");

            // Should not throw; password stays empty since PasswordSafe is unavailable
            assertDoesNotThrow(() -> CredentialResolver.ensureResolved(config));
        }
    }
}
