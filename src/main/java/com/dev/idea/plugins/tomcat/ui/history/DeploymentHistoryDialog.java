package com.dev.idea.plugins.tomcat.ui.history;

import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory;
import com.dev.idea.plugins.tomcat.service.TomcatDeploymentHistory.HistoryEntry;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;

/**
 * Dialog that displays run history for either all tracked configurations
 * in the current project or a single selected configuration from Services.
 */
public class DeploymentHistoryDialog extends DialogWrapper {

    private final Project project;
    private final @Nullable String configurationName;
    private final List<HistoryEntry> entries;

    public DeploymentHistoryDialog(@NotNull Project project) {
        this(project, null);
    }

    public DeploymentHistoryDialog(@NotNull Project project, @Nullable String configurationName) {
        super(project, false);
        this.project = project;
        this.configurationName = configurationName;
        this.entries = scopeEntries(
                TomcatDeploymentHistory.getInstance(project).getEntries(),
                configurationName
        );
        setTitle(dialogTitle(configurationName));
        setSize(800, 500);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        mainPanel.setPreferredSize(JBUI.size(780, 460));

        // Summary stats panel at the top
        mainPanel.add(createSummaryPanel(), BorderLayout.NORTH);

        // History table
        if (entries.isEmpty()) {
            JBLabel emptyLabel = new JBLabel(emptyStateMessage(configurationName),
                    AllIcons.General.Information, SwingConstants.CENTER);
            mainPanel.add(emptyLabel, BorderLayout.CENTER);
        } else {
            mainPanel.add(createHistoryTable(), BorderLayout.CENTER);
        }

        return mainPanel;
    }

