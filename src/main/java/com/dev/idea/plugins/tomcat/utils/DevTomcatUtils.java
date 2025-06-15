/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 * DevTomcat utility methods - our own implementation
 */

package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * DevTomcat utility methods matching IntelliJ Ultimate Tomcat functionality
 * Professional implementation based on Ultimate Tomcat capabilities
 */
public class DevTomcatUtils {

    private static final String CATALINA_BASE_DIR = ".idea";
    private static final String TOMCAT_INSTANCE_DIR = "tomcat";
    private static final String TEMP_DIR = "temp";
    private static final String WORK_DIR = "work";
    private static final String CONF_DIR = "conf";
    private static final String LOGS_DIR = "logs";
    private static final String WEBAPPS_DIR = "webapps";

    /**
     * Get Catalina base directory for the given configuration
     * Following Ultimate's pattern: .idea/tomcat/Unnamed_projectname_hash
     */
    @Nullable
    public static Path getCatalinaBase(@NotNull TomcatRunConfiguration configuration) {
        try {
            Project project = configuration.getProject();
            Module module = configuration.getModule();

            if (project == null || module == null) {
                return null;
            }

            String projectBasePath = project.getBasePath();
            if (projectBasePath == null) {
                return null;
            }

            // Ultimate pattern: .idea/tomcat/Unnamed_projectname_hash
            String configName = configuration.getName();
            if (configName == null || configName.trim().isEmpty()) {
                configName = "Unnamed";
            }

            String projectName = project.getName();
            String instanceName = configName + "_" + projectName + "_" + Math.abs(configuration.hashCode());

            Path catalinaBase = Paths.get(projectBasePath, CATALINA_BASE_DIR, TOMCAT_INSTANCE_DIR, instanceName);

            // Create Ultimate-style directory structure
            createUltimateStyleStructure(catalinaBase);

            System.out.println("DevTomcat: Using Ultimate-style Catalina base: " + catalinaBase);
            return catalinaBase;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error getting Ultimate-style Catalina base - " + e.getMessage());
            return null;
        }
    }

    /**
     * Create Ultimate-style Catalina base directory structure
     */
    private static void createUltimateStyleStructure(Path catalinaBase) throws IOException {
        // Create main directories like Ultimate
        Files.createDirectories(catalinaBase);
        Files.createDirectories(catalinaBase.resolve(TEMP_DIR));
        Files.createDirectories(catalinaBase.resolve(WORK_DIR));
        Files.createDirectories(catalinaBase.resolve(CONF_DIR));
        Files.createDirectories(catalinaBase.resolve(LOGS_DIR));
        Files.createDirectories(catalinaBase.resolve(WEBAPPS_DIR));

        // Create Ultimate-style work structure
        Files.createDirectories(catalinaBase.resolve(WORK_DIR + "/Catalina/localhost"));

        // Create Ultimate-style conf structure
        Files.createDirectories(catalinaBase.resolve(CONF_DIR + "/Catalina/localhost"));

        System.out.println("DevTomcat: Created Ultimate-style directory structure");
    }

