package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.runner.TomcatProcessHandler;
import com.dev.idea.plugins.tomcat.utils.DashboardCompat;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runToolbar.RunToolbarData;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
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

        // Tertiary: Run widget rows publish RunToolbarData instead of NAVIGATABLE
        // or SELECTED_ITEMS. Required for Restart/Redeploy/Update to appear next
        // to Run/Stop in the top-right Run widget dropdown.
        TomcatRunConfiguration fromRunToolbar = extractFromRunToolbar(e);
        if (fromRunToolbar != null) return fromRunToolbar;

        return null;
    }

    /**
     * Finds the active {@link ProcessHandler} for a Tomcat configuration, if running.
     * Must be called on the EDT — see {@link #findTomcatConfiguration(AnActionEvent)}.
     */
    @Nullable
    static ProcessHandler findProcessHandler(@NotNull AnActionEvent e) {
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        ProcessHandler handler = extractProcessHandler(navigatable);
        if (handler != null) return handler;

        Object[] items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS);
        if (items != null) {
            for (Object item : items) {
                handler = extractProcessHandler(item);
                if (handler != null) return handler;
            }
        }

        // Run widget context: neither NAVIGATABLE nor SELECTED_ITEMS is set —
        // the widget publishes RunToolbarData which carries the active
        // RunnerAndConfigurationSettings + ExecutionEnvironment for its slot.
        return extractProcessHandlerFromRunToolbar(e);
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

    /**
     * Hard cap on the unwrap recursion. IntelliJ wrappers rarely nest deeper
     * than 2–3 levels in practice; 8 is generous headroom and cheap insurance
     * against a pathological wrapper whose {@code getValue()} (or one of the
     * other probe methods) returns itself or forms a short cycle. Without this
     * guard such a wrapper would stack-overflow the EDT — low likelihood, high
     * blast radius, so we cap unconditionally rather than trust every wrapper
     * on the Services bus to be well-behaved.
     */
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Nullable
    private static ProcessHandler extractProcessHandler(@Nullable Object obj) {
        return extractProcessHandler(obj, 0);
    }

    @Nullable
    private static ProcessHandler extractProcessHandler(@Nullable Object obj, int depth) {
        if (obj == null || depth >= MAX_UNWRAP_DEPTH) return null;

        if (obj instanceof ProcessHandler handler) {
            return handler;
        }

        if (obj instanceof RunContentDescriptor desc) {
            return desc.getProcessHandler();
        }

        if (obj instanceof RunDashboardRunConfigurationNode node) {
            RunContentDescriptor desc = node.getDescriptor();
            if (desc != null) return desc.getProcessHandler();
        }
        if (obj instanceof javax.swing.tree.DefaultMutableTreeNode mutable) {
            return extractProcessHandler(mutable.getUserObject(), depth + 1);
        }
        for (String methodName : new String[]{"getDescriptor", "getNode", "getValue", "getData"}) {
            Object result = tryInvokeMethod(obj, methodName);
            ProcessHandler handler = extractProcessHandler(result, depth + 1);
            if (handler != null) return handler;
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

    /**
     * Extract our Tomcat config from a Run-widget action event.
     *
     * <p>Actions contributed to {@code RunToolbarProcessActionGroup} / {@code
     * RunToolbarProcessMainActionGroup} don't receive {@link CommonDataKeys#NAVIGATABLE}
     * or {@link PlatformCoreDataKeys#SELECTED_ITEMS} — the run widget publishes
     * its active slot state under {@link RunToolbarData#RUN_TOOLBAR_DATA_KEY}
     * instead. That key carries the {@link RunnerAndConfigurationSettings} bound
     * to the slot, which is the config the user clicked Restart against.
     */
    @Nullable
    private static TomcatRunConfiguration extractFromRunToolbar(@NotNull AnActionEvent e) {
        RunToolbarData runData = e.getData(RunToolbarData.RUN_TOOLBAR_DATA_KEY);
        if (runData == null) return null;
        RunnerAndConfigurationSettings settings = runData.getConfiguration();
        if (settings == null) return null;
        RunConfiguration rc = settings.getConfiguration();
        return rc instanceof TomcatRunConfiguration tomcat ? tomcat : null;
    }

    /**
     * Extract the {@link ProcessHandler} bound to the Run-widget slot's active
     * {@link ExecutionEnvironment}. Preferred over {@link RunContentManager}
     * enumeration because it identifies <em>this</em> slot's handler rather than
     * picking "any matching running Tomcat", which breaks when the user runs
     * the same config twice with Allow parallel run.
     */
    @Nullable
    private static ProcessHandler extractProcessHandlerFromRunToolbar(@NotNull AnActionEvent e) {
        RunToolbarData runData = e.getData(RunToolbarData.RUN_TOOLBAR_DATA_KEY);
        if (runData == null) return null;
        ExecutionEnvironment env = runData.getEnvironment();
        if (env == null) return null;
        RunContentDescriptor descriptor = env.getContentToReuse();
        if (descriptor != null) {
            ProcessHandler handler = descriptor.getProcessHandler();
            if (handler != null) return handler;
        }
        // Fall back to the RunContentManager lookup keyed by the slot's config —
        // needed when getContentToReuse() hasn't been wired (happens on the
        // very first launch after the slot is seeded).
        Project project = e.getProject();
        RunnerAndConfigurationSettings settings = runData.getConfiguration();
        if (project == null || settings == null) return null;
        for (RunContentDescriptor d : RunContentManager.getInstance(project).getAllDescriptors()) {
            ProcessHandler handler = d.getProcessHandler();
            if (handler == null || handler.isProcessTerminated()) continue;
            if (handler instanceof TomcatProcessHandler th
                    && th.getConfiguration() != null
                    && th.getConfiguration().equals(settings.getConfiguration())) {
                return handler;
            }
        }
        return null;
    }
}
