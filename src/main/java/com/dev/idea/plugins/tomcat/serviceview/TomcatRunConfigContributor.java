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
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.services.ServiceViewContributor;
import com.intellij.execution.services.ServiceViewDescriptor;
import com.intellij.execution.services.ServiceViewProvidingContributor;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
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

    // -- Endpoint resolution --

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
            data.setTooltip(buildTooltip());
            return data;
        }

        @NotNull
        private String buildTooltip() {
            StringBuilder sb = new StringBuilder();
            sb.append(tomcatConfig.getName());
            TomcatInfo info = tomcatConfig.getTomcatInfo();
            if (info != null) {
                if (!info.getName().isEmpty()) sb.append("\n").append(info.getName());
                if (!info.getVersion().isEmpty()) {
                    sb.append("\nVersion: ").append(info.getVersion());
                }
                if (info.getPath() != null && !info.getPath().isEmpty()) {
                    sb.append("\nHome: ").append(info.getPath());
                }
            }
            Endpoint endpoint = resolveEndpoint(project);
            if (endpoint.port() > 0) {
                sb.append("\n")
                  .append(endpoint.https() ? "https" : "http")
                  .append("://").append(endpoint.host()).append(":").append(endpoint.port());
            }
            return sb.toString();
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
            boolean debugging = isDebuggingNow();
            return switch (liveStatus.getServerState()) {
                // Pre-running and running states get a debug-themed icon when
                // the live executor is the debugger, so users can tell at a
                // glance whether the row is a Run or a Debug session — the
                // standard platform icon convention for running configurations.
                case STARTING, DEPLOYING ->
                        debugging ? AllIcons.Actions.StartDebugger : AllIcons.Actions.Execute;
                case RUNNING ->
                        debugging ? AllIcons.Actions.StartDebugger
                                  : AllIcons.RunConfigurations.TestState.Run;
                case FAILED -> AllIcons.General.Error;
                case STOPPED -> configIcon;
            };
        }

        /**
         * True when the live process handler for this configuration was
         * launched under the Debug executor. Returns false when the config
         * isn't running, when the executor is Run / Coverage / etc., or when
         * the lookup fails defensively.
         */
        private boolean isDebuggingNow() {
            TomcatProcessHandler handler = findLiveHandler(project);
            return handler != null
                    && DefaultDebugExecutor.EXECUTOR_ID.equals(handler.getExecutorId());
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
            data.setTooltip(buildTooltip());
            return data;
        }

        @Override
        @Nullable
        public Navigatable getNavigatable() {
            if (!canOpenInBrowser()) return null;
            String url = buildUrl();
            return new Navigatable() {
                @Override public void navigate(boolean requestFocus) { BrowserUtil.browse(url); }
                @Override public boolean canNavigate() { return true; }
                @Override public boolean canNavigateToSource() { return false; }
            };
        }

        @Override
        @Nullable
        public ActionGroup getPopupActions() {
            // Always return a non-null group so the user can discover the
            // available actions even before the artifact has reported its
            // DEPLOYED state. Each action enables/disables itself in update()
            // based on the current reachability — Open in Browser needs an
            // actually-deployed artifact, Copy URL just needs a resolved port
            // (the URL itself is well-formed regardless of deploy state).
            String url = buildUrl();
            DefaultActionGroup group = new DefaultActionGroup();
            group.add(new AnAction("Open in Browser",
                    "Open this deployed artifact in your default browser",
                    AllIcons.Nodes.PpWeb) {
                @Override public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(canOpenInBrowser());
                }
                @Override public void actionPerformed(@NotNull AnActionEvent e) {
                    BrowserUtil.browse(url);
                }
                @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }
            });
            group.add(new AnAction("Copy URL",
                    "Copy the deployed artifact's URL to the clipboard",
                    AllIcons.Actions.Copy) {
                @Override public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(item.getPort() > 0);
                }
                @Override public void actionPerformed(@NotNull AnActionEvent e) {
                    CopyPasteManager.getInstance()
                            .setContents(new java.awt.datatransfer.StringSelection(url));
                }
                @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }
            });
            return group;
        }

        /**
         * True when the artifact has a valid port and is confirmed deployed.
         * Undeployed or pre-deploy artifacts are not navigated to — they have
         * no meaningful URL yet.
         */
        private boolean canOpenInBrowser() {
            return item.getPort() > 0
                    && item.getState() == TomcatDeploymentStatusService.ArtifactState.DEPLOYED;
        }

        @NotNull
        private String buildUrl() {
            DeploymentArtifact artifact = item.getArtifact();
            String context = artifact.getContextPath();
            if (context == null || context.isEmpty()) {
                context = TomcatConstants.DEFAULT_CONTEXT_PATH;
            }
            return (item.isHttps() ? "https" : "http")
                    + "://" + bracketIpv6(item.getHost())
                    + ":" + item.getPort()
                    + context;
        }

        /**
         * Wraps a raw IPv6 literal in brackets if not already bracketed. A
         * {@link java.net.URI#getHost()} call returns IPv6 hosts unbracketed
         * (e.g. {@code ::1}), but URL syntax requires brackets — re-bracket
         * here so the assembled URL is well-formed regardless of input shape.
         */
        @NotNull
        private static String bracketIpv6(@NotNull String host) {
            return (host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
        }

        @NotNull
        private String buildTooltip() {
            DeploymentArtifact artifact = item.getArtifact();
            StringBuilder sb = new StringBuilder();
            sb.append(artifact.getDisplayName());
            sb.append(DeploymentArtifact.TYPE_EXPLODED.equals(artifact.getType())
                    ? " (Exploded)" : " (WAR)");
            if (item.getPort() > 0) {
                sb.append("\n").append(buildUrl());
            }
            TomcatDeploymentStatusService.ArtifactState state = item.getState();
            if (state != null) {
                sb.append("\nState: ").append(state.getLabel());
            }
            if (canOpenInBrowser()) {
                sb.append("\nDouble-click to open in browser");
            }
            return sb.toString();
        }
    }
}
