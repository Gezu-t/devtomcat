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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.dev.idea.plugins.tomcat.TomcatConstants.DEFAULT_HOST;

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

            // Endpoint — prefer the live resolved port from the running process
            // so auto-resolved ports (e.g. 8080 → 8082 on conflict) are displayed,
            // not the stale configured value. When HTTPS is enabled, the dashboard
            // shows the HTTPS port so the label matches the URL on the artifact rows.
            Endpoint endpoint = resolveLiveEndpoint(node, tomcatConfig);
            if (endpoint.port() > 0) {
                if (!statusText.isEmpty()) statusText.append(" · ");
                statusText.append(":").append(endpoint.port());
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
                // Use the resolved endpoint from the live process handler when available,
                // so auto-resolved ports appear correctly in child node URLs and the
                // scheme matches the configured (or live) HTTPS state. For remote mode
                // the host is parsed from the manager URL so "Open in Browser" lands on
                // the actual remote server, not the user's machine.
                Endpoint endpoint = resolveLiveEndpoint(node, tomcatConfig);
                int port = endpoint.port() > 0 ? endpoint.port() : 8080;
                String configName = tomcatConfig.getName();

                for (DeploymentArtifact artifact : artifacts) {
                    if (artifact != null) {
                        children.add(new TomcatDeploymentNode(
                                project, artifact, endpoint.host(), endpoint.https(),
                                port, configName));
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
     * (host, https, port) triple — what the URL displayed in the Services tree
     * should point at. {@code https=true} means render the URL with the
     * {@code https://} scheme; {@code host} is the connection host
     * ({@code localhost} for local mode, the remote server's host for remote
     * mode); {@code port} is the connector port. Port {@code 0} signals
     * "no port info" — callers fall back to a default or hide the URL.
     */
    record Endpoint(@NotNull String host, boolean https, int port) {}

    /**
     * Resolves the endpoint (scheme + host + port) to display for a run
     * configuration node.
     *
     * <p>Order of preference:
     * <ol>
     *   <li><b>Remote mode:</b> parse scheme/host/port from the configured
     *       Tomcat Manager URL via {@link #endpointFromManagerUrl}. The user's
     *       local config has no live process to consult, and the deployed app
     *       lives at the manager URL's host:port (different scheme/port
     *       directories under {@code /manager} aren't supported by Tomcat).</li>
     *   <li><b>Local mode, running:</b> the live
     *       {@link com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler}'s
     *       resolved {@link com.dev.idea.plugins.tomcat.model.PortConfig} —
     *       picks up auto-resolved ports (8080 → 8082) and the actual HTTPS
     *       state the JVM was launched with, even if the user has since edited
     *       the config.</li>
     *   <li><b>Local mode, stopped:</b> the configured value — HTTPS port when
     *       HTTPS is enabled, HTTP port otherwise — so an apply-while-stopped
     *       is reflected immediately.</li>
     * </ol>
     */
    @NotNull
    private static Endpoint resolveLiveEndpoint(@NotNull RunDashboardRunConfigurationNode node,
                                                 @NotNull TomcatRunConfiguration tomcatConfig) {
        if (tomcatConfig.isRemoteMode()) {
            String managerUrl = tomcatConfig.getConfigData().getRemoteConfig().getManagerUrl();
            return endpointFromManagerUrl(managerUrl);
        }
        RunContentDescriptor descriptor = node.getDescriptor();
        if (descriptor != null && descriptor.getProcessHandler()
                instanceof com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler th
                && !th.isProcessTerminated()) {
            com.dev.idea.plugins.tomcat.model.PortConfig live = th.getResolvedPorts();
            if (live != null && live.isHttpsEnabled() && live.getHttps() > 0) {
                return new Endpoint(DEFAULT_HOST, true, live.getHttps());
            }
            int httpPort = th.getHttpPort();
            if (httpPort > 0) return new Endpoint(DEFAULT_HOST, false, httpPort);
        }
        if (tomcatConfig.isHttpsEnabled()) {
            Integer httpsPort = tomcatConfig.getHttpsPort();
            if (httpsPort != null && httpsPort > 0) {
                return new Endpoint(DEFAULT_HOST, true, httpsPort);
            }
        }
        Integer httpPort = tomcatConfig.getHttpPort();
        return new Endpoint(DEFAULT_HOST, false, httpPort != null ? httpPort : 0);
    }

    /**
     * Parses (scheme, host, port) from a Tomcat Manager URL for display in the
     * Services tree.
     *
     * <p>Examples:
     * <pre>
     *   http://prod.example.com:8080/manager  → (host=prod.example.com,    https=false, port=8080)
     *   https://staging:8443/manager          → (host=staging,             https=true,  port=8443)
     *   http://[2001:db8::1]:8080/manager     → (host=[2001:db8::1],       https=false, port=8080)  // URI.getHost preserves brackets
     *   http://prod.example.com/manager       → (host=prod.example.com,    https=false, port=80)   // default port for scheme
     *   https://prod.example.com/manager      → (host=prod.example.com,    https=true,  port=443)
     * </pre>
     *
     * <p>Falls back to {@code (localhost, false, 0)} on any parse failure or
     * malformed input so the tree never renders {@code "http://null:0/foo"}.
     * A returned port of {@code 0} causes callers to hide the URL entirely
     * via {@link TomcatDeploymentNode#formatTooltip} / {@code canNavigate}.
     */
    @NotNull
    static Endpoint endpointFromManagerUrl(@Nullable String managerUrl) {
        if (managerUrl == null || managerUrl.isBlank()) {
            return new Endpoint(DEFAULT_HOST, false, 0);
        }
        try {
            URI uri = URI.create(managerUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return new Endpoint(DEFAULT_HOST, false, 0);
            }
            boolean https = "https".equalsIgnoreCase(scheme);
            int port = uri.getPort();
            if (port < 0) {
                // No explicit port in the URL — apply the scheme's default so
                // the displayed URL reaches the same place a browser would.
                port = https ? 443 : 80;
            }
            return new Endpoint(host, https, port);
        } catch (IllegalArgumentException e) {
            LOG.debug("Could not parse manager URL for Services tree: " + managerUrl, e);
            return new Endpoint(DEFAULT_HOST, false, 0);
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
