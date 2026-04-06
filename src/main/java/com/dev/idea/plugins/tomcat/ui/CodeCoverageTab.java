package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;

import com.intellij.ui.components.JBPanel;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;

public class CodeCoverageTab extends JBPanel<CodeCoverageTab> {

    private static final Logger LOG = Logger.getInstance(CodeCoverageTab.class);

    private final Project project;

    private JBTable includeTable;
    private DefaultTableModel includeTableModel;

    private JBTable excludeTable;
    private DefaultTableModel excludeTableModel;

    private List<String> includePatterns = new ArrayList<>();
    private List<String> excludePatterns = new ArrayList<>();

    public CodeCoverageTab(@NotNull Project project) {
        this.project = project;
        initializeUI();
        initializeDefaultPatterns();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(4, 0, 4, 0);

        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0; gbc.weighty = 0.5;
        mainPanel.add(createPatternsSection("Packages and classes to include in coverage data",
                true), gbc);

        gbc.gridy = 1;
        mainPanel.add(createPatternsSection("Packages and classes to exclude from coverage data",
                false), gbc);

        add(mainPanel, BorderLayout.CENTER);
    }

    private void initializeDefaultPatterns() {
        refreshIncludeTable();
        refreshExcludeTable();
    }

    private JPanel createPatternsSection(String title, boolean isInclude) {
        JPanel panel = new JPanel(new BorderLayout());

        panel.add(new TitledSeparator(title), BorderLayout.NORTH);

        String[] columnNames = {"Pattern"};
        DefaultTableModel model = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JBTable table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(JBUI.scale(24));

        if (isInclude) {
            includeTableModel = model;
            includeTable = table;
        } else {
            excludeTableModel = model;
            excludeTable = table;
        }

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> addPattern(isInclude))
                .setRemoveAction(button -> removePattern(isInclude))
                .setEditAction(button -> editPattern(isInclude))
                .disableUpDownActions();

        JComponent decoratedPanel = decorator.createPanel();
        decoratedPanel.setPreferredSize(new Dimension(0, JBUI.scale(150)));
        panel.add(decoratedPanel, BorderLayout.CENTER);

        return panel;
    }

    private void addPattern(boolean isInclude) {
        String type = isInclude ? "include" : "exclude";
        String example = isInclude ? "com.mycompany.*" : "*.test.*, *Test";
        String pattern = Messages.showInputDialog(
                project,
                "Enter package pattern to " + type + " in coverage:\n" +
                        "(Use * for wildcards, e.g., " + example + ")",
                "Add " + (isInclude ? "Include" : "Exclude") + " Pattern",
                null,
                null,
                null);

        if (pattern != null && !pattern.trim().isEmpty()) {
            pattern = pattern.trim();
            List<String> patterns = isInclude ? includePatterns : excludePatterns;
            JBTable table = isInclude ? includeTable : excludeTable;
            if (!patterns.contains(pattern)) {
                patterns.add(pattern);
                refreshTable(isInclude);
                int newRow = patterns.size() - 1;
                table.setRowSelectionInterval(newRow, newRow);
            }
        }
    }

    private void removePattern(boolean isInclude) {
        JBTable table = isInclude ? includeTable : excludeTable;
        List<String> patterns = isInclude ? includePatterns : excludePatterns;
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < patterns.size()) {
            patterns.remove(selectedRow);
            refreshTable(isInclude);
        }
    }

    private void editPattern(boolean isInclude) {
        JBTable table = isInclude ? includeTable : excludeTable;
        List<String> patterns = isInclude ? includePatterns : excludePatterns;
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < patterns.size()) {
            String currentPattern = patterns.get(selectedRow);

            String newPattern = Messages.showInputDialog(
                    project,
                    "Edit " + (isInclude ? "include" : "exclude") + " pattern:",
                    "Edit Pattern",
                    null,
                    currentPattern,
                    null);

            if (newPattern != null && !newPattern.trim().isEmpty()) {
                patterns.set(selectedRow, newPattern.trim());
                refreshTable(isInclude);
                table.setRowSelectionInterval(selectedRow, selectedRow);
            }
        }
    }

    private void refreshTable(boolean isInclude) {
        if (isInclude) {
            refreshIncludeTable();
        } else {
            refreshExcludeTable();
        }
    }

    private void refreshIncludeTable() {
        includeTableModel.setRowCount(0);
        for (String pattern : includePatterns) {
            includeTableModel.addRow(new Object[]{pattern});
        }
    }

    private void refreshExcludeTable() {
        excludeTableModel.setRowCount(0);
        for (String pattern : excludePatterns) {
            excludeTableModel.addRow(new Object[]{pattern});
        }
    }

    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        try {
            CoverageConfig cc = configuration.getConfigData().getCoverageConfig();
            includePatterns.clear();
            includePatterns.addAll(cc.getIncludePatterns());
            excludePatterns.clear();
            excludePatterns.addAll(cc.getExcludePatterns());

            refreshIncludeTable();
            refreshExcludeTable();

            LOG.debug("DevTomcat: Reset code coverage configuration");

        } catch (Exception e) {
            LOG.warn("DevTomcat: Error resetting coverage configuration", e);
            includePatterns.clear();
            excludePatterns.clear();
            refreshIncludeTable();
            refreshExcludeTable();
        }
    }

    public boolean isModified(@NotNull TomcatRunConfiguration configuration) {
        CoverageConfig cc = configuration.getConfigData().getCoverageConfig();
        return !includePatterns.equals(cc.getIncludePatterns())
                || !excludePatterns.equals(cc.getExcludePatterns());
    }

    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        try {
            CoverageConfig cc = configuration.getConfigData().getCoverageConfig();
            cc.setIncludePatterns(new ArrayList<>(includePatterns));
            cc.setExcludePatterns(new ArrayList<>(excludePatterns));

            LOG.debug("DevTomcat: Applied code coverage patterns - " +
                    includePatterns.size() + " include, " + excludePatterns.size() + " exclude");

        } catch (Exception e) {
            throw new ConfigurationException("Failed to apply coverage configuration: " + e.getMessage());
        }
    }

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
