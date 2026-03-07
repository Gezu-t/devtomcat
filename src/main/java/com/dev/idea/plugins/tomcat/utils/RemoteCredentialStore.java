package com.dev.idea.plugins.tomcat.utils;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Stores and retrieves Tomcat Manager credentials via IntelliJ's {@link PasswordSafe}.
 * Passwords are never written to XML configuration files.
 */
public final class RemoteCredentialStore {

    private static final Logger LOG = Logger.getInstance(RemoteCredentialStore.class);
    private static final String SERVICE_NAME = "DevTomcat-RemoteManager";

    private RemoteCredentialStore() {}

    /**
     * Schedules password storage on a pooled thread to avoid blocking read actions.
     * Safe to call from writeExternal (which runs inside a read action).
     * All {@link PasswordSafe} access is deferred to the pooled thread to avoid
     * {@code SlowOperations} assertions when called from a read action.
     *
     * @return true if the store was scheduled
     */
    public static boolean storePassword(@NotNull String managerUrl, String password) {
        try {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    PasswordSafe safe = PasswordSafe.getInstance();
                    CredentialAttributes attributes = createAttributes(managerUrl);
                    safe.set(attributes, new Credentials(managerUrl, password));
                } catch (Exception e) {
                    LOG.warn("Failed to store password in credential store", e);
                }
            });
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to schedule password storage", e);
            return false;
        }
    }

    /**
     * Retrieves the stored password for the given manager URL.
     * Must NOT be called inside a read action — {@link PasswordSafe#get} performs
     * slow I/O that triggers {@code SlowOperations} assertions.
     * Callers should use {@link ApplicationManager#executeOnPooledThread} first.
     *
     * @return the password, or empty string if not found
     */
    @NotNull
    public static String retrievePassword(@NotNull String managerUrl) {
        try {
            PasswordSafe safe = PasswordSafe.getInstance();
            CredentialAttributes attributes = createAttributes(managerUrl);
            Credentials credentials = safe.get(attributes);
            if (credentials != null && credentials.getPasswordAsString() != null) {
                return credentials.getPasswordAsString();
            }
        } catch (Exception e) {
            LOG.warn("Failed to retrieve password from credential store", e);
        }
        return "";
    }

    /**
     * Removes the stored password for the given manager URL.
     * All {@link PasswordSafe} access is deferred to a pooled thread.
     */
    public static void removePassword(@NotNull String managerUrl) {
        try {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    PasswordSafe safe = PasswordSafe.getInstance();
                    CredentialAttributes attributes = createAttributes(managerUrl);
                    safe.set(attributes, null);
                } catch (Exception e) {
                    LOG.debug("Failed to remove password from credential store", e);
                }
            });
        } catch (Exception e) {
            LOG.debug("Failed to schedule password removal", e);
        }
    }

    @NotNull
    private static CredentialAttributes createAttributes(@NotNull String managerUrl) {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(SERVICE_NAME, managerUrl)
        );
    }
}
