/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Startup/Connection configuration tab - Matches Ultimate exactly
 */

package com.dev.idea.plugins.tomcat.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Startup/Connection configuration tab - matches Ultimate interface exactly
 * Simple, clean layout focusing on the essential configuration elements
 */
public class StartupConnectionTab extends JPanel {

    private final Project project;

    // Startup script section
    private TextFieldWithBrowseButton startupScriptField;
    private JCheckBox useDefaultStartupCheckBox;

    // Shutdown script section
    private TextFieldWithBrowseButton shutdownScriptField;
    private JCheckBox useDefaultShutdownCheckBox;

    // Environment variables section
    private JBTable environmentTable;
    private DefaultTableModel envTableModel;
    private JButton addEnvButton;
    private JButton removeEnvButton;
    private JButton editEnvButton;
    private JButton importEnvButton;
    private JCheckBox passParentEnvsCheckBox;

    public StartupConnectionTab(@NotNull Project project, TomcatRunConfiguration configuration) {
        this.project = project;
        initializeUI();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(15));

        // Create main panel with GridBagLayout for precise positioning
        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(10, 0, 10, 0);

        // Startup script section
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        mainPanel.add(createStartupScriptSection(), gbc);

        // Shutdown script section
        gbc.gridy = 1;
        mainPanel.add(createShutdownScriptSection(), gbc);

