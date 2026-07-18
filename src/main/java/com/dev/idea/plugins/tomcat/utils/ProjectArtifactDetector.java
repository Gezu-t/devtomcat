package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Headless deployment detection for Tomcat, feeding the deployment-picker UI.
 *
 * <p>Produces typed {@link Deployment} objects from two sources:
 * <ul>
 *   <li>{@link #detectWebModules} — web modules, as {@link ModuleBackedDeployment}s
 *       pointing at the module's exploded <em>build output</em> (never a source
 *       directory — the sync pipeline writes {@code WEB-INF/classes} under the
 *       deployment path).</li>
 *   <li>{@link #scanForWarFiles} — WAR files / exploded directories in build output,
 *       as {@link ExternalFileDeployment}s.</li>
 * </ul>
 *
 * <p>UI-free and safe to call from any non-EDT context (read-action handled
 * internally).
 */
public final class ProjectArtifactDetector {

    private static final Logger LOG = Logger.getInstance(ProjectArtifactDetector.class);

    private ProjectArtifactDetector() {}

    /**
     * Detects deployable web modules as {@link ModuleBackedDeployment}s, one per
     * web module, with an <em>exploded build-output</em> deployment path.
     *
     * <p>Exploded deployments deploy in place ({@code docBase} = the path) and the
     * sync pipeline writes {@code WEB-INF/classes} under it, so the path must be a
     * build output, never a source directory. For a Maven WAR module the scanner
     * gives {@code target/<finalName>}; only when no build output is determinable
     * does it fall back to a web root (which {@link DeploymentSafety} then refuses
     * to write into at sync time, so a source tree is never corrupted either way).
     */
    @NotNull
    public static List<Deployment> detectWebModules(@NotNull Project project) {
        return TomcatReadActions.compute(() -> {
            List<Deployment> results = new ArrayList<>();

            try {
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    if (!TomcatModuleUtils.isWebModule(module)) continue;

                    String contextPath = TomcatModuleUtils.extractContextPath(module);
                    Path deployPath = deployableExplodedPath(module);
                    if (deployPath == null) continue;

                    results.add(ModuleBackedDeployment.ofName(
                            project, module.getName(), deployPath,
                            contextPath, /* exploded */ true));
                }
            } catch (Exception e) {
                LOG.warn("DevTomcat: Error detecting web modules", e);
            }

            return results;
        });
    }

    /**
     * The exploded deployment path for a web module: its build output when one is
     * determinable (Maven {@code target/<finalName>}), else a web root as a
     * fallback, else the module content root. Never returns {@code null} for a
     * module with any content root. Must be called under a read action.
     */
    @Nullable
    private static Path deployableExplodedPath(@NotNull Module module) {
        var detected = com.dev.idea.plugins.tomcat.setting.ProjectTomcatProfileScanner.scanModule(module);
        if (detected != null) {
            return Path.of(detected.explodedPath());
        }
        List<VirtualFile> webRoots = TomcatModuleUtils.findWebRoots(module);
        if (!webRoots.isEmpty()) {
            return Path.of(webRoots.get(0).getPath());
        }
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        return contentRoots.length > 0 ? Path.of(contentRoots[0].getPath()) : null;
    }

    /**
     * Scans Gradle and Maven output directories for WAR files and exploded WARs,
     * returning {@link ExternalFileDeployment}s.
     */
    @NotNull
    public static List<Deployment> scanForWarFiles(@NotNull Project project) {
        List<Deployment> results = new ArrayList<>();
        String basePath = project.getBasePath();
        if (basePath == null) return results;

        scanWarDirectory(new File(basePath, "build/libs"), results);
        scanWarDirectory(new File(basePath, "build/distributions"), results);
        scanWarDirectory(new File(basePath, "target"), results);
        scanWarDirectory(new File(basePath, "out/artifacts"), results);

        try {
            List<String> modulePaths = TomcatReadActions.compute(() -> {
                List<String> paths = new ArrayList<>();
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                        paths.add(root.getPath());
                    }
                }
                return paths;
            });
            for (String rootPath : modulePaths) {
                scanWarDirectory(new File(rootPath, "build/libs"), results);
                scanWarDirectory(new File(rootPath, "build/distributions"), results);
                scanWarDirectory(new File(rootPath, "target"), results);
                scanWarDirectory(new File(rootPath, "out/artifacts"), results);
            }
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error scanning module build outputs", e);
        }

        return collapseByContextPath(deduplicate(results));
    }

    /**
     * Collapses scanned candidates that resolve to the same Tomcat context path,
     * keeping one deterministically. A standard {@code mvn package} war build leaves
     * both {@code target/myapp.war} and the exploded {@code target/myapp/} sibling —
     * both generate context {@code /myapp}, and {@link Deployment#equals} does not
     * merge them because their paths differ. Emitting both yields two deployments
     * fighting over one context, which fails at deploy time. When both forms are
     * present for a context we prefer the packaged {@code .war} (the canonical build
     * artifact); otherwise we keep the first candidate seen.
     */
    @NotNull
    private static List<Deployment> collapseByContextPath(@NotNull List<Deployment> deployments) {
        LinkedHashMap<String, Deployment> byContext = new LinkedHashMap<>();
        for (Deployment d : deployments) {
            String context = d.getContextPath();
            Deployment existing = byContext.get(context);
            if (existing == null) {
                byContext.put(context, d);
            } else if (existing.isExploded() && !d.isExploded()) {
                // Prefer the packaged .war over its exploded sibling.
                byContext.put(context, d);
            }
        }
        return new ArrayList<>(byContext.values());
    }

    /** Same web-artifact-type detection as before, on the platform {@link Artifact}. */
    public static boolean isWebArtifact(@NotNull Artifact artifact) {
        try {
            ArtifactType type = artifact.getArtifactType();
            if (type == null) return false;

            String typeId = type.getId();
            if (typeId != null) {
                String lower = typeId.toLowerCase(Locale.ROOT);
                if (lower.contains("war") || lower.contains("web-application")) {
                    return true;
                }
            }

            String typeName = type.getPresentableName();
            if (typeName != null) {
                String lower = typeName.toLowerCase(Locale.ROOT);
                if (lower.contains("web application") || lower.contains("war")) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error checking web artifact: " + artifact.getName(), e);
            return false;
        }
    }

    // =====================================================================
    // Private helpers
    // =====================================================================

    private static void scanWarDirectory(@NotNull File dir, @NotNull List<Deployment> results) {
        if (!dir.isDirectory()) return;

        File[] entries = dir.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (entry.isFile() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".war")) {
                results.add(new ExternalFileDeployment(
                        Path.of(entry.getAbsolutePath()),
                        ContextPathUtils.generateContextPath(entry.getName()),
                        /* exploded */ false));
            } else if (entry.isDirectory()) {
                File webInf = new File(entry, TomcatConstants.WEB_INF);
                if (webInf.isDirectory()) {
                    results.add(new ExternalFileDeployment(
                            Path.of(entry.getAbsolutePath()),
                            ContextPathUtils.generateContextPath(entry.getName()),
                            /* exploded */ true));
                } else {
                    File[] subWarFiles = entry.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".war"));
                    if (subWarFiles != null) {
                        for (File war : subWarFiles) {
                            results.add(new ExternalFileDeployment(
                                    Path.of(war.getAbsolutePath()),
                                    ContextPathUtils.generateContextPath(war.getName()),
                                    /* exploded */ false));
                        }
                    }
                }
            }
        }
    }

    /**
     * Deduplicates while preserving insertion order. Equality is the typed
     * {@code Deployment.equals()} (Artifact-name, Module-name + output-path,
     * or external Path + context).
     */
    @NotNull
    private static List<Deployment> deduplicate(@NotNull List<Deployment> deployments) {
        LinkedHashSet<Deployment> unique = new LinkedHashSet<>(deployments);
        return new ArrayList<>(unique);
    }
}
