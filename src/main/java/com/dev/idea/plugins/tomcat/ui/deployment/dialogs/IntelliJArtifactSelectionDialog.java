package com.dev.idea.plugins.tomcat.ui.deployment.dialogs;

import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
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
        // Use the actual artifact type from IntelliJ's type system for accurate display.
        // In Ultimate: type IDs are "exploded-war", "war", "web-application-exploded", etc.
        // In Community: type IDs are "plain", "jar" — so we also check the artifact name
        // for type hints (e.g. "myapp_war_exploded", "myapp.war").
        String type = resolveDisplayType(item);
        return ContextPathUtils.formatArtifactDisplayName(item.getName(), type);
    }

    private static String resolveDisplayType(@NotNull Artifact item) {
        try {
            String typeId = item.getArtifactType().getId().toLowerCase();
            if (typeId.contains("exploded")) return "exploded";
            if (typeId.contains("war")) return "war";
            if (typeId.contains("ear")) return "ear";
        } catch (Exception ignored) {}

        // Fallback: infer from artifact name (common in Community Edition)
        String name = item.getName().toLowerCase();
        if (name.contains("exploded")) return "exploded";
        if (name.endsWith(".war") || name.endsWith("_war") || name.endsWith(":war")) return "war";

        // No type signal — return null so formatArtifactDisplayName returns the raw name
        return null;
    }
    
    public List<Artifact> getSelectedArtifacts() {
        return getChosenElements();
    }
}