package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.utils.TomcatModuleUtils;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.ModuleDeploymentDialog;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.packaging.impl.artifacts.JarArtifactType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            LOG.warn("Error showing artifact dialog: " + e.getMessage());
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
            for (DeploymentArtifact deployment : dialog.getSelectedDeployments()) {
                if (tableManager.hasDeployment(deployment.getName())) {
                    LOG.debug("Skipping duplicate deployment: " + deployment.getName());
                    continue;
                }
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
     * Combines results from module scanning and build output scanning.
     */
    private List<DeploymentArtifact> detectDeployables() {
        List<DeploymentArtifact> combined = new ArrayList<>();
        combined.addAll(detectDeployableModules());
        combined.addAll(scanForWarFiles());

        // Deduplicate by type+path so project-level and module-level scans don't show the same WAR twice.
        Map<String, DeploymentArtifact> unique = new LinkedHashMap<>();
        for (DeploymentArtifact item : combined) {
            if (item == null) continue;
            String key = (item.getType() + "|" + item.getPath()).toLowerCase();
            unique.putIfAbsent(key, item);
        }

        List<DeploymentArtifact> results = new ArrayList<>(unique.values());
        LOG.info("Auto-detection found " + results.size() + " unique deployable items");
        return results;
    }

    /**
     * Detects web modules in the project using TomcatModuleUtils.
     * Creates exploded deployment artifacts from web root directories.
     */
    private List<DeploymentArtifact> detectDeployableModules() {
        List<DeploymentArtifact> results = new ArrayList<>();

        try {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            for (Module module : modules) {
                if (!TomcatModuleUtils.isWebModule(module)) continue;
                if (tableManager.hasDeployment(module.getName())) continue;

                List<VirtualFile> webRoots = TomcatModuleUtils.findWebRoots(module);
                if (webRoots.isEmpty()) {
                    // Use module content root as fallback
                    VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
                    if (contentRoots.length > 0) {
                        String context = TomcatModuleUtils.extractContextPath(module);
                        DeploymentArtifact deployment = new DeploymentArtifact(
                                module.getName(),
                                contentRoots[0].getPath(),
                                DeploymentArtifact.TYPE_EXPLODED
                        );
                        deployment.setContextPath(context);
                        results.add(deployment);
                        LOG.debug("Detected web module (content root): " + module.getName());
                    }
                } else {
                    for (VirtualFile webRoot : webRoots) {
                        String name = module.getName();
                        // Disambiguate if module has multiple web roots
                        if (webRoots.size() > 1) {
                            name = module.getName() + " (" + webRoot.getName() + ")";
                        }
                        String context = TomcatModuleUtils.extractContextPath(module);
                        DeploymentArtifact deployment = new DeploymentArtifact(
                                name,
                                webRoot.getPath(),
                                DeploymentArtifact.TYPE_EXPLODED
                        );
                        deployment.setContextPath(context);
                        results.add(deployment);
                        LOG.debug("Detected web module: " + name + " at " + webRoot.getPath());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error detecting web modules: " + e.getMessage());
        }

        return results;
    }

    /**
     * Scans common build output directories for WAR files.
     * Checks Gradle (build/libs) and Maven (target) output paths.
     */
    private List<DeploymentArtifact> scanForWarFiles() {
        List<DeploymentArtifact> results = new ArrayList<>();
        String basePath = project.getBasePath();
        if (basePath == null) return results;

        // Scan project-level build output
        scanWarDirectory(new File(basePath, "build/libs"), results);
        scanWarDirectory(new File(basePath, "target"), results);

        // Scan each module's build output
        try {
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
                for (VirtualFile root : contentRoots) {
                    scanWarDirectory(new File(root.getPath(), "build/libs"), results);
                    scanWarDirectory(new File(root.getPath(), "target"), results);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error scanning module build outputs: " + e.getMessage());
        }

        return results;
    }

    private void scanWarDirectory(@NotNull File dir, @NotNull List<DeploymentArtifact> results) {
        if (!dir.isDirectory()) return;

        File[] warFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".war"));
        if (warFiles == null) return;

        for (File war : warFiles) {
            if (tableManager.hasDeployment(war.getName())) continue;

            String context = ContextPathUtils.generateContextPath(war.getName());
            DeploymentArtifact deployment = new DeploymentArtifact(
                    war.getName(),
                    war.getAbsolutePath(),
                    DeploymentArtifact.TYPE_WAR
            );
            deployment.setContextPath(context);
            results.add(deployment);
            LOG.debug("Detected WAR file: " + war.getName() + " at " + war.getAbsolutePath());
        }
    }

    public void showExternalSourceDialog() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Select External WAR or Directory");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".war");
            }

            @Override
            public String getDescription() {
                return "WAR files or directories";
            }
        });

        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            File chosen = chooser.getSelectedFile();
            if (chosen == null) {
                return;
            }

            String name = chosen.getName();
            String type = chosen.isDirectory() ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;
            String localPath = chosen.getAbsolutePath();

            String context = getUniqueContext(ContextPathUtils.generateContextPath(name));
            DeploymentArtifact deployment = new DeploymentArtifact(name, localPath, type);
            deployment.setContextPath(context);

            tableManager.addAndSelectDeployment(deployment);
            LOG.debug("Added external source: " + name);
        }
    }

    private List<Artifact> getSelectableArtifacts() {
        if (artifactManager == null) {
            return new ArrayList<>();
        }

        return Stream.of(artifactManager.getArtifacts())
                .filter(this::isWebArtifact)
                .filter(artifact -> !tableManager.hasDeployment(artifact.getName()))
                .collect(Collectors.toList());
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
            LOG.warn("Error adding artifact: " + e.getMessage());
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
            Artifact[] allArtifacts = artifactManager.getArtifacts();
            return Stream.of(allArtifacts)
                    .filter(this::isWebArtifact)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.warn("Error detecting web artifacts: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean isWebArtifact(@NotNull Artifact artifact) {
        try {
            ArtifactType type = artifact.getArtifactType();
            if (type == null) return false;

            String typeId = type.getId();
            String typeName = type.getPresentableName();

            boolean isWeb = typeId != null && (
                    typeId.toLowerCase().contains("war") ||
                            typeId.toLowerCase().contains("web") ||
                            typeId.equals("exploded-war") ||
                            typeId.contains("web-application")
            );

            if (!isWeb && typeName != null) {
                isWeb = typeName.toLowerCase().contains("web application") ||
                        typeName.toLowerCase().contains("war");
            }

            if (!isWeb && type instanceof JarArtifactType) {
                String artifactName = artifact.getName().toLowerCase();
                isWeb = artifactName.contains("web") ||
                        artifactName.contains("spring-boot") ||
                        artifactName.contains("webapp");
            }

            return isWeb;

        } catch (Exception e) {
            LOG.warn("Error checking web artifact: " + e.getMessage());
            return false;
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
            LOG.warn("Error finding artifact by name: " + e.getMessage());
        }

        return null;
    }

    private void showArtifactManagerError() {
        Messages.showErrorDialog(project,
                "Artifact manager is not available.\n" +
                        "Please ensure the project is properly loaded and try again.",
                "Artifact Manager Error"
        );
    }
}
