package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatLogFile;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.PathChooserDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
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
import com.intellij.ui.components.JBPanel;

public class LogsConfigurationTab extends JBPanel<LogsConfigurationTab> {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(LogsConfigurationTab.class);

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
        wireCheckboxConstraints();
        initializeDefaultLogs();

        if (configuration != null) {
            resetFrom(configuration);
        }
    }

    /**
     * Enforces the constraint: "Show this page" requires "Activate tool window".
     * Unchecking activateToolWindow disables and unchecks showPage.
     * Checking showPage auto-checks activateToolWindow.
     */
    private void wireCheckboxConstraints() {
        activateToolWindowCheck.addActionListener(e -> {
            if (!activateToolWindowCheck.isSelected()) {
                showPageCheck.setSelected(false);
                showPageCheck.setEnabled(false);
            } else {
                showPageCheck.setEnabled(true);
            }
        });

        showPageCheck.addActionListener(e -> {
            if (showPageCheck.isSelected() && !activateToolWindowCheck.isSelected()) {
                activateToolWindowCheck.setSelected(true);
            }
        });
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(JBUI.Borders.empty(10, 12));

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

    private void initializeDefaultLogs() {
        logRows.clear();
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_ACCESS_LOG_ID, false));
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_LOCALHOST_LOG_ID, false));
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_CATALINA_LOG_ID, false));
        logRows.add(new LogRow(false, TomcatLogFile.TOMCAT_MANAGER_LOG_ID, false));
        logRows.add(new LogRow(false, TomcatLogFile.TOMCAT_HOST_MANAGER_LOG_ID, false));
        refreshTable();
    }

    private JPanel createLogsSection() {
        JPanel panel = new JPanel(new BorderLayout());

        panel.add(new TitledSeparator("Log files to be shown in console"), BorderLayout.NORTH);

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
        tableComponent.setMinimumSize(new Dimension(320, 180));
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

        // Restore console output checkboxes
        var logFileConfig = configuration.getConfigData().getLogFileConfig();
        stdoutCheck.setSelected(logFileConfig.isShowStdoutConsole());
        stderrCheck.setSelected(logFileConfig.isShowStderrConsole());

        // Restore save-to-file state
        saveToFileCheck.setSelected(logFileConfig.isSaveConsoleToFile());
        saveToFileField.setText(logFileConfig.getSaveConsoleFilePath());
        saveToFileField.setEnabled(logFileConfig.isSaveConsoleToFile());
        saveToFileBrowse.setEnabled(logFileConfig.isSaveConsoleToFile());

        // Restore UI config checkboxes and enforce constraint
        boolean activateTW = configuration.getConfigData().getUiConfig().isActivateToolWindow();
        boolean showLogs = configuration.getConfigData().getUiConfig().isShowLogsPage();
        activateToolWindowCheck.setSelected(activateTW);
        showPageCheck.setSelected(showLogs);
        showPageCheck.setEnabled(activateTW);
    }

    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        List<String> activeLogs = new ArrayList<>();
        for (LogRow row : logRows) {
            if (row != null && row.active && row.entry != null && !row.entry.trim().isEmpty()) {
                activeLogs.add(row.entry.trim());
            }
        }

        var logFileConfig = configuration.getConfigData().getLogFileConfig();
        logFileConfig.setLogFiles(activeLogs);
        logFileConfig.setShowStdoutConsole(stdoutCheck.isSelected());
        logFileConfig.setShowStderrConsole(stderrCheck.isSelected());
        logFileConfig.setSaveConsoleToFile(saveToFileCheck.isSelected());
        logFileConfig.setSaveConsoleFilePath(saveToFileField.getText().trim());

        // UI checkbox constraints guarantee showLogsPage=true implies activateToolWindow=true.
        // UiConfig setters enforce the same invariant as a model-level safety net.
        var uiConfig = configuration.getConfigData().getUiConfig();
        uiConfig.setActivateToolWindow(activateToolWindowCheck.isSelected());
        uiConfig.setShowLogsPage(showPageCheck.isSelected());
    }

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

    public boolean isConfigurationValid() {
        try {
            validateSettings();
            return true;
        } catch (ConfigurationException e) {
            return false;
        }
    }

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
