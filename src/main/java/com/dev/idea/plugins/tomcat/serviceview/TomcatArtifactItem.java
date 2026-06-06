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
 * from the configuration name plus the artifact's stable identity (display
 * name, context path, type) — never its mutable state, so the row stays the
 * same row while host/port/status change underneath.
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
        // Identity = (configuration, artifact display name, context path, type) —
        // the artifact's stable identity, NOT its mutable state (host/port/state can
        // change underneath while the row stays the same row). Context path + type
        // are included so two same-named artifacts deployed at different contexts
        // don't collapse into a single tree node.
        return configurationName.equals(that.configurationName)
                && artifact.getDisplayName().equals(that.artifact.getDisplayName())
                && java.util.Objects.equals(artifact.getContextPath(), that.artifact.getContextPath())
                && java.util.Objects.equals(artifact.getType(), that.artifact.getType());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(configurationName, artifact.getDisplayName(),
                artifact.getContextPath(), artifact.getType());
    }
}
