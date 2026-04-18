package com.dev.idea.plugins.tomcat.utils;

    import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
    import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
    import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
    import com.intellij.openapi.application.PathManager;
    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.project.Project;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;
    import org.jetbrains.annotations.Nullable;

    import java.io.IOException;
    import java.nio.file.AtomicMoveNotSupportedException;
    import java.nio.file.Files;
    import java.nio.file.StandardCopyOption;

    import static com.dev.idea.plugins.tomcat.TomcatConstants.*;
    import java.nio.file.Path;
    import java.nio.file.Paths;
    import java.util.Objects;

    /**
     * Project-related Tomcat utilities.
     * Provides methods for resolving Tomcat paths relative to projects.
     */
    public final class TomcatProjectUtils {

        private static final Logger LOG = Logger.getInstance(TomcatProjectUtils.class);

        private TomcatProjectUtils() {
            // Utility class
        }

        /**
         * Get the CATALINA_BASE directory for a run configuration.
         * This is typically a project-specific directory where Tomcat writes its runtime files.
         *
         * @param config the run configuration
         * @return the CATALINA_BASE path, or null if not configured
         */
        @Nullable
        public static Path getCatalinaBase(@NotNull TomcatRunConfiguration config) {
            return getCatalinaBase(config, null);
        }

        /**
         * Subdirectory under the per-config CATALINA_BASE where isolated parallel-run
         * instances live. Keeps per-run state under a single predictable branch so
         * stale cleanup is easy and never reaches into the shared base directly.
         *
         * <p>Public so cross-package callers (e.g. process-exit cleanup in
         * {@code TomcatProcessHandler}) can verify that a path they're about to
         * delete really is under this isolation subtree.
         */
        public static final String PARALLEL_RUNS_SUBDIR = ".runs";

        /**
         * Returns the CATALINA_BASE directory for a run configuration, optionally
         * isolated by {@code runId} when "Allow parallel run" is active.
         *
         * <p>When {@code runId} is non-null and the user has not pinned an explicit
         * {@code CATALINA_BASE}, the resolved path is
         * {@code {systemBase}/{configName}/.runs/{runId}/} — isolated per launch so
         * two instances of the same configuration don't clobber each other's
         * {@code work/}, {@code logs/}, or context descriptors.
         *
         * <p>An explicit {@code CATALINA_BASE} pinned in the configuration always
         * wins over {@code runId}: the user took ownership of that directory and
         * the plugin must not silently redirect them.
         *
         * @param config the run configuration
         * @param runId  optional per-launch identifier; {@code null} or empty
         *               resolves the shared per-config base (default, pre-existing
         *               behaviour).
         * @return the resolved CATALINA_BASE path, or {@code null} if none can be derived
         */
        @Nullable
        public static Path getCatalinaBase(@NotNull TomcatRunConfiguration config,
                                           @Nullable String runId) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            TomcatConfigurationData data = config.getConfigData();
            if (data == null) {
                LOG.debug("No configuration data available");
                return null;
            }

            // First check if there's an explicit CATALINA_BASE set.
            // An explicit pin always wins — runId isolation is skipped deliberately.
            String catalinaBase = data.getCatalinaBase();
            if (StringUtil.isNotEmpty(catalinaBase)) {
                Path path = Paths.get(catalinaBase);
                if (Files.isDirectory(path)) {
                    return path;
                }
            }

            // Fall back to the IDE system directory — the standard location for
            // plugin runtime data (compiled JSPs, session files, temp caches, logs).
            // Path: {PathManager.getSystemPath()}/devtomcat/{projectLocationHash}/{configName}[/.runs/{runId}]/
            //
            // This mirrors how IntelliJ's built-in Tomcat integration stores its
            // CATALINA_BASE. The data is completely outside the project tree: no
            // indexing, no VCS concerns, no visible clutter.
            Project project = config.getProject();
            if (project == null || project.getBasePath() == null) {
                LOG.debug("No project base path available");
                return null;
            }

            String projectHash = projectLocationHash(project);
            String configName = sanitizeFileName(config.getName());
            Path base = Paths.get(
                    PathManager.getSystemPath(), SYSTEM_DIR_NAME, projectHash, configName);
            if (StringUtil.isNotEmpty(runId)) {
                base = base.resolve(PARALLEL_RUNS_SUBDIR).resolve(sanitizeFileName(runId));
            }

            LOG.debug("Using system CATALINA_BASE: " + base);
            return base;
        }

        /** Root directory name under {@link PathManager#getSystemPath()} for all DevTomcat runtime data. */
        static final String SYSTEM_DIR_NAME = "devtomcat";

        /** Directory name under the project root for user-editable conf overlays. */
        static final String CONF_OVERLAY_DIR_NAME = ".devtomcat";

        /**
         * Returns a short, stable hash derived from the project's base path.
         * Used to isolate CATALINA_BASE directories per project inside the
         * shared IDE system directory. Stable across IDE restarts.
         */
        @NotNull
        static String projectLocationHash(@NotNull Project project) {
            String basePath = project.getBasePath();
            if (basePath == null) return "default";
            // Use the same approach as IntelliJ's internal hashing: simple
            // hash-code-based hex string for brevity + project name for readability.
            int hash = basePath.hashCode();
            String hexHash = Integer.toHexString(hash & 0x7FFFFFFF);
            String projectName = Paths.get(basePath).getFileName().toString();
            return sanitizeFileName(projectName) + "_" + hexHash;
        }

            @Nullable
        public static Path getTomcatHome(@NotNull TomcatRunConfiguration config) {
            Objects.requireNonNull(config, "Configuration cannot be null");

            TomcatConfigurationData data = config.getConfigData();
            if (data == null) {
                return null;
            }

            TomcatInfo tomcatInfo = data.getTomcatInfo();
            if (tomcatInfo == null || StringUtil.isEmpty(tomcatInfo.getPath())) {
                return null;
            }

            return Paths.get(tomcatInfo.getPath());
        }

            @Nullable
        public static Path getLogsDirectory(@NotNull TomcatRunConfiguration config) {
            return getLogsDirectory(config, null);
        }

            @Nullable
        public static Path getLogsDirectory(@NotNull TomcatRunConfiguration config,
                                             @Nullable String runId) {
            Path catalinaBase = getCatalinaBase(config, runId);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_LOGS);
        }

            @Nullable
        public static Path getWorkDirectory(@NotNull TomcatRunConfiguration config) {
            return getWorkDirectory(config, null);
        }

            @Nullable
        public static Path getWorkDirectory(@NotNull TomcatRunConfiguration config,
                                             @Nullable String runId) {
            Path catalinaBase = getCatalinaBase(config, runId);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_WORK);
        }

            @Nullable
        public static Path getConfDirectory(@NotNull TomcatRunConfiguration config) {
            return getConfDirectory(config, null);
        }

            @Nullable
        public static Path getConfDirectory(@NotNull TomcatRunConfiguration config,
                                             @Nullable String runId) {
            Path catalinaBase = getCatalinaBase(config, runId);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_CONF);
        }

            @Nullable
        public static Path getWebappsDirectory(@NotNull TomcatRunConfiguration config) {
            return getWebappsDirectory(config, null);
        }

            /**
             * Returns the webapps directory for this configuration, isolated per
             * {@code runId} when the process is running in parallel-run mode. Callers
             * with access to a {@link com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler}
             * should pass the handler's {@code runId} so hot-update operations target
             * the right instance's webapps tree instead of the shared one.
             */
            @Nullable
        public static Path getWebappsDirectory(@NotNull TomcatRunConfiguration config,
                                                @Nullable String runId) {
            Path catalinaBase = getCatalinaBase(config, runId);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_WEBAPPS);
        }

        /**
         * Returns the conf overlay directory for a run configuration.
         *
         * <p>Path: {@code <project>/.devtomcat/<config-name>/conf/}
         *
         * <p>Conf overlays are <b>user-editable</b> configuration files (e.g. custom
         * {@code context.xml}, {@code catalina.properties}) that the user may want to
         * version-control or share with the team. They live in the project tree — not
         * in the IDE system directory — so they travel with the project.
         *
         * @return the overlay path, or null if project base path is unavailable
         */
        @Nullable
        public static Path getConfOverlayDirectory(@NotNull TomcatRunConfiguration config) {
            Project project = config.getProject();
            if (project == null || project.getBasePath() == null) {
                return null;
            }
            return resolveConfOverlayPath(project.getBasePath(), config.getName());
        }

        /**
         * Builds the conf overlay path from a project base path and config name.
         *
         * <p>Path: {@code <projectBasePath>/.devtomcat/<sanitized-config-name>/conf/}
         */
        @NotNull
        static Path resolveConfOverlayPath(@NotNull String projectBasePath, @Nullable String configName) {
            return Paths.get(projectBasePath, CONF_OVERLAY_DIR_NAME, sanitizeFileName(configName), "conf");
        }

        /**
         * Copies a file to a target path atomically: writes to a temporary file in the
         * target's directory, then renames. This ensures the target is never in a
         * half-written state if the copy is interrupted (disk full, permission error).
         *
         * <p>Falls back to {@link StandardCopyOption#REPLACE_EXISTING} if the filesystem
         * does not support atomic moves (e.g. cross-device).
         *
         * @param source the source file to copy
         * @param target the destination path (will be replaced atomically)
         * @throws IOException if the copy or rename fails
         */
        public static void atomicCopy(@NotNull Path source, @NotNull Path target) throws IOException {
            Path tempFile = Files.createTempFile(target.getParent(), ".devtomcat-", ".tmp");
            try {
                Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Filesystem doesn't support atomic move — fall back to non-atomic
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // Clean up temp file on failure — don't leave orphans
                safeDelete(tempFile, LOG);
                throw e;
            }
        }

        /**
         * Deletes a file if it exists, logging a debug message on failure.
         * Never throws — intended for cleanup paths where a deletion failure
         * should not abort the surrounding operation.
         */
        public static void safeDelete(@NotNull Path path, @NotNull Logger log) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.debug("Failed to delete " + path + ": " + e.getMessage());
            }
        }

        @NotNull
        static String sanitizeFileName(@Nullable String name) {
            if (StringUtil.isEmpty(name)) return "unnamed";
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return "unnamed";
            String sanitized = trimmed
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
            if (sanitized.isEmpty()) return "unnamed";
            // Append a hash of the original name so that two configs whose names differ
            // only in special characters (e.g. "my-tomcat" vs "my_tomcat") don't resolve
            // to the same sanitized string and share the same CATALINA_BASE dir.
            // Full 32-bit hex avoids collisions that the previous 16-bit truncation was
            // susceptible to across many configurations.
            String hash = String.format("%08x", trimmed.hashCode());
            return sanitized + "_" + hash;
        }
    }