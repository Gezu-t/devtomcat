package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class IntelliJArtifactSelectionDialog extends DialogWrapper {
    private final Project project;
    private final ArtifactManager artifactManager;
    private JBList<Artifact> artifactList;
    private final List<Artifact> selectedArtifacts = new ArrayList<>();

    public IntelliJArtifactSelectionDialog(@NotNull Project project, @NotNull ArtifactManager artifactManager) {
        super(project);
        this.project = project;
        this.artifactManager = artifactManager;
        setTitle("Select Artifacts to Deploy");
        setModal(true);
        System.out.println("DevTomcat: IntelliJArtifactSelectionDialog created");
        init();
        System.out.println("DevTomcat: IntelliJArtifactSelectionDialog initialized");
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(400, 300));

        // Description label at top - matching IntelliJ style
        JBLabel descriptionLabel = new JBLabel("Selected artifacts will be deployed at server startup");
        descriptionLabel.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        mainPanel.add(descriptionLabel, BorderLayout.NORTH);

        // Get all artifacts
        List<Artifact> artifacts = new ArrayList<>();
        Artifact[] allArtifacts = artifactManager.getArtifacts();
        System.out.println("DevTomcat: Total artifacts in project: " + allArtifacts.length);

        for (Artifact artifact : allArtifacts) {
            String typeId = artifact.getArtifactType().getId().toLowerCase();
            System.out.println("DevTomcat: Checking artifact '" + artifact.getName() + "' with type: " + typeId);
            // Show all WAR and exploded WAR artifacts
            if (typeId.contains("war") || typeId.contains("ear") || typeId.contains("web") ||
                typeId.contains("exploded")) {
                artifacts.add(artifact);
                System.out.println("DevTomcat: Added artifact: " + artifact.getName());
            }
        }

        System.out.println("DevTomcat: Found " + artifacts.size() + " deployable artifacts");

        // Create list with simple artifact names (matching IntelliJ official style)
        artifactList = new JBList<>(artifacts);
        artifactList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Simple cell renderer - just show artifact name with icon
        artifactList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                         boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Artifact) {
                    Artifact artifact = (Artifact) value;
                    setText(artifact.getName());
                    setIcon(artifact.getArtifactType().getIcon());
                }
                return this;
            }
        });

        JScrollPane scrollPane = new JScrollPane(artifactList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Show message if no artifacts found
        if (artifacts.isEmpty()) {
            JBLabel noArtifactsLabel = new JBLabel("No deployable artifacts found in project");
            noArtifactsLabel.setHorizontalAlignment(SwingConstants.CENTER);
            noArtifactsLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            mainPanel.add(noArtifactsLabel, BorderLayout.CENTER);
        }

        return mainPanel;
    }

    @Override
    protected void doOKAction() {
        selectedArtifacts.clear();
        selectedArtifacts.addAll(artifactList.getSelectedValuesList());
        super.doOKAction();
    }

    public List<Artifact> getSelectedArtifacts() {
        return new ArrayList<>(selectedArtifacts);
    }
}