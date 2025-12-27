/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Logs configuration tab - Simple log file management
 */

package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.PathChooserDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logs configuration tab - manages log file paths for Tomcat
 */
public class LogsConfigurationTab extends JPanel {

    private final Project project;

    private JBTable logsTable;
    private LogTableModel tableModel;

    private final List<LogRow> logRows = new ArrayList<>();

    private final JBCheckBox stdoutCheck = new JBCheckBox("Show console when a message is printed to standard output stream", true);
    private final JBCheckBox stderrCheck = new JBCheckBox("Show console when a message is printed to standard error stream", true);
    private final JBCheckBox showPageCheck = new JBCheckBox("Show this page", false);
    private final JBCheckBox activateToolWindowCheck = new JBCheckBox("Activate tool window", true);
    private final JBCheckBox saveToFileCheck = new JBCheckBox("Save console output to file:");
    private final JTextField saveToFileField = new JTextField();
    private final JButton saveToFileBrowse = new JButton("...");

    public LogsConfigurationTab(@NotNull Project project, @Nullable TomcatRunConfiguration configuration) {
        this.project = project;
        initializeUI();
        initializeDefaultLogs();

        if (configuration != null) {
            resetFrom(configuration);
        }
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10, 12));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.add(createLogsSection());
        content.add(Box.createVerticalStrut(12));
        content.add(createSaveSection());
        content.add(Box.createVerticalStrut(8));
        content.add(stdoutCheck);
        content.add(stderrCheck);
        content.add(Box.createVerticalStrut(12));
        content.add(createBottomOptions());
        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.CENTER);
    }

    /**
     * Initialize with default Tomcat logs (matching official IntelliJ Tomcat plugin)
     */
    private void initializeDefaultLogs() {
        logRows.clear();
        // Match exact order and activation from official plugin
        logRows.add(new LogRow(true, "Tomcat Localhost Access Log", false));
        logRows.add(new LogRow(true, "Tomcat Localhost Log", false));
        logRows.add(new LogRow(true, "Tomcat Catalina Log", false));
        logRows.add(new LogRow(false, "Tomcat Manager Log", false));
        logRows.add(new LogRow(false, "Tomcat Host Manager Log", false));
        refreshTable();
    }

    /**
     * Create logs section with table and buttons
     */
    private JPanel createLogsSection() {
        JPanel panel = new JPanel(new BorderLayout());

        JBLabel title = new JBLabel("Log files to be shown in console");
        title.setBorder(JBUI.Borders.emptyBottom(6));
        panel.add(title, BorderLayout.NORTH);

        tableModel = new LogTableModel();
        logsTable = new JBTable(tableModel);
        logsTable.setShowGrid(false);
        logsTable.setRowHeight(24);
        logsTable.setAutoCreateRowSorter(false);
        logsTable.getColumnModel().getColumn(0).setMaxWidth(70);
        logsTable.getColumnModel().getColumn(2).setMaxWidth(100);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(logsTable)
                .setAddAction(button -> addLogFile())
                .setEditAction(button -> editSelected())
                .setRemoveAction(button -> removeSelected())
                .disableUpDownActions();

        JComponent tableComponent = decorator.createPanel();
        tableComponent.setPreferredSize(new Dimension(600, 220));
        panel.add(tableComponent, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createSaveSection() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(saveToFileCheck, BorderLayout.WEST);

        JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
        pathPanel.add(saveToFileField, BorderLayout.CENTER);
        saveToFileBrowse.setMargin(JBUI.insets(2, 8));
        saveToFileBrowse.addActionListener(e -> openFileChooser());
        pathPanel.add(saveToFileBrowse, BorderLayout.EAST);

        panel.add(pathPanel, BorderLayout.CENTER);
        panel.setBorder(JBUI.Borders.empty(4, 0, 0, 0));
        saveToFileField.setEnabled(false);
        saveToFileBrowse.setEnabled(false);
        saveToFileCheck.addActionListener(e -> {
            boolean enabled = saveToFileCheck.isSelected();
            saveToFileField.setEnabled(enabled);
            saveToFileBrowse.setEnabled(enabled);
        });
        return panel;
    }

    private JPanel createBottomOptions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        panel.add(showPageCheck);
        panel.add(activateToolWindowCheck);
        return panel;
    }

    private void openFileChooser() {
        PathChooserDialog dialog = FileChooserFactory.getInstance()
                .createPathChooser(FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor(), project, this);
        dialog.choose(null, (chosen) -> {
            if (!chosen.isEmpty()) {
                VirtualFile vf = chosen.get(0);
                if (vf != null) {
                    saveToFileField.setText(vf.getPath());
                }
            }
        });
    }

    private void refreshTable() {
        if (tableModel != null) {
            tableModel.fireTableDataChanged();
        }
    }

    /**
     * Add a new log file path
     */
    private void addLogFile() {
        String path = promptForLogPath("Add Log File", null);

        if (path != null && !path.trim().isEmpty()) {
            String normalized = path.trim();
            logRows.add(new LogRow(true, normalized, false));
            refreshTable();
        }
    }

    private String promptForLogPath(String title, String currentValue) {
        return (String) JOptionPane.showInputDialog(
                this,
                "Enter log file name or path:",
                title,
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                currentValue == null ? "" : currentValue
        );
    }

    private void editSelected() {
        int selected = logsTable.getSelectedRow();
        if (selected < 0) return;
        selected = logsTable.convertRowIndexToModel(selected);

        LogRow row = logRows.get(selected);
        String newPath = promptForLogPath("Edit Log File", row.entry);
        if (newPath != null && !newPath.trim().isEmpty()) {
            row.entry = newPath.trim();
            refreshTable();
            logsTable.setRowSelectionInterval(selected, selected);
        }
    }

    private void removeSelected() {
        int selected = logsTable.getSelectedRow();
        if (selected < 0) return;
        selected = logsTable.convertRowIndexToModel(selected);
        logRows.remove(selected);
        refreshTable();
    }

    /**
     * Reset UI from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        logRows.clear();

        List<String> configLogFiles = configuration.getLogFileConfigurations();
        if (configLogFiles != null && !configLogFiles.isEmpty()) {
            for (String path : configLogFiles) {
                if (path != null && !path.trim().isEmpty()) {
                    logRows.add(new LogRow(true, path.trim(), false));
                }
            }
        }

        if (logRows.isEmpty()) {
            initializeDefaultLogs();
        } else {
            refreshTable();
        }

        activateToolWindowCheck.setSelected(configuration.getConfigData().getUiConfig().isActivateToolWindow());
        showPageCheck.setSelected(false);
    }

    /**
     * Apply UI settings to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        List<String> activeLogs = new ArrayList<>();
        for (LogRow row : logRows) {
            if (row != null && row.active && row.entry != null && !row.entry.trim().isEmpty()) {
                activeLogs.add(row.entry.trim());
            }
        }

        configuration.getConfigData().getLogFileConfig().setLogFiles(activeLogs);
        configuration.getConfigData().getUiConfig().setActivateToolWindow(activateToolWindowCheck.isSelected());
        configuration.getConfigData().getUiConfig().setFocusToolWindow(activateToolWindowCheck.isSelected());
    }

    /**
     * Validate settings
     */
    public void validateSettings() throws ConfigurationException {
        if (logRows == null) {
            return;
        }

        for (LogRow row : logRows) {
            if (row.entry == null || row.entry.trim().isEmpty()) {
                throw new ConfigurationException("Log file path cannot be empty");
            }
            if (row.entry.length() > 1024) {
                throw new ConfigurationException("Log file path too long: " + row.entry);
            }
        }
    }

    /**
     * Check if settings are valid
     */
    public boolean isValid() {
        try {
            validateSettings();
            return true;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    /**
     * Get current log files
     */
    @NotNull
    public List<String> getLogFiles() {
        List<String> files = new ArrayList<>();
        for (LogRow row : logRows) {
            if (row != null && row.entry != null) {
                files.add(row.entry);
            }
        }
        return files;
    }

    /**
     * Set log files
     */
    public void setLogFiles(@NotNull List<String> logFiles) {
        this.logRows.clear();
        for (String path : logFiles) {
            if (path != null && !path.trim().isEmpty()) {
                this.logRows.add(new LogRow(true, path.trim(), false));
            }
        }
        refreshTable();
    }

    private static class LogRow {
        boolean active;
        String entry;
        boolean skipContent;

        LogRow(boolean active, String entry, boolean skipContent) {
            this.active = active;
            this.entry = entry;
            this.skipContent = skipContent;
        }
    }

    private class LogTableModel extends AbstractTableModel {
        private final String[] columns = {"Is Active", "Log File Entry", "Skip Content"};

        @Override
        public int getRowCount() {
            return logRows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 0 || columnIndex == 2) return Boolean.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            LogRow row = logRows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.active;
                case 1 -> row.entry;
                case 2 -> row.skipContent;
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            LogRow row = logRows.get(rowIndex);
            switch (columnIndex) {
                case 0 -> row.active = Objects.equals(aValue, Boolean.TRUE);
                case 1 -> row.entry = aValue != null ? aValue.toString() : "";
                case 2 -> row.skipContent = Objects.equals(aValue, Boolean.TRUE);
                default -> {
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
