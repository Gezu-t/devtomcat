package com.dev.idea.plugins.tomcat.utils;

import com.intellij.execution.Location;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Module and Project utilities for DevTomcat plugin.
 *
 * This class handles all module-related operations including:
 * - Finding web modules in a project
 * - Locating web roots and resources
 * - Determining test vs production code
 * - Extracting context paths from modules
 *
 * @author Gezahegn Lemma (Gezu)
 */
public final class TomcatModuleUtils {

    private static final Logger LOG = Logger.getInstance(TomcatModuleUtils.class);

    // Common web root directory names
    private static final Set<String> WEB_ROOT_NAMES = Set.of(
            "webapp", "WebContent", "web", "WebRoot", "webroot", "public", "www"
    );

    // Common paths to web directories
    private static final List<String> WEB_ROOT_PATHS = Arrays.asList(
            "src/main/webapp",
            "web",
            "WebContent",
            "src/webapp",
            "webapp",
            "WebRoot",
            "src/main/web"
    );

    private TomcatModuleUtils() {
        // Utility class
    }

    /**
     * Finds the most appropriate web module in the project.
     * This method uses intelligent heuristics to identify the main web module.
     *
     * @param project The current project
     * @return The most likely web module, or null if none found
     */
    @Nullable
    public static Module findWebModule(@NotNull Project project) {
        List<Module> webModules = findAllWebModules(project);

        if (webModules.isEmpty()) {
            // No web modules found, try to find any suitable module
            return findBestModule(project);
        }

        if (webModules.size() == 1) {
            return webModules.get(0);
        }

        // Multiple web modules - use heuristics to find the best one
        return selectBestWebModule(webModules, project);
    }

    /**
     * Finds all modules that appear to be web modules.
     *
     * @param project The current project
     * @return List of web modules (never null)
     */
    @NotNull
    public static List<Module> findAllWebModules(@NotNull Project project) {
        return Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(TomcatModuleUtils::isWebModule)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a module is a web module by looking for web resources.
     *
     * @param module Module to check
     * @return true if the module contains web resources
     */
    public static boolean isWebModule(@NotNull Module module) {
        // Skip test modules
        if (isTestModule(module)) {
            return false;
        }

        // Check for web resources
        List<VirtualFile> webRoots = findWebRoots(module);
        return !webRoots.isEmpty();
    }

    /**
     * Finds all web root directories in a module.
     *
     * @param module Module to search
     * @return List of web root directories (never null)
     */
    @NotNull
    public static List<VirtualFile> findWebRoots(@NotNull Module module) {
        List<VirtualFile> webRoots = new ArrayList<>();
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile contentRoot : contentRoots) {
            // Check common web root paths
            for (String webPath : WEB_ROOT_PATHS) {
                VirtualFile webRoot = contentRoot.findFileByRelativePath(webPath);
                if (isValidWebRoot(webRoot)) {
                    webRoots.add(webRoot);
                }
            }

            // Also check direct children with web root names
            for (VirtualFile child : contentRoot.getChildren()) {
                if (child.isDirectory() && WEB_ROOT_NAMES.contains(child.getName())) {
                    if (isValidWebRoot(child)) {
                        webRoots.add(child);
                    }
                }
            }
        }

        return webRoots;
    }

    /**
     * Gets the primary web root for a module.
     *
     * @param module Module to check
     * @return Path to the web root, or null if not found
     */
    @Nullable
    public static VirtualFile getWebRoot(@NotNull Module module) {
        List<VirtualFile> webRoots = findWebRoots(module);

        if (webRoots.isEmpty()) {
            return null;
        }

        // Prefer src/main/webapp (Maven standard)
        for (VirtualFile root : webRoots) {
            if (root.getPath().endsWith("src/main/webapp")) {
                return root;
            }
        }

        // Return first found
        return webRoots.get(0);
    }

    /**
     * Creates a web root directory for a module if it doesn't exist.
     *
     * @param module Module to create web root for
     * @return Path to the created or existing web root
     */
    @Nullable
    public static Path createWebRoot(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        if (contentRoots.length == 0) {
            return null;
        }

        // Try Maven structure first
        Path webRoot = Paths.get(contentRoots[0].getPath(), "src", "main", "webapp");
        try {
            Files.createDirectories(webRoot);
            Files.createDirectories(webRoot.resolve("WEB-INF"));
            LOG.info("Created web root at: " + webRoot);
            return webRoot;
        } catch (Exception e) {
            LOG.warn("Failed to create Maven web structure", e);
        }

        // Fallback to simple structure
        webRoot = Paths.get(contentRoots[0].getPath(), "web");
        try {
            Files.createDirectories(webRoot);
            Files.createDirectories(webRoot.resolve("WEB-INF"));
            LOG.info("Created web root at: " + webRoot);
            return webRoot;
        } catch (Exception e) {
            LOG.error("Failed to create web root", e);
            return null;
        }
    }

