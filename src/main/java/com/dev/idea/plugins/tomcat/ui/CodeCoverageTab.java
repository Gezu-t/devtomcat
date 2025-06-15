/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Code Coverage configuration tab - Matches Ultimate exactly
 */

package com.dev.idea.plugins.tomcat.ui;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Code Coverage configuration tab - matches Ultimate interface exactly
 * Simple package pattern management for include/exclude coverage rules
 */
public class CodeCoverageTab extends JPanel {

    private final Project project;

    // Include patterns table
    private JBTable includeTable;
    private DefaultTableModel includeTableModel;
    private JButton addIncludeButton;
    private JButton removeIncludeButton;
    private JButton editIncludeButton;

    // Exclude patterns table
    private JBTable excludeTable;
    private DefaultTableModel excludeTableModel;
    private JButton addExcludeButton;
    private JButton removeExcludeButton;
    private JButton editExcludeButton;

    // Pattern storage
    private List<String> includePatterns = new ArrayList<>();
    private List<String> excludePatterns = new ArrayList<>();

    public CodeCoverageTab(@NotNull Project project) {
        this.project = project;
        initializeUI();
        initializeDefaultPatterns();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(15));

        // Create main panel with Ultimate layout
        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(10, 0, 10, 0);

        // Include patterns section
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0; gbc.weighty = 0.5;
        mainPanel.add(createIncludePatternsSection(), gbc);

        // Exclude patterns section
        gbc.gridy = 1;
        mainPanel.add(createExcludePatternsSection(), gbc);

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Initialize with default patterns
     */
    private void initializeDefaultPatterns() {
        // Start with no patterns - matches Ultimate "No class patterns configured"
        refreshIncludeTable();
        refreshExcludeTable();
    }

