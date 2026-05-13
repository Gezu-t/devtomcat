package com.dev.idea.plugins.tomcat.serviceview;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentStatusService;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.services.ServiceViewContributor;
import com.intellij.execution.services.ServiceViewDescriptor;
import com.intellij.execution.services.ServiceViewProvidingContributor;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a Tomcat run configuration as a service row in the Services tool
 * window. Provides deployment artifacts as child rows via the
 * {@link ServiceViewProvidingContributor} contract.
 *
 * <p>This is the replacement for the legacy {@code RunDashboardCustomizer}
 * tree-rendering path. The platform call stops invoking that customizer on
 * branch 253; the {@link ServiceViewContributor} pipeline is the supported
 * path on both 251 / 252 and 253+.
 *
 * <p>Identity = configuration name. The platform diffs the tree using
 * {@link #equals(Object)} / {@link #hashCode()}, so changing presentation
 * state (running / stopped, port shifts) keeps the row identity stable.
 */
public final class TomcatRunConfigContributor
        implements ServiceViewProvidingContributor<TomcatArtifactItem, TomcatRunConfigContributor> {

    @NotNull private final RunnerAndConfigurationSettings settings;
    @NotNull private final TomcatRunConfiguration tomcatConfig;

    public TomcatRunConfigContributor(@NotNull RunnerAndConfigurationSettings settings) {
        this.settings = settings;
        if (!(settings.getConfiguration() instanceof TomcatRunConfiguration tc)) {
            throw new IllegalArgumentException(
                    "TomcatRunConfigContributor requires a TomcatRunConfiguration");
        }
        this.tomcatConfig = tc;
    }

    @NotNull
    public RunnerAndConfigurationSettings getSettings() {
        return settings;
    }

    @NotNull
    public TomcatRunConfiguration getConfiguration() {
        return tomcatConfig;
    }

    @Override
    public TomcatRunConfigContributor asService() {
        return this;
    }

    @Override
    @NotNull
    public List<TomcatArtifactItem> getServices(@NotNull Project project) {
        List<DeploymentArtifact> artifacts =
                tomcatConfig.getConfigData().getDeploymentConfig().getArtifacts();
        if (artifacts == null || artifacts.isEmpty()) return Collections.emptyList();

        Endpoint endpoint = resolveEndpoint(project);
        Map<String, TomcatDeploymentStatusService.ArtifactState> artifactStates =
                resolveArtifactStates(project);

        List<TomcatArtifactItem> items = new ArrayList<>(artifacts.size());
        for (DeploymentArtifact artifact : artifacts) {
            if (artifact == null) continue;
            items.add(new TomcatArtifactItem(
                    artifact,
                    tomcatConfig.getName(),
                    endpoint.host(),
                    endpoint.https(),
                    endpoint.port(),
                    artifactStates.get(artifact.getDisplayName())));
        }
        return items;
    }

    @Override
    @NotNull
    public ServiceViewDescriptor getServiceDescriptor(@NotNull Project project,
                                                       @NotNull TomcatArtifactItem service) {
        return new TomcatArtifactDescriptor(project, service);
    }

    @Override
    @NotNull
    public ServiceViewDescriptor getViewDescriptor(@NotNull Project project) {
        return new RunConfigDescriptor(project);
    }

    // -- Endpoint resolution (mirrors TomcatRunDashboardCustomizer behaviour) --

    @NotNull
    private Endpoint resolveEndpoint(@NotNull Project project) {
        if (tomcatConfig.isRemoteMode()) {
            return endpointFromManagerUrl(
                    tomcatConfig.getConfigData().getRemoteConfig().getManagerUrl());
        }
        TomcatProcessHandler liveHandler = findLiveHandler(project);
        if (liveHandler != null) {
            PortConfig live = liveHandler.getResolvedPorts();
            if (live != null && live.isHttpsEnabled() && live.getHttps() > 0) {
                return new Endpoint(TomcatConstants.DEFAULT_HOST, true, live.getHttps());
            }
            int httpPort = liveHandler.getHttpPort();
            if (httpPort > 0) return new Endpoint(TomcatConstants.DEFAULT_HOST, false, httpPort);
        }
        if (tomcatConfig.isHttpsEnabled()) {
            Integer httpsPort = tomcatConfig.getHttpsPort();
            if (httpsPort != null && httpsPort > 0) {
                return new Endpoint(TomcatConstants.DEFAULT_HOST, true, httpsPort);
            }
        }
        Integer httpPort = tomcatConfig.getHttpPort();
        return new Endpoint(TomcatConstants.DEFAULT_HOST, false, httpPort != null ? httpPort : 0);
    }

    @Nullable
    private TomcatProcessHandler findLiveHandler(@NotNull Project project) {
        try {
            ProcessHandler[] handlers = ExecutionManager.getInstance(project).getRunningProcesses();
            for (ProcessHandler handler : handlers) {
                if (handler instanceof TomcatProcessHandler th && !th.isProcessTerminated()) {
                    TomcatRunConfiguration cfg = th.getConfiguration();
                    if (cfg != null && tomcatConfig.getName().equals(cfg.getName())) {
                        return th;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Defensive — ExecutionManager state can change during tree refresh.
        }
        return null;
    }

    @NotNull
    private Map<String, TomcatDeploymentStatusService.ArtifactState> resolveArtifactStates(
            @NotNull Project project) {
        try {
            TomcatDeploymentStatusService service =
                    TomcatDeploymentStatusService.getInstance(project);
            TomcatDeploymentStatusService.ConfigStatus status =
                    service.getStatus(tomcatConfig.getName());
            if (status != null) return status.getArtifactStates();
        } catch (Throwable ignored) {
        }
        return Collections.emptyMap();
    }

    @NotNull
    private static Endpoint endpointFromManagerUrl(@Nullable String managerUrl) {
        if (managerUrl == null || managerUrl.isBlank()) {
            return new Endpoint(TomcatConstants.DEFAULT_HOST, false, 0);
        }
        try {
            URI uri = URI.create(managerUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return new Endpoint(TomcatConstants.DEFAULT_HOST, false, 0);
            }
            boolean https = "https".equalsIgnoreCase(scheme);
            int port = uri.getPort();
            if (port < 0) port = https ? 443 : 80;
            return new Endpoint(host, https, port);
        } catch (IllegalArgumentException e) {
            return new Endpoint(TomcatConstants.DEFAULT_HOST, false, 0);
        }
    }

    private record Endpoint(@NotNull String host, boolean https, int port) {}

    // -- Identity --

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TomcatRunConfigContributor that)) return false;
        return tomcatConfig.getName().equals(that.tomcatConfig.getName());
    }

    @Override
    public int hashCode() {
        return tomcatConfig.getName().hashCode();
    }

    // ========================================================================
    // Descriptors (presentation)
    // ========================================================================

    /** Renders THIS run config as a row in the Services tree. */
    private final class RunConfigDescriptor implements ServiceViewDescriptor {

        @NotNull private final Project project;

        RunConfigDescriptor(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public ItemPresentation getPresentation() {
            PresentationData data = new PresentationData();
            data.setIcon(resolveIcon());
            data.addText(tomcatConfig.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

            String statusLine = buildStatusLine();
            if (!statusLine.isEmpty()) {
                data.addText("  " + statusLine, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            return data;
        }

        @Override
        @Nullable
        public ActionGroup getPopupActions() {
            return (ActionGroup) ActionManager.getInstance()
                    .getAction("RunDashboardPopup");
        }

        @NotNull
        private Icon resolveIcon() {
            TomcatDeploymentStatusService.ConfigStatus liveStatus = safeLiveStatus();
            Icon configIcon = tomcatConfig.getIcon();
            if (configIcon == null) configIcon = AllIcons.RunConfigurations.Application;
            if (liveStatus == null) return configIcon;
            return switch (liveStatus.getServerState()) {
                case STARTING, DEPLOYING -> AllIcons.Actions.Execute;
                case RUNNING -> AllIcons.RunConfigurations.TestState.Run;
                case FAILED -> AllIcons.General.Error;
                case STOPPED -> configIcon;
            };
        }

        @NotNull
        private String buildStatusLine() {
            StringBuilder text = new StringBuilder();

            TomcatInfo tomcatInfo = tomcatConfig.getTomcatInfo();
            if (tomcatInfo != null && !tomcatInfo.getVersion().isEmpty()) {
                text.append("Tomcat ").append(tomcatInfo.getVersion());
            }

            Endpoint endpoint = resolveEndpoint(project);
            if (endpoint.port() > 0) {
                if (text.length() > 0) text.append(" · ");
                text.append(":").append(endpoint.port());
            }

            TomcatDeploymentStatusService.ConfigStatus liveStatus = safeLiveStatus();
            if (liveStatus != null) {
                if (text.length() > 0) text.append(" · ");
                text.append(liveStatus.getServerState().getLabel());
            }
            return text.toString();
        }

        @Nullable
        private TomcatDeploymentStatusService.ConfigStatus safeLiveStatus() {
            try {
                return TomcatDeploymentStatusService.getInstance(project)
                        .getStatus(tomcatConfig.getName());
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** Renders one deployment artifact as a child row. */
    private static final class TomcatArtifactDescriptor implements ServiceViewDescriptor {

        @NotNull private final Project project;
        @NotNull private final TomcatArtifactItem item;

        TomcatArtifactDescriptor(@NotNull Project project, @NotNull TomcatArtifactItem item) {
            this.project = project;
            this.item = item;
        }

        @Override
        public ItemPresentation getPresentation() {
            PresentationData data = new PresentationData();
            DeploymentArtifact artifact = item.getArtifact();

            // Icon reflects live status.
            TomcatDeploymentStatusService.ArtifactState state = item.getState();
            if (state != null) {
                data.setIcon(switch (state) {
                    case DEPLOYING, RELOADING -> AllIcons.Actions.Execute;
                    case DEPLOYED -> AllIcons.RunConfigurations.TestPassed;
                    case FAILED -> AllIcons.General.Error;
                    default -> AllIcons.Nodes.Artifact;
                });
            } else {
                data.setIcon(AllIcons.Nodes.Artifact);
            }

            data.addText(artifact.getDisplayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);

            String typeBadge = DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                    ? " [Exploded]" : " [WAR]";
            data.addText(typeBadge,
                    SimpleTextAttributes.merge(
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                            SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES));

            String contextPath = artifact.getContextPath();
            if (contextPath != null && !contextPath.isEmpty()) {
                data.addText("  " + contextPath, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }

            if (state != null) {
                data.addText("  " + state.getLabel(),
                        SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
            return data;
        }
    }
}
