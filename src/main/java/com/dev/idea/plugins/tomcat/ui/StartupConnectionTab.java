package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Startup/Connection tab — mirrors IntelliJ Ultimate's Tomcat run configuration behavior.
 *
 * <p>Architecture:
 * <ul>
 *   <li><b>Horizontal mode tabs</b> — Run | Debug | Cover | Profile, matching IntelliJ Ultimate layout.</li>
 *   <li><b>Mode-aware content</b> — Local: startup/shutdown scripts + env vars.
 *       Remote + Debug: host/port connection fields + env vars.
 *       Remote + Run/Cover/Profile: env vars only.</li>
 *   <li><b>Computed defaults</b> — only JAVA_OPTS is auto-managed here, and only when
 *       VM options are defined on the Server tab.</li>
 *   <li><b>User ownership tracking</b> — Each env var is either "computed" (auto-managed) or
 *       "user-modified" (edited/added by the user). Computed vars auto-refresh when VM options
 *       or Tomcat server change; user-modified vars are never overwritten.</li>
 *   <li><b>Per-mode state</b> — Run/Debug/Coverage/Profile each have independent env var maps,
 *       managed by the embedded {@link EnvVarPanel}.</li>
 * </ul>
 */
public class StartupConnectionTab extends JBPanel<StartupConnectionTab> {

    private static final Logger LOG = Logger.getInstance(StartupConnectionTab.class);

    private final Project project;
    private TomcatRunConfiguration configuration;

    private static final String RUN_MODE      = "Run";
    private static final String DEBUG_MODE    = "Debug";
    private static final String COVERAGE_MODE = "Coverage";
    private static final String PROFILE_MODE  = "Profile";

    private static final String[] ALL_MODES = {RUN_MODE, DEBUG_MODE, COVERAGE_MODE, PROFILE_MODE};

    private String selectedMode = RUN_MODE;
    private final Map<String, JToggleButton> modeButtons = new LinkedHashMap<>();
    private ButtonGroup modeButtonGroup;
    private boolean remoteMode = false;

    private final Map<String, UIState> modeStates = new HashMap<>();

    /**
     * Per-mode UI snapshot. Script/connection state owned here;
     * env var state delegated to {@link EnvVarPanel.State}.
     */
    private static class UIState {
        boolean useDefaultStartup = true;
        String startupScript = "";
        boolean useDefaultShutdown = true;
        String shutdownScript = "";
        /** Remote debug host (only used in Remote + Debug mode). */
        String debugHost = "localhost";
        EnvVarPanel.State envState = new EnvVarPanel.State();
    }

    // Script sections (local mode only)
    private JPanel startupSection;
    private JPanel shutdownSection;
    private TextFieldWithBrowseButton startupScriptField;
    private TextFieldWithBrowseButton shutdownScriptField;
    private JBCheckBox useDefaultStartupCB;
    private JBCheckBox useDefaultShutdownCB;

    // Debug section (Debug mode only; shown in both local and remote)
    private JPanel debugSection;
    private JBLabel debugHostLabel;
    private JBTextField debugHostField;
    private JBRadioButton transportSocketRadio;
    private JBRadioButton transportShmemRadio;
    private JBTextField debugPortField;
    private JButton debuggerSettingsButton;

    // Env var panel
    private EnvVarPanel envVarPanel;

    // Content panel that holds mode-specific sections
    private JPanel contentPanel;

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
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(createModeTabBar(), gbc);

        gbc.gridy = 1;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(0);
        contentPanel = new JPanel(new GridBagLayout());
        rebuildContentPanel();
        mainPanel.add(contentPanel, gbc);