        // Environment variables section
        gbc.gridy = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        mainPanel.add(createEnvironmentVariablesSection(), gbc);

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Create startup script section - matches Ultimate exactly
     */
    private JPanel createStartupScriptSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2);

        // Label
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Startup script:"), gbc);

        // Text field
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 15, 2, 10);
        startupScriptField = new TextFieldWithBrowseButton();
        startupScriptField.setText(getDefaultStartupScript());
        startupScriptField.setEnabled(false);
        startupScriptField.addBrowseFolderListener(
                "Select Startup Script",
                "Choose a custom startup script for Tomcat",
                project,
                createScriptFileDescriptor()
        );
        panel.add(startupScriptField, gbc);

        // Browse button (already included in TextFieldWithBrowseButton)
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2, 0, 2, 10);
        JButton browseButton = new JButton("...");
        browseButton.setPreferredSize(new Dimension(30, 25));
        browseButton.setEnabled(false);
        panel.add(browseButton, gbc);

        // Use default checkbox
        gbc.gridx = 3; gbc.insets = JBUI.insets(2);
        useDefaultStartupCheckBox = new JCheckBox("Use default", true);
        useDefaultStartupCheckBox.addActionListener(e -> {
            updateStartupScriptState();
            browseButton.setEnabled(!useDefaultStartupCheckBox.isSelected());
        });
        panel.add(useDefaultStartupCheckBox, gbc);

        return panel;
    }

    /**
     * Create shutdown script section - matches Ultimate exactly
     */
    private JPanel createShutdownScriptSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2);

        // Label
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Shutdown script:"), gbc);

        // Text field
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 15, 2, 10);
        shutdownScriptField = new TextFieldWithBrowseButton();
        shutdownScriptField.setText(getDefaultShutdownScript());
        shutdownScriptField.setEnabled(false);
        shutdownScriptField.addBrowseFolderListener(
                "Select Shutdown Script",
                "Choose a custom shutdown script for Tomcat",
                project,
                createScriptFileDescriptor()
        );
        panel.add(shutdownScriptField, gbc);

        // Browse button
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2, 0, 2, 10);
        JButton browseButton = new JButton("...");
        browseButton.setPreferredSize(new Dimension(30, 25));
        browseButton.setEnabled(false);
        panel.add(browseButton, gbc);

        // Use default checkbox
        gbc.gridx = 3; gbc.insets = JBUI.insets(2);
        useDefaultShutdownCheckBox = new JCheckBox("Use default", true);
        useDefaultShutdownCheckBox.addActionListener(e -> {
            updateShutdownScriptState();
            browseButton.setEnabled(!useDefaultShutdownCheckBox.isSelected());
        });
        panel.add(useDefaultShutdownCheckBox, gbc);

        return panel;
    }

    /**
     * Create environment variables section - matches Ultimate exactly
     */
    private JPanel createEnvironmentVariablesSection() {
        JPanel panel = new JPanel(new BorderLayout());

        // Section title
        JLabel titleLabel = new JLabel("Environment Variables");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Center panel with checkbox and table
        JPanel centerPanel = new JPanel(new BorderLayout());

        // Pass environment variables checkbox
        passParentEnvsCheckBox = new JCheckBox("Pass environment variables", true);
        passParentEnvsCheckBox.setBorder(JBUI.Borders.emptyBottom(10));
        centerPanel.add(passParentEnvsCheckBox, BorderLayout.NORTH);

        // Table panel
        JPanel tablePanel = new JPanel(new BorderLayout());

        // Create simple 2-column table like Ultimate
        String[] columnNames = {"Name", "Value"};
        envTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Read-only table, use dialogs for editing
            }
        };

        environmentTable = new JBTable(envTableModel);
        environmentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        environmentTable.setRowHeight(25);
        environmentTable.getColumnModel().getColumn(0).setPreferredWidth(200); // Name
        environmentTable.getColumnModel().getColumn(1).setPreferredWidth(400); // Value
        environmentTable.getSelectionModel().addListSelectionListener(e -> updateEnvButtonStates());

        // Show "No variables" when empty
        JScrollPane envScrollPane = new JScrollPane(environmentTable);
        envScrollPane.setPreferredSize(new Dimension(0, 200));
        envScrollPane.setBorder(BorderFactory.createLoweredBevelBorder());
        tablePanel.add(envScrollPane, BorderLayout.CENTER);

        // Buttons panel - horizontal layout like Ultimate
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 5));

        addEnvButton = new JButton("+");
        removeEnvButton = new JButton("-");
        editEnvButton = new JButton("Edit");
        importEnvButton = new JButton("Import");

        Dimension buttonSize = new Dimension(30, 25);
        addEnvButton.setPreferredSize(buttonSize);
        removeEnvButton.setPreferredSize(buttonSize);
        editEnvButton.setPreferredSize(new Dimension(50, 25));
        importEnvButton.setPreferredSize(new Dimension(60, 25));

        addEnvButton.setToolTipText("Add environment variable");
        removeEnvButton.setToolTipText("Remove selected variable");
        editEnvButton.setToolTipText("Edit selected variable");
        importEnvButton.setToolTipText("Import system variables");

        addEnvButton.addActionListener(e -> addEnvironmentVariable());
        removeEnvButton.addActionListener(e -> removeEnvironmentVariable());
        editEnvButton.addActionListener(e -> editEnvironmentVariable());
        importEnvButton.addActionListener(e -> importSystemEnvironmentVariables());

        buttonsPanel.add(addEnvButton);
        buttonsPanel.add(removeEnvButton);
        buttonsPanel.add(editEnvButton);
        buttonsPanel.add(importEnvButton);

        tablePanel.add(buttonsPanel, BorderLayout.SOUTH);
        centerPanel.add(tablePanel, BorderLayout.CENTER);
        panel.add(centerPanel, BorderLayout.CENTER);

        updateEnvButtonStates();
        return panel;
    }

    /**
     * Add new environment variable
     */
    private void addEnvironmentVariable() {
        EnvironmentVariableDialog dialog = new EnvironmentVariableDialog(project, null, null);
        if (dialog.showAndGet()) {
            String[] result = dialog.getEnvironmentVariable();
            envTableModel.addRow(new Object[]{result[0], result[1]});
            updateEnvButtonStates();
        }
    }

    /**
     * Remove selected environment variable
     */
    private void removeEnvironmentVariable() {
        int selectedRow = environmentTable.getSelectedRow();
        if (selectedRow >= 0) {
            String varName = (String) envTableModel.getValueAt(selectedRow, 0);
            int result = JOptionPane.showConfirmDialog(this,
                    "Remove environment variable '" + varName + "'?",
                    "Remove Environment Variable",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                envTableModel.removeRow(selectedRow);
                updateEnvButtonStates();
            }
        }
    }

    /**
     * Edit selected environment variable
     */
    private void editEnvironmentVariable() {
        int selectedRow = environmentTable.getSelectedRow();
        if (selectedRow >= 0) {
            String name = (String) envTableModel.getValueAt(selectedRow, 0);
            String value = (String) envTableModel.getValueAt(selectedRow, 1);

            EnvironmentVariableDialog dialog = new EnvironmentVariableDialog(project, name, value);
            if (dialog.showAndGet()) {
                String[] result = dialog.getEnvironmentVariable();
                envTableModel.setValueAt(result[0], selectedRow, 0);
                envTableModel.setValueAt(result[1], selectedRow, 1);
            }
        }
    }

    /**
     * Import system environment variables
     */
    private void importSystemEnvironmentVariables() {
        Map<String, String> systemEnv = System.getenv();
        String[] envNames = systemEnv.keySet().toArray(new String[0]);

        JList<String> envList = new JList<>(envNames);
        envList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Pre-select common Java/Tomcat variables
        java.util.List<Integer> preSelected = new java.util.ArrayList<>();
        for (int i = 0; i < envNames.length; i++) {
            String name = envNames[i];
            if (name.startsWith("JAVA_") || name.startsWith("CATALINA_") ||
                    name.equals("PATH") || name.equals("CLASSPATH")) {
                preSelected.add(i);
            }
        }
        envList.setSelectedIndices(preSelected.stream().mapToInt(i -> i).toArray());

        JBScrollPane scrollPane = new JBScrollPane(envList);
        scrollPane.setPreferredSize(new Dimension(400, 300));

        int result = JOptionPane.showConfirmDialog(this, scrollPane,
                "Select Environment Variables to Import",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            for (String selectedVar : envList.getSelectedValuesList()) {
                String value = systemEnv.get(selectedVar);
                envTableModel.addRow(new Object[]{selectedVar, value});
            }
            updateEnvButtonStates();
        }
    }

    /**
     * Create file descriptor for script selection
     */
    private FileChooserDescriptor createScriptFileDescriptor() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
        descriptor.setTitle("Select Script File");
        descriptor.setDescription("Choose a startup or shutdown script file");
        descriptor.withFileFilter(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".bat") || name.endsWith(".sh") || name.endsWith(".cmd");
        });
        return descriptor;
    }

    /**
     * Get default startup script path
     */
    private String getDefaultStartupScript() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            return "C:\\apache-tomcat-10.1.15\\bin\\catalina.bat run";
        } else {
            return "/opt/tomcat/bin/catalina.sh run";
        }
    }

    /**
     * Get default shutdown script path
     */
    private String getDefaultShutdownScript() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            return "C:\\apache-tomcat-10.1.15\\bin\\catalina.bat stop";
        } else {
            return "/opt/tomcat/bin/catalina.sh stop";
        }
    }

    /**
     * Update startup script field state
     */
    private void updateStartupScriptState() {
        boolean useDefault = useDefaultStartupCheckBox.isSelected();
        startupScriptField.setEnabled(!useDefault);
        if (useDefault) {
            startupScriptField.setText(getDefaultStartupScript());
        }
    }

    /**
     * Update shutdown script field state
     */
    private void updateShutdownScriptState() {
        boolean useDefault = useDefaultShutdownCheckBox.isSelected();
        shutdownScriptField.setEnabled(!useDefault);
        if (useDefault) {
            shutdownScriptField.setText(getDefaultShutdownScript());
        }
    }

    /**
     * Update button states
     */
    private void updateEnvButtonStates() {
        int selectedRow = environmentTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;
        removeEnvButton.setEnabled(hasSelection);
        editEnvButton.setEnabled(hasSelection);
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        try {
            // Reset scripts
            useDefaultStartupCheckBox.setSelected(true);
            useDefaultShutdownCheckBox.setSelected(true);
            updateStartupScriptState();
            updateShutdownScriptState();

            // Reset environment variables
            envTableModel.setRowCount(0);

            // Load from configuration
            if (configuration != null) {
                Map<String, String> envVars = configuration.getEnvironmentVariables();
                if (envVars != null && !envVars.isEmpty()) {
                    for (Map.Entry<String, String> entry : envVars.entrySet()) {
                        envTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                    }
                }
            }

            passParentEnvsCheckBox.setSelected(true);
            updateEnvButtonStates();

            System.out.println("DevTomcat: Reset startup/connection configuration");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error resetting startup/connection: " + e.getMessage());
        }
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            // Apply environment variables
            Map<String, String> envVars = new HashMap<>();
            for (int i = 0; i < envTableModel.getRowCount(); i++) {
                String name = (String) envTableModel.getValueAt(i, 0);
                String value = (String) envTableModel.getValueAt(i, 1);
                if (name != null && !name.trim().isEmpty()) {
                    envVars.put(name.trim(), value != null ? value.trim() : "");
                }
            }
            configuration.setEnvironmentVariables(envVars);

            System.out.println("DevTomcat: Applied startup/connection configuration with " +
                    envVars.size() + " environment variables");

        } catch (Exception e) {
            throw new ConfigurationException("Failed to apply startup/connection settings: " + e.getMessage());
        }
    }

    // Utility methods
    public int getEnvironmentVariableCount() {
        return envTableModel.getRowCount();
    }

    public boolean hasCustomStartupScript() {
        return !useDefaultStartupCheckBox.isSelected();
    }

    public boolean hasCustomShutdownScript() {
        return !useDefaultShutdownCheckBox.isSelected();
    }

    /**
     * Simple Environment Variable Dialog
     */
    private static class EnvironmentVariableDialog extends com.intellij.openapi.ui.DialogWrapper {

        private JTextField nameField;
        private JTextField valueField;
        private JComboBox<String> commonVarsCombo;

        protected EnvironmentVariableDialog(@NotNull Project project, String name, String value) {
            super(project);
            setTitle(name == null ? "Add Environment Variable" : "Edit Environment Variable");
            setSize(450, 200);
            init();

            if (name != null) {
                nameField.setText(name);
                valueField.setText(value != null ? value : "");
                commonVarsCombo.setSelectedItem("Custom Variable");
            }
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(15));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Common variables dropdown
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Common:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            commonVarsCombo = new JComboBox<>(new String[]{
                    "Custom Variable", "JAVA_OPTS", "CATALINA_OPTS", "CATALINA_HOME",
                    "CATALINA_BASE", "JAVA_HOME", "CLASSPATH", "PATH"
            });
            commonVarsCombo.addActionListener(e -> fillCommonVariable());
            panel.add(commonVarsCombo, gbc);

            // Name field
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JTextField(25);
            panel.add(nameField, gbc);

            // Value field
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Value:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            valueField = new JTextField(25);
            panel.add(valueField, gbc);

            return panel;
        }

        private void fillCommonVariable() {
            String selected = (String) commonVarsCombo.getSelectedItem();
            if (!"Custom Variable".equals(selected) && selected != null) {
                nameField.setText(selected);
                switch (selected) {
                    case "JAVA_OPTS":
                        valueField.setText("-Xmx512m -Xms256m");
                        break;
                    case "CATALINA_OPTS":
                        valueField.setText("-Dfile.encoding=UTF-8");
                        break;
                    case "CATALINA_HOME":
                        valueField.setText("C:\\apache-tomcat-10.1.15");
                        break;
                    case "JAVA_HOME":
                        valueField.setText(System.getProperty("java.home"));
                        break;
                }
            }
        }

        @Override
        protected void doOKAction() {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(getContentPane(),
                        "Variable name is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                nameField.requestFocus();
                return;
            }
            super.doOKAction();
        }

        public String[] getEnvironmentVariable() {
            return new String[]{
                    nameField.getText().trim(),
                    valueField.getText().trim()
            };
        }
    }
}