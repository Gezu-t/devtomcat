package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.ProjectArtifactDetector;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ModuleDeploymentDialog;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;

public class ArtifactSelectionHandler {

    private static final Logger LOG = Logger.getInstance(ArtifactSelectionHandler.class);

    private final Project project;
    private final ArtifactManager artifactManager;
    private final DeploymentTableManager tableManager;

    public ArtifactSelectionHandler(@NotNull Project project,
                                    @Nullable ArtifactManager artifactManager,
                                    @NotNull DeploymentTableManager tableManager) {
        this.project = project;
        this.artifactManager = artifactManager;
        this.tableManager = tableManager;
    }

    public void showArtifactSelectionDialog() {
        try {
            // 1. Try IntelliJ-configured artifacts first
            List<Artifact> availableArtifacts = getSelectableArtifacts();
            if (!availableArtifacts.isEmpty()) {
                showIntelliJArtifactDialog(availableArtifacts);
                return;
            }

            // 2. Fall back to auto-detection of web modules and build outputs
            List<DeploymentArtifact> detected = detectDeployables();
            if (!detected.isEmpty()) {
                showAutoDetectedDialog(detected);
                return;
            }

            // 3. Nothing found — show helpful message
            Messages.showWarningDialog(project,
                    "No deployable artifacts found.\n\n" +
                            "Options:\n" +
                            "  - Use '+' → 'External Source...' to select a WAR file or directory\n" +
                            "  - Configure artifacts in File → Project Structure → Artifacts\n" +
                            "  - Add a 'war' plugin to your build.gradle / pom.xml and rebuild",
                    "No Artifacts Available"
            );

        } catch (Exception e) {
            LOG.warn("Error showing artifact dialog", e);
            Messages.showErrorDialog(project,
                    "Error selecting artifacts: " + e.getMessage(),
                    "Selection Error"
            );
        }
    }

    private void showIntelliJArtifactDialog(@NotNull List<Artifact> artifacts) {
        IntelliJArtifactSelectionDialog dialog = new IntelliJArtifactSelectionDialog(project, artifacts);

        if (dialog.showAndGet()) {
            Set<String> existingBaseNames = tableManager.getDeployments().stream()
                    .map(d -> extractBaseModuleName(d.getName()))
                    .collect(Collectors.toSet());

            for (Artifact artifact : dialog.getSelectedArtifacts()) {
                String baseName = extractBaseModuleName(artifact.getName());
                if (existingBaseNames.contains(baseName)) {
                    LOG.debug("Skipping duplicate artifact (base name match): " + artifact.getName());
                    continue;
                }
                existingBaseNames.add(baseName);
                String context = getUniqueContext(generateContextPath(artifact));
                addArtifactWithContext(artifact, context);
            }
        }
    }

    private void showAutoDetectedDialog(@NotNull List<DeploymentArtifact> detected) {
        ModuleDeploymentDialog dialog = new ModuleDeploymentDialog(project, detected);

        if (dialog.showAndGet()) {
            Set<String> existingBaseNames = tableManager.getDeployments().stream()
                    .map(d -> extractBaseModuleName(d.getName()))
                    .collect(Collectors.toSet());

            for (DeploymentArtifact deployment : dialog.getSelectedDeployments()) {
                String baseName = extractBaseModuleName(deployment.getName());
                if (existingBaseNames.contains(baseName)) {
                    LOG.debug("Skipping duplicate deployment (base name match): " + deployment.getName());
                    continue;
                }
                existingBaseNames.add(baseName);
                String context = getUniqueContext(deployment.getContextPath());
                deployment.setContextPath(context);

                tableManager.addAndSelectDeployment(deployment);
                LOG.info("Added auto-detected deployment: " + deployment.getName() +
                        " [" + deployment.getType() + "] context=" + context);
            }
        }
    }