    @NotNull
    private JComponent createSummaryPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 4, JBUI.scale(12), 0));
        panel.setBorder(JBUI.Borders.emptyBottom(4));

        int total = entries.size();
        long successes = entries.stream().filter(e -> e.success).count();
        long failures = total - successes;
        long avgDuration = entries.isEmpty() ? 0 :
                (long) entries.stream().mapToLong(e -> e.durationMs).average().orElse(0);

        panel.add(createStatCard("Total Runs", String.valueOf(total), AllIcons.Actions.Execute));
        panel.add(createStatCard("Successful", String.valueOf(successes),
                AllIcons.RunConfigurations.TestPassed));
        panel.add(createStatCard("Failed", String.valueOf(failures),
                failures > 0 ? AllIcons.General.Error : AllIcons.RunConfigurations.TestPassed));
        panel.add(createStatCard("Avg Duration", formatDuration(avgDuration), AllIcons.Actions.Profile));

        return panel;
    }

    @NotNull
    private JComponent createStatCard(@NotNull String label, @NotNull String value, @NotNull Icon icon) {
        JPanel card = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        card.setBorder(JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(6, 10)));

        JBLabel iconLabel = new JBLabel(icon);
        card.add(iconLabel, BorderLayout.WEST);

        JPanel textPanel = new JPanel(new GridLayout(2, 1));
        textPanel.setOpaque(false);

        JBLabel valueLabel = new JBLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, valueLabel.getFont().getSize() + 2f));
        textPanel.add(valueLabel);

        JBLabel nameLabel = new JBLabel(label);
        nameLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        textPanel.add(nameLabel);

        card.add(textPanel, BorderLayout.CENTER);
        return card;
    }

    @NotNull
    private JComponent createHistoryTable() {
        HistoryTableModel model = new HistoryTableModel(entries);
        JBTable table = new JBTable(model);
        table.setRowHeight(JBUI.scale(24));
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.size(0, 0));
        table.getTableHeader().setReorderingAllowed(false);

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(140)); // Timestamp
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(150)); // Config
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(60));  // Status
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(80));  // Duration
        table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(70));  // Startup
        table.getColumnModel().getColumn(5).setPreferredWidth(JBUI.scale(50));  // Errors
        table.getColumnModel().getColumn(6).setPreferredWidth(JBUI.scale(60));  // Warnings
        table.getColumnModel().getColumn(7).setPreferredWidth(JBUI.scale(180)); // Artifacts

        // Status column renderer with color
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel) {
                    String status = String.valueOf(value);
                    if ("OK".equals(status)) {
                        c.setForeground(JBColor.namedColor("DevTomcat.deployedForeground",
                                new JBColor(0x008000, 0x6AAB73)));
                    } else {
                        c.setForeground(JBColor.RED);
                    }
                }
                return c;
            }
        });

        // Error column renderer — highlight non-zero
        table.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel && value instanceof Integer count && count > 0) {
                    c.setForeground(JBColor.RED);
                }
                return c;
            }
        });

        return new JBScrollPane(table);
    }

    @NotNull
    private static String formatDuration(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1000) return ms + "ms";
        double seconds = ms / 1000.0;
        // Locale.ROOT — stable '.' decimal separator across IDE locales.
        if (seconds < 60) return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
        long minutes = (long) (seconds / 60);
        long secs = (long) (seconds % 60);
        return minutes + "m " + secs + "s";
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{
                getOKAction(),
                createClearAction()
        };
    }

    @NotNull
    private Action createClearAction() {
        return new DialogWrapperAction(clearActionLabel(configurationName)) {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                int confirm = JOptionPane.showConfirmDialog(
                        getContentPanel(),
                        clearConfirmationMessage(configurationName),
                        "Confirm",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    TomcatDeploymentHistory history = TomcatDeploymentHistory.getInstance(project);
                    if (configurationName == null) {
                        history.clearHistory();
                    } else {
                        history.removeEntriesFor(configurationName);
                    }
                    close(OK_EXIT_CODE);
                }
            }
        };
    }

    @NotNull
    static List<HistoryEntry> scopeEntries(@NotNull List<HistoryEntry> entries,
                                           @Nullable String configurationName) {
        if (configurationName == null || configurationName.isBlank()) {
            return List.copyOf(entries);
        }
        return entries.stream()
                .filter(entry -> configurationName.equals(entry.configName))
                .toList();
    }

    @NotNull
    static String dialogTitle(@Nullable String configurationName) {
        if (configurationName == null || configurationName.isBlank()) {
            return "DevTomcat — Run History";
        }
        return "DevTomcat — Run History — " + configurationName;
    }

    @NotNull
    static String emptyStateMessage(@Nullable String configurationName) {
        if (configurationName == null || configurationName.isBlank()) {
            return "No run history yet. Run a Tomcat configuration to start tracking.";
        }
        return "No run history yet for '" + configurationName + "'. Run it to start tracking.";
    }

    @NotNull
    static String clearActionLabel(@Nullable String configurationName) {
        if (configurationName == null || configurationName.isBlank()) {
            return "Clear History";
        }
        return "Clear This History";
    }

    @NotNull
    static String clearConfirmationMessage(@Nullable String configurationName) {
        if (configurationName == null || configurationName.isBlank()) {
            return "Clear all run history?";
        }
        return "Clear run history for '" + configurationName + "'?";
    }

    // ======== Table Model ========

    private static final class HistoryTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Timestamp", "Configuration", "Status", "Duration",
                "Startup", "Errors", "Warnings", "Artifacts"
        };
        private final List<HistoryEntry> entries;

        HistoryTableModel(@NotNull List<HistoryEntry> entries) {
            this.entries = entries;
        }

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case 5, 6 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int col) {
            HistoryEntry e = entries.get(row);
            return switch (col) {
                case 0 -> e.getFormattedTimestamp();
                case 1 -> e.configName;
                case 2 -> e.success ? "OK" : "FAILED";
                case 3 -> formatDuration(e.durationMs);
                case 4 -> e.startupTimeMs > 0 ? formatDuration(e.startupTimeMs) : "—";
                case 5 -> e.errorCount;
                case 6 -> e.warningCount;
                case 7 -> e.artifactNames.isEmpty() ? "—" : String.join(", ", e.artifactNames);
                default -> "";
            };
        }
    }
}
