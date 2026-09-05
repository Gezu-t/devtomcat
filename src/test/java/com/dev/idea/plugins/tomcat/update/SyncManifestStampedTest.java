package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stamped reconcile: known stamps are not re-statted, and an unchanged manifest is not rewritten. */
@DisplayName("SyncManifest stamped reconcile")
class SyncManifestStampedTest {

    @TempDir Path tmp;

    private static SyncManifest.Stamp stampOf(Path file) throws IOException {
        return SyncManifest.stampOf(Files.readAttributes(file, BasicFileAttributes.class));
    }

    @Test
    @DisplayName("a no-op reconcile does not rewrite the manifest")
    void noOpDoesNotRewrite() throws IOException {
        Path base = Files.createDirectories(tmp.resolve("base"));
        Path manifest = tmp.resolve("store").resolve("m.manifest");
        Files.writeString(base.resolve("a.txt"), "x");
        Map<String, SyncManifest.Stamp> stamps = Map.of("a.txt", stampOf(base.resolve("a.txt")));

        assertTrue(SyncManifest.reconcileStamped(base, manifest, stamps, Map.of()).manifestWritten(), "first write");

        Files.setLastModifiedTime(manifest, FileTime.fromMillis(1_000_000L));
        SyncManifest.ReconcileResult again = SyncManifest.reconcileStamped(base, manifest, stamps, Map.of());
        assertFalse(again.manifestWritten());
        assertEquals(1_000_000L, Files.getLastModifiedTime(manifest).toMillis(), "file really untouched");

        Files.writeString(base.resolve("a.txt"), "xy");
        Map<String, SyncManifest.Stamp> changed = Map.of("a.txt", stampOf(base.resolve("a.txt")));
        assertTrue(SyncManifest.reconcileStamped(base, manifest, changed, Map.of()).manifestWritten());
    }

    @Test
    @DisplayName("unknown stamps are statted, so the manifest still records real stamps")
    void unknownStampsAreStatted() throws IOException {
        Path base = Files.createDirectories(tmp.resolve("base"));
        Path manifest = tmp.resolve("m.manifest");
        Files.writeString(base.resolve("a.txt"), "xyz");
        Map<String, SyncManifest.Stamp> unknown = new HashMap<>();
        unknown.put("a.txt", SyncManifest.Stamp.UNKNOWN);

        SyncManifest.reconcileStamped(base, manifest, unknown, Map.of());
        assertEquals(3, SyncManifest.readStamped(manifest).get("a.txt").size());
    }

    @Test
    @DisplayName("removing an orphan forces a write")
    void orphanRemovalForcesWrite() throws IOException {
        Path base = Files.createDirectories(tmp.resolve("base"));
        Path manifest = tmp.resolve("m.manifest");
        Files.writeString(base.resolve("a.txt"), "a");
        Files.writeString(base.resolve("b.txt"), "b");
        Map<String, SyncManifest.Stamp> both = Map.of("a.txt", stampOf(base.resolve("a.txt")), "b.txt", stampOf(base.resolve("b.txt")));
        SyncManifest.reconcileStamped(base, manifest, both, Map.of());

        SyncManifest.ReconcileResult r = SyncManifest.reconcileStamped(base, manifest, Map.of("a.txt", both.get("a.txt")), Map.of());
        assertEquals(1, r.removed());
        assertTrue(r.manifestWritten());
        assertFalse(Files.exists(base.resolve("b.txt")));
    }
}
