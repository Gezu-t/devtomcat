package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;

/**
 * Self-contained panel for managing Tomcat run configuration environment variables.
 *
 * <p>Owns the env var table, toolbar actions (Add/Remove/Edit/Populate Defaults/Copy/Paste),
 * computed-vs-user-modified key tracking, and the Add/Edit dialog. Designed to be embedded
 * inside {@link StartupConnectionTab}, which calls {@link #saveState()} and
 * {@link #restoreState(State)} when switching between Run/Debug/Coverage/Profile modes.
 */
public class EnvVarPanel extends JBPanel<EnvVarPanel> {

    private static final Logger LOG = Logger.getInstance(EnvVarPanel.class);

    // =========================================================================
    // State
    // =========================================================================

    /**
     * Snapshot of env var state for one runner mode.
     * Stored per-mode by {@link StartupConnectionTab}.
     */
    public static class State {
        public Map<String, String> envVars = new LinkedHashMap<>();
        public Set<String> computedKeys = new LinkedHashSet<>();
        public Set<String> deletedComputedKeys = new LinkedHashSet<>();
        public boolean passParentEnvs = true;
    }

    private final Project project;

    private JBTable envTable;
    private DefaultTableModel envModel;
    private JBCheckBox passParentEnvsCB;

    /** Keys currently shown as computed (gray italic) in the table. */
    private final Set<String> currentComputedKeys = new LinkedHashSet<>();
    /** Keys the user explicitly deleted — never auto-restored until Populate Defaults. */
    private final Set<String> currentDeletedComputedKeys = new LinkedHashSet<>();

    /** Wired by StartupConnectionTab to supply the current configuration on Populate Defaults. */
    private Runnable populateDefaultsListener;

