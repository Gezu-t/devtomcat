package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pre-launch preflight validation that catches common failures before Tomcat starts.
 *
 * <p>Runs three categories of checks:
 * <ol>
 *   <li><b>Required system properties</b> — validates that user-specified {@code -D} properties
 *       referencing file paths point to existing locations</li>
 *   <li><b>Duplicate/conflicting JARs</b> — scans deployed artifacts' {@code WEB-INF/lib}
 *       for JARs with the same base name but different versions</li>
 *   <li><b>Locked paths</b> — scans existing files in catalina.base work/temp directories
 *       and app-specified persistence paths for held file locks</li>
 * </ol>
 *
 * <p>Issues are classified as blocking (prevent launch) or warnings (logged but non-blocking).
 *
 * @see TomcatCommandLineState#ensurePreLaunchSetup()
 */
public final class TomcatPreflightValidator {

    private static final Logger LOG = Logger.getInstance(TomcatPreflightValidator.class);

    /**
     * System properties that reference filesystem paths and must exist for Tomcat to start.
     * Checked only when explicitly set by the user in VM options.
     */
    static final Set<String> PATH_SYSTEM_PROPERTIES = Set.of(
            "catalina.home",
            "catalina.base",
            "java.io.tmpdir",
            "java.util.logging.config.file",
            "javax.net.ssl.keyStore",
            "javax.net.ssl.trustStore"
    );

    /**
     * System properties that point to application-owned persistence/cache directories.
     * If set by the user, these directories are scanned for held file locks during
     * the locked-path check, since a previous Tomcat instance holding locks here is
     * the most common cause of startup failures.
     */
    static final Set<String> PERSISTENCE_PATH_PROPERTIES = Set.of(
            "ehcache.disk.store.dir",
            "hazelcast.persistence.dir",
            "java.io.tmpdir",
            "derby.system.home",
            "h2.baseDir",
            "lucene.index.dir"
    );

    /** Maximum depth when walking directories for locked files. */
    private static final int LOCK_SCAN_MAX_DEPTH = 3;

    /** Maximum number of files to probe per directory to avoid slow scans. */
    private static final int LOCK_SCAN_MAX_FILES = 50;

    /**
     * Pattern to extract base name and version from a JAR filename.
     * Matches: name-1.2.3.jar, name-1.2.3-SNAPSHOT.jar, name-1.2.jar
     */
    static final Pattern JAR_VERSION_PATTERN =
            Pattern.compile("^(.+?)-(\\d+(?:\\.\\d+)*(?:[.-].+)?)\\.jar$");

    private TomcatPreflightValidator() {}

    // =========================================================================
    // Top-level entry point
    // =========================================================================

    /**
     * Runs all preflight checks and returns the combined result.
     *
     * @param configuration the run configuration to validate
     * @return preflight result with any issues found
     */
    @NotNull
    public static PreflightResult validate(@NotNull TomcatRunConfiguration configuration) {
        List<PreflightIssue> issues = new ArrayList<>();

        String vmOptions = configuration.getConfigData().getVmConfig().getVmOptions();
        Map<String, String> parsedProperties = parseSystemProperties(vmOptions);

        checkRequiredSystemProperties(parsedProperties, issues);
        checkDuplicateDeployments(configuration.getConfigData().getDeploymentConfig(), issues);
        checkDuplicateJars(configuration.getConfigData().getDeploymentConfig(), issues);
        checkLockedPaths(configuration, parsedProperties, issues);

        return new PreflightResult(issues);
    }

    // =========================================================================
    // System property parsing (handles quoting and spaces)
    // =========================================================================

    /**
     * Parses {@code -Dkey=value} entries from a VM options string.
     *
     * <p>Uses IntelliJ's {@link ParametersListUtil} for tokenization, which handles
     * double-quoted values with spaces (e.g., {@code -Dpath="C:\Program Files\app"}).
     * Single-quoted values are normalized to double quotes first, since
     * {@code ParametersListUtil} only handles double-quoting.
     *
     * @param vmOptions the raw VM options string from user configuration
     * @return map of property key → value for all {@code -D} entries found
     */
    @NotNull
    static Map<String, String> parseSystemProperties(@NotNull String vmOptions) {
        if (vmOptions.isEmpty()) return Map.of();

        // ParametersListUtil only handles double quotes, so normalize single quotes first
        String normalized = normalizeSingleQuotes(vmOptions);
        List<String> tokens = ParametersListUtil.parse(normalized);
        Map<String, String> properties = new LinkedHashMap<>();

        for (String token : tokens) {
            if (token.startsWith("-D") && token.contains("=")) {
                int eqIndex = token.indexOf('=');
                String key = token.substring(2, eqIndex);
                String value = token.substring(eqIndex + 1);
                // Strip surrounding quotes if ParametersListUtil preserved them
                value = stripQuotes(value);
                if (!key.isEmpty()) {
                    properties.put(key, value);
                }
            }
        }

        return properties;
    }

