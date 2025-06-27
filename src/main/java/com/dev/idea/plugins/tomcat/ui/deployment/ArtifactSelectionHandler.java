package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.deployment.dialogs.IntelliJArtifactSelectionDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.packaging.impl.artifacts.JarArtifactType;
import com.intellij.packaging.impl.artifacts.PlainArtifactType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Professional Artifact Selection Handler
 * Manages artifact selection, detection, and addition logic
 *
 * Single Responsibility: Handling artifact selection and detection
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

        System.out.println("DevTomcat: ArtifactSelectionHandler initialized");
    }

    /**
     * Show artifact selection dialog
     */
    public void showArtifactSelectionDialog() {
        try {
            System.out.println("DevTomcat: Opening artifact selection dialog...");

            if (artifactManager == null) {
                showArtifactManagerError();
                return;
            }

            IntelliJArtifactSelectionDialog dialog = new IntelliJArtifactSelectionDialog(project, artifactManager);
            if (dialog.showAndGet()) {
                List<Artifact> selectedArtifacts = dialog.getSelectedArtifacts();
                System.out.println("DevTomcat: User selected " + selectedArtifacts.size() + " artifacts");

                if (!selectedArtifacts.isEmpty()) {
                    for (Artifact artifact : selectedArtifacts) {
                        addArtifact(artifact);
                    }

                    // Refresh table after adding all
                    tableManager.refreshTable();

                    System.out.println("DevTomcat: Added " + selectedArtifacts.size() + " artifacts");
                }
            } else {
                System.out.println("DevTomcat: User cancelled artifact selection");
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error showing artifact dialog: " + e.getMessage());
            e.printStackTrace();
            Messages.showErrorDialog(project,
                    "Error opening artifact selection dialog: " + e.getMessage(),
                    "DevTomcat - Dialog Error");
        }
    }

    /**
     * Show external source dialog
     */
    public void showExternalSourceDialog() {
        // Future enhancement
        Messages.showInfoMessage(project,
                "External Source deployment will be available in future releases.",
                "DevTomcat - External Source");
    }

    /**
     * Add artifact to deployment
     */
    public void addArtifact(@NotNull Artifact artifact) {
        try {
            String artifactName = artifact.getName();

            // Check if already added
            if (tableManager.hasDeployment(artifactName)) {
                System.out.println("DevTomcat: Artifact already in deployment table: " + artifactName);
                return;
            }

            String contextPath = generateContextPath(artifactName, artifact.getArtifactType());
            String outputPath = artifact.getOutputPath();

            DeploymentArtifact deployment = DeploymentArtifact.fromIntellijArtifact(
                    artifact,
                    contextPath
            );

            tableManager.addDeployment(deployment);

            System.out.println("DevTomcat: Added artifact: " + artifactName +
                    " with context: " + contextPath);

        } catch (Exception e) {
            System.err.println("DevTomcat: Error adding artifact: " + e.getMessage());
            e.printStackTrace();
        }
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
            System.out.println("DevTomcat: Analyzing " + allArtifacts.length + " artifacts for web detection");

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

            // Check for web artifact types
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

            // Special handling for JAR files
            if (!isWeb && typeId != null && typeId.toLowerCase().contains("jar")) {
                String artifactName = artifact.getName().toLowerCase();
                isWeb = artifactName.contains("web") ||
                        artifactName.contains("spring-boot") ||
                        artifactName.contains("webapp");
            }

            if (isWeb) {
                System.out.println("DevTomcat: Detected web artifact: " + artifact.getName());
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
    public String generateContextPath(@NotNull String artifactName, @NotNull ArtifactType artifactType) {
        // Clean artifact name
        String cleanName = artifactName
                .replaceAll(":(war|jar)( exploded)?$", "")
                .replaceAll("[^a-zA-Z0-9-]", "")
                .toLowerCase();

        // Handle ROOT context
        if (cleanName.isEmpty() || "root".equals(cleanName)) {
            return "/";
        }

        // Ensure starts with /
        return "/" + cleanName;
    }

    /**
     * Get artifact type display name
     */
    private String getArtifactTypeDisplayName(@NotNull ArtifactType type) {
        if (type instanceof JarArtifactType) {
            return "jar";
        } else if (type.getId().contains("war")) {
            return type.getId().contains("exploded") ? "war exploded" : "war";
        } else if (type instanceof PlainArtifactType) {
            return "directory";
        } else {
            return type.getPresentableName().toLowerCase();
        }
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
     * Show artifact manager error message
     */
    private void showArtifactManagerError() {
        System.err.println("DevTomcat: Cannot show artifact dialog - ArtifactManager is null");
        Messages.showErrorDialog(project,
                "Artifact manager not available. Please ensure the project is properly loaded.",
                "DevTomcat - Artifact Error");
    }
}