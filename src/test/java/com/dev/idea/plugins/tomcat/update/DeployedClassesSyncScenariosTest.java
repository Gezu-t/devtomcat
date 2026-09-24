package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;

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
import java.util.Map;
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

    // The manifest store must never write into the real IDE system directory
    // from a test; redirect it to a per-test temp root.
    @org.junit.jupiter.api.BeforeEach
    void redirectManifestStore(@TempDir Path storeRoot) {
        SyncManifestStore.setRootOverride(storeRoot);
    }

    @org.junit.jupiter.api.AfterEach
    void resetManifestStore() {
        SyncManifestStore.setRootOverride(null);
    }

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        writeClass(src, "com/example/app/web/config/AppConfig.class", "app-config");
        writeClass(src, "com/example/app/web/controller/HomeController.class", "home");
        writeClass(src, "com/example/app/web/dao/UserDao.class", "dao");
        writeClass(src, "com/example/app/shared/util/Strings.class", "strings");

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(4, r.copied());
        assertEquals(0, r.brokenSkipped());
        // Every package path mirrored exactly.
        assertFileContent(dst.resolve("com/example/app/web/config/AppConfig.class"), "app-config");
        assertFileContent(dst.resolve("com/example/app/web/controller/HomeController.class"), "home");
        assertFileContent(dst.resolve("com/example/app/web/dao/UserDao.class"), "dao");
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
        writeRaw(src.resolve("app-logging.xml"), "<configuration/>".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("application.yml"), "server: { port: 8080 }".getBytes(StandardCharsets.UTF_8));
        writeRaw(src.resolve("schema.json"), "{}".getBytes(StandardCharsets.UTF_8));

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(5, r.copied(), "all 5 (1 class + 4 resources) must mirror");
        assertEquals(0, r.brokenSkipped());
        assertTrue(Files.exists(dst.resolve("messages.properties")));
        assertTrue(Files.exists(dst.resolve("app-logging.xml")));
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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult first = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(2, first.copied(), "first sync mirrors everything");

        TreeMirror.MirrorResult second = DeployedClassesSync.mirrorTree(src, dst);
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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(file, dst);

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

        TreeMirror.MirrorResult web = DeployedClassesSync.mirrorTree(webSrc, dst);
        TreeMirror.MirrorResult dep = DeployedClassesSync.mirrorTree(depSrc, dst);

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
    // The detector does not care what kind of class is broken — a framework
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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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
                "app-logging-profile.xml",
                "application.yml",
                "application.yaml",
                "META-INF/MANIFEST.MF",
                "META-INF/persistence.xml",
                "META-INF/app.factories",
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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

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
        TreeMirror.MirrorResult first = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(3, first.copied());

        // User then edits B with an unresolved import; IDE Make produces an
        // ECJ stub. A and C stay clean.
        writeRaw(src.resolve("com/foo/B.class"), ecjBrokenPayload());
        // Bump A's mtime (simulating a real edit + clean recompile).
        Files.setLastModifiedTime(src.resolve("com/foo/A.class"),
                FileTime.fromMillis(Files.getLastModifiedTime(src.resolve("com/foo/A.class"))
                        .toMillis() + 5000L));
        Files.writeString(src.resolve("com/foo/A.class"), "a-v2-edited");

        TreeMirror.MirrorResult second = DeployedClassesSync.mirrorTree(src, dst);

        assertEquals(1, second.copied(), "only A (the cleanly-edited class) refreshes");
        assertEquals(1, second.brokenSkipped(), "B (the broken stub) refused");
        assertFileContent(dst.resolve("com/foo/A.class"), "a-v2-edited");
        // Critically: B's pass-1 working copy survives in the deployment.
        assertFileContent(dst.resolve("com/foo/B.class"), "b-v1");
        assertFileContent(dst.resolve("com/foo/C.class"), "c-v1");
    }

    // ===========================================================================
    // contributedPaths contract — downstream reconciles (SyncManifest.reconcile)
    // treat a mirror's contributed paths as "what the source claims". The
    // critical edge: a path can contribute WITHOUT being copied.
    // ===========================================================================

    @Test
    @DisplayName("contributedPaths — broken ECJ stub still contributes its path (reconcile must keep the working copy)")
    void contributedPaths_brokenEcjStubStillContributes(@TempDir Path tmp) throws Exception {
        // Critical edge case: when src has a broken-ECJ stub, mirrorTree
        // refuses to overwrite the (presumably working) dst copy. But it
        // MUST still report the relative path in contributedPaths — a
        // downstream reconcile keyed on contributed paths would otherwise
        // delete the working dst copy because "the source doesn't claim it".
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // dst has a working copy from a previous successful build.
        writeClass(dst, "com/foo/Broken.class", "working-bytes-from-prior-build");
        // src has the broken ECJ stub.
        writeRaw(src.resolve("com/foo/Broken.class"), ecjBrokenPayload());

        TreeMirror.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst);
        assertEquals(0, mr.copied(), "broken stub must not overwrite working dst");
        assertEquals(1, mr.brokenSkipped());
        assertTrue(mr.contributedPaths().contains("com/foo/Broken.class"),
                "the refused stub's path must still be claimed as contributed");
        assertTrue(Files.exists(dst.resolve("com/foo/Broken.class")),
                "working dst copy must survive the mirror");
    }

    // ===========================================================================
    // SyncManifest.reconcile — the class-sync PRODUCTION reconcile (manifest-based,
    // shared with WebResourcesSync). Deletes ONLY classes we synced before and no
    // longer do (removed from source); never a class the artifact build placed that
    // the mirror doesn't cover — that over-deletion was the ClassNotFoundException
    // data-loss bug.
    // ===========================================================================

    @Test
    @DisplayName("reconcile — a class removed from source (was synced) is deleted")
    void reconcile01_removedFromSourceDeleted(@TempDir Path tmp) throws Exception {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path manifest = tmp.resolve("m.manifest");
        writeClass(dst, "com/foo/Keep.class", "keep");
        writeClass(dst, "com/foo/Gone.class", "gone");
        // Run 1 (production sequence): both classes synced — reconcile records
        // them with their deployed stamps.
        assertEquals(0, SyncManifest.reconcile(dst, manifest,
                Set.of("com/foo/Keep.class", "com/foo/Gone.class")));

        // Run 2 only syncs Keep (Gone deleted from source).
        int removed = SyncManifest.reconcile(dst, manifest, Set.of("com/foo/Keep.class"));

        assertEquals(1, removed);
        assertTrue(Files.exists(dst.resolve("com/foo/Keep.class")), "live class stays");
        assertFalse(Files.exists(dst.resolve("com/foo/Gone.class")), "class removed from source is cleaned");
    }

    @Test
    @DisplayName("reconcile — a class the sync NEVER wrote (artifact-provided) is PRESERVED")
    void reconcile02_neverSyncedArtifactClassPreserved(@TempDir Path tmp) throws Exception {
        // The reported bug: the artifact deploys a class from a root the module
        // resolver doesn't enumerate, so the mirror never covers it. It must never
        // be treated as an orphan and deleted (no ClassNotFoundException).
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path manifest = tmp.resolve("m.manifest");
        writeClass(dst, "com/app/config/MainConfig.class", "synced-bytes");
        writeClass(dst, "com/app/handler/extra/ExtraHandler.class", "artifact-bytes");
        // Run 1 records only what the SYNC wrote — not the artifact class.
        SyncManifest.reconcile(dst, manifest, Set.of("com/app/config/MainConfig.class"));

        int removed = SyncManifest.reconcile(
                dst, manifest, Set.of("com/app/config/MainConfig.class"));

        assertEquals(0, removed, "a class the sync never wrote must never be deleted");
        assertTrue(Files.exists(dst.resolve("com/app/handler/extra/ExtraHandler.class")),
                "artifact-deployed class the mirror doesn't cover must survive");
        assertTrue(Files.exists(dst.resolve("com/app/config/MainConfig.class")));
    }

    @Test
    @DisplayName("reconcile — first run with no prior manifest deletes nothing, records the synced set")
    void reconcile03_firstRunNoManifest(@TempDir Path tmp) throws Exception {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path manifest = tmp.resolve("m.manifest");
        writeClass(dst, "com/foo/Pre.class", "pre-existing-from-artifact");

        int removed = SyncManifest.reconcile(dst, manifest, Set.of("com/foo/Pre.class"));

        assertEquals(0, removed, "no prior manifest -> nothing is a proven orphan");
        assertTrue(Files.exists(dst.resolve("com/foo/Pre.class")));
        assertEquals(Set.of("com/foo/Pre.class"), SyncManifest.read(manifest),
                "the synced set is recorded for the next run");
    }

    @Test
    @DisplayName("reconcile — idempotent: re-running with the same synced set deletes nothing")
    void reconcile04_idempotent(@TempDir Path tmp) throws Exception {
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path manifest = tmp.resolve("m.manifest");
        writeClass(dst, "X.class", "x");
        Set<String> synced = Set.of("X.class");

        assertEquals(0, SyncManifest.reconcile(dst, manifest, synced));
        assertEquals(0, SyncManifest.reconcile(dst, manifest, synced));
        assertTrue(Files.exists(dst.resolve("X.class")));
    }

    @Test
    @DisplayName("reconcile — manifest round-trips; an absent manifest reads empty")
    void reconcile05_manifestRoundTrip(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("sub/dir/m.manifest"); // parent created on write
        Set<String> paths = Set.of("a/B.class", "c/D.class", "E.class");

        SyncManifest.write(manifest, paths);
        assertEquals(paths, SyncManifest.read(manifest));
        assertTrue(SyncManifest.read(tmp.resolve("nope.manifest")).isEmpty(),
                "an absent manifest reads as an empty set (safe: nothing deleted)");
    }

    @Test
    @DisplayName("reconcile integration — across two launches over a real WEB-INF/classes: manifest outside the webapp, stale removed, artifact preserved")
    void reconcile06_acrossLaunchesRealLayout(@TempDir Path tmp) throws Exception {
        // Real deployed layout: <docBase>/WEB-INF/classes. The manifest must land in
        // the IDE-owned store — NEVER inside the webapp — persist between launches,
        // and reconcile correctly through the same path computation syncDeployments uses.
        Path webInfClasses = Files.createDirectories(tmp.resolve("app/WEB-INF/classes"));
        Path manifest = DeployedClassesSync.classSyncManifestFor(webInfClasses);

        // ---- Launch 1: sync writes A and B; an artifact-only class C is also deployed. ----
        writeClass(webInfClasses, "com/app/A.class", "a");
        writeClass(webInfClasses, "com/app/B.class", "b");
        writeClass(webInfClasses, "vendor/extra/C.class", "artifact-c"); // the sync never produces this
        int removed1 = SyncManifest.reconcile(
                webInfClasses, manifest, Set.of("com/app/A.class", "com/app/B.class"));

        assertEquals(0, removed1, "first launch establishes the baseline and deletes nothing");
        assertTrue(Files.isRegularFile(manifest), "manifest is written");
        assertFalse(manifest.startsWith(tmp.resolve("app")),
                "manifest must live in the store, never inside the webapp");
        assertTrue(manifest.getFileName().toString().endsWith(".classsync.manifest"),
                "store file is identifiable by its kind");

        // ---- Launch 2: user removed B from source; the sync now only produces A. ----
        int removed2 = SyncManifest.reconcile(
                webInfClasses, manifest, Set.of("com/app/A.class"));

        assertEquals(1, removed2, "only the class removed from source is cleaned across launches");
        assertTrue(Files.exists(webInfClasses.resolve("com/app/A.class")), "live class stays");
        assertFalse(Files.exists(webInfClasses.resolve("com/app/B.class")), "class removed from source is cleaned");
        assertTrue(Files.exists(webInfClasses.resolve("vendor/extra/C.class")),
                "an artifact-only class the sync never wrote survives every launch");
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

        TreeMirror.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, true);

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

        TreeMirror.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, true);

        // Classes-only mode claims ONLY the .class as contributed — the
        // resource is deliberately unclaimed so a downstream reconcile keyed
        // on contributed paths removes the duplicate and the classpath holds
        // one copy (the one in the lib JAR).
        assertTrue(mr.contributedPaths().contains("com/example/dao/UserDao.class"),
                "the dependency .class must be claimed (it legitimately wins over the lib JAR copy)");
        assertFalse(mr.contributedPaths().contains("descriptors/registry.xml"),
                "the resource must NOT be claimed in classes-only mode, so a reconcile can remove the duplicate");
        assertFileContent(dst.resolve("com/example/dao/UserDao.class"), "dao");
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
        TreeMirror.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, false);

        assertEquals(3, mr.copied(), "own root mirrors .class AND resources");
        assertTrue(Files.exists(dst.resolve("com/example/web/HomeController.class")));
        assertTrue(Files.exists(dst.resolve("config/app.properties")),
                "the web module's own resources belong in WEB-INF/classes (not duplicated in any lib JAR)");
        assertTrue(Files.exists(dst.resolve("descriptors/registry.xml")));
    }

    // ===========================================================================
    // Covering-JAR gate: a dependency root whose classes are also deployed as a
    // WEB-INF/lib JAR may overlay that JAR ONLY with IDE output strictly newer
    // than the JAR — otherwise a stale loose class in WEB-INF/classes shadows a
    // freshly-rebuilt JAR forever (Tomcat searches WEB-INF/classes first).
    // The floor excludes at/below-floor files from contribution, so the
    // reconcile drops their previously-mirrored copies — "newest wins" in both
    // directions.
    // ===========================================================================

    @Test
    @DisplayName("covering-JAR floor — only sources newer than the JAR are mirrored or contributed")
    void jarFloor01_gateExcludesOlderSources(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("web/target/app/WEB-INF/classes"));

        writeClass(src, "com/example/dep/Old.class", "compiled-before-the-jar");
        writeClass(src, "com/example/dep/Fresh.class", "compiled-after-the-jar");
        long jarMtime = 100_000L;
        Files.setLastModifiedTime(src.resolve("com/example/dep/Old.class"),
                FileTime.fromMillis(jarMtime - 10_000));
        Files.setLastModifiedTime(src.resolve("com/example/dep/Fresh.class"),
                FileTime.fromMillis(jarMtime + 10_000));

        TreeMirror.MirrorResult mr = DeployedClassesSync.mirrorTree(src, dst, true, jarMtime);

        assertEquals(1, mr.copied(), "only the source newer than the JAR may overlay it");
        assertTrue(Files.exists(dst.resolve("com/example/dep/Fresh.class")));
        assertFalse(Files.exists(dst.resolve("com/example/dep/Old.class")),
                "an at/below-floor source must not create a shadowing overlay");
        assertTrue(mr.contributedPaths().contains("com/example/dep/Fresh.class"));
        assertFalse(mr.contributedPaths().contains("com/example/dep/Old.class"),
                "below-floor sources are unclaimed so the reconcile can drop stale copies");
        assertFalse(mr.walkFailed());
    }

    @Test
    @DisplayName("covering-JAR cycle — rebuilt JAR drops the overlay; a newer recompile restores it (newest wins)")
    void jarFloor02_newestWinsAcrossRebuilds(@TempDir Path tmp) throws Exception {
        // Full per-deployment sequence syncDeployments runs: drop pass, gated
        // mirror, reconcile with JAR coverage — across three user actions.
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path artifactRoot = Files.createDirectories(tmp.resolve("web/target/app"));
        Path dst = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        Path jar = artifactRoot.resolve("WEB-INF/lib/web-module-lib.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-v1");
        String jarRel = "WEB-INF/lib/web-module-lib.jar";
        String depClass = "com/example/dep/Util.class";
        Path manifest = tmp.resolve("m.manifest");

        // ---- Pass 1: IDE output newer than the JAR -> overlay is created. ----
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));
        writeClass(src, depClass, "ide-output-v1");
        Files.setLastModifiedTime(src.resolve(depClass), FileTime.fromMillis(110_000L));

        assertEquals(0, SyncManifest.dropStaleJarOverlays(dst, manifest, artifactRoot));
        TreeMirror.MirrorResult pass1 = DeployedClassesSync.mirrorTree(src, dst, true, 100_000L);
        assertEquals(1, pass1.copied());
        SyncManifest.reconcile(dst, manifest, pass1.contributedPaths(),
                Map.of(jarRel, new SyncManifest.JarCoverage(
                        SyncManifest.stampOf(jar), pass1.contributedPaths())));
        assertFileContent(dst.resolve(depClass), "ide-output-v1");

        // ---- Pass 2: build tool rebuilds the JAR (newer than the overlay). ----
        Files.writeString(jar, "jar-v2-rebuilt");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(200_000L));

        int dropped = SyncManifest.dropStaleJarOverlays(dst, manifest, artifactRoot);
        assertEquals(1, dropped, "the stale overlay shadowing the rebuilt JAR must be dropped");
        assertFalse(Files.exists(dst.resolve(depClass)), "the newer JAR serves now");
        TreeMirror.MirrorResult pass2 = DeployedClassesSync.mirrorTree(src, dst, true, 200_000L);
        assertEquals(0, pass2.copied(),
                "the gate must not resurrect the below-floor IDE output the drop just removed");
        assertFalse(Files.exists(dst.resolve(depClass)));
        SyncManifest.reconcile(dst, manifest, pass2.contributedPaths(),
                Map.of(jarRel, new SyncManifest.JarCoverage(
                        SyncManifest.stampOf(jar), pass2.contributedPaths())));

        // ---- Pass 3: the user edits and the IDE recompiles past the JAR. ----
        writeClass(src, depClass, "ide-output-v2-newer-than-jar");
        Files.setLastModifiedTime(src.resolve(depClass), FileTime.fromMillis(210_000L));

        assertEquals(0, SyncManifest.dropStaleJarOverlays(dst, manifest, artifactRoot),
                "an unchanged JAR triggers no drop");
        TreeMirror.MirrorResult pass3 = DeployedClassesSync.mirrorTree(src, dst, true, 200_000L);
        assertEquals(1, pass3.copied(), "IDE output newer than the JAR overlays it again");
        assertFileContent(dst.resolve(depClass), "ide-output-v2-newer-than-jar");
    }

    @Test
    @DisplayName("production sequence — syncArtifactTree itself drops, floors, and restores the overlay")
    void jarFloor03_productionSequenceNewestWins(@TempDir Path tmp) throws Exception {
        // Same story as jarFloor02, but driven through the seam syncDeployments
        // actually runs per artifact: the WEB-INF/lib scan, the drop pass, the
        // covering-JAR floor, the coverage recording, and the reconcile are the
        // PRODUCTION wiring here — reordering or dropping any pass in
        // syncArtifactTree fails this test.
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path artifactRoot = Files.createDirectories(tmp.resolve("web/target/app"));
        Path webInfClasses = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        Path jar = artifactRoot.resolve("WEB-INF/lib/common-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        String depClass = "com/example/dep/Util.class";
        var logger = org.mockito.Mockito.mock(
                com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger.class);
        // The dependency root resolves under the version-independent identity
        // "common"; the deployed JAR carries a versioned file name — the floor
        // only works if the scan ties the identity back to the real file.
        List<DeployedClassesSync.SourceRoot> roots =
                List.of(new DeployedClassesSync.SourceRoot(src, true, "common"));

        // ---- Pass 1: IDE output newer than the deployed JAR -> overlay created. ----
        Files.writeString(jar, "jar-v1");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));
        writeClass(src, depClass, "ide-output-v1");
        Files.setLastModifiedTime(src.resolve(depClass), FileTime.fromMillis(110_000L));

        DeployedClassesSync.ArtifactSyncOutcome pass1 = DeployedClassesSync.syncArtifactTree(
                "web-module", artifactRoot, webInfClasses, roots, logger);
        assertEquals(1, pass1.copied());
        assertFileContent(webInfClasses.resolve(depClass), "ide-output-v1");

        // ---- Pass 2: build tool rebuilds the JAR newer than the overlay. ----
        Files.writeString(jar, "jar-v2-rebuilt");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(200_000L));

        DeployedClassesSync.ArtifactSyncOutcome pass2 = DeployedClassesSync.syncArtifactTree(
                "web-module", artifactRoot, webInfClasses, roots, logger);
        assertEquals(0, pass2.copied(),
                "the floor must not resurrect below-floor IDE output the drop removed");
        assertFalse(Files.exists(webInfClasses.resolve(depClass)),
                "the rebuilt JAR serves now — the stale overlay must be dropped by the production sequence");

        // ---- Pass 3: the user edits and the IDE recompiles past the JAR. ----
        writeClass(src, depClass, "ide-output-v2-newer-than-jar");
        Files.setLastModifiedTime(src.resolve(depClass), FileTime.fromMillis(210_000L));

        DeployedClassesSync.ArtifactSyncOutcome pass3 = DeployedClassesSync.syncArtifactTree(
                "web-module", artifactRoot, webInfClasses, roots, logger);
        assertEquals(1, pass3.copied(), "IDE output newer than the JAR overlays it again");
        assertFileContent(webInfClasses.resolve(depClass), "ide-output-v2-newer-than-jar");
    }

    @Test
    @DisplayName("production sequence — a dependency jar the build named freely is still the cover: .class-only, floored, recorded")
    void jarFloor04_buildNamedJarIsTheCover(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path artifactRoot = Files.createDirectories(tmp.resolve("web/target/app"));
        Path webInfClasses = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        // Not "common-<version>.jar": a finalName, a classifier, a Gradle archive name.
        Path jar = artifactRoot.resolve("WEB-INF/lib/backend-final.jar");
        String depClass = "com/example/dep/Util.class";
        String oldClass = "com/example/dep/Legacy.class";
        String depResource = "com/example/dep/messages.properties";
        writeZip(jar, depClass, oldClass, depResource);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));
        writeClass(src, depClass, "ide-output-newer");
        writeClass(src, oldClass, "ide-output-older");
        Files.writeString(src.resolve(depResource), "key=value");
        Files.setLastModifiedTime(src.resolve(depClass), FileTime.fromMillis(110_000L));
        Files.setLastModifiedTime(src.resolve(oldClass), FileTime.fromMillis(90_000L));
        Files.setLastModifiedTime(src.resolve(depResource), FileTime.fromMillis(110_000L));
        var logger = org.mockito.Mockito.mock(TomcatDeploymentLogger.class);
        List<DeployedClassesSync.SourceRoot> roots =
                List.of(new DeployedClassesSync.SourceRoot(src, true, "common"));

        DeployedClassesSync.ArtifactSyncOutcome outcome = DeployedClassesSync.syncArtifactTree(
                "web-module", artifactRoot, webInfClasses, roots, logger);

        assertEquals(1, outcome.copied(), "only the class newer than the jar overlays it");
        assertFileContent(webInfClasses.resolve(depClass), "ide-output-newer");
        assertFalse(Files.exists(webInfClasses.resolve(oldClass)), "below the jar's floor: the jar serves it");
        assertFalse(Files.exists(webInfClasses.resolve(depResource)), ".class-only: the jar carries the resource");
        Map<String, Set<String>> coverage = DeployedClassesSync.overlayCoverage(artifactRoot);
        assertEquals(Set.of(depClass), coverage.get("WEB-INF/lib/backend-final.jar"),
                "the overlay is recorded under the jar's real name, so the duplicate warning knows it is the sync's own");
        org.mockito.Mockito.verify(logger).logServerWarning(org.mockito.ArgumentMatchers.argThat(
                msg -> msg.contains("1 resource file(s)") && msg.contains("backend-final.jar")));
    }

    @Test
    @DisplayName("covering-JAR floor — resources the policy excludes are counted only when newer than the jar")
    void jarFloor06_resourcesNewerThanJarCounted(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dep/target/classes"));
        Path dst = Files.createDirectories(tmp.resolve("web/target/app/WEB-INF/classes"));
        long jarMtime = 100_000L;
        writeClass(src, "com/example/dep/Util.class", "c");
        writeClass(src, "config/edited.properties", "k=v2");
        writeClass(src, "config/untouched.properties", "k=v");
        Files.setLastModifiedTime(src.resolve("com/example/dep/Util.class"), FileTime.fromMillis(jarMtime + 10));
        Files.setLastModifiedTime(src.resolve("config/edited.properties"), FileTime.fromMillis(jarMtime + 10));
        Files.setLastModifiedTime(src.resolve("config/untouched.properties"), FileTime.fromMillis(jarMtime - 10));

        TreeMirror.MirrorResult floored = DeployedClassesSync.mirrorTree(src, dst, true, jarMtime);
        assertEquals(1, floored.excludedNewerThanFloor(), "only the resource edited after the jar counts");
        assertEquals(1, floored.copied(), "the class still overlays; classes are never counted");

        TreeMirror.MirrorResult unfloored = DeployedClassesSync.mirrorTree(
                Files.createDirectories(tmp.resolve("dep2/target/classes")), dst, true);
        assertEquals(0, unfloored.excludedNewerThanFloor(), "no covering jar, nothing to be newer than");
    }

    @Test
    @DisplayName("production sequence — a module split into classes and resources roots: the resources root is covered too")
    void jarFloor05_splitRootsCoveredTogether(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("dep/build/classes/java/main"));
        Path resources = Files.createDirectories(tmp.resolve("dep/build/resources/main"));
        Path artifactRoot = Files.createDirectories(tmp.resolve("web/build/app"));
        Path webInfClasses = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        Path jar = artifactRoot.resolve("WEB-INF/lib/dep-custom.jar");
        String depClass = "com/example/dep/Util.class";
        String depResource = "application.properties";
        writeZip(jar, depClass, depResource);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(100_000L));
        writeClass(classes, depClass, "ide-output-newer");
        Files.setLastModifiedTime(classes.resolve(depClass), FileTime.fromMillis(110_000L));
        Files.writeString(resources.resolve(depResource), "k=v");
        Files.setLastModifiedTime(resources.resolve(depResource), FileTime.fromMillis(110_000L));
        var logger = org.mockito.Mockito.mock(TomcatDeploymentLogger.class);
        List<DeployedClassesSync.SourceRoot> roots = List.of(
                new DeployedClassesSync.SourceRoot(classes, true, "dep"),
                new DeployedClassesSync.SourceRoot(resources, true, "dep"));

        DeployedClassesSync.ArtifactSyncOutcome outcome = DeployedClassesSync.syncArtifactTree(
                "web-module", artifactRoot, webInfClasses, roots, logger);

        assertEquals(1, outcome.copied());
        assertFileContent(webInfClasses.resolve(depClass), "ide-output-newer");
        assertFalse(Files.exists(webInfClasses.resolve(depResource)),
                "the resources root shares the module's cover: the jar carries the resource");
        assertEquals(Set.of(depClass), DeployedClassesSync.overlayCoverage(artifactRoot).get("WEB-INF/lib/dep-custom.jar"));
    }

    private static void writeZip(Path zipFile, String... entries) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (String e : entries) {
                zip.putNextEntry(new java.util.zip.ZipEntry(e));
                zip.write(1);
                zip.closeEntry();
            }
        }
    }

    // ===========================================================================
    // walkFailed contract — deletion-safety guard for the caller's reconcile.
    // A root whose walk could not be trusted to fully enumerate its tree must
    // report walkFailed=true so syncDeployments defers SyncManifest.reconcile
    // (refreshing stamps only) rather than deleting deployed files the failed
    // root legitimately owns but never visited (partial-failure deletion).
    // ===========================================================================

    @Test
    @DisplayName("walkFailed — clean full walk reports walkFailed=false")
    void walkFailed01_cleanWalk(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        writeClass(src, "com/foo/Service.class", "bytes");

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertFalse(r.walkFailed(),
                "a fully-enumerated source root must not mark the walk failed");
    }

    @Test
    @DisplayName("walkFailed — empty source dir still walks cleanly (walkFailed=false)")
    void walkFailed02_emptySrcIsClean(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertFalse(r.walkFailed(),
                "an empty but readable source is a successful (empty) walk, not a failure");
    }

    @Test
    @DisplayName("walkFailed — non-directory src reports walkFailed=true (vanished root)")
    void walkFailed03_nonDirectorySrc(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("not-a-dir.txt");
        Files.writeString(file, "oops");
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(file, dst);

        assertTrue(r.walkFailed(),
                "a vanished / non-directory source root must defer the caller's orphan pass");
    }

    @Test
    @DisplayName("walkFailed — nested src/dst refusal reports walkFailed=true")
    void walkFailed04_nestedPathsRefused(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("root"));
        Path dst = Files.createDirectories(src.resolve("WEB-INF/classes")); // dst nested under src

        TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

        assertTrue(r.walkFailed(),
                "the nesting-guard refusal is an untrusted walk and must defer orphan removal");
    }

    @Test
    @DisplayName("walkFailed — an unvisitable entry (no-execute source subdir) reports walkFailed=true")
    void walkFailed05_unvisitableEntry(@TempDir Path tmp) throws Exception {
        // Files.isDirectory is USELESS inside visitFileFailed — the stat
        // itself failed, so it reports false for a real directory. ANY
        // unvisitable entry must mark the walk failed, or the subtree's
        // previously-synced deployed classes would be reconciled away while
        // their source still exists (mirrors WebResourcesSyncTest's pin).
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions required to simulate an unreadable subtree");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        writeClass(src, "com/foo/Visible.class", "bytes");
        Path locked = src.resolve("com/locked");
        writeClass(src, "com/locked/Hidden.class", "unreachable");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> readOnlyNoExec =
                java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> restore =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(locked, readOnlyNoExec);
        try {
            TreeMirror.MirrorResult r = DeployedClassesSync.mirrorTree(src, dst);

            assertTrue(r.walkFailed(),
                    "an unvisitable source entry must defer the caller's reconcile");
            assertTrue(r.contributedPaths().contains("com/foo/Visible.class"),
                    "the mirror still covers the readable part of the tree");
        } finally {
            Files.setPosixFilePermissions(locked, restore); // let @TempDir clean up
        }
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

    @Test
    @DisplayName("deployedTreeChanged: true when files are copied, false when up to date, true again when an orphan is removed")
    void deployedTreeChangedTracksCopiesAndOrphans(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("target/classes"));
        Path artifactRoot = Files.createDirectories(tmp.resolve("target/app"));
        Path webInfClasses = Files.createDirectories(artifactRoot.resolve("WEB-INF/classes"));
        Files.writeString(src.resolve("A.class"), "a");
        Files.writeString(src.resolve("B.class"), "b");
        List<DeployedClassesSync.SourceRoot> roots = List.of(new DeployedClassesSync.SourceRoot(src, false, null));
        TomcatDeploymentLogger logger = org.mockito.Mockito.mock(TomcatDeploymentLogger.class);

        DeployedClassesSync.ArtifactSyncOutcome first =
                DeployedClassesSync.syncArtifactTree("app", artifactRoot, webInfClasses, roots, logger);
        assertTrue(first.deployedTreeChanged(), "two files copied");

        DeployedClassesSync.ArtifactSyncOutcome second =
                DeployedClassesSync.syncArtifactTree("app", artifactRoot, webInfClasses, roots, logger);
        assertFalse(second.deployedTreeChanged(), "nothing to do");

        Files.delete(src.resolve("B.class"));
        DeployedClassesSync.ArtifactSyncOutcome third =
                DeployedClassesSync.syncArtifactTree("app", artifactRoot, webInfClasses, roots, logger);
        assertTrue(third.deployedTreeChanged(), "an orphan was removed from the deployed tree");
        assertFalse(Files.exists(webInfClasses.resolve("B.class")));
    }
}