    /**
     * Converts single-quoted segments to double-quoted so that
     * {@link ParametersListUtil#parse} handles them correctly.
     * Only converts matched pairs: {@code 'foo bar'} → {@code "foo bar"}.
     */
    static String normalizeSingleQuotes(@NotNull String input) {
        if (!input.contains("'")) return input;

        StringBuilder sb = new StringBuilder(input.length());
        boolean inSingleQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inSingleQuote) {
                // Check if there's a matching closing quote
                int closing = input.indexOf('\'', i + 1);
                if (closing > i) {
                    sb.append('"');
                    inSingleQuote = true;
                } else {
                    sb.append(c); // unmatched, keep as-is
                }
            } else if (c == '\'' && inSingleQuote) {
                sb.append('"');
                inSingleQuote = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strips matched leading/trailing double quotes from a value.
     * Handles edge cases where ParametersListUtil preserves quotes.
     */
    static String stripQuotes(@NotNull String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    // =========================================================================
    // Check 1: Required system properties referencing file paths
    // =========================================================================

    /**
     * Validates that user-specified system properties referencing filesystem paths
     * point to existing locations.
     *
     * @param properties parsed -D properties from VM options
     * @param issues     list to append any issues to
     */
    static void checkRequiredSystemProperties(@NotNull Map<String, String> properties,
                                              @NotNull List<PreflightIssue> issues) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (PATH_SYSTEM_PROPERTIES.contains(key) && !value.isEmpty()) {
                Path path = Paths.get(value);
                if (!Files.exists(path)) {
                    issues.add(new PreflightIssue(
                            PreflightIssue.Severity.ERROR,
                            String.format("VM option -D%s=%s references a path that does not exist. " +
                                    "Tomcat will fail to start with this setting.", key, value)));
                }
            }
        }
    }

    // =========================================================================
    // Check 2: Duplicate context paths and deployment paths
    // =========================================================================

    /**
     * Warns when two or more deployment artifacts share the same context path or
     * the same physical deployment path.
     *
     * <ul>
     *   <li><b>Duplicate context path</b> — Tomcat will deploy only one of them;
     *       the second silently shadows or replaces the first, causing confusing 404s.</li>
     *   <li><b>Duplicate deployment path</b> — both entries point at the same WAR/directory
     *       on disk, which means the same app is deployed twice under different context names,
     *       doubling startup time and memory with no benefit.</li>
     * </ul>
     */
    static void checkDuplicateDeployments(@NotNull DeploymentConfig deploymentConfig,
                                          @NotNull List<PreflightIssue> issues) {
        List<DeploymentArtifact> artifacts = deploymentConfig.getDeployedArtifacts();
        if (artifacts.size() < 2) return;

        // context path → first artifact name that claimed it
        Map<String, String> contextPaths = new LinkedHashMap<>();
        // normalised deployment path → first artifact name that used it
        Map<String, String> deployPaths = new LinkedHashMap<>();

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null || !artifact.isValid()) continue;

            String ctx = artifact.getContextPath();
            if (ctx != null && !ctx.isEmpty()) {
                String normalCtx = ctx.toLowerCase(Locale.ROOT);
                if (contextPaths.containsKey(normalCtx)) {
                    issues.add(new PreflightIssue(
                            PreflightIssue.Severity.WARNING,
                            String.format(
                                    "Duplicate context path '%s': used by both '%s' and '%s'. " +
                                    "Tomcat will only deploy one of them.",
                                    ctx, contextPaths.get(normalCtx), artifact.getName())));
                } else {
                    contextPaths.put(normalCtx, artifact.getName());
                }
            }

