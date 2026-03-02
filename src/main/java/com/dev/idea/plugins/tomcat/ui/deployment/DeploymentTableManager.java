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

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles list operations for deployment artifacts.
 */
public class DeploymentTableManager {

    private static final Logger LOG = Logger.getInstance(DeploymentTableManager.class);

    private final CollectionListModel<DeploymentArtifact> listModel;
    private final JBList<DeploymentArtifact> deploymentList;
    private final List<DeploymentArtifact> deployments = new ArrayList<>();

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
                    append(value.getDisplayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
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
            String contextPath = !deployments.isEmpty()
                    ? deployments.get(0).getApplicationContext()
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

        for (int i = 0; i < deployments.size(); i++) {
            if (i != index && deployments.get(i).getApplicationContext().equals(newContext)) {
                return false;
            }
        }

        DeploymentArtifact deployment = deployments.get(index);
        deployment.setApplicationContext(newContext);

        if (deployment.isUsingDefaultContext()) {
            deployment.setServerPath(newContext);
        }

        fireDeploymentChanged();
        return true;
    }

    public void addDeployment(@NotNull DeploymentArtifact deployment) {
        try {
            if (deployment.getApplicationContext() == null ||
                    deployment.getApplicationContext().isEmpty()) {
                deployment.setApplicationContext(ContextPathUtils.generateContextPath(deployment.getName()));
            }

            deployments.add(deployment);
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
            LOG.warn("Error adding deployment: " + e.getMessage());
        }
    }

    public void removeSelectedDeployment() {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (isValidIndex(selectedIndex)) {
            DeploymentArtifact deployment = deployments.get(selectedIndex);
            deployments.remove(selectedIndex);
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
            Collections.swap(deployments, selectedIndex, selectedIndex - 1);
            listModel.setElementAt(deployments.get(selectedIndex - 1), selectedIndex - 1);
            listModel.setElementAt(deployments.get(selectedIndex), selectedIndex);

            deploymentList.setSelectedIndex(selectedIndex - 1);
            fireDeploymentChanged();
        }
    }

    public void moveSelectedDown() {
        int selectedIndex = deploymentList.getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < deployments.size() - 1) {
            Collections.swap(deployments, selectedIndex, selectedIndex + 1);
            listModel.setElementAt(deployments.get(selectedIndex), selectedIndex);
            listModel.setElementAt(deployments.get(selectedIndex + 1), selectedIndex + 1);

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
            deployments.set(selectedIndex, deployment);
            listModel.setElementAt(deployment, selectedIndex);
            LOG.debug("Updated deployment: " + deployment.getDisplayName());
        }
    }

    public void clearAll() {
        deployments.clear();
        listModel.removeAll();
        LOG.debug("Cleared all deployments");
        fireArtifactListChanged();
    }

    public JComponent getComponent() {
        return deploymentList;
    }

    public List<DeploymentArtifact> getDeployments() {
        List<DeploymentArtifact> result = new ArrayList<>();
        for (DeploymentArtifact deployment : deployments) {
            result.add(deployment.clone());
        }
        return result;
    }

    public int getDeploymentCount() {
        return deployments.size();
    }

    public boolean hasDeployment(String artifactName) {
        return deployments.stream()
                .anyMatch(d -> d.getName().equals(artifactName));
    }

    private boolean isValidIndex(int index) {
        return index >= 0 && index < deployments.size();
    }

    private void updateSelectionAfterRemoval(int removedIndex) {
        if (!deployments.isEmpty()) {
            int newSelection = Math.min(removedIndex, deployments.size() - 1);
            if (newSelection >= 0) {
                deploymentList.setSelectedIndex(newSelection);
            }
        }
    }

    public void refreshList() {
        if (SwingUtilities.isEventDispatchThread()) {
            doRefreshList();
        } else {
            SwingUtilities.invokeLater(this::doRefreshList);
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

    public int getSelectedRow() {
        return deploymentList.getSelectedIndex();
    }

    public boolean hasSelection() {
        return deploymentList.getSelectedIndex() >= 0;
    }

    public void addAndSelectDeployment(DeploymentArtifact deployment) {
        addDeployment(deployment);
        int lastIndex = listModel.getSize() - 1;
        if (lastIndex >= 0) {
            deploymentList.setSelectedIndex(lastIndex);
        }
    }
}
