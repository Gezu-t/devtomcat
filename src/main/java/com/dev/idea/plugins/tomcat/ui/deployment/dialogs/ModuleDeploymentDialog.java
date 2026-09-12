package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
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
public class ModuleDeploymentDialog extends ChooseElementsDialog<Deployment> {

    public ModuleDeploymentDialog(@NotNull Project project, @NotNull List<Deployment> items) {
        super(project, items, "Select Modules and Files to Deploy",
                "Auto-detected web modules and build outputs. Select items to deploy at server startup.");
    }

    @Override
    protected Icon getItemIcon(Deployment item) {
        return item.isExploded() ? AllIcons.Nodes.Module : AllIcons.Nodes.Artifact;
    }

    @Override
    protected String getItemText(Deployment item) {
        // Colon notation from the archive the item established (e.g. "app:war exploded");
        // a bare name when it established none.
        return ContextPathUtils.formatArtifactDisplayName(
                item.getDisplayName(), item.getArchive(), item.isExploded());
    }

    public List<Deployment> getSelectedDeployments() {
        return getChosenElements();
    }
}
