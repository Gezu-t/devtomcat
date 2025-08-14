package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Project and configuration utilities for DevTomcat plugin.
 *
 * This class handles:
 * - Catalina base directory management
 * - Project structure initialization
 * - Work directory cleanup
 * - DevTomcat home directory operations
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class TomcatProjectUtils {

    private static final Logger LOG = Logger.getInstance(TomcatProjectUtils.class);

    // Directory structure constants
    private static final String IDEA_DIR = ".idea";
    private static final String TOMCAT_DIR = "tomcat";
    private static final String CATALINA_DIR = "Catalina";
    private static final String LOCALHOST_DIR = "localhost";

    // Tomcat directories
    private static final String TEMP_DIR = "temp";
    private static final String WORK_DIR = "work";
    private static final String CONF_DIR = "conf";
    private static final String LOGS_DIR = "logs";
    private static final String WEBAPPS_DIR = "webapps";

    // DevTomcat home structure
    private static final String DEVTOMCAT_HOME = ".devtomcat";
    private static final String TEMPLATES_DIR = "templates";
    private static final String CONFIGS_DIR = "configs";
    private static final String SERVERS_DIR = "servers";

    private TomcatProjectUtils() {
        // Utility class
    }

    /**
     * Gets or creates the Catalina base directory for a configuration.
     *
     * @param configuration Tomcat run configuration
     * @return Path to Catalina base, or null if creation failed
     */
    @Nullable
    public static Path getCatalinaBase(@NotNull TomcatRunConfiguration configuration) {
        try {
            Project project = configuration.getProject();
            String projectPath = project.getBasePath();

            if (StringUtil.isEmpty(projectPath)) {
                LOG.error("Project base path is empty");
                return null;
            }

            // Create unique instance name
            String configName = sanitizeName(configuration.getName());
            String projectName = sanitizeName(project.getName());
            String instanceId = String.format("%s_%s_%d", configName, projectName,
                    Math.abs(configuration.hashCode()));

            // Build path: <project>/.idea/tomcat/<instance>
            Path catalinaBase = Paths.get(projectPath, IDEA_DIR, TOMCAT_DIR, instanceId);

            // Create directory structure
            createCatalinaStructure(catalinaBase);

            LOG.info("Catalina base ready at: " + catalinaBase);
            return catalinaBase;

        } catch (Exception e) {
            LOG.error("Failed to create Catalina base", e);
            return null;
        }
    }

    /**
     * Gets the logs directory for a configuration.
     *
     * @param configuration Tomcat run configuration
     * @return Path to logs directory, or null
     */
    @Nullable
    public static Path getLogsDirectory(@NotNull TomcatRunConfiguration configuration) {
        Path catalinaBase = getCatalinaBase(configuration);
        return catalinaBase != null ? catalinaBase.resolve(LOGS_DIR) : null;
    }

    /**
     * Initializes the DevTomcat project structure.
     *
     * @param project Project to initialize
     */
    public static void initializeDevTomcatProject(@NotNull Project project) {
        try {
            String basePath = project.getBasePath();
            if (StringUtil.isEmpty(basePath)) {
                return;
            }

            // Create .idea directory structure
            Path ideaDir = Paths.get(basePath, IDEA_DIR);
            Files.createDirectories(ideaDir);

            // Create .gitignore for DevTomcat files
            createGitIgnore(ideaDir);

            // Initialize global DevTomcat home
            initializeDevTomcatHome();

            LOG.info("DevTomcat project structure initialized");

        } catch (Exception e) {
            LOG.warn("Failed to initialize project structure", e);
        }
    }

    /**
     * Gets the DevTomcat home directory.
     *
     * @return Path to ~/.devtomcat
     */
    @NotNull
    public static Path getDevTomcatHome() {
        return Paths.get(System.getProperty("user.home"), DEVTOMCAT_HOME);
    }

    /**
     * Initializes the DevTomcat home directory structure.
     */
    public static void initializeDevTomcatHome() {
        try {
            Path home = getDevTomcatHome();

            // Create directory structure
            Files.createDirectories(home.resolve(TEMPLATES_DIR));
            Files.createDirectories(home.resolve(CONFIGS_DIR));
            Files.createDirectories(home.resolve(SERVERS_DIR));

            // Create default templates if they don't exist
            createDefaultTemplates(home.resolve(TEMPLATES_DIR));

            LOG.info("DevTomcat home initialized at: " + home);

        } catch (IOException e) {
            LOG.error("Failed to initialize DevTomcat home", e);
        }
    }

    /**
     * Cleans up the work directory for a configuration.
     *
     * @param configuration Tomcat run configuration
     */
    public static void cleanupWorkDirectory(@NotNull TomcatRunConfiguration configuration) {
        Path catalinaBase = getCatalinaBase(configuration);
        if (catalinaBase == null) {
            return;
        }

        try {
            // Clean temp directory completely
            Path tempDir = catalinaBase.resolve(TEMP_DIR);
            if (Files.exists(tempDir)) {
                deleteDirectory(tempDir, false);
                LOG.info("Cleaned temp directory");
            }

            // Clean work directory selectively
            Path workDir = catalinaBase.resolve(WORK_DIR);
            if (Files.exists(workDir)) {
                cleanWorkDirectory(workDir);
                LOG.info("Cleaned work directory");
            }

        } catch (Exception e) {
            LOG.warn("Failed to cleanup directories", e);
        }
    }

    /**
     * Removes all Tomcat instances for a project.
     *
     * @param project Project to clean
     */
    public static void removeAllInstances(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (StringUtil.isEmpty(basePath)) {
            return;
        }

        Path tomcatDir = Paths.get(basePath, IDEA_DIR, TOMCAT_DIR);
        if (Files.exists(tomcatDir)) {
            try {
                deleteDirectory(tomcatDir, true);
                LOG.info("Removed all Tomcat instances for project");
            } catch (IOException e) {
                LOG.error("Failed to remove Tomcat instances", e);
            }
        }
    }

    /**
     * Gets the path to store server templates.
     *
     * @return Path to templates directory
     */
    @NotNull
    public static Path getTemplatesDirectory() {
        return getDevTomcatHome().resolve(TEMPLATES_DIR);
    }

    /**
     * Sanitizes a name for use in file paths.
     *
     * @param name Name to sanitize
     * @return Sanitized name safe for filesystem
     */
    @NotNull
    public static String sanitizeName(@Nullable String name) {
        if (StringUtil.isEmpty(name)) {
            return "unnamed";
        }

        return name.trim()
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Validates a Tomcat installation directory.
     * Delegates to TomcatServerUtils for consistency.
     *
     * @param homePath Path to Tomcat home directory
     * @return true if the directory contains a valid Tomcat installation
     */
    public static boolean isValidTomcatInstallation(@NotNull String homePath) {
        try {
            TomcatServerUtils.validateTomcatHome(homePath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== Private Helper Methods =====================

    private static void createCatalinaStructure(@NotNull Path base) throws IOException {
        // Create main directories
        Files.createDirectories(base.resolve(TEMP_DIR));
        Files.createDirectories(base.resolve(WORK_DIR));
        Files.createDirectories(base.resolve(CONF_DIR));
        Files.createDirectories(base.resolve(LOGS_DIR));
        Files.createDirectories(base.resolve(WEBAPPS_DIR));

        // Create Catalina-specific subdirectories
        Path catalinaWork = base.resolve(WORK_DIR).resolve(CATALINA_DIR).resolve(LOCALHOST_DIR);
        Files.createDirectories(catalinaWork);

        Path catalinaConf = base.resolve(CONF_DIR).resolve(CATALINA_DIR).resolve(LOCALHOST_DIR);
        Files.createDirectories(catalinaConf);

        LOG.debug("Created Catalina directory structure at: " + base);
    }

    private static void createGitIgnore(@NotNull Path ideaDir) throws IOException {
        Path gitignore = ideaDir.resolve(".gitignore");

        if (!Files.exists(gitignore)) {
            List<String> lines = Arrays.asList(
                    "# DevTomcat generated files",
                    "tomcat/",
                    "*.log",
                    "*.tmp",
                    "*.pid"
            );
            Files.write(gitignore, lines, StandardOpenOption.CREATE);
            LOG.debug("Created .gitignore for DevTomcat");
        }
    }

    private static void createDefaultTemplates(@NotNull Path templatesDir) throws IOException {
        // Create default server.xml template
        Path serverXml = templatesDir.resolve("server.xml");
        if (!Files.exists(serverXml)) {
            String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Server port=\"${server.port}\" shutdown=\"SHUTDOWN\">\n" +
                    "  <Service name=\"Catalina\">\n" +
                    "    <Connector port=\"${http.port}\" protocol=\"HTTP/1.1\"\n" +
                    "               connectionTimeout=\"20000\"\n" +
                    "               redirectPort=\"${https.port}\" />\n" +
                    "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n" +
                    "      <Host name=\"localhost\" appBase=\"webapps\"\n" +
                    "            unpackWARs=\"true\" autoDeploy=\"true\">\n" +
                    "      </Host>\n" +
                    "    </Engine>\n" +
                    "  </Service>\n" +
                    "</Server>";
            Files.write(serverXml, content.getBytes(StandardCharsets.UTF_8));
        }

        // Create default context.xml template
        Path contextXml = templatesDir.resolve("context.xml");
        if (!Files.exists(contextXml)) {
            String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Context>\n" +
                    "    <WatchedResource>WEB-INF/web.xml</WatchedResource>\n" +
                    "    <WatchedResource>WEB-INF/tomcat-web.xml</WatchedResource>\n" +
                    "    <WatchedResource>${catalina.base}/conf/web.xml</WatchedResource>\n" +
                    "</Context>";
            Files.write(contextXml, content.getBytes(StandardCharsets.UTF_8));
        }
    }



    private static void deleteDirectory(@NotNull Path dir, boolean deleteRoot) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                        if (deleteRoot || !directory.equals(dir)) {
                            Files.deleteIfExists(directory);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        // Selectively clean work directory, preserving session files
        Files.walkFileTree(workDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();

                // Keep session files
                if (!fileName.endsWith(".ser") && !fileName.equals("SESSIONS.ser")) {
                    Files.deleteIfExists(file);
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }
}