    /**
     * Create include patterns section - matches Ultimate exactly
     */
    private JPanel createIncludePatternsSection() {
        JPanel panel = new JPanel(new BorderLayout());

        // Section title
        JLabel titleLabel = new JLabel("Packages and classes to include in coverage data");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Center panel with table and buttons
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(createIncludeTablePanel(), BorderLayout.CENTER);
        centerPanel.add(createIncludeButtonsPanel(), BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Create exclude patterns section - matches Ultimate exactly
     */
    private JPanel createExcludePatternsSection() {
        JPanel panel = new JPanel(new BorderLayout());

        // Section title
        JLabel titleLabel = new JLabel("Packages and classes to exclude from coverage data");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Center panel with table and buttons
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(createExcludeTablePanel(), BorderLayout.CENTER);
        centerPanel.add(createExcludeButtonsPanel(), BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Create include patterns table
     */
    private JPanel createIncludeTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Create simple single-column table
        String[] columnNames = {"Pattern"};
        includeTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        includeTable = new JBTable(includeTableModel);
        includeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        includeTable.setRowHeight(25);
        includeTable.getSelectionModel().addListSelectionListener(e -> updateIncludeButtonStates());

        // Show "No class patterns configured" when empty like Ultimate
        JScrollPane scrollPane = new JScrollPane(includeTable);
        scrollPane.setPreferredSize(new Dimension(0, 120));
        scrollPane.setBorder(BorderFactory.createLoweredBevelBorder());
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create exclude patterns table
     */
    private JPanel createExcludeTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Create simple single-column table
        String[] columnNames = {"Pattern"};
        excludeTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        excludeTable = new JBTable(excludeTableModel);
        excludeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        excludeTable.setRowHeight(25);
        excludeTable.getSelectionModel().addListSelectionListener(e -> updateExcludeButtonStates());

        JScrollPane scrollPane = new JScrollPane(excludeTable);
        scrollPane.setPreferredSize(new Dimension(0, 120));
        scrollPane.setBorder(BorderFactory.createLoweredBevelBorder());
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create include buttons panel - horizontal layout like Ultimate
     */
    private JPanel createIncludeButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 5));

        addIncludeButton = new JButton("+");
        removeIncludeButton = new JButton("-");
        editIncludeButton = new JButton("Edit");

        Dimension buttonSize = new Dimension(30, 25);
        addIncludeButton.setPreferredSize(buttonSize);
        removeIncludeButton.setPreferredSize(buttonSize);
        editIncludeButton.setPreferredSize(new Dimension(50, 25));

        addIncludeButton.setToolTipText("Add include pattern");
        removeIncludeButton.setToolTipText("Remove selected pattern");
        editIncludeButton.setToolTipText("Edit selected pattern");

        addIncludeButton.addActionListener(e -> addIncludePattern());
        removeIncludeButton.addActionListener(e -> removeIncludePattern());
        editIncludeButton.addActionListener(e -> editIncludePattern());

        panel.add(addIncludeButton);
        panel.add(removeIncludeButton);
        panel.add(editIncludeButton);

        updateIncludeButtonStates();
        return panel;
    }

    /**
     * Create exclude buttons panel - horizontal layout like Ultimate
     */
    private JPanel createExcludeButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 5));

        addExcludeButton = new JButton("+");
        removeExcludeButton = new JButton("-");
        editExcludeButton = new JButton("Edit");

        Dimension buttonSize = new Dimension(30, 25);
        addExcludeButton.setPreferredSize(buttonSize);
        removeExcludeButton.setPreferredSize(buttonSize);
        editExcludeButton.setPreferredSize(new Dimension(50, 25));

        addExcludeButton.setToolTipText("Add exclude pattern");
        removeExcludeButton.setToolTipText("Remove selected pattern");
        editExcludeButton.setToolTipText("Edit selected pattern");

        addExcludeButton.addActionListener(e -> addExcludePattern());
        removeExcludeButton.addActionListener(e -> removeExcludePattern());
        editExcludeButton.addActionListener(e -> editExcludePattern());

        panel.add(addExcludeButton);
        panel.add(removeExcludeButton);
        panel.add(editExcludeButton);

        updateExcludeButtonStates();
        return panel;
    }

    /**
     * Add include pattern
     */
    private void addIncludePattern() {
        String pattern = JOptionPane.showInputDialog(this,
                "Enter package pattern to include in coverage:\n" +
                        "(Use * for wildcards, e.g., com.mycompany.*)",
                "Add Include Pattern",
                JOptionPane.QUESTION_MESSAGE);

        if (pattern != null && !pattern.trim().isEmpty()) {
            pattern = pattern.trim();
            if (!includePatterns.contains(pattern)) {
                includePatterns.add(pattern);
                refreshIncludeTable();

                // Select the new pattern
                int newRow = includePatterns.size() - 1;
                includeTable.setRowSelectionInterval(newRow, newRow);
                updateIncludeButtonStates();
            }
        }
    }

    /**
     * Remove include pattern
     */
    private void removeIncludePattern() {
        int selectedRow = includeTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < includePatterns.size()) {
            String pattern = includePatterns.get(selectedRow);

            int result = JOptionPane.showConfirmDialog(this,
                    "Remove include pattern '" + pattern + "'?",
                    "Remove Pattern",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                includePatterns.remove(selectedRow);
                refreshIncludeTable();
                updateIncludeButtonStates();
            }
        }
    }

    /**
     * Edit include pattern
     */
    private void editIncludePattern() {
        int selectedRow = includeTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < includePatterns.size()) {
            String currentPattern = includePatterns.get(selectedRow);

            String newPattern = (String) JOptionPane.showInputDialog(this,
                    "Edit include pattern:",
                    "Edit Include Pattern",
                    JOptionPane.QUESTION_MESSAGE,
                    null, null, currentPattern);

            if (newPattern != null && !newPattern.trim().isEmpty()) {
                includePatterns.set(selectedRow, newPattern.trim());
                refreshIncludeTable();
                includeTable.setRowSelectionInterval(selectedRow, selectedRow);
            }
        }
    }

    /**
     * Add exclude pattern
     */
    private void addExcludePattern() {
        String pattern = JOptionPane.showInputDialog(this,
                "Enter package pattern to exclude from coverage:\n" +
                        "(Use * for wildcards, e.g., *.test.*, *Test)",
                "Add Exclude Pattern",
                JOptionPane.QUESTION_MESSAGE);

        if (pattern != null && !pattern.trim().isEmpty()) {
            pattern = pattern.trim();
            if (!excludePatterns.contains(pattern)) {
                excludePatterns.add(pattern);
                refreshExcludeTable();

                // Select the new pattern
                int newRow = excludePatterns.size() - 1;
                excludeTable.setRowSelectionInterval(newRow, newRow);
                updateExcludeButtonStates();
            }
        }
    }

    /**
     * Remove exclude pattern
     */
    private void removeExcludePattern() {
        int selectedRow = excludeTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < excludePatterns.size()) {
            String pattern = excludePatterns.get(selectedRow);

            int result = JOptionPane.showConfirmDialog(this,
                    "Remove exclude pattern '" + pattern + "'?",
                    "Remove Pattern",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                excludePatterns.remove(selectedRow);
                refreshExcludeTable();
                updateExcludeButtonStates();
            }
        }
    }

    /**
     * Edit exclude pattern
     */
    private void editExcludePattern() {
        int selectedRow = excludeTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < excludePatterns.size()) {
            String currentPattern = excludePatterns.get(selectedRow);

            String newPattern = (String) JOptionPane.showInputDialog(this,
                    "Edit exclude pattern:",
                    "Edit Exclude Pattern",
                    JOptionPane.QUESTION_MESSAGE,
                    null, null, currentPattern);

            if (newPattern != null && !newPattern.trim().isEmpty()) {
                excludePatterns.set(selectedRow, newPattern.trim());
                refreshExcludeTable();
                excludeTable.setRowSelectionInterval(selectedRow, selectedRow);
            }
        }
    }

    /**
     * Refresh include table
     */
    private void refreshIncludeTable() {
        includeTableModel.setRowCount(0);
        for (String pattern : includePatterns) {
            includeTableModel.addRow(new Object[]{pattern});
        }
        updateIncludeButtonStates();
    }

    /**
     * Refresh exclude table
     */
    private void refreshExcludeTable() {
        excludeTableModel.setRowCount(0);
        for (String pattern : excludePatterns) {
            excludeTableModel.addRow(new Object[]{pattern});
        }
        updateExcludeButtonStates();
    }

    /**
     * Update include button states
     */
    private void updateIncludeButtonStates() {
        int selectedRow = includeTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeIncludeButton.setEnabled(hasSelection);
        editIncludeButton.setEnabled(hasSelection);
    }

    /**
     * Update exclude button states
     */
    private void updateExcludeButtonStates() {
        int selectedRow = excludeTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeExcludeButton.setEnabled(hasSelection);
        editExcludeButton.setEnabled(hasSelection);
    }

    /**
     * Reset from configuration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        try {
            // Clear current patterns
            includePatterns.clear();
            excludePatterns.clear();

            // Load patterns from VM options if available
            String vmOptions = configuration.getVmOptions();
            if (vmOptions != null) {
                // Parse include patterns
                if (vmOptions.contains("-Dcoverage.include=")) {
                    int start = vmOptions.indexOf("-Dcoverage.include=") + "-Dcoverage.include=".length();
                    int end = vmOptions.indexOf(" ", start);
                    if (end == -1) end = vmOptions.length();

                    String includeStr = vmOptions.substring(start, end);
                    if (!includeStr.isEmpty()) {
                        String[] patterns = includeStr.split(",");
                        for (String pattern : patterns) {
                            if (!pattern.trim().isEmpty()) {
                                includePatterns.add(pattern.trim());
                            }
                        }
                    }
                }

                // Parse exclude patterns
                if (vmOptions.contains("-Dcoverage.exclude=")) {
                    int start = vmOptions.indexOf("-Dcoverage.exclude=") + "-Dcoverage.exclude=".length();
                    int end = vmOptions.indexOf(" ", start);
                    if (end == -1) end = vmOptions.length();

                    String excludeStr = vmOptions.substring(start, end);
                    if (!excludeStr.isEmpty()) {
                        String[] patterns = excludeStr.split(",");
                        for (String pattern : patterns) {
                            if (!pattern.trim().isEmpty()) {
                                excludePatterns.add(pattern.trim());
                            }
                        }
                    }
                }
            }

            // If no patterns loaded, start empty (like Ultimate shows "No class patterns configured")
            refreshIncludeTable();
            refreshExcludeTable();

            System.out.println("DevTomcat: Reset code coverage configuration");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error resetting coverage configuration: " + e.getMessage());
            includePatterns.clear();
            excludePatterns.clear();
            refreshIncludeTable();
            refreshExcludeTable();
        }
    }

    /**
     * Apply to configuration
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            String vmOptions = configuration.getVmOptions() != null ? configuration.getVmOptions() : "";

            // Remove existing coverage pattern options
            vmOptions = vmOptions.replaceAll("-Dcoverage\\.include=[^\\s]*", "").trim();
            vmOptions = vmOptions.replaceAll("-Dcoverage\\.exclude=[^\\s]*", "").trim();

            StringBuilder newVmOptions = new StringBuilder(vmOptions);

            // Add include patterns if any
            if (!includePatterns.isEmpty()) {
                if (newVmOptions.length() > 0) {
                    newVmOptions.append(" ");
                }
                newVmOptions.append("-Dcoverage.include=").append(String.join(",", includePatterns));
            }

            // Add exclude patterns if any
            if (!excludePatterns.isEmpty()) {
                if (newVmOptions.length() > 0) {
                    newVmOptions.append(" ");
                }
                newVmOptions.append("-Dcoverage.exclude=").append(String.join(",", excludePatterns));
            }

            configuration.setVmOptions(newVmOptions.toString());

            System.out.println("DevTomcat: Applied code coverage patterns - " +
                    includePatterns.size() + " include, " + excludePatterns.size() + " exclude");

        } catch (Exception e) {
            throw new ConfigurationException("Failed to apply coverage configuration: " + e.getMessage());
        }
    }

    // Utility methods
    public boolean hasIncludePatterns() {
        return !includePatterns.isEmpty();
    }

    public boolean hasExcludePatterns() {
        return !excludePatterns.isEmpty();
    }

    public int getIncludePatternsCount() {
        return includePatterns.size();
    }

    public int getExcludePatternsCount() {
        return excludePatterns.size();
    }

    public List<String> getIncludePatterns() {
        return new ArrayList<>(includePatterns);
    }

    public List<String> getExcludePatterns() {
        return new ArrayList<>(excludePatterns);
    }

    public String getCoverageSummary() {
        if (!hasIncludePatterns() && !hasExcludePatterns()) {
            return "No coverage patterns configured";
        }

        return String.format("Coverage patterns: %d include, %d exclude",
                includePatterns.size(), excludePatterns.size());
    }
}