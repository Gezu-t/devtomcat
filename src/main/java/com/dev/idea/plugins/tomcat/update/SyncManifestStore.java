package com.dev.idea.plugins.tomcat.update;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Resolves where a deployment's sync manifest lives: under the IDE system
 * directory ({@code {system}/devtomcat/sync-manifests/}), <em>never</em> inside
 * the user's project or webapp.
 *
 * <p>Earlier versions stored the manifests inside the deployed webapp
 * ({@code WEB-INF/.devtomcat-*.manifest}) — plugin bookkeeping visibly leaking
 * into the user's artifact, and (before the source-tree guard) even into
 * {@code src/main/webapp}. The store keys a manifest by the deployment
 * directory's absolute path, so every launch of any config resolves the same
 * file for the same deployment without touching the artifact.
 *
 * <p>{@link #resolveWithMigration} transparently adopts a legacy in-webapp
 * manifest on first contact — its entries are moved into the store and the
 * legacy file is deleted — so reconcile history survives the relocation and
 * the webapp is cleaned in the same step.
 */
final class SyncManifestStore {

    private static final Logger LOG = Logger.getInstance(SyncManifestStore.class);

    private static final String STORE_DIR = "sync-manifests";
    /** Same root name as the CATALINA_BASE store — all DevTomcat runtime data in one place. */
    private static final String SYSTEM_DIR_NAME = "devtomcat";

    @Nullable private static volatile Path rootOverride;

    private SyncManifestStore() {}

    /**
     * The store path for {@code kind} ("classsync" / "webresources") keyed by
     * {@code deploymentKey} (the directory the sync writes into), after adopting
     * {@code legacyManifest} if one still exists there. Never throws; a failed
     * migration leaves the legacy file for the next attempt and returns the
     * store path regardless (a fresh manifest baseline deletes nothing — the
     * safe direction).
     */
    @NotNull
    static Path resolveWithMigration(@NotNull String kind,
                                     @NotNull Path deploymentKey,
                                     @NotNull Path legacyManifest) {
        Path store = resolve(root(), kind, deploymentKey);
        try {
            if (Files.isRegularFile(legacyManifest)) {
                Path parent = store.getParent();
                if (parent != null) Files.createDirectories(parent);
                if (!Files.exists(store)) {
                    try {
                        Files.move(legacyManifest, store);
                    } catch (IOException moveFailed) {
                        Files.copy(legacyManifest, store, StandardCopyOption.REPLACE_EXISTING);
                        Files.delete(legacyManifest);
                    }
                    LOG.info("Sync manifest migrated out of the webapp: "
                            + legacyManifest + " -> " + store);
                } else {
                    // The store already has a (newer) record — the stale legacy
                    // file is simply removed from the webapp.
                    Files.delete(legacyManifest);
                    LOG.info("Removed stale legacy sync manifest from the webapp: " + legacyManifest);
                }
            }
        } catch (IOException e) {
            // Best-effort: the legacy file stays for a later attempt; syncing
            // against a fresh store baseline deletes nothing (the safe direction).
            LOG.debug("Sync manifest migration failed for " + legacyManifest
                    + " (" + e.getMessage() + ")");
        }
        return store;
    }

    /**
     * Pure resolution — {@code <root>/<stem>-<hash16>.<kind>.manifest}. The stem
     * (deployment dir name, sanitized) is for human debuggability; the hash of
     * the absolute normalized path is the identity.
     */
    @NotNull
    static Path resolve(@NotNull Path root, @NotNull String kind, @NotNull Path deploymentKey) {
        Path abs = deploymentKey.toAbsolutePath().normalize();
        Path fileName = abs.getFileName();
        String stem = sanitize(fileName != null ? fileName.toString() : "root");
        return root.resolve(stem + "-" + sha1Hex16(abs.toString()) + "." + kind + ".manifest");
    }

    @NotNull
    private static Path root() {
        Path override = rootOverride;
        if (override != null) return override;
        return Path.of(PathManager.getSystemPath(), SYSTEM_DIR_NAME, STORE_DIR);
    }

    /** Tests must redirect the store away from the real IDE system directory. */
    @TestOnly
    static void setRootOverride(@Nullable Path root) {
        rootOverride = root;
    }

    @NotNull
    private static String sanitize(@NotNull String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "dir" : cleaned;
    }

    @NotNull
    private static String sha1Hex16(@NotNull String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandated by the JCA spec; unreachable on a compliant JRE.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
