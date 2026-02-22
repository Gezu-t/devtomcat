package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
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
            if (artifactManager == null) {
                showArtifactManagerError();
                return;
            }

            List<Artifact> availableArtifacts = getSelectableArtifacts();
            if (availableArtifacts.isEmpty()) {
                Messages.showWarningDialog(project,
                        "No deployable artifacts found in the project.\n" +
                                "Please create a WAR or Web artifact in Project Structure.",
                        "No Artifacts Available"
                );
                return;
            }

            IntelliJArtifactSelectionDialog dialog = new IntelliJArtifactSelectionDialog(
                    project, artifactManager
            );

            if (dialog.showAndGet()) {
                List<Artifact> selectedArtifacts = dialog.getSelectedArtifacts();

                for (Artifact artifact : selectedArtifacts) {
                    if (tableManager.hasDeployment(artifact.getName())) {
                        Messages.showWarningDialog(project,
                                "Artifact '" + artifact.getName() + "' is already deployed.",
                                "Duplicate Artifact"
                        );
                        continue;
                    }

                    String context = getUniqueContext(generateContextPath(artifact));
                    
                    addArtifactWithContext(artifact, context);
                }
            }

        } catch (Exception e) {
            LOG.warn("Error showing artifact dialog: " + e.getMessage());
            Messages.showErrorDialog(project,
                    "Error selecting artifacts: " + e.getMessage(),
                    "Selection Error"
            );
        }
    }

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

        descriptor.withFileFilter(file -> {
            String name = file.getName().toLowerCase();
            return file.isDirectory() || name.endsWith(".war");
        });

        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen != null) {
            String name = chosen.getName();
            String type = chosen.isDirectory() ? DeploymentArtifact.TYPE_EXPLODED : DeploymentArtifact.TYPE_WAR;
            String localPath = chosen.getPath();

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

            if (deployment.isUsingDefaultContext()) {
                deployment.setServerPath(applicationContext);
            }

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