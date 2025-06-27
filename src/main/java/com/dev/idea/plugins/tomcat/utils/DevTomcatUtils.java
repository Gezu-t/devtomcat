package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * DevTomcat Core Utility Methods
 *
 * Provides low-level filesystem operations, Tomcat installation management,
 * and XML utilities for the DevTomcat plugin.
 *
 * This class focuses on pure filesystem and Tomcat-specific operations.
 * For UI and IntelliJ-specific operations, see {@link PluginUtils}.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class DevTomcatUtils {

    private static final Logger LOG = Logger.getInstance(DevTomcatUtils.class);

    // Directory structure constants
    private static final String CATALINA_BASE_DIR   = ".idea";
    private static final String TOMCAT_INSTANCE_DIR = "tomcat";
    private static final String TEMP_DIR            = "temp";
    private static final String WORK_DIR            = "work";
    private static final String CONF_DIR            = "conf";
    private static final String LOGS_DIR            = "logs";
    private static final String WEBAPPS_DIR         = "webapps";

    // Prevent instantiation
    private DevTomcatUtils() {}

    // =====================================================================
    // CATALINA BASE MANAGEMENT
    // =====================================================================

    /**
     * Computes and creates the per-configuration Catalina base directory.
     * Layout: {@code <project>/.idea/tomcat/<configName>_<projectName>_<hash>}
     *
     * @param configuration The Tomcat run configuration
     * @return Path to the Catalina base directory, or null if creation failed
     */
    @Nullable
    public static Path getCatalinaBase(@NotNull TomcatRunConfiguration configuration) {
        try {
            Project project = configuration.getProject();
            if (project == null) return null;

            String projectBase = project.getBasePath();
            if (projectBase == null) return null;

            String cfgName = sanitizeName(configuration.getName(), "Unnamed");
            String projectName = sanitizeName(project.getName(), "Project");
            String instanceDir = String.format("%s_%s_%d", cfgName, projectName, Math.abs(configuration.hashCode()));

            Path base = Paths.get(projectBase, CATALINA_BASE_DIR, TOMCAT_INSTANCE_DIR, instanceDir);
            createCatalinaStructure(base);

            LOG.info("DevTomcat: Catalina base ready at " + base);
            return base;

        } catch (Exception ex) {
            LOG.error("DevTomcat: Failed to create Catalina base", ex);
            return null;
        }
    }

    /**
     * Creates the complete Tomcat directory structure
     */
    private static void createCatalinaStructure(@NotNull Path base) throws IOException {
        // Create main directories
        Files.createDirectories(base.resolve(TEMP_DIR));
        Files.createDirectories(base.resolve(WORK_DIR));
        Files.createDirectories(base.resolve(CONF_DIR));
        Files.createDirectories(base.resolve(LOGS_DIR));
        Files.createDirectories(base.resolve(WEBAPPS_DIR));

        // Create Catalina-specific subdirectories
        Files.createDirectories(base.resolve(WORK_DIR).resolve("Catalina").resolve("localhost"));
        Files.createDirectories(base.resolve(CONF_DIR).resolve("Catalina").resolve("localhost"));

        LOG.debug("DevTomcat: Directory structure created under " + base);
    }

    /**
     * Get the logs directory for a configuration
     */
    @Nullable
    public static Path getLogsDirectory(@NotNull TomcatRunConfiguration configuration) {
        Path base = getCatalinaBase(configuration);
        return base != null ? base.resolve(LOGS_DIR) : null;
    }

    // =====================================================================
    // TOMCAT INSTALLATION VALIDATION
    // =====================================================================

    /**
     * Validates a Tomcat installation directory
     *
     * @param homePath Path to Tomcat home directory
     * @return true if the directory contains a valid Tomcat installation
     */
    public static boolean isValidTomcatInstallation(@NotNull String homePath) {
        try {
            Path home = Paths.get(homePath);

            // Check required directories
            if (!Files.isDirectory(home.resolve("bin"))) {
                LOG.warn("DevTomcat: Missing bin directory in " + homePath);
                return false;
            }

            if (!Files.isDirectory(home.resolve("lib"))) {
                LOG.warn("DevTomcat: Missing lib directory in " + homePath);
                return false;
            }

            // Check required JAR files
            Path bootstrapJar = home.resolve("lib").resolve("bootstrap.jar");
            Path catalinaJar = home.resolve("lib").resolve("catalina.jar");

            boolean valid = Files.isRegularFile(bootstrapJar) && Files.isRegularFile(catalinaJar);

            if (!valid) {
                LOG.warn("DevTomcat: Missing required JAR files in " + homePath);
            } else {
                LOG.info("DevTomcat: Valid Tomcat installation found at " + homePath);
            }

            return valid;

        } catch (Exception ex) {
            LOG.error("DevTomcat: Error validating Tomcat installation at " + homePath, ex);
            return false;
        }
    }

    /**
     * Attempts to detect Tomcat version from directory name or version file
     *
     * @param homePath Path to Tomcat home directory
     * @return Detected version string, or "9.0.0" as fallback
     */
    @NotNull
    public static String detectTomcatVersion(@NotNull String homePath) {
        try {
            Path home = Paths.get(homePath);

            // First try: Check for version.sh/version.bat output
            // (Would require running the script - not implemented here)

            // Second try: Parse from directory name (e.g., "apache-tomcat-9.0.56")
            String dirName = home.getFileName().toString();
            String versionPattern = "\\d+\\.\\d+(\\.\\d+)?";

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(versionPattern);
            java.util.regex.Matcher matcher = pattern.matcher(dirName);

            if (matcher.find()) {
                String version = matcher.group();
                LOG.info("DevTomcat: Detected Tomcat version " + version + " from directory name");
                return version;
            }

            // Third try: Check RELEASE-NOTES file
            Path releaseNotes = home.resolve("RELEASE-NOTES");
            if (Files.exists(releaseNotes)) {
                try (Stream<String> lines = Files.lines(releaseNotes).limit(10)) {
                    java.util.Optional<String> versionLine = lines
                            .filter(line -> line.contains("Apache Tomcat Version"))
                            .findFirst();

                    if (versionLine.isPresent()) {
                        matcher = pattern.matcher(versionLine.get());
                        if (matcher.find()) {
                            String version = matcher.group();
                            LOG.info("DevTomcat: Detected Tomcat version " + version + " from RELEASE-NOTES");
                            return version;
                        }
                    }
                }
            }

            LOG.warn("DevTomcat: Could not detect Tomcat version, using default 9.0.0");
            return "9.0.0";

        } catch (Exception ex) {
            LOG.error("DevTomcat: Error detecting Tomcat version", ex);
            return "9.0.0";
        }
    }

    // =====================================================================
    // CLEANUP UTILITIES
    // =====================================================================

    /**
     * Cleans temporary files from a Catalina base directory
     *
     * @param catalinaBase Path to the Catalina base directory
     */
    public static void cleanupTempFiles(@NotNull Path catalinaBase) {
        try {
            // Clean temp directory completely
            Path tempDir = catalinaBase.resolve(TEMP_DIR);
            if (Files.exists(tempDir)) {
                deleteDirectoryContents(tempDir);
                LOG.info("DevTomcat: Cleaned temp directory");
            }

            // Clean work directory selectively (preserve session files)
            Path workDir = catalinaBase.resolve(WORK_DIR);
            if (Files.exists(workDir)) {
                cleanWorkDirectory(workDir);
                LOG.info("DevTomcat: Cleaned work directory");
            }

        } catch (Exception ex) {
            LOG.error("DevTomcat: Cleanup failed for " + catalinaBase, ex);
        }
    }

    /**
     * Recursively deletes directory contents
     */
    private static void deleteDirectoryContents(@NotNull Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(path -> !path.equals(dir))
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOG.warn("DevTomcat: Could not delete " + path, e);
                        }
                    });
        }
    }

    /**
     * Selectively cleans work directory, preserving session files
     */
    private static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        if (!Files.exists(workDir)) return;

        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".ser")) // Preserve session files
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOG.warn("DevTomcat: Could not delete " + path, e);
                        }
                    });
        }
    }

    // =====================================================================
    // XML UTILITIES (SECURE)
    // =====================================================================

    /**
     * Creates a secure DocumentBuilder with XXE protection
     *
     * @return Configured DocumentBuilder
     * @throws ParserConfigurationException if configuration fails
     */
    @NotNull
    public static DocumentBuilder createSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        try {
            // Disable DTDs completely
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            // Disable external entities
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            // Disable external DTDs and schemas
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            // Some XML processors don't support these features, continue with basic security
            LOG.warn("DevTomcat: Some XXE protection features not supported", e);
        }

        // Configure for safety
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        factory.setValidating(false);

        return factory.newDocumentBuilder();
    }

    /**
     * Creates a secure Transformer for XML output
     *
     * @return Configured Transformer
     * @throws TransformerConfigurationException if configuration fails
     */
    @NotNull
    public static Transformer createSecureTransformer() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();

        try {
            // Secure the factory
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException e) {
            // Some transformers don't support these attributes
            LOG.warn("DevTomcat: Some transformer security features not supported", e);
        }

        Transformer transformer = factory.newTransformer();

        // Configure output properties
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");

        try {
            // Try to set indent amount (not all transformers support this)
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        } catch (IllegalArgumentException ignored) {
            // Not critical if not supported
        }

        return transformer;
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    /**
     * Checks if a directory is empty
     *
     * @param dir Directory to check
     * @return true if directory doesn't exist or is empty
     */
    public static boolean isEmptyDirectory(@NotNull Path dir) {
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return true;
            }

            try (Stream<Path> entries = Files.list(dir)) {
                return !entries.findFirst().isPresent();
            }

        } catch (IOException e) {
            LOG.warn("DevTomcat: Could not check if directory is empty: " + dir, e);
            return true;
        }
    }

    /**
     * Sanitizes a name for use in file paths
     *
     * @param name Name to sanitize
     * @param defaultValue Default value if name is empty
     * @return Sanitized name safe for filesystem use
     */
    @NotNull
    public static String sanitizeName(@Nullable String name, @NotNull String defaultValue) {
        if (name == null || name.trim().isEmpty()) {
            return defaultValue;
        }

        // Replace problematic characters with underscore
        return name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Get DevTomcat home directory
     *
     * @return Path to ~/.devtomcat directory
     */
    @NotNull
    public static Path getDevTomcatHome() {
        return Paths.get(System.getProperty("user.home"), ".devtomcat");
    }

    /**
     * Initialize DevTomcat home directory
     */
    public static void initializeDevTomcatHome() {
        try {
            Path home = getDevTomcatHome();
            Files.createDirectories(home);
            Files.createDirectories(home.resolve("templates"));
            Files.createDirectories(home.resolve("configs"));
            LOG.info("DevTomcat: Home directory initialized at " + home);
        } catch (IOException e) {
            LOG.error("DevTomcat: Failed to initialize home directory", e);
        }
    }
}