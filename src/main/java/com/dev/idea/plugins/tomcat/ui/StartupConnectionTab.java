package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class StartupConnectionTab extends JBPanel<StartupConnectionTab> {

    private static final Logger LOG = Logger.getInstance(StartupConnectionTab.class);

    private final Project project;
    private final TomcatRunConfiguration configuration;

    private static final String RUN_MODE = "Run";
    private static final String DEBUG_MODE = "Debug";
    private static final String COVERAGE_MODE = "Coverage";

    private JBList<String> modeList;
    private String selectedMode = RUN_MODE;

    private TextFieldWithBrowseButton startupScriptField;
    private TextFieldWithBrowseButton shutdownScriptField;
    private JCheckBox useDefaultStartupCB;
    private JCheckBox useDefaultShutdownCB;

    private JBTable envTable;
    private DefaultTableModel envModel;
    private JCheckBox passParentEnvsCB;

    public StartupConnectionTab(@NotNull Project project, @NotNull TomcatRunConfiguration configuration) {
        this.project = project;
        this.configuration = configuration;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(JBUI.Borders.empty(8, 12, 8, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Mode selector list (Run / Debug / Coverage)
        gbc.gridy = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(0, 0, 10, 0);
        mainPanel.add(createModeSelector(), gbc);

        // Startup script row
        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(4, 0);
        mainPanel.add(createStartupSection(), gbc);

        // Shutdown script row
        gbc.gridy = 2;
        mainPanel.add(createShutdownSection(), gbc);

        // Environment Variables section (fills remaining space)
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
        String[] modes = {RUN_MODE, DEBUG_MODE, COVERAGE_MODE};
        modeList = new JBList<>(modes);
        modeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modeList.setSelectedIndex(0);
        modeList.setVisibleRowCount(3);

        modeList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(JBUI.Borders.empty(4, 8));
                String mode = (String) value;
                switch (mode) {
                    case RUN_MODE -> label.setIcon(AllIcons.Actions.Execute);
                    case DEBUG_MODE -> label.setIcon(AllIcons.Actions.StartDebugger);
                    case COVERAGE_MODE -> label.setIcon(AllIcons.General.RunWithCoverage);
                }
                return label;
            }
        });

        modeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String mode = modeList.getSelectedValue();
                if (mode != null) {
                    selectedMode = mode;
                    LOG.debug("Mode switched to: " + mode);
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(modeList);
        scrollPane.setPreferredSize(new Dimension(0, 90));
        scrollPane.setMinimumSize(new Dimension(0, 90));
        return scrollPane;
    }

    private JPanel createStartupSection() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = JBUI.insets(2);

        g.gridx = 0;
        g.gridy = 0;
        p.add(new JLabel("Startup script:"), g);

        g.gridx = 1;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        startupScriptField = new TextFieldWithBrowseButton();
        startupScriptField.addBrowseFolderListener("Select Startup Script",
                "Choose a custom startup script for Tomcat", project, scriptFileDescriptor());
        p.add(startupScriptField, g);

        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2);
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

        g.gridx = 0;
        g.gridy = 0;
        p.add(new JLabel("Shutdown script:"), g);

        g.gridx = 1;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = JBUI.insets(2, 15, 2, 10);
        shutdownScriptField = new TextFieldWithBrowseButton();
        shutdownScriptField.addBrowseFolderListener("Select Shutdown Script",
                "Choose a custom shutdown script for Tomcat", project, scriptFileDescriptor());
        p.add(shutdownScriptField, g);

        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = JBUI.insets(2);
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
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        envTable = new JBTable(envModel);
        envTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        envTable.setRowHeight(25);
        envTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        envTable.getColumnModel().getColumn(1).setPreferredWidth(400);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(envTable)
                .setAddAction(button -> addEnvVar())
                .setRemoveAction(button -> removeEnvVar())
                .disableUpDownActions();

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
        envTablePanel.setPreferredSize(new Dimension(0, 150));
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

    private void removeEnvVar() {
        int row = envTable.getSelectedRow();
        if (row >= 0) {
            envModel.removeRow(row);
            if (envModel.getRowCount() > 0) {
                int newSel = Math.min(row, envModel.getRowCount() - 1);
                envTable.setRowSelectionInterval(newSel, newSel);
            }
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

            // Parse "NAME=VALUE" format
            int eq = text.indexOf('=');
            if (eq > 0) {
                String name = text.substring(0, eq).trim();
                String value = text.substring(eq + 1).trim();
                if (!name.isEmpty() && !hasDuplicate(name)) {
                    envModel.addRow(new String[]{name, value});
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

        modeList.setSelectedIndex(0);

        LOG.debug("StartupConnectionTab reset – env size: " + (env == null ? 0 : env.size()));
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

        Map<String, String> env = new LinkedHashMap<>();
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

        cfg.setDebugPort(cfg.getDebugPort());
        cfg.setDebugTransport(cfg.getDebugTransport());
        cfg.setUseModuleClasspath(cfg.isUseModuleClasspath());

        LOG.info("StartupConnectionTab applied – env: " + env.size());
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
            panel.add(new JLabel("Common:"), g);
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
