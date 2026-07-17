package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link SyncManifestStore} — manifests keyed by deployment directory,
 * stored outside the user's project, with transparent adoption of legacy
 * in-webapp manifests.
 */
class SyncManifestStoreTest {

    @Test
    @DisplayName("resolution is deterministic: same deployment dir → same store file")
    void deterministic(@TempDir Path root) {
        Path key = Path.of("/projects/x/target/app-1.0");
        assertEquals(SyncManifestStore.resolve(root, "classsync", key),
                SyncManifestStore.resolve(root, "classsync", key));
    }

    @Test
    @DisplayName("distinct deployment dirs and kinds resolve to distinct files")
    void distinct(@TempDir Path root) {
        Path a = Path.of("/projects/x/target/app-1.0");
        Path b = Path.of("/projects/y/target/app-1.0"); // same dir NAME, different path
        assertNotEquals(SyncManifestStore.resolve(root, "classsync", a),
                SyncManifestStore.resolve(root, "classsync", b));
        assertNotEquals(SyncManifestStore.resolve(root, "classsync", a),
                SyncManifestStore.resolve(root, "webresources", a));
    }

    @Test
    @DisplayName("store file never lives under the deployment dir")
    void outsideDeployment(@TempDir Path root) {
        Path key = Path.of("/projects/x/target/app-1.0");
        assertFalse(SyncManifestStore.resolve(root, "classsync", key).startsWith(key));
    }

    @Test
    @DisplayName("migration: legacy manifest moves into an empty store slot and is deleted")
    void migratesLegacy(@TempDir Path tmp) throws Exception {
        Path storeRoot = tmp.resolve("store");
        SyncManifestStore.setRootOverride(storeRoot);
        try {
            Path key = Files.createDirectories(tmp.resolve("app/WEB-INF/classes"));
            Path legacy = tmp.resolve("app/WEB-INF/.legacy.manifest");
            Files.writeString(legacy, "com/a/A.class");

            Path store = SyncManifestStore.resolveWithMigration("classsync", key, legacy);

            assertFalse(Files.exists(legacy), "legacy file removed from the webapp");
            assertEquals("com/a/A.class", Files.readString(store).trim(), "entries preserved");
        } finally {
            SyncManifestStore.setRootOverride(null);
        }
    }

    @Test
    @DisplayName("migration: an existing store manifest wins; the legacy file is just deleted")
    void existingStoreWins(@TempDir Path tmp) throws Exception {
        Path storeRoot = tmp.resolve("store");
        SyncManifestStore.setRootOverride(storeRoot);
        try {
            Path key = Files.createDirectories(tmp.resolve("app/WEB-INF/classes"));
            Path store = SyncManifestStore.resolve(storeRoot, "classsync", key);
            Files.createDirectories(store.getParent());
            Files.writeString(store, "com/current/B.class");
            Path legacy = tmp.resolve("app/WEB-INF/.legacy.manifest");
            Files.writeString(legacy, "com/old/A.class");

            Path resolved = SyncManifestStore.resolveWithMigration("classsync", key, legacy);

            assertEquals(store, resolved);
            assertFalse(Files.exists(legacy), "legacy file still cleaned up");
            assertEquals("com/current/B.class", Files.readString(store).trim(),
                    "newer store content is not clobbered by the stale legacy file");
        } finally {
            SyncManifestStore.setRootOverride(null);
        }
    }

    @Test
    @DisplayName("no legacy file: resolution is pure, nothing is created")
    void noLegacyNoSideEffects(@TempDir Path tmp) throws Exception {
        Path storeRoot = tmp.resolve("store");
        SyncManifestStore.setRootOverride(storeRoot);
        try {
            Path key = Files.createDirectories(tmp.resolve("app/WEB-INF/classes"));
            Path store = SyncManifestStore.resolveWithMigration(
                    "classsync", key, tmp.resolve("app/WEB-INF/.absent.manifest"));
            assertFalse(Files.exists(store), "resolution alone must not create files");
        } finally {
            SyncManifestStore.setRootOverride(null);
        }
    }
}
