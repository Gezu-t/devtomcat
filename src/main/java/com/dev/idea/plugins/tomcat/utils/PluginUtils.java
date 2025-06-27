package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.Location;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleFileIndex;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.setting.TomcatServersConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * UI and IntelliJ Platform Integration Utilities
 *
 * Provides high-level utilities for IntelliJ IDEA integration, UI operations,
 * module management, and project-specific functionality.
 *
 * For low-level filesystem and Tomcat operations, see {@link DevTomcatUtils}.
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class PluginUtils {

    // Port validation constants
    public static final int MIN_PORT_VALUE = 1;
    public static final int MAX_PORT_VALUE = 65535;

    // Common web root paths to check
    private static final List<String> WEB_ROOT_PATHS = Arrays.asList(
            "src/main/webapp",
            "web",
            "WebContent",
            "src/webapp",
            "webapp",
            "src/main/web",
            "public",
            "www"
    );

    // Prevent instantiation
    private PluginUtils() {}

    // =====================================================================
    // NAMING AND GENERATION
    // =====================================================================

    /**
     * Generates a unique sequential name from a list of existing names
     *
     * @param existingNames List of already used names
     * @param baseName Preferred base name
     * @return Unique name like "baseName" or "baseName (2)"
     */
    @NotNull
    public static String generateUniqueSequentialName(@NotNull List<String> existingNames,
                                                      @NotNull String baseName) {
        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        int maxSequence = 0;
        Pattern pattern = Pattern.compile("^" + Pattern.quote(baseName) + " \\((\\d+)\\)$");

        for (String name : existingNames) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                int sequence = Integer.parseInt(matcher.group(1));
                maxSequence = Math.max(maxSequence, sequence);
            }
        }

        return baseName + " (" + (maxSequence + 1) + ")";
    }

    // =====================================================================
    // TOMCAT SERVER SELECTION
    // =====================================================================

    /**
     * Shows a file chooser for selecting a Tomcat installation
     *
     * @param callback Called with the created TomcatInfo if successful
     */
    public static void chooseTomcatServer(@NotNull Consumer<TomcatInfo> callback) {
        chooseTomcatServer(null, callback);
    }

    /**
     * Shows a file chooser for selecting a Tomcat installation with custom naming
     *
     * @param nameGenerator Optional function to generate server name
     * @param callback Called with the created TomcatInfo if successful
     */
    public static void chooseTomcatServer(@Nullable UnaryOperator<String> nameGenerator,
                                          @NotNull Consumer<TomcatInfo> callback) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Tomcat Server")
                .withDescription("Select the directory containing a Tomcat installation")
                .withShowHiddenFiles(false);

        FileChooser.chooseFile(descriptor, null, null, file -> {
            if (file != null) {
                String path = file.getPath();

                // Validate the installation
                if (!DevTomcatUtils.isValidTomcatInstallation(path)) {
                    // Could show error dialog here
                    return;
                }

                // Create TomcatInfo
                TomcatServerManagerState.createTomcatInfo(path, nameGenerator)
                        .ifPresent(callback);
            }
        });
    }

    /**
     * Opens the Tomcat Servers configuration dialog
     */
    public static void openTomcatServersConfiguration() {
        ShowSettingsUtil.getInstance().showSettingsDialog(null, TomcatServersConfigurable.class);
    }

    // =====================================================================
    // PORT VALIDATION
    // =====================================================================

    /**
     * Parses and validates a port number string
     *
     * @param portString String representation of port
     * @return Valid port number
     * @throws ConfigurationException if port is invalid
     */
    public static int parseAndValidatePort(@NotNull String portString) throws ConfigurationException {
        if (StringUtil.isEmpty(portString)) {
            throw new ConfigurationException("Port cannot be empty");
        }

        try {
            int port = Integer.parseInt(portString.trim());

            if (port < MIN_PORT_VALUE || port > MAX_PORT_VALUE) {
                throw new ConfigurationException(
                        String.format("Port must be between %d and %d", MIN_PORT_VALUE, MAX_PORT_VALUE)
                );
            }

            return port;

        } catch (NumberFormatException e) {
            throw new ConfigurationException("Port must be a valid number");
        }
    }

    /**
     * Validates that two ports don't conflict
     *
     * @param port1 First port
     * @param port2 Second port
     * @param port1Name Name of first port for error message
     * @param port2Name Name of second port for error message
     * @throws ConfigurationException if ports conflict
     */
    public static void validatePortsDoNotConflict(int port1, int port2,
                                                  @NotNull String port1Name,
                                                  @NotNull String port2Name)
            throws ConfigurationException {
        if (port1 == port2) {
            throw new ConfigurationException(
                    String.format("%s and %s cannot use the same port (%d)",
                            port1Name, port2Name, port1)
            );
        }
    }

    // =====================================================================
    // MODULE AND PROJECT UTILITIES
    // =====================================================================

    /**
     * Extracts a context path from a module name
     *
     * @param module Module to extract from
     * @return Suggested context path
     */
    @NotNull
    public static String extractContextPath(@NotNull Module module) {
        String moduleName = module.getName();

        // Remove common suffixes
        moduleName = StringUtil.trimEnd(moduleName, ".main");
        moduleName = StringUtil.trimEnd(moduleName, ".web");

        // Get last component after dots
        String[] parts = moduleName.split("\\.");
        String contextName = parts.length > 0 ? parts[parts.length - 1] : moduleName;

        // Ensure it starts with /
        if (!contextName.startsWith("/")) {
            contextName = "/" + contextName;
        }

        return contextName.toLowerCase();
    }

    /**
     * Finds web root directories in a module
     *
     * @param module Module to search
     * @return List of web root virtual files
     */
    @NotNull
    public static List<VirtualFile> findWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();

        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            // Check common web root paths
            for (String webPath : WEB_ROOT_PATHS) {
                VirtualFile webRoot = contentRoot.findFileByRelativePath(webPath);
                if (webRoot != null && webRoot.isDirectory()) {
                    // Verify it has WEB-INF
                    VirtualFile webInf = webRoot.findChild("WEB-INF");
                    if (webInf != null && webInf.isDirectory()) {
                        webRoots.add(webRoot);
                        break; // Found valid web root in this content root
                    }
                }
            }
        }

        return webRoots;
    }

    /**
     * Finds all web roots in a project
     *
     * @param project Project to search
     * @return List of all web root virtual files
     */
    @NotNull
    public static List<VirtualFile> findAllWebRoots(@NotNull Project project) {
        return Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(module -> !module.getName().endsWith(".test"))
                .flatMap(module -> findWebRoots(module).stream())
                .collect(Collectors.toList());
    }

    /**
     * Gets the best web root path for a module
     *
     * @param module Module to check
     * @return Path to web root, or null if not found
     */
    @Nullable
    public static String getWebRootPath(@NotNull Module module) {
        List<VirtualFile> webRoots = findWebRoots(module);
        if (!webRoots.isEmpty()) {
            return webRoots.get(0).getPath();
        }

        // Try to create a default structure
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        if (contentRoots.length > 0) {
            Path webPath = Paths.get(contentRoots[0].getPath(), "src", "main", "webapp");
            try {
                Files.createDirectories(webPath);
                Files.createDirectories(webPath.resolve("WEB-INF"));
                return webPath.toString();
            } catch (Exception e) {
                // Fall back to simpler structure
                webPath = Paths.get(contentRoots[0].getPath(), "web");
                try {
                    Files.createDirectories(webPath);
                    Files.createDirectories(webPath.resolve("WEB-INF"));
                    return webPath.toString();
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    /**
     * Gets all non-test modules in a project
     *
     * @param project Project to check
     * @return List of production modules
     */
    @NotNull
    public static List<Module> getProductionModules(@NotNull Project project) {
        return Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(module -> !module.getName().endsWith(".test"))
                .filter(module -> !module.getName().endsWith(".iml"))
                .collect(Collectors.toList());
    }

    /**
     * Attempts to guess the main module for a project
     *
     * @param project Project to analyze
     * @return Best guess for main module, or null
     */
    @Nullable
    public static Module guessMainModule(@NotNull Project project) {
        List<Module> modules = getProductionModules(project);

        if (modules.isEmpty()) {
            return null;
        }

        // Look for a module named like the project
        String projectName = project.getName();
        for (Module module : modules) {
            if (module.getName().equals(projectName) ||
                    module.getName().equals(projectName + ".main")) {
                return module;
            }
        }

        // Look for common patterns
        for (Module module : modules) {
            String name = module.getName().toLowerCase();
            if (name.contains("web") || name.contains("main") || name.equals("app")) {
                return module;
            }
        }

        // Return first module as last resort
        return modules.get(0);
    }

    /**
     * Checks if a location is under test sources
     *
     * @param location Location to check
     * @return true if location is in test sources
     */
    public static boolean isInTestSources(@Nullable Location<?> location) {
        if (location == null) {
            return false;
        }

        VirtualFile file = location.getVirtualFile();
        if (file == null) {
            return false;
        }

        ProjectFileIndex index = ProjectFileIndex.getInstance(location.getProject());
        return index.isInTestSourceContent(file);
    }

    /**
     * Finds the module containing a file path
     *
     * @param filePath Path to file
     * @param project Project to search in
     * @return Module containing the file, or null
     */
    @Nullable
    public static Module findModuleForPath(@Nullable String filePath, @NotNull Project project) {
        if (StringUtil.isEmpty(filePath)) {
            return null;
        }

        VirtualFile file = VfsUtil.findFile(Paths.get(filePath), true);
        if (file == null) {
            return null;
        }

        return ModuleUtilCore.findModuleForFile(file, project);
    }

    // =====================================================================
    // CONFIGURATION HELPERS (Delegating to DevTomcatUtils)
    // =====================================================================

    /**
     * Gets the Catalina base directory for a configuration
     * Delegates to {@link DevTomcatUtils#getCatalinaBase(TomcatRunConfiguration)}
     *
     * @param configuration Tomcat run configuration
     * @return Path to Catalina base, or null
     */
    @Nullable
    public static Path getCatalinaBase(@NotNull TomcatRunConfiguration configuration) {
        return DevTomcatUtils.getCatalinaBase(configuration);
    }

    /**
     * Gets the Tomcat logs directory for a configuration
     *
     * @param configuration Tomcat run configuration
     * @return Path to logs directory, or null
     */
    @Nullable
    public static Path getTomcatLogsDirectory(@NotNull TomcatRunConfiguration configuration) {
        return DevTomcatUtils.getLogsDirectory(configuration);
    }

    // =====================================================================
    // PROJECT INITIALIZATION
    // =====================================================================

    /**
     * Initializes DevTomcat project structure
     *
     * @param project Project to initialize
     */
    public static void initializeProjectStructure(@NotNull Project project) {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return;

            Path ideaDir = Paths.get(basePath, ".idea");
            Files.createDirectories(ideaDir);

            // Create .gitignore for DevTomcat files
            Path gitignore = ideaDir.resolve(".gitignore");
            if (!Files.exists(gitignore)) {
                List<String> lines = Arrays.asList(
                        "# DevTomcat generated files",
                        "tomcat/",
                        "*.log"
                );
                Files.write(gitignore, lines);
            }

            // Initialize DevTomcat home directory
            DevTomcatUtils.initializeDevTomcatHome();

        } catch (Exception e) {
            // Silently fail - not critical
        }
    }

    // =====================================================================
    // VALIDATION UTILITIES
    // =====================================================================

    /**
     * Validates a file path exists
     *
     * @param path Path to validate
     * @return true if path exists
     */
    public static boolean isValidPath(@Nullable String path) {
        if (StringUtil.isEmpty(path)) {
            return false;
        }

        try {
            return Files.exists(Paths.get(path));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates a directory path exists and is a directory
     *
     * @param path Path to validate
     * @return true if path is a valid directory
     */
    public static boolean isValidDirectory(@Nullable String path) {
        if (StringUtil.isEmpty(path)) {
            return false;
        }

        try {
            Path p = Paths.get(path);
            return Files.exists(p) && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    // =====================================================================
    // ARTIFACT UTILITIES
    // =====================================================================

    /**
     * Suggests a deployment path for an artifact
     *
     * @param artifactName Name of the artifact
     * @return Suggested deployment path
     */
    @NotNull
    public static String suggestDeploymentPath(@NotNull String artifactName) {
        // Remove common suffixes
        String cleanName = artifactName
                .replaceAll(":(war|jar|ear)( exploded)?$", "")
                .replaceAll("\\.(war|jar|ear)$", "")
                .trim();

        // Handle special cases
        if (cleanName.isEmpty() || "root".equalsIgnoreCase(cleanName)) {
            return "/";
        }

        // Convert to lowercase and ensure starts with /
        cleanName = cleanName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        return "/" + cleanName;
    }

    /**
     * Gets the file extension for an artifact type
     *
     * @param artifactType Type of artifact (e.g., "war", "jar")
     * @return File extension with dot (e.g., ".war")
     */
    @NotNull
    public static String getArtifactExtension(@NotNull String artifactType) {
        String type = artifactType.toLowerCase();

        if (type.contains("exploded") || type.contains("directory")) {
            return "";
        }

        if (type.contains("war")) return ".war";
        if (type.contains("jar")) return ".jar";
        if (type.contains("ear")) return ".ear";

        return "";
    }

    // =====================================================================
    // UI UTILITIES
    // =====================================================================

    /**
     * Creates a standardized error message for configuration exceptions
     *
     * @param field Field name that has error
     * @param message Error message
     * @return Formatted error message
     */
    @NotNull
    public static String createConfigurationError(@NotNull String field, @NotNull String message) {
        return String.format("%s: %s", field, message);
    }

    /**
     * Formats a port for display with service name
     *
     * @param serviceName Name of service (e.g., "HTTP", "JMX")
     * @param port Port number
     * @return Formatted string like "HTTP: 8080"
     */
    @NotNull
    public static String formatPort(@NotNull String serviceName, int port) {
        return String.format("%s: %d", serviceName, port);
    }

    // =====================================================================
    // PLUGIN INFORMATION
    // =====================================================================

    /**
     * Gets the DevTomcat plugin version
     *
     * @return Plugin version string
     */
    @NotNull
    public static String getPluginVersion() {
        // In a real implementation, this would read from plugin.xml
        return "1.0.0";
    }

    /**
     * Gets the plugin display name
     *
     * @return Plugin display name
     */
    @NotNull
    public static String getPluginDisplayName() {
        return "DevTomcat - Professional Tomcat Integration";
    }

    /**
     * Checks if running in development mode
     *
     * @return true if running from IDE in development
     */
    public static boolean isDevelopmentMode() {
        // Check for common development indicators
        String ideaPrefix = System.getProperty("idea.plugins.path");
        return ideaPrefix != null && ideaPrefix.contains("sandbox");
    }
}