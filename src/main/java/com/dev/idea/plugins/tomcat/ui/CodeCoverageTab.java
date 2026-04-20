package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PackageChooserDialog;
import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.dev.idea.plugins.tomcat.utils.TomcatReadActions;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiPackage;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

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
        table.getColumnModel().getColumn(0).setCellRenderer(new PatternCellRenderer());

        if (isInclude) {
            includeTableModel = model;
            includeTable = table;
        } else {
            excludeTableModel = model;
            excludeTable = table;
        }

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> showAddPatternPopup(isInclude, button))
                .setRemoveAction(button -> removePattern(isInclude))
                .setEditAction(button -> editPattern(isInclude))
                .disableUpDownActions();

        JComponent decoratedPanel = decorator.createPanel();
        decoratedPanel.setPreferredSize(new Dimension(0, JBUI.scale(150)));
        panel.add(decoratedPanel, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Shows the + button popup — the user picks between class, package, or
     * freeform pattern, and the relevant chooser opens. Lets entries be pulled
     * straight from the project model instead of hand-typing FQNs, while the
     * freeform option preserves the advanced-wildcard escape hatch.
     *
     * <p>Anchored via {@link AnActionButton#getPreferredPopupPoint()} so the popup
     * pins to the {@code +} button on the table's toolbar. Passing the raw component
     * to {@code showUnderneathOf} dropped the popup to the bottom of the screen on
     * some IntelliJ versions because the resolved component wasn't where the button
     * actually rendered.
     */
    private void showAddPatternPopup(boolean isInclude, @NotNull AnActionButton button) {
        BaseListPopupStep<AddOption> step = new BaseListPopupStep<>(null, AddOption.values()) {
            @NotNull
            @Override
            public String getTextFor(AddOption value) {
                return value.label;
            }

            @Override
            public Icon getIconFor(AddOption value) {
                return value.icon;
            }

            @Override
            public PopupStep<?> onChosen(AddOption selected, boolean finalChoice) {
                return doFinalStep(() -> selected.invoke(CodeCoverageTab.this, isInclude));
            }
        };
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.show(button.getPreferredPopupPoint());
    }

    /** Opens the class chooser and stores the chosen class's qualified name. */
    private void addClassPattern(boolean isInclude) {
        TreeClassChooser chooser = TreeClassChooserFactory.getInstance(project)
                .createAllProjectScopeChooser(chooserTitle(isInclude, "Class"));
        chooser.showDialog();
        PsiClass selected = chooser.getSelected();
        if (selected == null) return;
        String fqn = TomcatReadActions.compute(selected::getQualifiedName);
        if (fqn == null || fqn.isEmpty()) return;
        insertPattern(isInclude, fqn);
    }

    /**
     * Opens the package chooser. Appends the {@code .*} subpackage-matching
     * suffix that the coverage runner interprets as "this package and
     * everything below it".
     *
     * <p>The default (root) package is a hazard: mapping it silently to
     * {@code *} would turn a single click into a global include/exclude
     * wildcard and almost always mean something different from what the user
     * intended. Confirm explicitly in that case so the broad-scope behaviour
     * is never silent.
     */
    private void addPackagePattern(boolean isInclude) {
        PackageChooserDialog dialog = new PackageChooserDialog(chooserTitle(isInclude, "Package"), project);
        if (!dialog.showAndGet()) return;
        PsiPackage selected = dialog.getSelectedPackage();
        if (selected == null) return;
        String fqn = TomcatReadActions.compute(selected::getQualifiedName);
        if (fqn == null) return;
        if (fqn.isEmpty()) {
            int choice = Messages.showYesNoDialog(
                    project,
                    "You selected the default (root) package. Adding it as a coverage pattern "
                            + "expands to '*' and matches every class in the project.\n\n"
                            + "Add a global wildcard " + (isInclude ? "include" : "exclude") + "?",
                    "Confirm Global Wildcard",
                    Messages.getWarningIcon());
            if (choice != Messages.YES) return;
            insertPattern(isInclude, "*");
            return;
        }
        insertPattern(isInclude, fqn + ".*");
    }

    /** Freeform wildcard input — preserves the advanced-user escape hatch. */
    private void addFreeformPattern(boolean isInclude) {
        String type = isInclude ? "include" : "exclude";
        String example = isInclude ? "com.mycompany.*" : "*.test.*, *Test";
        String pattern = Messages.showInputDialog(
                project,
                "Enter pattern to " + type + " in coverage:\n" +
                        "(Use * for wildcards, e.g., " + example + ")",
                "Add " + (isInclude ? "Include" : "Exclude") + " Pattern",
                null,
                null,
                null);
        if (pattern != null && !pattern.trim().isEmpty()) {
            insertPattern(isInclude, pattern.trim());
        }
    }

    /**
     * Inserts a pattern into the target list, skipping duplicates and
     * selecting the new row so the user sees where it landed.
     */
    private void insertPattern(boolean isInclude, @NotNull String pattern) {
        List<String> patterns = isInclude ? includePatterns : excludePatterns;
        JBTable table = isInclude ? includeTable : excludeTable;
        if (patterns.contains(pattern)) return;
        patterns.add(pattern);
        refreshTable(isInclude);
        int newRow = patterns.size() - 1;
        table.setRowSelectionInterval(newRow, newRow);
    }

    @NotNull
    private static String chooserTitle(boolean isInclude, @NotNull String kind) {
        return (isInclude ? "Include " : "Exclude ") + kind + " in Coverage";
    }

    /**
     * The three entries in the + popup. Labels, icons, and invocation target
     * co-located so a fourth option is a one-line change instead of scattered
     * edits across popup wiring.
     */
    private enum AddOption {
        CLASS("Add Class\u2026", AllIcons.Nodes.Class,
                CodeCoverageTab::addClassPattern),
        PACKAGE("Add Package\u2026", AllIcons.Nodes.Package,
                CodeCoverageTab::addPackagePattern),
        PATTERN("Add Pattern\u2026", AllIcons.General.Filter,
                CodeCoverageTab::addFreeformPattern);

        final String label;
        final Icon icon;
        private final ObjBooleanConsumer invoker;

        AddOption(String label, Icon icon, ObjBooleanConsumer invoker) {
            this.label = label;
            this.icon = icon;
            this.invoker = invoker;
        }

        void invoke(@NotNull CodeCoverageTab tab, boolean isInclude) {
            invoker.accept(tab, isInclude);
        }
    }

    /**
     * Narrow functional interface for {@code (CodeCoverageTab, boolean) → void}.
     * Avoids the boxing a {@code BiConsumer<CodeCoverageTab, Boolean>} would
     * incur and keeps the enum declaration compact.
     */
    @FunctionalInterface
    private interface ObjBooleanConsumer {
        void accept(@NotNull CodeCoverageTab tab, boolean isInclude);
    }

    /**
     * Renders each row with an icon that matches its shape: package icon for
     * {@code *.*}, class icon for non-wildcard FQN, filter icon for freeform
     * wildcards. The resolution is a pure string check — no PSI lookup on the
     * paint path so scrolling stays snappy.
     */
    private static final class PatternCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setIcon(iconFor(value == null ? "" : value.toString()));
            return this;
        }

        @Nullable
        private static Icon iconFor(@NotNull String pattern) {
            if (pattern.isEmpty()) return null;
            if (pattern.endsWith(".*") || pattern.equals("*")) return AllIcons.Nodes.Package;
            if (pattern.contains("*") || pattern.contains("?")) return AllIcons.General.Filter;
            // Non-wildcard FQN — almost always a class. No PSI resolve here
            // because per-row index lookups on the EDT stall scroll.
            return AllIcons.Nodes.Class;
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
