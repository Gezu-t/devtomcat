package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.*;
import java.util.List;

public class LogsConfigurationTab extends JBPanel<LogsConfigurationTab> {

    private static final Logger LOG =
            Logger.getInstance(LogsConfigurationTab.class);

    private final Project project;

    private JBTable logsTable;
    private LogTableModel tableModel;

    private final List<LogRow> logRows = new ArrayList<>();

    private final JBCheckBox stdoutCheck = new JBCheckBox("Show console when a message is printed to standard output stream", true);
    private final JBCheckBox stderrCheck = new JBCheckBox("Show console when a message is printed to standard error stream", true);
    private final JBCheckBox showPageCheck = new JBCheckBox("Show this page", false);
    private final JBCheckBox activateToolWindowCheck = new JBCheckBox("Activate tool window", true);
    private final JBCheckBox saveToFileCheck = new JBCheckBox("Save console output to file:");
    private final TextFieldWithBrowseButton saveToFileField;

    public LogsConfigurationTab(@NotNull Project project, @Nullable TomcatRunConfiguration configuration) {
        this.project = project;
        saveToFileField = new TextFieldWithBrowseButton();
        SafeBrowseUtil.addBrowseFolderListener(
                saveToFileField, "Save Console Output", "Choose file to save console output",
                project, new FileChooserDescriptor(true, false, false, false, false, false));

        initializeUI();
        wireCheckboxConstraints();
        initializeDefaultLogs();

        if (configuration != null) {
            resetFrom(configuration);
        }
    }

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
        content.setBorder(JBUI.Borders.empty(8, 12));

        // --- Log files table ---
        content.add(createLogsSection());

        content.add(Box.createVerticalStrut(10));

        // --- Save to file ---
        content.add(createSaveSection());

        content.add(Box.createVerticalStrut(6));

        // --- Stdout / Stderr ---
        JPanel stdioPanel = new JPanel();
        stdioPanel.setLayout(new BoxLayout(stdioPanel, BoxLayout.Y_AXIS));
        stdioPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        stdoutCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        stderrCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        stdioPanel.add(stdoutCheck);
        stdioPanel.add(Box.createVerticalStrut(4));
        stdioPanel.add(stderrCheck);
        content.add(stdioPanel);

        content.add(Box.createVerticalStrut(10));

