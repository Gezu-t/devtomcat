package com.dev.idea.plugins.tomcat.setting;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.utils.MavenModelProvider;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
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
    private static final Pattern POM_PARENT_BLOCK = Pattern.compile(
            "<parent>(.*?)</parent>", Pattern.DOTALL);
    /**
     * Container sections that can hold third-party {@code <artifactId>}/
     * {@code <version>} tags (dependencies, plugins, …). A module's own
     * coordinates always precede them, so matching stops at the first one.
     */
    private static final Pattern POM_SECTIONS = Pattern.compile(
            "<(properties|dependencyManagement|dependencies|build|profiles|modules|repositories|pluginRepositories)>");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern POM_PROFILES_BLOCK = Pattern.compile("<profiles>.*?</profiles>", Pattern.DOTALL);
    private static final Pattern POM_BUILD_BLOCK = Pattern.compile("<build>(.*?)</build>", Pattern.DOTALL);
    private static final Pattern POM_PLUGIN_BLOCKS = Pattern.compile(
            "<(plugins|pluginManagement)>.*?</\\1>", Pattern.DOTALL);
    private static final Pattern POM_FINAL_NAME = Pattern.compile("<finalName>\\s*([^<\\s]+)\\s*</finalName>");

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

        /** The build output's name: the exploded directory's last segment, i.e. Maven's {@code build.finalName}. */
        @NotNull
        public String outputName() {
            int slash = Math.max(explodedPath.lastIndexOf('/'), explodedPath.lastIndexOf('\\'));
            return explodedPath.substring(slash + 1);
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
                DetectedWebappModule detected = scanModule(module);
                if (detected != null) found.add(detected);
            }
            return found;
        });

        List<TomcatInfo> tomcats = new ArrayList<>();
        try {
            tomcats.addAll(TomcatServerManagerState.getInstance().getTomcatInfos());
        } catch (Throwable t) {
            TomcatProgress.rethrowIfControlFlow(t);
            LOG.debug("Profile scanner: TomcatServerManagerState unavailable: " + t.getMessage());
        }

        return new ProjectProfile(webapps, tomcats);
    }

    /**
     * Inspects a single module for WAR packaging and, when found, returns its
     * detected webapp profile — Maven artifactId, version, and the exploded
     * build-output path ({@code <build.directory>/<build.finalName>}, where
     * maven-war-plugin writes the exploded webapp). Returns {@code null} for modules that
     * don't package a WAR.
     *
     * <p>The exploded path is a <em>build output</em>, never a source web root —
     * deployment paths feed the class-sync pipeline, which writes into (and
     * reconciles deletions under) {@code WEB-INF/classes} beneath them, so a
     * source directory must never be used as one.
     *
     * <p><strong>Must be called inside a read action</strong> (module roots +
     * VFS access). {@link #scan} wraps its loop in one; other callers (e.g. the
     * run-configuration producer, which the platform already invokes under a
     * read action) are responsible for their own.
     */
    @Nullable
    public static DetectedWebappModule scanModule(@NotNull Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        for (VirtualFile root : contentRoots) {
            VirtualFile pom = root.findChild(TomcatConstants.MAVEN_BUILD_FILE);
            if (pom == null || !pom.exists()) continue;
            String pomText;
            try {
                pomText = VfsUtil.loadText(pom);
            } catch (IOException e) {
                LOG.debug("Profile scanner: cannot read " + pom.getPath() + ": " + e.getMessage());
                continue;
            }
            PomCoordinates coords = parseWarCoordinates(pomText, module.getName());
            if (coords == null) continue;

            String explodedPath = explodedOutputPath(root.getPath(),
                    MavenModelProvider.buildDirectory(module), MavenModelProvider.finalName(module), coords);

            return new DetectedWebappModule(
                    module.getName(), coords.artifactId(), coords.version(), explodedPath);
        }
        return null;
    }

    /** The Maven coordinates that name a WAR module's build output. */
    record PomCoordinates(@NotNull String artifactId, @NotNull String version, @NotNull String finalName) {}

    /**
     * Extracts a WAR module's own artifactId/version/finalName from raw pom text, or
     * {@code null} when the pom doesn't declare WAR packaging.
     *
     * <p>A multi-module child pom declares its {@code <parent>} coordinates
     * <em>before</em> its own, so a first-match scan over the raw text returns
     * the parent's artifactId — the wrong build-output name for every standard
     * child module. The parent block is therefore stripped before matching:
     * the first artifactId left is the module's own. Version resolution follows
     * Maven inheritance — the module's own {@code <version>} when declared,
     * else the parent's.
     */
    @Nullable
    static PomCoordinates parseWarCoordinates(@NotNull String pomText, @NotNull String fallbackArtifactId) {
        if (!POM_PACKAGING.matcher(pomText).find()) return null;

        String parentBlock = firstMatch(POM_PARENT_BLOCK, pomText, "");
        String ownText = POM_PARENT_BLOCK.matcher(pomText).replaceFirst("");
        Matcher section = POM_SECTIONS.matcher(ownText);
        if (section.find()) {
            ownText = ownText.substring(0, section.start());
        }

        String artifactId = firstMatch(POM_ARTIFACT_ID, ownText, fallbackArtifactId);
        String inheritedVersion = firstMatch(POM_VERSION, parentBlock, "1.0-SNAPSHOT");
        String version = firstMatch(POM_VERSION, ownText, inheritedVersion);

        return new PomCoordinates(artifactId, version, finalNameFrom(pomText, artifactId, version));
    }

    /**
     * {@code <build.directory>/<build.finalName>}: the resolved Maven model's values, else the pom's literal
     * ones under {@code <contentRoot>/target}.
     */
    @NotNull
    static String explodedOutputPath(@NotNull String contentRoot, @Nullable String resolvedBuildDirectory,
                                     @Nullable String resolvedFinalName, @NotNull PomCoordinates coords) {
        String dir = resolvedBuildDirectory != null ? resolvedBuildDirectory : contentRoot + "/target";
        return dir + "/" + (resolvedFinalName != null ? resolvedFinalName : coords.finalName());
    }

    /**
     * The pom's literal {@code <build><finalName>} (profiles and plugin configuration excluded), else Maven's
     * default {@code artifactId-version}; an unresolvable {@code ${...}} also falls back to the default.
     */
    @NotNull
    static String finalNameFrom(@NotNull String pomText, @NotNull String artifactId, @NotNull String version) {
        String text = POM_PROFILES_BLOCK.matcher(XML_COMMENT.matcher(pomText).replaceAll("")).replaceAll("");
        String build = POM_PLUGIN_BLOCKS.matcher(firstMatch(POM_BUILD_BLOCK, text, "")).replaceAll("");
        String name = firstMatch(POM_FINAL_NAME, build, "")
                .replace("${project.artifactId}", artifactId).replace("${artifactId}", artifactId)
                .replace("${project.version}", version).replace("${version}", version);
        return name.isEmpty() || name.contains("${") ? artifactId + "-" + version : name;
    }

    @NotNull
    private static String firstMatch(@NotNull Pattern pattern, @NotNull String text, @NotNull String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }
}
