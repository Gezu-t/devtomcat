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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the covering-JAR record form of {@link SyncManifest} and the
 * {@link SyncManifest#dropStaleJarOverlays} pass built on it: overlay classes
 * mirrored into {@code WEB-INF/classes} while an older {@code WEB-INF/lib}
 * JAR covered their dependency must be dropped once that JAR is rebuilt
 * (Tomcat loads {@code WEB-INF/classes} first, so a stale overlay silently
 * shadows the newer JAR) — and must NEVER be dropped on weaker evidence.
 */
@DisplayName("SyncManifest covering-JAR records")
class SyncManifestJarRecordsTest {

    private static final String JAR_REL = "WEB-INF/lib/web-module-lib.jar";
    private static final String OVERLAY_REL = "com/example/dep/Util.class";

    /** Standard fixture: an exploded deployment with one overlay class covered by one lib JAR. */
    private record Fixture(Path artifactRoot, Path classes, Path jar, Path overlay, Path manifest) {}

    private static Fixture fixture(Path tmp) throws IOException {
        Path artifactRoot = Files.createDirectories(tmp.resolve("deploy/app"));
        Path classes = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        Path jar = artifactRoot.resolve(JAR_REL);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-bytes-v1");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));
        Path overlay = classes.resolve(OVERLAY_REL);
        Files.createDirectories(overlay.getParent());
        Files.writeString(overlay, "overlay-class-bytes");
        return new Fixture(artifactRoot, classes, jar, overlay, tmp.resolve("m.manifest"));
    }

    /** Records the fixture's overlay as synced under the JAR's cover — the production sequence. */
    private static void recordCoverage(Fixture f) {
        SyncManifest.reconcile(f.classes(), f.manifest(), Set.of(OVERLAY_REL),
                Map.of(JAR_REL, new SyncManifest.JarCoverage(
                        SyncManifest.stampOf(f.jar()), Set.of(OVERLAY_REL))));
    }

    private static void rebuildJar(Fixture f) throws IOException {
        Files.writeString(f.jar(), "jar-bytes-v2-rebuilt");
        Files.setLastModifiedTime(f.jar(), FileTime.fromMillis(200_000L));
    }

    @Test
    @DisplayName("round-trip: JAR records survive write+read; entry parsing is unaffected")
    void jarRecordRoundTrip(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);

        Map<String, SyncManifest.JarCoverage> records = SyncManifest.readJarRecords(f.manifest());
        assertEquals(Set.of(JAR_REL), records.keySet());
        assertEquals(Set.of(OVERLAY_REL), records.get(JAR_REL).coveredPaths());
        assertEquals(SyncManifest.stampOf(f.jar()), records.get(JAR_REL).stamp());

        // Entry parsing must not see the JAR record lines as paths.
        assertEquals(Set.of(OVERLAY_REL), SyncManifest.read(f.manifest()));
        assertFalse(SyncManifest.readStamped(f.manifest()).get(OVERLAY_REL).isUnknown(),
                "the overlay entry keeps its real stamp");
    }

    @Test
    @DisplayName("old-format manifest (no JAR records) parses to no records and never drops")
    void oldFormatTolerated(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // Pre-existing manifest written by an older version: entries only.
        SyncManifest.reconcile(f.classes(), f.manifest(), Set.of(OVERLAY_REL));
        rebuildJar(f);

        assertTrue(SyncManifest.readJarRecords(f.manifest()).isEmpty());
        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(f.overlay()), "no JAR records — nothing may be dropped");
    }

    @Test
    @DisplayName("drop: a rebuilt (newer-mtime) covering JAR drops its recorded overlay files")
    void dropsWhenJarRebuilt(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // A neighbour file never recorded under the JAR's cover must survive.
        Path neighbour = f.classes().resolve("com/example/web/App.class");
        Files.createDirectories(neighbour.getParent());
        Files.writeString(neighbour, "web-module-own-class");
        recordCoverage(f);
        rebuildJar(f);

        int dropped = SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot());

        assertEquals(1, dropped);
        assertFalse(Files.exists(f.overlay()), "the shadowing overlay must be gone — the newer JAR serves");
        assertTrue(Files.exists(neighbour), "files outside the JAR's coverage are untouched");
        assertTrue(Files.exists(f.jar()));
    }

    @Test
    @DisplayName("drop: size change at identical mtime also counts as rebuilt")
    void dropsOnSizeChangeWithEqualMtime(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);
        // Rebuild that lands within mtime resolution: same mtime, new size.
        Files.writeString(f.jar(), "jar-bytes-v2-different-length");
        Files.setLastModifiedTime(f.jar(), FileTime.fromMillis(100_000L));

        assertEquals(1, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertFalse(Files.exists(f.overlay()));
    }

    @Test
    @DisplayName("no drop when the covering JAR is unchanged")
    void noDropWhenJarUnchanged(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);

        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(f.overlay()));
    }

    @Test
    @DisplayName("no drop when the covering JAR vanished — nothing newer is serving")
    void noDropWhenJarVanished(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);
        Files.delete(f.jar());

        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(f.overlay()));
    }

    @Test
    @DisplayName("never delete when the deployed file no longer matches its recorded stamp")
    void neverDeletesOnStampMismatch(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);
        // Another producer (the build) rewrote the deployed path since we recorded it.
        Files.writeString(f.overlay(), "rewritten-by-the-build");
        Files.setLastModifiedTime(f.overlay(), FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        rebuildJar(f);

        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(f.overlay()), "bytes we did not write are never ours to delete");
    }

    @Test
    @DisplayName("an entry with an unknown stamp is never dropped, even under a rebuilt JAR")
    void neverDeletesUnknownStampEntries(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        // Hand-build a manifest: bare (stampless) entry + a valid JAR record.
        SyncManifest.Stamp jarStamp = SyncManifest.stampOf(f.jar());
        Files.write(f.manifest(), java.util.List.of(
                OVERLAY_REL,
                "#jar\t" + JAR_REL + '\t' + jarStamp.size() + '\t' + jarStamp.mtimeMillis()
                        + '\t' + jarStamp.creationMillis() + '\t' + OVERLAY_REL));
        rebuildJar(f);

        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(f.overlay()),
                "without a stamp we cannot prove we wrote the overlay — preserve it");
    }

    @Test
    @DisplayName("containment: corrupt JAR / overlay record paths never reach outside their trees")
    void refusesOutOfTreeRecords(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        Path outside = tmp.resolve("precious.txt");
        Files.writeString(outside, "must survive");
        SyncManifest.Stamp jarStamp = SyncManifest.stampOf(f.jar());
        SyncManifest.Stamp outsideStamp = SyncManifest.stampOf(outside);
        // From WEB-INF/classes, four ".." segments escape to the temp root.
        String escape = "../../../../precious.txt";
        Files.write(f.manifest(), java.util.List.of(
                // Entry with the REAL stamp of the outside file, escaping the base dir.
                escape + '\t' + outsideStamp.size() + '\t' + outsideStamp.mtimeMillis()
                        + '\t' + outsideStamp.creationMillis(),
                "#jar\t" + JAR_REL + '\t' + jarStamp.size() + '\t' + jarStamp.mtimeMillis()
                        + '\t' + jarStamp.creationMillis() + '\t' + escape));
        rebuildJar(f);

        assertEquals(0, SyncManifest.dropStaleJarOverlays(f.classes(), f.manifest(), f.artifactRoot()));
        assertTrue(Files.exists(outside), "a corrupt record must never delete outside the deployed tree");
    }

    @Test
    @DisplayName("reconcile replaces JAR records; refresh merges them (deferred-run safety)")
    void reconcileReplacesRefreshMerges(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp);
        recordCoverage(f);

        // refresh (deferred run): a second covered path joins; the first must survive the merge.
        String second = "com/example/dep/More.class";
        Files.writeString(f.classes().resolve(second), "more-bytes");
        SyncManifest.Stamp jarStamp = SyncManifest.stampOf(f.jar());
        SyncManifest.refresh(f.classes(), f.manifest(), Set.of(second),
                Map.of(JAR_REL, new SyncManifest.JarCoverage(jarStamp, Set.of(second))));
        assertEquals(Set.of(OVERLAY_REL, second),
                SyncManifest.readJarRecords(f.manifest()).get(JAR_REL).coveredPaths(),
                "refresh must union coverage — losing a link would leave a permanent stale shadow");

        // reconcile (trusted run): this pass's coverage replaces the record outright.
        SyncManifest.reconcile(f.classes(), f.manifest(), Set.of(OVERLAY_REL, second),
                Map.of(JAR_REL, new SyncManifest.JarCoverage(jarStamp, Set.of(second))));
        assertEquals(Set.of(second),
                SyncManifest.readJarRecords(f.manifest()).get(JAR_REL).coveredPaths());
    }
}