        JBScrollPane scrollPane = new JBScrollPane(mainPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Creates a horizontal tab bar with toggle buttons for each mode (Run | Debug | Cover | Profile).
     */
    private JComponent createModeTabBar() {
        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabBar.setBorder(JBUI.Borders.emptyBottom(4));

        modeButtonGroup = new ButtonGroup();

        for (String mode : ALL_MODES) {
            JToggleButton btn = new JToggleButton();
            btn.setFocusPainted(false);
            btn.setBorder(JBUI.Borders.empty(6, 14));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            switch (mode) {
                case RUN_MODE -> {
                    btn.setText("Run");
                    btn.setIcon(AllIcons.Actions.Execute);
                }
                case DEBUG_MODE -> {
                    btn.setText("Debug");
                    btn.setIcon(AllIcons.Actions.StartDebugger);
                }
                case COVERAGE_MODE -> {
                    btn.setText("Cover");
                    btn.setIcon(AllIcons.General.RunWithCoverage);
                }
                case PROFILE_MODE -> {
                    btn.setText("Profile");
                    btn.setIcon(AllIcons.Actions.ProfileCPU);
                }
            }

            btn.addActionListener(e -> {
                if (!mode.equals(selectedMode)) {
                    saveCurrentState();
                    selectedMode = mode;
                    updateModeVisibility();
                    restoreCurrentState();
                    LOG.debug("Mode switched to: " + mode);
                }
            });

            modeButtonGroup.add(btn);
            modeButtons.put(mode, btn);
            tabBar.add(btn);
        }

        modeButtons.get(RUN_MODE).setSelected(true);
        return tabBar;
    }

    /**
     * Rebuilds the content panel with all sections. Call once during init;
     * visibility is controlled by {@link #updateModeVisibility()}.
     */
    private void rebuildContentPanel() {
        contentPanel.removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridy = 0;
        gbc.weighty = 0;
        gbc.insets = JBUI.insets(4, 0);
        startupSection = createStartupSection();
        contentPanel.add(startupSection, gbc);

        gbc.gridy = 1;
        shutdownSection = createShutdownSection();
        contentPanel.add(shutdownSection, gbc);

        gbc.gridy = 2;
        debugSection = createDebugSection();
        contentPanel.add(debugSection, gbc);

        gbc.gridy = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(10, 0, 0, 0);
        envVarPanel = new EnvVarPanel(project);
        envVarPanel.setPopulateDefaultsAction(() -> envVarPanel.populateDefaults(configuration));
        contentPanel.add(envVarPanel, gbc);

        updateModeVisibility();
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    /**
     * Creates the Debug configuration section — shown whenever Debug mode is selected
     * in either local or remote server mode. Matches IntelliJ Ultimate's layout:
     * <ul>
     *   <li><b>Host</b> — remote mode only (JDWP target host)</li>
     *   <li><b>Transport</b> — Socket / Shared memory radios
     *       (Socket-only in local mode; Tomcat's JDWP launcher doesn't support dt_shmem cross-platform)</li>
     *   <li><b>Port</b> — JDWP listen port (local) / attach port (remote)</li>
     *   <li><b>Debugger Settings…</b> — opens IDE-wide Debugger configurable</li>
     * </ul>
     */
    private JPanel createDebugSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(JBUI.Borders.emptyTop(4));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0; g.gridy = 0;
        g.gridwidth = 4;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        p.add(new TitledSeparator("Debug"), g);

        // Row: Host (remote mode only) — visibility toggled in updateModeVisibility()
        g.gridy = 1; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        debugHostLabel = new JBLabel("Host:");
        p.add(debugHostLabel, g);

        g.gridx = 1; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        debugHostField = new JBTextField();
        debugHostField.getEmptyText().setText("localhost");
        p.add(debugHostField, g);

        // Row: Transport radios
        g.gridy = 2; g.gridx = 0; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        p.add(new JBLabel("Transport:"), g);

        transportSocketRadio = new JBRadioButton("Socket", true);
        transportShmemRadio = new JBRadioButton("Shared memory");
        ButtonGroup transportGroup = new ButtonGroup();
        transportGroup.add(transportSocketRadio);
        transportGroup.add(transportShmemRadio);

        JPanel transportPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        transportPanel.add(transportSocketRadio);
        transportPanel.add(transportShmemRadio);

        g.gridx = 1; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = JBUI.insets(0, 0, 2, 0);
        p.add(transportPanel, g);

        // Row: Port + Debugger Settings button
        g.gridy = 3; g.gridx = 0; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        p.add(new JBLabel("Port:"), g);

        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 0;
        g.ipadx = JBUI.scale(40);
        debugPortField = new JBTextField(8);
        debugPortField.getEmptyText().setText(String.valueOf(DebugConfig.DEFAULT_DEBUG_PORT));
        p.add(debugPortField, g);
        g.ipadx = 0;

        // Spacer to push the button right
        g.gridx = 2; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        p.add(Box.createHorizontalGlue(), g);

        g.gridx = 3; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.anchor = GridBagConstraints.EAST;
        g.insets = JBUI.insets(4, 4, 2, 4);
        debuggerSettingsButton = new JButton("Debugger Settings...");
        debuggerSettingsButton.addActionListener(e -> openDebuggerSettings());
        p.add(debuggerSettingsButton, g);
        g.anchor = GridBagConstraints.WEST;

        return p;
    }

    /**
     * Opens IntelliJ's IDE-wide Debugger settings page. Uses the display name lookup
     * so the call remains stable across IntelliJ versions (the internal configurable ID
     * has changed over time, the display name has not).
     */
    private void openDebuggerSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Debugger");
    }

