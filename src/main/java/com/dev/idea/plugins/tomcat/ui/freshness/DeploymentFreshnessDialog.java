package com.dev.idea.plugins.tomcat.ui.freshness;

import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.ModuleRow;
import com.dev.idea.plugins.tomcat.update.DeploymentFreshnessReport.Report;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * "Deployment Freshness" dialog — one grouped table (deployment header rows,
 * then per-module rows): Module / Delivered As / Freshness. Deliberately dumb:
 * it renders reports computed by {@link DeploymentFreshnessReport}; the
 * supplier runs OFF the EDT (pooled thread), the table updates on the EDT.
 */
public class DeploymentFreshnessDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(DeploymentFreshnessDialog.class);

    /** "Current" freshness color — same named color the history dialog uses. */
    private static final JBColor CURRENT_COLOR = JBColor.namedColor(
            "DevTomcat.deployedForeground", new JBColor(0x008000, 0x6AAB73));

    /** One rendered table line: a deployment header or a module row. */
    private record Line(@NotNull String module, @NotNull String delivery,
                        @NotNull String freshness, boolean header,
                        boolean stale, boolean unverified) {}

    private final Supplier<List<Report>> computer;
    private final FreshnessTableModel model = new FreshnessTableModel();
    private final Action refreshAction = new AbstractAction("Refresh") {
        @Override public void actionPerformed(ActionEvent e) { refresh(); }
    };

    /** {@code computer} is invoked on a pooled thread — it must not touch the EDT. */
    public DeploymentFreshnessDialog(@NotNull Project project,
                                     @NotNull String configName,
                                     @NotNull Supplier<List<Report>> computer) {
        super(project, false);
        this.computer = computer;
        setTitle("Deployment Freshness — '" + configName + "'");
        setOKButtonText("Close");
        init();
        refresh();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTable table = new JBTable(model);
        table.setRowHeight(JBUI.scale(24));
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.size(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new FreshnessCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(220));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(230));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(280));

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setPreferredSize(JBUI.size(760, 360));
        panel.add(new JBScrollPane(table), BorderLayout.CENTER);
        JBLabel hint = new JBLabel(
                "Freshness compares each module's compiled output with what Tomcat serves for it.");
        hint.setForeground(NamedColorUtil.getInactiveTextColor());
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{refreshAction};
    }

    /** Recompute off the EDT; the table only ever receives a finished report. */
    private void refresh() {
        refreshAction.setEnabled(false);
        model.setLines(List.of(new Line("Computing…", "", "", true, false, false)));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Line> lines;
            try {
                lines = flatten(computer.get());
            } catch (ProcessCanceledException pce) {
                throw pce;
            } catch (Exception ex) {
                LOG.warn("Deployment freshness computation failed", ex);
                lines = List.of(new Line(
                        "Could not compute freshness — see the IDE log", "", "",
                        true, false, false));
            }
            List<Line> finished = lines;
            // ModalityState.any(): this dialog is modal; the update must land
            // while it is showing, and it only touches the dialog's own table.
            ApplicationManager.getApplication().invokeLater(() -> {
                if (isDisposed()) return;
                model.setLines(finished);
                refreshAction.setEnabled(true);
            }, ModalityState.any());
        });
    }

    @NotNull
    private static List<Line> flatten(@NotNull List<Report> reports) {
        if (reports.isEmpty()) {
            return List.of(new Line("No deployments configured", "", "", true, false, false));
        }
        List<Line> lines = new ArrayList<>();
        for (Report report : reports) {
            lines.add(new Line(report.deploymentName(),
                    report.shape().getLabel(), "", true, false, false));
            for (ModuleRow row : report.rows()) {
                lines.add(new Line(row.moduleName(),
                        DeploymentFreshnessReport.deliveryLabel(row.delivery()),
                        DeploymentFreshnessReport.freshnessLabel(row),
                        false,
                        row.freshness().stale(),
                        !row.freshness().verified()));
            }
        }
        return lines;
    }

    private static final class FreshnessTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Module", "Delivered As", "Freshness"};

        private List<Line> lines = List.of();

        void setLines(@NotNull List<Line> lines) {
            this.lines = lines;
            fireTableDataChanged();
        }

        @NotNull Line lineAt(int row) { return lines.get(row); }

        @Override public int getRowCount() { return lines.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return false; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Line line = lines.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> line.module();
                case 1 -> line.delivery();
                default -> line.freshness();
            };
        }
    }

    private static final class FreshnessCellRenderer extends ColoredTableCellRenderer {

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value,
                                             boolean selected, boolean hasFocus,
                                             int row, int column) {
            if (!(table.getModel() instanceof FreshnessTableModel m)
                    || row < 0 || row >= m.getRowCount()) {
                return;
            }
            Line line = m.lineAt(row);
            if (line.header()) {
                if (column == 0) {
                    append(line.module(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                } else if (column == 1 && !line.delivery().isEmpty()) {
                    append(line.delivery(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                return;
            }
            switch (column) {
                case 0 -> append("    " + line.module(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                case 1 -> append(line.delivery(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                default -> {
                    if (line.stale()) {
                        append(line.freshness(), SimpleTextAttributes.ERROR_ATTRIBUTES);
                    } else if (line.unverified()) {
                        append(line.freshness(), new SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_PLAIN,
                                NamedColorUtil.getInactiveTextColor()));
                    } else {
                        append(line.freshness(), new SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_PLAIN, CURRENT_COLOR));
                    }
                }
            }
        }
    }
}
