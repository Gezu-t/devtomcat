package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.service.TomcatDeploymentNode;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.pom.Navigatable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.TreePath;

/**
 * Opens the selected deployment artifact's URL in the default browser.
 * Registered in the {@code ServiceViewItemPopup} group so it appears in the
 * Services tool window right-click context menu.
 */
public class OpenDeploymentInBrowserAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(OpenDeploymentInBrowserAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        TomcatDeploymentNode node = findDeploymentNode(e);
        if (node != null) {
            node.navigate(true);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TomcatDeploymentNode node = findDeploymentNode(e);
        e.getPresentation().setEnabledAndVisible(node != null && node.canNavigate());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * Attempts to find the selected TomcatDeploymentNode from the action context.
     * Tries NAVIGATABLE data key first, then falls back to inspecting the tree selection.
     */
    private static TomcatDeploymentNode findDeploymentNode(@NotNull AnActionEvent e) {
        // Primary: check if NAVIGATABLE exposes our node directly
        Navigatable navigatable = e.getData(CommonDataKeys.NAVIGATABLE);
        if (navigatable instanceof TomcatDeploymentNode node) {
            return node;
        }

        // Fallback: walk the tree selection to find wrapped TomcatDeploymentNode
        var component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT);
        if (component instanceof JTree tree) {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                return extractDeploymentNode(path.getLastPathComponent());
            }
        }
        return null;
    }

    /**
     * Extracts a TomcatDeploymentNode from a tree node wrapper.
     * The Services tree wraps AbstractTreeNode instances in internal wrapper classes;
     * we walk the object graph via getUserObject() or getValue() to find our node.
     */
    private static TomcatDeploymentNode extractDeploymentNode(Object treeNode) {
        if (treeNode instanceof TomcatDeploymentNode node) {
            return node;
        }

        // ServiceView wraps nodes in DefaultMutableTreeNode or similar
        if (treeNode instanceof javax.swing.tree.DefaultMutableTreeNode mutableNode) {
            Object userObject = mutableNode.getUserObject();
            if (userObject instanceof TomcatDeploymentNode node) {
                return node;
            }
            // The userObject might be a ServiceViewItem wrapper — try extracting via reflection
            return extractViaReflection(userObject);
        }

        return extractViaReflection(treeNode);
    }

    /**
     * Last-resort extraction using reflection to navigate ServiceView internal wrappers.
     * Looks for methods that return the underlying AbstractTreeNode or its value.
     */
    private static TomcatDeploymentNode extractViaReflection(Object wrapper) {
        if (wrapper == null) return null;

        LOG.debug("Reflection fallback for ServiceView wrapper: " + wrapper.getClass().getName());
        try {
            // Try common accessor patterns used by ServiceView internals
            for (String methodName : new String[]{"getNode", "getValue", "getData", "getContent"}) {
                try {
                    var method = wrapper.getClass().getMethod(methodName);
                    Object result = method.invoke(wrapper);
                    if (result instanceof TomcatDeploymentNode node) {
                        return node;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try next method name
                }
            }
        } catch (Exception e) {
            LOG.debug("Reflection extraction failed for " + wrapper.getClass().getName(), e);
        }
        return null;
    }
}
