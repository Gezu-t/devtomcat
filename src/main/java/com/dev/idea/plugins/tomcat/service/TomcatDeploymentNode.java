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
 * <p>Double-clicking the node opens the artifact in the browser using its
 * context path and the resolved (scheme, host, port) — HTTPS when the
 * configuration (or live process) has HTTPS enabled, HTTP otherwise. The
 * host is {@code localhost} for local-mode configurations and the manager
 * URL's host (extracted by {@link TomcatRunDashboardCustomizer}) for remote
 * mode, so a remote deploy's "Open in Browser" action lands on the actual
 * remote server rather than the user's machine.
 */
public class TomcatDeploymentNode extends AbstractTreeNode<DeploymentArtifact> {

    private final String host;
    private final boolean https;
    private final int port;
    private final String configurationName;

    public TomcatDeploymentNode(@NotNull Project project,
                                 @NotNull DeploymentArtifact artifact,
                                 @NotNull String host,
                                 boolean https,
                                 int port,
                                 @Nullable String configurationName) {
        super(project, artifact);
        // Empty host falls back to localhost so a malformed manager URL never
        // produces "http://:8080/foo". Defensive — the customizer should
        // already substitute localhost when parsing fails.
        this.host = host.isEmpty() ? DEFAULT_HOST : host;
        this.https = https;
        this.port = port > 0 ? port : Integer.parseInt(DEFAULT_PORT);
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
        presentation.addText(formatTypeBadge(artifact),
                SimpleTextAttributes.merge(SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES));

        // Live status label
        if (artifactState != null) {
            SimpleTextAttributes statusAttr = switch (artifactState) {
                case DEPLOYED -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                        JBColor.namedColor("DevTomcat.deployedForeground",
                                new JBColor(0x59A869, 0x499C54)));
                case FAILED -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                        JBColor.namedColor("DevTomcat.failedForeground", JBColor.RED));
                case DEPLOYING, RELOADING -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES;
                default -> SimpleTextAttributes.GRAYED_ATTRIBUTES;
            };
            presentation.addText("  " + artifactState.getLabel(), statusAttr);

            // Show URL next to deployed artifacts (double-click node to open in browser)
            if (artifactState == TomcatDeploymentStatusService.ArtifactState.DEPLOYED) {
                presentation.addText("  " + buildUrl(artifact), SimpleTextAttributes.GRAYED_ATTRIBUTES);
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
        return formatTooltip(artifact, host, https, port);
    }

    @NotNull
    private String buildUrl(@NotNull DeploymentArtifact artifact) {
        return formatUrl(artifact, host, https, port);
    }

    /** Builds the tooltip text for a deployment node. Package-visible for testing. */
    @NotNull
    static String formatTooltip(@NotNull DeploymentArtifact artifact,
                                 @NotNull String host,
                                 boolean https,
                                 int port) {
        StringBuilder sb = new StringBuilder();
        sb.append(artifact.getDisplayName());
        String typeBadge = DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                ? " (Exploded)" : " (WAR)";
        sb.append(typeBadge);
        if (port > 0) {
            sb.append("\n").append(formatUrl(artifact, host, https, port));
        }
        return sb.toString();
    }

    /**
     * Builds the browser URL for a deployment artifact. Package-visible for testing.
     *
     * <p>An IPv6 host returned by {@link java.net.URI#getHost()} comes back
     * unbracketed (e.g. {@code ::1}), but URL syntax requires brackets around
     * IPv6 literals. Re-bracket here so {@code formatUrl} produces a valid URL
     * regardless of whether the caller passed an IPv4, hostname, or raw IPv6.
     */
    @NotNull
    static String formatUrl(@NotNull DeploymentArtifact artifact,
                             @NotNull String host,
                             boolean https,
                             int port) {
        String context = artifact.getContextPath();
        if (context == null || context.isEmpty()) {
            context = DEFAULT_CONTEXT_PATH;
        }
        return (https ? "https" : "http") + "://" + bracketIpv6(host) + ":" + port + context;
    }

    /** Wraps a raw IPv6 literal in brackets if not already bracketed. */
    @NotNull
    private static String bracketIpv6(@NotNull String host) {
        // ":" without brackets is the unambiguous signature of a raw IPv6 host
        // returned by URI.getHost() (e.g. "::1", "2001:db8::1"). IPv4 and
        // hostnames never contain ":", so this is safe.
        return (host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
    }

    /** Returns the type badge string for display. Package-visible for testing. */
    @NotNull
    static String formatTypeBadge(@NotNull DeploymentArtifact artifact) {
        return DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                ? " [Exploded]" : " [WAR]";
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
        return canNavigate(port, resolveCurrentArtifactState());
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    @Nullable
    private TomcatDeploymentStatusService.ArtifactState resolveCurrentArtifactState() {
        DeploymentArtifact artifact = getValue();
        return artifact != null ? resolveArtifactState(artifact) : null;
    }

    /** Returns true only when the artifact has a valid port and is confirmed deployed. */
    static boolean canNavigate(int port,
                               @Nullable TomcatDeploymentStatusService.ArtifactState artifactState) {
        return port > 0 && artifactState == TomcatDeploymentStatusService.ArtifactState.DEPLOYED;
    }

    @Nullable
    public String getConfigurationName() {
        return configurationName;
    }

    @NotNull
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isHttps() {
        return https;
    }
}
