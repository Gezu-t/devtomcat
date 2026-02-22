package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class StartupConnectionTab extends JPanel {

    private static final Logger LOG = Logger.getInstance(StartupConnectionTab.class);

    private final Project project;
    private final TomcatRunConfiguration configuration;

    private JTabbedPane modeTabs;
    private static final String RUN_MODE = "Run", DEBUG_MODE = "Debug", COVERAGE_MODE = "Coverage";

    private TextFieldWithBrowseButton startupScriptField, shutdownScriptField;
    private JCheckBox useDefaultStartupCB, useDefaultShutdownCB;
    private JButton editStartupParamsBtn, editShutdownParamsBtn;

    private JBTable envTable;
    private DefaultTableModel envModel;
    private JCheckBox passParentEnvsCB;

    private TextFieldWithBrowseButton debugPortField;
    private ComboBox<String> transportCombo;
    private JCheckBox useModuleClasspathCB;


    public StartupConnectionTab(@NotNull Project project, @NotNull TomcatRunConfiguration configuration) {
        this.project = project;
        this.configuration = configuration;
        initUI();
        updateModeVisibility(RUN_MODE);
    }

    private void initUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(15));

        modeTabs = new JTabbedPane();
        modeTabs.addTab(RUN_MODE,     createModePanel("Run configuration for local Tomcat server"));
        modeTabs.addTab(DEBUG_MODE,   createDebugPanel());
        modeTabs.addTab(COVERAGE_MODE,createModePanel("Coverage configuration (JaCoCo)"));
        modeTabs.addChangeListener(e -> updateModeVisibility(modeTabs.getTitleAt(modeTabs.getSelectedIndex())));
        add(modeTabs, BorderLayout.NORTH);

        JPanel shared = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = JBUI.insets(10, 0);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridy = 0; shared.add(createStartupSection(), gbc);
        gbc.gridy = 1; shared.add(createShutdownSection(), gbc);
        gbc.gridy = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        shared.add(createEnvSection(), gbc);

        add(shared, BorderLayout.CENTER);
    }

    private JPanel createModePanel(String description) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JBLabel(description).withBorder(JBUI.Borders.emptyBottom(10)), BorderLayout.NORTH);
        return p;
    }

    private JPanel createDebugPanel() {
        JPanel base = createModePanel("Debug configuration for local Tomcat server");
        JPanel debug = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = JBUI.insets(5);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; debug.add(new JBLabel("Port:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        debugPortField = new TextFieldWithBrowseButton();
        debugPortField.setText(String.valueOf(DynamicTomcatEnvironment.getJmxPort()));
        debug.add(debugPortField, g);

        g.gridx = 0; g.gridy = 1; debug.add(new JBLabel("Transport:"), g);
        g.gridx = 1;
        transportCombo = new ComboBox<>(new String[]{"Socket", "Shared Memory"});
        transportCombo.setSelectedItem("Socket");
        debug.add(transportCombo, g);

        g.gridx = 0; g.gridy = 2; g.gridwidth = 2;
        useModuleClasspathCB = new JCheckBox("Use module classpath");
        debug.add(useModuleClasspathCB, g);

        base.add(debug, BorderLayout.SOUTH);
        return base;
    }

    private void updateModeVisibility(String mode) {
        boolean debug = DEBUG_MODE.equals(mode);
        debugPortField.setVisible(debug);
        transportCombo.setVisible(debug);
        useModuleClasspathCB.setVisible(debug);
        revalidate(); repaint();
    }

    private JPanel createStartupSection() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0; g.gridy = 0; p.add(new JLabel("Startup script:"), g);

        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        startupScriptField = new TextFieldWithBrowseButton();
        startupScriptField.addBrowseFolderListener("Select Startup Script", "Choose a custom startup script for Tomcat",
                project, scriptFileDescriptor());
        p.add(startupScriptField, g);

        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2, 0, 2, 10);
        editStartupParamsBtn = new JButton("Edit...");
        editStartupParamsBtn.addActionListener(e -> editScriptParams("Startup"));
        p.add(editStartupParamsBtn, g);

        g.gridx = 3; g.insets = JBUI.insets(2);
        useDefaultStartupCB = new JCheckBox("Use default", true);
        useDefaultStartupCB.addActionListener(e -> updateStartupState());
        p.add(useDefaultStartupCB, g);

        return p;
    }

    private JPanel createShutdownSection() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0; g.gridy = 0; p.add(new JLabel("Shutdown script:"), g);

        g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        shutdownScriptField = new TextFieldWithBrowseButton();
        shutdownScriptField.addBrowseFolderListener("Select Shutdown Script", "Choose a custom shutdown script for Tomcat",
                project, scriptFileDescriptor());
        p.add(shutdownScriptField, g);

        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2, 0, 2, 10);
        editShutdownParamsBtn = new JButton("Edit...");
        editShutdownParamsBtn.addActionListener(e -> editScriptParams("Shutdown"));
        p.add(editShutdownParamsBtn, g);

        g.gridx = 3; g.insets = JBUI.insets(2);
        useDefaultShutdownCB = new JCheckBox("Use default", true);
        useDefaultShutdownCB.addActionListener(e -> updateShutdownState());
        p.add(useDefaultShutdownCB, g);

        return p;
    }

    private JPanel createEnvSection() {
        JPanel p = new JPanel(new BorderLayout());

        p.add(new TitledSeparator("Environment Variables"), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());

        passParentEnvsCB = new JCheckBox("Pass environment variables", true);
        passParentEnvsCB.setBorder(JBUI.Borders.emptyBottom(6));
        center.add(passParentEnvsCB, BorderLayout.NORTH);

        String[] cols = {"Name", "Value"};
        envModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        envTable = new JBTable(envModel);
        envTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        envTable.setRowHeight(25);
        envTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        envTable.getColumnModel().getColumn(1).setPreferredWidth(400);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(envTable)
                .setAddAction(button -> addEnvVar())
                .setRemoveAction(button -> removeEnvVar())
                .setEditAction(button -> editEnvVar())
                .disableUpDownActions();

        JComponent envTablePanel = decorator.createPanel();
        envTablePanel.setPreferredSize(new Dimension(0, 120));
        center.add(envTablePanel, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);

        return p;
    }

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
        String os = System.getProperty("os.name").toLowerCase();
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

    private void editScriptParams(String type) {
        ScriptParamsDialog dlg = new ScriptParamsDialog(project, type);
        if (dlg.showAndGet()) {
            LOG.info(type + " params updated: " + dlg.getParams());
        }
    }

    private void addEnvVar() {
        EnvVarDialog dlg = new EnvVarDialog(project, null, null);
        if (dlg.showAndGet()) {
            String[] v = dlg.getVar();
            if (hasDuplicate(v[0])) {
                Messages.showErrorDialog(project, "Duplicate variable name", "Error");
                return;
            }
            envModel.addRow(v);
        }
    }

    private void editEnvVar() {
        int row = envTable.getSelectedRow();
        if (row < 0) return;
        String name = (String) envModel.getValueAt(row, 0);
        String value = (String) envModel.getValueAt(row, 1);
        EnvVarDialog dlg = new EnvVarDialog(project, name, value);
        if (dlg.showAndGet()) {
            String[] v = dlg.getVar();
            if (!v[0].equals(name) && hasDuplicate(v[0])) {
                Messages.showErrorDialog(project, "Duplicate variable name", "Error");
                return;
            }
            envModel.setValueAt(v[0], row, 0);
            envModel.setValueAt(v[1], row, 1);
        }
    }

    private void removeEnvVar() {
        int row = envTable.getSelectedRow();
        if (row >= 0) {
            int rc = Messages.showYesNoDialog(project,
                    "Remove variable '" + envModel.getValueAt(row, 0) + "'?",
                    "Confirm", Messages.getQuestionIcon());
            if (rc == Messages.YES) {
                envModel.removeRow(row);
            }
        }
    }

    private boolean hasDuplicate(String name) {
        for (int i = 0; i < envModel.getRowCount(); i++) {
            if (name.equals(envModel.getValueAt(i, 0))) return true;
        }
        return false;
    }

    public void resetFrom(@NotNull TomcatRunConfiguration cfg) {
        useDefaultStartupCB.setSelected(true);
        useDefaultShutdownCB.setSelected(true);
        updateStartupState();
        updateShutdownState();

        envModel.setRowCount(0);
        Map<String, String> env = cfg.getEnvironmentVariables();
        if (env != null) {
            env.forEach((k, v) -> envModel.addRow(new Object[]{k, v}));
        }
        passParentEnvsCB.setSelected(cfg.isPassParentEnvs());

        debugPortField.setText(String.valueOf(cfg.getDebugPort()));
        transportCombo.setSelectedItem(cfg.getDebugTransport());
        useModuleClasspathCB.setSelected(cfg.isUseModuleClasspath());

        LOG.debug("StartupConnectionTab reset – env size: {}", env == null ? 0 : env.size());
    }

    public void applyTo(@NotNull TomcatRunConfiguration cfg) throws ConfigurationException {
        if (!useDefaultStartupCB.isSelected()) {
            String path = startupScriptField.getText().trim();
            if (StringUtil.isEmpty(path)) throw new ConfigurationException("Startup script path is required");
            File f = new File(path);
            if (!f.exists()) throw new ConfigurationException("Startup script does not exist: " + path);
            if (!f.canExecute()) LOG.warn("Startup script may not be executable: " + path);
            cfg.setStartupScript(path);
        } else {
            cfg.setStartupScript(null);
        }

        if (!useDefaultShutdownCB.isSelected()) {
            String path = shutdownScriptField.getText().trim();
            if (StringUtil.isEmpty(path)) throw new ConfigurationException("Shutdown script path is required");
            File f = new File(path);
            if (!f.exists()) throw new ConfigurationException("Shutdown script does not exist: " + path);
            cfg.setShutdownScript(path);
        } else {
            cfg.setShutdownScript(null);
        }

        Map<String, String> env = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < envModel.getRowCount(); i++) {
            String name = ((String) envModel.getValueAt(i, 0)).trim();
            String value = ((String) envModel.getValueAt(i, 1)).trim();
            if (StringUtil.isEmpty(name)) continue;
            if (seen.contains(name)) throw new ConfigurationException("Duplicate env var: " + name);
            seen.add(name);
            env.put(name, value);
        }
        cfg.setEnvironmentVariables(env);
        cfg.setPassParentEnvs(passParentEnvsCB.isSelected());

        if (DEBUG_MODE.equals(modeTabs.getTitleAt(modeTabs.getSelectedIndex()))) {
            try {
                int port = Integer.parseInt(debugPortField.getText().trim());
                cfg.setDebugPort(port);
            } catch (NumberFormatException ex) {
                throw new ConfigurationException("Invalid debug port");
            }
            cfg.setDebugTransport((String) transportCombo.getSelectedItem());
            cfg.setUseModuleClasspath(useModuleClasspathCB.isSelected());
        }

        LOG.info("StartupConnectionTab applied – env: " + env.size() + ", debug: " + DEBUG_MODE.equals(modeTabs.getTitleAt(modeTabs.getSelectedIndex())));
    }

    private static class ScriptParamsDialog extends DialogWrapper {
        private final JTextArea area;
        ScriptParamsDialog(@NotNull Project p, String type) {
            super(p);
            setTitle("Edit " + type + " Parameters");
            area = new JTextArea(8, 50);
            area.setText("-Dfile.encoding=UTF-8");
            init();
        }
        @Override protected JComponent createCenterPanel() { return new JBScrollPane(area); }
        String getParams() { return area.getText().trim(); }
    }

    private static class EnvVarDialog extends DialogWrapper {
        private final JTextField nameF = new JTextField(25);
        private final JTextField valueF = new JTextField(25);
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
                    case "CATALINA_HOME"  -> "";  // Would need TomcatInfo access
                    case "CATALINA_BASE"  -> "";  // Would need TomcatInfo access
                    case "JAVA_HOME"      -> System.getenv("JAVA_HOME");
                    case "CLASSPATH"      -> System.getenv("CLASSPATH");
                    case "PATH"           -> System.getenv("PATH");
                    default               -> "";
                };

                valueF.setText(StringUtil.notNullize(varValue));
            });
            init();
        }

        @Override protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(15));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = JBUI.insets(5); g.anchor = GridBagConstraints.WEST;

            g.gridx = 0; g.gridy = 0; panel.add(new JLabel("Common:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(commonCombo, g);

            g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
            panel.add(new JLabel("Name:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(nameF, g);

            g.gridx = 0; g.gridy = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
            panel.add(new JLabel("Value:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
            panel.add(valueF, g);

            return panel;
        }

        @Override protected void doOKAction() {
            if (StringUtil.isEmpty(nameF.getText().trim())) {
                Messages.showErrorDialog(getContentPane(), "Variable name is required", "Validation");
                nameF.requestFocus(); return;
            }
            super.doOKAction();
        }

        String[] getVar() { return new String[]{nameF.getText().trim(), valueF.getText().trim()}; }

    }
}