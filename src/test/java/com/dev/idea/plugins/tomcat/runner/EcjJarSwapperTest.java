package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.projectRoots.JavaSdkVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the ECJ JAR swap primitive: download path, SHA-1 verification,
 * atomic backup-and-replace, rollback on failure, refusal of unsafe
 * preconditions.
 *
 * <p>Tests inject a {@link FakeDownloader} so no network access is required
 * and SHA mismatches can be exercised deterministically. The atomic-move
 * paths run against the local filesystem.
 */
class EcjJarSwapperTest {

    private static final String FAKE_ECJ_BODY = "FAKE-ECJ-CONTENTS";

    @Nested
    @DisplayName("computePlan")
    class PlanComputation {

        @Test
        @DisplayName("computes Maven Central URLs and sibling backup path")
        void computeDefaults(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);

            assertEquals(current, plan.currentEcjJar());
            assertEquals(lib.resolve("ecj-" + EcjJarSwapper.DEFAULT_ECJ_VERSION + ".jar"),
                    plan.targetEcjJar());
            assertEquals(lib.resolve("ecj-3.7.2.jar" + EcjJarSwapper.BACKUP_SUFFIX),
                    plan.backupPath());
            assertTrue(plan.downloadUrl().toString().startsWith("https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/"),
                    "Download URL must point at Maven Central");
            assertTrue(plan.sha1Url().toString().endsWith(".jar.sha1"),
                    "SHA-1 URL appended");
            assertEquals(EcjJarSwapper.DEFAULT_ECJ_VERSION, plan.targetVersion());
        }

        @Test
        @DisplayName("custom version is reflected in URLs and target file name")
        void customVersion(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current, "3.40.0");

