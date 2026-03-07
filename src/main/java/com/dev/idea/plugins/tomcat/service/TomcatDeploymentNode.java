package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Represents a deployed artifact as a child node in the Services tool window tree.
 * Mirrors IntelliJ Ultimate's behavior of showing artifacts under the server node.
 *
 * <p>Double-clicking the node opens the artifact in the browser using
 * its context path and configured HTTP port (via inherited {@code Navigatable}).
 */
public class TomcatDeploymentNode extends AbstractTreeNode<DeploymentArtifact> {

    private final int httpPort;
    private final String configurationName;

    public TomcatDeploymentNode(@NotNull Project project,
                                 @NotNull DeploymentArtifact artifact,
                                 int httpPort,
                                 @Nullable String configurationName) {
        super(project, artifact);
        this.httpPort = httpPort > 0 ? httpPort : Integer.parseInt(DEFAULT_PORT);
        this.configurationName = configurationName;
    }

    @Override
    @NotNull
    public Collection<? extends AbstractTreeNode<?>> getChildren() {
        return Collections.emptyList();
    }

    @Override
    protected void update(@NotNull PresentationData presentation) {
        DeploymentArtifact artifact = getValue();
        if (artifact == null) return;

        // Resolve live artifact status
        TomcatDeploymentStatusService.ArtifactState artifactState = resolveArtifactState(artifact);

        // Icon reflects live status
        if (artifactState != null) {
            switch (artifactState) {
                case DEPLOYING, RELOADING -> presentation.setIcon(AllIcons.Actions.Execute);
                case DEPLOYED -> presentation.setIcon(AllIcons.RunConfigurations.TestPassed);
                case FAILED -> presentation.setIcon(AllIcons.General.Error);
                default -> presentation.setIcon(AllIcons.Nodes.Artifact);
            }
        } else {
            presentation.setIcon(AllIcons.Nodes.Artifact);
        }

        presentation.addText(artifact.getDisplayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

        // Type badge: [WAR] or [Exploded]
        String typeBadge = DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                ? " [Exploded]" : " [WAR]";
        presentation.addText(typeBadge,
                SimpleTextAttributes.merge(SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES));

        // Live status label
        if (artifactState != null) {
            if (artifactState == TomcatDeploymentStatusService.ArtifactState.DEPLOYED) {
                // Clickable hyperlink style — double-click or navigate() opens in browser
                presentation.addText("  " + artifactState.getLabel(), SimpleTextAttributes.LINK_ATTRIBUTES);
                presentation.addText("  " + buildUrl(artifact), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else {
                SimpleTextAttributes statusAttr = switch (artifactState) {
                    case FAILED -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                            JBColor.namedColor("DevTomcat.failedForeground", JBColor.RED));
                    case DEPLOYING, RELOADING -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES;
                    default -> SimpleTextAttributes.GRAYED_ATTRIBUTES;
                };
                presentation.addText("  " + artifactState.getLabel(), statusAttr);
            }
        }

        // Context path (skip when DEPLOYED — URL is already shown)
        if (artifactState != TomcatDeploymentStatusService.ArtifactState.DEPLOYED) {
            String contextPath = artifact.getContextPath();
            if (contextPath != null && !contextPath.isEmpty()) {
                presentation.addText("  " + contextPath, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        // Tooltip with full artifact info
        presentation.setTooltip(buildTooltip(artifact));
    }

    @Nullable
    private TomcatDeploymentStatusService.ArtifactState resolveArtifactState(
            @NotNull DeploymentArtifact artifact) {
        Project project = getProject();
        if (project == null || project.isDisposed() || configurationName == null) return null;
        TomcatDeploymentStatusService service =
                TomcatDeploymentStatusService.getInstance(project);
        TomcatDeploymentStatusService.ConfigStatus status = service.getStatus(configurationName);
        if (status == null) return null;
        return status.getArtifactStates().get(artifact.getDisplayName());
    }

    @NotNull
    private String buildTooltip(@NotNull DeploymentArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append("Artifact: ").append(artifact.getDisplayName());
        sb.append("\nType: ").append(artifact.getType());
        sb.append("\nContext: ").append(artifact.getContextPath());
        sb.append("\nPath: ").append(artifact.getPath());
        if (httpPort > 0) {
            sb.append("\nURL: ").append(buildUrl(artifact));
        }
        return sb.toString();
    }

    @NotNull
    private String buildUrl(@NotNull DeploymentArtifact artifact) {
        String context = artifact.getContextPath();
        if (context == null || context.isEmpty()) {
            context = DEFAULT_CONTEXT_PATH;
        }
        return "http://" + DEFAULT_HOST + ":" + httpPort + context;
    }

    // --- Navigatable implementation (double-click opens in browser) ---

    @Override
    public void navigate(boolean requestFocus) {
        DeploymentArtifact artifact = getValue();
        if (artifact != null) {
            BrowserUtil.browse(buildUrl(artifact));
        }
    }

    @Override
    public boolean canNavigate() {
        return getValue() != null && httpPort > 0;
    }

    @Override
    public boolean canNavigateToSource() {
        DeploymentArtifact artifact = getValue();
        if (artifact == null || !canNavigate()) return false;
        Project project = getProject();
        if (project == null || project.isDisposed() || configurationName == null) return false;
        TomcatDeploymentStatusService.ConfigStatus status =
                TomcatDeploymentStatusService.getInstance(project).getStatus(configurationName);
        if (status == null) return false;
        return status.getArtifactStates().get(artifact.getDisplayName())
                == TomcatDeploymentStatusService.ArtifactState.DEPLOYED;
    }

    @Nullable
    public String getConfigurationName() {
        return configurationName;
    }

    public int getHttpPort() {
        return httpPort;
    }
}