    /**
     * Extracts a suggested context path from a module name.
     *
     * @param module Module to extract context from
     * @return Suggested context path (e.g., "/myapp")
     */
    @NotNull
    public static String extractContextPath(@NotNull Module module) {
        String moduleName = module.getName();

        // Remove common suffixes
        moduleName = StringUtil.trimEnd(moduleName, ".main");
        moduleName = StringUtil.trimEnd(moduleName, ".web");
        moduleName = StringUtil.trimEnd(moduleName, "-web");
        moduleName = StringUtil.trimEnd(moduleName, "_web");

        // Get last component after dots
        int lastDot = moduleName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < moduleName.length() - 1) {
            moduleName = moduleName.substring(lastDot + 1);
        }

        // Clean up the name
        moduleName = moduleName.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Handle special cases
        if (moduleName.isEmpty() || "root".equals(moduleName) || "main".equals(moduleName)) {
            return "/";
        }

        return "/" + moduleName;
    }

    /**
     * Checks if a location is within test sources.
     *
     * @param location Location to check
     * @return true if the location is in test sources
     */
    public static boolean isTestSource(@Nullable Location<? extends PsiElement> location) {
        if (location == null) {
            return false;
        }

        VirtualFile file = location.getVirtualFile();
        if (file == null) {
            return false;
        }

        Project project = location.getProject();
        ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);

        return projectFileIndex.isInTestSourceContent(file);
    }

    /**
     * Finds the module containing a file path.
     *
     * @param filePath Path to the file
     * @param project Current project
     * @return Module containing the file, or null
     */
    @Nullable
    public static Module getModuleForPath(@Nullable String filePath, @NotNull Project project) {
        if (StringUtil.isEmpty(filePath)) {
            return null;
        }

        try {
            Path path = Paths.get(filePath);
            VirtualFile file = VfsUtil.findFile(path, true);

            if (file != null) {
                return ModuleUtilCore.findModuleForFile(file, project);
            }
        } catch (Exception e) {
            LOG.warn("Failed to find module for path: " + filePath, e);
        }

        return null;
    }

    /**
     * Gets all production (non-test) modules in the project.
     *
     * @param project Current project
     * @return List of production modules
     */
    @NotNull
    public static List<Module> getProductionModules(@NotNull Project project) {
        return Arrays.stream(ModuleManager.getInstance(project).getModules())
                .filter(module -> !isTestModule(module))
                .collect(Collectors.toList());
    }

    /**
     * Gets the web root path as a string for a module.
     *
     * @param module Module to check
     * @return Path to web root, or null if not found
     */
    @Nullable
    public static String getWebRootPath(@NotNull Module module) {
        VirtualFile webRoot = getWebRoot(module);
        return webRoot != null ? webRoot.getPath() : null;
    }

    // ===================== Private Helper Methods =====================

    private static boolean isValidWebRoot(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        // Check for WEB-INF directory
        VirtualFile webInf = dir.findChild("WEB-INF");
        if (webInf != null && webInf.isDirectory()) {
            return true;
        }

        // Check for common web files
        return dir.findChild("index.html") != null ||
                dir.findChild("index.jsp") != null ||
                dir.findChild("index.xhtml") != null;
    }

    private static boolean isTestModule(@NotNull Module module) {
        String name = module.getName().toLowerCase();
        return name.contains("test") ||
                name.contains("spec") ||
                name.endsWith("-test") ||
                name.endsWith("_test");
    }

    @Nullable
    private static Module findBestModule(@NotNull Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();

        if (modules.length == 0) {
            return null;
        }

        if (modules.length == 1) {
            return modules[0];
        }

        // Look for module with project name
        String projectName = project.getName();
        for (Module module : modules) {
            if (module.getName().equals(projectName) ||
                    module.getName().equals(projectName + ".main")) {
                return module;
            }
        }

        // Look for common main module patterns
        for (Module module : modules) {
            String name = module.getName().toLowerCase();
            if (name.equals("main") || name.equals("app") || name.contains("main")) {
                return module;
            }
        }

        // Return first non-test module
        return Arrays.stream(modules)
                .filter(m -> !isTestModule(m))
                .findFirst()
                .orElse(modules[0]);
    }

    @Nullable
    private static Module selectBestWebModule(@NotNull List<Module> webModules, @NotNull Project project) {
        // Prefer module with project name
        String projectName = project.getName();
        for (Module module : webModules) {
            if (module.getName().equalsIgnoreCase(projectName)) {
                return module;
            }
        }

        // Prefer main web module
        for (Module module : webModules) {
            String name = module.getName().toLowerCase();
            if (name.contains("main") && name.contains("web")) {
                return module;
            }
        }

        // Return first
        return webModules.get(0);
    }


}