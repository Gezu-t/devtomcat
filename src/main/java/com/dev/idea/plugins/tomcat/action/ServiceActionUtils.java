package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.DashboardCompat;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.TreePath;

/**
 * Shared utilities for extracting DevTomcat-related objects from the Services
 * tool window action event context.
 */
final class ServiceActionUtils {

    private ServiceActionUtils() {}

    /**
     * Extracts a {@link TomcatRunConfiguration} from a Services tree action event.
     * Tries NAVIGATABLE first (which may expose a RunDashboardRunConfigurationNode),
     * then walks the tree selection as a fallback.
     */
    @Nullable
    static TomcatRunConfiguration findTomcatConfiguration(@NotNull AnActionEvent e) {
        // Try NAVIGATABLE — may expose RunDashboardRunConfigurationNode directly
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        TomcatRunConfiguration config = extractFromObject(navigatable);
        if (config != null) return config;

        // Try tree selection fallback
        var component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT);
        if (component instanceof JTree tree) {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                config = extractFromObject(path.getLastPathComponent());
                if (config != null) return config;

                // Walk up the tree path for wrapper scenarios
                for (int i = path.getPathCount() - 1; i >= 0; i--) {
                    config = extractFromObject(path.getPathComponent(i));
                    if (config != null) return config;
                }
            }
        }

        return null;
    }

    /**
     * Finds the active {@link ProcessHandler} for a Tomcat configuration, if running.
     */
    @Nullable
    static ProcessHandler findProcessHandler(@NotNull AnActionEvent e) {
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        if (navigatable instanceof RunDashboardRunConfigurationNode node) {
            RunContentDescriptor descriptor = node.getDescriptor();
            if (descriptor != null) {
                return descriptor.getProcessHandler();
            }
        }

        // Tree fallback
        var component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT);
        if (component instanceof JTree tree) {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                Object last = path.getLastPathComponent();
                ProcessHandler handler = extractProcessHandler(last);
                if (handler != null) return handler;
            }
        }
        return null;
    }

    /**
     * Returns the {@link RunnerAndConfigurationSettings} for the given config.
     */
    @Nullable
    static RunnerAndConfigurationSettings findSettings(@NotNull Project project,
                                                        @NotNull TomcatRunConfiguration config) {
        return RunManager.getInstance(project).findSettings(config);
    }

    /**
     * Checks whether a process handler represents a running (non-terminated) process.
     */
    static boolean isRunning(@Nullable ProcessHandler handler) {
        return handler != null && !handler.isProcessTerminated() && !handler.isProcessTerminating();
    }

    private static TomcatRunConfiguration extractFromObject(@Nullable Object obj) {
        if (obj == null) return null;

        if (obj instanceof RunDashboardRunConfigurationNode node) {
            RunConfiguration rc = DashboardCompat.getConfiguration(node);
            if (rc instanceof TomcatRunConfiguration tomcat) return tomcat;
        }

        if (obj instanceof javax.swing.tree.DefaultMutableTreeNode mutable) {
            return extractFromObject(mutable.getUserObject());
        }

        // Reflection fallback for ServiceView wrappers
        return extractViaReflection(obj);
    }

    @Nullable
    private static ProcessHandler extractProcessHandler(@Nullable Object obj) {
        if (obj instanceof RunDashboardRunConfigurationNode node) {
            RunContentDescriptor desc = node.getDescriptor();
            if (desc != null) return desc.getProcessHandler();
        }
        if (obj instanceof javax.swing.tree.DefaultMutableTreeNode mutable) {
            return extractProcessHandler(mutable.getUserObject());
        }
        // Reflection fallback
        try {
            for (String methodName : new String[]{"getDescriptor", "getNode"}) {
                try {
                    var method = obj.getClass().getMethod(methodName);
                    Object result = method.invoke(obj);
                    if (result instanceof RunContentDescriptor desc) {
                        return desc.getProcessHandler();
                    }
                    if (result instanceof RunDashboardRunConfigurationNode node) {
                        RunContentDescriptor desc = node.getDescriptor();
                        if (desc != null) return desc.getProcessHandler();
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static TomcatRunConfiguration extractViaReflection(@Nullable Object wrapper) {
        if (wrapper == null) return null;
        try {
            for (String methodName : new String[]{"getConfigurationSettings", "getNode", "getValue", "getData"}) {
                try {
                    var method = wrapper.getClass().getMethod(methodName);
                    Object result = method.invoke(wrapper);
                    if (result instanceof RunnerAndConfigurationSettings settings) {
                        RunConfiguration rc = settings.getConfiguration();
                        if (rc instanceof TomcatRunConfiguration tomcat) return tomcat;
                    }
                    TomcatRunConfiguration nested = extractFromObject(result);
                    if (nested != null) return nested;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
