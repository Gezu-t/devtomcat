package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
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
        /** Remote debug port (only used in Remote + Debug mode). */
        int debugPort = 5005;
        EnvVarPanel.State envState = new EnvVarPanel.State();
    }

    // Script sections (local mode only)
    private JPanel startupSection;
    private JPanel shutdownSection;
    private TextFieldWithBrowseButton startupScriptField;
    private TextFieldWithBrowseButton shutdownScriptField;
    private JBCheckBox useDefaultStartupCB;
    private JBCheckBox useDefaultShutdownCB;

    // Debug connection section (remote + debug mode only)
    private JPanel debugConnectionSection;
    private JBTextField debugHostField;
    private JBTextField debugPortField;

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
        debugConnectionSection = createDebugConnectionSection();
        contentPanel.add(debugConnectionSection, gbc);

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
     * Creates the remote debug connection section with Host and Port fields.
     */
    private JPanel createDebugConnectionSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(JBUI.Borders.emptyTop(4));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0; g.gridy = 0;
        g.gridwidth = 4;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        p.add(new TitledSeparator("Debug Connection"), g);

        g.gridy = 1; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        p.add(new JBLabel("Host:"), g);

        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = JBUI.insets(4, 4, 2, 16);
        debugHostField = new JBTextField();
        debugHostField.setText("localhost");
        debugHostField.getEmptyText().setText("localhost");
        p.add(debugHostField, g);

        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = JBUI.insets(4, 4, 2, 4);
        p.add(new JBLabel("Port:"), g);

        g.gridx = 3; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 0.3;
        g.insets = JBUI.insets(4, 4, 2, 4);
        debugPortField = new JBTextField();
        debugPortField.setText("5005");
        debugPortField.getEmptyText().setText("5005");
        p.add(debugPortField, g);

        return p;
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
     */
    private void updateModeVisibility() {
        boolean showScripts = !remoteMode;
        boolean showDebugConnection = remoteMode && DEBUG_MODE.equals(selectedMode);

        if (startupSection != null) startupSection.setVisible(showScripts);
        if (shutdownSection != null) shutdownSection.setVisible(showScripts);
        if (debugConnectionSection != null) debugConnectionSection.setVisible(showDebugConnection);

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
            state.debugPort = runnerSettings.getDebugPort();

            EnvVarPanel.initializeState(state.envState, defaults, runnerSettings);

            modeStates.put(mode, state);
        }

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
        if (debugPortField != null) {
            try { state.debugPort = Integer.parseInt(debugPortField.getText().trim()); }
            catch (NumberFormatException ignored) { }
        }

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
        if (debugPortField != null) debugPortField.setText(String.valueOf(state.debugPort));

        envVarPanel.restoreState(state.envState);
    }

    public void applyTo(@NotNull TomcatRunConfiguration cfg) throws ConfigurationException {
        saveCurrentState();

        boolean scriptsApplicable = !remoteMode;

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
            runnerSettings.setDebugPort(state.debugPort);

            EnvVarPanel.State envState = state.envState;
            runnerSettings.setEnvironmentVariables(new LinkedHashMap<>(envState.envVars));
            runnerSettings.setComputedEnvironmentKeys(new LinkedHashSet<>(envState.computedKeys));
            runnerSettings.setDeletedComputedEnvironmentKeys(new LinkedHashSet<>(envState.deletedComputedKeys));
            runnerSettings.setPassParentEnvs(envState.passParentEnvs);
        }

        LOG.info("StartupConnectionTab applied for all modes");
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
