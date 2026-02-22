package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.icons.AllIcons;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles table operations for deployment artifacts.
 */
public class DeploymentTableManager {

    private static final Logger LOG = Logger.getInstance(DeploymentTableManager.class);

    private JBTable deploymentTable;
    private DefaultTableModel tableModel;
    private final List<DeploymentArtifact> deployments = new ArrayList<>();

    private static final String[] COLUMN_NAMES = {"", "Artifact", "Type", "Application Context"};

    private static final int COL_DEPLOY = 0;
    private static final int COL_ARTIFACT = 1;
    private static final int COL_TYPE = 2;
    private static final int COL_CONTEXT = 3;

    private static final int ROW_HEIGHT = 26;

    private Consumer<String> deploymentChangeListener;

    public DeploymentTableManager() {
        initializeTable();
        LOG.debug("DeploymentTableManager initialized with 4 columns");
    }

    public void setDeploymentChangeListener(Consumer<String> listener) {
        this.deploymentChangeListener = listener;
    }

    private void fireDeploymentChanged() {
        if (deploymentChangeListener != null) {
            String contextPath = !deployments.isEmpty()
                    ? deployments.get(0).getApplicationContext()
                    : "/";
            deploymentChangeListener.accept(contextPath);
        }
    }

    private void initializeTable() {
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == COL_DEPLOY) {
                    return Boolean.class;
                }
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == COL_DEPLOY;
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                if (column == COL_DEPLOY && value instanceof Boolean) {
                    deployments.get(row).setDeployed((Boolean) value);
                    super.setValueAt(value, row, column);
                } else {
                    super.setValueAt(value, row, column);
                }
            }
        };

        deploymentTable = new JBTable(tableModel);
        deploymentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploymentTable.setRowHeight(ROW_HEIGHT);
        deploymentTable.setShowGrid(false);
        deploymentTable.setIntercellSpacing(new Dimension(0, 0));
        deploymentTable.getEmptyText().setText("No artifacts configured for deployment");
        deploymentTable.setTableHeader(null);

        configureColumns();
    }

    private void configureColumns() {
        TableColumn deployCol = deploymentTable.getColumnModel().getColumn(COL_DEPLOY);
        deployCol.setPreferredWidth(0);
        deployCol.setMaxWidth(0);
        deployCol.setMinWidth(0);
        deployCol.setResizable(false);

        TableColumn artifactCol = deploymentTable.getColumnModel().getColumn(COL_ARTIFACT);
        artifactCol.setPreferredWidth(600);
        artifactCol.setMinWidth(200);

        TableColumn typeCol = deploymentTable.getColumnModel().getColumn(COL_TYPE);
        typeCol.setPreferredWidth(0);
        typeCol.setMaxWidth(0);
        typeCol.setMinWidth(0);
        typeCol.setResizable(false);

        TableColumn contextCol = deploymentTable.getColumnModel().getColumn(COL_CONTEXT);
        contextCol.setPreferredWidth(0);
        contextCol.setMaxWidth(0);
        contextCol.setMinWidth(0);
        contextCol.setResizable(false);

        artifactCol.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                label.setIcon(AllIcons.Nodes.Artifact);
                label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                return label;
            }
        });
    }

    public boolean updateSelectedContext(String newContext) {
        int row = deploymentTable.getSelectedRow();
        if (!isValidRow(row)) return false;

        newContext = ContextPathUtils.normalizeContextPath(newContext);

        if (!ContextPathUtils.isValidContextPath(newContext)) {
            return false;
        }

        for (int i = 0; i < deployments.size(); i++) {
            if (i != row && deployments.get(i).getApplicationContext().equals(newContext)) {
                return false;
            }
        }

        DeploymentArtifact deployment = deployments.get(row);
        deployment.setApplicationContext(newContext);
        tableModel.setValueAt(newContext, row, COL_CONTEXT);

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

            Object[] rowData = {
                    deployment.isDeployed(),
                    deployment.getDisplayName(),
                    deployment.getType(),
                    deployment.getApplicationContext()
            };
            tableModel.addRow(rowData);

            int newRow = tableModel.getRowCount() - 1;
            if (newRow >= 0) {
                deploymentTable.setRowSelectionInterval(newRow, newRow);
                deploymentTable.scrollRectToVisible(
                        deploymentTable.getCellRect(newRow, 0, true)
                );
            }

            LOG.debug("Added deployment: " + deployment.getDisplayName() +
                    " with context: " + deployment.getApplicationContext());
            fireDeploymentChanged();

        } catch (Exception e) {
            LOG.warn("Error adding deployment: " + e.getMessage());
        }
    }


    public void removeSelectedDeployment() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            DeploymentArtifact deployment = deployments.get(selectedRow);
            deployments.remove(selectedRow);
            tableModel.removeRow(selectedRow);

            updateSelectionAfterRemoval(selectedRow);

            LOG.debug("Removed deployment: " + deployment.getDisplayName());
            fireDeploymentChanged();
        }
    }

    public void moveSelectedUp() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow > 0 && isValidRow(selectedRow)) {
            DeploymentArtifact temp = deployments.get(selectedRow - 1);
            deployments.set(selectedRow - 1, deployments.get(selectedRow));
            deployments.set(selectedRow, temp);

            swapTableRows(selectedRow, selectedRow - 1);

            deploymentTable.setRowSelectionInterval(selectedRow - 1, selectedRow - 1);
            fireDeploymentChanged();
        }
    }

    public void moveSelectedDown() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < deployments.size() - 1) {
            DeploymentArtifact temp = deployments.get(selectedRow + 1);
            deployments.set(selectedRow + 1, deployments.get(selectedRow));
            deployments.set(selectedRow, temp);

            swapTableRows(selectedRow, selectedRow + 1);

            deploymentTable.setRowSelectionInterval(selectedRow + 1, selectedRow + 1);
            fireDeploymentChanged();
        }
    }

    private void swapTableRows(int row1, int row2) {
        for (int col = 0; col < tableModel.getColumnCount(); col++) {
            Object temp = tableModel.getValueAt(row1, col);
            tableModel.setValueAt(tableModel.getValueAt(row2, col), row1, col);
            tableModel.setValueAt(temp, row2, col);
        }
    }

    @Nullable
    public DeploymentArtifact getSelectedDeployment() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            return deployments.get(selectedRow);
        }
        return null;
    }

    public void updateSelectedDeployment(@NotNull DeploymentArtifact deployment) {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            deployments.set(selectedRow, deployment);
            tableModel.setValueAt(deployment.isDeployed(), selectedRow, COL_DEPLOY);
            tableModel.setValueAt(deployment.getDisplayName(), selectedRow, COL_ARTIFACT);
            tableModel.setValueAt(deployment.getType(), selectedRow, COL_TYPE);
            tableModel.setValueAt(deployment.getApplicationContext(), selectedRow, COL_CONTEXT);

            LOG.debug("Updated deployment: " + deployment.getDisplayName());
        }
    }

    public void clearAll() {
        deployments.clear();
        tableModel.setRowCount(0);
        LOG.debug("Cleared all deployments");
    }

    public JBTable getTable() {
        return deploymentTable;
    }

    public List<DeploymentArtifact> getDeployments() {
        if (deploymentTable.isEditing()) {
            deploymentTable.getCellEditor().stopCellEditing();
        }

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

    private boolean isValidRow(int row) {
        return row >= 0 && row < deployments.size() && row < tableModel.getRowCount();
    }

    private void updateSelectionAfterRemoval(int removedRow) {
        if (!deployments.isEmpty()) {
            int newSelection = Math.min(removedRow, deployments.size() - 1);
            if (newSelection >= 0 && newSelection < tableModel.getRowCount()) {
                deploymentTable.setRowSelectionInterval(newSelection, newSelection);
            }
        }
    }

    public void refreshTable() {
        if (SwingUtilities.isEventDispatchThread()) {
            doRefreshTable();
        } else {
            SwingUtilities.invokeLater(this::doRefreshTable);
        }
    }

    private void doRefreshTable() {
        int selectedRow = deploymentTable.getSelectedRow();
        tableModel.fireTableDataChanged();
        deploymentTable.revalidate();
        deploymentTable.repaint();
        if (selectedRow >= 0 && selectedRow < tableModel.getRowCount()) {
            deploymentTable.setRowSelectionInterval(selectedRow, selectedRow);
        }
    }

    public int getSelectedRow() {
        return deploymentTable.getSelectedRow();
    }

    public boolean hasSelection() {
        return deploymentTable.getSelectedRow() >= 0;
    }

    public void addAndSelectDeployment(DeploymentArtifact deployment) {
        addDeployment(deployment);
        int lastRowIndex = tableModel.getRowCount() - 1;
        if (lastRowIndex >= 0) {
            deploymentTable.setRowSelectionInterval(lastRowIndex, lastRowIndex);
        }
    }

}