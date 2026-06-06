package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end scenario coverage for {@link DeployedClassesSync#mirrorTree}.
 *
 * <p>Unit tests in {@code DeployedClassesSyncTest} pin individual contracts
 * ({@code shouldCopy}, {@code isBrokenEcjClass}, {@code SyncReport} record).
 * This suite drives the mirror under realistic workflows — what the user
 * actually does on a project — and asserts on the final filesystem state.
 * Together the two suites cover both the contracts and the integration
 * between them, without needing a live IntelliJ Project fixture.
 *
 * <p>Each scenario has the shape:
 * <ol>
 *   <li>Set up a source tree (simulating a module's {@code target/classes/}
 *       or {@code out/production/}).</li>
 *   <li>Set up a destination tree (simulating a deployed
 *       {@code WEB-INF/classes/}).</li>
 *   <li>Set explicit mtimes so the gate behaviour is deterministic
 *       (filesystem mtime resolution varies — pinning makes the test
 *       hermetic).</li>
 *   <li>Call {@link DeployedClassesSync#mirrorTree}.</li>
 *   <li>Assert on copied counts, brokenSkipped counts, and the resulting
 *       byte-for-byte contents of the destination.</li>
 * </ol>
 */
class DeployedClassesSyncScenariosTest {

    /**
     * ECJ "compile-with-errors" stub payload. The detector only cares that
     * the file ends in {@code .class} and contains the literal string
     * {@code Unresolved compilation problems} in its bytes — so a synthetic
     * payload with a plausible class-file magic header followed by that
     * marker is enough to drive the detector reliably.
     */
    private static byte[] ecjBrokenPayload() {
        byte[] header = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0, 0, 0x3D, 0, 10};
        byte[] marker = ("Unresolved compilation problems: \n"
                + "\tThe import net.bull cannot be resolved\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[header.length + marker.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(marker, 0, payload, header.length, marker.length);
        return payload;
    }

    // -----------------------------------------------------------------
    // Scenario 1: Cold start — destination empty, source full → mirror copies everything
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 1 — cold start: empty dst, populated src → mirrors everything")
    void scenario01_coldStart(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "com/foo/Service.class", "service-bytes");
        writeClass(src, "com/foo/Controller.class", "controller-bytes");
        writeClass(src, "com/foo/util/Helper.class", "helper-bytes");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(3, r.copied(), "all three classes must copy on cold start");
        assertEquals(0, r.brokenSkipped());
        assertFileContent(dst.resolve("com/foo/Service.class"), "service-bytes");
        assertFileContent(dst.resolve("com/foo/Controller.class"), "controller-bytes");
        assertFileContent(dst.resolve("com/foo/util/Helper.class"), "helper-bytes");
    }

    // -----------------------------------------------------------------
    // Scenario 2: Steady state — dst already has every file at matching mtime/size → zero copies
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 2 — steady state: identical src and dst → no copies")
    void scenario02_steadyState(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "A.class", "v1");
        writeClass(dst, "A.class", "v1");
        // Pin mtimes equal so the gate sees parity.
        Files.setLastModifiedTime(src.resolve("A.class"), FileTime.fromMillis(5_000L));
        Files.setLastModifiedTime(dst.resolve("A.class"), FileTime.fromMillis(5_000L));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied(), "steady-state sync must copy nothing");
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 3: Single-file edit — one of many sources is newer → only that one copies
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 3 — single edit: one file newer in src → only that one copies")
    void scenario03_singleEdit(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "A.class", "old-A");
        writeClass(src, "B.class", "EDITED-B");
        writeClass(dst, "A.class", "old-A");
        writeClass(dst, "B.class", "old-B");

        // A is steady; B is edited (src newer).
        Files.setLastModifiedTime(src.resolve("A.class"), FileTime.fromMillis(5_000L));
        Files.setLastModifiedTime(dst.resolve("A.class"), FileTime.fromMillis(5_000L));
        Files.setLastModifiedTime(src.resolve("B.class"), FileTime.fromMillis(10_000L));
        Files.setLastModifiedTime(dst.resolve("B.class"), FileTime.fromMillis(5_000L));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "exactly one file must copy — the edited one");
        assertEquals(0, r.brokenSkipped());
        assertFileContent(dst.resolve("A.class"), "old-A");
        assertFileContent(dst.resolve("B.class"), "EDITED-B");
    }

    // -----------------------------------------------------------------
    // Scenario 4: ECJ broken-class refusal — broken stub in src is NEVER copied
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 4 — ECJ broken class in src: refuses copy, leaves working dst")
    void scenario04_ecjBrokenSkipped(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Working classes alongside the broken one.
        writeClass(src, "Good.class", "fresh-good");
        writeRaw(src.resolve("BrokenConfig.class"), ecjBrokenPayload());

        // Destination has the working pre-existing copy of BrokenConfig (e.g.
        // from a prior mvn install). The mirror must NOT replace it.
        writeClass(dst, "BrokenConfig.class", "working-config-from-mvn");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "only Good.class should copy");
        assertEquals(1, r.brokenSkipped(), "BrokenConfig.class must be flagged as broken");
        assertFileContent(dst.resolve("Good.class"), "fresh-good");
        // Critically: the previously-working deployed copy is preserved.
        assertFileContent(dst.resolve("BrokenConfig.class"), "working-config-from-mvn");
    }

    // -----------------------------------------------------------------
    // Scenario 5: ECJ broken class with NO working dst — still refused, no broken stub deployed
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 5 — ECJ broken with no dst: still refused (avoids first-deploy poison)")
    void scenario05_ecjBrokenNoDst(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeRaw(src.resolve("Broken.class"), ecjBrokenPayload());

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied());
        assertEquals(1, r.brokenSkipped());
        assertFalse(Files.exists(dst.resolve("Broken.class")),
                "first-deploy of a broken stub must be refused too — otherwise Tomcat startup fails");
    }

    // -----------------------------------------------------------------
    // Scenario 6: Mixed working + broken classes
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 6 — mixed batch: working classes mirror, broken classes refused")
    void scenario06_mixedBatch(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "pkg/Working1.class", "w1");
        writeClass(src, "pkg/Working2.class", "w2");
        writeRaw(src.resolve("pkg/Broken1.class"),
                ecjBrokenPayload());
        writeRaw(src.resolve("pkg/Broken2.class"),
                ecjBrokenPayload());
        writeClass(src, "pkg/Working3.class", "w3");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(3, r.copied(), "three working classes must mirror");
        assertEquals(2, r.brokenSkipped(), "two broken stubs must be refused");
        assertFileContent(dst.resolve("pkg/Working1.class"), "w1");
        assertFileContent(dst.resolve("pkg/Working2.class"), "w2");
        assertFileContent(dst.resolve("pkg/Working3.class"), "w3");
        assertFalse(Files.exists(dst.resolve("pkg/Broken1.class")));
        assertFalse(Files.exists(dst.resolve("pkg/Broken2.class")));
    }

    // -----------------------------------------------------------------
    // Scenario 7: Nested package structure (deep hierarchy) preserved
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 7 — nested packages: deep directory hierarchy preserved end-to-end")
    void scenario07_nestedPackages(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "com/example/app/local/web/config/AppConfig.class", "app-config");
        writeClass(src, "com/example/app/local/web/controller/HomeController.class", "home");
        writeClass(src, "com/example/app/local/web/dao/UserDao.class", "dao");
        writeClass(src, "com/example/app/shared/util/Strings.class", "strings");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(4, r.copied());
        assertEquals(0, r.brokenSkipped());
        // Every package path mirrored exactly.
        assertFileContent(dst.resolve("com/example/app/local/web/config/AppConfig.class"), "app-config");
        assertFileContent(dst.resolve("com/example/app/local/web/controller/HomeController.class"), "home");
        assertFileContent(dst.resolve("com/example/app/local/web/dao/UserDao.class"), "dao");
        assertFileContent(dst.resolve("com/example/app/shared/util/Strings.class"), "strings");
    }

    // -----------------------------------------------------------------
    // Scenario 8: Resources alongside classes — both mirror
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 8 — resources + classes: .properties, .xml, .yml, .json all mirror")
    void scenario08_resourcesMirror(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeRaw(src.resolve("App.class"), "code".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("messages.properties"), "greeting=hi".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("logback.xml"), "<configuration/>".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("application.yml"), "server: { port: 8080 }".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("schema.json"), "{}".getBytes(StandardCharsets.UTF_8));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(5, r.copied(), "all 5 (1 class + 4 resources) must mirror");
        assertEquals(0, r.brokenSkipped());
        assertTrue(Files.exists(dst.resolve("messages.properties")));
        assertTrue(Files.exists(dst.resolve("logback.xml")));
        assertTrue(Files.exists(dst.resolve("application.yml")));
        assertTrue(Files.exists(dst.resolve("schema.json")));
    }

    // -----------------------------------------------------------------
    // Scenario 9: Resource file containing the ECJ marker as USER text — NOT skipped
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 9 — .properties containing ECJ marker text: mirrors normally (extension gate)")
    void scenario09_resourceWithEcjLiteralText(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // A user could plausibly have the literal string in a docstring or
        // localized error catalog. The detector's extension gate must stop
        // this from being mis-flagged.
        Files.writeString(src.resolve("errors.properties"),
                "ecj.error=Unresolved compilation problems found");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied());
        assertEquals(0, r.brokenSkipped(),
                "the ECJ marker only triggers on .class extensions; .properties bypass");
        assertTrue(Files.exists(dst.resolve("errors.properties")));
    }

    // -----------------------------------------------------------------
    // Scenario 10: Size tie-breaker — equal mtime but different size still copies
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 10 — size tie-breaker: equal mtime, different size, fresh src wins")
    void scenario10_sizeTieBreaker(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "Edited.class", "this-version-is-longer-after-edit");
        writeClass(dst, "Edited.class", "shorter");
        // Force identical mtimes — the dual-edit-within-mtime-resolution drift case.
        Files.setLastModifiedTime(src.resolve("Edited.class"), FileTime.fromMillis(5_000L));
        Files.setLastModifiedTime(dst.resolve("Edited.class"), FileTime.fromMillis(5_000L));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "size mismatch under equal mtime must trigger copy");
        assertEquals(0, r.brokenSkipped());
        assertFileContent(dst.resolve("Edited.class"), "this-version-is-longer-after-edit");
    }

    // -----------------------------------------------------------------
    // Scenario 11: Re-sync idempotency — second sync over the same source is a no-op
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 11 — idempotency: re-running sync over same src/dst is a no-op")
    void scenario11_resyncIdempotent(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "X.class", "x");
        writeClass(src, "Y.class", "y");

        DeployedClassesSync.MirrorResult first = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(2, first.copied(), "first sync mirrors everything");

        DeployedClassesSync.MirrorResult second = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(0, second.copied(),
                "second sync over identical state must be a no-op (the sync aligns the deployed mtime to the source)");
        assertEquals(0, second.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 12: Empty source root — empty MirrorResult, no error
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 12 — empty src: returns (0, 0), no exception")
    void scenario12_emptySrc(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied());
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 13: Source path is not a directory (e.g. someone passed a file)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 13 — src is a file, not a dir: returns empty (defensive)")
    void scenario13_srcIsFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("not-a-dir.txt");
        Files.writeString(file, "oops");
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(file, dst);

        assertEquals(0, r.copied(), "mirrorTree must gracefully handle non-directory src");
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 14: Multi-module project layout — two source roots merge
    //
    // Generic shape, not tied to any specific project. The universal-launch
    // hook iterates over every production class root the OrderEnumerator
    // returns for the deployment's owning module — that includes the web
    // module's own output AND every transitive dep module's output. They
    // must layer into one WEB-INF/classes/ without collision (real Java
    // forbids the same FQN appearing in two compile units, so collision
    // is impossible in practice).
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 14 — multi-module merge: web + dep module outputs layer into one WEB-INF/classes")
    void scenario14_multiModuleMerge(@TempDir Path tmp) throws Exception {
        Path webSrc = Files.createDirectories(tmp.resolve("webmodule/target/classes"));
        Path depSrc = Files.createDirectories(tmp.resolve("depmodule/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("webmodule/target/app/WEB-INF/classes"));

        writeClass(webSrc, "com/example/web/HomeController.class", "home");
        writeClass(webSrc, "com/example/web/config/AppConfig.class", "app");
        writeClass(depSrc, "com/example/common/util/DateUtils.class", "dates");
        writeClass(depSrc, "com/example/common/dto/UserDto.class", "user");

        DeployedClassesSync.MirrorResult web = DeployedClassesSync.mirrorTree(webSrc, dst);
        DeployedClassesSync.MirrorResult dep = DeployedClassesSync.mirrorTree(depSrc, dst);

        assertEquals(2, web.copied());
        assertEquals(2, dep.copied());

        Set<String> deployed = listRelative(dst);
        Set<String> expected = new HashSet<>(List.of(
                "com/example/web/HomeController.class",
                "com/example/web/config/AppConfig.class",
                "com/example/common/util/DateUtils.class",
                "com/example/common/dto/UserDto.class"
        ));
        assertEquals(expected, deployed,
                "merged WEB-INF/classes must contain BOTH module outputs");
    }

    // -----------------------------------------------------------------
    // Scenario 15: General broken-class contract.
    //
    // The detector does not care what kind of class is broken — Spring
    // @Configuration, MyBatis mapper, EJB session bean, plain DAO,
    // framework hook, third-party library shim, the user's own helper.
    // Identical treatment: refuse to overwrite the deployed copy with a
    // stub that throws java.lang.Error("Unresolved compilation problems")
    // at class init. Pinning the GENERAL behaviour rather than any one
    // user-reported class. This is what protects every project.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 15 — general broken-class contract: any ECJ stub refused, working dst preserved")
    void scenario15_generalBrokenClassContract(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Three healthy classes — any kind of class, any kind of project.
        writeClass(src, "com/example/web/HomeController.class", "fresh-controller");
        writeClass(src, "com/example/service/OrderService.class", "fresh-service");
        writeClass(src, "com/example/dao/UserDao.class", "fresh-dao");

        // Two broken stubs — could be anything: framework config, lib
        // integration shim, the user's own glue. The detector treats them
        // identically.
        writeRaw(src.resolve("com/example/config/SomeConfig.class"),
                ecjBrokenPayload());
        writeRaw(src.resolve("com/example/integration/AnyFrameworkHook.class"),
                ecjBrokenPayload());

        // Pre-existing working deployed copies (from a clean prior build —
        // mvn install, gradle war, IntelliJ Build Artifacts; doesn't matter
        // which built them, only that they were clean).
        writeClass(dst, "com/example/web/HomeController.class", "old-controller");
        writeClass(dst, "com/example/config/SomeConfig.class", "working-config");
        writeClass(dst, "com/example/integration/AnyFrameworkHook.class", "working-hook");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(3, r.copied(), "three healthy classes must mirror");
        assertEquals(2, r.brokenSkipped(), "two broken stubs must be refused regardless of class type");

        // Fresh classes overwrote the deployed copies.
        assertFileContent(dst.resolve("com/example/web/HomeController.class"), "fresh-controller");
        assertFileContent(dst.resolve("com/example/service/OrderService.class"), "fresh-service");
        assertFileContent(dst.resolve("com/example/dao/UserDao.class"), "fresh-dao");

        // The deployed copies of the BROKEN sources remain byte-identical.
        // This is the general contract that protects every project, every
        // framework, every class type.
        assertFileContent(dst.resolve("com/example/config/SomeConfig.class"),
                "working-config");
        assertFileContent(dst.resolve("com/example/integration/AnyFrameworkHook.class"),
                "working-hook");
    }

    // -----------------------------------------------------------------
    // Scenario 16: Destination has files NOT in source (e.g. .jar dependencies in WEB-INF/classes which is unusual but possible) → preserved, not deleted
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 16 — dst has extras: mirror never deletes destination-only files")
    void scenario16_dstExtrasPreserved(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "A.class", "a");

        // Maven-built or hand-added files in dst that shouldn't be touched.
        writeClass(dst, "Legacy.class", "legacy-must-survive");
        Files.writeString(dst.resolve("manifest.txt"), "deployment-manifest");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied());
        assertTrue(Files.exists(dst.resolve("A.class")));
        assertTrue(Files.exists(dst.resolve("Legacy.class")),
                "destination-only file must survive the sync (mirror is source-driven, never deletes)");
        assertTrue(Files.exists(dst.resolve("manifest.txt")));
        assertFileContent(dst.resolve("Legacy.class"), "legacy-must-survive");
    }

    // -----------------------------------------------------------------
    // Scenario 17: Subdirectory created in src after first sync → second sync adds it
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 17 — new package added between syncs: new subdir mirrored, prior files steady")
    void scenario17_newPackageBetweenSyncs(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "old/Already.class", "old");
        DeployedClassesSync.mirrorTree(src, dst);

        // User adds a new package.
        writeClass(src, "fresh/NewClass.class", "new");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "exactly one new file (the fresh/NewClass) must copy");
        assertEquals(0, r.brokenSkipped());
        assertTrue(Files.exists(dst.resolve("old/Already.class")));
        assertTrue(Files.exists(dst.resolve("fresh/NewClass.class")));
    }

    // -----------------------------------------------------------------
    // Scenario 18: Multiple mtime resolution levels (millisecond drift) handled correctly
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 18 — millisecond mtime jitter: even 1ms newer src triggers copy")
    void scenario18_millisecondMtimeJitter(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "Jitter.class", "edited");
        writeClass(dst, "Jitter.class", "previous");
        Files.setLastModifiedTime(src.resolve("Jitter.class"), FileTime.fromMillis(1_000_001L));
        Files.setLastModifiedTime(dst.resolve("Jitter.class"), FileTime.fromMillis(1_000_000L));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "even 1ms newer src must trigger copy");
        assertFileContent(dst.resolve("Jitter.class"), "edited");
    }

    // -----------------------------------------------------------------
    // RESILIENCE SCENARIOS — adverse conditions that real-world projects hit
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // Scenario 19: Empty .class file (zero bytes) — never treated as broken
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 19 — empty .class file: too small for marker, copies normally")
    void scenario19_emptyClassFile(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // A zero-byte .class file cannot contain the ECJ marker. The detector's
        // size-fast-reject path keeps this cheap (no readAllBytes called).
        Files.createFile(src.resolve("Empty.class"));

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "empty .class is still a file to mirror");
        assertEquals(0, r.brokenSkipped(),
                "empty file cannot contain the marker — must NOT be flagged broken");
        assertEquals(0L, Files.size(dst.resolve("Empty.class")));
    }

    // -----------------------------------------------------------------
    // Scenario 20: Tiny .class file just below marker length — fast-reject
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 20 — sub-marker-size .class: size guard short-circuits the byte scan")
    void scenario20_subMarkerSize(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // 10 bytes — well below the ~31-byte marker. The detector must not
        // even attempt to read the file's bytes; the size check rejects.
        byte[] tiny = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0, 0, 0x3D, 0, 10};
        Files.write(src.resolve("Tiny.class"), tiny);

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied());
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 21: Symlinked file in src — skipped entirely
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 21 — symlinked file in src: skipped (mirror does not follow links)")
    void scenario21_symlinkSkipped(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));

        writeClass(src, "Normal.class", "n");

        // Real file outside src; src contains a symlink to it. Mirror should
        // visit the symlink, see isSymbolicLink, and skip — not follow into
        // arbitrary on-disk locations.
        Path target = elsewhere.resolve("Outside.class");
        Files.writeString(target, "outside-content");
        try {
            Files.createSymbolicLink(src.resolve("Linked.class"), target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // Some sandboxes / Windows non-admin lacks symlink privileges —
            // skip the test in that case rather than fail spuriously.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks not creatable here: " + e.getMessage());
            return;
        }

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "only the real file should mirror");
        assertEquals(0, r.brokenSkipped());
        assertTrue(Files.exists(dst.resolve("Normal.class")));
        assertFalse(Files.exists(dst.resolve("Linked.class")),
                "symlink must NOT be followed/copied");
    }

    // -----------------------------------------------------------------
    // Scenario 22: Symlinked directory in src — entire subtree skipped
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 22 — symlinked directory in src: subtree skipped (loop / external-path safety)")
    void scenario22_symlinkedDirSubtreeSkipped(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere/pkg"));

        writeClass(src, "Real.class", "r");
        writeRaw(elsewhere.resolve("HiddenFromSync.class"),
                "would-be-bad".getBytes(StandardCharsets.UTF_8));

        try {
            Files.createSymbolicLink(src.resolve("linkedpkg"), elsewhere);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks not creatable: " + e.getMessage());
            return;
        }

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "only the real file in src copies");
        assertFalse(Files.exists(dst.resolve("linkedpkg/HiddenFromSync.class")),
                "symlinked subtree contents must NOT travel into the deployment");
    }

    // -----------------------------------------------------------------
    // Scenario 23: src nested inside dst — recursion-loop guard refuses
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 23 — src nested inside dst: refused upfront (loop guard)")
    void scenario23_srcInsideDst(@TempDir Path tmp) throws Exception {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path src = Files.createDirectories(dst.resolve("nested/src"));
        writeClass(src, "X.class", "x");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied(),
                "src nested under dst would loop — must be refused without copying anything");
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 24: dst nested inside src — also refused (mirror would re-visit dst)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 24 — dst nested inside src: refused upfront (mirror would re-enter dst)")
    void scenario24_dstInsideSrc(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(src.resolve("nested/dst"));
        writeClass(src, "Y.class", "y");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied(),
                "dst nested under src would re-enter dst during the walk — must be refused");
    }

    // -----------------------------------------------------------------
    // Scenario 25: Marker bytes RIGHT AT the end of file (boundary)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 25 — ECJ marker at end of class file: still detected")
    void scenario25_markerAtFileEnd(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Marker placed last in the file — exercises the substring scan's
        // boundary handling so a slightly different ECJ build wouldn't
        // somehow slip past the detector.
        byte[] header = new byte[1024];
        byte[] marker = "Unresolved compilation problems".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[header.length + marker.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(marker, 0, all, header.length, marker.length);

        Files.write(src.resolve("EdgeCase.class"), all);
        writeClass(dst, "EdgeCase.class", "previous-clean-copy");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(0, r.copied());
        assertEquals(1, r.brokenSkipped(),
                "marker placement anywhere in the file (incl. very end) must trigger detection");
        assertFileContent(dst.resolve("EdgeCase.class"), "previous-clean-copy");
    }

    // -----------------------------------------------------------------
    // Scenario 26: Unicode / non-ASCII file names
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 26 — non-ASCII filenames: handled correctly end-to-end")
    void scenario26_unicodeFilenames(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Real-world cases: i18n-tagged messages files, devs using Cyrillic
        // / CJK identifiers (legal in Java for non-private members). Mirror
        // mustn't break on the filesystem layer's UTF-8 handling.
        writeClass(src, "com/example/Λ.class", "lambda-class");
        writeRaw(src.resolve("com/example/résumé.properties"),
                "k=v".getBytes(StandardCharsets.UTF_8));
        writeClass(src, "com/example/日本語.class", "japanese-class");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(3, r.copied(), "all 3 non-ASCII-named files must mirror");
        assertTrue(Files.exists(dst.resolve("com/example/Λ.class")));
        assertTrue(Files.exists(dst.resolve("com/example/résumé.properties")));
        assertTrue(Files.exists(dst.resolve("com/example/日本語.class")));
    }

    // -----------------------------------------------------------------
    // Scenario 27: Diverse file-type matrix — broad coverage of resource kinds
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 27 — diverse file-type matrix: every resource kind seen in real webapps")
    void scenario27_diverseFileTypes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // The kinds of files that typically live in target/classes alongside
        // compiled bytecode. Every one of these comes from src/main/resources
        // via the Maven build (or src/main/java for the .class). All must
        // mirror.
        String[] paths = {
                "com/example/Service.class",
                "messages.properties",
                "logback-spring.xml",
                "application.yml",
                "application.yaml",
                "META-INF/MANIFEST.MF",
                "META-INF/persistence.xml",
                "META-INF/spring.factories",
                "openapi.json",
                "schema.graphql",
                "static-data.csv",
                "init.sql",
                "config.toml",
                "templates/email.html",
                "static/style.css",
                "static/app.js",
                "i18n/messages_en_US.properties",
                "i18n/messages_zh_CN.properties",
                ".keep" // hidden file convention for empty-dir markers
        };
        for (String p : paths) {
            writeRaw(src.resolve(p), ("content of " + p).getBytes(StandardCharsets.UTF_8));
        }

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(paths.length, r.copied(),
                "all " + paths.length + " file types must mirror");
        assertEquals(0, r.brokenSkipped(),
                "only .class files are even scanned for the ECJ marker");
        for (String p : paths) {
            assertTrue(Files.exists(dst.resolve(p)), "missing in dst: " + p);
        }
    }

    // -----------------------------------------------------------------
    // Scenario 28: Empty subdirectory in src — not propagated as a file copy
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 28 — empty subdirectories in src: ignored (no spurious counts)")
    void scenario28_emptySubdirectoriesIgnored(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // A scaffolded but unused package (e.g. annotation processor created
        // the dir but no class ended up there yet).
        Files.createDirectories(src.resolve("com/example/empty"));
        writeClass(src, "com/example/A.class", "a");

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(), "only the actual class file counts");
        assertEquals(0, r.brokenSkipped());
    }

    // -----------------------------------------------------------------
    // Scenario 29: ECJ broken class WITH non-ECJ "Unresolved compilation"
    //              fragment in user data — only the .class hits the detector
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 29 — strict extension gate: marker in non-class file ignored")
    void scenario29_extensionGateStrict(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // .classified-data is NOT a .class (suffix is .classified-data, not .class)
        Files.writeString(src.resolve("data.classified-data"),
                "this contains: Unresolved compilation problems");

        // A real .class file with the marker
        writeRaw(src.resolve("RealBroken.class"), ecjBrokenPayload());

        DeployedClassesSync.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, r.copied(),
                "the data file (despite containing the marker as user text) must mirror");
        assertEquals(1, r.brokenSkipped(),
                "the actual .class file must be refused");
        assertTrue(Files.exists(dst.resolve("data.classified-data")));
        assertFalse(Files.exists(dst.resolve("RealBroken.class")));
    }

    // -----------------------------------------------------------------
    // Scenario 30: Cold mirror followed by partial broken introduction
    //              — second pass refuses broken without touching working
    // -----------------------------------------------------------------
    @Test
    @DisplayName("Scenario 30 — two-pass: clean cold start, then a class breaks → only that one refused")
    void scenario30_twoPassPartialBreakage(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Pass 1: everything healthy, all mirrors.
        writeClass(src, "com/foo/A.class", "a-v1");
        writeClass(src, "com/foo/B.class", "b-v1");
        writeClass(src, "com/foo/C.class", "c-v1");
        DeployedClassesSync.MirrorResult first = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(3, first.copied());

        // User then edits B with an unresolved import; IDE Make produces an
        // ECJ stub. A and C stay clean.
        writeRaw(src.resolve("com/foo/B.class"), ecjBrokenPayload());
        // Bump A's mtime (simulating a real edit + clean recompile).
        Files.setLastModifiedTime(src.resolve("com/foo/A.class"),
                FileTime.fromMillis(Files.getLastModifiedTime(src.resolve("com/foo/A.class"))
                        .toMillis() + 5000L));
        Files.writeString(src.resolve("com/foo/A.class"), "a-v2-edited");

        DeployedClassesSync.MirrorResult second = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, second.copied(), "only A (the cleanly-edited class) refreshes");
        assertEquals(1, second.brokenSkipped(), "B (the broken stub) refused");
        assertFileContent(dst.resolve("com/foo/A.class"), "a-v2-edited");
        // Critically: B's pass-1 working copy survives in the deployment.
        assertFileContent(dst.resolve("com/foo/B.class"), "b-v1");
        assertFileContent(dst.resolve("com/foo/C.class"), "c-v1");
    }

    // ===========================================================================
    // Orphan reconciliation — the destination is the sole authority for what
    // Tomcat loads, so anything in dst that source no longer claims must be
    // deletable. Scenarios cover: simple delete, multi-source-root union,
    // empty-source safety net, idempotency.
    // ===========================================================================

    @Test
    @DisplayName("Orphan reconcile — source deletes a file → dst orphan removed")
    void orphan01_singleDelete(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Initial state: src and dst both have two classes.
        writeClass(src, "com/foo/Keep.class", "keep");
        writeClass(src, "com/foo/Gone.class", "gone");
        DeployedClassesSync.mirrorTree(src, dst);  // populates dst

        // User deletes Gone.java → IDE rebuild → src no longer has Gone.class.
        Files.delete(src.resolve("com/foo/Gone.class"));

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst);
        // mirrorTree itself only copies; the orphan pass is invoked by callers.
        int removed = DeployedClassesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(1, removed, "the deleted source file must be reflected in dst");
        assertTrue(Files.exists(dst.resolve("com/foo/Keep.class")), "live file stays");
        assertFalse(Files.exists(dst.resolve("com/foo/Gone.class")), "orphan must be deleted");
    }

    @Test
    @DisplayName("Orphan reconcile — files only ever in dst (never in src) are removed")
    void orphan02_pureOrphan(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // dst has stale content from a previous build that src no longer
        // produces (e.g. a class file from a class that was renamed).
        writeClass(dst, "com/foo/Stale.class", "stale-bytes");
        writeClass(src, "com/foo/Fresh.class", "fresh-bytes");

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst);
        int removed = DeployedClassesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(1, removed);
        assertFalse(Files.exists(dst.resolve("com/foo/Stale.class")));
        assertTrue(Files.exists(dst.resolve("com/foo/Fresh.class")));
    }

    @Test
    @DisplayName("Orphan reconcile — multi source root: union of contributions is retained")
    void orphan03_multipleSourceRootsUnion(@TempDir Path tmp) throws Exception {
        // Simulating a module with separate Java + resources output dirs (some
        // builds split target/classes/ and target/classes-resources/, or
        // Gradle's java/main + resources/main). Union of both roots' contents
        // must survive the orphan pass; anything in neither must be deleted.
        Path javaSrc = Files.createDirectories(tmp.resolve("javaSrc"));
        Path resSrc = Files.createDirectories(tmp.resolve("resSrc"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(javaSrc, "com/foo/Foo.class", "foo");
        writeClass(resSrc, "com/foo/messages.properties", "hello=world");
        // Orphan that comes from neither source root.
        writeClass(dst, "com/foo/Orphan.class", "stale");

        Set<String> allContributed = new HashSet<>();
        allContributed.addAll(DeployedClassesSync.mirrorTree(javaSrc, dst).contributedPaths());
        allContributed.addAll(DeployedClassesSync.mirrorTree(resSrc, dst).contributedPaths());

        int removed = DeployedClassesSync.removeOrphans(dst, allContributed);

        assertEquals(1, removed);
        assertTrue(Files.exists(dst.resolve("com/foo/Foo.class")));
        assertTrue(Files.exists(dst.resolve("com/foo/messages.properties")));
        assertFalse(Files.exists(dst.resolve("com/foo/Orphan.class")));
    }

    @Test
    @DisplayName("Orphan reconcile — empty retain set deletes everything (caller MUST gate this)")
    void orphan04_emptyRetainSetDeletesEverything(@TempDir Path tmp) throws Exception {
        // This pins the contract: removeOrphans is destructive when the
        // retain set is empty. The CALLER must guard against the
        // "every source root was unreadable" case before calling — which
        // syncDeployments does via `if (!contributedPaths.isEmpty())`. This
        // test exists to make sure that gate is never accidentally removed,
        // because the failure mode (empty retain set → wipe WEB-INF/classes)
        // would silently destroy a working deployment.
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        writeClass(dst, "A.class", "a");
        writeClass(dst, "com/b/B.class", "b");

        int removed = DeployedClassesSync.removeOrphans(dst, java.util.Collections.emptySet());

        assertEquals(2, removed, "removeOrphans with empty retain MUST delete every file — "
                + "the gate against this lives in syncDeployments, NOT here");
        assertFalse(Files.exists(dst.resolve("A.class")));
        assertFalse(Files.exists(dst.resolve("com/b/B.class")));
    }

    @Test
    @DisplayName("Orphan reconcile — idempotency: second pass after a clean sync is a no-op")
    void orphan05_idempotent(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "X.class", "x");
        writeClass(src, "com/y/Y.class", "y");

        // First pass — populates dst.
        DeployedClassesSync.MirrorResult first = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(0, DeployedClassesSync.removeOrphans(dst, first.contributedPaths()),
                "fresh mirror produces no orphans");

        // Second pass — same state, must be idempotent.
        DeployedClassesSync.MirrorResult second = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(0, DeployedClassesSync.removeOrphans(dst, second.contributedPaths()),
                "re-running over identical state produces no orphans");
        assertTrue(Files.exists(dst.resolve("X.class")));
        assertTrue(Files.exists(dst.resolve("com/y/Y.class")));
    }

    @Test
    @DisplayName("Orphan reconcile — broken ECJ stub: source path still contributes (orphan pass skips it)")
    void orphan06_brokenEcjStubStillContributes(@TempDir Path tmp) throws Exception {
        // Critical edge case: when src has a broken-ECJ stub, mirrorTree
        // refuses to overwrite the (presumably working) dst copy. But it
        // MUST still add the relative path to contributedPaths so the orphan
        // pass doesn't then delete the working dst copy because "the source
        // doesn't claim it".
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // dst has a working copy from a previous successful build.
        writeClass(dst, "com/foo/Broken.class", "working-bytes-from-prior-build");
        // src has the broken ECJ stub.
        writeRaw(src.resolve("com/foo/Broken.class"), ecjBrokenPayload());

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(0, mr.copied(), "broken stub must not overwrite working dst");
        assertEquals(1, mr.brokenSkipped());

        int removed = DeployedClassesSync.removeOrphans(dst, mr.contributedPaths());
        assertEquals(0, removed,
                "the broken-stub path must still be in contributedPaths so the orphan "
                        + "pass keeps the working dst copy");
        assertTrue(Files.exists(dst.resolve("com/foo/Broken.class")),
                "working dst copy must survive");
    }

    @Test
    @DisplayName("Orphan reconcile — nested directory: orphans deep in the tree are removed")
    void orphan07_nestedDirectory(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        writeClass(src, "com/a/b/c/Live.class", "live");
        // Orphans at varying depths.
        writeClass(dst, "com/a/Old1.class", "stale1");
        writeClass(dst, "com/a/b/c/Old2.class", "stale2");
        writeClass(dst, "com/a/b/c/d/e/Deep.class", "deep");

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst);
        int removed = DeployedClassesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(3, removed);
        assertTrue(Files.exists(dst.resolve("com/a/b/c/Live.class")));
        assertFalse(Files.exists(dst.resolve("com/a/Old1.class")));
        assertFalse(Files.exists(dst.resolve("com/a/b/c/Old2.class")));
        assertFalse(Files.exists(dst.resolve("com/a/b/c/d/e/Deep.class")));
    }

    // ===========================================================================
    // Dependency-module policy: classesOnly mirror mechanism
    //
    // A dependency module's compile-output root (Maven target/classes/) holds
    // both .class files and everything copied from src/main/resources/. When
    // that dependency is packaged as a WEB-INF/lib/<lib>.jar, its resources
    // already reach Tomcat through the JAR, so copying them into WEB-INF/classes/
    // too would put the same path on the classpath twice — fatal for any
    // framework that discovers resources by enumerating the classpath. The
    // classesOnly=true mirror skips non-.class files for exactly that case.
    //
    // These tests pin the low-level mirrorTree mechanism for a GIVEN
    // classesOnly value. Choosing that value per root — .class-only when the
    // dependency is packaged in the deployed WEB-INF/lib/, full content when it
    // is not — is the job of shouldMirrorClassesOnly, exercised separately.
    // ===========================================================================

    @Test
    @DisplayName("classesOnly — dependency root contributes .class files only, resources skipped")
    void classesOnly01_skipsNonClassResources(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("web/target/app/WEB-INF/classes"));

        // Bytecode — must be mirrored (incl. inner classes).
        writeClass(src, "com/example/dao/UserDao.class", "dao");
        writeClass(src, "com/example/dao/UserDao$Row.class", "row");
        // Non-class resources — already in the dep's lib JAR; must be skipped.
        writeClass(src, "config/app.properties", "k=v");
        writeClass(src, "META-INF/services/com.example.Spi", "com.example.Impl");
        writeClass(src, "descriptors/registry.xml", "<registry/>");

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, true);

        assertEquals(2, mr.copied(), "only the two .class files may be mirrored");
        assertTrue(Files.exists(dst.resolve("com/example/dao/UserDao.class")));
        assertTrue(Files.exists(dst.resolve("com/example/dao/UserDao$Row.class")));
        assertFalse(Files.exists(dst.resolve("config/app.properties")),
                "dependency resource must NOT be copied into WEB-INF/classes");
        assertFalse(Files.exists(dst.resolve("META-INF/services/com.example.Spi")),
                "dependency service registration must NOT be duplicated onto the classpath");
        assertFalse(Files.exists(dst.resolve("descriptors/registry.xml")),
                "dependency descriptor must NOT be duplicated onto the classpath");

        // Skipped resources must be ABSENT from contributedPaths so the
        // caller's orphan pass treats any pre-existing copy as removable.
        Set<String> contributed = mr.contributedPaths();
        assertTrue(contributed.contains("com/example/dao/UserDao.class"));
        assertTrue(contributed.contains("com/example/dao/UserDao$Row.class"));
        assertFalse(contributed.contains("config/app.properties"));
        assertFalse(contributed.contains("META-INF/services/com.example.Spi"));
        assertFalse(contributed.contains("descriptors/registry.xml"));
    }

    @Test
    @DisplayName("classesOnly — self-heal: orphan pass removes a resource an earlier full-content sync left in WEB-INF/classes")
    void classesOnly02_orphanPassRemovesPreviouslyDuplicatedResource(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("web/target/app/WEB-INF/classes"));

        writeClass(src, "com/example/dao/UserDao.class", "dao");
        writeClass(src, "descriptors/registry.xml", "<registry/>");

        // Simulate the broken state an earlier full-content sync produced:
        // the dependency's descriptor was copied into WEB-INF/classes/ even
        // though it also lives in the dependency's lib JAR.
        writeClass(dst, "com/example/dao/UserDao.class", "stale-dao");
        writeClass(dst, "descriptors/registry.xml", "<registry/>");

        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, true);
        int removed = DeployedClassesSync.removeOrphans(dst, mr.contributedPaths());

        assertEquals(1, removed, "the duplicated descriptor must be reconciled away");
        assertTrue(Files.exists(dst.resolve("com/example/dao/UserDao.class")),
                "the dependency .class must remain (it legitimately wins over the lib JAR copy)");
        assertFalse(Files.exists(dst.resolve("descriptors/registry.xml")),
                "the duplicate descriptor must be gone so the classpath holds one copy (in the lib JAR)");
    }

    @Test
    @DisplayName("classesOnly=false — web module's own root mirrors full content (resources included)")
    void classesOnly03_ownRootMirrorsEverything(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("web/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("web/target/app/WEB-INF/classes"));

        writeClass(src, "com/example/web/HomeController.class", "home");
        writeClass(src, "config/app.properties", "k=v");
        writeClass(src, "descriptors/registry.xml", "<registry/>");

        // Full-content path (the web module's own output): the 2-arg overload
        // and the explicit classesOnly=false must behave identically.
        DeployedClassesSync.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, false);

        assertEquals(3, mr.copied(), "own root mirrors .class AND resources");
        assertTrue(Files.exists(dst.resolve("com/example/web/HomeController.class")));
        assertTrue(Files.exists(dst.resolve("config/app.properties")),
                "the web module's own resources belong in WEB-INF/classes (not duplicated in any lib JAR)");
        assertTrue(Files.exists(dst.resolve("descriptors/registry.xml")));
    }

    // ===========================================================================
    // Test fixtures and helpers
    // ===========================================================================

    /** Write a "class" file with arbitrary bytes (no actual class-file header). */
    private static void writeClass(Path root, String relPath, String content) throws IOException {
        Path target = root.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** Write raw bytes at the exact path; auto-creates parent dirs. */
    private static void writeRaw(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Asserts the file's UTF-8 contents match the expected string. */
    private static void assertFileContent(Path file, String expected) throws IOException {
        assertTrue(Files.exists(file), "file must exist: " + file);
        byte[] actual = Files.readAllBytes(file);
        assertArrayEquals(
                expected.getBytes(StandardCharsets.UTF_8),
                actual,
                "content mismatch at " + file);
    }

    /** Lists every file under {@code root} as a forward-slash-normalized relative path. */
    private static Set<String> listRelative(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                out.add(rel);
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.naturalOrder());
        return new HashSet<>(out);
    }
}
