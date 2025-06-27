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
import java.util.List;

/**
 * Professional IntelliJ Artifact Selection Dialog
 * Provides a clean interface for selecting project artifacts to deploy
 *
 * Matches IntelliJ Ultimate's artifact selection style
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class IntelliJArtifactSelectionDialog extends DialogWrapper {

    private final Project project;
    private final ArtifactManager artifactManager;
    private JBList<Artifact> artifactsList;
    private DefaultListModel<Artifact> listModel;

    public IntelliJArtifactSelectionDialog(@NotNull Project project,
                                           @NotNull ArtifactManager artifactManager) {
        super(project);
        this.project = project;
        this.artifactManager = artifactManager;

        setTitle("Select Artifacts to Deploy");
        setModal(true);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(500, 350));

        // Description label
        JBLabel descriptionLabel = new JBLabel(
                "Select artifacts from Project Structure to deploy at server startup"
        );
        descriptionLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(descriptionLabel, BorderLayout.NORTH);

        // Create and populate artifacts list
        createArtifactsList();

        JScrollPane scrollPane = new JScrollPane(artifactsList);
        scrollPane.setPreferredSize(new Dimension(480, 250));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Info panel
        panel.add(createInfoPanel(), BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Create artifacts list with custom renderer
     */
    private void createArtifactsList() {
        listModel = new DefaultListModel<>();
        artifactsList = new JBList<>(listModel);
        artifactsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Custom cell renderer
        artifactsList.setCellRenderer(new ArtifactListCellRenderer());

        // Populate list
        populateArtifactsList();
    }

    /**
     * Populate artifacts list from project
     */
    private void populateArtifactsList() {
        try {
            Artifact[] artifacts = artifactManager.getArtifacts();
            System.out.println("DevTomcat: Found " + artifacts.length + " artifacts in project");

            for (Artifact artifact : artifacts) {
                listModel.addElement(artifact);
            }

            // Pre-select first artifact if available
            if (listModel.getSize() > 0) {
                artifactsList.setSelectedIndex(0);
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error populating artifacts: " + e.getMessage());
        }
    }

    /**
     * Create info panel
     */
    private JPanel createInfoPanel() {
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(JBUI.Borders.emptyTop(10));

        JBLabel infoLabel = new JBLabel(
                "<html><i>Artifacts are configured in File | Project Structure | Artifacts</i></html>"
        );
        infoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        infoPanel.add(infoLabel, BorderLayout.WEST);

        return infoPanel;
    }

    /**
     * Get selected artifacts
     */
    public List<Artifact> getSelectedArtifacts() {
        return artifactsList.getSelectedValuesList();
    }

    @Override
    protected void doOKAction() {
        List<Artifact> selected = getSelectedArtifacts();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(
                    getContentPane(),
                    "Please select at least one artifact to deploy.",
                    "No Selection",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        super.doOKAction();
    }

    /**
     * Custom cell renderer for artifacts
     */
    private static class ArtifactListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof Artifact) {
                Artifact artifact = (Artifact) value;
                setText(artifact.getName() + " (" + artifact.getArtifactType().getPresentableName() + ")");
                setIcon(artifact.getArtifactType().getIcon());
            }

            return this;
        }
    }
}