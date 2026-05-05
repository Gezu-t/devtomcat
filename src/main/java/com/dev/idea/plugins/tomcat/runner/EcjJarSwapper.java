package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Replaces an outdated {@code ecj-X.Y.Z.jar} inside a Tomcat install with a
 * newer release downloaded from Maven Central. Used by the 1.0.10
 * "Swap ECJ JAR" notification action that follows the ECJ-version-mismatch
 * warning surfaced by {@link EcjVersionCompat}.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li><b>Network only on user opt-in.</b> Detection (and the warning that
 *       triggers this swap) is local-only; the download fires only when the
 *       user explicitly clicks "Swap". No background fetch.</li>
 *   <li><b>SHA-1 verified.</b> Maven Central publishes a {@code .sha1}
 *       alongside every artifact. Mismatch refuses the swap and leaves the
 *       install untouched.</li>
 *   <li><b>Reversible.</b> The existing JAR is renamed to
 *       {@code <name>.devtomcat-bak} (in place, atomic move) before the new
 *       JAR lands. Users can restore by deleting the new JAR and renaming
 *       the backup back. {@link #execute} refuses to overwrite a pre-existing
 *       {@code .devtomcat-bak} so we never destroy a previous backup.</li>
 *   <li><b>API-injected I/O.</b> The {@link Downloader} interface lets tests
 *       inject canned responses without going to the network. Production
 *       paths use {@link Downloader#realNetwork()}.</li>
 *   <li><b>Idempotent failure.</b> Every failure path leaves the install in
 *       its original state. Temp files are cleaned up; partial downloads
 *       never overwrite the live JAR.</li>
 * </ul>
 *
 * <h2>Why ECJ 3.36.0 by default</h2>
 * Eclipse 4.30 / ECJ 3.36.0 supports class file majors up to Java 22 (major
 * 66) and is API-stable with the {@code ecj-3.7.x} that Tomcat 7 ships:
 * Tomcat's Jasper module invokes
 * {@code org.eclipse.jdt.internal.compiler.batch.Main}, which has remained
 * the entry point across every ECJ release. The swap is a drop-in
 * replacement and does not require any Tomcat configuration changes.
 *
 * @author Gezahegn Lemma (Gezu)
 */
final class EcjJarSwapper {

    private static final Logger LOG = Logger.getInstance(EcjJarSwapper.class);

    /** Default ECJ release the swap targets. Supports up to Java 22. */
    static final String DEFAULT_ECJ_VERSION = "3.36.0";

    /** Maven Central root for ECJ artifacts. */
    static final String MAVEN_CENTRAL_BASE =
            "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj";

    /** Suffix appended to the existing JAR file name when moving it aside. */
    static final String BACKUP_SUFFIX = ".devtomcat-bak";

    private EcjJarSwapper() {}

    // ------------------------------------------------------------------ //
    // Public API
    // ------------------------------------------------------------------ //

    /**
     * Computes the swap plan for an existing ECJ JAR. Does not access the
     * network; pure path calculation that callers can present to the user
     * for confirmation before triggering {@link #execute}.
     */
    @NotNull
    static SwapPlan computePlan(@NotNull Path currentEcjJar) {
        return computePlan(currentEcjJar, DEFAULT_ECJ_VERSION);
    }

    /** Same as {@link #computePlan(Path)} but lets the caller pin a specific ECJ version. */
    @NotNull
    static SwapPlan computePlan(@NotNull Path currentEcjJar, @NotNull String targetVersion) {
        Path lib = currentEcjJar.getParent();
        if (lib == null) {
            throw new IllegalArgumentException(
                    "ECJ JAR has no parent directory: " + currentEcjJar);
        }
        String targetFileName = "ecj-" + targetVersion + ".jar";
        Path targetEcjJar = lib.resolve(targetFileName);
        Path backupPath = currentEcjJar.resolveSibling(currentEcjJar.getFileName() + BACKUP_SUFFIX);
        URL downloadUrl = mavenCentralUrl(targetVersion, "");
        URL sha1Url = mavenCentralUrl(targetVersion, ".sha1");
        return new SwapPlan(currentEcjJar, targetEcjJar, backupPath,
                downloadUrl, sha1Url, targetVersion);
    }

    @NotNull
    private static URL mavenCentralUrl(@NotNull String version, @NotNull String suffix) {
        try {
            return URI.create(MAVEN_CENTRAL_BASE + "/" + version
                    + "/ecj-" + version + ".jar" + suffix).toURL();
        } catch (Exception e) {
            // Should never happen for the constants above, but keep the
            // error path well-typed so callers get a clean exception.
            throw new IllegalStateException(
                    "Failed to construct Maven Central URL for ECJ " + version, e);
        }
    }

    /**
     * Executes the swap described by {@code plan}. Returns a {@link SwapResult}
     * describing the outcome. Never throws; all failure modes are translated
     * to a {@link SwapResult} with {@link Outcome#FAILED} so the caller can
     * surface a single, predictable error path.
     *
     * <p>State left on disk on failure: the original ECJ JAR is in its
     * original location, no new files have been created in {@code lib/},
     * any temp files are cleaned up.
     */
    @NotNull
    static SwapResult execute(@NotNull SwapPlan plan,
                              @NotNull Downloader downloader,
                              @Nullable ProgressIndicator indicator) {
        // Pre-flight checks that do not touch disk.
        if (!Files.isRegularFile(plan.currentEcjJar())) {
            return SwapResult.failed("Existing ECJ JAR is missing or not a regular file: "
                    + plan.currentEcjJar());
        }
        Path lib = plan.currentEcjJar().getParent();
        if (lib == null || !Files.isWritable(lib)) {
            return SwapResult.failed("Tomcat lib directory is not writable: " + lib
                    + ". Run the IDE with permission to modify the Tomcat install,"
                    + " or perform the swap manually.");
        }
        if (Files.exists(plan.backupPath())) {
            return SwapResult.failed("A previous backup already exists at " + plan.backupPath()
                    + ". Inspect or remove that file before swapping again.");
        }
        if (Files.exists(plan.targetEcjJar()) && !plan.targetEcjJar().equals(plan.currentEcjJar())) {
            return SwapResult.failed("Target ECJ JAR already exists: " + plan.targetEcjJar()
                    + ". Remove it first or pick a different ECJ version.");
        }

        Path tmpJar = null;
        try {
            // Download into a sibling temp file so the atomic move at the
            // end is on the same filesystem (move-across-filesystems would
            // silently degrade to copy+delete and lose atomicity).
            if (indicator != null) {
                indicator.setText("Downloading ECJ " + plan.targetVersion() + " from Maven Central");
                indicator.setIndeterminate(false);
            }
            tmpJar = Files.createTempFile(lib, ".devtomcat-ecj-", ".jar.tmp");
            downloader.download(plan.downloadUrl(), tmpJar, indicator);

            if (indicator != null) {
                indicator.setText("Verifying SHA-1 checksum");
                indicator.setIndeterminate(true);
            }
            String expectedSha1 = downloader.fetchString(plan.sha1Url()).trim();
            // Maven Central writes "<sha1>  <filename>" or just "<sha1>".
            // Take the leading hex run.
            int firstSpace = expectedSha1.indexOf(' ');
            if (firstSpace > 0) expectedSha1 = expectedSha1.substring(0, firstSpace);

            String actualSha1 = sha1Hex(tmpJar);
            if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                return SwapResult.failed("SHA-1 mismatch on the downloaded ECJ JAR. "
                        + "Expected " + expectedSha1 + ", got " + actualSha1
                        + ". Refusing the swap; install left untouched.");
            }

            // Atomic move the existing JAR aside, then atomic move the new
            // JAR into place. If the second move fails, the first is rolled
            // back so the install is never left without an ECJ.
            if (indicator != null) {
                indicator.setText("Swapping ECJ JAR");
            }
            atomicMove(plan.currentEcjJar(), plan.backupPath());
            try {
                atomicMove(tmpJar, plan.targetEcjJar());
            } catch (IOException moveErr) {
                // Roll the backup back so the install is in its original state.
                try {
                    atomicMove(plan.backupPath(), plan.currentEcjJar());
                } catch (IOException rollbackErr) {
                    // Both moves failed; the user has to investigate manually.
                    LOG.warn("ECJ swap rollback failed", rollbackErr);
                    return SwapResult.failed("ECJ swap failed and rollback also failed: "
                            + moveErr.getMessage() + " (rollback: " + rollbackErr.getMessage() + ")."
                            + " Investigate " + lib + " manually.");
                }
                return SwapResult.failed("ECJ swap failed during final move: "
                        + moveErr.getMessage() + ". Backup rolled back; install untouched.");
            }
            tmpJar = null; // moved into place; do not delete in finally
            LOG.info("ECJ JAR swapped: " + plan.currentEcjJar() + " -> "
                    + plan.targetEcjJar() + " (backup at " + plan.backupPath() + ")");
            return SwapResult.success(plan.targetEcjJar(), plan.backupPath());

        } catch (IOException e) {
            LOG.warn("ECJ swap failed", e);
            return SwapResult.failed("ECJ swap failed: " + e.getMessage()
                    + ". Tomcat install was not modified.");
        } finally {
            if (tmpJar != null) {
                try { Files.deleteIfExists(tmpJar); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Restores a backup created by a previous {@link #execute} call. Used by
     * the "Undo swap" action. Verifies the install state matches what we
     * expect before touching anything.
     */
    @NotNull
    static SwapResult restoreBackup(@NotNull Path backupPath, @NotNull Path activeJar) {
        if (!Files.isRegularFile(backupPath)) {
            return SwapResult.failed("Backup not found at " + backupPath);
        }
        if (!Files.isRegularFile(activeJar)) {
            return SwapResult.failed("Active ECJ JAR not found at " + activeJar);
        }
        Path originalLocation = backupPath.resolveSibling(
                stripBackupSuffix(backupPath.getFileName().toString()));
        if (Files.exists(originalLocation) && !originalLocation.equals(activeJar)) {
            return SwapResult.failed("Original ECJ location is occupied by another file: "
                    + originalLocation);
        }
        try {
            atomicMove(activeJar, activeJar.resolveSibling(activeJar.getFileName() + ".devtomcat-replaced"));
            atomicMove(backupPath, originalLocation);
            // Leave the .devtomcat-replaced file behind for the user to
            // decide what to do with; we've already done the visible
            // restore. Logging it so it's discoverable.
            LOG.info("ECJ backup restored: " + backupPath + " -> " + originalLocation
                    + "; previous active JAR moved to " + activeJar.resolveSibling(
                            activeJar.getFileName() + ".devtomcat-replaced"));
            return SwapResult.success(originalLocation, null);
        } catch (IOException e) {
            return SwapResult.failed("Backup restore failed: " + e.getMessage());
        }
    }

    @NotNull
    private static String stripBackupSuffix(@NotNull String name) {
        return name.endsWith(BACKUP_SUFFIX)
                ? name.substring(0, name.length() - BACKUP_SUFFIX.length())
                : name;
    }

    // ------------------------------------------------------------------ //
    // I/O helpers
    // ------------------------------------------------------------------ //

    private static void atomicMove(@NotNull Path source, @NotNull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Filesystem doesn't support ATOMIC_MOVE; fall back to plain move.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @NotNull
    private static String sha1Hex(@NotNull Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable in JVM", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ------------------------------------------------------------------ //
    // Records / interfaces
    // ------------------------------------------------------------------ //

    /**
     * Plan for swapping a specific ECJ JAR. All paths and URLs computed up
     * front so the caller can present a clear confirmation dialog to the
     * user before triggering the network/disk operation.
     */
    record SwapPlan(
            @NotNull Path currentEcjJar,
            @NotNull Path targetEcjJar,
            @NotNull Path backupPath,
            @NotNull URL downloadUrl,
            @NotNull URL sha1Url,
            @NotNull String targetVersion
    ) {}

    /** Result of a {@link #execute} or {@link #restoreBackup} call. */
    record SwapResult(@NotNull Outcome outcome,
                      @Nullable Path newJarPath,
                      @Nullable Path backupPath,
                      @Nullable String errorMessage) {

        static SwapResult success(@NotNull Path newJarPath, @Nullable Path backupPath) {
            return new SwapResult(Outcome.SUCCESS, newJarPath, backupPath, null);
        }

        static SwapResult failed(@NotNull String errorMessage) {
            return new SwapResult(Outcome.FAILED, null, null, errorMessage);
        }

        boolean isSuccess() { return outcome == Outcome.SUCCESS; }
    }

    enum Outcome { SUCCESS, FAILED }

    /**
     * Network surface, abstracted so unit tests can inject canned responses.
     * Production callers use {@link #realNetwork()} which fetches over HTTPS.
     */
    interface Downloader {
        /**
         * Downloads {@code url} into {@code target}. {@code indicator} (when
         * non-null) is updated with download progress.
         */
        void download(@NotNull URL url, @NotNull Path target,
                      @Nullable ProgressIndicator indicator) throws IOException;

        /** Fetches {@code url} as a UTF-8 string. */
        @NotNull
        String fetchString(@NotNull URL url) throws IOException;

        /** Production HTTPS implementation. */
        @NotNull
        static Downloader realNetwork() {
            return new HttpsDownloader();
        }
    }

    /** Default {@link Downloader} that talks to the network via HttpsURLConnection. */
    private static final class HttpsDownloader implements Downloader {

        @Override
        public void download(@NotNull URL url, @NotNull Path target,
                             @Nullable ProgressIndicator indicator) throws IOException {
            HttpURLConnection conn = openConnection(url);
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + " from " + url);
                }
                long contentLength = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream()) {
                    long downloaded = 0;
                    byte[] buf = new byte[8192];
                    int read;
                    try (var out = Files.newOutputStream(target)) {
                        while ((read = in.read(buf)) != -1) {
                            if (indicator != null && indicator.isCanceled()) {
                                throw new IOException("Download cancelled by user");
                            }
                            out.write(buf, 0, read);
                            downloaded += read;
                            if (indicator != null && contentLength > 0) {
                                indicator.setFraction((double) downloaded / contentLength);
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
        }

        @Override
        @NotNull
        public String fetchString(@NotNull URL url) throws IOException {
            HttpURLConnection conn = openConnection(url);
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + " from " + url);
                }
                try (InputStream in = conn.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } finally {
                conn.disconnect();
            }
        }

        @NotNull
        private static HttpURLConnection openConnection(@NotNull URL url) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            // Maven Central tolerates a missing UA but it's good citizenship
            // to identify ourselves so traffic is attributable.
            conn.setRequestProperty("User-Agent", "DevTomcat-IDE-Plugin/1.0");
            return conn;
        }
    }
}
