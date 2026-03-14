package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.ui.components.JBPanel;
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
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * Startup/Connection tab — mirrors IntelliJ Ultimate's Tomcat run configuration behavior.
 *
 * <p>Architecture:
 * <ul>
 *   <li><b>Computed defaults</b> — only JAVA_OPTS is auto-managed here, and only when
 *       VM options are defined on the Server tab.</li>
 *   <li><b>User ownership tracking</b> — Each env var is either "computed" (auto-managed) or
 *       "user-modified" (edited/added by the user). Computed vars auto-refresh when VM options
 *       or Tomcat server change; user-modified vars are never overwritten.</li>
 *   <li><b>Double-click edit</b> — Editing a computed var promotes it to user-modified.</li>
 *   <li><b>Reset Defaults</b> — Restores all computed defaults, clearing user modifications.</li>
 *   <li><b>Per-mode state</b> — Run/Debug/Coverage/Profile each have independent env var maps.</li>
 * </ul>
 */
public class StartupConnectionTab extends JBPanel<StartupConnectionTab> {

    private static final Logger LOG = Logger.getInstance(StartupConnectionTab.class);

    private final Project project;
    private TomcatRunConfiguration configuration;

    private static final String RUN_MODE = "Run";
    private static final String DEBUG_MODE = "Debug";
    private static final String COVERAGE_MODE = "Coverage";
    private static final String PROFILE_MODE = "Profile";

    private static final String[] ALL_MODES = {RUN_MODE, DEBUG_MODE, COVERAGE_MODE, PROFILE_MODE};

    /** Computed env var keys, delegated to RuntimeEnvResolver. */
    private static final Set<String> COMPUTED_KEYS = RuntimeEnvResolver.COMPUTED_KEYS;

    private JBList<String> modeList;
    private String selectedMode = RUN_MODE;

    private final Map<String, UIState> modeStates = new HashMap<>();

    /**
     * Per-mode UI snapshot. Tracks both the env var values and which keys are
     * still "computed" (auto-managed) vs "user-modified" (user took ownership).
     */
    private static class UIState {
        boolean useDefaultStartup = true;
        String startupScript = "";
        boolean useDefaultShutdown = true;
        String shutdownScript = "";
        boolean passParentEnvs = true;
        /** All env vars (computed + user). Insertion order preserved. */
        Map<String, String> envVars = new LinkedHashMap<>();
        /** Keys still auto-managed — refresh updates only these. */
        Set<String> computedKeys = new LinkedHashSet<>();
        /** Keys the user explicitly deleted — never auto-restore until Reset Defaults. */
        Set<String> deletedComputedKeys = new LinkedHashSet<>();
    }

    private JPanel startupSection;
    private JPanel shutdownSection;
    private TextFieldWithBrowseButton startupScriptField;
    private TextFieldWithBrowseButton shutdownScriptField;
    private JBCheckBox useDefaultStartupCB;
    private JBCheckBox useDefaultShutdownCB;

    private JBTable envTable;
    private DefaultTableModel envModel;
    private JBCheckBox passParentEnvsCB;

