package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
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

/**
 * Shared utilities for extracting DevTomcat-related objects from the Services
 * tool window action event context.
 */
final class ServiceActionUtils {

    private ServiceActionUtils() {}

    /**
     * Extracts a {@link TomcatRunConfiguration} from a Services tree action event.
     *
     * <p>Uses only BGT-safe data keys ({@link CommonDataKeys#NAVIGATABLE} and
     * {@link PlatformCoreDataKeys#SELECTED_ITEMS}) so this method is safe to call from
     * an action's {@code update()} method running on {@link ActionUpdateThread#BGT}.
     * {@link PlatformCoreDataKeys#CONTEXT_COMPONENT} / {@code JTree.getSelectionPath()}
     * requires the EDT and must NOT be called from a BGT update path.
     */
    @Nullable
    static TomcatRunConfiguration findTomcatConfiguration(@NotNull AnActionEvent e) {
        // Primary: NAVIGATABLE exposes RunDashboardRunConfigurationNode directly — BGT-safe
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        TomcatRunConfiguration config = extractFromObject(navigatable);
        if (config != null) return config;

        // Secondary: SELECTED_ITEMS covers multi-selection and newer Services panel layouts
        Object[] items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS);
        if (items != null) {
            for (Object item : items) {
                config = extractFromObject(item);
                if (config != null) return config;
            }
        }

        return null;
    }

    /**
     * Finds the active {@link ProcessHandler} for a Tomcat configuration, if running.
     *
     * <p>BGT-safe — uses only {@link CommonDataKeys#NAVIGATABLE} and
     * {@link PlatformCoreDataKeys#SELECTED_ITEMS}.
     */
    @Nullable
    static ProcessHandler findProcessHandler(@NotNull AnActionEvent e) {
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        if (navigatable instanceof RunDashboardRunConfigurationNode node) {
            RunContentDescriptor descriptor = node.getDescriptor();
            if (descriptor != null) return descriptor.getProcessHandler();
        }

        Object[] items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS);
        if (items != null) {
            for (Object item : items) {
                ProcessHandler handler = extractProcessHandler(item);
                if (handler != null) return handler;
            }
        }

        return null;
    }

    /**
     * Returns the active {@link TomcatProcessHandler} for a Tomcat configuration, or
     * {@code null} if the process handler is absent or not a Tomcat handler.
     */
    @Nullable
    static TomcatProcessHandler findTomcatProcessHandler(@NotNull AnActionEvent e) {
        ProcessHandler handler = findProcessHandler(e);
        return handler instanceof TomcatProcessHandler tomcatHandler ? tomcatHandler : null;
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
