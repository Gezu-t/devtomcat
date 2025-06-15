/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Logs configuration tab - Matches Ultimate exactly
 */

package com.dev.idea.plugins.tomcat.ui;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.LogFileConfiguration;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs configuration tab - matches Ultimate interface exactly
 * Simple, clean layout with checkbox table and console options
 */
public class LogsConfigurationTab extends JPanel {

    private final Project project;

    // Logs table and model
    private JBTable logsTable;
    private DefaultTableModel tableModel;

    // Management buttons
    private JButton addButton;
    private JButton removeButton;
    private JButton editButton;

    // Bottom console options
    private JCheckBox saveConsoleOutputCheckBox;
    private JTextField saveToFileField;
    private JButton browseFileButton;
    private JCheckBox showConsoleStdOutCheckBox;
    private JCheckBox showConsoleStdErrCheckBox;

    // Log configurations storage
    private List<LogFileConfiguration> logConfigurations = new ArrayList<>();

    public LogsConfigurationTab(@NotNull Project project, TomcatRunConfiguration configuration) {
        this.project = project;
        initializeUI();
        initializeDefaultLogs();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(15));

        // Create main panel with proper Ultimate layout
        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(10, 0, 10, 0);

        // Log files section
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        mainPanel.add(createLogsSection(), gbc);

        // Console options section
        gbc.gridy = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0.0;
        mainPanel.add(createConsoleOptionsSection(), gbc);

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Initialize with default Tomcat logs matching Ultimate
     */
    private void initializeDefaultLogs() {
        // Add default Tomcat logs exactly like Ultimate shows
        logConfigurations.add(createTomcatLog("Tomcat Localhost Log", true));
        logConfigurations.add(createTomcatLog("Tomcat Catalina Log", true));
        logConfigurations.add(createTomcatLog("Tomcat Manager Log", false));
        logConfigurations.add(createTomcatLog("Tomcat Host Manager Log", false));
        logConfigurations.add(createTomcatLog("Tomcat Localhost Access Log", false));

        refreshLogsTable();
    }

    /**
     * Create a default Tomcat log configuration
     */
    private LogFileConfiguration createTomcatLog(String name, boolean active) {
        LogFileConfiguration config = new LogFileConfiguration();
        config.setAlias(name);
        config.setActive(active);
        config.setSkipContent(false);
        config.setShowAllMessages(true);

        // Set appropriate file paths
        switch (name) {
            case "Tomcat Localhost Log":
                config.setFilePath("$CATALINA_BASE/logs/localhost.$DATE.log");
                break;
            case "Tomcat Catalina Log":
                config.setFilePath("$CATALINA_BASE/logs/catalina.$DATE.log");
                break;
            case "Tomcat Manager Log":
                config.setFilePath("$CATALINA_BASE/logs/manager.$DATE.log");
                break;
            case "Tomcat Host Manager Log":
                config.setFilePath("$CATALINA_BASE/logs/host-manager.$DATE.log");
                break;
            case "Tomcat Localhost Access Log":
                config.setFilePath("$CATALINA_BASE/logs/localhost_access_log.$DATE.txt");
                break;
        }

        return config;
    }

