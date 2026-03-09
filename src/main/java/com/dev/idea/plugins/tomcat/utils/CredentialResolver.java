package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Ensures remote credentials are fully resolved before use on the execution path.
 *
 * <p>The serializer loads credentials asynchronously (PasswordSafe is slow I/O,
 * prohibited inside read actions). This class provides a synchronous resolution
 * point that must be called before any remote deployment attempt.
 *
 * <p>If PasswordSafe has a credential stored for the manager URL, it overwrites
 * whatever the async deserializer loaded (which may still be the legacy XML value
 * or empty if the async thread hasn't finished).
 */
public final class CredentialResolver {

    private static final Logger LOG = Logger.getInstance(CredentialResolver.class);

    private CredentialResolver() {}

    /**
     * Synchronously resolves credentials for the given remote config.
     * Must be called outside a read action (e.g., on the execution thread
     * in {@code TomcatCommandLineState.startProcess()}).
     *
     * <p>If PasswordSafe contains a password for the manager URL, it is
     * set on the config, overriding any stale or missing async value.
     *
     * @param remoteConfig the remote configuration whose password to resolve
     */
    public static void ensureResolved(@NotNull RemoteConfig remoteConfig) {
        if (!remoteConfig.isUseCredentials()) {
            return;
        }

        String managerUrl = remoteConfig.getManagerUrl();
        try {
            String safePassword = RemoteCredentialStore.retrievePassword(managerUrl);
            if (!safePassword.isEmpty()) {
                remoteConfig.setPassword(safePassword);
                LOG.debug("Credential resolved from PasswordSafe for: " + managerUrl);
            } else if (remoteConfig.getPassword().isEmpty()) {
                LOG.warn("No credential found in PasswordSafe or config for: " + managerUrl);
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve credential for: " + managerUrl, e);
            // Fall through — whatever password is on the config (legacy or empty) will be used
        }
    }
}
