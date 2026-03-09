package com.dev.idea.plugins.tomcat.utils;

    import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
    import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
    import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.project.Project;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;
    import org.jetbrains.annotations.Nullable;

    import java.nio.file.Files;

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

            // Create a project-specific CATALINA_BASE
            String configName = sanitizeFileName(config.getName());
            Path projectCatalinaBase = Paths.get(project.getBasePath(), ".idea", "tomcat", configName);

            LOG.debug("Using project CATALINA_BASE: " + projectCatalinaBase);
            return projectCatalinaBase;
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
         * <p>Path: {@code <project>/.devtomcat/<config-name>/conf/}
         * <ul>
         *   <li>Outside {@code .idea/} — survives IDE cache cleans</li>
         *   <li>Per-config — different configurations can have different overlays</li>
         *   <li>Version-controllable if the team wants shared Tomcat customizations</li>
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
         * <p>Path: {@code <projectBasePath>/.devtomcat/<sanitized-config-name>/conf/}
         */
        @NotNull
        static Path resolveConfOverlayPath(@NotNull String projectBasePath, @Nullable String configName) {
            return Paths.get(projectBasePath, ".devtomcat", sanitizeFileName(configName), "conf");
        }

        @NotNull
        static String sanitizeFileName(@Nullable String name) {
            if (StringUtil.isEmpty(name)) return "unnamed";
            String sanitized = name.trim()
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
            return sanitized.isEmpty() ? "unnamed" : sanitized;
        }
    }