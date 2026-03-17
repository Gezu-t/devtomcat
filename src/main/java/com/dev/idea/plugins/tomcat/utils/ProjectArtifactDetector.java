package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
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
import java.util.*;

/**
 * Headless artifact detection for Tomcat deployments.
 *
 * <p>Detects deployable web artifacts from IntelliJ's artifact system,
 * web modules, and build output directories (Gradle/Maven).
 *
 * <p>Detection priority (first tier with results wins):
 * <ol>
 *   <li>IntelliJ-configured web artifacts (Project Structure &rarr; Artifacts)</li>
 *   <li>Web modules with web roots (src/main/webapp, WEB-INF, etc.)</li>
 *   <li>WAR files in build output (build/libs, target)</li>
 * </ol>
 *
 * <p>This class is UI-free and safe to call from configuration initialization,
 * factory defaults, or any non-EDT context.
 */
public final class ProjectArtifactDetector {

    private static final Logger LOG = Logger.getInstance(ProjectArtifactDetector.class);

    private ProjectArtifactDetector() {}

    /**
     * Detects all deployable artifacts in the project using a tiered strategy.
     * Returns results from the highest-priority tier that produces matches.
     *
     * @param project the current project
     * @return detected deployment artifacts (never null, may be empty)
     */
    @NotNull
    public static List<DeploymentArtifact> detect(@NotNull Project project) {
        // Tier 1: IntelliJ-configured web artifacts (highest quality — user explicitly set these up)
        List<DeploymentArtifact> artifacts = detectIntelliJWebArtifacts(project);
        if (!artifacts.isEmpty()) {
            LOG.info("DevTomcat: Auto-detected " + artifacts.size() +
                    " IntelliJ web artifact(s) for project: " + project.getName());
            return artifacts;
        }

        // Tier 2: Web modules with web roots (detected from project structure)
        artifacts = detectWebModules(project);
        if (!artifacts.isEmpty()) {
            LOG.info("DevTomcat: Auto-detected " + artifacts.size() +
                    " web module(s) for project: " + project.getName());
            return artifacts;
        }

        // Tier 3: WAR files in build output (Gradle/Maven artifacts)
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
     * Detects IntelliJ-configured web artifacts (war, exploded-war, web-application).
     */
    @NotNull
    public static List<DeploymentArtifact> detectIntelliJWebArtifacts(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<DeploymentArtifact>>) () -> {
            ArtifactManager artifactManager = getArtifactManager(project);
            if (artifactManager == null) return Collections.<DeploymentArtifact>emptyList();

            List<DeploymentArtifact> results = new ArrayList<>();
            for (Artifact artifact : artifactManager.getArtifacts()) {
                if (!isWebArtifact(artifact)) continue;

                String typeId = artifact.getArtifactType().getId().toLowerCase();
                String type = typeId.contains("exploded")
                        ? DeploymentArtifact.TYPE_EXPLODED
                        : DeploymentArtifact.TYPE_WAR;

                String outputPath = artifact.getOutputFilePath();
                DeploymentArtifact deployment = new DeploymentArtifact(
                        artifact.getName(),
                        outputPath != null ? outputPath : "",
                        type
                );
                deployment.setContextPath(ContextPathUtils.generateContextPath(artifact.getName()));
                results.add(deployment);
            }
            return deduplicate(results);
        });
    }

    /**
     * Detects web modules with web root directories (src/main/webapp, WEB-INF, etc.).
     */
    @NotNull
    public static List<DeploymentArtifact> detectWebModules(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<DeploymentArtifact>>) () -> {
            List<DeploymentArtifact> results = new ArrayList<>();

            try {
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    if (!TomcatModuleUtils.isWebModule(module)) continue;

                    List<VirtualFile> webRoots = TomcatModuleUtils.findWebRoots(module);
                    if (webRoots.isEmpty()) {
                        // Use module content root as fallback for web modules without explicit web roots
                        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
                        if (contentRoots.length > 0) {
                            results.add(createModuleArtifact(module, contentRoots[0].getPath()));
                        }
                    } else {
                        for (VirtualFile webRoot : webRoots) {
                            String name = module.getName();
                            if (webRoots.size() > 1) {
                                name = module.getName() + " (" + webRoot.getName() + ")";
                            }
                            DeploymentArtifact deployment = new DeploymentArtifact(
                                    name,
                                    webRoot.getPath(),
                                    DeploymentArtifact.TYPE_EXPLODED
                            );
                            deployment.setContextPath(TomcatModuleUtils.extractContextPath(module));
                            results.add(deployment);
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
     * Scans Gradle and Maven output directories for WAR files.
     *
     * <p>Checks the following locations at both project and module level:
     * <ul>
     *   <li>{@code build/libs} — Gradle default WAR output</li>
     *   <li>{@code build/distributions} — Gradle distribution plugin</li>
     *   <li>{@code target} — Maven default output</li>
     *   <li>{@code out/artifacts} — IntelliJ build output</li>
     * </ul>
     */
    @NotNull
    public static List<DeploymentArtifact> scanForWarFiles(@NotNull Project project) {
        List<DeploymentArtifact> results = new ArrayList<>();
        String basePath = project.getBasePath();
        if (basePath == null) return results;

        // Project-level build output
        scanWarDirectory(new File(basePath, "build/libs"), results);
        scanWarDirectory(new File(basePath, "build/distributions"), results);
        scanWarDirectory(new File(basePath, "target"), results);
        scanWarDirectory(new File(basePath, "out/artifacts"), results);

        // Module-level build output (requires read access for ModuleManager)
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

        return deduplicate(results);
    }

    /**
     * Checks whether an IntelliJ Artifact represents a deployable web artifact.
     *
     * <p>Matches by artifact type ID and presentable name against known web artifact
     * patterns across IntelliJ Ultimate and Community editions. Defensively handles
     * null type IDs and names from third-party plugins or future IntelliJ versions.
     */
    public static boolean isWebArtifact(@NotNull Artifact artifact) {
        try {
            ArtifactType type = artifact.getArtifactType();
            if (type == null) return false;

            String typeId = type.getId();
            if (typeId != null) {
                String lower = typeId.toLowerCase();
                // Covers: war, exploded-war, web-application, web-application-exploded
                if (lower.contains("war") || lower.contains("web-application")) {
                    return true;
                }
            }

            String typeName = type.getPresentableName();
            if (typeName != null) {
                String lower = typeName.toLowerCase();
                // Covers: "Web Application: Archive", "Web Application: Exploded", "WAR"
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

    /**
     * Filters a list of artifacts to exclude those already present in an existing collection.
     * Matches by artifact name (case-insensitive).
     */
    @NotNull
    public static List<DeploymentArtifact> filterExisting(
            @NotNull List<DeploymentArtifact> candidates,
            @NotNull Collection<String> existingNames) {
        Set<String> lowerNames = new HashSet<>();
        for (String name : existingNames) {
            lowerNames.add(name.toLowerCase());
        }
        List<DeploymentArtifact> filtered = new ArrayList<>();
        for (DeploymentArtifact candidate : candidates) {
            if (!lowerNames.contains(candidate.getName().toLowerCase())) {
                filtered.add(candidate);
            }
        }
        return filtered;
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

    @NotNull
    private static DeploymentArtifact createModuleArtifact(@NotNull Module module, @NotNull String path) {
        DeploymentArtifact deployment = new DeploymentArtifact(
                module.getName(),
                path,
                DeploymentArtifact.TYPE_EXPLODED
        );
        deployment.setContextPath(TomcatModuleUtils.extractContextPath(module));
        return deployment;
    }

    private static void scanWarDirectory(@NotNull File dir, @NotNull List<DeploymentArtifact> results) {
        if (!dir.isDirectory()) return;

        File[] entries = dir.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (entry.isFile() && entry.getName().toLowerCase().endsWith(".war")) {
                DeploymentArtifact deployment = new DeploymentArtifact(
                        entry.getName(),
                        entry.getAbsolutePath(),
                        DeploymentArtifact.TYPE_WAR
                );
                deployment.setContextPath(ContextPathUtils.generateContextPath(entry.getName()));
                results.add(deployment);
            } else if (entry.isDirectory()) {
                // Check one level of subdirectories (e.g., out/artifacts/myapp_war/)
                // for both WAR files and exploded WAR directories (containing WEB-INF)
                File webInf = new File(entry, TomcatConstants.WEB_INF);
                if (webInf.isDirectory()) {
                    DeploymentArtifact deployment = new DeploymentArtifact(
                            entry.getName(),
                            entry.getAbsolutePath(),
                            DeploymentArtifact.TYPE_EXPLODED
                    );
                    deployment.setContextPath(ContextPathUtils.generateContextPath(entry.getName()));
                    results.add(deployment);
                } else {
                    // Scan subdirectory for WAR files
                    File[] subWarFiles = entry.listFiles((d, name) -> name.toLowerCase().endsWith(".war"));
                    if (subWarFiles != null) {
                        for (File war : subWarFiles) {
                            DeploymentArtifact deployment = new DeploymentArtifact(
                                    war.getName(),
                                    war.getAbsolutePath(),
                                    DeploymentArtifact.TYPE_WAR
                            );
                            deployment.setContextPath(ContextPathUtils.generateContextPath(war.getName()));
                            results.add(deployment);
                        }
                    }
                }
            }
        }
    }

    /**
     * Deduplicates by type+path (case-insensitive) while preserving insertion order.
     */
    @NotNull
    private static List<DeploymentArtifact> deduplicate(@NotNull List<DeploymentArtifact> artifacts) {
        Map<String, DeploymentArtifact> unique = new LinkedHashMap<>();
        for (DeploymentArtifact item : artifacts) {
            if (item == null) continue;
            String key = (item.getType() + "|" + item.getPath()).toLowerCase();
            unique.putIfAbsent(key, item);
        }
        return new ArrayList<>(unique.values());
    }
}
