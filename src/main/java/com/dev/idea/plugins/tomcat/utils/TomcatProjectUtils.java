package com.dev.idea.plugins.tomcat.utils;

    import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
    import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
    import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
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
            Objects.requireNonNull(config, "Configuration cannot be null");

            TomcatConfigurationData data = config.getConfigData();
            if (data == null) {
                LOG.debug("No configuration data available");
                return null;
            }

            // First check if there's an explicit CATALINA_BASE set
            String catalinaBase = data.getCatalinaBase();
            if (StringUtil.isNotEmpty(catalinaBase)) {
                Path path = Paths.get(catalinaBase);
                if (Files.isDirectory(path)) {
                    return path;
                }
            }

            // Fall back to project-based path
            Project project = config.getProject();
            if (project == null || project.getBasePath() == null) {
                LOG.debug("No project base path available");
                return null;
            }

            // Create a project-specific CATALINA_BASE inside .idea/devtomcat so the Tomcat
            // work directory (JSP compilations, session files, etc.) sits alongside other
            // IDE-managed project metadata and is hidden from source trees. .idea/ is
            // excluded from VCS by the standard IntelliJ .gitignore.
            String configName = sanitizeFileName(config.getName());
            Path projectCatalinaBase = Paths.get(project.getBasePath(), DEVTOMCAT_DIR_NAME, configName);

            LOG.debug("Using project CATALINA_BASE: " + projectCatalinaBase);
            return projectCatalinaBase;
        }

        /**
         * Location of DevTomcat per-configuration data (CATALINA_BASE, conf overlays).
         * Lives under {@code .idea/} alongside other IntelliJ project metadata.
         */
        static final String DEVTOMCAT_DIR_NAME = ".idea/devtomcat";

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
            Path catalinaBase = getCatalinaBase(config);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_LOGS);
        }

            @Nullable
        public static Path getWorkDirectory(@NotNull TomcatRunConfiguration config) {
            Path catalinaBase = getCatalinaBase(config);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_WORK);
        }

            @Nullable
        public static Path getConfDirectory(@NotNull TomcatRunConfiguration config) {
            Path catalinaBase = getCatalinaBase(config);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_CONF);
        }

            @Nullable
        public static Path getWebappsDirectory(@NotNull TomcatRunConfiguration config) {
            Path catalinaBase = getCatalinaBase(config);
            if (catalinaBase == null) {
                return null;
            }
            return catalinaBase.resolve(DIR_WEBAPPS);
        }

        /**
         * Returns the conf overlay directory for a run configuration.
         *
         * <p>Path: {@code <project>/.idea/devtomcat/<config-name>/conf/}
         * <ul>
         *   <li>Under {@code .idea/} — alongside other IDE project metadata, hidden from source trees</li>
         *   <li>Per-config — different configurations can have different overlays</li>
         * </ul>
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
         * <p>Path: {@code <projectBasePath>/.idea/devtomcat/<sanitized-config-name>/conf/}
         */
        @NotNull
        static Path resolveConfOverlayPath(@NotNull String projectBasePath, @Nullable String configName) {
            return Paths.get(projectBasePath, DEVTOMCAT_DIR_NAME, sanitizeFileName(configName), "conf");
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