    /**
     * Create logs section - matches Ultimate exactly
     */
    private JPanel createLogsSection() {
        JPanel panel = new JPanel(new BorderLayout());

        // Section title
        JLabel titleLabel = new JLabel("Log files to be shown in console");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Center panel with table and buttons
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(createLogsTablePanel(), BorderLayout.CENTER);
        centerPanel.add(createButtonsPanel(), BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Create the logs table - matches Ultimate exactly
     */
    private JPanel createLogsTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Create table model with 3 columns matching Ultimate
        String[] columnNames = {"Is Active", "Log File Entry", "Skip Content"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                switch (column) {
                    case 0: // Is Active
                    case 2: // Skip Content
                        return Boolean.class;
                    case 1: // Log File Entry
                        return String.class;
                    default:
                        return Object.class;
                }
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0 || column == 2; // Only checkboxes editable
            }
        };

        logsTable = new JBTable(tableModel);
        logsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logsTable.getTableHeader().setReorderingAllowed(false);
        logsTable.setRowHeight(25);

        // Set column widths to match Ultimate
        logsTable.getColumnModel().getColumn(0).setPreferredWidth(80);   // Is Active
        logsTable.getColumnModel().getColumn(1).setPreferredWidth(300);  // Log File Entry
        logsTable.getColumnModel().getColumn(2).setPreferredWidth(100);  // Skip Content

        // Add table listener for real-time updates
        tableModel.addTableModelListener(e -> {
            if (e.getColumn() >= 0 && e.getFirstRow() >= 0) {
                updateLogConfigurationFromTable(e.getFirstRow());
            }
        });

        // Add selection listener
        logsTable.getSelectionModel().addListSelectionListener(e -> updateButtonStates());

        JScrollPane scrollPane = new JScrollPane(logsTable);
        scrollPane.setPreferredSize(new Dimension(0, 200));
        scrollPane.setBorder(BorderFactory.createLoweredBevelBorder());
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create buttons panel - horizontal layout like Ultimate
     */
    private JPanel createButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 5));

        addButton = new JButton("+");
        removeButton = new JButton("-");
        editButton = new JButton("Edit");

        Dimension buttonSize = new Dimension(30, 25);
        addButton.setPreferredSize(buttonSize);
        removeButton.setPreferredSize(buttonSize);
        editButton.setPreferredSize(new Dimension(50, 25));

        addButton.setToolTipText("Add log file");
        removeButton.setToolTipText("Remove selected log file");
        editButton.setToolTipText("Edit selected log file");

        addButton.addActionListener(e -> addLogFile());
        removeButton.addActionListener(e -> removeLogFile());
        editButton.addActionListener(e -> editLogFile());

        panel.add(addButton);
        panel.add(removeButton);
        panel.add(editButton);

        updateButtonStates();
        return panel;
    }

    /**
     * Create console options section - matches Ultimate exactly
     */
    private JPanel createConsoleOptionsSection() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.emptyTop(20));

        // Save console output option
        JPanel savePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2);

        gbc.gridx = 0; gbc.gridy = 0;
        saveConsoleOutputCheckBox = new JCheckBox("Save console output to file:");
        savePanel.add(saveConsoleOutputCheckBox, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 10, 2, 5);
        saveToFileField = new JTextField();
        saveToFileField.setEnabled(false);
        savePanel.add(saveToFileField, gbc);

        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2);
        browseFileButton = new JButton("...");
        browseFileButton.setPreferredSize(new Dimension(30, 25));
        browseFileButton.setEnabled(false);
        browseFileButton.addActionListener(e -> browseSaveToFile());
        savePanel.add(browseFileButton, gbc);

        saveConsoleOutputCheckBox.addActionListener(e -> {
            boolean enabled = saveConsoleOutputCheckBox.isSelected();
            saveToFileField.setEnabled(enabled);
            browseFileButton.setEnabled(enabled);
        });

        // Console stream options
        showConsoleStdOutCheckBox = new JCheckBox(
                "Show console when a message is printed to standard output stream", false);
        showConsoleStdErrCheckBox = new JCheckBox(
                "Show console when a message is printed to standard error stream", false);

        panel.add(savePanel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(showConsoleStdOutCheckBox);
        panel.add(Box.createVerticalStrut(5));
        panel.add(showConsoleStdErrCheckBox);

        return panel;
    }

    /**
     * Add new log file
     */
    private void addLogFile() {
        LogFileConfigurationDialog dialog = new LogFileConfigurationDialog(project, null);
        if (dialog.showAndGet()) {
            LogFileConfiguration config = dialog.getLogFileConfiguration();
            logConfigurations.add(config);
            refreshLogsTable();

            // Select the new row
            int newRow = logConfigurations.size() - 1;
            logsTable.setRowSelectionInterval(newRow, newRow);
            updateButtonStates();
        }
    }

    /**
     * Remove selected log file
     */
    private void removeLogFile() {
        int selectedRow = logsTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < logConfigurations.size()) {
            LogFileConfiguration config = logConfigurations.get(selectedRow);

            int result = JOptionPane.showConfirmDialog(this,
                    "Remove log file '" + config.getAlias() + "'?",
                    "Remove Log File",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                logConfigurations.remove(selectedRow);
                refreshLogsTable();
                updateButtonStates();
            }
        }
    }

    /**
     * Edit selected log file
     */
    private void editLogFile() {
        int selectedRow = logsTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < logConfigurations.size()) {
            LogFileConfiguration config = logConfigurations.get(selectedRow);

            LogFileConfigurationDialog dialog = new LogFileConfigurationDialog(project, config);
            if (dialog.showAndGet()) {
                LogFileConfiguration updated = dialog.getLogFileConfiguration();
                logConfigurations.set(selectedRow, updated);
                refreshLogsTable();
                logsTable.setRowSelectionInterval(selectedRow, selectedRow);
            }
        }
    }

    /**
     * Browse for save file
     */
    private void browseSaveToFile() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, false, false, false, false, true);
        descriptor.setTitle("Save Console Output");
        descriptor.setDescription("Choose where to save console output");
        descriptor.withFileFilter(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".out");
        });

        VirtualFile file = FileChooser.chooseFile(descriptor, this, project, null);
        if (file != null) {
            saveToFileField.setText(file.getPath());
        }
    }

    /**
     * Update configuration from table changes
     */
    private void updateLogConfigurationFromTable(int row) {
        if (row >= 0 && row < logConfigurations.size()) {
            LogFileConfiguration config = logConfigurations.get(row);

            Boolean isActive = (Boolean) tableModel.getValueAt(row, 0);
            Boolean skipContent = (Boolean) tableModel.getValueAt(row, 2);

            if (isActive != null) {
                config.setActive(isActive);
            }
            if (skipContent != null) {
                config.setSkipContent(skipContent);
                config.setShowAllMessages(!skipContent);
            }
        }
    }

    /**
     * Refresh table from configurations
     */
    private void refreshLogsTable() {
        tableModel.setRowCount(0);

        for (LogFileConfiguration config : logConfigurations) {
            tableModel.addRow(new Object[]{
                    config.isActive(),
                    config.getAlias(),
                    config.isSkipContent()
            });
        }

        updateButtonStates();
    }

    /**
     * Update button states
     */
    private void updateButtonStates() {
        int selectedRow = logsTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeButton.setEnabled(hasSelection);
        editButton.setEnabled(hasSelection);
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        try {
            logConfigurations.clear();

            List<LogFileConfiguration> configLogs = configuration.getLogFileConfigurations();
            if (configLogs != null && !configLogs.isEmpty()) {
                logConfigurations.addAll(configLogs);
            } else {
                initializeDefaultLogs();
                return;
            }

            refreshLogsTable();

            // Reset console options
            saveConsoleOutputCheckBox.setSelected(false);
            saveToFileField.setText("");
            saveToFileField.setEnabled(false);
            browseFileButton.setEnabled(false);
            showConsoleStdOutCheckBox.setSelected(false);
            showConsoleStdErrCheckBox.setSelected(false);

            System.out.println("DevTomcat: Reset logs configuration");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error resetting logs: " + e.getMessage());
            logConfigurations.clear();
            initializeDefaultLogs();
        }
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            // Update all configurations from table
            for (int i = 0; i < Math.min(tableModel.getRowCount(), logConfigurations.size()); i++) {
                updateLogConfigurationFromTable(i);
            }

            // Copy configurations to avoid reference issues
            List<LogFileConfiguration> configLogs = new ArrayList<>();
            for (LogFileConfiguration config : logConfigurations) {
                configLogs.add(new LogFileConfiguration(config));
            }

            configuration.setLogFileConfigurations(configLogs);

            long activeCount = configLogs.stream().filter(LogFileConfiguration::isActive).count();
            System.out.println("DevTomcat: Applied " + configLogs.size() +
                    " log configurations (" + activeCount + " active)");

        } catch (Exception e) {
            throw new ConfigurationException("Failed to apply log configuration: " + e.getMessage());
        }
    }

    // Utility methods
    public int getActiveLogFileCount() {
        return (int) logConfigurations.stream().filter(LogFileConfiguration::isActive).count();
    }

    public boolean hasLogFiles() {
        return !logConfigurations.isEmpty();
    }

    /**
     * Simple Log File Configuration Dialog
     */
    private static class LogFileConfigurationDialog extends com.intellij.openapi.ui.DialogWrapper {

        private final Project project;
        private LogFileConfiguration config;

        private JTextField aliasField;
        private JTextField filePathField;
        private JButton browsePathButton;
        private JCheckBox activeCheckBox;
        private JCheckBox skipContentCheckBox;

        protected LogFileConfigurationDialog(@NotNull Project project, LogFileConfiguration existing) {
            super(project);
            this.project = project;
            this.config = existing;

            setTitle(existing == null ? "Add Log File" : "Edit Log File");
            setSize(500, 250);
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(15));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Alias field
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Alias:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            aliasField = new JTextField(25);
            panel.add(aliasField, gbc);

            // File path field
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("File Path:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.8;

            JPanel pathPanel = new JPanel(new BorderLayout());
            filePathField = new JTextField();
            browsePathButton = new JButton("Browse");
            browsePathButton.addActionListener(e -> browseLogFile());

            pathPanel.add(filePathField, BorderLayout.CENTER);
            pathPanel.add(browsePathButton, BorderLayout.EAST);
            panel.add(pathPanel, gbc);

            // Options
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
            activeCheckBox = new JCheckBox("Active (monitor this log file)", true);
            panel.add(activeCheckBox, gbc);

            gbc.gridy = 3;
            skipContentCheckBox = new JCheckBox("Skip content (show file name only)");
            panel.add(skipContentCheckBox, gbc);

            // Initialize fields
            if (config != null) {
                aliasField.setText(config.getAlias());
                filePathField.setText(config.getFilePath());
                activeCheckBox.setSelected(config.isActive());
                skipContentCheckBox.setSelected(config.isSkipContent());
            }

            return panel;
        }

        private void browseLogFile() {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
            descriptor.setTitle("Select Log File");
            descriptor.withFileFilter(file -> {
                String name = file.getName().toLowerCase();
                return name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".out");
            });

            VirtualFile file = FileChooser.chooseFile(descriptor, getContentPane(), project, null);
            if (file != null) {
                filePathField.setText(file.getPath());
            }
        }

        @Override
        protected void doOKAction() {
            String alias = aliasField.getText().trim();
            String filePath = filePathField.getText().trim();

            if (alias.isEmpty()) {
                JOptionPane.showMessageDialog(getContentPane(),
                        "Alias is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                aliasField.requestFocus();
                return;
            }

            if (filePath.isEmpty()) {
                JOptionPane.showMessageDialog(getContentPane(),
                        "File path is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                filePathField.requestFocus();
                return;
            }

            if (config == null) {
                config = new LogFileConfiguration();
            }

            config.setAlias(alias);
            config.setFilePath(filePath);
            config.setActive(activeCheckBox.isSelected());
            config.setSkipContent(skipContentCheckBox.isSelected());
            config.setShowAllMessages(!skipContentCheckBox.isSelected());

            super.doOKAction();
        }

        public LogFileConfiguration getLogFileConfiguration() {
            return config;
        }
    }
}