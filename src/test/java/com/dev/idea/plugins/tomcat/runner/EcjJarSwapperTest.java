package com.dev.idea.plugins.tomcat.runner;

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
}