    /**
     * Auto-detects deployable web modules and WAR build outputs in the project.
     * Delegates to {@link ProjectArtifactDetector} and filters out artifacts already
     * present in the deployment table.
     */
    private List<DeploymentArtifact> detectDeployables() {
        List<DeploymentArtifact> modules = ProjectArtifactDetector.detectWebModules(project);
        LOG.info("Auto-detection: " + modules.size() + " web module(s) found");
        for (DeploymentArtifact m : modules) {
            LOG.info("  Web module: " + m.getName() + " [" + m.getType() + "] path=" + m.getPath());
        }

        List<DeploymentArtifact> wars = ProjectArtifactDetector.scanForWarFiles(project);
        LOG.info("Auto-detection: " + wars.size() + " WAR file(s)/exploded dir(s) found");
        for (DeploymentArtifact w : wars) {
            LOG.info("  WAR/Exploded: " + w.getName() + " [" + w.getType() + "] path=" + w.getPath());
        }

        // Combine with modules first (higher quality), then deduplicate by name
        List<DeploymentArtifact> combined = new ArrayList<>(modules);
        combined.addAll(wars);
        combined = deduplicateByName(combined);

        // Filter out POM-packaged parent modules that leak through WAR scans
        Set<String> pomModuleNames = detectPomModuleNames();
        if (!pomModuleNames.isEmpty()) {
            combined.removeIf(item -> pomModuleNames.contains(extractBaseModuleName(item.getName())));
        }

        // Filter out stale artifacts from renamed/removed modules.
        // The out/artifacts/ directory and IntelliJ's ArtifactManager can retain entries
        // for modules that no longer exist after a rename. Without this filter, the user
        // sees old module names in the selection dialog alongside current ones.
        Set<String> activeModules = getActiveModuleNames();
        int beforeFilter = combined.size();
        combined.removeIf(item -> !hasActiveSourceModule(item.getName(), activeModules));
        int filtered = beforeFilter - combined.size();
        if (filtered > 0) {
            LOG.info("Auto-detection: filtered " + filtered +
                    " stale artifact(s) from renamed/removed modules");
        }

        LOG.info("Auto-detection: " + combined.size() + " deployable item(s) available");
        return combined;
    }

