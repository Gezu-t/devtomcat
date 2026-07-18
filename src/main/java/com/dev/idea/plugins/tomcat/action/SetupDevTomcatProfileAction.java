package com.dev.idea.plugins.tomcat.action;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.dev.idea.plugins.tomcat.model.PortStrategy;
import com.dev.idea.plugins.tomcat.setting.ProjectTomcatProfileScanner;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Creates ONE Tomcat run config with all detected WAR modules as module-backed deployments
// "multiple deployments per Tomcat" topology.
// User picks port mode at setup time (auto-resolve vs fixed).
public class SetupDevTomcatProfileAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(SetupDevTomcatProfileAction.class);

    public SetupDevTomcatProfileAction() {
        super("Set Up DevTomcat from Project", "Detect WAR modules and create a Tomcat run configuration", null);
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        ProjectTomcatProfileScanner.ProjectProfile profile = ProjectTomcatProfileScanner.scan(project);
        if (profile.isEmpty()) {
            Messages.showInfoMessage(project,
                    "No WAR-packaging Maven modules were detected.\n"
                    + "Add <packaging>war</packaging> to a pom.xml and try again.",
                    "DevTomcat Setup");
            return;
        }
        SetupDialog dialog = new SetupDialog(project, profile);
        if (!dialog.showAndGet()) return;
        createConfiguration(project, dialog);
    }

    private static void createConfiguration(@NotNull Project project, @NotNull SetupDialog dialog) {
        List<ProjectTomcatProfileScanner.DetectedWebappModule> selected = dialog.getSelectedModules();
        if (selected.isEmpty()) {
            Messages.showWarningDialog(project, "No webapps selected.", "DevTomcat Setup");
            return;
        }
        String name = dialog.getConfigName();
        if (name.isBlank()) name = "Tomcat";

        try {
            RunManager runManager = RunManager.getInstance(project);
            // The REGISTERED type instance, never `new` — a foreign type/factory
            // instance breaks identity comparisons against platform-loaded configs
            // and instantiates a parallel factory/template pair for the session.
            TomcatRunConfigurationType type =
                    ConfigurationTypeUtil.findConfigurationType(TomcatRunConfigurationType.class);
            RunnerAndConfigurationSettings settings =
                    runManager.createConfiguration(name, type.getConfigurationFactories()[0]);
            TomcatRunConfiguration cfg = (TomcatRunConfiguration) settings.getConfiguration();

            // Port mode controls both the value and the strategy:
            //   AUTO  → seed 8080, AUTO_BUMP (existing behavior)
            //   FIXED → seed user-entered port, STRICT (refuse to bump)
            int port = dialog.isFixedPortMode() ? dialog.getFixedPort() : 8080;
            cfg.getConfigData().getPortConfig().setHttp(port);
            cfg.getConfigData().getPortConfig().setShutdown(port - 75); // 8080 → 8005
            cfg.getConfigData().getPortConfig().setStrategy(
                    dialog.isFixedPortMode() ? PortStrategy.STRICT : PortStrategy.AUTO_BUMP);

            TomcatInfo tomcat = dialog.getSelectedTomcat();
            if (tomcat != null) cfg.getConfigData().setTomcatInfo(tomcat);

            List<Deployment> deployments = new ArrayList<>();
            for (ProjectTomcatProfileScanner.DetectedWebappModule m : selected) {
                // Module-backed: the pointer binds the scanner's owning module
                // directly (moduleName, not the Maven artifactId), so class sync
                // and producer matching need no name-guessing resolution.
                deployments.add(ModuleBackedDeployment.ofName(
                        project, m.moduleName(), java.nio.file.Path.of(m.explodedPath()),
                        m.contextPath(), true));
            }
            cfg.getConfigData().getDeploymentConfig().setDeployments(deployments);
            cfg.getConfigData().setServerMode(TomcatConstants.MODE_LOCAL);

            runManager.addConfiguration(settings);
            runManager.setSelectedConfiguration(settings);

            Messages.showInfoMessage(project,
                    "Created run configuration '" + name + "' with " + deployments.size()
                    + " deployment(s).",
                    "DevTomcat Setup");
        } catch (Throwable t) {
            LOG.warn("Setup failed", t);
            Messages.showErrorDialog(project,
                    "Could not create configuration: " + t.getMessage(),
                    "DevTomcat Setup");
        }
    }

    // --- Dialog ---

    private static final class SetupDialog extends DialogWrapper {
        private final ProjectTomcatProfileScanner.ProjectProfile profile;
        private final JBTextField nameField = new JBTextField("Tomcat");
        private final ComboBox<TomcatInfo> tomcatPicker;
        private final ModulesTableModel modulesModel;
        private final JRadioButton autoMode = new JRadioButton("Auto-resolve (start at 8080, bump if busy)", true);
        private final JRadioButton fixedMode = new JRadioButton("Fixed port (fail if busy):");
        private final JBTextField fixedPortField = new JBTextField("8080", 6);
        private JTable modulesTable;

        SetupDialog(@NotNull Project project,
                    @NotNull ProjectTomcatProfileScanner.ProjectProfile profile) {
            super(project, true);
            this.profile = profile;
            this.modulesModel = new ModulesTableModel(profile.webappModules());
            this.tomcatPicker = new ComboBox<>(profile.registeredTomcats().toArray(new TomcatInfo[0]));
            this.tomcatPicker.setRenderer(new SimpleListCellRenderer<TomcatInfo>() {
                @Override
                public void customize(@NotNull JList<? extends TomcatInfo> list, TomcatInfo v, int index,
                                      boolean selected, boolean hasFocus) {
                    setText(v == null ? "" : v.getName() + " (" + v.getVersion() + ")");
                }
            });
            setTitle("Set Up DevTomcat from Project");
            init();
        }

        @Override
        protected @NotNull JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
            panel.setPreferredSize(JBUI.size(640, 420));

            JBLabel summary = new JBLabel(
                    "Detected " + profile.webappModules().size() + " WAR module(s). "
                    + "This creates ONE Tomcat run configuration containing all selected webapps.");
            summary.setBorder(JBUI.Borders.empty(4, 6));

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.anchor = GridBagConstraints.WEST;
            g.insets = JBUI.insets(3);
            g.gridy = 0;

            g.gridx = 0; form.add(new JBLabel("Configuration name:"), g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            form.add(nameField, g);

            g.gridy++;
            g.gridx = 0; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            form.add(new JBLabel("Tomcat install:"), g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            if (profile.registeredTomcats().isEmpty()) {
                JBLabel none = new JBLabel("(none — register one via Project Settings → Application Servers first)");
                none.setForeground(NamedColorUtil.getInactiveTextColor());
                form.add(none, g);
                tomcatPicker.setEnabled(false);
            } else {
                form.add(tomcatPicker, g);
            }

            g.gridy++;
            g.gridx = 0; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            g.anchor = GridBagConstraints.NORTHWEST;
            form.add(new JBLabel("Port:"), g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            form.add(buildPortModePanel(), g);
            g.anchor = GridBagConstraints.WEST;

            JPanel formWrap = new JPanel(new BorderLayout());
            formWrap.add(form, BorderLayout.NORTH);

            modulesTable = new JTable(modulesModel);
            modulesTable.setRowHeight(JBUI.scale(24));
            modulesTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(48));
            // Highlight duplicate context-path cells with a tinted background.
            // The renderer reads live duplicate-state from the model, so any
            // table-model edit that changes the duplicate set will repaint via
            // the listener below.
            modulesTable.getColumnModel().getColumn(2).setCellRenderer(new DuplicateAwareRenderer(modulesModel));
            modulesModel.addTableModelListener(e -> {
                modulesTable.repaint();
                initValidation();
            });
            JBScrollPane scroll = new JBScrollPane(modulesTable);
            scroll.setBorder(BorderFactory.createTitledBorder("Webapps to include"));

            panel.add(summary, BorderLayout.NORTH);
            panel.add(formWrap, BorderLayout.CENTER);
            panel.add(scroll, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildPortModePanel() {
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.anchor = GridBagConstraints.WEST;
            g.gridy = 0;
            g.gridx = 0; g.gridwidth = 2;
            p.add(autoMode, g);

            g.gridy++;
            g.gridx = 0; g.gridwidth = 1;
            p.add(fixedMode, g);
            g.gridx = 1;
            p.add(fixedPortField, g);

            ButtonGroup group = new ButtonGroup();
            group.add(autoMode);
            group.add(fixedMode);
            fixedPortField.setEnabled(false);
            autoMode.addActionListener(e -> fixedPortField.setEnabled(false));
            fixedMode.addActionListener(e -> fixedPortField.setEnabled(true));
            return p;
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            // Commit any in-progress cell edit so its value participates in validation.
            if (modulesTable != null && modulesTable.isEditing()) {
                modulesTable.getCellEditor().stopCellEditing();
            }
            Set<Integer> dupes = modulesModel.findDuplicateContextPathRows();
            if (!dupes.isEmpty()) {
                String paths = dupes.stream()
                        .map(modulesModel::getContextPath)
                        .map(SetupDevTomcatProfileAction::canonicalizeForCompare)
                        .distinct()
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return new ValidationInfo(
                        "Duplicate context path(s) among selected webapps: " + paths
                                + ". Each artifact must have a unique context.",
                        modulesTable);
            }
            // Reject blank context paths on selected rows.
            for (int i = 0; i < modulesModel.getRowCount(); i++) {
                if (!modulesModel.isSelectedRow(i)) continue;
                if (modulesModel.getContextPath(i).trim().isEmpty()) {
                    return new ValidationInfo(
                            "Context path is required for every selected webapp.",
                            modulesTable);
                }
            }
            return null;
        }

        @NotNull String getConfigName() { return nameField.getText().trim(); }

        boolean isFixedPortMode() { return fixedMode.isSelected(); }

        int getFixedPort() {
            try { return Integer.parseInt(fixedPortField.getText().trim()); }
            catch (NumberFormatException e) { return 8080; }
        }

        @NotNull List<ProjectTomcatProfileScanner.DetectedWebappModule> getSelectedModules() {
            return modulesModel.getSelected();
        }

        @Nullable TomcatInfo getSelectedTomcat() {
            return (TomcatInfo) tomcatPicker.getSelectedItem();
        }
    }

    private static final class ModulesTableModel extends AbstractTableModel {
        private final List<ProjectTomcatProfileScanner.DetectedWebappModule> modules;
        private final boolean[] selected;
        private final String[] contextPaths;
        private static final String[] COLS = {"", "Module", "Context Path", "Exploded path"};

        ModulesTableModel(@NotNull List<ProjectTomcatProfileScanner.DetectedWebappModule> modules) {
            this.modules = modules;
            this.selected = new boolean[modules.size()];
            this.contextPaths = new String[modules.size()];
            for (int i = 0; i < modules.size(); i++) {
                selected[i] = true;
                contextPaths[i] = modules.get(i).contextPath();
            }
        }

        @Override public int getRowCount() { return modules.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }

        @Override
        public boolean isCellEditable(int row, int col) { return col == 0 || col == 2; }

        @Override
        public @Nullable Object getValueAt(int row, int col) {
            ProjectTomcatProfileScanner.DetectedWebappModule m = modules.get(row);
            return switch (col) {
                case 0 -> selected[row];
                case 1 -> m.moduleName();
                case 2 -> contextPaths[row];
                case 3 -> m.explodedPath();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col == 0) selected[row] = Boolean.TRUE.equals(value);
            else if (col == 2) contextPaths[row] = String.valueOf(value);
            fireTableRowsUpdated(row, row);
        }

        boolean isSelectedRow(int row) {
            return row >= 0 && row < selected.length && selected[row];
        }

        @NotNull
        String getContextPath(int row) {
            return row >= 0 && row < contextPaths.length && contextPaths[row] != null
                    ? contextPaths[row]
                    : "";
        }

        /**
         * Returns the row indices whose canonical context path collides with another
         * selected row. Unselected rows are ignored (they won't be deployed). Two
         * paths collide when {@link ContextPathUtils#resolveContextNameSafe} returns
         * the same value — so {@code /foo}, {@code /foo/}, and {@code foo/} all
         * resolve to the same context name and are flagged together.
         */
        @NotNull
        Set<Integer> findDuplicateContextPathRows() {
            Map<String, List<Integer>> byName = new HashMap<>();
            for (int i = 0; i < modules.size(); i++) {
                if (!selected[i]) continue;
                String canonical = canonicalizeForCompare(contextPaths[i]);
                byName.computeIfAbsent(canonical, k -> new ArrayList<>()).add(i);
            }
            Set<Integer> out = new LinkedHashSet<>();
            for (List<Integer> rows : byName.values()) {
                if (rows.size() > 1) out.addAll(rows);
            }
            return out;
        }

        @NotNull
        List<ProjectTomcatProfileScanner.DetectedWebappModule> getSelected() {
            List<ProjectTomcatProfileScanner.DetectedWebappModule> out = new ArrayList<>();
            for (int i = 0; i < modules.size(); i++) {
                if (!selected[i]) continue;
                // Carry the user-edited context path forward as a proper field
                // on the record — no overloading of artifactId. createConfiguration
                // reads artifactId and contextPath independently.
                out.add(modules.get(i).withContextPath(contextPaths[i]));
            }
            return out;
        }
    }

    /**
     * Canonicalises a raw user-typed context path to the form Tomcat will see
     * when {@code TomcatConfigurationValidator} resolves it. Two paths
     * collide if and only if this returns equal strings. Package-private for
     * the unit test (see {@code SetupContextPathDuplicateDetectionTest}).
     */
    @NotNull
    static String canonicalizeForCompare(@Nullable String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        try {
            // Pass through to resolveContextName so empty / "/" / "//" / etc.
            // all fold to the same ROOT name — same canonical form Tomcat sees.
            return ContextPathUtils.resolveContextName(
                    trimmed.isEmpty() || trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        } catch (IllegalArgumentException e) {
            // Path contained traversal / illegal chars — treat as its own bucket
            // so it isn't silently folded into another row's duplicate group.
            return "!invalid:" + trimmed;
        }
    }

    /**
     * Table cell renderer that tints the context-path cell with a JBColor red
     * background when the row's path collides with another selected row.
     * The duplicate set is recomputed on each paint from the live model, so
     * any edit that changes the collision picture is reflected as soon as the
     * table is repainted (the dialog's TableModelListener triggers that).
     */
    private static final class DuplicateAwareRenderer extends DefaultTableCellRenderer {
        private static final JBColor DUPLICATE_BG =
                new JBColor(new Color(255, 224, 224), new Color(85, 35, 35));
        private final ModulesTableModel model;

        DuplicateAwareRenderer(@NotNull ModulesTableModel model) {
            this.model = model;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            // Don't override the selection-highlight palette.
            if (isSelected) return c;
            Set<Integer> dupes = model.findDuplicateContextPathRows();
            c.setBackground(dupes.contains(row) ? DUPLICATE_BG : table.getBackground());
            return c;
        }
    }
}
