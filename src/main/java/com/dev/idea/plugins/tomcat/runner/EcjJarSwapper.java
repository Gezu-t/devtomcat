package com.dev.idea.plugins.tomcat.runner;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    /**
     * Default ECJ release for modern (Java 17+) JVMs. Reads class files up
     * to Java 22 (major 66). Older JVMs fall back to a legacy ECJ via
     * {@link #selectEcjVersion} so the swapped JAR can actually load on the
     * runtime JVM — the regression that motivated the picker is a webapp
     * launched with Java 8 throwing
     * {@code UnsupportedClassVersionError: org/eclipse/jdt/internal/compiler/env/INameEnvironment
     * has been compiled by a more recent version of the Java Runtime
     * (class file version 61.0)} on the first JSP request because the swap
     * had unconditionally installed a Java 17-built ECJ.
     */
    static final String DEFAULT_ECJ_VERSION = "3.36.0";

    /**
     * Tiered ECJ release picks per JVM minimum. Each entry pairs a JVM floor
     * with the highest ECJ release that still runs on that floor and the
     * highest webapp class-file major that release can read. Sorted highest
     * JVM first so {@link #selectEcj} can return the first match.
     *
     * <ul>
     *   <li><b>3.36.0</b> on Java 17+: reads up to Java 22 (major 66).</li>
     *   <li><b>3.35.0</b> on Java 11+: last release before the Java 17 minimum;
     *       reads up to Java 21 (major 65).</li>
     *   <li><b>3.24.0</b> on Java 8+: last release before the Java 11 minimum;
     *       reads up to Java 15 (major 59).</li>
     * </ul>
     *
     * <p>If the runtime JVM cannot read the webapp's class-file major even
     * with the highest tier we can install, that is a JVM-upgrade decision
     * the user has to make — no ECJ exists that bridges Java 8 to a Java 17
     * webapp. {@link #selectEcj} returns the chosen tier alongside its
     * {@link EcjPick#maxReadableClassFileMajor} so callers can detect that
     * deeper incompatibility and surface a clear message instead of
     * silently picking an ECJ that will fail at JSP-compile time anyway.
     *
     * <h3>Maintenance contract</h3>
     * This table is hardcoded by deliberate choice — querying Maven Central
     * for the latest ECJ at swap time would require network at swap-time
     * and add a dependency surface for marginal benefit. The table is only
     * consumed by:
     * <ol>
     *   <li>{@link #selectEcj} — the forward-path picker. If a newer ECJ
     *       release appears upstream, the picker keeps recommending the
     *       last version it knows about. That is a soft degradation
     *       (suboptimal but functional swap), not a regression — the
     *       recommended JAR still loads on its tier's JVM floor.</li>
     *   <li>{@link #requiredJvmFor(String)} — version-string fallback for
     *       stale-swap detection. The JAR-introspection path
     *       ({@link #requiredJvmFor(Path)}) is preferred and reads the
     *       actual class-file major; this version-string mapping is only
     *       used when the JAR cannot be read.</li>
     * </ol>
     * When Eclipse releases a new ECJ that bumps the JVM floor, add a row
     * at the top with the new floor's first version as
     * {@code tierStartVersion}, the latest of THIS tier as the previous
     * row's {@code version}, and update the tier-end version of the
     * adjacent row. Do not edit blindly — verify the bump in the Eclipse
     * release notes before changing.
     */
    private static final EcjTier[] ECJ_TIERS = {
            // tier name        latest    starts at  min JVM                max class-file
            new EcjTier("3.36.0", "3.36.0", JavaSdkVersion.JDK_17,  66 /* Java 22 */),
            new EcjTier("3.35.0", "3.25.0", JavaSdkVersion.JDK_11,  65 /* Java 21 */),
            new EcjTier("3.24.0", "3.21.0", JavaSdkVersion.JDK_1_8, 59 /* Java 15 */)
    };

    /** Maven Central root for ECJ artifacts — the default download base. */
    static final String MAVEN_CENTRAL_BASE =
            "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj";

    /**
     * Registry key overriding the ECJ download base URL. Lets users behind a
     * firewall — or where Maven Central is slow/blocked, e.g. mainland China —
     * point the swap at a mirror such as Aliyun or Huawei. Standard Maven layout
     * is assumed: the plugin appends {@code /<version>/ecj-<version>.jar}.
     */
    static final String REG_ECJ_MIRROR_BASE_URL = "devtomcat.ecj.mirror.base.url";

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
     *
     * <p>Picks {@link #DEFAULT_ECJ_VERSION} unconditionally. Prefer
     * {@link #computePlan(Path, JavaSdkVersion)} so the picked ECJ matches
     * the JVM that will host Tomcat — installing ecj-3.36 on a Java 8 host
     * appears to succeed but throws {@code UnsupportedClassVersionError} on
     * the first JSP request.
     */
    @NotNull
    static SwapPlan computePlan(@NotNull Path currentEcjJar) {
        return computePlan(currentEcjJar, DEFAULT_ECJ_VERSION);
    }

    /**
     * Computes the swap plan, picking the highest ECJ tier whose runtime-JVM
     * floor is satisfied by {@code runtimeJvm}. Falls back to
     * {@link #DEFAULT_ECJ_VERSION} when the JVM is unknown so existing setups
     * on modern hosts behave identically to the legacy single-version
     * {@link #computePlan(Path)} path.
     */
    @NotNull
    static SwapPlan computePlan(@NotNull Path currentEcjJar, @Nullable JavaSdkVersion runtimeJvm) {
        return computePlan(currentEcjJar, selectEcj(runtimeJvm).version());
    }

    /**
     * Registry key allowing the swap target ECJ version to be pinned by an
     * advanced user (typically a corporate environment with a compliance
     * requirement on a specific Eclipse JDT release). When set, the picker
     * returns the pinned version verbatim rather than walking the tier
     * table — the user accepts responsibility for the runtime constraints.
     *
     * <p>Empty string disables the override and the JVM-aware picker runs
     * normally. Default is empty so existing setups behave identically to
     * the version that did not have the override.
     */
    private static final String REGISTRY_KEY_VERSION_OVERRIDE = "devtomcat.ecj.target.version";

    /**
     * Selects the ECJ tier compatible with the given runtime JVM. Returns the
     * default (modern) tier when {@code runtimeJvm} is null. Never returns
     * null: every supported JVM down to Java 8 has a tier; older JVMs fall
     * through to the lowest tier with a defensive note in the log.
     *
     * <p>Honours the {@value #REGISTRY_KEY_VERSION_OVERRIDE} registry key:
     * when set to a non-empty version string, the picker returns that
     * version verbatim. The metadata fields ({@code minRuntimeJvm},
     * {@code maxReadableClassFileMajor}) come from the closest matching
     * tier so downstream prompts can still render an informative message;
     * when no tier matches (override version older than all known tiers),
     * the metadata defaults to permissive ({@code JDK_1_8} /
     * {@code Integer.MAX_VALUE}) so the user's pin is not second-guessed.
     */
    @NotNull
    static EcjPick selectEcj(@Nullable JavaSdkVersion runtimeJvm) {
        String override = readVersionOverride();
        if (!override.isEmpty()) {
            return overridePick(override);
        }
        if (runtimeJvm == null) {
            EcjTier highest = ECJ_TIERS[0];
            return new EcjPick(highest.version(), highest.minRuntimeJvm(),
                    highest.maxReadableClassFileMajor());
        }
        for (EcjTier tier : ECJ_TIERS) {
            if (runtimeJvm.isAtLeast(tier.minRuntimeJvm())) {
                return new EcjPick(tier.version(), tier.minRuntimeJvm(),
                        tier.maxReadableClassFileMajor());
            }
        }
        // Java 7 or older — every tier wants Java 8+. Pick the lowest tier
        // and log; the user will hit a runtime error but the log gives them
        // a precise diagnostic instead of a silent failure.
        EcjTier lowest = ECJ_TIERS[ECJ_TIERS.length - 1];
        LOG.warn("Runtime JVM " + runtimeJvm + " is below every ECJ tier's floor;"
                + " falling back to ECJ " + lowest.version()
                + " which still requires Java " + lowest.minRuntimeJvm()
                + ". The webapp's JRE must be upgraded for the swap to load.");
        return new EcjPick(lowest.version(), lowest.minRuntimeJvm(),
                lowest.maxReadableClassFileMajor());
    }

    /**
     * Reads the version-override registry key, returning the trimmed value
     * or an empty string if the key is unset / blank / unavailable. Never
     * throws — registry access in test classloaders or detached IDE
     * lifecycles can fail; we treat any error as "no override".
     */
    @NotNull
    private static String readVersionOverride() {
        try {
            String raw = Registry.stringValue(REGISTRY_KEY_VERSION_OVERRIDE);
            return raw == null ? "" : raw.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Builds an {@link EcjPick} for a user-pinned override version. Best-
     * effort metadata: when the override matches a known tier, the tier's
     * JVM floor and class-file ceiling carry through; otherwise we default
     * to permissive values so the override is not blocked by checks built
     * around the tier table.
     */
    @NotNull
    private static EcjPick overridePick(@NotNull String version) {
        EcjTier match = lookupTierForVersion(version);
        if (match != null) {
            return new EcjPick(version, match.minRuntimeJvm(), match.maxReadableClassFileMajor());
        }
        // Unknown / pre-tier version — trust the user, do not gate.
        return new EcjPick(version, JavaSdkVersion.JDK_1_8, Integer.MAX_VALUE);
    }

    /**
     * Returns the {@link EcjTier} whose {@code tierStartVersion} is &le; the
     * given version, walking highest-floor first. Used by both
     * {@link #requiredJvmFor(String)} and {@link #overridePick} so the two
     * version-string lookups share one source of truth. Returns null when
     * no tier matches (input is older than every tier's start).
     */
    @Nullable
    private static EcjTier lookupTierForVersion(@NotNull String ecjVersion) {
        for (EcjTier tier : ECJ_TIERS) {
            if (compareEcjVersions(ecjVersion, tier.tierStartVersion()) >= 0) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Class entries inside ECJ that we probe in order to determine the JVM
     * the JAR was compiled for. The first entry is what Tomcat's Jasper
     * loads first ({@code INameEnvironment} — the class that triggers the
     * user-reported {@code UnsupportedClassVersionError}); the rest are
     * stable ECJ entry points kept around as fallbacks so detection still
     * works if Eclipse ever refactors internal packages and removes
     * {@code INameEnvironment} from this exact path.
     *
     * <p>Order: most-canonical first, descending in stability. Each entry
     * has been in ECJ for many releases; finding any one is enough to
     * read a class-file major.
     */
    private static final String[] ECJ_INTROSPECT_CLASSES = {
            "org/eclipse/jdt/internal/compiler/env/INameEnvironment.class",
            "org/eclipse/jdt/internal/compiler/batch/Main.class",
            "org/eclipse/jdt/internal/compiler/Compiler.class",
            "org/eclipse/jdt/core/compiler/CompilationProgress.class"
    };

    /**
     * Reads the bundled ECJ JAR and returns the minimum JVM it requires
     * based on the class-file major version of one of
     * {@link #ECJ_INTROSPECT_CLASSES}. Probes the list in order and uses
     * the first entry that exists in the JAR. Class-file majors map to JVM
     * features as: 52 → 8, 53 → 9, 55 → 11, 61 → 17, etc.
     * ({@code feature = major - 44} for Java 5 and later).
     *
     * <p>Returns {@code null} when the JAR is missing, unreadable, or does
     * not contain any probe class — callers should then fall back to
     * {@link #requiredJvmFor(String)} or treat the swap as ambiguous.
     *
     * <p>This is the version-proof path: even if Eclipse releases a new ECJ
     * with a higher JVM floor than anything in {@link #ECJ_TIERS}, the
     * detection logic reads the actual bytecode and gets the answer right
     * without needing the table updated. The multi-class probe insulates
     * against the additional risk that any single canonical class is
     * renamed or moved upstream.
     */
    @Nullable
    static JavaSdkVersion requiredJvmFor(@NotNull Path ecjJar) {
        for (String classEntry : ECJ_INTROSPECT_CLASSES) {
            Integer major = readClassFileMajor(ecjJar, classEntry);
            if (major != null) {
                return classFileMajorToJvm(major);
            }
        }
        return null;
    }

    /**
     * Reads the class-file major version from a single class entry inside a
     * JAR. Returns {@code null} when the JAR is missing, the entry is not
     * present, or the entry is too short to contain a class-file header.
     * Defensive: never throws — the caller's fallback path stays usable.
     */
    @Nullable
    static Integer readClassFileMajor(@NotNull Path jarPath, @NotNull String classEntryName) {
        if (!Files.isRegularFile(jarPath)) return null;
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry(classEntryName);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] header = in.readNBytes(8);
                if (header.length < 8) return null;
                // Class-file format (JVMS §4.1):
                //   u4 magic     = 0xCAFEBABE   (bytes 0..3)
                //   u2 minor     =              (bytes 4..5)
                //   u2 major     =              (bytes 6..7)
                int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
                          | ((header[2] & 0xff) << 8)  |  (header[3] & 0xff);
                if (magic != 0xCAFEBABE) return null;
                return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
            }
        } catch (IOException e) {
            LOG.debug("Could not read class-file major from " + jarPath
                    + " entry " + classEntryName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Maps a class-file major version to the JVM feature version that
     * introduced it. Class-file format is stable across the JVM spec
     * (JVMS §4.1, table 4.1-A): {@code feature = major - 44} for Java 5+.
     *
     * <p>Class-file majors are part of the JVM specification, not Eclipse's
     * release schedule, so this mapping does not depend on any third-party
     * version table. Translation is correct for every JVM, including ones
     * the IDE doesn't have an enum value for (the fallback uses
     * {@link JavaSdkVersion#fromVersionString} on the computed feature).
     */
    @Nullable
    static JavaSdkVersion classFileMajorToJvm(int classFileMajor) {
        if (classFileMajor < 52) return JavaSdkVersion.JDK_1_8;  // pre-Java 8 floor we still support
        // Common values get an enum directly so the picker / detector logs
        // are stable string-printed across IntelliJ versions where the enum
        // names have shifted.
        switch (classFileMajor) {
            case 52: return JavaSdkVersion.JDK_1_8;
            case 53: return JavaSdkVersion.JDK_1_9;
            case 54: return JavaSdkVersion.JDK_10;
            case 55: return JavaSdkVersion.JDK_11;
            case 56: return JavaSdkVersion.JDK_12;
            case 57: return JavaSdkVersion.JDK_13;
            case 58: return JavaSdkVersion.JDK_14;
            case 59: return JavaSdkVersion.JDK_15;
            case 60: return JavaSdkVersion.JDK_16;
            case 61: return JavaSdkVersion.JDK_17;
            default:
                // Java 18+ — let the platform parser handle it. If a future
                // class-file major lands in a JAR before IntelliJ knows
                // about it, fromVersionString returns null and we fall back
                // to "modern default" upstream.
                int feature = classFileMajor - 44;
                return JavaSdkVersion.fromVersionString(String.valueOf(feature));
        }
    }

    /**
     * Returns the minimum JVM required to load an ECJ JAR of the given
     * Maven version. Used by stale-swap detection so a previous-release
     * install of a Java-17-only ECJ on a Java 8 host can be flagged for
     * rollback.
     *
     * <p>Resolution: walks {@link #ECJ_TIERS} highest-floor-first and
     * returns the floor of the first tier whose version is &le; the input.
     * For versions older than every tier (e.g. the original Tomcat 7
     * {@code ecj-3.7.2.jar}), falls back to {@link JavaSdkVersion#JDK_1_8}
     * — the lowest JVM any tier supports — because we can't make stronger
     * claims about pre-tier releases without hardcoding more of the JDT
     * release history than is necessary.
     */
    @NotNull
    static JavaSdkVersion requiredJvmFor(@NotNull String ecjVersion) {
        for (EcjTier tier : ECJ_TIERS) {
            if (compareEcjVersions(ecjVersion, tier.tierStartVersion()) >= 0) {
                return tier.minRuntimeJvm();
            }
        }
        return JavaSdkVersion.JDK_1_8;
    }

    /**
     * Lexicographic-by-numeric-segment compare of two ECJ Maven versions
     * ("3.36.0" vs "3.7.2"). Returns negative / zero / positive in the
     * usual {@link Comparable} contract. Non-numeric segments fall back to
     * string compare so a future release tag like "4.0.0-RC1" still gets
     * an ordering even if it's not pretty.
     */
    private static int compareEcjVersions(@NotNull String a, @NotNull String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.min(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int aNum = parseSegmentOrMinusOne(as[i]);
            int bNum = parseSegmentOrMinusOne(bs[i]);
            if (aNum >= 0 && bNum >= 0) {
                int cmp = Integer.compare(aNum, bNum);
                if (cmp != 0) return cmp;
            } else {
                int cmp = as[i].compareTo(bs[i]);
                if (cmp != 0) return cmp;
            }
        }
        return Integer.compare(as.length, bs.length);
    }

    private static int parseSegmentOrMinusOne(@NotNull String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Looks for an already-installed ECJ JAR in {@code libDir} whose
     * runtime-JVM requirement is higher than {@code runtimeJvm}, paired
     * with a {@code .devtomcat-bak} backup written by a previous swap.
     * Returns the descriptor of that stale state, or {@code null} when no
     * actionable rollback is available (no JVM info, no backup, or current
     * ECJ is compatible with the JVM).
     *
     * <p>Detection strategy:
     * <ul>
     *   <li>Find the lone {@code ecj-*.jar} file in the lib directory
     *       (multiple matches → null; we cannot tell which was the swap
     *       target).</li>
     *   <li>Find the lone {@code *.devtomcat-bak} sibling. Resolve its
     *       restore-target name by stripping {@link #BACKUP_SUFFIX}.</li>
     *   <li>Parse the current JAR's ECJ version from its filename.</li>
     *   <li>Compare {@link #requiredJvmFor} against {@code runtimeJvm};
     *       only flag when the JAR cannot load on the configured JVM.</li>
     * </ul>
     */
    @Nullable
    static StaleSwap detectStaleSwap(@NotNull Path libDir, @Nullable JavaSdkVersion runtimeJvm) {
        if (runtimeJvm == null) return null;
        if (!Files.isDirectory(libDir)) return null;
        try (java.util.stream.Stream<Path> entries = Files.list(libDir)) {
            java.util.List<Path> all = entries.toList();
            Path currentEcj = null;
            int currentEcjCount = 0;
            Path backup = null;
            int backupCount = 0;
            for (Path p : all) {
                String name = p.getFileName().toString();
                if (name.endsWith(BACKUP_SUFFIX)) {
                    backup = p;
                    backupCount++;
                } else if (name.startsWith("ecj-") && name.endsWith(".jar")) {
                    currentEcj = p;
                    currentEcjCount++;
                }
            }
            if (currentEcjCount != 1 || backupCount != 1) {
                // Ambiguous (multiple ecj-*.jar files, or no backup) — bail
                // rather than guess. The user can recover manually.
                return null;
            }
            String currentName = currentEcj.getFileName().toString();
            String currentVersion = currentName.substring("ecj-".length(),
                    currentName.length() - ".jar".length());
            // Primary path: read the actual class-file major from a key
            // ECJ class. This is version-proof — even an ECJ release we've
            // never heard of gets the right verdict because the JVM spec
            // pins class-file major → JVM feature mapping.
            JavaSdkVersion requires = requiredJvmFor(currentEcj);
            if (requires == null) {
                // Fallback: we couldn't read the JAR (corrupted, missing
                // entry, IO error). Use the tier-table mapping by version
                // string so detection still works on a known release.
                requires = requiredJvmFor(currentVersion);
            }
            if (runtimeJvm.isAtLeast(requires)) {
                // Installed ECJ loads fine on this JVM — no rollback needed.
                return null;
            }
            String backupName = backup.getFileName().toString();
            String restoreName = backupName.substring(0, backupName.length() - BACKUP_SUFFIX.length());
            Path restoreTarget = backup.resolveSibling(restoreName);
            return new StaleSwap(currentEcj, backup, restoreTarget,
                    currentVersion, requires);
        } catch (IOException e) {
            LOG.debug("Could not scan Tomcat lib directory for stale ECJ swap: " + e.getMessage());
            return null;
        }
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
        URL downloadUrl = ecjArtifactUrl(targetVersion, "");
        URL sha1Url = ecjArtifactUrl(targetVersion, ".sha1");
        return new SwapPlan(currentEcjJar, targetEcjJar, backupPath,
                downloadUrl, sha1Url, targetVersion);
    }

    @NotNull
    private static URL ecjArtifactUrl(@NotNull String version, @NotNull String suffix) {
        return buildEcjUrl(ecjBaseUrl(), version, suffix);
    }

    /**
     * Resolves the ECJ download base URL: the {@link #REG_ECJ_MIRROR_BASE_URL}
     * registry override when set, else {@link #MAVEN_CENTRAL_BASE}. Registry access
     * is guarded so a headless/early context with no Registry still falls back cleanly.
     */
    @NotNull
    static String ecjBaseUrl() {
        try {
            String override = Registry.stringValue(REG_ECJ_MIRROR_BASE_URL);
            if (override != null && !override.isBlank()) {
                return override;
            }
        } catch (Exception ignored) {
            // No Registry available (or key absent) — use the default below.
        }
        return MAVEN_CENTRAL_BASE;
    }

    /**
     * Builds the ECJ artifact URL from a Maven-layout {@code base}, tolerating a
     * trailing slash. Pure (no Registry) so mirror handling is unit-testable.
     */
    @NotNull
    static URL buildEcjUrl(@NotNull String base, @NotNull String version, @NotNull String suffix) {
        String root = base.strip();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        try {
            return URI.create(root + "/" + version + "/ecj-" + version + ".jar" + suffix).toURL();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to construct ECJ download URL for version " + version + " from base " + root, e);
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
        // Refuse if the backup path doesn't end in our managed suffix.
        // Without this check, stripBackupSuffix returns the input unchanged
        // and the move-to-self below silently no-ops, falsely reporting success.
        String backupName = backupPath.getFileName().toString();
        if (!backupName.endsWith(BACKUP_SUFFIX)) {
            return SwapResult.failed("Backup path does not end in '" + BACKUP_SUFFIX
                    + "': " + backupPath
                    + ". restoreBackup only handles files created by EcjJarSwapper.execute.");
        }
        if (!Files.isRegularFile(activeJar)) {
            return SwapResult.failed("Active ECJ JAR not found at " + activeJar);
        }
        Path originalLocation = backupPath.resolveSibling(stripBackupSuffix(backupName));
        if (originalLocation.equals(backupPath)) {
            // Defensive: stripBackupSuffix produced the same name. Should be
            // impossible after the endsWith check above, but if a future
            // refactor removes that guard we don't want to fall through to
            // a destructive move-to-self.
            return SwapResult.failed("Restore target is the backup itself: " + backupPath);
        }
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

    /**
     * Default {@link Downloader} that talks to the network via IntelliJ's
     * {@link HttpRequests} API. Routed through the platform's HTTP layer so
     * the swap respects user-configured HTTP proxies (Settings &raquo;
     * Appearance &amp; Behavior &raquo; System Settings &raquo; HTTP Proxy)
     * including auth, the platform's TLS trust store, and platform-level
     * redirect handling. Corporate users behind a proxy could not use this
     * action when the implementation went directly through
     * {@code HttpURLConnection}; routing through {@code HttpRequests}
     * fixes that without expanding the surface area of code we own.
     */
    private static final class HttpsDownloader implements Downloader {

        @Override
        public void download(@NotNull URL url, @NotNull Path target,
                             @Nullable ProgressIndicator indicator) throws IOException {
            HttpRequests.request(url.toString())
                    .userAgent("DevTomcat-IDE-Plugin/1.0")
                    .connectTimeout(15_000)
                    .readTimeout(60_000)
                    .saveToFile(target.toFile(), indicator);
        }

        @Override
        @NotNull
        public String fetchString(@NotNull URL url) throws IOException {
            return HttpRequests.request(url.toString())
                    .userAgent("DevTomcat-IDE-Plugin/1.0")
                    .connectTimeout(15_000)
                    .readTimeout(60_000)
                    .readString(null);
        }
    }

    // ------------------------------------------------------------------ //
    // ECJ tier model
    // ------------------------------------------------------------------ //

    /**
     * One row in {@link #ECJ_TIERS}.
     *
     * @param version              the highest ECJ release in this tier — what
     *                             {@link #selectEcj} returns for a JVM that
     *                             matches this tier.
     * @param tierStartVersion     the lowest ECJ release that already requires
     *                             this tier's {@code minRuntimeJvm}.
     *                             {@link #requiredJvmFor} walks tiers
     *                             highest-floor-first and returns
     *                             {@code minRuntimeJvm} for the first tier
     *                             whose start is &le; the queried version.
     * @param minRuntimeJvm        Java version required to load any ECJ JAR
     *                             in this tier ({@code [tierStartVersion .. version]}).
     * @param maxReadableClassFileMajor highest webapp class-file major the
     *                                  tier's {@code version} can read.
     */
    private record EcjTier(@NotNull String version,
                           @NotNull String tierStartVersion,
                           @NotNull JavaSdkVersion minRuntimeJvm,
                           int maxReadableClassFileMajor) {}

    /**
     * Picker output. Carries the chosen ECJ version alongside the JVM and
     * class-file constraints that drove the pick so callers can render an
     * informative message ("the highest ECJ that runs on Java 8 reads class
     * files up to Java 15; your webapp needs Java 17 — upgrade the JRE").
     */
    record EcjPick(@NotNull String version,
                   @NotNull JavaSdkVersion minRuntimeJvm,
                   int maxReadableClassFileMajor) {

        /**
         * True when the selected ECJ can read class files at the given
         * webapp class-file major. False means the JVM-and-ECJ pairing the
         * user has chosen physically cannot compile their webapp; the only
         * fix is upgrading the JVM (or downgrading the webapp's bytecode).
         */
        boolean canCompileClassFileMajor(int classFileMajor) {
            return classFileMajor <= maxReadableClassFileMajor;
        }
    }

    /**
     * Description of a previous swap that no longer matches the runtime JVM.
     * Returned by {@link #detectStaleSwap}; consumed by the
     * "Restore previous ECJ" notification action.
     *
     * @param currentEcjJar the active {@code ecj-X.Y.Z.jar} the swap installed
     * @param backupPath    the {@code .devtomcat-bak} written by the original swap
     * @param restoreTarget the path the backup will move to on restore
     *                      (i.e. the backup name with {@link #BACKUP_SUFFIX} stripped)
     * @param currentVersion the Maven version parsed from {@code currentEcjJar}'s filename
     * @param installedRequiresJvm minimum JVM the {@code currentEcjJar} requires to load
     */
    record StaleSwap(@NotNull Path currentEcjJar,
                     @NotNull Path backupPath,
                     @NotNull Path restoreTarget,
                     @NotNull String currentVersion,
                     @NotNull JavaSdkVersion installedRequiresJvm) {}

    /**
     * Performs the rollback described by a {@link StaleSwap}: removes the
     * active (incompatible) ECJ JAR, restores the backup to its original
     * filename, leaves a {@code .devtomcat-replaced} sidecar so the user
     * can inspect or delete the displaced JAR. Delegates to the existing
     * {@link #restoreBackup} primitive so the atomic-move-and-cleanup
     * contract is shared with the manual undo path.
     */
    @NotNull
    static SwapResult restoreFromBackup(@NotNull StaleSwap stale) {
        return restoreBackup(stale.backupPath(), stale.currentEcjJar());
    }
}
