package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.DashboardCompat;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardCustomizer;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Customizes how DevTomcat run configurations appear in the Services tool window.
 * Adds status text (e.g., Tomcat version, port) to the tree node and exposes
 * deployed artifacts as expandable child nodes — matching IntelliJ Ultimate behavior.
 */
public class TomcatRunDashboardCustomizer extends RunDashboardCustomizer {

    private static final Logger LOG = Logger.getInstance(TomcatRunDashboardCustomizer.class);

    @Override
    public boolean isApplicable(@NotNull RunnerAndConfigurationSettings settings,
                                @Nullable RunContentDescriptor descriptor) {
        return settings.getConfiguration() instanceof TomcatRunConfiguration;
    }

    @Override
    public boolean updatePresentation(@NotNull PresentationData presentation,
                                      @NotNull RunDashboardRunConfigurationNode node) {
        RunConfiguration config = DashboardCompat.getConfiguration(node);
        if (!(config instanceof TomcatRunConfiguration tomcatConfig)) {
            return false;
        }

        try {
            StringBuilder statusText = new StringBuilder();

            // Tomcat version
            TomcatInfo tomcatInfo = tomcatConfig.getTomcatInfo();
            if (tomcatInfo != null && !tomcatInfo.getVersion().isEmpty()) {
                statusText.append("Tomcat ").append(tomcatInfo.getVersion());
            }

            // HTTP port
            Integer httpPort = tomcatConfig.getHttpPort();
            if (httpPort != null && httpPort > 0) {
                if (!statusText.isEmpty()) statusText.append(" · ");
                statusText.append(":").append(httpPort);
            }

            // Live deployment status from the status service
            Project project = config.getProject();
            if (project == null || project.isDisposed()) return false;
            TomcatDeploymentStatusService statusService =
                    TomcatDeploymentStatusService.getInstance(project);
            TomcatDeploymentStatusService.ConfigStatus liveStatus =
                    statusService.getStatus(tomcatConfig.getName());

            if (liveStatus != null) {
                TomcatDeploymentStatusService.ServerState state = liveStatus.getServerState();
                if (!statusText.isEmpty()) statusText.append(" · ");

                switch (state) {
                    case STARTING, DEPLOYING -> {
                        statusText.append(state.getLabel());
                        presentation.setIcon(AllIcons.Actions.Execute);
                    }
                    case RUNNING -> {
                        statusText.append(state.getLabel());
                        if (liveStatus.getStartupTimeMs() > 0) {
                            statusText.append(" (").append(formatDuration(liveStatus.getStartupTimeMs())).append(")");
                        }
                    }
                    case FAILED -> {
                        statusText.append(state.getLabel());
                        presentation.setIcon(AllIcons.General.Error);
                    }
                    case STOPPED -> statusText.append(state.getLabel());
                }

                if (state != TomcatDeploymentStatusService.ServerState.STOPPED) {
                    String issueSummary = formatIssueSummary(
                            liveStatus.getErrorCount(),
                            liveStatus.getWarningCount()
                    );
                    if (!issueSummary.isEmpty()) {
                        statusText.append(" · ").append(issueSummary);
                    }
                }
            } else {
                // No live status — show static artifact count
                List<DeploymentArtifact> artifacts = tomcatConfig.getConfigData().getDeploymentConfig().getArtifacts();
                if (artifacts != null && !artifacts.isEmpty()) {
                    if (!statusText.isEmpty()) statusText.append(" · ");
                    statusText.append(artifacts.size()).append(artifacts.size() == 1 ? " artifact" : " artifacts");
                }
            }

            if (!statusText.isEmpty()) {
                presentation.addText("  " + statusText, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }

            return true;

        } catch (Exception e) {
            LOG.debug("Error updating presentation for: " + config.getName(), e);
            return false;
        }
    }

    @Override
    public @Nullable Collection<? extends AbstractTreeNode<?>> getChildren(
            @NotNull RunDashboardRunConfigurationNode node) {
        RunConfiguration config = DashboardCompat.getConfiguration(node);
        if (!(config instanceof TomcatRunConfiguration tomcatConfig)) {
            return null;
        }

        try {
            Project project = config.getProject();
            if (project == null || project.isDisposed()) return null;
            List<AbstractTreeNode<?>> children = new ArrayList<>();

            // Deployment artifact nodes
            List<DeploymentArtifact> artifacts = tomcatConfig.getConfigData()
                    .getDeploymentConfig().getArtifacts();
            if (artifacts != null) {
                Integer httpPort = tomcatConfig.getHttpPort();
                int port = httpPort != null ? httpPort : 8080;
                String configName = tomcatConfig.getName();

                for (DeploymentArtifact artifact : artifacts) {
                    if (artifact != null) {
                        children.add(new TomcatDeploymentNode(project, artifact, port, configName));
                    }
                }
            }

            return children.isEmpty() ? null : children;
        } catch (Exception e) {
            LOG.debug("Error getting deployment children", e);
            return null;
        }
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     * Under 1 second: "850ms", under 1 minute: "12.3s", over 1 minute: "1m 23s".
     */
    @NotNull
    static String formatDuration(long ms) {
        if (ms < 1_000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            long tenths = (ms % 1_000) / 100;
            return (ms / 1_000) + "." + tenths + "s";
        }
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1_000;
        return minutes + "m " + seconds + "s";
    }

    @NotNull
    static String formatIssueSummary(int errors, int warnings) {
        List<String> parts = new ArrayList<>(2);
        if (errors > 0) {
            parts.add(errors + (errors == 1 ? " error" : " errors"));
        }
        if (warnings > 0) {
            parts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
        }
        return String.join(" · ", parts);
    }
}