            assertEquals(lib.resolve("ecj-3.40.0.jar"), plan.targetEcjJar());
            assertTrue(plan.downloadUrl().toString().contains("/3.40.0/"));
            assertEquals("3.40.0", plan.targetVersion());
        }
    }

    @Nested
    @DisplayName("execute — happy path")
    class Execute {

        @Test
        @DisplayName("downloads, verifies SHA-1, backs up the old JAR, and installs the new one")
        void successfulSwap(@TempDir Path tmp) throws Exception {
            // Arrange
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD-ECJ-CONTENTS");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            FakeDownloader downloader = FakeDownloader.serving(
                    plan.downloadUrl(), FAKE_ECJ_BODY.getBytes(),
                    plan.sha1Url(), sha1Of(FAKE_ECJ_BODY));

            // Act
            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            // Assert
            assertTrue(result.isSuccess(),
                    "Expected SUCCESS, got " + result.outcome() + " (" + result.errorMessage() + ")");
            assertEquals(plan.targetEcjJar(), result.newJarPath());
            assertEquals(plan.backupPath(), result.backupPath());
            // Backup retains the original contents.
            assertEquals("OLD-ECJ-CONTENTS", Files.readString(plan.backupPath()));
            // New JAR has the downloaded contents.
            assertEquals(FAKE_ECJ_BODY, Files.readString(plan.targetEcjJar()));
            // Original location no longer holds the old JAR.
            assertFalse(Files.exists(current) && !current.equals(plan.targetEcjJar()),
                    "Original ECJ JAR file moved aside");
            // No leftover temp files in lib/.
            try (var stream = Files.list(lib)) {
                assertTrue(stream.noneMatch(p -> p.getFileName().toString().contains(".tmp")),
                        "Temp file cleaned up");
            }
        }
    }

    @Nested
    @DisplayName("execute — refuses unsafe preconditions")
    class Preconditions {

        @Test
        @DisplayName("missing source JAR -> FAILED, no network, no disk changes")
        void missingSourceJar(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar"); // not created

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            FakeDownloader downloader = FakeDownloader.thatRecordsCalls();

            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("missing"),
                    "Error must mention missing source JAR: " + result.errorMessage());
            assertEquals(0, downloader.downloadCount(),
                    "Download must not be attempted when source is missing");
        }

        @Test
        @DisplayName("backup already exists -> FAILED, no overwrite of prior backup")
        void backupAlreadyPresent(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD");
            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            // Pre-create a backup file with content the user expects to keep.
            Files.writeString(plan.backupPath(), "PREVIOUS-BACKUP");

            FakeDownloader downloader = FakeDownloader.thatRecordsCalls();
            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("backup"),
                    "Error mentions the existing backup: " + result.errorMessage());
            assertEquals("PREVIOUS-BACKUP", Files.readString(plan.backupPath()),
                    "Existing backup contents must be preserved");
            assertEquals(0, downloader.downloadCount(),
                    "Download must not be attempted");
        }

        @Test
        @DisplayName("target JAR already exists at a different path -> FAILED")
        void targetCollision(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD");
            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            // A leftover same-version JAR from a previous swap blocks us
            // from creating the new one without trampling.
            Files.writeString(plan.targetEcjJar(), "STALE-PREVIOUS-SWAP");

            FakeDownloader downloader = FakeDownloader.thatRecordsCalls();
            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("Target ECJ JAR already exists"),
                    "Error mentions the colliding target: " + result.errorMessage());
            assertEquals("STALE-PREVIOUS-SWAP", Files.readString(plan.targetEcjJar()));
        }

        @Test
        @DisplayName("non-writable lib directory -> FAILED before any download")
        void nonWritableLib(@TempDir Path tmp) throws Exception {
            // Skip on Windows where Files.isWritable isn't a reliable
            // indicator of POSIX permissions.
            assumeTrue(!System.getProperty("os.name").toLowerCase().startsWith("windows"));

            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD");
            // Make lib/ read-only.
            Set<PosixFilePermission> readOnly = new HashSet<>();
            readOnly.add(PosixFilePermission.OWNER_READ);
            readOnly.add(PosixFilePermission.OWNER_EXECUTE);
            try {
                Files.setPosixFilePermissions(lib, readOnly);

                EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
                FakeDownloader downloader = FakeDownloader.thatRecordsCalls();
                EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

                assertFalse(result.isSuccess());
                assertTrue(result.errorMessage().contains("not writable"),
                        "Error mentions non-writable lib: " + result.errorMessage());
                assertEquals(0, downloader.downloadCount());
            } finally {
                // Restore so JUnit can clean the temp dir.
                Set<PosixFilePermission> rw = new HashSet<>();
                rw.add(PosixFilePermission.OWNER_READ);
                rw.add(PosixFilePermission.OWNER_WRITE);
                rw.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(lib, rw);
            }
        }
    }

    @Nested
    @DisplayName("execute — SHA-1 mismatch refuses the swap")
    class ShaMismatch {

        @Test
        @DisplayName("downloaded JAR with wrong SHA-1 -> FAILED, install untouched")
        void shaMismatchAborts(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            // Serve a body that does not match the published SHA-1.
            FakeDownloader downloader = FakeDownloader.serving(
                    plan.downloadUrl(), FAKE_ECJ_BODY.getBytes(),
                    plan.sha1Url(), sha1Of("DIFFERENT-CONTENT"));

            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("SHA-1 mismatch"),
                    "Error must call out the SHA mismatch: " + result.errorMessage());
            // Install untouched: original JAR still in place, no backup written.
            assertEquals("OLD", Files.readString(current));
            assertFalse(Files.exists(plan.backupPath()),
                    "No backup must be created when SHA verification fails");
            assertFalse(Files.exists(plan.targetEcjJar()),
                    "Target JAR must not be present after SHA mismatch");
        }

        @Test
        @DisplayName("Maven Central SHA file with trailing filename is parsed correctly")
        void shaWithFilenameSuffix(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path current = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(current, "OLD");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(current);
            String sha = sha1Of(FAKE_ECJ_BODY);
            // Maven Central historically wrote "<sha1>  <filename>" — pin
            // that the parser strips the trailing filename and accepts it.
            FakeDownloader downloader = FakeDownloader.serving(
                    plan.downloadUrl(), FAKE_ECJ_BODY.getBytes(),
                    plan.sha1Url(), sha + "  ecj-" + plan.targetVersion() + ".jar");

            EcjJarSwapper.SwapResult result = EcjJarSwapper.execute(plan, downloader, null);

            assertTrue(result.isSuccess(),
                    "Expected SUCCESS for two-token SHA file, got " + result.errorMessage());
        }
    }

    @Nested
    @DisplayName("restoreBackup")
    class Restore {

        @Test
        @DisplayName("restores .devtomcat-bak to its original location")
        void restoreHappyPath(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path active = lib.resolve("ecj-3.36.0.jar");
            Path backup = lib.resolve("ecj-3.7.2.jar" + EcjJarSwapper.BACKUP_SUFFIX);
            Files.writeString(active, "ACTIVE");
            Files.writeString(backup, "ORIGINAL");

            EcjJarSwapper.SwapResult result = EcjJarSwapper.restoreBackup(backup, active);

            assertTrue(result.isSuccess());
            Path expectedRestored = lib.resolve("ecj-3.7.2.jar");
            assertEquals(expectedRestored, result.newJarPath());
            assertEquals("ORIGINAL", Files.readString(expectedRestored));
            assertFalse(Files.exists(backup),
                    "Backup file should be moved into place, not duplicated");
            // The previously active JAR is preserved with a marker suffix
            // for the user to investigate.
            assertTrue(Files.exists(lib.resolve("ecj-3.36.0.jar.devtomcat-replaced")),
                    "Previously active JAR moved aside with the replaced-marker suffix");
        }

        @Test
        @DisplayName("missing backup -> FAILED, active JAR untouched")
        void missingBackup(@TempDir Path tmp) throws Exception {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path active = lib.resolve("ecj-3.36.0.jar");
            Path backup = lib.resolve("ecj-3.7.2.jar" + EcjJarSwapper.BACKUP_SUFFIX);
            Files.writeString(active, "ACTIVE");

            EcjJarSwapper.SwapResult result = EcjJarSwapper.restoreBackup(backup, active);

            assertFalse(result.isSuccess());
            assertEquals("ACTIVE", Files.readString(active),
                    "Active JAR untouched when backup is missing");
        }

        @Test
        @DisplayName("backup path without .devtomcat-bak suffix is refused (no silent move-to-self)")
        void wrongSuffixIsRefused(@TempDir Path tmp) throws Exception {
            // Regression: stripBackupSuffix returns the input unchanged when
            // the suffix is missing, which would make originalLocation equal
            // backupPath and turn the atomic-move into a silent no-op that
            // falsely reported success. Refuse up front instead.
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path active = lib.resolve("ecj-3.36.0.jar");
            // A regular JAR file, NOT a .devtomcat-bak — caller passed wrong path.
            Path notABackup = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(active, "ACTIVE");
            Files.writeString(notABackup, "NOT-ACTUALLY-A-BACKUP");

            EcjJarSwapper.SwapResult result = EcjJarSwapper.restoreBackup(notABackup, active);

            assertFalse(result.isSuccess(),
                    "Refusing a path without our managed suffix protects the user from silent no-ops");
            assertTrue(result.errorMessage().contains(EcjJarSwapper.BACKUP_SUFFIX),
                    "Error names the required suffix: " + result.errorMessage());
            assertEquals("ACTIVE", Files.readString(active));
            assertEquals("NOT-ACTUALLY-A-BACKUP", Files.readString(notABackup));
        }
    }

    // ---------------------------------------------------------------- //
    // Helpers
    // ---------------------------------------------------------------- //

    private static String sha1Of(String s) throws Exception {
        return sha1Of(s.getBytes());
    }

    private static String sha1Of(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    /**
     * In-memory {@link EcjJarSwapper.Downloader} that returns canned bytes
     * for known URLs and counts download invocations so refusal-without-
     * download tests can verify their precondition was hit before any
     * network call.
     */
    private static final class FakeDownloader implements EcjJarSwapper.Downloader {
        private final URL jarUrl;
        private final byte[] jarBody;
        private final URL shaUrl;
        private final String shaBody;
        private int downloadCount = 0;

        private FakeDownloader(@Nullable URL jarUrl, byte @Nullable [] jarBody,
                               @Nullable URL shaUrl, @Nullable String shaBody) {
            this.jarUrl = jarUrl;
            this.jarBody = jarBody;
            this.shaUrl = shaUrl;
            this.shaBody = shaBody;
        }

        static FakeDownloader serving(URL jarUrl, byte[] jarBody, URL shaUrl, String shaBody) {
            return new FakeDownloader(jarUrl, jarBody, shaUrl, shaBody);
        }

        static FakeDownloader thatRecordsCalls() {
            return new FakeDownloader(null, null, null, null);
        }

        int downloadCount() {
            return downloadCount;
        }

        @Override
        public void download(@NotNull URL url, @NotNull Path target,
                             @Nullable com.intellij.openapi.progress.ProgressIndicator indicator)
                throws IOException {
            downloadCount++;
            if (jarUrl == null || !jarUrl.toString().equals(url.toString())) {
                throw new IOException("Unexpected download URL in test: " + url);
            }
            Files.write(target, jarBody);
        }

        @Override
        @NotNull
        public String fetchString(@NotNull URL url) throws IOException {
            if (shaUrl != null && shaUrl.toString().equals(url.toString())) {
                return shaBody;
            }
            throw new IOException("Unexpected fetchString URL in test: " + url);
        }
    }

    @Nested
    @DisplayName("selectEcj — JVM-aware ECJ tier picker")
    class SelectEcj {

        @Test
        @DisplayName("Java 17+ JVM picks the modern ECJ that reads up to Java 22")
        void java17PicksModernEcj() {
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_17);
            assertEquals("3.36.0", pick.version());
            assertEquals(JavaSdkVersion.JDK_17, pick.minRuntimeJvm());
            assertEquals(66, pick.maxReadableClassFileMajor(),
                    "ECJ 3.36 should read up to Java 22 class files (major 66)");
        }

        @Test
        @DisplayName("Java 21 JVM picks the modern ECJ (highest tier)")
        void java21PicksModernEcj() {
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_21);
            assertEquals("3.36.0", pick.version());
        }

        @Test
        @DisplayName("Java 11 JVM picks ECJ 3.35 (last release before the Java 17 minimum)")
        void java11PicksLastJava11Ecj() {
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_11);
            assertEquals("3.35.0", pick.version());
            assertEquals(JavaSdkVersion.JDK_11, pick.minRuntimeJvm());
            assertEquals(65, pick.maxReadableClassFileMajor(),
                    "ECJ 3.35 should read up to Java 21 class files (major 65)");
        }

        @Test
        @DisplayName("Java 16 JVM picks ECJ 3.35 (Java 17 floor not satisfied)")
        void java16PicksLastJava11Ecj() {
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_16);
            assertEquals("3.35.0", pick.version());
        }

        @Test
        @DisplayName("Java 8 JVM picks ECJ 3.24 (last release before the Java 11 minimum) — bug repro")
        void java8PicksLastJava8Ecj() {
            // The motivating regression: a Java 8 host throws
            // UnsupportedClassVersionError if the swap unconditionally installs
            // ecj-3.36 (which is compiled for Java 17). The picker must
            // downgrade to ecj-3.24 here.
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_1_8);
            assertEquals("3.24.0", pick.version(),
                    "Java 8 must NOT receive ecj-3.36 (which requires Java 17 to load)");
            assertEquals(JavaSdkVersion.JDK_1_8, pick.minRuntimeJvm());
            assertEquals(59, pick.maxReadableClassFileMajor(),
                    "ECJ 3.24 should read up to Java 15 class files (major 59)");
        }

        @Test
        @DisplayName("null JVM defaults to the modern ECJ (preserves legacy single-version behaviour)")
        void nullJvmDefaultsToModernEcj() {
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(null);
            assertEquals("3.36.0", pick.version());
        }

        @Test
        @DisplayName("Java 7 JVM falls back to the lowest tier with a defensive log warning")
        void java7FallsBackToLowestTier() {
            // Every tier requires Java 8+. A Java 7 JVM cannot run any of
            // them, but rather than refuse the swap we pick the lowest tier
            // and let the runtime error point at the JVM as the upgrade
            // target. The picker logs a warning so the diagnostic is in the
            // log even if the user dismisses the prompt.
            EcjJarSwapper.EcjPick pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_1_7);
            assertEquals("3.24.0", pick.version());
        }

        @Test
        @DisplayName("canCompileClassFileMajor — Java 8 ECJ cannot read a Java 17 webapp")
        void java8EcjCannotReadJava17Classes() {
            EcjJarSwapper.EcjPick java8Pick = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_1_8);
            assertFalse(java8Pick.canCompileClassFileMajor(61),
                    "ECJ 3.24 (max class file 59 / Java 15) cannot read a Java 17 webapp (major 61)");
            assertTrue(java8Pick.canCompileClassFileMajor(59),
                    "ECJ 3.24 must still read Java 15 class files");
            assertTrue(java8Pick.canCompileClassFileMajor(52),
                    "ECJ 3.24 must read Java 8 class files");
        }

        @Test
        @DisplayName("canCompileClassFileMajor — Java 17 ECJ reads everything up to Java 22")
        void java17EcjReadsThroughJava22() {
            EcjJarSwapper.EcjPick modern = EcjJarSwapper.selectEcj(JavaSdkVersion.JDK_17);
            assertTrue(modern.canCompileClassFileMajor(61), "Java 17 webapp");
            assertTrue(modern.canCompileClassFileMajor(65), "Java 21 webapp");
            assertTrue(modern.canCompileClassFileMajor(66), "Java 22 webapp");
            assertFalse(modern.canCompileClassFileMajor(67),
                    "ECJ 3.36 should not pretend to read Java 23 class files");
        }
    }

    @Nested
    @DisplayName("detectStaleSwap — recovery for previous-version bad swaps")
    class DetectStaleSwap {

        @Test
        @DisplayName("Java 8 JVM with ecj-3.36 installed flags the swap as stale")
        void java8WithModernEcjIsStale(@TempDir Path lib) throws IOException {
            // Reproduces the user-reported state: Tomcat 7's lib/ contains
            // the modern ecj-3.36.0.jar (from a 1.0.10 swap) plus the
            // ecj-3.7.2.jar.devtomcat-bak preserved by that swap. Launch on
            // Java 8 → JSP request → UnsupportedClassVersionError. The
            // detector must flag this so the rollback prompt can fire.
            Files.writeString(lib.resolve("ecj-3.36.0.jar"), "fake-modern-ecj");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original-ecj");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_1_8);

            assertNotNull(stale, "Stale swap must be detected on Java 8 + ecj-3.36 install");
            assertEquals("3.36.0", stale.currentVersion());
            assertEquals(JavaSdkVersion.JDK_17, stale.installedRequiresJvm());
            assertEquals("ecj-3.36.0.jar", stale.currentEcjJar().getFileName().toString());
            assertEquals("ecj-3.7.2.jar.devtomcat-bak",
                    stale.backupPath().getFileName().toString());
            assertEquals("ecj-3.7.2.jar",
                    stale.restoreTarget().getFileName().toString());
        }

        @Test
        @DisplayName("Java 17 JVM with ecj-3.36 installed is not stale")
        void java17WithModernEcjIsNotStale(@TempDir Path lib) throws IOException {
            Files.writeString(lib.resolve("ecj-3.36.0.jar"), "fake-modern-ecj");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original-ecj");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_17);

            assertNull(stale, "ecj-3.36 loads fine on Java 17 — no rollback offered");
        }

        @Test
        @DisplayName("Java 11 JVM with ecj-3.35 installed is not stale (boundary)")
        void java11WithJava11EcjIsNotStale(@TempDir Path lib) throws IOException {
            Files.writeString(lib.resolve("ecj-3.35.0.jar"), "fake-3.35-ecj");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original-ecj");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_11);

            assertNull(stale, "ecj-3.35 requires Java 11 — fine on Java 11");
        }

        @Test
        @DisplayName("No backup file means we cannot offer a rollback")
        void noBackupReturnsNull(@TempDir Path lib) throws IOException {
            // A clean install or one swapped before the backup feature
            // existed. Without a backup we have nothing to restore to;
            // the detector must return null rather than guess.
            Files.writeString(lib.resolve("ecj-3.36.0.jar"), "fake-modern-ecj");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_1_8);

            assertNull(stale);
        }

        @Test
        @DisplayName("Multiple ecj-*.jar files is ambiguous; detector bails")
        void multipleEcjJarsBail(@TempDir Path lib) throws IOException {
            // A user might have manually placed an additional ECJ alongside
            // the swapped one. We can't tell which is "active" so the
            // detector defers to the user rather than guess wrong.
            Files.writeString(lib.resolve("ecj-3.36.0.jar"), "first");
            Files.writeString(lib.resolve("ecj-3.24.0.jar"), "second");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_1_8);

            assertNull(stale);
        }

        @Test
        @DisplayName("null JVM means we can't decide — no prompt")
        void nullJvmReturnsNull(@TempDir Path lib) throws IOException {
            Files.writeString(lib.resolve("ecj-3.36.0.jar"), "fake-modern-ecj");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original");

            EcjJarSwapper.StaleSwap stale = EcjJarSwapper.detectStaleSwap(lib, null);

            assertNull(stale);
        }

        @Test
        @DisplayName("Non-existent lib directory returns null without throwing")
        void missingLibDir(@TempDir Path tmp) {
            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(tmp.resolve("does-not-exist"),
                            JavaSdkVersion.JDK_1_8);
            assertNull(stale);
        }

        @Test
        @DisplayName("Java 8 with ecj-3.35 installed flags as stale (reaches Java 11 floor)")
        void java8WithJava11EcjIsStale(@TempDir Path lib) throws IOException {
            Files.writeString(lib.resolve("ecj-3.35.0.jar"), "fake-3.35-ecj");
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original-ecj");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_1_8);

            assertNotNull(stale);
            assertEquals(JavaSdkVersion.JDK_11, stale.installedRequiresJvm());
        }
    }

    @Nested
    @DisplayName("requiredJvmFor(Path) — JAR-introspection path (version-proof)")
    class RequiredJvmForJar {

        // Class-file majors:
        //   52 = Java 8, 55 = Java 11, 61 = Java 17, 65 = Java 21
        // Build minimal JAR fixtures that contain just enough header bytes
        // for the class-file-major reader to work.

        @Test
        @DisplayName("ECJ JAR with class-file major 61 reports JDK_17 (the bug repro)")
        void jarWithJava17ClassesReportsJdk17(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("ecj-3.36.0.jar");
            writeFakeEcjJar(jar, 61 /* Java 17 */);

            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(jar);
            assertEquals(JavaSdkVersion.JDK_17, required);
        }

        @Test
        @DisplayName("ECJ JAR with class-file major 55 reports JDK_11")
        void jarWithJava11ClassesReportsJdk11(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("ecj-3.35.0.jar");
            writeFakeEcjJar(jar, 55 /* Java 11 */);

            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(jar);
            assertEquals(JavaSdkVersion.JDK_11, required);
        }

        @Test
        @DisplayName("ECJ JAR with class-file major 52 reports JDK_1_8")
        void jarWithJava8ClassesReportsJdk18(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("ecj-3.24.0.jar");
            writeFakeEcjJar(jar, 52 /* Java 8 */);

            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(jar);
            assertEquals(JavaSdkVersion.JDK_1_8, required);
        }

        @Test
        @DisplayName("Future class-file major (e.g. 70 → Java 26) parses through JavaSdkVersion.fromVersionString")
        void jarWithFutureClassFileMajor(@TempDir Path tmp) throws IOException {
            // Version-proof contract: a hypothetical future ECJ release with
            // a class-file major beyond the current switch cases should
            // still resolve via fromVersionString — the mapping
            // (major - 44 = feature) is part of the JVM spec, not Eclipse's
            // release schedule. We don't assert a specific enum (the IDE
            // may not know about Java 26 yet) but the call must not throw.
            Path jar = tmp.resolve("ecj-future.jar");
            writeFakeEcjJar(jar, 70 /* hypothetical Java 26 */);

            // Must not throw. May return null if the platform doesn't know
            // about that JVM yet — caller falls back to version-string path.
            assertDoesNotThrow(() -> EcjJarSwapper.requiredJvmFor(jar));
        }

        @Test
        @DisplayName("Missing JAR returns null (not throws)")
        void missingJarReturnsNull(@TempDir Path tmp) {
            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(tmp.resolve("does-not-exist.jar"));
            assertNull(required);
        }

        @Test
        @DisplayName("JAR without the introspection class returns null")
        void jarWithoutIntrospectionClassReturnsNull(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("not-an-ecj.jar");
            // Write a JAR with a different class entry — INameEnvironment
            // is missing.
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(jar))) {
                out.putNextEntry(new java.util.zip.ZipEntry("com/example/Other.class"));
                out.write(buildClassFileHeader(52));
                out.closeEntry();
            }

            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(jar);
            assertNull(required, "Missing introspection class must yield null, not a default");
        }

        @Test
        @DisplayName("JAR with garbage in place of the magic number returns null")
        void garbageMagicReturnsNull(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("not-a-class.jar");
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(jar))) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                        "org/eclipse/jdt/internal/compiler/env/INameEnvironment.class"));
                out.write(new byte[]{0x00, 0x00, 0x00, 0x00, 0, 0, 0, 0});
                out.closeEntry();
            }

            JavaSdkVersion required = EcjJarSwapper.requiredJvmFor(jar);
            assertNull(required, "Bad magic must yield null");
        }

        @Test
        @DisplayName("classFileMajorToJvm spec mapping holds for the common range")
        void classFileMajorMapping() {
            assertEquals(JavaSdkVersion.JDK_1_8, EcjJarSwapper.classFileMajorToJvm(52));
            assertEquals(JavaSdkVersion.JDK_1_9, EcjJarSwapper.classFileMajorToJvm(53));
            assertEquals(JavaSdkVersion.JDK_11,  EcjJarSwapper.classFileMajorToJvm(55));
            assertEquals(JavaSdkVersion.JDK_17,  EcjJarSwapper.classFileMajorToJvm(61));
        }

        @Test
        @DisplayName("detectStaleSwap reads the JAR and ignores a misleading filename")
        void detectStaleSwapPrefersJarOverFilename(@TempDir Path lib) throws IOException {
            // The active JAR's FILENAME says ecj-3.24.0 (which would map to
            // Java 8 via the version-string path), but the JAR ACTUALLY
            // contains class-file major 61 (Java 17). Detection must trust
            // the bytecode over the filename — otherwise an ECJ rebuilt
            // for a newer JVM but kept under an old filename would slip
            // past the check.
            Path active = lib.resolve("ecj-3.24.0.jar");
            writeFakeEcjJar(active, 61 /* Java 17 — JAR is mislabelled */);
            Files.writeString(lib.resolve("ecj-3.7.2.jar.devtomcat-bak"), "fake-original");

            EcjJarSwapper.StaleSwap stale =
                    EcjJarSwapper.detectStaleSwap(lib, JavaSdkVersion.JDK_1_8);

            assertNotNull(stale, "Detector must read JAR bytes, not just the filename");
            assertEquals(JavaSdkVersion.JDK_17, stale.installedRequiresJvm());
        }

        @NotNull
        private static byte[] buildClassFileHeader(int major) {
            // 4 bytes magic + 2 bytes minor + 2 bytes major
            byte[] hdr = new byte[8];
            hdr[0] = (byte) 0xCA; hdr[1] = (byte) 0xFE;
            hdr[2] = (byte) 0xBA; hdr[3] = (byte) 0xBE;
            hdr[4] = 0; hdr[5] = 0;            // minor = 0
            hdr[6] = (byte) ((major >> 8) & 0xFF);
            hdr[7] = (byte) (major & 0xFF);
            return hdr;
        }

        private static void writeFakeEcjJar(@NotNull Path jar, int classFileMajor) throws IOException {
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(jar))) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                        "org/eclipse/jdt/internal/compiler/env/INameEnvironment.class"));
                out.write(buildClassFileHeader(classFileMajor));
                out.closeEntry();
            }
        }
    }

    @Nested
    @DisplayName("requiredJvmFor — ECJ version → minimum JVM mapping")
    class RequiredJvmFor {

        @Test
        @DisplayName("ECJ 3.36+ requires Java 17")
        void modern() {
            assertEquals(JavaSdkVersion.JDK_17, EcjJarSwapper.requiredJvmFor("3.36.0"));
            assertEquals(JavaSdkVersion.JDK_17, EcjJarSwapper.requiredJvmFor("3.40.0"));
        }

        @Test
        @DisplayName("ECJ 3.25–3.35 requires Java 11")
        void java11Era() {
            assertEquals(JavaSdkVersion.JDK_11, EcjJarSwapper.requiredJvmFor("3.35.0"));
            assertEquals(JavaSdkVersion.JDK_11, EcjJarSwapper.requiredJvmFor("3.30.0"));
            assertEquals(JavaSdkVersion.JDK_11, EcjJarSwapper.requiredJvmFor("3.25.0"));
        }

        @Test
        @DisplayName("ECJ ≤ 3.24 requires Java 8")
        void java8Era() {
            assertEquals(JavaSdkVersion.JDK_1_8, EcjJarSwapper.requiredJvmFor("3.24.0"));
            assertEquals(JavaSdkVersion.JDK_1_8, EcjJarSwapper.requiredJvmFor("3.21.0"));
            assertEquals(JavaSdkVersion.JDK_1_8, EcjJarSwapper.requiredJvmFor("3.7.2"));
        }
    }

    @Nested
    @DisplayName("restoreFromBackup — recovery primitive")
    class RestoreFromBackup {

        @Test
        @DisplayName("Atomically restores backup and moves displaced JAR to .devtomcat-replaced sidecar")
        void atomicRestore(@TempDir Path lib) throws IOException {
            Path active = lib.resolve("ecj-3.36.0.jar");
            Path backup = lib.resolve("ecj-3.7.2.jar.devtomcat-bak");
            Path restoreTarget = lib.resolve("ecj-3.7.2.jar");
            Files.writeString(active, "modern-content");
            Files.writeString(backup, "original-content");

            EcjJarSwapper.StaleSwap stale = new EcjJarSwapper.StaleSwap(
                    active, backup, restoreTarget,
                    "3.36.0", JavaSdkVersion.JDK_17);

            EcjJarSwapper.SwapResult result = EcjJarSwapper.restoreFromBackup(stale);

            assertTrue(result.isSuccess(), "restore should succeed: " + result.errorMessage());
            assertTrue(Files.exists(restoreTarget), "ecj-3.7.2.jar must be back in place");
            assertEquals("original-content", Files.readString(restoreTarget),
                    "Restored content must match the backup, not the displaced JAR");
            assertFalse(Files.exists(backup), "Backup must be consumed by the restore");
            assertFalse(Files.exists(active),
                    "Displaced JAR must move out of its original location");
            assertTrue(Files.exists(lib.resolve("ecj-3.36.0.jar.devtomcat-replaced")),
                    "Displaced JAR must land at .devtomcat-replaced for inspection");
        }
    }

    @Nested
    @DisplayName("computePlan(currentEcjJar, runtimeJvm) — version selection")
    class ComputePlanWithJvm {

        @Test
        @DisplayName("Java 17 JVM produces a plan targeting ecj-3.36.0")
        void java17PlanTargetsModern(@TempDir Path tomcatLib) throws IOException {
            Path ecj = tomcatLib.resolve("ecj-3.7.2.jar");
            Files.writeString(ecj, "stub");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(ecj, JavaSdkVersion.JDK_17);

            assertEquals("3.36.0", plan.targetVersion());
            assertEquals("ecj-3.36.0.jar", plan.targetEcjJar().getFileName().toString());
        }

        @Test
        @DisplayName("Java 8 JVM produces a plan targeting ecj-3.24.0 — bug repro")
        void java8PlanTargetsLegacyEcj(@TempDir Path tomcatLib) throws IOException {
            // Reproduces the user-reported failure mode: launching with
            // Java 8 (Corretto-1.8) on a webapp whose ECJ swap installed
            // ecj-3.36.0 unconditionally. The plan must now target ecj-3.24.0
            // so the JAR actually loads on Java 8.
            Path ecj = tomcatLib.resolve("ecj-3.7.2.jar");
            Files.writeString(ecj, "stub");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(ecj, JavaSdkVersion.JDK_1_8);

            assertEquals("3.24.0", plan.targetVersion());
            assertEquals("ecj-3.24.0.jar", plan.targetEcjJar().getFileName().toString());
            assertTrue(plan.downloadUrl().toString().contains("/ecj/3.24.0/"));
            assertTrue(plan.sha1Url().toString().endsWith(".jar.sha1"));
        }

        @Test
        @DisplayName("null JVM produces a plan targeting the default modern ECJ")
        void nullJvmPlanTargetsDefault(@TempDir Path tomcatLib) throws IOException {
            Path ecj = tomcatLib.resolve("ecj-3.7.2.jar");
            Files.writeString(ecj, "stub");

            EcjJarSwapper.SwapPlan plan = EcjJarSwapper.computePlan(ecj, (JavaSdkVersion) null);

            assertEquals(EcjJarSwapper.DEFAULT_ECJ_VERSION, plan.targetVersion());
        }
    }
}
