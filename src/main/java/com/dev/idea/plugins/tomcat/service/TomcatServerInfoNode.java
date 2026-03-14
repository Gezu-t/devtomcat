package com.dev.idea.plugins.tomcat.service;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.PortConfig;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Displays server configuration details as an expandable node in the Services tree.
 * Shows ports, paths, and JVM settings as child "info line" nodes.
 */
public class TomcatServerInfoNode extends AbstractTreeNode<String> {

    private final TomcatRunConfiguration configuration;

    public TomcatServerInfoNode(@NotNull Project project,
                                 @NotNull TomcatRunConfiguration configuration) {
        super(project, "Server Info");
        this.configuration = configuration;
    }

    @Override
    @NotNull
    public Collection<? extends AbstractTreeNode<?>> getChildren() {
        Project project = getProject();
        if (project == null || project.isDisposed()) return Collections.emptyList();

        List<AbstractTreeNode<?>> children = new ArrayList<>();

        // Tomcat home path
        TomcatInfo info = configuration.getTomcatInfo();
        if (info != null) {
            if (!info.getVersion().isEmpty()) {
                children.add(new InfoLineNode(project, "Version", "Tomcat " + info.getVersion(),
                        AllIcons.General.Information));
            }
            children.add(new InfoLineNode(project, "CATALINA_HOME", info.getPath(),
                    AllIcons.Nodes.Folder));
        }

        // CATALINA_BASE
        Path catalinaBase = TomcatProjectUtils.getCatalinaBase(configuration);
        if (catalinaBase != null) {
            children.add(new InfoLineNode(project, "CATALINA_BASE", catalinaBase.toString(),
                    AllIcons.Nodes.Folder));
        }

        // Ports
        PortConfig ports = configuration.getConfigData().getPortConfig();
        children.add(new InfoLineNode(project, "HTTP", String.valueOf(ports.getHttp()),
                AllIcons.Nodes.Plugin));
        children.add(new InfoLineNode(project, "Shutdown", String.valueOf(ports.getShutdown()),
                AllIcons.Nodes.Plugin));

        if (ports.isHttpsEnabled()) {
            children.add(new InfoLineNode(project, "HTTPS", String.valueOf(ports.getHttps()),
                    AllIcons.Nodes.Plugin));
        }
        if (ports.isAjpEnabled()) {
            children.add(new InfoLineNode(project, "AJP", String.valueOf(ports.getAjp()),
                    AllIcons.Nodes.Plugin));
        }
        if (ports.isJmxEnabled()) {
            children.add(new InfoLineNode(project, "JMX", String.valueOf(ports.getJmx()),
                    AllIcons.Nodes.Plugin));
        }

        // VM options
        String vmOptions = configuration.getVmOptions();
        if (!vmOptions.isEmpty()) {
            children.add(new InfoLineNode(project, "VM Options", vmOptions,
                    AllIcons.General.Settings));
        }

        // Server mode
        String mode = configuration.getConfigData().getServerMode();
        if (mode != null && !mode.isEmpty()) {
            children.add(new InfoLineNode(project, "Mode", mode,
                    AllIcons.General.Information));
        }

        return children;
    }

    @Override
    protected void update(@NotNull PresentationData presentation) {
        presentation.setIcon(AllIcons.General.GearPlain);
        presentation.addText("Server Configuration", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
    }

    @Override
    public boolean canNavigate() {
        return false;
    }

    @Override
    public boolean canNavigateToSource() {
        return false;
    }

    /**
     * Leaf node displaying a single key-value info line in the Services tree.
     */
    static final class InfoLineNode extends AbstractTreeNode<String> {

        private final String label;
        private final String value;
        private final Icon icon;

        InfoLineNode(@NotNull Project project, @NotNull String label,
                     @NotNull String value, @NotNull Icon icon) {
            super(project, label + ": " + value);
            this.label = label;
            this.value = value;
            this.icon = icon;
        }

        @Override
        @NotNull
        public Collection<? extends AbstractTreeNode<?>> getChildren() {
            return Collections.emptyList();
        }

        @Override
        protected void update(@NotNull PresentationData presentation) {
            presentation.setIcon(icon);
            presentation.addText(label + ": ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
            presentation.addText(value, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }

        @Override
        public boolean canNavigate() {
            return false;
        }

        @Override
        public boolean canNavigateToSource() {
            return false;
        }
    }
}
