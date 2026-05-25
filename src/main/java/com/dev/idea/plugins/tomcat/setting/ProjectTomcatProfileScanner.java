package com.dev.idea.plugins.tomcat.setting;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Scans a Project for WAR-packaging modules and registered Tomcats.
// Pure data; UI consumes the result.
public final class ProjectTomcatProfileScanner {

    private static final Logger LOG = Logger.getInstance(ProjectTomcatProfileScanner.class);

    private static final Pattern POM_PACKAGING = Pattern.compile(
            "<packaging>\\s*war\\s*</packaging>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POM_ARTIFACT_ID = Pattern.compile(
            "<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern POM_VERSION = Pattern.compile(
            "<version>\\s*([^<\\s]+)\\s*</version>");

    public record DetectedWebappModule(
            @NotNull String moduleName,
            @NotNull String artifactId,
            @NotNull String version,
            @NotNull String explodedPath,
            @NotNull String contextPath) {
        /** Convenience: default context path is {@code "/" + artifactId}. */
        public DetectedWebappModule(@NotNull String moduleName,
                                    @NotNull String artifactId,
                                    @NotNull String version,
                                    @NotNull String explodedPath) {
            this(moduleName, artifactId, version, explodedPath, "/" + artifactId);
        }

        /** Returns a copy with {@code contextPath} replaced; other fields unchanged. */
        @NotNull
        public DetectedWebappModule withContextPath(@NotNull String newContextPath) {
            return new DetectedWebappModule(moduleName, artifactId, version, explodedPath, newContextPath);
        }
    }

    public record ProjectProfile(
            @NotNull List<DetectedWebappModule> webappModules,
            @NotNull List<TomcatInfo> registeredTomcats) {
        public boolean isEmpty() { return webappModules.isEmpty(); }
    }

    private ProjectTomcatProfileScanner() {}

    @NotNull
    public static ProjectProfile scan(@NotNull Project project) {
        if (project.isDisposed()) {
            return new ProjectProfile(List.of(), List.of());
        }
        List<DetectedWebappModule> webapps = com.dev.idea.plugins.tomcat.utils.TomcatReadActions.compute(() -> {
            List<DetectedWebappModule> found = new ArrayList<>();
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                DetectedWebappModule detected = inspectModule(module);
                if (detected != null) found.add(detected);
            }
            return found;
        });

        List<TomcatInfo> tomcats = new ArrayList<>();
        try {
            tomcats.addAll(TomcatServerManagerState.getInstance().getTomcatInfos());
        } catch (Throwable t) {
            LOG.debug("Profile scanner: TomcatServerManagerState unavailable: " + t.getMessage());
        }

        return new ProjectProfile(webapps, tomcats);
    }

    @Nullable
    private static DetectedWebappModule inspectModule(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        for (VirtualFile root : contentRoots) {
            VirtualFile pom = root.findChild("pom.xml");
            if (pom == null || !pom.exists()) continue;
            String pomText;
            try {
                pomText = VfsUtil.loadText(pom);
            } catch (IOException e) {
                LOG.debug("Profile scanner: cannot read " + pom.getPath() + ": " + e.getMessage());
                continue;
            }
            if (!POM_PACKAGING.matcher(pomText).find()) continue;

            String artifactId = firstMatch(POM_ARTIFACT_ID, pomText, module.getName());
            String version = firstMatch(POM_VERSION, pomText, "1.0-SNAPSHOT");
            String explodedPath = root.getPath() + "/target/" + artifactId + "-" + version;

            return new DetectedWebappModule(module.getName(), artifactId, version, explodedPath);
        }
        return null;
    }

    @NotNull
    private static String firstMatch(@NotNull Pattern pattern, @NotNull String text, @NotNull String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }
}