    /**
     * Check if a folder is empty
     */
    public static boolean isEmptyFolder(@NotNull Path path) {
        try {
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return true;
            }

            try (Stream<Path> entries = Files.list(path)) {
                return !entries.findFirst().isPresent();
            }
        } catch (IOException e) {
            System.err.println("DevTomcat: Error checking if folder is empty - " + e.getMessage());
            return true;
        }
    }

    /**
     * Create a document builder for XML processing
     */
    @NotNull
    public static DocumentBuilder createDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature("http://xml.org/sax/features/namespaces", false);
        factory.setFeature("http://xml.org/sax/features/validation", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        System.out.println("DevTomcat: Created XML document builder");
        return builder;
    }

    /**
     * Create a transformer for XML processing
     */
    @NotNull
    public static Transformer createTransformer() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();

        // Set output properties for pretty printing
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        System.out.println("DevTomcat: Created XML transformer");
        return transformer;
    }

    /**
     * Get module content root path
     */
    @Nullable
    public static String getModuleContentRoot(@NotNull Module module) {
        try {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            VirtualFile[] contentRoots = rootManager.getContentRoots();

            if (contentRoots.length > 0) {
                String path = contentRoots[0].getPath();
                System.out.println("DevTomcat: Module content root: " + path);
                return path;
            }

            return null;
        } catch (Exception e) {
            System.err.println("DevTomcat: Error getting module content root - " + e.getMessage());
            return null;
        }
    }

    /**
     * Get web application root directory using Ultimate's detection logic
     */
    @Nullable
    public static String getWebAppRoot(@NotNull Module module) {
        try {
            String contentRoot = getModuleContentRoot(module);
            if (contentRoot == null) {
                return null;
            }

            // Ultimate's web app detection order
            String[] ultimateWebAppPaths = {
                    "src/main/webapp",     // Maven standard
                    "web",                 // IntelliJ default
                    "WebContent",          // Eclipse standard
                    "src/webapp",          // Alternative Maven
                    "webapp",              // Simple structure
                    "src/main/web",        // Spring Boot alternative
                    "public",              // Modern web frameworks
                    "www"                  // Alternative structure
            };

            for (String webAppPath : ultimateWebAppPaths) {
                Path webAppDir = Paths.get(contentRoot, webAppPath);
                if (Files.exists(webAppDir) && Files.isDirectory(webAppDir)) {
                    // Ultimate also checks for WEB-INF to confirm it's a webapp
                    Path webInf = webAppDir.resolve("WEB-INF");
                    if (Files.exists(webInf) && Files.isDirectory(webInf)) {
                        String path = webAppDir.toString();
                        System.out.println("DevTomcat:  web app root found: " + path);
                        return path;
                    }
                }
            }

            // Ultimate fallback: create minimal structure if needed
            Path defaultWebApp = Paths.get(contentRoot, "web");
            if (!Files.exists(defaultWebApp)) {
                Files.createDirectories(defaultWebApp);
                Files.createDirectories(defaultWebApp.resolve("WEB-INF"));
                System.out.println("DevTomcat: Created  default web structure at: " + defaultWebApp);
                return defaultWebApp.toString();
            }

            System.out.println("DevTomcat: Using Ultimate fallback web app root: " + defaultWebApp);
            return defaultWebApp.toString();

        } catch (Exception e) {
            System.err.println("DevTomcat: Error getting web app root - " + e.getMessage());
            return null;
        }
    }

    /**
     * Validate Tomcat installation directory
     */
    public static boolean isValidTomcatInstallation(@NotNull String tomcatHome) {
        try {
            Path tomcatPath = Paths.get(tomcatHome);
            if (!Files.exists(tomcatPath) || !Files.isDirectory(tomcatPath)) {
                return false;
            }

            // Check for essential Tomcat directories and files
            Path binDir = tomcatPath.resolve("bin");
            Path libDir = tomcatPath.resolve("lib");
            Path confDir = tomcatPath.resolve("conf");
            Path bootstrapJar = libDir.resolve("bootstrap.jar");
            Path catalinaJar = libDir.resolve("catalina.jar");

            boolean isValid = Files.exists(binDir) && Files.isDirectory(binDir) &&
                    Files.exists(libDir) && Files.isDirectory(libDir) &&
                    Files.exists(confDir) && Files.isDirectory(confDir) &&
                    Files.exists(bootstrapJar) && Files.isRegularFile(bootstrapJar) &&
                    Files.exists(catalinaJar) && Files.isRegularFile(catalinaJar);

            if (isValid) {
                System.out.println("DevTomcat: Valid Tomcat installation found at: " + tomcatHome);
            } else {
                System.err.println("DevTomcat: Invalid Tomcat installation at: " + tomcatHome);
            }

            return isValid;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error validating Tomcat installation - " + e.getMessage());
            return false;
        }
    }

    /**
     * Detect Tomcat version from installation directory
     */
    @Nullable
    public static String detectTomcatVersion(@NotNull String tomcatHome) {
        try {
            // Try to get version from directory name
            Path tomcatPath = Paths.get(tomcatHome);
            String dirName = tomcatPath.getFileName().toString();

            // Common patterns: apache-tomcat-9.0.82, tomcat-10.1.15, etc.
            if (dirName.contains("tomcat")) {
                String[] parts = dirName.split("-");
                for (String part : parts) {
                    if (part.matches("\\d+\\.\\d+.*")) {
                        System.out.println("DevTomcat: Detected Tomcat version: " + part);
                        return part;
                    }
                }
            }

            // Fallback: try to read from jar manifest or server info
            // For now, return a default version
            String defaultVersion = "9.0.0";
            System.out.println("DevTomcat: Using default Tomcat version: " + defaultVersion);
            return defaultVersion;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error detecting Tomcat version - " + e.getMessage());
            return "9.0.0";
        }
    }

    /**
     * Clean up temporary files and work directories
     */
    public static void cleanupTempFiles(@NotNull Path catalinaBase) {
        try {
            // Clean temp directory
            Path tempDir = catalinaBase.resolve(TEMP_DIR);
            if (Files.exists(tempDir)) {
                deleteDirectoryContents(tempDir);
                System.out.println("DevTomcat: Cleaned temp directory");
            }

            // Clean work directory (except session files)
            Path workDir = catalinaBase.resolve(WORK_DIR);
            if (Files.exists(workDir)) {
                cleanWorkDirectory(workDir);
                System.out.println("DevTomcat: Cleaned work directory");
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error cleaning up temp files - " + e.getMessage());
        }
    }

    /**
     * Delete directory contents
     */
    private static void deleteDirectoryContents(@NotNull Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> !path.equals(directory))
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("DevTomcat: Error deleting: " + path + " - " + e.getMessage());
                        }
                    });
        }
    }

    /**
     * Clean work directory while preserving session files
     */
    private static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        if (!Files.exists(workDir)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".ser")) // Preserve session files
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("DevTomcat: Error deleting work file: " + path + " - " + e.getMessage());
                        }
                    });
        }
    }

    /**
     * Create DevTomcat working directory structure
     */
    public static void initializeDevTomcatStructure(@NotNull Project project) {
        try {
            String projectBasePath = project.getBasePath();
            if (projectBasePath == null) {
                return;
            }

            Path devTomcatDir = Paths.get(projectBasePath, CATALINA_BASE_DIR);
            Files.createDirectories(devTomcatDir);

            // Create .gitignore for DevTomcat directory
            Path gitIgnore = devTomcatDir.resolve(".gitignore");
            if (!Files.exists(gitIgnore)) {
                String gitIgnoreContent = "# DevTomcat generated files\n" +
                        "*/temp/\n" +
                        "*/work/\n" +
                        "*/logs/\n" +
                        "*.log\n";
                Files.write(gitIgnore, gitIgnoreContent.getBytes());
            }

            System.out.println("DevTomcat: Initialized DevTomcat directory structure");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error initializing DevTomcat structure - " + e.getMessage());
        }
    }

    /**
     * Get DevTomcat plugin version
     */
    @NotNull
    public static String getPluginVersion() {
        return "1.0.0-SNAPSHOT"; // Can be read from plugin.xml or build properties
    }

    /**
     * Get DevTomcat home directory (in user's home)
     */
    @NotNull
    public static Path getDevTomcatHome() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".devtomcat");
    }

    /**
     * Validate file path
     */
    public static boolean isValidPath(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        try {
            Path p = Paths.get(path);
            return Files.exists(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get safe file name (remove invalid characters)
     */
    @NotNull
    public static String getSafeFileName(@NotNull String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}