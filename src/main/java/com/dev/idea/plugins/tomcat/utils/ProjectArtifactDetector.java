package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
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
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Headless deployment detection for Tomcat.
 *
 * <p>Produces typed {@link Deployment} objects from one of three sources, in priority
 * order:
 * <ol>
 *   <li>IntelliJ-configured web artifacts → {@link ArtifactBackedDeployment}</li>
 *   <li>Web modules with web roots → {@link ModuleBackedDeployment}</li>
 *   <li>WAR files / exploded directories on disk → {@link ExternalFileDeployment}</li>
 * </ol>
 *
 * <p>UI-free and safe to call from configuration initialization, factory defaults,
 * or any non-EDT context (read-action handled internally).
 */
public final class ProjectArtifactDetector {

    private static final Logger LOG = Logger.getInstance(ProjectArtifactDetector.class);

    private ProjectArtifactDetector() {}

    @NotNull
    public static List<Deployment> detect(@NotNull Project project) {
        List<Deployment> artifacts = detectIntelliJWebArtifacts(project);
        if (!artifacts.isEmpty()) {
            LOG.info("DevTomcat: Auto-detected " + artifacts.size() +
                    " IntelliJ web artifact(s) for project: " + project.getName());
            return artifacts;
        }

        artifacts = detectWebModules(project);
        if (!artifacts.isEmpty()) {
            LOG.info("DevTomcat: Auto-detected " + artifacts.size() +
                    " web module(s) for project: " + project.getName());
            return artifacts;
        }

        artifacts = scanForWarFiles(project);
        if (!artifacts.isEmpty()) {
            LOG.info("DevTomcat: Auto-detected " + artifacts.size() +
                    " WAR file(s) in build output for project: " + project.getName());
            return artifacts;
        }

        LOG.debug("DevTomcat: No deployable artifacts detected in project: " + project.getName());
        return Collections.emptyList();
    }

    /**
     * Detects IntelliJ-configured web artifacts as {@link ArtifactBackedDeployment}s.
     *
     * <p>{@code artifactManager.getArtifacts()} only returns live, registered
     * artifacts, and {@link ArtifactBackedDeployment} holds an {@code ArtifactPointer}
     * the platform keeps valid across rename/delete, so orphan concerns are handled
     * structurally downstream. We deliberately do <em>not</em> filter by a
     * name-string-to-module-name heuristic here: that silently dropped valid
     * user-renamed artifacts whose name no longer encodes the module name.
     */
    @NotNull
    public static List<Deployment> detectIntelliJWebArtifacts(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<Deployment>>) () -> {
            ArtifactManager artifactManager = getArtifactManager(project);
            if (artifactManager == null) return Collections.<Deployment>emptyList();

            List<Deployment> results = new ArrayList<>();
            for (Artifact artifact : artifactManager.getArtifacts()) {
                if (!isWebArtifact(artifact)) continue;

                results.add(ArtifactBackedDeployment.ofName(
                        project,
                        artifact.getName(),
                        ContextPathUtils.generateContextPath(artifact.getName())));
            }
            return deduplicate(results);
        });
    }

    /**
     * Detects web modules with web root directories as {@link ModuleBackedDeployment}s.
     */
    @NotNull
    public static List<Deployment> detectWebModules(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<Deployment>>) () -> {
            List<Deployment> results = new ArrayList<>();

            try {
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    if (!TomcatModuleUtils.isWebModule(module)) continue;

                    String contextPath = TomcatModuleUtils.extractContextPath(module);
                    List<VirtualFile> webRoots = TomcatModuleUtils.findWebRoots(module);
                    if (webRoots.isEmpty()) {
                        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
                        if (contentRoots.length > 0) {
                            results.add(ModuleBackedDeployment.ofName(
                                    project, module.getName(),
                                    Path.of(contentRoots[0].getPath()),
                                    contextPath, /* exploded */ true));
                        }
                    } else {
                        for (VirtualFile webRoot : webRoots) {
                            // The display name on ModuleBackedDeployment comes from the
                            // ModulePointer; the multi-webroot disambiguation suffix
                            // ("(webroot-name)") that legacy applied to a free-form
                            // string can't ride along the pointer. Multi-webroot is rare
                            // in practice — pick the first match for now.
                            results.add(ModuleBackedDeployment.ofName(
                                    project, module.getName(),
                                    Path.of(webRoot.getPath()),
                                    contextPath, /* exploded */ true));
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("DevTomcat: Error detecting web modules", e);
            }

            return results;
        });
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
            List<String> modulePaths = ApplicationManager.getApplication().runReadAction((Computable<List<String>>) () -> {
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

    @Nullable
    private static ArtifactManager getArtifactManager(@NotNull Project project) {
        try {
            return ArtifactManager.getInstance(project);
        } catch (Exception e) {
            LOG.debug("DevTomcat: ArtifactManager not available: " + e.getMessage());
            return null;
        }
    }

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
