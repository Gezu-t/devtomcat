package com.dev.idea.plugins.tomcat.serviceview;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Leaf node in the Services tool window tree: one deployment artifact under
 * a Tomcat run configuration.
 *
 * <p>Plain immutable carrier — the surrounding {@link TomcatRunConfigContributor}
 * owns the rendering (it returns this object from {@code getServices(...)} and
 * provides the matching {@link com.intellij.execution.services.ServiceViewDescriptor}
 * from {@code getServiceDescriptor(...)}). Keeping the data and the descriptor
 * apart lets the platform diff the tree on its own equality contract; that's
 * why this class implements {@link #equals(Object)} and {@link #hashCode()}
 * solely from the artifact identity + configuration name (state can change
 * underneath but the row stays the same row).
 *
 * <p>The browser URL fields ({@code host}, {@code https}, {@code port}) are
 * captured at construction time from the live or configured endpoint of the
 * owning run config so the open-in-browser action lands on the right place
 * without re-resolving on every redraw.
 */
public final class TomcatArtifactItem {

    @NotNull private final DeploymentArtifact artifact;
    @NotNull private final String configurationName;
    @NotNull private final String host;
    private final boolean https;
    private final int port;
    @Nullable private final TomcatDeploymentStatusService.ArtifactState state;

    public TomcatArtifactItem(@NotNull DeploymentArtifact artifact,
                              @NotNull String configurationName,
                              @NotNull String host,
                              boolean https,
                              int port,
                              @Nullable TomcatDeploymentStatusService.ArtifactState state) {
        this.artifact = artifact;
        this.configurationName = configurationName;
        this.host = host;
        this.https = https;
        this.port = port;
        this.state = state;
    }

    @NotNull public DeploymentArtifact getArtifact() { return artifact; }
    @NotNull public String getConfigurationName() { return configurationName; }
    @NotNull public String getHost() { return host; }
    public boolean isHttps() { return https; }
    public int getPort() { return port; }
    @Nullable public TomcatDeploymentStatusService.ArtifactState getState() { return state; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TomcatArtifactItem that)) return false;
        // Identity = (configuration, artifact display name). State, host, port can
        // change underneath but the row in the tree is the same row.
        return configurationName.equals(that.configurationName)
                && artifact.getDisplayName().equals(that.artifact.getDisplayName());
    }

    @Override
    public int hashCode() {
        return 31 * configurationName.hashCode() + artifact.getDisplayName().hashCode();
    }
}
