package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static com.dev.idea.plugins.tomcat.TomcatConstants.WEB_INF;
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

    @NotNull
    public static List<VirtualFile> findWebRoots(@NotNull Module module) {
        // Use LinkedHashSet to deduplicate: WEB_ROOT_PATHS and WEB_ROOT_NAMES share
        // entries like "webapp", "web", "WebContent", so a single directory can be
        // matched by both loops and added twice without deduplication.
        LinkedHashSet<VirtualFile> webRoots = new LinkedHashSet<>();
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
                if (child.isValid() && child.isDirectory() && WEB_ROOT_NAMES.contains(child.getName())) {
                    if (isValidWebRoot(child)) {
                        webRoots.add(child);
                    }
                }
            }
        }

        return new ArrayList<>(webRoots);
    }

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

    private static boolean isValidWebRoot(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        // Check for WEB-INF directory (traditional Java web apps)
        VirtualFile webInf = dir.findChild(WEB_INF);
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
     * Determines whether a module is a test module.
     *
     * <p>Uses suffix-based matching to avoid false positives: a module named
     * "devtomcat-test" or "my-contest-app" is NOT a test module, but
     * "myapp.test", "myapp-tests", or "myapp_test" ARE test modules.
     * Also checks Gradle composite build naming (e.g., "project.test").
     */
    private static boolean isTestModule(@NotNull Module module) {
        String name = module.getName().toLowerCase();

        // Suffix-based checks (precise)
        if (name.endsWith(".test") || name.endsWith(".tests") ||
                name.endsWith("-test") || name.endsWith("-tests") ||
                name.endsWith("_test") || name.endsWith("_tests") ||
                name.endsWith(".spec") || name.endsWith("-spec") ||
                name.endsWith("_spec")) {
            return true;
        }

        // Exact name checks
        if ("test".equals(name) || "tests".equals(name)) {
            return true;
        }

        // Gradle sub-project test source sets: "project.module.test"
        // Only match when "test" is the final path segment after a dot
        if (name.contains(".") && name.substring(name.lastIndexOf('.') + 1).equals("test")) {
            return true;
        }

        return false;
    }

    private static boolean hasWebBuildConfiguration(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile root : contentRoots) {
            if (checkMavenWebConfig(root) || checkGradleWebConfig(root)) {
                return true;
            }
        }

        // Also check the project base dir (for single-module projects where the module root differs)
        VirtualFile baseDir = ProjectUtil.guessProjectDir(module.getProject());
        if (baseDir != null) {
            if (checkMavenWebConfig(baseDir) || checkGradleWebConfig(baseDir)) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkMavenWebConfig(@NotNull VirtualFile dir) {
        VirtualFile pomFile = dir.findChild("pom.xml");
        if (pomFile == null || !pomFile.exists()) return false;

        try {
            String content = VfsUtil.loadText(pomFile);
            // POM-packaged projects are aggregators/parents, not web apps
            if (content.contains("<packaging>pom</packaging>")) {
                return false;
            }
            return content.contains("<packaging>war</packaging>") ||
                    content.contains("maven-war-plugin") ||
                    content.contains("spring-boot-starter-web");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean checkGradleWebConfig(@NotNull VirtualFile dir) {
        // Check both Groovy DSL and Kotlin DSL
        VirtualFile gradleFile = dir.findChild("build.gradle");
        if (gradleFile == null) {
            gradleFile = dir.findChild("build.gradle.kts");
        }
        if (gradleFile == null || !gradleFile.exists()) return false;

        try {
            String content = VfsUtil.loadText(gradleFile);
            return content.contains("apply plugin: 'war'") ||
                    content.contains("id(\"war\")") ||
                    content.contains("id 'war'") ||
                    content.contains("id \"war\"") ||
                    content.contains("plugin 'war'") ||
                    content.contains("org.springframework.boot") ||
                    content.contains("spring-boot-starter-web");
        } catch (IOException e) {
            return false;
        }
    }
}