            String path = artifact.getPath();
            if (path != null && !path.isEmpty()) {
                String normalPath = Paths.get(path).toAbsolutePath()
                        .normalize().toString().toLowerCase(Locale.ROOT);
                if (deployPaths.containsKey(normalPath)) {
                    issues.add(new PreflightIssue(
                            PreflightIssue.Severity.WARNING,
                            String.format(
                                    "Duplicate deployment path: '%s' and '%s' both point to '%s'. " +
                                    "The same application will be deployed twice.",
                                    deployPaths.get(normalPath), artifact.getName(), path)));
                } else {
                    deployPaths.put(normalPath, artifact.getName());
                }
            }
        }
    }

    // =========================================================================
    // Check 3: Duplicate/conflicting JARs in WEB-INF/lib
    // =========================================================================

    /**
     * Scans WEB-INF/lib of deployed artifacts for JARs that share the same base name
     * but have different versions (e.g., guava-30.1.jar and guava-31.0.jar).
     *
     * @param deploymentConfig the deployment configuration containing artifacts
     * @param issues           list to append any issues to
     */
    static void checkDuplicateJars(@NotNull DeploymentConfig deploymentConfig,
                                   @NotNull List<PreflightIssue> issues) {
        List<DeploymentArtifact> artifacts = deploymentConfig.getDeployedArtifacts();

        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            String artifactPath = artifact.getPath();
            if (artifactPath.isEmpty()) continue;

            Path webInfLib = Paths.get(artifactPath)
                    .resolve(TomcatConstants.WEB_INF)
                    .resolve(TomcatConstants.WEB_INF_LIB);

            if (!Files.isDirectory(webInfLib)) continue;

            checkDuplicateJarsInDirectory(webInfLib, artifact.getDisplayName(), issues);
        }
    }

    /**
     * Groups JARs in a directory by base name and reports groups with multiple versions.
     *
     * @param libDir       the WEB-INF/lib directory to scan
     * @param artifactName display name of the containing artifact (for error messages)
     * @param issues       list to append any issues to
     */
    static void checkDuplicateJarsInDirectory(@NotNull Path libDir,
                                              @NotNull String artifactName,
                                              @NotNull List<PreflightIssue> issues) {
        Map<String, List<String>> jarsByBaseName = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(libDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                  .forEach(p -> {
                      String jarName = p.getFileName().toString();
                      String baseName = extractJarBaseName(jarName);
                      jarsByBaseName.computeIfAbsent(baseName, k -> new ArrayList<>())
                                    .add(jarName);
                  });
        } catch (IOException e) {
            LOG.debug("Could not scan for duplicate jars in " + libDir + ": " + e.getMessage());
            return;
        }

        for (Map.Entry<String, List<String>> entry : jarsByBaseName.entrySet()) {
            List<String> jars = entry.getValue();
            if (jars.size() > 1) {
                issues.add(new PreflightIssue(
                        PreflightIssue.Severity.WARNING,
                        String.format("Artifact '%s' has conflicting JARs in WEB-INF/lib: %s. " +
                                "Multiple versions of the same library cause unpredictable classloading.",
                                artifactName, String.join(", ", jars))));
            }
        }
    }

    // =========================================================================
    // Check 3: Locked paths (Tomcat-owned + app persistence dirs)
    // =========================================================================

    /**
     * Checks for held file locks in two categories of directories:
     * <ol>
     *   <li>Tomcat-owned: catalina.base/{work, temp, logs}</li>
     *   <li>App persistence: directories referenced by known persistence-related
     *       {@code -D} properties (Ehcache, Derby, H2, etc.)</li>
     * </ol>
     *
     * <p>For each directory that exists, walks up to {@link #LOCK_SCAN_MAX_DEPTH} levels
     * deep and probes up to {@link #LOCK_SCAN_MAX_FILES} regular files for exclusive locks.
     * A file that cannot be locked is evidence that another process (typically a previous
     * Tomcat instance) is still holding it.
     */
    static void checkLockedPaths(@NotNull TomcatRunConfiguration configuration,
                                 @NotNull Map<String, String> parsedProperties,
                                 @NotNull List<PreflightIssue> issues) {
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration);
        if (catalinaBase != null) {
            scanDirectoryForLocks(catalinaBase.resolve(TomcatConstants.DIR_WORK), "work", issues);
            scanDirectoryForLocks(catalinaBase.resolve(TomcatConstants.DIR_TEMP), "temp", issues);
            scanDirectoryForLocks(catalinaBase.resolve(TomcatConstants.DIR_LOGS), "logs", issues);
        }

        // Also scan application-owned persistence paths from VM properties
        for (Map.Entry<String, String> entry : parsedProperties.entrySet()) {
            if (PERSISTENCE_PATH_PROPERTIES.contains(entry.getKey())) {
                String value = entry.getValue();
                if (!value.isEmpty()) {
                    Path persistenceDir = Paths.get(value);
                    if (Files.isDirectory(persistenceDir)) {
                        scanDirectoryForLocks(persistenceDir,
                                entry.getKey() + " (" + value + ")", issues);
                    }
                }
            }
        }
    }

    /**
     * Scans an existing directory for files with held locks.
     *
     * <p>Walks up to {@link #LOCK_SCAN_MAX_DEPTH} levels deep and probes up to
     * {@link #LOCK_SCAN_MAX_FILES} regular files. For each file, attempts to acquire
     * an exclusive lock via {@link FileChannel#tryLock()}. If the lock cannot be acquired,
     * the file is held by another process.
     *
     * @param dir     the directory to scan
     * @param dirName human-readable label for error messages
     * @param issues  list to append any issues to
     */
    static void scanDirectoryForLocks(@NotNull Path dir, @NotNull String dirName,
                                      @NotNull List<PreflightIssue> issues) {
        if (!Files.exists(dir)) return;

        if (!Files.isDirectory(dir)) {
            issues.add(new PreflightIssue(
                    PreflightIssue.Severity.ERROR,
                    String.format("catalina.base/%s exists but is not a directory: %s. " +
                            "Remove or rename this file before launching.", dirName, dir)));
            return;
        }

        // Check basic writability first
        if (!Files.isWritable(dir)) {
            issues.add(new PreflightIssue(
                    PreflightIssue.Severity.ERROR,
                    String.format("catalina.base/%s is not writable: %s. " +
                            "Check directory permissions.", dirName, dir)));
            return;
        }

        // Walk existing files and try to lock them
        List<String> lockedFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, LOCK_SCAN_MAX_DEPTH)) {
            Iterator<Path> it = walk
                    .filter(Files::isRegularFile)
                    .iterator();

            int probed = 0;
            while (it.hasNext() && probed < LOCK_SCAN_MAX_FILES) {
                Path file = it.next();
                probed++;
                if (isFileLocked(file)) {
                    lockedFiles.add(dir.relativize(file).toString());
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not walk " + dir + " for lock check: " + e.getMessage());
        }

        if (!lockedFiles.isEmpty()) {
            String fileList = lockedFiles.size() <= 5
                    ? String.join(", ", lockedFiles)
                    : String.join(", ", lockedFiles.subList(0, 5)) +
                      " (and " + (lockedFiles.size() - 5) + " more)";
            issues.add(new PreflightIssue(
                    PreflightIssue.Severity.WARNING,
                    String.format("%s has %d locked file(s): %s. " +
                            "A previous Tomcat instance may still be running.",
                            dirName, lockedFiles.size(), fileList)));
        }
    }

    /**
     * Tests whether a file is locked by another process by attempting to acquire
     * an exclusive lock. Returns {@code true} if the lock cannot be obtained.
     *
     * <p>The lock is released immediately if obtained; this is a non-destructive probe.
     */
    static boolean isFileLocked(@NotNull Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            // Try a shared lock (read lock) — if even that fails, the file is
            // exclusively locked by another process
            FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // Same JVM already holds a lock on this file region — the file is locked
            return true;
        } catch (IOException e) {
            // Can't open the file at all — treat as potentially locked
            LOG.debug("Could not probe lock on " + file + ": " + e.getMessage());
            return true;
        }
    }

    // =========================================================================
    // JAR name utilities
    // =========================================================================

    /**
     * Extracts the base name from a JAR filename by stripping the version suffix.
     * For example: "guava-31.0.1-jre.jar" → "guava", "slf4j-api-2.0.9.jar" → "slf4j-api".
     * If no version pattern is found, returns the full name (minus .jar).
     */
    static String extractJarBaseName(@NotNull String jarName) {
        Matcher matcher = JAR_VERSION_PATTERN.matcher(jarName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName;
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * A single preflight issue with severity and human-readable message.
     */
    public static final class PreflightIssue {
        public enum Severity { ERROR, WARNING }

        private final Severity severity;
        private final String message;

        public PreflightIssue(@NotNull Severity severity, @NotNull String message) {
            this.severity = severity;
            this.message = message;
        }

        @NotNull public Severity getSeverity() { return severity; }
        @NotNull public String getMessage() { return message; }
        public boolean isBlocking() { return severity == Severity.ERROR; }

        @Override
        public String toString() {
            return "[" + severity + "] " + message;
        }
    }

    /**
     * Aggregated result of all preflight checks.
     */
    public static final class PreflightResult {
        private final List<PreflightIssue> issues;

        PreflightResult(@NotNull List<PreflightIssue> issues) {
            this.issues = List.copyOf(issues);
        }

        @NotNull public List<PreflightIssue> getIssues() { return issues; }

        public boolean hasIssues() { return !issues.isEmpty(); }

        public boolean hasBlockingIssues() {
            return issues.stream().anyMatch(PreflightIssue::isBlocking);
        }

        @NotNull
        public List<PreflightIssue> getBlockingIssues() {
            return issues.stream().filter(PreflightIssue::isBlocking).toList();
        }

        @NotNull
        public List<PreflightIssue> getWarnings() {
            return issues.stream().filter(i -> !i.isBlocking()).toList();
        }

        @NotNull
        public String getBlockingMessage() {
            return issues.stream()
                    .filter(PreflightIssue::isBlocking)
                    .map(PreflightIssue::getMessage)
                    .findFirst()
                    .orElse("Unknown preflight failure");
        }
    }
}