    public EnvVarPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        buildPanel();
    }

    // =========================================================================
    // Panel Construction
    // =========================================================================

    private void buildPanel() {
        add(new TitledSeparator("Environment Variables"), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());

        passParentEnvsCB = new JBCheckBox("Pass environment variables", true);
        passParentEnvsCB.setBorder(JBUI.Borders.emptyBottom(6));
        center.add(passParentEnvsCB, BorderLayout.NORTH);

        String[] cols = {"Name", "Value"};
        envModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        envTable = new JBTable(envModel);
        envTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        envTable.setRowHeight(JBUI.scale(24));
        envTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        envTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        envTable.setDefaultRenderer(Object.class, new ComputedVarCellRenderer());

        envTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && envTable.getSelectedRow() >= 0) {
                    editSelectedEnvVar();
                }
            }
        });

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(envTable)
                .setAddAction(button -> addEnvVar())
                .setRemoveAction(button -> removeEnvVar())
                .setEditAction(button -> editSelectedEnvVar())
                .disableUpDownActions();

        decorator.addExtraAction(new DumbAwareAction(
                "Populate Defaults", "Populate default environment variables", AllIcons.Actions.Rollback) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (populateDefaultsListener != null) populateDefaultsListener.run();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });

        decorator.addExtraAction(new DumbAwareAction(
                "Copy", "Copy selected environment variable", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                copyEnvVar();
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(envTable.getSelectedRow() >= 0);
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });

        decorator.addExtraAction(new DumbAwareAction(
                "Paste", "Paste environment variable", AllIcons.Actions.MenuPaste) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                pasteEnvVar();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        });

        JComponent envTablePanel = decorator.createPanel();
        envTablePanel.setPreferredSize(new Dimension(0, JBUI.scale(150)));
        center.add(envTablePanel, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
    }

    /** Called by StartupConnectionTab to wire up the Populate Defaults toolbar action. */
    public void setPopulateDefaultsAction(@NotNull Runnable action) {
        this.populateDefaultsListener = action;
    }

    // =========================================================================
    // State Save / Restore
    // =========================================================================

    /**
     * Captures the current table contents and tracking sets into a {@link State} snapshot.
     * Called by StartupConnectionTab before switching modes.
     */
    @NotNull
    public State saveState() {
        State state = new State();
        state.passParentEnvs = passParentEnvsCB.isSelected();
        for (int i = 0; i < envModel.getRowCount(); i++) {
            String name = ((String) envModel.getValueAt(i, 0)).trim();
            String value = ((String) envModel.getValueAt(i, 1)).trim();
            if (!StringUtil.isEmpty(name)) {
                state.envVars.put(name, value);
            }
        }
        state.computedKeys.addAll(currentComputedKeys);
        state.deletedComputedKeys.addAll(currentDeletedComputedKeys);
        return state;
    }

    /**
     * Populates the table and tracking sets from a {@link State} snapshot.
     * Called by StartupConnectionTab after switching modes.
     */
    public void restoreState(@NotNull State state) {
        passParentEnvsCB.setSelected(state.passParentEnvs);
        currentComputedKeys.clear();
        currentComputedKeys.addAll(state.computedKeys);
        currentDeletedComputedKeys.clear();
        currentDeletedComputedKeys.addAll(state.deletedComputedKeys);
        envModel.setRowCount(0);
        state.envVars.forEach((k, v) -> envModel.addRow(new Object[]{k, v}));
    }

    // =========================================================================
    // Populate Defaults
    // =========================================================================

    /**
     * Resets the table to auto-managed defaults derived from the given configuration,
     * preserving any user-added variables that aren't in the computed key set.
     */
    public void populateDefaults(@NotNull TomcatRunConfiguration configuration) {
        Map<String, String> defaults = RuntimeEnvResolver.computeDefaults(configuration.getConfigData());

        Map<String, String> userOnly = new LinkedHashMap<>();
        for (int i = 0; i < envModel.getRowCount(); i++) {
            String name = (String) envModel.getValueAt(i, 0);
            String value = (String) envModel.getValueAt(i, 1);
            if (!RuntimeEnvResolver.COMPUTED_KEYS.contains(name) && !defaults.containsKey(name)) {
                userOnly.put(name, value);
            }
        }

        currentComputedKeys.clear();
        currentDeletedComputedKeys.clear();
        envModel.setRowCount(0);

        for (String key : RuntimeEnvResolver.COMPUTED_KEYS) {
            String value = defaults.get(key);
            if (value != null) {
                envModel.addRow(new Object[]{key, value});
                currentComputedKeys.add(key);
            }
        }
        userOnly.forEach((k, v) -> envModel.addRow(new Object[]{k, v}));
        // Don't touch passParentEnvsCB — it's unrelated to env var defaults
        LOG.info("Reset environment variables to defaults");
    }

    // =========================================================================
    // Toolbar Actions
    // =========================================================================

    private void addEnvVar() {
        EnvVarDialog dlg = new EnvVarDialog(project, null, null);
        if (dlg.showAndGet()) {
            String[] v = dlg.getVar();
            String name = v[0];
            if (hasDuplicate(name)) {
                Messages.showErrorDialog(project, "Duplicate variable name: " + name, "Error");
                return;
            }
            envModel.addRow(v);
            currentComputedKeys.remove(name);
            // User explicitly re-added a previously deleted computed key — honour their intent
            currentDeletedComputedKeys.remove(name);
        }
    }

    private void editSelectedEnvVar() {
        int row = envTable.getSelectedRow();
        if (row < 0) return;

        String oldName = (String) envModel.getValueAt(row, 0);
        String oldValue = (String) envModel.getValueAt(row, 1);

        EnvVarDialog dlg = new EnvVarDialog(project, oldName, oldValue);
        if (dlg.showAndGet()) {
            String[] v = dlg.getVar();
            String newName = v[0];
            for (int i = 0; i < envModel.getRowCount(); i++) {
                if (i != row && newName.equals(envModel.getValueAt(i, 0))) {
                    Messages.showErrorDialog(project, "Duplicate variable name: " + newName, "Error");
                    return;
                }
            }
            envModel.setValueAt(newName, row, 0);
            envModel.setValueAt(v[1], row, 1);
            // Editing promotes computed → user-modified
            currentComputedKeys.remove(oldName);
            currentComputedKeys.remove(newName);
            // If the user edits a key back to a previously deleted computed name,
            // clear the deletion so ensureComputedEnvVars won't strip it at launch
            currentDeletedComputedKeys.remove(newName);
            envTable.repaint();
        }
    }

    private void removeEnvVar() {
        int row = envTable.getSelectedRow();
        if (row < 0) return;

        String name = (String) envModel.getValueAt(row, 0);
        if (currentComputedKeys.remove(name)) {
            currentDeletedComputedKeys.add(name);
        }
        envModel.removeRow(row);
        if (envModel.getRowCount() > 0) {
            int newSel = Math.min(row, envModel.getRowCount() - 1);
            envTable.setRowSelectionInterval(newSel, newSel);
        }
    }

    private void copyEnvVar() {
        int row = envTable.getSelectedRow();
        if (row < 0) return;
        String name = (String) envModel.getValueAt(row, 0);
        String value = (String) envModel.getValueAt(row, 1);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(name + "=" + value), null);
    }

    private void pasteEnvVar() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String text = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (text == null || text.isEmpty()) return;
            int eq = text.indexOf('=');
            if (eq > 0) {
                String name = text.substring(0, eq).trim();
                String value = text.substring(eq + 1).trim();
                if (!name.isEmpty() && !hasDuplicate(name)) {
                    envModel.addRow(new String[]{name, value});
                    currentComputedKeys.remove(name);
                    currentDeletedComputedKeys.remove(name);
                }
            }
        } catch (Exception ex) {
            LOG.debug("Paste failed: " + ex.getMessage());
        }
    }

    private boolean hasDuplicate(String name) {
        for (int i = 0; i < envModel.getRowCount(); i++) {
            if (name.equals(envModel.getValueAt(i, 0))) return true;
        }
        return false;
    }

    // =========================================================================
    // State Initialisation (package-private for testing)
    // =========================================================================

    /**
     * Initialises a {@link State} from persisted {@link RunnerSettings} and computed defaults.
     *
     * <p>Three cases:
     * <ol>
     *   <li>Persisted computed/deleted key sets present → restore exactly as saved.</li>
     *   <li>No persisted vars → seed from computed defaults.</li>
     *   <li>Persisted vars but no key-set metadata → heuristically promote matching defaults.</li>
     * </ol>
     */
    static void initializeState(@NotNull State state,
                                @NotNull Map<String, String> defaults,
                                @NotNull RunnerSettings runnerSettings) {
        state.envVars.clear();
        state.computedKeys.clear();
        state.deletedComputedKeys.clear();
        state.passParentEnvs = runnerSettings.isPassParentEnvs();

        Map<String, String> persisted = runnerSettings.getEnvironmentVariables();
        Set<String> persistedComputed = runnerSettings.getComputedEnvironmentKeys();
        Set<String> persistedDeleted = runnerSettings.getDeletedComputedEnvironmentKeys();

        state.deletedComputedKeys.addAll(persistedDeleted);

        if (!persistedComputed.isEmpty() || !persistedDeleted.isEmpty()) {
            state.envVars.putAll(persisted);
            state.computedKeys.addAll(persistedComputed);
            return;
        }

        if (persisted.isEmpty()) {
            for (String key : RuntimeEnvResolver.COMPUTED_KEYS) {
                String value = defaults.get(key);
                if (!StringUtil.isEmpty(value)) {
                    state.envVars.put(key, value);
                    state.computedKeys.add(key);
                }
            }
            return;
        }

        state.envVars.putAll(persisted);
        for (String key : RuntimeEnvResolver.COMPUTED_KEYS) {
            if (!persisted.containsKey(key)) continue;
            String computedVal = defaults.get(key);
            String persistedVal = persisted.get(key);
            if (!StringUtil.isEmpty(computedVal) && Objects.equals(computedVal, persistedVal)) {
                state.computedKeys.add(key);
            }
        }
    }

    // =========================================================================
    // Cell Renderer
    // =========================================================================

    /** Renders computed defaults in gray italic; user vars render normally. */
    private class ComputedVarCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (row < envModel.getRowCount()) {
                String name = (String) envModel.getValueAt(row, 0);
                boolean isComputed = currentComputedKeys.contains(name);
                if (isComputed && !isSelected) {
                    c.setForeground(NamedColorUtil.getInactiveTextColor());
                    c.setFont(c.getFont().deriveFont(Font.ITALIC));
                } else if (!isSelected) {
                    c.setForeground(table.getForeground());
                    c.setFont(table.getFont());
                }
            }
            return c;
        }
    }

    // =========================================================================
    // Add / Edit Dialog
    // =========================================================================

    private static class EnvVarDialog extends DialogWrapper {
        private final JBTextField nameF = new JBTextField(25);
        private final JBTextField valueF = new JBTextField(25);
        private final ComboBox<String> commonCombo = new ComboBox<>(new String[]{
                "Custom Variable", "JAVA_OPTS", "CATALINA_OPTS", "CATALINA_HOME",
                "CATALINA_BASE", "JAVA_HOME", "CLASSPATH", "PATH"
        });

        EnvVarDialog(@NotNull Project p, @Nullable String name, @Nullable String value) {
            super(p);
            setTitle(name == null ? "Add Environment Variable" : "Edit Environment Variable");
            if (name != null) {
                nameF.setText(name);
                valueF.setText(value != null ? value : "");
            }
            commonCombo.addActionListener(e -> {
                String sel = (String) commonCombo.getSelectedItem();
                if ("Custom Variable".equals(sel) || sel == null) return;
                nameF.setText(sel);
                String varValue = switch (sel) {
                    case "JAVA_OPTS"     -> DynamicTomcatEnvironment.buildJavaOpts();
                    case "CATALINA_OPTS" -> DynamicTomcatEnvironment.buildCatalinaOpts();
                    case "JAVA_HOME"     -> System.getenv("JAVA_HOME");
                    case "CLASSPATH"     -> System.getenv("CLASSPATH");
                    case "PATH"          -> System.getenv("PATH");
                    default              -> "";
                };
                valueF.setText(StringUtil.notNullize(varValue));
            });
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(15));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = JBUI.insets(5);
            g.anchor = GridBagConstraints.WEST;

            g.gridx = 0; g.gridy = 0;
            panel.add(new JBLabel("Common:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(commonCombo, g);

            g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
            panel.add(new JBLabel("Name:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(nameF, g);

            g.gridx = 0; g.gridy = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
            panel.add(new JBLabel("Value:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(valueF, g);

            return panel;
        }

        @Override
        protected void doOKAction() {
            if (StringUtil.isEmpty(nameF.getText().trim())) {
                Messages.showErrorDialog(getContentPane(), "Variable name is required", "Validation");
                nameF.requestFocus();
                return;
            }
            super.doOKAction();
        }

        String[] getVar() { return new String[]{nameF.getText().trim(), valueF.getText().trim()}; }
    }
}
