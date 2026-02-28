package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardCustomizer;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Customizes how DevTomcat run configurations appear in the Services tool window.
 * Adds status text (e.g., Tomcat version, port, deployed artifacts) to the tree node.
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
        RunConfiguration config = node.getConfigurationSettings().getConfiguration();
        if (!(config instanceof TomcatRunConfiguration tomcatConfig)) {
            return false;
        }

        try {
            StringBuilder statusText = new StringBuilder();

            // Tomcat version
            TomcatInfo tomcatInfo = tomcatConfig.getTomcatInfo();
            if (tomcatInfo != null && tomcatInfo.getVersion() != null && !tomcatInfo.getVersion().isEmpty()) {
                statusText.append("Tomcat ").append(tomcatInfo.getVersion());
            }

            // HTTP port
            Integer httpPort = tomcatConfig.getHttpPort();
            if (httpPort != null && httpPort > 0) {
                if (!statusText.isEmpty()) statusText.append(" · ");
                statusText.append(":").append(httpPort);
            }

            // Deployed artifacts count
            List<DeploymentArtifact> artifacts = tomcatConfig.getConfigData().getDeploymentConfig().getArtifacts();
            if (artifacts != null && !artifacts.isEmpty()) {
                if (!statusText.isEmpty()) statusText.append(" · ");
                statusText.append(artifacts.size()).append(artifacts.size() == 1 ? " artifact" : " artifacts");
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
}
