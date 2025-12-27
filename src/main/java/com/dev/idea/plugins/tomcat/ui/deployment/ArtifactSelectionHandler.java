package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.packaging.impl.artifacts.JarArtifactType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Professional Artifact Selection Handler - Corrected Version
 * Handles artifact selection, context generation, and deployment creation
 *
 * Works with existing DeploymentArtifact model structure
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class ArtifactSelectionHandler {

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

    /**
     * Show artifact selection dialog with context input
     */
    public void showArtifactSelectionDialog() {
        try {
            if (artifactManager == null) {
                showArtifactManagerError();
                return;
            }

            // Get available artifacts
            List<Artifact> availableArtifacts = getSelectableArtifacts();
            if (availableArtifacts.isEmpty()) {
                Messages.showWarningDialog(project,
                        "No deployable artifacts found in the project.\n" +
                                "Please create a WAR or Web artifact in Project Structure.",
                        "No Artifacts Available"
                );
                return;
            }

            // Show artifact selection dialog
            IntelliJArtifactSelectionDialog dialog = new IntelliJArtifactSelectionDialog(
                    project, artifactManager
            );

            if (dialog.showAndGet()) {
                List<Artifact> selectedArtifacts = dialog.getSelectedArtifacts();

                for (Artifact artifact : selectedArtifacts) {
                    // Check if already added
                    if (tableManager.hasDeployment(artifact.getName())) {
                        Messages.showWarningDialog(project,
                                "Artifact '" + artifact.getName() + "' is already deployed.",
                                "Duplicate Artifact"
                        );
                        continue;
                    }

                    // Generate context path
                    String suggestedContext = generateContextPath(artifact);

                    // Show context dialog
                    ArtifactContextDialog contextDialog = new ArtifactContextDialog(
                            project,
                            artifact.getName(),
                            suggestedContext
                    );

                    if (contextDialog.showAndGet()) {
                        String applicationContext = contextDialog.getApplicationContext();

                        // Check for duplicate context
                        if (isContextInUse(applicationContext)) {
                            Messages.showErrorDialog(project,
                                    "Context path '" + applicationContext + "' is already in use.",
                                    "Duplicate Context Path"
                            );
                            continue;
                        }

                        addArtifactWithContext(artifact, applicationContext);
                    }
                }

                tableManager.refreshTable();
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error showing artifact dialog: " + e.getMessage());
            e.printStackTrace();
            Messages.showErrorDialog(project,
                    "Error selecting artifacts: " + e.getMessage(),
                    "Selection Error"
            );
        }
    }

    /**
     * Show external source dialog
     */
    public void showExternalSourceDialog() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(
                true,  // files
                true,  // directories
                true,  // jar files
                false, // jar contents
                false, // recursive
                false  // multiple
        );

        descriptor.setTitle("Select External WAR or Directory");
        descriptor.setDescription("Select a WAR file or exploded web application directory");

        // Set file filter for WAR files
        descriptor.withFileFilter(file -> {
            String name = file.getName().toLowerCase();
            return file.isDirectory() || name.endsWith(".war");
        });

        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen != null) {
            String name = chosen.getName();
            String type = chosen.isDirectory() ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;
            String localPath = chosen.getPath();

            // Generate context from file name
            String suggestedContext = generateContextFromFileName(name);

            // Show context dialog
            ArtifactContextDialog contextDialog = new ArtifactContextDialog(
                    project,
                    name + " (External)",
                    suggestedContext
            );

            if (contextDialog.showAndGet()) {
                String applicationContext = contextDialog.getApplicationContext();

                // Check for duplicate context
                if (isContextInUse(applicationContext)) {
                    Messages.showErrorDialog(project,
                            "Context path '" + applicationContext + "' is already in use.",
                            "Duplicate Context Path"
                    );
                    return;
                }

                // Create external deployment
                DeploymentArtifact deployment = new DeploymentArtifact(name, localPath, type);
                deployment.setContextPath(applicationContext);

                tableManager.addDeployment(deployment);
                System.out.println("DevTomcat: Added external source: " + name);
            }
        }
    }

    /**
     * Get selectable artifacts (not already deployed)
     */
    private List<Artifact> getSelectableArtifacts() {
        if (artifactManager == null) {
            return new ArrayList<>();
        }

        return Stream.of(artifactManager.getArtifacts())
                .filter(artifact -> !tableManager.hasDeployment(artifact.getName()))
                .collect(Collectors.toList());
    }

    /**
     * Check if context is already in use
     */
    private boolean isContextInUse(String context) {
        return tableManager.getDeployments().stream()
                .anyMatch(d -> d.getApplicationContext().equals(context));
    }

    /**
     * Add artifact with specific application context
     */
    private void addArtifactWithContext(@NotNull Artifact artifact, @NotNull String applicationContext) {
        try {
            // Determine artifact type
            String typeId = artifact.getArtifactType().getId().toLowerCase();
            String type = typeId.contains("exploded") ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;

            // Get artifact output path
            String outputPath = artifact.getOutputFilePath();
            if (outputPath == null) {
                outputPath = "";
            }

            // Create deployment artifact
            DeploymentArtifact deployment = new DeploymentArtifact(
                    artifact.getName(),
                    outputPath,
                    type
            );
            deployment.setContextPath(applicationContext);

            // Set server path same as application context if using default
            if (deployment.isUsingDefaultContext()) {
                deployment.setServerPath(applicationContext);
            }

            tableManager.addDeployment(deployment);

            System.out.println("DevTomcat: Added artifact: " + artifact.getName() +
                    " with context: " + applicationContext);

        } catch (Exception e) {
            System.err.println("DevTomcat: Error adding artifact: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Add artifact with auto-generated context (for auto-detection)
     */
    public void addArtifact(@NotNull Artifact artifact) {
        String contextPath = generateContextPath(artifact);

        // Check if context is already in use
        int counter = 1;
        String originalContext = contextPath;
        while (isContextInUse(contextPath)) {
            contextPath = originalContext + "-" + counter++;
        }

        addArtifactWithContext(artifact, contextPath);
    }

    /**
     * Detect web artifacts in project
     */
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
            System.err.println("DevTomcat: Error detecting web artifacts: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Check if artifact is web deployable
     */
    private boolean isWebArtifact(@NotNull Artifact artifact) {
        try {
            ArtifactType type = artifact.getArtifactType();
            if (type == null) return false;

            String typeId = type.getId();
            String typeName = type.getPresentableName();

            // Check type ID
            boolean isWeb = typeId != null && (
                    typeId.toLowerCase().contains("war") ||
                            typeId.toLowerCase().contains("web") ||
                            typeId.equals("exploded-war") ||
                            typeId.contains("web-application")
            );

            // Check type name
            if (!isWeb && typeName != null) {
                isWeb = typeName.toLowerCase().contains("web application") ||
                        typeName.toLowerCase().contains("war");
            }

            // Check artifact name for JAR artifacts that might be web apps
            if (!isWeb && type instanceof JarArtifactType) {
                String artifactName = artifact.getName().toLowerCase();
                isWeb = artifactName.contains("web") ||
                        artifactName.contains("spring-boot") ||
                        artifactName.contains("webapp");
            }

            return isWeb;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error checking web artifact: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate context path from artifact
     */
    public String generateContextPath(@NotNull Artifact artifact) {
        return generateContextFromFileName(artifact.getName());
    }

    /**
     * Generate context path from file/artifact name
     */
    private String generateContextFromFileName(@NotNull String fileName) {
        // Remove common suffixes and extensions
        String context = fileName
                .replaceAll(":(war|jar)(\\s+exploded)?$", "")
                .replaceAll("\\.(war|jar)$", "")
                .replaceAll("[-_]?exploded$", "");

        // Special cases
        if (context.equalsIgnoreCase("ROOT") ||
                context.equalsIgnoreCase("root.war")) {
            return "/";
        }

        // Handle version numbers (e.g., myapp-1.0.0 -> myapp)
        context = context.replaceAll("-\\d+(\\.\\d+)*(-SNAPSHOT)?$", "");

        // Convert to URL-safe format
        context = context
                .replaceAll("[^a-zA-Z0-9\\-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();

        // Empty or just dashes -> root
        if (context.isEmpty() || context.matches("-+")) {
            return "/";
        }

        return "/" + context;
    }

    /**
     * Find artifact by name
     */
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
            System.err.println("DevTomcat: Error finding artifact by name: " + e.getMessage());
        }

        return null;
    }

    /**
     * Show artifact manager error
     */
    private void showArtifactManagerError() {
        Messages.showErrorDialog(project,
                "Artifact manager is not available.\n" +
                        "Please ensure the project is properly loaded and try again.",
                "Artifact Manager Error"
        );
    }
}