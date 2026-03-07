package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.intellij.ide.util.ChooseElementsDialog;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import com.intellij.openapi.diagnostic.Logger;
import javax.swing.Icon;

public class IntelliJArtifactSelectionDialog extends ChooseElementsDialog<Artifact> {

    private static final Logger LOG = Logger.getInstance(IntelliJArtifactSelectionDialog.class);

    public IntelliJArtifactSelectionDialog(@NotNull Project project, @NotNull List<Artifact> artifacts) {
        super(project, artifacts, "Select Artifacts to Deploy", "Selected artifacts will be deployed at server startup");
        LOG.debug("IntelliJArtifactSelectionDialog initialized with " + artifacts.size() + " artifacts");
    }

    @Override
    protected Icon getItemIcon(Artifact item) {
        try {
            return item.getArtifactType().getIcon();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String getItemText(Artifact item) {
        String name = item.getName();
        try {
            String typeName = item.getArtifactType().getPresentableName();
            if (typeName != null && !typeName.isEmpty()) {
                return name + "  [" + typeName + "]";
            }
        } catch (Exception e) {
            // Fall through to plain name
        }
        return name;
    }
    
    public List<Artifact> getSelectedArtifacts() {
        return getChosenElements();
    }
}