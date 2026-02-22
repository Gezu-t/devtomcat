package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.ChooseElementsDialog;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

/**
 * Dialog for selecting auto-detected deployable modules and WAR files.
 * Shown as a fallback when no IntelliJ-configured artifacts are available.
 */
public class ModuleDeploymentDialog extends ChooseElementsDialog<DeploymentArtifact> {

    public ModuleDeploymentDialog(@NotNull Project project, @NotNull List<DeploymentArtifact> items) {
        super(project, items, "Select Deployable Module",
                "Auto-detected web modules and build outputs. Select items to deploy at server startup.");
    }

    @Override
    protected Icon getItemIcon(DeploymentArtifact item) {
        return switch (item.getType()) {
            case DeploymentArtifact.TYPE_WAR -> AllIcons.Nodes.Artifact;
            case DeploymentArtifact.TYPE_EXPLODED -> AllIcons.Nodes.Module;
            default -> AllIcons.Nodes.Folder;
        };
    }

    @Override
    protected String getItemText(DeploymentArtifact item) {
        String typeLabel = switch (item.getType()) {
            case DeploymentArtifact.TYPE_WAR -> "WAR";
            case DeploymentArtifact.TYPE_EXPLODED -> "Exploded";
            default -> item.getType();
        };
        return item.getDisplayName() + "  [" + typeLabel + "]";
    }

    public List<DeploymentArtifact> getSelectedDeployments() {
        return getChosenElements();
    }
}
