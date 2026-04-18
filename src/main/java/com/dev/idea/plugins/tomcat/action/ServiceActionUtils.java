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
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared utilities for extracting DevTomcat-related objects from the Services
 * tool window action event context.
 */
final class ServiceActionUtils {

    private ServiceActionUtils() {}

    /**
     * Extracts a {@link TomcatRunConfiguration} from a Services tree action event.
     *
     * <p>Must be called on the EDT. In IntelliJ 2024.x the Services panel data providers
     * for {@link CommonDataKeys#NAVIGATABLE} and {@link PlatformCoreDataKeys#SELECTED_ITEMS}
     * are computed lazily from the tree selection and are not snapshot-captured before BGT
     * dispatch. All callers (action {@code update()} and {@code actionPerformed()}) run on
     * the EDT via {@link ActionUpdateThread#EDT}.
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
     * Must be called on the EDT — see {@link #findTomcatConfiguration(AnActionEvent)}.
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

    /**
     * Applies the shared Services-panel startup-gate policy to an action's {@link Presentation}.
     *
     * <p>Three states, uniform across {@code Restart}, {@code Redeploy}, and
     * {@code Update Application}:
     * <ul>
     *   <li><b>Hidden</b> — no Tomcat config or handler attached, or the handler has
     *       fully terminated. The action no longer applies to this node.</li>
     *   <li><b>Visible but disabled</b> — a live handler is attached but the shared
     *       gate {@link TomcatProcessHandler#getRestartBlockReason()} says the restart
     *       is not safe right now (starting up, shutting down). The reason string is
     *       set as the presentation description so the IDE renders it as the
     *       disabled-button tooltip. Prevents the "where did the button go?"
     *       confusion and keeps muscle-memory targets stable as state transitions.</li>
     *   <li><b>Visible and enabled</b> — gate open; action is safe to invoke.
     *       Description reset to {@code readyDescription}.</li>
     * </ul>
     *
     * <p>The state-predicate {@code getRestartBlockReason()} is the single source of
     * truth shared with the toolbar rerun intercept, Ctrl+F10 provider, and
     * frame-deactivation listener — the three surfaces cannot drift on whether a
     * restart is allowed, only on how they render the block.
     */
    static void applyStartupGate(@NotNull Presentation presentation,
                                  @Nullable TomcatRunConfiguration config,
                                  @Nullable TomcatProcessHandler handler,
                                  @NotNull String readyDescription) {
        // Only a handler that has fully terminated removes the action entirely —
        // the process is gone, the Services node is about to disappear. Using the
        // raw isProcessTerminated() flag here would silently hide the action
        // during the shutdown overlap window (both terminating and terminated
        // briefly true), bypassing the "Tomcat is shutting down" branch of the
        // shared gate. isFullyTerminated() keeps the overlap routed through
        // getRestartBlockReason() so the tooltip still appears.
        if (config == null || handler == null || handler.isFullyTerminated()) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        String blockReason = handler.getRestartBlockReason();
        if (blockReason != null) {
            presentation.setVisible(true);
            presentation.setEnabled(false);
            presentation.setDescription(blockReason);
        } else {
            presentation.setEnabledAndVisible(true);
            presentation.setDescription(readyDescription);
        }
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
        for (String methodName : new String[]{"getDescriptor", "getNode"}) {
            Object result = tryInvokeMethod(obj, methodName);
            if (result instanceof RunContentDescriptor desc) {
                return desc.getProcessHandler();
            }
            if (result instanceof RunDashboardRunConfigurationNode node) {
                RunContentDescriptor desc = node.getDescriptor();
                if (desc != null) return desc.getProcessHandler();
            }
        }
        return null;
    }

    @Nullable
    private static TomcatRunConfiguration extractViaReflection(@Nullable Object wrapper) {
        if (wrapper == null) return null;
        for (String methodName : new String[]{"getConfigurationSettings", "getNode", "getValue", "getData"}) {
            Object result = tryInvokeMethod(wrapper, methodName);
            if (result instanceof RunnerAndConfigurationSettings settings) {
                RunConfiguration rc = settings.getConfiguration();
                if (rc instanceof TomcatRunConfiguration tomcat) return tomcat;
            }
            TomcatRunConfiguration nested = extractFromObject(result);
            if (nested != null) return nested;
        }
        return null;
    }

    /**
     * Invokes a zero-argument method on {@code obj} by name, returning {@code null}
     * if the method does not exist or any error occurs during invocation.
     */
    @Nullable
    private static Object tryInvokeMethod(@Nullable Object obj, @NotNull String methodName) {
        if (obj == null) return null;
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