    public StartupConnectionTab(@NotNull Project project, @NotNull TomcatRunConfiguration configuration) {
        this.project = project;
        this.configuration = configuration;
        initUI();
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private void initUI() {
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(JBUI.Borders.empty(8, 12, 8, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridy = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(createModeSelector(), gbc);

        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(4, 0);
        startupSection = createStartupSection();
        mainPanel.add(startupSection, gbc);

        gbc.gridy = 2;
        shutdownSection = createShutdownSection();
        mainPanel.add(shutdownSection, gbc);

        gbc.gridy = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(10, 0, 0, 0);
        mainPanel.add(createEnvSection(), gbc);

        JBScrollPane scrollPane = new JBScrollPane(mainPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JComponent createModeSelector() {
        modeList = new JBList<>(ALL_MODES);
        modeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modeList.setSelectedIndex(0);
        modeList.setVisibleRowCount(ALL_MODES.length);

        modeList.setCellRenderer(new com.intellij.ui.SimpleListCellRenderer<String>() {
            @Override
            public void customize(@NotNull JList<? extends String> list, String value, int index,
                                  boolean selected, boolean hasFocus) {
                setBorder(JBUI.Borders.empty(4, 8));
                if (value != null) {
                    switch (value) {
                        case RUN_MODE -> setIcon(AllIcons.Actions.Execute);
                        case DEBUG_MODE -> setIcon(AllIcons.Actions.StartDebugger);
                        case COVERAGE_MODE -> {
                            setIcon(AllIcons.General.RunWithCoverage);
                            setText("Cover");
                        }
                        case PROFILE_MODE -> setIcon(AllIcons.Actions.ProfileCPU);
                    }
                }
            }
        });

        modeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String newMode = modeList.getSelectedValue();
                if (newMode != null && !newMode.equals(selectedMode)) {
                    saveCurrentState();
                    selectedMode = newMode;
                    restoreCurrentState();
                    LOG.debug("Mode switched to: " + newMode);
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(modeList);
        scrollPane.setPreferredSize(new Dimension(0, JBUI.scale(120)));
        scrollPane.setMinimumSize(new Dimension(0, JBUI.scale(120)));
        return scrollPane;
    }

    private JPanel createStartupSection() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0;
        g.gridy = 0;
        p.add(new JBLabel("Startup script:"), g);

        g.gridx = 1;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        startupScriptField = new TextFieldWithBrowseButton();
        com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                startupScriptField, "Select Startup Script",
                "Choose a custom startup script for Tomcat", project, scriptFileDescriptor());
        p.add(startupScriptField, g);

        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2);
        useDefaultStartupCB = new JBCheckBox("Use default", true);
        useDefaultStartupCB.addActionListener(e -> updateStartupState());
        p.add(useDefaultStartupCB, g);

        return p;
    }

    private JPanel createShutdownSection() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0;
        g.gridy = 0;
        p.add(new JBLabel("Shutdown script:"), g);

        g.gridx = 1;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        shutdownScriptField = new TextFieldWithBrowseButton();
        com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                shutdownScriptField, "Select Shutdown Script",
                "Choose a custom shutdown script for Tomcat", project, scriptFileDescriptor());
        p.add(shutdownScriptField, g);

        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2);
        useDefaultShutdownCB = new JBCheckBox("Use default", true);
        useDefaultShutdownCB.addActionListener(e -> updateShutdownState());
        p.add(useDefaultShutdownCB, g);

        return p;
    }

