package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.ProjectArtifactDetector;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ModuleDeploymentDialog;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.packaging.elements.CompositePackagingElement;
import com.intellij.packaging.elements.PackagingElementFactory;
import com.intellij.packaging.impl.artifacts.PlainArtifactType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
            for (Artifact artifact : dialog.getSelectedArtifacts()) {
                if (tableManager.hasDeployment(artifact.getName())) {
                    LOG.debug("Skipping duplicate artifact: " + artifact.getName());
                    continue;
                }
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
        Set<String> pomNames = new HashSet<>();
        try {
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                for (com.intellij.openapi.vfs.VirtualFile root :
                        com.intellij.openapi.roots.ModuleRootManager.getInstance(module).getContentRoots()) {
                    com.intellij.openapi.vfs.VirtualFile pomFile = root.findChild("pom.xml");
                    if (pomFile != null && pomFile.exists()) {
                        try {
                            String content = com.intellij.openapi.vfs.VfsUtil.loadText(pomFile);
                            if (content.contains("<packaging>pom</packaging>")) {
                                pomNames.add(module.getName().toLowerCase());
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Error detecting POM modules", e);
        }
        return pomNames;
    }

    public void showExternalSourceDialog() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, true, true, false, false)
                .withTitle("Select External WAR or Directory")
                .withDescription("Select a WAR file or exploded directory to deploy");

        VirtualFile chosen = com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.chooseFile(descriptor, project, null);
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
            List<Artifact> webArtifacts = Stream.of(artifactManager.getArtifacts())
                    .filter(ProjectArtifactDetector::isWebArtifact)
                    .filter(artifact -> !tableManager.hasDeployment(artifact.getName()))
                    .collect(Collectors.toList());

            return preferExplodedVariants(webArtifacts);
        } catch (Exception e) {
            LOG.warn("Error getting selectable artifacts", e);
            return new ArrayList<>();
        }
    }

    /**
     * When both a packaged WAR and an exploded variant exist for the same module,
     * keeps only the exploded one — matching IntelliJ Ultimate's local Tomcat behavior.
     * If only a packaged WAR exists (no exploded counterpart), it is kept.
     *
     * <p>Grouping uses the artifact type to determine exploded vs. packaged,
     * and strips common type suffixes case-insensitively from the name to derive
     * a module identity key. This handles naming conventions across different
     * IntelliJ versions and build tools (e.g. "app:war", "app:war exploded",
     * "app (exploded)", "my-app.war").
     */
    static List<Artifact> preferExplodedVariants(@NotNull List<Artifact> artifacts) {
        if (artifacts.isEmpty()) return artifacts;

        // Group artifacts by base module identity
        java.util.LinkedHashMap<String, List<Artifact>> grouped = new java.util.LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            String key = extractModuleKey(artifact);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(artifact);
        }

        List<Artifact> result = new ArrayList<>();
        for (List<Artifact> group : grouped.values()) {
            if (group.size() <= 1) {
                // Single artifact — no disambiguation needed
                result.addAll(group);
                continue;
            }

            // Multiple variants for the same module: prefer exploded
            List<Artifact> exploded = new ArrayList<>();
            List<Artifact> other = new ArrayList<>();
            for (Artifact a : group) {
                if (isExplodedType(a)) {
                    exploded.add(a);
                } else {
                    other.add(a);
                }
            }
            result.addAll(!exploded.isEmpty() ? exploded : other);
        }
        return result;
    }

    /**
     * Extracts a stable module identity key from an artifact name by stripping
     * common type suffixes. Case-insensitive to handle variations.
     *
     * <p>Examples:
     * <ul>
     *   <li>"webapp-one:war" → "webapp-one"</li>
     *   <li>"webapp-one:war exploded" → "webapp-one"</li>
     *   <li>"myapp:ear exploded" → "myapp"</li>
     *   <li>"myapp.war" → "myapp"</li>
     *   <li>"my-app (exploded)" → "my-app"</li>
     *   <li>"plain-name" → "plain-name" (no suffix to strip)</li>
     * </ul>
     */
    private static String extractModuleKey(@NotNull Artifact artifact) {
        // Delegate to ContextPathUtils which handles type suffixes, version patterns,
        // and Tomcat parallel deployment notation (##version)
        return ContextPathUtils.extractBaseModuleName(artifact.getName());
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
            String typeId = artifact.getArtifactType().getId().toLowerCase();
            String type = typeId.contains("exploded") ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;

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
                    " with context: " + applicationContext);

        } catch (Exception e) {
            LOG.warn("Error adding artifact", e);
        }
    }

    public void addArtifact(@NotNull Artifact artifact) {
        String contextPath = getUniqueContext(generateContextPath(artifact));
        addArtifactWithContext(artifact, contextPath);
    }

    public List<Artifact> detectWebArtifacts() {
        if (artifactManager == null) {
            return new ArrayList<>();
        }

        try {
            return Stream.of(artifactManager.getArtifacts())
                    .filter(ProjectArtifactDetector::isWebArtifact)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warn("Error detecting web artifacts", e);
            return new ArrayList<>();
        }
    }

    public String generateContextPath(@NotNull Artifact artifact) {
        return ContextPathUtils.generateContextPath(artifact.getName());
    }

    @Nullable
    public Artifact findArtifactByName(@NotNull String name) {
        if (artifactManager == null) return null;

        try {
            for (Artifact artifact : artifactManager.getArtifacts()) {
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
