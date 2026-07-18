package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentRow;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.utils.ArtifactPackagingDetector;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.MavenModelProvider;
import com.dev.idea.plugins.tomcat.utils.ProjectArtifactDetector;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ModuleDeploymentDialog;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
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
        // The artifact scan does blocking filesystem I/O (raw File.listFiles over
        // build/target/out dirs for every module, plus per-module pom.xml reads),
        // so it must not run on the EDT. Snapshot the existing deployment names on
        // the EDT first — the table manager backs a Swing model and must not be
        // read from a pooled thread — then run the detection off the EDT under a
        // cancelable modal progress, and finally show the dialog back on the EDT.
        Set<String> existingNames = tableManager.getRows().stream()
                .map(DeploymentRow::getDisplayName)
                .collect(Collectors.toSet());

        DetectionResult result;
        try {
            result = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    (com.intellij.openapi.util.ThrowableComputable<DetectionResult, RuntimeException>) () -> {
                        List<Artifact> artifacts = getSelectableArtifacts(existingNames);
                        if (!artifacts.isEmpty()) {
                            return new DetectionResult(artifacts, null);
                        }
                        return new DetectionResult(null, detectDeployables());
                    },
                    "Detecting Deployable Artifacts", true, project);
        } catch (ProcessCanceledException pce) {
            // User canceled the scan — nothing to show.
            return;
        } catch (Exception e) {
            LOG.warn("Error showing artifact dialog", e);
            Messages.showErrorDialog(project,
                    "Error selecting artifacts: " + e.getMessage(),
                    "Selection Error"
            );
            return;
        }

        // Back on the EDT (runProcessWithProgressSynchronously returns here).
        if (result.artifacts() != null && !result.artifacts().isEmpty()) {
            // 1. IntelliJ-configured artifacts
            showIntelliJArtifactDialog(result.artifacts());
        } else if (result.detected() != null && !result.detected().isEmpty()) {
            // 2. Auto-detected web modules and build outputs
            showAutoDetectedDialog(result.detected());
        } else {
            // 3. Nothing found — show helpful message
            Messages.showWarningDialog(project,
                    "No deployable artifacts found.\n\n" +
                            "Options:\n" +
                            "  - Use '+' → 'External Source...' to select a WAR file or directory\n" +
                            "  - Configure artifacts in File → Project Structure → Artifacts\n" +
                            "  - Add a 'war' plugin to your build.gradle / pom.xml and rebuild",
                    "No Artifacts Available"
            );
        }
    }

    /**
     * Result of the off-EDT detection scan: either the IntelliJ-configured
     * artifacts (preferred) or the auto-detected fallback deployments.
     */
    private record DetectionResult(@Nullable List<Artifact> artifacts,
                                   @Nullable List<Deployment> detected) {
    }

    private void showIntelliJArtifactDialog(@NotNull List<Artifact> artifacts) {
        IntelliJArtifactSelectionDialog dialog = new IntelliJArtifactSelectionDialog(project, artifacts);

        if (dialog.showAndGet()) {
            Set<String> existingBaseNames = tableManager.getRows().stream()
                    .map(d -> extractBaseModuleName(d.getDisplayName()))
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

    private void showAutoDetectedDialog(@NotNull List<Deployment> detected) {
        ModuleDeploymentDialog dialog = new ModuleDeploymentDialog(project, detected);

        if (dialog.showAndGet()) {
            Set<String> existingBaseNames = tableManager.getRows().stream()
                    .map(d -> extractBaseModuleName(d.getDisplayName()))
                    .collect(Collectors.toSet());

            for (Deployment deployment : dialog.getSelectedDeployments()) {
                String baseName = extractBaseModuleName(deployment.getDisplayName());
                if (existingBaseNames.contains(baseName)) {
                    LOG.debug("Skipping duplicate deployment (base name match): " + deployment.getDisplayName());
                    continue;
                }
                existingBaseNames.add(baseName);
                // Provenance is structural now: ModuleBackedDeployment vs
                // ExternalFileDeployment already says module-backed vs disk-scanned WAR.
                String context = getUniqueContext(deployment.getContextPath());
                DeploymentRow row = DeploymentRow.of(deployment);
                row.setContextPath(context);

                tableManager.addAndSelectDeployment(row);
                LOG.info("Added auto-detected deployment: " + deployment.getDisplayName() +
                        " [" + (deployment.isExploded() ? "exploded" : "war") +
                        "] kind=" + deployment.getKind() +
                        " context=" + context);
            }
        }
    }

    /**
     * Auto-detects deployable web modules and WAR build outputs in the project.
     * Delegates to {@link ProjectArtifactDetector} and filters out artifacts already
     * present in the deployment table.
     */
    private List<Deployment> detectDeployables() {
        List<Deployment> modules = new ArrayList<>(ProjectArtifactDetector.detectWebModules(project));
        LOG.info("Auto-detection: " + modules.size() + " web module(s) found");
        for (Deployment m : modules) {
            LOG.info("  Web module: " + m.getDisplayName() +
                    " [" + (m.isExploded() ? "exploded" : "war") + "] path=" + m.getResolvedPath());
        }

        List<Deployment> wars = new ArrayList<>(ProjectArtifactDetector.scanForWarFiles(project));
        LOG.info("Auto-detection: " + wars.size() + " WAR file(s)/exploded dir(s) found");
        for (Deployment w : wars) {
            LOG.info("  WAR/Exploded: " + w.getDisplayName() +
                    " [" + (w.isExploded() ? "exploded" : "war") + "] path=" + w.getResolvedPath());
        }

        // Combine with modules first (higher quality), then deduplicate by name
        List<Deployment> combined = new ArrayList<>(modules);
        combined.addAll(wars);
        combined = deduplicateByName(combined);

        // Filter out POM-packaged parent modules that leak through WAR scans
        Set<String> pomModuleNames = detectPomModuleNames();
        if (!pomModuleNames.isEmpty()) {
            combined.removeIf(item -> pomModuleNames.contains(extractBaseModuleName(item.getDisplayName())));
        }

        // Filter out stale artifacts from renamed/removed modules.
        // The out/artifacts/ directory and IntelliJ's ArtifactManager can retain entries
        // for modules that no longer exist after a rename. Without this filter, the user
        // sees old module names in the selection dialog alongside current ones.
        Set<String> activeModules = getActiveModuleNames();
        int beforeFilter = combined.size();
        combined.removeIf(item -> !hasActiveSourceModule(item.getDisplayName(), activeModules));
        int filtered = beforeFilter - combined.size();
        if (filtered > 0) {
            LOG.info("Auto-detection: filtered " + filtered +
                    " stale artifact(s) from renamed/removed modules");
        }

        LOG.info("Auto-detection: " + combined.size() + " deployable item(s) available");
        return combined;
    }

    /**
     * Deduplicates deployments by base module name + packaging (case-insensitive).
     * Strips common suffixes like {@code _war_exploded}, {@code _war}, {@code .war}
     * to recognize e.g. "webapp-one" and "webapp-one_war_exploded" as the same module.
     * Only merges items of the same packaging (exploded with exploded, WAR with WAR).
     * When duplicates exist, prefers the variant with a build output path.
     */
    private static List<Deployment> deduplicateByName(List<Deployment> deployments) {
        LinkedHashMap<String, Deployment> unique = new LinkedHashMap<>();
        for (Deployment item : deployments) {
            String key = extractBaseModuleName(item.getDisplayName()) + "|" + item.isExploded();
            Deployment existing = unique.get(key);
            if (existing == null) {
                unique.put(key, item);
            } else {
                // Prefer the variant whose path points to build output (out/artifacts, target)
                // over a source directory (src/main/webapp)
                if (isBuildOutputPath(item.getResolvedPath()) && !isBuildOutputPath(existing.getResolvedPath())) {
                    unique.put(key, item);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String extractBaseModuleName(String name) {
        return ContextPathUtils.extractBaseModuleName(name);
    }

    private static boolean isBuildOutputPath(@Nullable Path path) {
        if (path == null) return false;
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
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
            return TomcatReadActions.compute(() -> {
                Set<String> pomNames = new HashSet<>();
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    if (isPomPackagedModule(module)) {
                        pomNames.add(module.getName().toLowerCase(Locale.ROOT));
                    }
                }
                return pomNames;
            });
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.debug("Error detecting POM modules", e);
            return new HashSet<>();
        }
    }

    /**
     * Whether {@code module} is a Maven aggregator/parent (pom packaging) and so
     * never itself deployable. Primary signal is the resolved effective Maven
     * packaging (typed, build-agnostic, and sees packaging inherited from a parent
     * POM or driven by a {@code ${property}} that a raw text scan misses); when the
     * model resolves to any concrete packaging it is authoritative. Only for a
     * project the IDE has not imported yet — where the resolved packaging is
     * {@code null} — does it fall back to scanning the raw {@code pom.xml} text.
     * ({@code "pom"} is the bare resolved value, distinct from the XML fragment
     * {@link TomcatConstants#POM_PACKAGING_POM}.)
     */
    private boolean isPomPackagedModule(@NotNull Module module) {
        String packaging = MavenModelProvider.packaging(module);
        if (packaging != null) {
            return "pom".equalsIgnoreCase(packaging);
        }
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
            VirtualFile pomFile = root.findChild(TomcatConstants.MAVEN_BUILD_FILE);
            if (pomFile != null && pomFile.exists()) {
                try {
                    if (VfsUtil.loadText(pomFile).contains(TomcatConstants.POM_PACKAGING_POM)) {
                        return true;
                    }
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Error reading pom.xml for module '" + module.getName() +
                            "' at " + pomFile.getPath(), e);
                }
            }
        }
        return false;
    }

    public void showExternalSourceDialog() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, true, true, false, false)
                .withTitle("Select External WAR or Directory")
                .withDescription("Select a WAR file or exploded directory to deploy");

        VirtualFile chosen = SafeBrowseUtil.chooseFile(descriptor, project, null);
        if (chosen == null) {
            return;
        }

        String localPath = chosen.getPath();

        // Duplicate-path guard: external sources don't have a stable "name" from the
        // project model — the file path IS the identity. Picking the same file twice
        // would add two rows deploying the same bytes to different context paths,
        // which is almost never what the user wants. Refuse silently with an info
        // dialog so the user doesn't get a mysterious second row.
        Path chosenPath = Path.of(localPath);
        for (DeploymentRow existing : tableManager.getRows()) {
            if (existing != null && chosenPath.equals(existing.getResolvedPath())) {
                Messages.showInfoMessage(project,
                        "This file or directory is already in the deployment list as '"
                                + existing.getDisplayName() + "' at context '"
                                + existing.getContextPath() + "'.\n\n"
                                + "Remove the existing entry first if you want to re-add it.",
                        "Already Added");
                LOG.debug("Refused duplicate external-source add: " + localPath);
                return;
            }
        }

        String name = chosen.getName();
        String context = getUniqueContext(ContextPathUtils.generateContextPath(name));
        // ExternalFileDeployment carries EXTERNAL provenance structurally — the
        // validator never flags it as orphaned and the rename refresher skips it.
        ExternalFileDeployment deployment =
                new ExternalFileDeployment(chosenPath, context, chosen.isDirectory());

        tableManager.addAndSelectDeployment(DeploymentRow.of(deployment));
        LOG.debug("Added external source: " + name + " at " + localPath);
    }

    /**
     * @param existingDeploymentNames names already present in the deployment
     *        table, snapshotted on the EDT before this runs on a pooled thread
     *        (the table manager backs a Swing model and must not be read off-EDT).
     */
    private List<Artifact> getSelectableArtifacts(@NotNull Set<String> existingDeploymentNames) {
        if (artifactManager == null) {
            return new ArrayList<>();
        }

        try {
            Set<String> activeModules = getActiveModuleNames();

            // artifactManager.getArtifacts() accesses the project model —
            // snapshot the names+refs under a read action, then filter outside.
            List<Artifact> allPlatformArtifacts = TomcatReadActions.compute(
                    () -> List.of(artifactManager.getArtifacts()));

            // Show ALL IntelliJ artifacts (not just web-typed), because Community Edition
            // only has PlainArtifactType (ID: "plain") and JarArtifactType (ID: "jar") —
            // neither passes isWebArtifact(). Users must be able to select any artifact.
            // Sort web artifacts first (exploded → WAR), then others alphabetically.
            List<Artifact> filtered = allPlatformArtifacts.stream()
                    .filter(artifact -> !existingDeploymentNames.contains(artifact.getName()))
                    .filter(artifact -> hasActiveSourceModule(artifact.getName(), activeModules))
                    .collect(Collectors.toList());

            return sortByTypeCategory(filtered);
        } catch (ProcessCanceledException pce) {
            throw pce;
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
        String name = artifact.getName().toLowerCase(Locale.ROOT);
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
                if (typeId != null && typeId.toLowerCase(Locale.ROOT).contains("exploded")) {
                    return true;
                }
                String typeName = type.getPresentableName();
                if (typeName != null && typeName.toLowerCase(Locale.ROOT).contains("exploded")) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.debug("Error checking artifact type for: " + artifact.getName(), e);
        }
        // Fallback: check name pattern
        String name = artifact.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains("exploded");
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
        return tableManager.getRows().stream()
                .anyMatch(d -> d.getContextPath().equals(context));
    }

    private void addArtifactWithContext(@NotNull Artifact artifact, @NotNull String applicationContext) {
        try {
            boolean exploded = ArtifactPackagingDetector.resolveExplodedPackaging(artifact);

            // Output path / packaging read off the platform Artifact now double as
            // the typed deployment's last-known fallbacks for unresolved pointers.
            ArtifactBackedDeployment deployment = ArtifactBackedDeployment.ofName(
                    project,
                    artifact.getName(),
                    applicationContext,
                    artifact.getOutputFilePath(),
                    exploded);

            tableManager.addAndSelectDeployment(DeploymentRow.of(deployment));

            LOG.debug("Added artifact: " + artifact.getName() +
                    " [" + (exploded ? "exploded" : "war") + "] with context: " + applicationContext);

        } catch (Exception e) {
            LOG.warn("Error adding artifact", e);
        }
    }

    private String generateContextPath(@NotNull Artifact artifact) {
        return ContextPathUtils.generateContextPath(artifact.getName());
    }

    /**
     * Returns the lowercase names of all modules currently in the project.
     * Used to detect orphaned artifacts whose source module was renamed or removed.
     */
    @NotNull
    private Set<String> getActiveModuleNames() {
        try {
            return TomcatReadActions.compute(() -> {
                Set<String> names = new HashSet<>();
                for (Module module : ModuleManager.getInstance(project).getModules()) {
                    names.add(module.getName().toLowerCase(Locale.ROOT));
                }
                return names;
            });
        } catch (ProcessCanceledException pce) {
            throw pce;
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
        String baseName = extractBaseModuleName(artifactName).toLowerCase(Locale.ROOT);
        return baseName.isEmpty() || activeModuleNames.contains(baseName);
    }

}