        // --- Bottom options ---
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        activateToolWindowCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        showPageCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottomPanel.add(activateToolWindowCheck);
        bottomPanel.add(Box.createVerticalStrut(4));
        bottomPanel.add(showPageCheck);
        content.add(bottomPanel);

        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.CENTER);
    }

    private void initializeDefaultLogs() {
        logRows.clear();
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_CATALINA_OUT_ID, false));
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_CATALINA_LOG_ID, false));
        logRows.add(new LogRow(true, TomcatLogFile.TOMCAT_LOCALHOST_LOG_ID, false));
        logRows.add(new LogRow(false, TomcatLogFile.TOMCAT_ACCESS_LOG_ID, false));
        logRows.add(new LogRow(false, TomcatLogFile.TOMCAT_MANAGER_LOG_ID, false));
        logRows.add(new LogRow(false, TomcatLogFile.TOMCAT_HOST_MANAGER_LOG_ID, false));
        refreshTable();
    }

    /**
     * Ensures all standard Tomcat log entries are present in existing configurations.
     * New standard logs added in plugin updates are appended with their default active state.
     */
    private void mergeStandardLogs() {
        Set<String> existing = new HashSet<>();
        for (LogRow row : logRows) {
            if (row.entry != null) {
                existing.add(row.entry);
            }
        }

        String[][] standardLogs = {
                {TomcatLogFile.TOMCAT_CATALINA_OUT_ID, "true"},
                {TomcatLogFile.TOMCAT_CATALINA_LOG_ID, "true"},
                {TomcatLogFile.TOMCAT_LOCALHOST_LOG_ID, "true"},
                {TomcatLogFile.TOMCAT_ACCESS_LOG_ID, "false"},
                {TomcatLogFile.TOMCAT_MANAGER_LOG_ID, "false"},
                {TomcatLogFile.TOMCAT_HOST_MANAGER_LOG_ID, "false"},
        };

        for (String[] entry : standardLogs) {
            if (!existing.contains(entry[0])) {
                logRows.add(new LogRow(Boolean.parseBoolean(entry[1]), entry[0], false));
            }
        }
    }

    private JPanel createLogsSection() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(new TitledSeparator("Log files to be shown in console"), BorderLayout.NORTH);

        tableModel = new LogTableModel();
        logsTable = new JBTable(tableModel);
        logsTable.setShowGrid(false);
        logsTable.setRowHeight(JBUI.scale(24));
        logsTable.setAutoCreateRowSorter(false);
        logsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logsTable.getTableHeader().setReorderingAllowed(false);

        // Checkbox columns: fixed widths, centered
        configureCheckboxColumn(0, JBUI.scale(55));
        configureCheckboxColumn(2, JBUI.scale(110));

        // Center-align checkbox column headers
        DefaultTableCellRenderer centerHeaderRenderer = new DefaultTableCellRenderer();
        centerHeaderRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        logsTable.getColumnModel().getColumn(0).setHeaderRenderer(centerHeaderRenderer);
        logsTable.getColumnModel().getColumn(2).setHeaderRenderer(centerHeaderRenderer);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(logsTable)
                .setAddAction(button -> addLogFile())
                .setEditAction(button -> editSelected())
                .setRemoveAction(button -> removeSelected())
                .disableUpDownActions();

        JComponent tableComponent = decorator.createPanel();
        tableComponent.setMinimumSize(new Dimension(JBUI.scale(320), JBUI.scale(160)));
        tableComponent.setPreferredSize(new Dimension(JBUI.scale(600), JBUI.scale(200)));
        panel.add(tableComponent, BorderLayout.CENTER);
        return panel;
    }

    private void configureCheckboxColumn(int columnIndex, int width) {
        var column = logsTable.getColumnModel().getColumn(columnIndex);
        column.setPreferredWidth(width);
        column.setMinWidth(width);
        column.setMaxWidth(width);
    }

    private JPanel createSaveSection() {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(JBUI.Borders.empty(2, 0));

        panel.add(saveToFileCheck, BorderLayout.WEST);
        panel.add(saveToFileField, BorderLayout.CENTER);

        saveToFileField.setEnabled(false);
        saveToFileCheck.addActionListener(e -> saveToFileField.setEnabled(saveToFileCheck.isSelected()));

        return panel;
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
            // Select the newly added row
            int lastRow = logRows.size() - 1;
            logsTable.setRowSelectionInterval(lastRow, lastRow);
            logsTable.scrollRectToVisible(logsTable.getCellRect(lastRow, 0, true));
        }
    }

    private String promptForLogPath(String title, String currentValue) {
        return Messages.showInputDialog(
                project,
                "Enter log file name or path:",
                title,
                null,
                currentValue == null ? "" : currentValue,
                null
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
        // Select the next row (or previous if removed last)
        if (!logRows.isEmpty()) {
            int newSelection = Math.min(selected, logRows.size() - 1);
            logsTable.setRowSelectionInterval(newSelection, newSelection);
        }
    }

    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        logRows.clear();

        var logFileConfig = configuration.getConfigData().getLogFileConfig();
        List<String> configLogFiles = configuration.getLogFileConfigurations();
        if (configLogFiles != null && !configLogFiles.isEmpty()) {
            for (String path : configLogFiles) {
                if (path != null && !path.trim().isEmpty()) {
                    String trimmed = path.trim();
                    logRows.add(new LogRow(true, trimmed, logFileConfig.isSkipContent(trimmed)));
                }
            }
        }

        if (logRows.isEmpty()) {
            initializeDefaultLogs();
        } else {
            mergeStandardLogs();
            refreshTable();
        }

        // Restore console output checkboxes
        stdoutCheck.setSelected(logFileConfig.isShowStdoutConsole());
        stderrCheck.setSelected(logFileConfig.isShowStderrConsole());

        // Restore save-to-file state
        saveToFileCheck.setSelected(logFileConfig.isSaveConsoleToFile());
        saveToFileField.setText(logFileConfig.getSaveConsoleFilePath());
        saveToFileField.setEnabled(logFileConfig.isSaveConsoleToFile());

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

        // Save skipContent per log entry
        Map<String, Boolean> skipMap = new HashMap<>();
        for (LogRow row : logRows) {
            if (row != null && row.entry != null && !row.entry.trim().isEmpty()) {
                skipMap.put(row.entry.trim(), row.skipContent);
            }
        }
        logFileConfig.setSkipContentEntries(skipMap);

        logFileConfig.setShowStdoutConsole(stdoutCheck.isSelected());
        logFileConfig.setShowStderrConsole(stderrCheck.isSelected());
        logFileConfig.setSaveConsoleToFile(saveToFileCheck.isSelected());
        logFileConfig.setSaveConsoleFilePath(saveToFileField.getText().trim());

        var uiConfig = configuration.getConfigData().getUiConfig();
        uiConfig.setActivateToolWindow(activateToolWindowCheck.isSelected());
        uiConfig.setShowLogsPage(showPageCheck.isSelected());
    }

    public void validateSettings() throws ConfigurationException {
        for (LogRow row : logRows) {
            if (!row.active) continue; // skip inactive rows
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
        private final String[] columns = {"Active", "Log File Entry", "Skip Content"};

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
