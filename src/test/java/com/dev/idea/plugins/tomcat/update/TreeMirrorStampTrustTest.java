package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorded-stamp fast path: an up-to-date file is recognised from the
 * source walk's own attributes plus one readdir of its destination directory,
 * with no per-file destination stat — and the cases where it must NOT be
 * trusted: destination missing, source changed, stamp unknown.
 */
@DisplayName("TreeMirror recorded-stamp trust")
class TreeMirrorStampTrustTest {

    @TempDir Path tmp;

    private static TreeMirror.Policy policy(Map<String, SyncManifest.Stamp> recorded) {
        return new TreeMirror.Policy("test", Set.of(), null, null, true, Long.MIN_VALUE, recorded);
    }

    private Path src() throws IOException { return Files.createDirectories(tmp.resolve("src")); }
    private Path dst() throws IOException { return Files.createDirectories(tmp.resolve("dst")); }

    @Test
    @DisplayName("a matching recorded stamp skips the file without consulting the destination")
    void trustedStampSkipsWithoutStat() throws IOException {
        Path src = src(), dst = dst();
        Files.writeString(src.resolve("a.txt"), "hello");
        TreeMirror.MirrorResult first = TreeMirror.mirrorTree(src, dst, policy(null));
        assertEquals(1, first.copied());
        assertTrue(first.stamps().containsKey("a.txt"), "the copy must record the destination stamp");

        // Out-of-band change to the destination: different size AND newer mtime —
        // the plain gate would copy on the size mismatch.
        Files.writeString(dst.resolve("a.txt"), "hello world");
        Files.setLastModifiedTime(dst.resolve("a.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        TreeMirror.MirrorResult trusted = TreeMirror.mirrorTree(src, dst, policy(first.stamps()));
        assertEquals(0, trusted.copied(), "recorded stamp matches the source: destination not consulted");
        assertEquals("hello world", Files.readString(dst.resolve("a.txt")));
        assertEquals(first.stamps().get("a.txt"), trusted.stamps().get("a.txt"), "stamp carried, not re-read");

        TreeMirror.MirrorResult gateOnly = TreeMirror.mirrorTree(src, dst, policy(null));
        assertEquals(1, gateOnly.copied(), "without recorded stamps the size mismatch is copied — proves the trust path changed the outcome");
    }

    @Test
    @DisplayName("a missing destination is copied even when the stamp matches (mvn clean survives the manifest)")
    void missingDestinationIsCopiedDespiteMatchingStamp() throws IOException {
        Path src = src(), dst = dst();
        Files.writeString(src.resolve("a.txt"), "hello");
        TreeMirror.MirrorResult first = TreeMirror.mirrorTree(src, dst, policy(null));
        Files.delete(dst.resolve("a.txt"));

        TreeMirror.MirrorResult again = TreeMirror.mirrorTree(src, dst, policy(first.stamps()));
        assertEquals(1, again.copied());
        assertTrue(Files.exists(dst.resolve("a.txt")));
    }

    @Test
    @DisplayName("a changed source is copied and re-stamped")
    void changedSourceIsCopiedAndRestamped() throws IOException {
        Path src = src(), dst = dst();
        Files.writeString(src.resolve("a.txt"), "hello");
        TreeMirror.MirrorResult first = TreeMirror.mirrorTree(src, dst, policy(null));

        Files.writeString(src.resolve("a.txt"), "hello again, longer");
        Files.setLastModifiedTime(src.resolve("a.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        TreeMirror.MirrorResult again = TreeMirror.mirrorTree(src, dst, policy(first.stamps()));
        assertEquals(1, again.copied());
        assertEquals("hello again, longer".length(), again.stamps().get("a.txt").size());
    }

    @Test
    @DisplayName("an unknown recorded stamp falls back to the destination gate")
    void unknownStampFallsBackToGate() throws IOException {
        Path src = src(), dst = dst();
        Files.writeString(src.resolve("a.txt"), "hello");
        TreeMirror.MirrorResult r = TreeMirror.mirrorTree(src, dst, policy(Map.of("a.txt", SyncManifest.Stamp.UNKNOWN)));
        assertEquals(1, r.copied());
        assertFalse(r.stamps().get("a.txt").isUnknown(), "a real stamp is recorded after the copy");
    }

    @Test
    @DisplayName("nested directories are listed per directory, keyed by the source parent")
    void nestedDirectoriesTrustPerDirectory() throws IOException {
        Path src = src(), dst = dst();
        Files.createDirectories(src.resolve("p/q"));
        Files.writeString(src.resolve("p/q/b.txt"), "deep");
        TreeMirror.MirrorResult first = TreeMirror.mirrorTree(src, dst, policy(null));
        assertEquals(1, first.copied());

        Files.writeString(dst.resolve("p/q/b.txt"), "deep-modified");
        Files.setLastModifiedTime(dst.resolve("p/q/b.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        TreeMirror.MirrorResult trusted = TreeMirror.mirrorTree(src, dst, policy(first.stamps()));
        assertEquals(0, trusted.copied());
    }
}