    private JPanel createEnvSection() {
        JPanel p = new JPanel(new BorderLayout());

        p.add(new TitledSeparator("Environment Variables"), BorderLayout.NORTH);

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

        // Computed defaults render in gray italic; user vars render normally
        envTable.setDefaultRenderer(Object.class, new ComputedVarCellRenderer());

        // Double-click to edit (promotes computed → user-modified)
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

        decorator.addExtraAction(new com.intellij.ui.AnActionButton("Populate Defaults", AllIcons.Actions.Rollback) {
            @Override
            public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                populateDefaults();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        decorator.addExtraAction(new com.intellij.ui.AnActionButton("Copy", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                copyEnvVar();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public boolean isEnabled() {
                return envTable.getSelectedRow() >= 0;
            }
        });

        decorator.addExtraAction(new com.intellij.ui.AnActionButton("Paste", AllIcons.Actions.MenuPaste) {
            @Override
            public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                pasteEnvVar();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        JComponent envTablePanel = decorator.createPanel();
        envTablePanel.setPreferredSize(new Dimension(0, JBUI.scale(150)));
        center.add(envTablePanel, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);

        return p;
    }

    /**
     * Cell renderer that visually distinguishes computed defaults from user-defined vars.
     * Computed defaults render in gray italic; user vars render normally.
     */
    private class ComputedVarCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (row < envModel.getRowCount()) {
                String name = (String) envModel.getValueAt(row, 0);
                UIState state = modeStates.get(selectedMode);
                boolean isComputed = state != null && state.computedKeys.contains(name);

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
    // Remote Mode
    // =========================================================================

    /**
     * Adjusts the tab content for remote vs local server mode.
     * Remote mode hides startup/shutdown script sections because the remote
     * server is already running — scripts are irrelevant.
     * Environment variables are still shown (used for Manager API connection).
     */
    public void setRemoteMode(boolean remote) {
        if (startupSection != null) {
            startupSection.setVisible(!remote);
        }
        if (shutdownSection != null) {
            shutdownSection.setVisible(!remote);
        }
        revalidate();
        repaint();
    }

    // =========================================================================
    // Computed Defaults
    // =========================================================================

    /**
     * Computes Startup/Connection env vars derived from the current configuration.
     * Delegates to {@link RuntimeEnvResolver} so all derivation rules are centralized.
     */
    private Map<String, String> computeDefaultEnvVars(@NotNull TomcatRunConfiguration cfg) {
        return RuntimeEnvResolver.computeDefaults(cfg.getConfigData());
    }

    /**
     * Refreshes computed env vars to reflect the current state from the Server tab.
     * Called by the editor on tab switch.
     */
    public void refreshComputedEnvVars(@NotNull TomcatRunConfiguration cfg) {
        this.configuration = cfg;

        // Refresh computed env var values (only keys the user hasn't edited)
        saveCurrentState();
        Map<String, String> freshDefaults = computeDefaultEnvVars(cfg);
        for (UIState state : modeStates.values()) {
            for (String key : COMPUTED_KEYS) {
                if (!state.computedKeys.contains(key)) {
                    continue;
                }
                String value = freshDefaults.get(key);
                if (value != null) {
                    state.envVars.put(key, value);
                } else {
                    state.envVars.remove(key);
                    state.computedKeys.remove(key);
                }
            }
        }

        restoreCurrentState();
        LOG.debug("Refreshed computed env vars from Server tab state");
    }

    /** Populates the current mode's env vars with the current auto-managed values. */
    private void populateDefaults() {
        UIState state = modeStates.computeIfAbsent(selectedMode, k -> new UIState());
        Map<String, String> defaults = computeDefaultEnvVars(configuration);

        // Preserve user-added vars that aren't in the computed set
        Map<String, String> userOnly = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : state.envVars.entrySet()) {
            if (!COMPUTED_KEYS.contains(e.getKey()) && !defaults.containsKey(e.getKey())) {
                userOnly.put(e.getKey(), e.getValue());
            }
        }

        state.envVars.clear();
        state.computedKeys.clear();
        state.deletedComputedKeys.clear();

        // Computed defaults first (in stable order), then user-added vars
        for (String key : COMPUTED_KEYS) {
            if (defaults.containsKey(key)) {
                state.envVars.put(key, defaults.get(key));
                state.computedKeys.add(key);
            }
        }
        state.envVars.putAll(userOnly);

        restoreCurrentState();
        LOG.info("Reset environment variables to defaults for mode: " + selectedMode);
    }

    // =========================================================================
    // Env Var Actions
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
            // User-added var is not tracked as computed
            UIState state = modeStates.get(selectedMode);
            if (state != null) {
                state.computedKeys.remove(name);
            }
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

            // Check duplicate (skip self)
            for (int i = 0; i < envModel.getRowCount(); i++) {
                if (i != row && newName.equals(envModel.getValueAt(i, 0))) {
                    Messages.showErrorDialog(project, "Duplicate variable name: " + newName, "Error");
                    return;
                }
            }

            envModel.setValueAt(newName, row, 0);
            envModel.setValueAt(v[1], row, 1);

            // Editing promotes computed → user-modified (won't auto-refresh)
            UIState state = modeStates.get(selectedMode);
            if (state != null) {
                state.computedKeys.remove(oldName);
                state.computedKeys.remove(newName);
            }
            envTable.repaint();
        }
    }

    private void removeEnvVar() {
        int row = envTable.getSelectedRow();
        if (row < 0) return;

        String name = (String) envModel.getValueAt(row, 0);
        UIState state = modeStates.get(selectedMode);
        if (state != null) {
            // Track that user explicitly deleted this computed key
            if (state.computedKeys.remove(name)) {
                state.deletedComputedKeys.add(name);
            }
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
        String text = name + "=" + value;
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
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
                    // Pasted var is user-owned
                    UIState state = modeStates.get(selectedMode);
                    if (state != null) {
                        state.computedKeys.remove(name);
                    }
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
    // Script Helpers
    // =========================================================================

    private FileChooserDescriptor scriptFileDescriptor() {
        FileChooserDescriptor d = new FileChooserDescriptor(true, false, false, false, false, false);
        d.setTitle("Select Script File");
        d.setDescription("Choose a startup or shutdown script file");
        d.withFileFilter(f -> {
            String n = f.getName().toLowerCase();
            return n.endsWith(".bat") || n.endsWith(".sh") || n.endsWith(".cmd");
        });
        return d;
    }

    private String defaultStartupScript() {
        return scriptPath("catalina", "run");
    }

    private String defaultShutdownScript() {
        return scriptPath("catalina", "stop");
    }

    private String scriptPath(String base, String command) {
        TomcatInfo ti = configuration.getTomcatInfo();
        if (ti == null) return "";
        String bin = Paths.get(ti.getPath(), "bin").toString();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String script = os.contains("win") ? base + ".bat" : base + ".sh";
        return bin + File.separator + script + " " + command;
    }

    private void updateStartupState() {
        boolean useDef = useDefaultStartupCB.isSelected();
        startupScriptField.setEnabled(!useDef);
        if (useDef) startupScriptField.setText(defaultStartupScript());
    }

    private void updateShutdownState() {
        boolean useDef = useDefaultShutdownCB.isSelected();
        shutdownScriptField.setEnabled(!useDef);
        if (useDef) shutdownScriptField.setText(defaultShutdownScript());
    }

    // =========================================================================
    // State Management (per-mode save/restore)
    // =========================================================================

    public void resetFrom(@NotNull TomcatRunConfiguration cfg) {
        this.configuration = cfg;
        modeStates.clear();

        Map<String, String> defaults = computeDefaultEnvVars(cfg);

        for (String mode : ALL_MODES) {
            UIState state = new UIState();
            var runnerSettings = cfg.getConfigData().getRunnerSettings(mode);

            String startup = runnerSettings.getStartupScript();
            state.useDefaultStartup = StringUtil.isEmpty(startup);
            state.startupScript = state.useDefaultStartup ? defaultStartupScript() : startup;

            String shutdown = runnerSettings.getShutdownScript();
            state.useDefaultShutdown = StringUtil.isEmpty(shutdown);
            state.shutdownScript = state.useDefaultShutdown ? defaultShutdownScript() : shutdown;

            state.passParentEnvs = runnerSettings.isPassParentEnvs();

            initializeComputedEnvState(state, defaults, runnerSettings);

            modeStates.put(mode, state);
        }

        selectedMode = RUN_MODE;
        modeList.setSelectedIndex(0);
        restoreCurrentState();

        LOG.debug("StartupConnectionTab reset: "
                + modeStates.values().stream().mapToInt(s -> s.computedKeys.size()).sum() + " auto-managed env keys");
    }

    private void saveCurrentState() {
        UIState state = modeStates.computeIfAbsent(selectedMode, k -> new UIState());
        state.useDefaultStartup = useDefaultStartupCB.isSelected();
        state.startupScript = startupScriptField.getText();
        state.useDefaultShutdown = useDefaultShutdownCB.isSelected();
        state.shutdownScript = shutdownScriptField.getText();
        state.passParentEnvs = passParentEnvsCB.isSelected();

        state.envVars.clear();
        for (int i = 0; i < envModel.getRowCount(); i++) {
            String name = ((String) envModel.getValueAt(i, 0)).trim();
            String value = ((String) envModel.getValueAt(i, 1)).trim();
            if (!StringUtil.isEmpty(name)) {
                state.envVars.put(name, value);
            }
        }
        // computedKeys and deletedComputedKeys are maintained by add/edit/remove actions
    }

    static void initializeComputedEnvState(@NotNull UIState state,
                                           @NotNull Map<String, String> defaults,
                                           @NotNull com.dev.idea.plugins.tomcat.model.RunnerSettings runnerSettings) {
        state.envVars.clear();
        state.computedKeys.clear();
        state.deletedComputedKeys.clear();

        Map<String, String> persisted = runnerSettings.getEnvironmentVariables();
        Set<String> persistedComputed = runnerSettings.getComputedEnvironmentKeys();
        Set<String> persistedDeleted = runnerSettings.getDeletedComputedEnvironmentKeys();

        state.deletedComputedKeys.addAll(persistedDeleted);

        if (!persistedComputed.isEmpty() || !persistedDeleted.isEmpty()) {
            state.envVars.putAll(persisted);
            state.computedKeys.addAll(persistedComputed);
            return;
        }

        boolean hasPersistedVars = !persisted.isEmpty();
        if (!hasPersistedVars) {
            for (String key : COMPUTED_KEYS) {
                String value = defaults.get(key);
                if (!StringUtil.isEmpty(value)) {
                    state.envVars.put(key, value);
                    state.computedKeys.add(key);
                }
            }
            return;
        }

        state.envVars.putAll(persisted);
        for (String key : COMPUTED_KEYS) {
            if (!persisted.containsKey(key)) continue;

            String computedVal = defaults.get(key);
            String persistedVal = persisted.get(key);
            if (!StringUtil.isEmpty(computedVal) && Objects.equals(computedVal, persistedVal)) {
                state.computedKeys.add(key);
            }
        }
    }

    private void restoreCurrentState() {
        UIState state = modeStates.computeIfAbsent(selectedMode, k -> new UIState());

        useDefaultStartupCB.setSelected(state.useDefaultStartup);
        startupScriptField.setText(state.startupScript);
        updateStartupState();

        useDefaultShutdownCB.setSelected(state.useDefaultShutdown);
        shutdownScriptField.setText(state.shutdownScript);
        updateShutdownState();

        passParentEnvsCB.setSelected(state.passParentEnvs);

        envModel.setRowCount(0);
        state.envVars.forEach((k, v) -> envModel.addRow(new Object[]{k, v}));
    }

    public void applyTo(@NotNull TomcatRunConfiguration cfg) throws ConfigurationException {
        saveCurrentState();

        // Scripts are only relevant for local mode — remote server is already running
        boolean scriptsApplicable = startupSection != null && startupSection.isVisible();

        for (Map.Entry<String, UIState> entry : modeStates.entrySet()) {
            String mode = entry.getKey();
            UIState state = entry.getValue();
            var runnerSettings = cfg.getConfigData().getRunnerSettings(mode);

            if (scriptsApplicable && !state.useDefaultStartup) {
                String path = state.startupScript.trim();
                if (StringUtil.isEmpty(path)) throw new ConfigurationException("Startup script path is required for mode " + mode);
                File f = new File(path);
                if (!f.exists()) throw new ConfigurationException("Startup script does not exist for mode " + mode + ": " + path);
                runnerSettings.setStartupScript(path);
            } else {
                runnerSettings.setStartupScript(null);
            }

            if (scriptsApplicable && !state.useDefaultShutdown) {
                String path = state.shutdownScript.trim();
                if (StringUtil.isEmpty(path)) throw new ConfigurationException("Shutdown script path is required for mode " + mode);
                File f = new File(path);
                if (!f.exists()) throw new ConfigurationException("Shutdown script does not exist for mode " + mode + ": " + path);
                runnerSettings.setShutdownScript(path);
            } else {
                runnerSettings.setShutdownScript(null);
            }

            runnerSettings.setEnvironmentVariables(new LinkedHashMap<>(state.envVars));
            runnerSettings.setComputedEnvironmentKeys(new LinkedHashSet<>(state.computedKeys));
            runnerSettings.setDeletedComputedEnvironmentKeys(new LinkedHashSet<>(state.deletedComputedKeys));
            runnerSettings.setPassParentEnvs(state.passParentEnvs);
        }

        LOG.info("StartupConnectionTab applied for all modes");
    }

    // =========================================================================
    // Env Var Dialog
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
            if (name != null) { nameF.setText(name); valueF.setText(value != null ? value : ""); }

            commonCombo.addActionListener(e -> {
                String sel = (String) commonCombo.getSelectedItem();
                if ("Custom Variable".equals(sel) || sel == null) return;

                nameF.setText(sel);

                String varValue = switch (sel) {
                    case "JAVA_OPTS"      -> DynamicTomcatEnvironment.buildJavaOpts();
                    case "CATALINA_OPTS"  -> DynamicTomcatEnvironment.buildCatalinaOpts();
                    case "CATALINA_HOME"  -> "";
                    case "CATALINA_BASE"  -> "";
                    case "JAVA_HOME"      -> System.getenv("JAVA_HOME");
                    case "CLASSPATH"      -> System.getenv("CLASSPATH");
                    case "PATH"           -> System.getenv("PATH");
                    default               -> "";
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
