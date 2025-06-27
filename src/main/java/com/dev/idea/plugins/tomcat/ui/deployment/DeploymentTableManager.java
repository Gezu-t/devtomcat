package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Professional Deployment Table Manager
 * Handles all table-related operations for deployment artifacts
 *
 * Single Responsibility: Managing the deployment artifacts table
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentTableManager {

    private JBTable deploymentTable;
    private DefaultTableModel tableModel;
    private final List<DeploymentArtifact> deployments = new ArrayList<>();

    // Table configuration constants
    private static final String[] COLUMN_NAMES = {"Artifact", "Type", "Server Path"};
    private static final int ROW_HEIGHT = 24;
    private static final Color GRID_COLOR = new Color(60, 63, 65);

    public DeploymentTableManager() {
        initializeTable();
        System.out.println("DevTomcat: DeploymentTableManager initialized");
    }

    /**
     * Initialize table components
     */
    private void initializeTable() {
        // Create table model
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Ultimate doesn't allow inline editing
            }
        };

        // Create table
        deploymentTable = new JBTable(tableModel);
        deploymentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploymentTable.setRowHeight(ROW_HEIGHT);
        deploymentTable.setShowGrid(true);
        deploymentTable.setGridColor(GRID_COLOR);

        // Set column widths
        configureColumnWidths();

        System.out.println("DevTomcat: Deployment table created with " + COLUMN_NAMES.length + " columns");
    }

    /**
     * Configure column widths to match Ultimate
     */
    private void configureColumnWidths() {
        if (deploymentTable.getColumnModel().getColumnCount() >= 3) {
            deploymentTable.getColumnModel().getColumn(0).setPreferredWidth(200);
            deploymentTable.getColumnModel().getColumn(1).setPreferredWidth(120);
            deploymentTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        }
    }

    /**
     * Add deployment to table
     */
    public void addDeployment(@NotNull DeploymentArtifact deployment) {
        try {
            // Add to internal list
            deployments.add(deployment);

            // Add to table model
            Object[] rowData = {
                    deployment.getDisplayName(),
                    deployment.getType(),
                    deployment.getServerPath()
            };
            tableModel.addRow(rowData);

            // Update UI
            SwingUtilities.invokeLater(() -> {
                tableModel.fireTableDataChanged();
                deploymentTable.revalidate();
                deploymentTable.repaint();

                // Select and scroll to new row
                int newRow = tableModel.getRowCount() - 1;
                if (newRow >= 0) {
                    deploymentTable.setRowSelectionInterval(newRow, newRow);
                    deploymentTable.scrollRectToVisible(
                            deploymentTable.getCellRect(newRow, 0, true)
                    );
                }
            });

            System.out.println("DevTomcat: Added deployment: " + deployment.getDisplayName());

        } catch (Exception e) {
            System.err.println("DevTomcat: Error adding deployment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove selected deployment
     */
    public void removeSelectedDeployment() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            DeploymentArtifact deployment = deployments.get(selectedRow);
            deployments.remove(selectedRow);
            tableModel.removeRow(selectedRow);

            // Update selection
            updateSelectionAfterRemoval(selectedRow);

            System.out.println("DevTomcat: Removed deployment: " + deployment.getDisplayName());
        }
    }

    /**
     * Get selected deployment
     */
    @Nullable
    public DeploymentArtifact getSelectedDeployment() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            return deployments.get(selectedRow);
        }
        return null;
    }

    /**
     * Update deployment at selected row
     */
    public void updateSelectedDeployment(@NotNull DeploymentArtifact deployment) {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            // Update internal list
            deployments.set(selectedRow, deployment);

            // Update table model
            tableModel.setValueAt(deployment.getDisplayName(), selectedRow, 0);
            tableModel.setValueAt(deployment.getType(), selectedRow, 1);
            tableModel.setValueAt(deployment.getServerPath(), selectedRow, 2);

            System.out.println("DevTomcat: Updated deployment: " + deployment.getDisplayName());
        }
    }

    /**
     * Clear all deployments
     */
    public void clearAll() {
        deployments.clear();
        tableModel.setRowCount(0);
        System.out.println("DevTomcat: Cleared all deployments");
    }

    /**
     * Get deployment table component
     */
    public JBTable getTable() {
        return deploymentTable;
    }

    /**
     * Get all deployments
     */
    public List<DeploymentArtifact> getDeployments() {
        return new ArrayList<>(deployments);
    }

    /**
     * Get deployment count
     */
    public int getDeploymentCount() {
        return deployments.size();
    }

    /**
     * Check if deployment exists by name
     */
    public boolean hasDeployment(String artifactName) {
        return deployments.stream()
                .anyMatch(d -> d.getDisplayName().equals(artifactName));
    }

    /**
     * Validate row index
     */
    private boolean isValidRow(int row) {
        return row >= 0 && row < deployments.size() && row < tableModel.getRowCount();
    }

    /**
     * Update selection after row removal
     */
    private void updateSelectionAfterRemoval(int removedRow) {
        if (!deployments.isEmpty()) {
            int newSelection = Math.min(removedRow, deployments.size() - 1);
            if (newSelection >= 0 && newSelection < tableModel.getRowCount()) {
                deploymentTable.setRowSelectionInterval(newSelection, newSelection);
            }
        }
    }

    /**
     * Force table refresh
     */
    public void refreshTable() {
        SwingUtilities.invokeLater(() -> {
            tableModel.fireTableDataChanged();
            deploymentTable.revalidate();
            deploymentTable.repaint();
        });
    }
}