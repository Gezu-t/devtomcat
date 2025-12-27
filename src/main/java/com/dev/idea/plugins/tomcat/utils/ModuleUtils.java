package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * IntelliJ module utilities for web project detection.
 *
 * @author Gezahegn Lemma
 */
public final class ModuleUtils {

    private static final Set<String> WEB_ROOT_NAMES = Set.of(
        "webapp", "WebContent", "web", "WebRoot", "public", "www"
    );

    private static final List<String> WEB_ROOT_PATHS = Arrays.asList(
        "src/main/webapp", "web", "WebContent", "webapp", "WebRoot", "public"
    );

    private static final Set<String> WEB_EXTENSIONS = Set.of(
        "html", "jsp", "xhtml", "js", "ts", "xml"
    );

    private ModuleUtils() {}

    public static boolean isWebModule(@NotNull Module module) {
        if (isTestModule(module)) return false;
        return !findWebRoots(module).isEmpty() || hasWebBuildConfig(module);
    }

    @NotNull
    public static List<VirtualFile> findWebRoots(@NotNull Module module) {
        List<VirtualFile> roots = new ArrayList<>();

        for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
            // Check standard paths
            for (String path : WEB_ROOT_PATHS) {
                VirtualFile webRoot = contentRoot.findFileByRelativePath(path);
                if (isValidWebRoot(webRoot)) roots.add(webRoot);
            }

            // Check direct children
            for (VirtualFile child : contentRoot.getChildren()) {
                if (child.isDirectory() && WEB_ROOT_NAMES.contains(child.getName())) {
                    if (isValidWebRoot(child)) roots.add(child);
                }
            }
        }

        return roots;
    }

    @NotNull
    public static String extractContextPath(@NotNull Module module) {
        String name = module.getName();

        // Remove common suffixes
        for (String suffix : Arrays.asList(".main", ".web", "-web", "_web")) {
            name = StringUtil.trimEnd(name, suffix);
        }

        // Get last component
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) name = name.substring(lastDot + 1);

        // Clean up
        name = name.toLowerCase()
                   .replaceAll("[^a-z0-9-]", "-")
                   .replaceAll("-+", "-")
                   .replaceAll("^-|-$", "");

        if (name.isEmpty() || "root".equals(name) || "main".equals(name)) {
            return "/";
        }

        return "/" + name;
    }

    private static boolean isValidWebRoot(@Nullable VirtualFile dir) {
        if (dir == null || !dir.isDirectory()) return false;

        // Check for WEB-INF
        VirtualFile webInf = dir.findChild("WEB-INF");
        if (webInf != null && webInf.isDirectory()) return true;

        // Check for web files
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isDirectory()) {
                String ext = child.getExtension();
                if (ext != null && WEB_EXTENSIONS.contains(ext.toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isTestModule(@NotNull Module module) {
        String name = module.getName().toLowerCase();
        return name.contains("test") || name.contains("spec");
    }

    private static boolean hasWebBuildConfig(@NotNull Module module) {
        VirtualFile baseDir = module.getProject().getBaseDir();
        if (baseDir == null) return false;

        // Check pom.xml
        VirtualFile pom = baseDir.findFileByRelativePath("pom.xml");
        if (pom != null) {
            try {
                String content = VfsUtil.loadText(pom);
                if (content.contains("<packaging>war</packaging>") ||
                    content.contains("maven-war-plugin")) {
                    return true;
                }
            } catch (IOException ignored) {}
        }

        // Check build.gradle
        VirtualFile gradle = baseDir.findFileByRelativePath("build.gradle");
        if (gradle != null) {
            try {
                String content = VfsUtil.loadText(gradle);
                if (content.contains("apply plugin: 'war'")) return true;
            } catch (IOException ignored) {}
        }

        return false;
    }
}