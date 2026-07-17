package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.icons.AllIcons;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles list operations for deployment artifacts.
 * Uses {@link CollectionListModel} as the single source of truth.
 */
public class DeploymentTableManager {

    private static final Logger LOG = Logger.getInstance(DeploymentTableManager.class);

    private final CollectionListModel<DeploymentArtifact> listModel;
    private final JBList<DeploymentArtifact> deploymentList;

    private static final int ROW_HEIGHT = 26;

    private Consumer<String> deploymentChangeListener;
    private Runnable artifactListChangeListener;

    public DeploymentTableManager() {
        listModel = new CollectionListModel<>();
        deploymentList = new JBList<>(listModel);
        deploymentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploymentList.setFixedCellHeight(ROW_HEIGHT);
        deploymentList.getEmptyText().setText("No artifacts configured for deployment");
        deploymentList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends DeploymentArtifact> list,
                                                 DeploymentArtifact value,
                                                 int index,
                                                 boolean selected,
                                                 boolean hasFocus) {
                if (value != null) {
                    setIcon(AllIcons.Nodes.Artifact);
                    // Format display name using colon notation (e.g. "app:war exploded")
                    String displayName = ContextPathUtils.formatArtifactDisplayName(
                            value.getDisplayName(), value.getType());
                    append(displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                }
            }
        });
        LOG.debug("DeploymentTableManager initialized with JBList");
    }

    public void setDeploymentChangeListener(Consumer<String> listener) {
        this.deploymentChangeListener = listener;
    }

    public void setArtifactListChangeListener(Runnable listener) {
        this.artifactListChangeListener = listener;
    }

    private void fireArtifactListChanged() {
        if (artifactListChangeListener != null) {
            artifactListChangeListener.run();
        }
    }

    private void fireDeploymentChanged() {
        if (deploymentChangeListener != null) {
            String contextPath = listModel.getSize() > 0
                    ? listModel.getElementAt(0).getApplicationContext()
                    : "/";
            deploymentChangeListener.accept(contextPath);
        }
    }

    public boolean updateSelectedContext(String newContext) {
        int index = deploymentList.getSelectedIndex();
        if (!isValidIndex(index)) return false;

        newContext = ContextPathUtils.normalizeContextPath(newContext);

        if (!ContextPathUtils.isValidContextPath(newContext)) {
            return false;
        }

        if (isContextPathTaken(newContext, index)) {
            return false;
        }

        DeploymentArtifact deployment = listModel.getElementAt(index);
        deployment.setApplicationContext(newContext);

        fireDeploymentChanged();
        return true;
    }

    public void addDeployment(@NotNull DeploymentArtifact deployment) {
        try {
            if (deployment.getApplicationContext() == null ||
                    deployment.getApplicationContext().isEmpty()) {
                deployment.setApplicationContext(ContextPathUtils.generateContextPath(deployment.getName()));
            }

            // Auto-adjust context path if it collides with an existing artifact
            String ctx = ContextPathUtils.normalizeContextPath(deployment.getApplicationContext());
            deployment.setApplicationContext(ctx);
            if (isContextPathTaken(ctx, -1)) {
                String base = ctx.endsWith("/") ? ctx.substring(0, ctx.length() - 1) : ctx;
                for (int suffix = 2; suffix <= 99; suffix++) {
                    // Normalize the candidate before both checking and assigning.
                    // Stored contexts are always slash-prefixed; for a root ("/")
                    // collision base is "" and the raw candidate ("-2") would never
                    // match a stored "/-2", so the taken-check would keep handing out
                    // the same normalized value to every further root-defaulting row.
                    String candidate = ContextPathUtils.normalizeContextPath(base + "-" + suffix);
                    if (!isContextPathTaken(candidate, -1)) {
                        deployment.setApplicationContext(candidate);
                        break;
                    }
                }
            }

            listModel.add(deployment);

            int lastIndex = listModel.getSize() - 1;
            if (lastIndex >= 0) {
                deploymentList.setSelectedIndex(lastIndex);
                deploymentList.ensureIndexIsVisible(lastIndex);
            }

            LOG.debug("Added deployment: " + deployment.getDisplayName() +
                    " with context: " + deployment.getApplicationContext());
            fireDeploymentChanged();
            fireArtifactListChanged();

        } catch (Exception e) {
            LOG.warn("Error adding deployment", e);
        }
    }

    public void removeSelectedDeployment() {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (isValidIndex(selectedIndex)) {
            DeploymentArtifact deployment = listModel.getElementAt(selectedIndex);
            listModel.remove(selectedIndex);

            updateSelectionAfterRemoval(selectedIndex);

            LOG.debug("Removed deployment: " + deployment.getDisplayName());
            fireDeploymentChanged();
            fireArtifactListChanged();
        }
    }

    public void moveSelectedUp() {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (selectedIndex > 0 && isValidIndex(selectedIndex)) {
            DeploymentArtifact current = listModel.getElementAt(selectedIndex);
            DeploymentArtifact above = listModel.getElementAt(selectedIndex - 1);
            listModel.setElementAt(above, selectedIndex);
            listModel.setElementAt(current, selectedIndex - 1);

            deploymentList.setSelectedIndex(selectedIndex - 1);
            fireDeploymentChanged();
        }
    }

    public void moveSelectedDown() {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < listModel.getSize() - 1) {
            DeploymentArtifact current = listModel.getElementAt(selectedIndex);
            DeploymentArtifact below = listModel.getElementAt(selectedIndex + 1);
            listModel.setElementAt(below, selectedIndex);
            listModel.setElementAt(current, selectedIndex + 1);

            deploymentList.setSelectedIndex(selectedIndex + 1);
            fireDeploymentChanged();
        }
    }

    @Nullable
    public DeploymentArtifact getSelectedDeployment() {
        return deploymentList.getSelectedValue();
    }

    public void updateSelectedDeployment(@NotNull DeploymentArtifact deployment) {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (isValidIndex(selectedIndex)) {
            listModel.setElementAt(deployment, selectedIndex);
            LOG.debug("Updated deployment: " + deployment.getDisplayName());
            // Fire deploymentChangeListener so context-path edits made through
            // the edit dialog propagate to the browser URL, matching the
            // behaviour of updateSelectedContext() for the inline field —
            // without this, a dialog edit of the context path saved cleanly but
            // the browser URL kept pointing at the pre-edit context.
            fireDeploymentChanged();
        }
    }

    public void clearAll() {
        listModel.removeAll();
        LOG.debug("Cleared all deployments");
        fireArtifactListChanged();
    }

    public JComponent getComponent() {
        return deploymentList;
    }

    public List<DeploymentArtifact> getDeployments() {
        List<DeploymentArtifact> result = new ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) {
            result.add(listModel.getElementAt(i).clone());
        }
        return result;
    }

    /**
     * Typed view of the table contents — each legacy row is adapted via
     * {@link com.dev.idea.plugins.tomcat.model.DeploymentAdapter#toTyped}.
     * Used by the configuration apply path so the {@code DeploymentConfig}
     * can store typed entries directly without an intermediate legacy list.
     */
    @NotNull
    public List<com.dev.idea.plugins.tomcat.model.Deployment> getTypedDeployments(
            @NotNull com.intellij.openapi.project.Project project) {
        List<com.dev.idea.plugins.tomcat.model.Deployment> result = new ArrayList<>(listModel.getSize());
        for (int i = 0; i < listModel.getSize(); i++) {
            result.add(com.dev.idea.plugins.tomcat.model.DeploymentAdapter
                    .toTyped(project, listModel.getElementAt(i)));
        }
        return result;
    }

    /**
     * Returns the actual {@link DeploymentArtifact} instances held by the list model.
     * Unlike {@link #getDeployments()}, these are not clones — field mutations
     * ({@code setName}, {@code setPath}) propagate directly to the UI.
     *
     * <p>Intended for {@link com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher}
     * to repair stale references in-place when modules or artifacts are renamed.
     * Call {@link #refreshList()} after mutating the returned items to repaint the UI.
     */
    @NotNull
    public List<DeploymentArtifact> getLiveDeployments() {
        List<DeploymentArtifact> result = new ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) {
            result.add(listModel.getElementAt(i));
        }
        return result;
    }

    public int getDeploymentCount() {
        return listModel.getSize();
    }

    public boolean hasDeployment(String artifactName) {
        for (int i = 0; i < listModel.getSize(); i++) {
            if (listModel.getElementAt(i).getName().equals(artifactName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidIndex(int index) {
        return index >= 0 && index < listModel.getSize();
    }

    /**
     * Checks if a context path is already used by another artifact.
     * @param contextPath the path to check
     * @param excludeIndex index to skip (use -1 when adding a new artifact)
     */
    private boolean isContextPathTaken(@NotNull String contextPath, int excludeIndex) {
        for (int i = 0; i < listModel.getSize(); i++) {
            if (i == excludeIndex) continue;
            if (contextPath.equals(listModel.getElementAt(i).getApplicationContext())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if {@code contextPath} is already used by an artifact OTHER than
     * {@code except}. Identity-based ({@code ==}) so the comparison is robust
     * to mutations of {@code except}'s own context — the dialog mutates the
     * deployment in place and validates after, so reference identity is the
     * only stable "this is the one being edited" signal we have.
     *
     * <p>Exposed package-visible so {@code DeploymentConfigurationPanel} can
     * supply the same duplicate-check to the edit dialog that the inline
     * context-path field already uses, closing the asymmetry where dialog
     * edits could create duplicate context paths that the inline field
     * would have rejected.
     */
    boolean isContextPathTakenByOthers(@NotNull String contextPath,
                                        @NotNull DeploymentArtifact except) {
        for (int i = 0; i < listModel.getSize(); i++) {
            DeploymentArtifact other = listModel.getElementAt(i);
            if (other == except) continue;
            if (contextPath.equals(other.getApplicationContext())) {
                return true;
            }
        }
        return false;
    }

    private void updateSelectionAfterRemoval(int removedIndex) {
        if (listModel.getSize() > 0) {
            int newSelection = Math.min(removedIndex, listModel.getSize() - 1);
            if (newSelection >= 0) {
                deploymentList.setSelectedIndex(newSelection);
            }
        }
    }

    public void refreshList() {
        Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            doRefreshList();
        } else {
            app.invokeLater(this::doRefreshList);
        }
    }

    private void doRefreshList() {
        int selectedIndex = deploymentList.getSelectedIndex();
        deploymentList.revalidate();
        deploymentList.repaint();
        if (selectedIndex >= 0 && selectedIndex < listModel.getSize()) {
            deploymentList.setSelectedIndex(selectedIndex);
        }
    }

    public void addAndSelectDeployment(DeploymentArtifact deployment) {
        // addDeployment already selects and scrolls to the appended row.
        addDeployment(deployment);
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < listModel.getSize()) {
            deploymentList.setSelectedIndex(index);
            deploymentList.ensureIndexIsVisible(index);
        }
    }
}
