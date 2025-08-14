package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Professional Deployment Table Manager - Corrected Version
 * Handles all table-related operations for deployment artifacts
 *
 * Works with the existing DeploymentArtifact model structure
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentTableManager {

    private JBTable deploymentTable;
    private DefaultTableModel tableModel;
    private final List<DeploymentArtifact> deployments = new ArrayList<>();

    // Column definitions - 4 columns matching Ultimate
    private static final String[] COLUMN_NAMES = {
            "",                    // Deploy checkbox
            "Artifact",           // Artifact name
            "Type",              // Artifact type
            "Application Context" // Context path (editable)
    };

    private static final int COL_DEPLOY = 0;
    private static final int COL_ARTIFACT = 1;
    private static final int COL_TYPE = 2;
    private static final int COL_CONTEXT = 3;

    // UI Constants
    private static final int ROW_HEIGHT = 26;
    private static final Color GRID_COLOR = new JBColor(
            new Color(200, 200, 200),
            new Color(60, 63, 65)
    );
    private static final Color ERROR_COLOR = JBColor.RED;

    public DeploymentTableManager() {
        initializeTable();
        System.out.println("DevTomcat: DeploymentTableManager initialized with 4 columns");
    }

    /**
     * Initialize table components
     */
    private void initializeTable() {
        // Create table model
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
                // Deploy checkbox and Context are editable
                return column == COL_DEPLOY || column == COL_CONTEXT;
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                if (column == COL_DEPLOY && value instanceof Boolean) {
                    // Update deploy status
                    deployments.get(row).setDeployed((Boolean) value);
                    super.setValueAt(value, row, column);
                } else if (column == COL_CONTEXT && value instanceof String) {
                    // Validate and update context
                    String newContext = ((String) value).trim();
                    if (validateAndSetContext(row, newContext)) {
                        super.setValueAt(newContext, row, column);
                    } else {
                        // Revert to original value
                        super.setValueAt(deployments.get(row).getApplicationContext(), row, column);
                    }
                } else {
                    super.setValueAt(value, row, column);
                }
            }
        };

        // Create table
        deploymentTable = new JBTable(tableModel);
        deploymentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploymentTable.setRowHeight(ROW_HEIGHT);
        deploymentTable.setShowGrid(true);
        deploymentTable.setGridColor(GRID_COLOR);
        deploymentTable.setIntercellSpacing(new Dimension(1, 1));

        // Configure columns
        configureColumns();

        // Add custom cell editor for context with validation
        configureContextEditor();

        // Add double-click handler for editing
        deploymentTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = deploymentTable.rowAtPoint(e.getPoint());
                    int col = deploymentTable.columnAtPoint(e.getPoint());
                    if (row >= 0 && col == COL_CONTEXT) {
                        deploymentTable.editCellAt(row, col);
                    }
                }
            }
        });
    }

    /**
     * Configure column widths and appearance
     */
    private void configureColumns() {
        // Deploy checkbox column
        TableColumn deployCol = deploymentTable.getColumnModel().getColumn(COL_DEPLOY);
        deployCol.setPreferredWidth(40);
        deployCol.setMaxWidth(40);
        deployCol.setMinWidth(40);
        deployCol.setResizable(false);

        // Artifact name column
        TableColumn artifactCol = deploymentTable.getColumnModel().getColumn(COL_ARTIFACT);
        artifactCol.setPreferredWidth(250);
        artifactCol.setMinWidth(150);

        // Type column
        TableColumn typeCol = deploymentTable.getColumnModel().getColumn(COL_TYPE);
        typeCol.setPreferredWidth(120);
        typeCol.setMinWidth(80);
        typeCol.setMaxWidth(150);

        // Context column
        TableColumn contextCol = deploymentTable.getColumnModel().getColumn(COL_CONTEXT);
        contextCol.setPreferredWidth(200);
        contextCol.setMinWidth(100);

        // Center checkbox
        deployCol.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                JCheckBox checkBox = new JCheckBox();
                checkBox.setSelected((Boolean) value);
                checkBox.setHorizontalAlignment(JLabel.CENTER);
                if (isSelected) {
                    checkBox.setBackground(table.getSelectionBackground());
                }
                return checkBox;
            }
        });

        // Custom renderer for context to show validation state
        contextCol.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                );

                String context = (String) value;
                if (!isValidContextPath(context)) {
                    c.setForeground(ERROR_COLOR);
                    ((JComponent) c).setToolTipText("Invalid context path");
                } else {
                    c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                    ((JComponent) c).setToolTipText("Context path: " + context);
                }

                return c;
            }
        });
    }

    /**
     * Configure context editor with validation
     */
    private void configureContextEditor() {
        JTextField contextField = new JTextField();

        // Add input verifier for validation
        contextField.setInputVerifier(new InputVerifier() {
            @Override
            public boolean verify(JComponent input) {
                String text = ((JTextField) input).getText().trim();
                return isValidContextPath(text);
            }
        });

        DefaultCellEditor contextEditor = new DefaultCellEditor(contextField) {
            @Override
            public boolean stopCellEditing() {
                String value = (String) getCellEditorValue();
                if (!isValidContextPath(value)) {
                    JOptionPane.showMessageDialog(
                            deploymentTable,
                            "Invalid context path. Must start with / and contain valid URL characters.",
                            "Invalid Context",
                            JOptionPane.ERROR_MESSAGE
                    );
                    return false;
                }
                return super.stopCellEditing();
            }
        };

        deploymentTable.getColumnModel().getColumn(COL_CONTEXT).setCellEditor(contextEditor);
    }

    /**
     * Validate and set context path
     */
    private boolean validateAndSetContext(int row, String newContext) {
        // Normalize context
        newContext = normalizeContextPath(newContext);

        // Validate
        if (!isValidContextPath(newContext)) {
            return false;
        }

        // Check for duplicates
        for (int i = 0; i < deployments.size(); i++) {
            if (i != row && deployments.get(i).getApplicationContext().equals(newContext)) {
                JOptionPane.showMessageDialog(
                        deploymentTable,
                        "Context path '" + newContext + "' is already used by another artifact.",
                        "Duplicate Context",
                        JOptionPane.ERROR_MESSAGE
                );
                return false;
            }
        }

        // Update deployment
        DeploymentArtifact deployment = deployments.get(row);
        deployment.setApplicationContext(newContext);

        // If using default context, update server path too
        if (deployment.isUsingDefaultContext()) {
            deployment.setServerPath(newContext);
        }

        return true;
    }

    /**
     * Validate context path
     */
    private boolean isValidContextPath(String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }

        if (!context.equals("/") && !context.startsWith("/")) {
            return false;
        }

        // No spaces allowed
        if (context.contains(" ")) {
            return false;
        }

        // No double slashes
        if (context.contains("//")) {
            return false;
        }

        // Valid URL characters only
        return context.matches("^/[a-zA-Z0-9\\-_.~!$&'()*+,;=:@/]*$");
    }

    /**
     * Add deployment to table
     */
    public void addDeployment(@NotNull DeploymentArtifact deployment) {
        try {
            // Ensure valid context
            if (deployment.getApplicationContext() == null ||
                    deployment.getApplicationContext().isEmpty()) {
                deployment.setApplicationContext(generateDefaultContext(deployment.getName()));
            }

            // Add to internal list
            deployments.add(deployment);

            // Add to table model
            Object[] rowData = {
                    deployment.isDeployed(),
                    deployment.getDisplayName(),
                    deployment.getType(),
                    deployment.getApplicationContext()
            };
            tableModel.addRow(rowData);

            // Update UI
            SwingUtilities.invokeLater(() -> {
                tableModel.fireTableDataChanged();
                deploymentTable.revalidate();
                deploymentTable.repaint();

                // Select new row
                int newRow = tableModel.getRowCount() - 1;
                if (newRow >= 0) {
                    deploymentTable.setRowSelectionInterval(newRow, newRow);
                    deploymentTable.scrollRectToVisible(
                            deploymentTable.getCellRect(newRow, 0, true)
                    );
                }
            });

            System.out.println("DevTomcat: Added deployment: " + deployment.getDisplayName() +
                    " with context: " + deployment.getApplicationContext());

        } catch (Exception e) {
            System.err.println("DevTomcat: Error adding deployment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Generate default context from artifact name
     */
    private String generateDefaultContext(String artifactName) {
        // Remove common suffixes
        String context = artifactName
                .replaceAll(":(war|jar)( exploded)?$", "")
                .replaceAll("\\.(war|jar)$", "");

        // Special case for ROOT
        if (context.equalsIgnoreCase("ROOT")) {
            return "/";
        }

        // Convert to URL-safe
        context = context
                .replaceAll("[^a-zA-Z0-9\\-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();

        return "/" + context;
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
     * Move selected deployment up
     */
    public void moveSelectedUp() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow > 0 && isValidRow(selectedRow)) {
            // Swap in list
            DeploymentArtifact temp = deployments.get(selectedRow - 1);
            deployments.set(selectedRow - 1, deployments.get(selectedRow));
            deployments.set(selectedRow, temp);

            // Swap in table
            swapTableRows(selectedRow, selectedRow - 1);

            // Update selection
            deploymentTable.setRowSelectionInterval(selectedRow - 1, selectedRow - 1);
        }
    }

    /**
     * Move selected deployment down
     */
    public void moveSelectedDown() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < deployments.size() - 1) {
            // Swap in list
            DeploymentArtifact temp = deployments.get(selectedRow + 1);
            deployments.set(selectedRow + 1, deployments.get(selectedRow));
            deployments.set(selectedRow, temp);

            // Swap in table
            swapTableRows(selectedRow, selectedRow + 1);

            // Update selection
            deploymentTable.setRowSelectionInterval(selectedRow + 1, selectedRow + 1);
        }
    }

    /**
     * Swap two rows in the table
     */
    private void swapTableRows(int row1, int row2) {
        for (int col = 0; col < tableModel.getColumnCount(); col++) {
            Object temp = tableModel.getValueAt(row1, col);
            tableModel.setValueAt(tableModel.getValueAt(row2, col), row1, col);
            tableModel.setValueAt(temp, row2, col);
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
     * Update selected deployment
     */
    public void updateSelectedDeployment(@NotNull DeploymentArtifact deployment) {
        int selectedRow = deploymentTable.getSelectedRow();
        if (isValidRow(selectedRow)) {
            // Update internal list
            deployments.set(selectedRow, deployment);

            // Update table model
            tableModel.setValueAt(deployment.isDeployed(), selectedRow, COL_DEPLOY);
            tableModel.setValueAt(deployment.getDisplayName(), selectedRow, COL_ARTIFACT);
            tableModel.setValueAt(deployment.getType(), selectedRow, COL_TYPE);
            tableModel.setValueAt(deployment.getApplicationContext(), selectedRow, COL_CONTEXT);

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
     * Get all deployments (syncs inline edits first)
     */
    public List<DeploymentArtifact> getDeployments() {
        // Sync any pending edits
        if (deploymentTable.isEditing()) {
            deploymentTable.getCellEditor().stopCellEditing();
        }

        // Return cloned list to prevent external modification
        List<DeploymentArtifact> result = new ArrayList<>();
        for (DeploymentArtifact deployment : deployments) {
            result.add(deployment.clone());
        }
        return result;
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
                .anyMatch(d -> d.getName().equals(artifactName));
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

    /**
     * Get selected row index
     */
    public int getSelectedRow() {
        return deploymentTable.getSelectedRow();
    }

    /**
     * Check if any row is selected
     */
    public boolean hasSelection() {
        return deploymentTable.getSelectedRow() >= 0;
    }

    /**
     * Normalize context path
     */
    private String normalizeContextPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/";
        }

        path = path.trim();

        // Ensure path starts with /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // Remove trailing / except for root
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // Collapse multiple slashes
        path = path.replaceAll("/+", "/");

        return path;
    }
}