    /**
     * Deduplicates artifacts by base module name + type (case-insensitive).
     * Strips common suffixes like {@code _war_exploded}, {@code _war}, {@code .war}
     * to recognize e.g. "webapp-one" and "webapp-one_war_exploded" as the same module.
     * Only merges items of the same deployment type (exploded with exploded, WAR with WAR).
     * When duplicates exist, prefers the variant with a build output path.
     */
    private static List<DeploymentArtifact> deduplicateByName(List<DeploymentArtifact> artifacts) {
        java.util.LinkedHashMap<String, DeploymentArtifact> unique = new java.util.LinkedHashMap<>();
        for (DeploymentArtifact item : artifacts) {
            String key = extractBaseModuleName(item.getName()) + "|" + item.getType();
            DeploymentArtifact existing = unique.get(key);
            if (existing == null) {
                unique.put(key, item);
            } else {
                // Prefer the variant whose path points to build output (out/artifacts, target)
                // over a source directory (src/main/webapp)
                if (isBuildOutputPath(item.getPath()) && !isBuildOutputPath(existing.getPath())) {
                    unique.put(key, item);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String extractBaseModuleName(String name) {
        return ContextPathUtils.extractBaseModuleName(name);
    }

    private static boolean isBuildOutputPath(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/').toLowerCase();
        return normalized.contains("/out/artifacts/") ||
                normalized.contains("/target/") ||
                normalized.contains("/build/libs/");
    }

    /**
     * Detects module names that have POM packaging (Maven aggregator/parent modules).
     * These should not appear as deployable artifacts.
     */
    private Set<String> detectPomModuleNames() {
        try {
            return ReadAction.compute(() -> {
                Set<String> pomNames = new HashSet<>();
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    for (VirtualFile root :
                            ModuleRootManager.getInstance(module).getContentRoots()) {
                        VirtualFile pomFile = root.findChild("pom.xml");
                        if (pomFile != null && pomFile.exists()) {
                            try {
                                String content = VfsUtil.loadText(pomFile);
                                if (content.contains("<packaging>pom</packaging>")) {
                                    pomNames.add(module.getName().toLowerCase());
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                return pomNames;
            });
        } catch (Exception e) {
            LOG.debug("Error detecting POM modules", e);
            return new HashSet<>();
        }
    }

    public void showExternalSourceDialog() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, true, true, false, false)
                .withTitle("Select External WAR or Directory")
                .withDescription("Select a WAR file or exploded directory to deploy");

        VirtualFile chosen = SafeBrowseUtil.chooseFile(descriptor, project, null);
        if (chosen == null) {
            return;
        }

        String name = chosen.getName();
        String type = chosen.isDirectory() ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;
        String localPath = chosen.getPath();

        String context = getUniqueContext(ContextPathUtils.generateContextPath(name));
        DeploymentArtifact deployment = new DeploymentArtifact(name, localPath, type);
        deployment.setContextPath(context);

        tableManager.addAndSelectDeployment(deployment);
        LOG.debug("Added external source: " + name);
    }

    private List<Artifact> getSelectableArtifacts() {
        if (artifactManager == null) {
            return new ArrayList<>();
        }

        try {
            Set<String> activeModules = getActiveModuleNames();

            // artifactManager.getArtifacts() accesses the project model —
            // snapshot the names+refs under a read action, then filter outside.
            List<Artifact> allPlatformArtifacts = ReadAction.compute(
                    () -> List.of(artifactManager.getArtifacts()));

            // Show ALL IntelliJ artifacts (not just web-typed), because Community Edition
            // only has PlainArtifactType (ID: "plain") and JarArtifactType (ID: "jar") —
            // neither passes isWebArtifact(). Users must be able to select any artifact.
            // Sort web artifacts first (exploded → WAR), then others alphabetically.
            List<Artifact> filtered = allPlatformArtifacts.stream()
                    .filter(artifact -> !tableManager.hasDeployment(artifact.getName()))
                    .filter(artifact -> hasActiveSourceModule(artifact.getName(), activeModules))
                    .collect(Collectors.toList());

            return sortByTypeCategory(filtered);
        } catch (Exception e) {
            LOG.warn("Error getting selectable artifacts", e);
            return new ArrayList<>();
        }
    }

    /**
     * Sorts artifacts by type category so the user sees them grouped logically:
     * <ol>
     *   <li>Web exploded artifacts (best for local Tomcat development)</li>
     *   <li>Web WAR artifacts (packaged deployments)</li>
     *   <li>All other artifacts (plain, jar, etc.)</li>
     * </ol>
     * Within each category, artifacts are sorted alphabetically by name.
     * Both exploded AND WAR variants are shown — the user decides which to deploy.
     */
    static List<Artifact> sortByTypeCategory(@NotNull List<Artifact> artifacts) {
        if (artifacts.isEmpty()) return artifacts;

        List<Artifact> result = new ArrayList<>(artifacts);
        result.sort((a, b) -> {
            int catA = typeCategory(a);
            int catB = typeCategory(b);
            if (catA != catB) return Integer.compare(catA, catB);
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    /**
     * Returns a sort-order category for the artifact:
     * 0 = web exploded, 1 = web WAR, 2 = everything else.
     */
    private static int typeCategory(@NotNull Artifact artifact) {
        if (ProjectArtifactDetector.isWebArtifact(artifact)) {
            return isExplodedType(artifact) ? 0 : 1;
        }
        // Non-web artifact — check name patterns as a secondary signal
        // (CE users often name their artifacts with war/exploded suffixes)
        String name = artifact.getName().toLowerCase();
        if (name.contains("exploded")) return 0;
        if (name.contains("war")) return 1;
        return 2;
    }

    /**
     * Determines whether an artifact is an exploded (directory-based) variant
     * by checking both the artifact type ID and the artifact name.
     */
    private static boolean isExplodedType(@NotNull Artifact artifact) {
        try {
            ArtifactType type = artifact.getArtifactType();
            if (type != null) {
                String typeId = type.getId();
                if (typeId != null && typeId.toLowerCase().contains("exploded")) {
                    return true;
                }
                String typeName = type.getPresentableName();
                if (typeName != null && typeName.toLowerCase().contains("exploded")) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.debug("Error checking artifact type for: " + artifact.getName(), e);
        }
        // Fallback: check name pattern
        String name = artifact.getName();
        return name != null && name.toLowerCase().contains("exploded");
    }

    private String getUniqueContext(String baseContext) {
        String context = baseContext;
        int counter = 1;
        while (isContextInUse(context)) {
            context = baseContext + "-" + counter;
            counter++;
        }
        return context;
    }

    private boolean isContextInUse(String context) {
        return tableManager.getDeployments().stream()
                .anyMatch(d -> d.getApplicationContext().equals(context));
    }

    private void addArtifactWithContext(@NotNull Artifact artifact, @NotNull String applicationContext) {
        try {
            String type = resolveDeploymentType(artifact);

            String outputPath = artifact.getOutputFilePath();
            if (outputPath == null) {
                outputPath = "";
            }

            DeploymentArtifact deployment = new DeploymentArtifact(
                    artifact.getName(),
                    outputPath,
                    type
            );
            deployment.setContextPath(applicationContext);

            tableManager.addAndSelectDeployment(deployment);

            LOG.debug("Added artifact: " + artifact.getName() +
                    " [" + type + "] with context: " + applicationContext);

        } catch (Exception e) {
            LOG.warn("Error adding artifact", e);
        }
    }

    /**
     * Resolves the deployment type for an IntelliJ Artifact.
     * Checks the artifact type ID first (works in Ultimate), then falls back
     * to checking the artifact name and output path (needed for Community Edition
     * where types are "plain" or "jar").
     */
    static String resolveDeploymentType(@NotNull Artifact artifact) {
        // 1. Check IntelliJ artifact type ID (authoritative in Ultimate)
        try {
            String typeId = artifact.getArtifactType().getId().toLowerCase();
            if (typeId.contains("exploded")) return DeploymentArtifact.TYPE_EXPLODED;
            if (typeId.contains("war")) return DeploymentArtifact.TYPE_WAR;
        } catch (Exception ignored) {}

        // 2. Check artifact name for type hints (CE users often follow naming conventions)
        String name = artifact.getName().toLowerCase();
        if (name.contains("exploded")) return DeploymentArtifact.TYPE_EXPLODED;
        if (name.endsWith("_war") || name.endsWith(".war") || name.endsWith(":war")) {
            return DeploymentArtifact.TYPE_WAR;
        }

        // 3. Check output path — directory = exploded, file = packaged
        String outputPath = artifact.getOutputFilePath();
        if (outputPath != null) {
            java.io.File outputFile = new java.io.File(outputPath);
            if (outputFile.isDirectory()) return DeploymentArtifact.TYPE_EXPLODED;
            if (outputPath.toLowerCase().endsWith(".war")) return DeploymentArtifact.TYPE_WAR;
        }

        // Default: treat as exploded (better for local development — supports hot reload)
        return DeploymentArtifact.TYPE_EXPLODED;
    }

    public void addArtifact(@NotNull Artifact artifact) {
        String contextPath = getUniqueContext(generateContextPath(artifact));
        addArtifactWithContext(artifact, contextPath);
    }

    public String generateContextPath(@NotNull Artifact artifact) {
        return ContextPathUtils.generateContextPath(artifact.getName());
    }

    /**
     * Returns the lowercase names of all modules currently in the project.
     * Used to detect orphaned artifacts whose source module was renamed or removed.
     */
    @NotNull
    private Set<String> getActiveModuleNames() {
        try {
            return ReadAction.compute(() -> {
                Set<String> names = new HashSet<>();
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    names.add(module.getName().toLowerCase());
                }
                return names;
            });
        } catch (Exception e) {
            LOG.debug("Error getting active module names", e);
            return new HashSet<>();
        }
    }

    /**
     * Checks whether an artifact's base module name corresponds to a module that
     * currently exists in the project. Returns {@code true} (keep) when:
     * <ul>
     *   <li>The base name is empty (can't determine module — keep to be safe)</li>
     *   <li>The base name matches a current module name</li>
     * </ul>
     * Returns {@code false} (filter out) when the base name resolves to a module
     * that no longer exists — i.e. the artifact is orphaned from a rename/delete.
     */
    private static boolean hasActiveSourceModule(@NotNull String artifactName,
                                                 @NotNull Set<String> activeModuleNames) {
        String baseName = extractBaseModuleName(artifactName).toLowerCase();
        return baseName.isEmpty() || activeModuleNames.contains(baseName);
    }

    @Nullable
    public Artifact findArtifactByName(@NotNull String name) {
        if (artifactManager == null) return null;

        try {
            Artifact[] allArtifacts = ReadAction.compute(artifactManager::getArtifacts);
            for (Artifact artifact : allArtifacts) {
                if (artifact.getName().equals(name)) {
                    return artifact;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error finding artifact by name", e);
        }

        return null;
    }

}
