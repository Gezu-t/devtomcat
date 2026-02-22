package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.intellij.ide.util.ChooseElementsDialog;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;
import javax.swing.Icon;

public class IntelliJArtifactSelectionDialog extends ChooseElementsDialog<Artifact> {

    private static final Logger LOG = Logger.getInstance(IntelliJArtifactSelectionDialog.class);
    
    public IntelliJArtifactSelectionDialog(@NotNull Project project, @NotNull ArtifactManager artifactManager) {
        super(project, getDeployableArtifacts(artifactManager), "Select Artifacts to Deploy", "Selected artifacts will be deployed at server startup");
        LOG.debug("IntelliJArtifactSelectionDialog initialized as ChooseElementsDialog");
    }

    private static List<Artifact> getDeployableArtifacts(ArtifactManager artifactManager) {
        Artifact[] allArtifacts = artifactManager.getArtifacts();
        return new ArrayList<>(java.util.Arrays.asList(allArtifacts));
    }

    @Override
    protected Icon getItemIcon(Artifact item) {
        return item.getArtifactType().getIcon();
    }

    @Override
    protected String getItemText(Artifact item) {
        return item.getName();
    }
    
    public List<Artifact> getSelectedArtifacts() {
        return getChosenElements();
    }
}