    private JPanel createStartupSection() {
        startupScriptField = new TextFieldWithBrowseButton();
        useDefaultStartupCB = new JBCheckBox("Use default", true);
        useDefaultStartupCB.addActionListener(e -> updateStartupState());
        return createScriptSection("Startup script:", "Select Startup Script",
                "Choose a custom startup script for Tomcat", startupScriptField, useDefaultStartupCB);
    }

    private JPanel createShutdownSection() {
        shutdownScriptField = new TextFieldWithBrowseButton();
        useDefaultShutdownCB = new JBCheckBox("Use default", true);
        useDefaultShutdownCB.addActionListener(e -> updateShutdownState());
        return createScriptSection("Shutdown script:", "Select Shutdown Script",
                "Choose a custom shutdown script for Tomcat", shutdownScriptField, useDefaultShutdownCB);
    }

    private JPanel createScriptSection(@NotNull String label, @NotNull String browseTitle,
                                        @NotNull String browseDescription,
                                        @NotNull TextFieldWithBrowseButton field,
                                        @NotNull JBCheckBox useDefaultCB) {
        SafeBrowseUtil.addBrowseFolderListener(
                field, browseTitle, browseDescription, project, scriptFileDescriptor());

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);
        g.gridx = 0; g.gridy = 0;
        p.add(new JBLabel(label), g);

        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        p.add(field, g);

        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2);
        p.add(useDefaultCB, g);

        return p;
    }

    // =========================================================================
    // Mode Visibility
    // =========================================================================

    /**
     * Updates section visibility based on current server mode (local/remote)
     * and selected runner mode (Run/Debug/Cover/Profile).
     *
     * <p>Rules:
     * <ul>
     *   <li>Startup/Shutdown scripts: local mode only.</li>
     *   <li>Debug section: Debug mode only (local OR remote).</li>
     *   <li>Host field inside Debug section: remote mode only.</li>
     *   <li>Transport radios: always locked to Socket (both disabled).
     *       Tomcat's JDWP launcher only binds {@code dt_socket}, and the data model
     *       stores a numeric port rather than a shared-memory address. The Shared-memory
     *       radio is rendered (to match IntelliJ Ultimate's visual layout) but disabled
     *       so users aren't misled into picking an unsupported transport.</li>
     * </ul>
     */
    private void updateModeVisibility() {
        boolean showScripts = !remoteMode;
        boolean showDebug = DEBUG_MODE.equals(selectedMode);

        if (startupSection != null) startupSection.setVisible(showScripts);
        if (shutdownSection != null) shutdownSection.setVisible(showScripts);
        if (debugSection != null) debugSection.setVisible(showDebug);

        if (debugHostField != null) {
            debugHostField.setVisible(remoteMode);
            debugHostLabel.setVisible(remoteMode);
        }

        if (transportSocketRadio != null && transportShmemRadio != null) {
            transportSocketRadio.setEnabled(false);
            transportShmemRadio.setEnabled(false);
            transportSocketRadio.setSelected(true);
        }

        if (contentPanel != null) {
            contentPanel.revalidate();
            contentPanel.repaint();
        }
    }

    /**
     * Adjusts the tab content for remote vs local server mode.
     * Called by TomcatConfigurationEditor when the server mode changes.
     */
    public void setRemoteMode(boolean remote) {
        this.remoteMode = remote;
        updateModeVisibility();
    }

    // =========================================================================
    // Computed Defaults
    // =========================================================================

    private Map<String, String> computeDefaultEnvVars(@NotNull TomcatRunConfiguration cfg) {
        return RuntimeEnvResolver.computeDefaults(cfg.getConfigData());
    }

    /**
     * Refreshes computed env vars to reflect the current state from the Server tab.
     * Called by the editor on tab switch.
     */
    public void refreshComputedEnvVars(@NotNull TomcatRunConfiguration cfg) {
        this.configuration = cfg;
        saveCurrentState();

        Map<String, String> freshDefaults = computeDefaultEnvVars(cfg);
        for (UIState state : modeStates.values()) {
            EnvVarPanel.State envState = state.envState;
            for (String key : RuntimeEnvResolver.COMPUTED_KEYS) {
                if (!envState.computedKeys.contains(key)) continue;
                String value = freshDefaults.get(key);
                if (value != null) {
                    envState.envVars.put(key, value);
                } else {
                    envState.envVars.remove(key);
                    envState.computedKeys.remove(key);
                }
            }
        }

        restoreCurrentState();
        LOG.debug("Refreshed computed env vars from Server tab state");
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
            RunnerSettings runnerSettings = cfg.getConfigData().getRunnerSettings(mode);

            String startup = runnerSettings.getStartupScript();
            state.useDefaultStartup = StringUtil.isEmpty(startup);
            state.startupScript = state.useDefaultStartup ? defaultStartupScript() : startup;

            String shutdown = runnerSettings.getShutdownScript();
            state.useDefaultShutdown = StringUtil.isEmpty(shutdown);
            state.shutdownScript = state.useDefaultShutdown ? defaultShutdownScript() : shutdown;

            state.debugHost = runnerSettings.getDebugHost();

            EnvVarPanel.initializeState(state.envState, defaults, runnerSettings);

            modeStates.put(mode, state);
        }

        // Debug port/transport are global (not per-mode). Local mode reads DebugConfig (single
        // source of truth for the launch path); remote mode reads the Debug-mode RunnerSettings
        // (which stores the attach target). Transport is always Socket — see updateModeVisibility().
        DebugConfig debugConfig = cfg.getConfigData().getDebugConfig();
        int initialPort;
        if (remoteMode) {
            initialPort = cfg.getConfigData().getRunnerSettings(DEBUG_MODE).getDebugPort();
        } else {
            initialPort = (debugConfig != null) ? debugConfig.getPort() : DebugConfig.DEFAULT_DEBUG_PORT;
        }
        if (debugPortField != null) debugPortField.setText(String.valueOf(initialPort));
        if (transportSocketRadio != null) transportSocketRadio.setSelected(true);

        selectedMode = RUN_MODE;
        modeButtons.get(RUN_MODE).setSelected(true);
        updateModeVisibility();
        restoreCurrentState();

        LOG.debug("StartupConnectionTab reset: "
                + modeStates.values().stream().mapToInt(s -> s.envState.computedKeys.size()).sum()
                + " auto-managed env keys");
    }

    private void saveCurrentState() {
        UIState state = modeStates.computeIfAbsent(selectedMode, k -> new UIState());
        state.useDefaultStartup = useDefaultStartupCB.isSelected();
        state.startupScript = startupScriptField.getText();
        state.useDefaultShutdown = useDefaultShutdownCB.isSelected();
        state.shutdownScript = shutdownScriptField.getText();

        if (debugHostField != null) state.debugHost = debugHostField.getText().trim();

        // Port/transport live in their widgets — no per-mode cache. The widgets retain their
        // state across mode switches (they're just hidden), and applyTo() reads them directly
        // so invalid input is surfaced as a ConfigurationException instead of being swallowed.

        state.envState = envVarPanel.saveState();
    }

    private void restoreCurrentState() {
        UIState state = modeStates.computeIfAbsent(selectedMode, k -> new UIState());

        useDefaultStartupCB.setSelected(state.useDefaultStartup);
        startupScriptField.setText(state.startupScript);
        updateStartupState();

        useDefaultShutdownCB.setSelected(state.useDefaultShutdown);
        shutdownScriptField.setText(state.shutdownScript);
        updateShutdownState();

        if (debugHostField != null) debugHostField.setText(state.debugHost);

        envVarPanel.restoreState(state.envState);
    }

    /**
     * Parses and validates the debug port from the widget. Called from {@link #applyTo}
     * so bad input (empty / non-numeric / out-of-range) produces a user-visible
     * {@link ConfigurationException} instead of being silently swallowed.
     */
    private int parseDebugPortOrThrow() throws ConfigurationException {
        String raw = debugPortField != null ? debugPortField.getText() : "";
        Integer parsed = PortUtils.parsePort(raw, "Debug");
        if (parsed == null) {
            throw new ConfigurationException("Debug port is required");
        }
        if (parsed < 1024 || parsed > PortUtils.MAX_PORT) {
            throw new ConfigurationException(
                    "Debug port must be between 1024 and " + PortUtils.MAX_PORT + " (got " + parsed + ")");
        }
        return parsed;
    }

    public void applyTo(@NotNull TomcatRunConfiguration cfg) throws ConfigurationException {
        saveCurrentState();

        boolean scriptsApplicable = !remoteMode;

        int debugPort = parseDebugPortOrThrow();
        // Transport is always Socket — the radios are render-only (see updateModeVisibility()).
        String debugTransport = TomcatConstants.TRANSPORT_SOCKET;

        for (Map.Entry<String, UIState> entry : modeStates.entrySet()) {
            String mode = entry.getKey();
            UIState state = entry.getValue();
            RunnerSettings runnerSettings = cfg.getConfigData().getRunnerSettings(mode);

            if (scriptsApplicable && !state.useDefaultStartup) {
                String path = state.startupScript.trim();
                if (StringUtil.isEmpty(path))
                    throw new ConfigurationException("Startup script path is required for mode " + mode);
                if (!new File(path).exists())
                    throw new ConfigurationException("Startup script does not exist for mode " + mode + ": " + path);
                runnerSettings.setStartupScript(path);
            } else {
                runnerSettings.setStartupScript(null);
            }

            if (scriptsApplicable && !state.useDefaultShutdown) {
                String path = state.shutdownScript.trim();
                if (StringUtil.isEmpty(path))
                    throw new ConfigurationException("Shutdown script path is required for mode " + mode);
                if (!new File(path).exists())
                    throw new ConfigurationException("Shutdown script does not exist for mode " + mode + ": " + path);
                runnerSettings.setShutdownScript(path);
            } else {
                runnerSettings.setShutdownScript(null);
            }

            runnerSettings.setDebugHost(state.debugHost);
            // In remote mode, the attach port lives on the Debug-mode RunnerSettings.
            // In local mode, the JDWP port is global (DebugConfig); keep RunnerSettings in sync
            // so serialization/cloning round-trips don't resurrect a stale value.
            runnerSettings.setDebugPort(debugPort);

            EnvVarPanel.State envState = state.envState;
            runnerSettings.setEnvironmentVariables(new LinkedHashMap<>(envState.envVars));
            runnerSettings.setComputedEnvironmentKeys(new LinkedHashSet<>(envState.computedKeys));
            runnerSettings.setDeletedComputedEnvironmentKeys(new LinkedHashSet<>(envState.deletedComputedKeys));
            runnerSettings.setPassParentEnvs(envState.passParentEnvs);
        }

        // Persist the local JDWP port + transport into DebugConfig — single source of truth
        // for the launch path (TomcatJavaParametersBuilder reads from here).
        DebugConfig debugConfig = cfg.getConfigData().getDebugConfig();
        if (debugConfig != null) {
            debugConfig.setPort(debugPort);
            debugConfig.setTransport(debugTransport);
        }

        LOG.info("StartupConnectionTab applied for all modes (debugPort=" + debugPort
                + ", transport=" + debugTransport + ")");
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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
}
