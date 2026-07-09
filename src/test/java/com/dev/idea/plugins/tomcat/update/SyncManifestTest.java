package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the safety guards of {@link SyncManifest#reconcile} that both sync
 * pipelines ({@link DeployedClassesSync}, {@link WebResourcesSync}) rely on.
 * The core reconcile semantics (delete only previously-manifested paths,
 * first-run baseline, idempotence, round-trip) are pinned by the pipeline
 * scenario tests; this class covers the manifest-integrity guards: a corrupt
 * or hand-edited manifest must never let the reconcile delete outside the
 * deployed tree or through a symlink.
 */
@DisplayName("SyncManifest safety guards")
class SyncManifestTest {

    /**
     * A manifest line carrying {@code rel} with the REAL current stamp of
     * {@code file} — so in the test the guard under scrutiny is the ONLY
     * thing standing between the entry and deletion (a bare entry would be
     * saved by the unknown-stamp rule instead, making the test vacuous).
     */
    private static String stampedLine(Path file, String rel) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attrs =
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        return rel + '\t' + attrs.size()
                + '\t' + attrs.lastModifiedTime().toMillis()
                + '\t' + attrs.creationTime().toMillis();
    }

    @Test
    @DisplayName("containment — a manifest entry escaping the base dir via .. is never deleted")
    void refusesPathTraversalEntries(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path outside = tmp.resolve("deploy/precious.txt");
        Files.writeString(outside, "must survive");
        Path manifest = tmp.resolve("m.manifest");
        // Corrupt/hand-edited manifest pointing above the deployed tree, with
        // a MATCHING stamp — only the containment guard prevents deletion.
        Files.write(manifest, java.util.List.of(stampedLine(outside, "../precious.txt")));

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed, "an out-of-tree entry must be refused, not deleted");
        assertTrue(Files.exists(outside), "file outside the deployed tree must survive");
    }

    @Test
    @DisplayName("containment — an absolute-path-shaped entry cannot reach outside the base dir")
    void refusesAbsoluteEntries(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path outside = tmp.resolve("elsewhere.txt");
        Files.writeString(outside, "must survive");
        Path manifest = tmp.resolve("m.manifest");
        Files.write(manifest, java.util.List.of(
                stampedLine(outside, outside.toAbsolutePath().toString())));

        SyncManifest.reconcile(base, manifest, Set.of());

        assertTrue(Files.exists(outside),
                "an absolute manifest entry outside the base must never be deleted");
    }

    @Test
    @DisplayName("symlink at a manifested path is never deleted — someone else owns that path now")
    void neverDeletesSymlinks(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path realTarget = tmp.resolve("real.txt");
        Files.writeString(realTarget, "linked-to");
        Path link = base.resolve("stale.txt");
        try {
            Files.createSymbolicLink(link, realTarget);
        } catch (IOException | UnsupportedOperationException e) {
            // Filesystem without symlink support (or no privilege on Windows
            // CI) — the guard cannot be exercised here; nothing to pin.
            return;
        }
        Path manifest = tmp.resolve("m.manifest");
        // Run 1 records the entry with a real stamp (stat follows the link to
        // the target) — so on run 2 the stamp MATCHES and only the symlink
        // guard prevents deletion.
        SyncManifest.reconcile(base, manifest, Set.of("stale.txt"));

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed, "the sync never writes symlinks, so one at a manifested"
                + " path means another owner — never delete it");
        assertTrue(Files.isSymbolicLink(link), "symlink must survive");
        assertTrue(Files.exists(realTarget));
    }

    @Test
    @DisplayName("case-only rename never deletes the freshly-synced file (case-insensitive FS)")
    void caseRenameSafe(@TempDir Path tmp) throws IOException {
        // User renames Index.jsp -> index.jsp. Old manifest says Index.jsp,
        // current sync says index.jsp. On a case-insensitive filesystem
        // (macOS, Windows) those are the SAME physical file — deleting the
        // "stale" old spelling would delete the fresh copy. On a
        // case-sensitive filesystem the old spelling is a genuinely distinct
        // stale file and must still be cleaned.
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path oldSpelling = base.resolve("Index.jsp");
        Files.writeString(oldSpelling, "fresh content");
        boolean caseInsensitiveFs = Files.exists(base.resolve("index.jsp"));
        if (!caseInsensitiveFs) {
            // Case-sensitive: materialize the new spelling as its own file.
            Files.writeString(base.resolve("index.jsp"), "fresh content");
        }
        Path manifest = tmp.resolve("m.manifest");
        // Run 1 (production sequence) records the old spelling with its stamp.
        SyncManifest.reconcile(base, manifest, Set.of("Index.jsp"));

        int removed = SyncManifest.reconcile(base, manifest, Set.of("index.jsp"));

        assertTrue(Files.exists(base.resolve("index.jsp")),
                "the freshly-synced spelling must always survive");
        if (caseInsensitiveFs) {
            assertEquals(0, removed,
                    "same physical file under the old spelling must be preserved");
        } else {
            assertEquals(1, removed,
                    "on a case-sensitive FS the old spelling is a distinct stale file");
            assertFalse(Files.exists(oldSpelling));
        }
        assertEquals(Set.of("index.jsp"), SyncManifest.read(manifest),
                "the manifest records the new spelling either way");
    }

    @Test
    @DisplayName("read is the exact inverse of write: empty lines skipped, names never trimmed")
    void readIsExactInverseOfWrite(@TempDir Path tmp) throws IOException {
        // Trimming on read would alias a whitespace-edged filename to a
        // DIFFERENT deployed file — which reconcile would then delete. Only
        // genuinely empty lines are skipped; a name is preserved verbatim.
        Path manifest = tmp.resolve("m.manifest");
        Files.write(manifest, java.util.List.of("a/B.txt", "", " space.jsp", "c/D.txt"));

        assertEquals(Set.of("a/B.txt", " space.jsp", "c/D.txt"), SyncManifest.read(manifest));

        // Round-trip identity for a whitespace-edged name through write() too.
        SyncManifest.write(manifest, Set.of(" space.jsp"));
        assertEquals(Set.of(" space.jsp"), SyncManifest.read(manifest));
    }

    @Test
    @DisplayName("a whitespace-edged synced name never aliases (and deletes) the build's twin file")
    void whitespaceEdgedNameNeverAliasesTwin(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Files.writeString(base.resolve(" space.jsp"), "synced-by-us");   // leading space
        Files.writeString(base.resolve("space.jsp"), "build-produced");  // the twin
        Path manifest = tmp.resolve("m.manifest");
        // Run 1 records the exact spelling " space.jsp".
        SyncManifest.reconcile(base, manifest, Set.of(" space.jsp"));

        // Run 2: still synced — nothing may be deleted, especially not the twin.
        assertEquals(0, SyncManifest.reconcile(base, manifest, Set.of(" space.jsp")));
        assertTrue(Files.exists(base.resolve("space.jsp")), "the build's twin must survive");

        // Run 3: the synced file left the source — the EXACT file is cleaned,
        // the twin still survives.
        int removed = SyncManifest.reconcile(base, manifest, Set.of());
        assertEquals(1, removed);
        assertFalse(Files.exists(base.resolve(" space.jsp")));
        assertTrue(Files.exists(base.resolve("space.jsp")),
                "cleanup must target the recorded spelling, never its trimmed alias");
    }

    @Test
    @DisplayName("a file rewritten by another producer at a formerly-synced path is preserved (stamp mismatch)")
    void rewrittenFilePreserved(@TempDir Path tmp) throws IOException {
        // Path membership alone is not proof of ownership: a path we once
        // synced can later be produced by the build (descriptor migrated to a
        // filtered <webResources> dir, generated file). The recorded
        // size+mtime stamp no longer matches the rewritten file — preserve it.
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path file = base.resolve("WEB-INF/web.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<web-app/>");
        Path manifest = tmp.resolve("m.manifest");
        SyncManifest.reconcile(base, manifest, Set.of("WEB-INF/web.xml"));

        // The build rewrites the file (new content, new mtime).
        Files.writeString(file, "<web-app><servlet/></web-app>");
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed, "a rewritten file is another producer's now — never ours to delete");
        assertTrue(Files.exists(file));
        assertTrue(SyncManifest.read(manifest).isEmpty(),
                "the entry still leaves the manifest — ownership ended");
    }

    @Test
    @DisplayName("a REPLACED file with identical size+mtime is preserved (creation time betrays the new copy)")
    void replacedFilePreservedViaCreationTime(@TempDir Path tmp) throws IOException {
        // Build copiers (Maven resources, Gradle Copy) preserve the source's
        // mtime — as does this sync — so a file migrated unmodified into a
        // build-owned dir re-lands with the identical size+mtime. But the
        // copier necessarily creates a NEW file: creation time differs, and
        // the stamp gate must preserve it.
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path file = base.resolve("asset.css");
        Files.writeString(file, "body{}");
        java.nio.file.attribute.FileTime originalMtime = Files.getLastModifiedTime(file);
        long originalCreation = Files.readAttributes(
                file, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
        Path manifest = tmp.resolve("m.manifest");
        SyncManifest.reconcile(base, manifest, Set.of("asset.css"));

        // "Build re-deploys it": new file, same bytes, mtime restored.
        Files.delete(file);
        try { Thread.sleep(15); } catch (InterruptedException ignored) { }
        Files.writeString(file, "body{}");
        Files.setLastModifiedTime(file, originalMtime);
        long newCreation = Files.readAttributes(
                file, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
        org.junit.jupiter.api.Assumptions.assumeTrue(newCreation != originalCreation,
                "filesystem must expose a real creation time to exercise this guard");

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed,
                "a replacement copy is another producer's file — size+mtime parity must not fool us");
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("refresh() re-stamps contributed paths on deferred runs so cleanup stays possible later")
    void refreshKeepsCleanupPossible(@TempDir Path tmp) throws IOException {
        // A deferred run (walk failure elsewhere) skips reconcile, but the
        // mirror already re-copied edited files. Without a stamp refresh the
        // manifest keeps the OLD stamp, and once the file leaves the source
        // the mismatch preserves it FOREVER — a permanent stale-file leak.
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Files.writeString(base.resolve("x.jsp"), "v1");
        Files.writeString(base.resolve("y.jsp"), "steady");
        Path manifest = tmp.resolve("m.manifest");
        SyncManifest.reconcile(base, manifest, Set.of("x.jsp", "y.jsp"));

        // Deferred run: the mirror re-copied an edited x.jsp (new size+mtime),
        // then the caller could only refresh — no deletions allowed.
        Files.writeString(base.resolve("x.jsp"), "v2 — edited during the deferred run");
        Files.setLastModifiedTime(base.resolve("x.jsp"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        SyncManifest.refresh(base, manifest, Set.of("x.jsp"));
        assertTrue(Files.exists(base.resolve("x.jsp")), "refresh never deletes");
        assertEquals(Set.of("x.jsp", "y.jsp"), SyncManifest.read(manifest),
                "refresh keeps prior entries it didn't touch");

        // Clean run after the user removed both from source: BOTH must be
        // cleanable — x.jsp thanks to the refreshed stamp.
        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(2, removed, "the file edited during the deferred run must still be cleanable");
        assertFalse(Files.exists(base.resolve("x.jsp")));
        assertFalse(Files.exists(base.resolve("y.jsp")));
    }

    @Test
    @DisplayName("an entry with an unknown stamp (bare-seeded / unparseable line) is never deleted")
    void unknownStampNeverDeleted(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Files.writeString(base.resolve("page.jsp"), "content");
        Path manifest = tmp.resolve("m.manifest");
        // Bare seeding (no stamps) — known path, unverifiable provenance.
        SyncManifest.write(manifest, Set.of("page.jsp"));

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed,
                "without a stamp we cannot prove we wrote the file — preserve it");
        assertTrue(Files.exists(base.resolve("page.jsp")));
    }

    @Test
    @DisplayName("a manifest entry whose file already vanished is skipped without error")
    void toleratesVanishedEntries(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("deploy/app"));
        Path manifest = tmp.resolve("m.manifest");
        SyncManifest.write(manifest, Set.of("already/gone.txt"));

        int removed = SyncManifest.reconcile(base, manifest, Set.of());

        assertEquals(0, removed, "a vanished entry is not an error and not counted");
        assertEquals(Set.of(), SyncManifest.read(manifest),
                "the new (empty) synced set replaces the stale manifest");
    }
}
