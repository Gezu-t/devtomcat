package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Module and Project utilities for DevTomcat plugin.
 * Provides methods to identify web modules, locate web roots, and extract context paths
 * from IntelliJ IDEA modules, ensuring compatibility with various web project structures.
 *
 * This class handles:
 * - Detection of web modules based on web roots and build configurations
 * - Identification of web root directories (e.g., src/main/webapp, WebContent)
 * - Extraction of context paths from module names
 * - Test vs. production source detection
 *
 * @author Gezahegn Lemma (Gezu)
 * @version 1.1
 */
public final class TomcatModuleUtils {

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
            "src/main/web",
            "src/main/resources/static",
            "public"
    );

    // Common web file extensions
    private static final Set<String> WEB_FILE_EXTENSIONS = Set.of(
            "html", "jsp", "xhtml", "js", "ts", // Frontend files
            "xml" // web.xml for traditional Java web apps
    );

    private TomcatModuleUtils() {
        // Utility class
    }

    /**
     * Checks if a module is a web module by looking for web roots or build tool configurations.
     *
     * @param module Module to check
     * @return true if the module contains web resources or is configured as a web module
     */
    public static boolean isWebModule(@NotNull Module module) {
        // Skip test modules
        if (isTestModule(module)) {
            return false;
        }

        // Check for web roots
        List<VirtualFile> webRoots = findWebRoots(module);
        if (!webRoots.isEmpty()) {
            return true;
        }

        // Check for build tool configurations (e.g., Maven war plugin)
        return hasWebBuildConfiguration(module);
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

            // Check direct children with web root names
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
    public static boolean isTestSource(@Nullable com.intellij.execution.Location<? extends PsiElement> location) {
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

    // ===================== Private Helper Methods =====================

    /**
     * Validates a directory as a web root by checking for WEB-INF or common web files.
     *
     * @param dir Directory to validate
     * @return true if the directory is a valid web root
     */
    private static boolean isValidWebRoot(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        // Check for WEB-INF directory (traditional Java web apps)
        VirtualFile webInf = dir.findChild("WEB-INF");
        if (webInf != null && webInf.isDirectory()) {
            return true;
        }

        // Check for common web files (including SPA frameworks)
        for (VirtualFile child : dir.getChildren()) {
            if (child.isValid() && !child.isDirectory()) {
                String extension = child.getExtension();
                if (extension != null && WEB_FILE_EXTENSIONS.contains(extension.toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if a module is a test module based on its name.
     *
     * @param module Module to check
     * @return true if the module is likely a test module
     */
    private static boolean isTestModule(@NotNull Module module) {
        String name = module.getName().toLowerCase();
        return name.contains("test") ||
                name.contains("spec") ||
                name.endsWith("-test") ||
                name.endsWith("_test");
    }

    /**
     * Checks if a module has a web build configuration (e.g., Maven war plugin or Gradle war plugin).
     *
     * @param module Module to check
     * @return true if a web build configuration is detected
     */
    private static boolean hasWebBuildConfiguration(@NotNull Module module) {
        Project project = module.getProject();
        VirtualFile baseDir = module.getProject().getBaseDir();

        if (baseDir == null) {
            return false;
        }

        // Check for Maven pom.xml
        VirtualFile pomFile = baseDir.findFileByRelativePath("pom.xml");
        if (pomFile != null && pomFile.exists()) {
            try {
                String content = com.intellij.openapi.vfs.VfsUtil.loadText(pomFile);
                if (content.contains("<packaging>war</packaging>") ||
                        content.contains("maven-war-plugin") ||
                        content.contains("spring-boot-starter-web")) {
                    return true;
                }
            } catch (IOException e) {
                // Ignore file read errors
            }
        }

        // Check for Gradle build.gradle
        VirtualFile gradleFile = baseDir.findFileByRelativePath("build.gradle");
        if (gradleFile != null && gradleFile.exists()) {
            try {
                String content = com.intellij.openapi.vfs.VfsUtil.loadText(gradleFile);
                if (content.contains("apply plugin: 'war'") ||
                        content.contains("org.springframework.boot")) {
                    return true;
                }
            } catch (IOException e) {
                // Ignore file read errors
            }
        }

        return false;
    }
}