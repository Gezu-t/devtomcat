package com.dev.idea.plugins.tomcat.ui.dialogs;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Comprehensive Tomcat Server Configuration Dialog
 * Manages all Tomcat server configurations with add/edit/remove functionality
 */
public class TomcatServerConfigurationDialog extends DialogWrapper {

    private final Project project;
    private JBList<TomcatInfo> serverList;
    private DefaultListModel<TomcatInfo> listModel;
    private JButton addButton;
    private JButton editButton;
    private JButton removeButton;
    private JButton detectButton;

    public TomcatServerConfigurationDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("Tomcat Server Configuration");
        setSize(600, 400);
        init();
        loadServers();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(580, 350));

        // Create server list
        createServerList();

        // Create buttons panel
        JPanel buttonsPanel = createButtonsPanel();

        // Layout
        mainPanel.add(new JBScrollPane(serverList), BorderLayout.CENTER);
        mainPanel.add(buttonsPanel, BorderLayout.EAST);

        // Instructions at top
        JPanel instructionsPanel = createInstructionsPanel();
        mainPanel.add(instructionsPanel, BorderLayout.NORTH);

        return mainPanel;
    }

    private void createServerList() {
        listModel = new DefaultListModel<>();
        serverList = new JBList<>(listModel);
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer(new TomcatServerRenderer());
        serverList.addListSelectionListener(e -> updateButtonStates());
    }

    private JPanel createButtonsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        addButton = new JButton("Add...");
        addButton.addActionListener(this::addServer);
        gbc.gridy = 0;
        panel.add(addButton, gbc);

        editButton = new JButton("Edit...");
        editButton.addActionListener(this::editServer);
        gbc.gridy = 1;
        panel.add(editButton, gbc);

        removeButton = new JButton("Remove");
        removeButton.addActionListener(this::removeServer);
        gbc.gridy = 2;
        panel.add(removeButton, gbc);

        // Separator
        gbc.gridy = 3;
        gbc.insets = JBUI.insets(15, 5, 5, 5);
        panel.add(new JSeparator(), gbc);

        detectButton = new JButton("Auto-Detect");
        detectButton.addActionListener(this::autoDetectServers);
        detectButton.setToolTipText("Automatically detect Tomcat installations");
        gbc.gridy = 4;
        gbc.insets = JBUI.insets(5);
        panel.add(detectButton, gbc);

        return panel;
    }

    private JPanel createInstructionsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        JLabel titleLabel = new JLabel("Configured Tomcat Servers:");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        panel.add(titleLabel, BorderLayout.WEST);

        JLabel helpLabel = new JLabel("Add Tomcat servers to use in run configurations");
        helpLabel.setFont(helpLabel.getFont().deriveFont(Font.ITALIC, 11f));
        helpLabel.setForeground(Color.GRAY);
        panel.add(helpLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void loadServers() {
        listModel.clear();
        List<TomcatInfo> servers = TomcatServerManagerState.getInstance().getTomcatInfos();
        for (TomcatInfo server : servers) {
            listModel.addElement(server);
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasSelection = serverList.getSelectedValue() != null;
        editButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
    }

    private void addServer(ActionEvent e) {
        AddTomcatServerDialog dialog = new AddTomcatServerDialog(project);
        if (dialog.showAndGet()) {
            TomcatInfo newServer = dialog.getTomcatInfo();
            if (newServer != null) {
                TomcatServerManagerState.getInstance().getTomcatInfos().add(newServer);
                listModel.addElement(newServer);
                serverList.setSelectedValue(newServer, true);
                updateButtonStates();
            }
        }
    }

    private void editServer(ActionEvent e) {
        TomcatInfo selected = serverList.getSelectedValue();
        if (selected != null) {
            EditTomcatServerDialog dialog = new EditTomcatServerDialog(project, selected);
            if (dialog.showAndGet()) {
                TomcatInfo updated = dialog.getTomcatInfo();
                if (updated != null) {
                    // Update the server in place
                    selected.setName(updated.getName());
                    selected.setPath(updated.getPath());
                    selected.setVersion(updated.getVersion());

                    // Refresh the list display
                    int index = listModel.indexOf(selected);
                    listModel.setElementAt(selected, index);
                    serverList.repaint();
                }
            }
        }
    }

    private void removeServer(ActionEvent e) {
        TomcatInfo selected = serverList.getSelectedValue();
        if (selected != null) {
            int result = Messages.showYesNoDialog(
                    "Remove Tomcat server '" + selected.getName() + "'?",
                    "Remove Server",
                    Messages.getQuestionIcon()
            );

            if (result == Messages.YES) {
                TomcatServerManagerState.getInstance().getTomcatInfos().remove(selected);
                listModel.removeElement(selected);
                updateButtonStates();
            }
        }
    }

    private void autoDetectServers(ActionEvent e) {
        AutoDetectDialog dialog = new AutoDetectDialog(project);
        if (dialog.showAndGet()) {
            List<TomcatInfo> detected = dialog.getDetectedServers();
            for (TomcatInfo server : detected) {
                TomcatServerManagerState.getInstance().getTomcatInfos().add(server);
                listModel.addElement(server);
            }
            updateButtonStates();

            if (!detected.isEmpty()) {
                Messages.showInfoMessage(
                        "Detected and added " + detected.size() + " Tomcat server(s)",
                        "Auto-Detection Complete"
                );
            }
        }
    }

    /**
     * Custom renderer for Tomcat servers
     */
    private static class TomcatServerRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof TomcatInfo) {
                TomcatInfo server = (TomcatInfo) value;
                setText("<html><b>" + server.getName() + "</b><br>" +
                        "<small>" + server.getVersion() + " - " + server.getPath() + "</small></html>");
            }

            return this;
        }
    }

    /**
     * Add new Tomcat server dialog
     */
    private static class AddTomcatServerDialog extends DialogWrapper {
        private final Project project;
        private JTextField nameField;
        private JTextField pathField;
        private JTextField versionField;
        private TomcatInfo tomcatInfo;

        public AddTomcatServerDialog(@NotNull Project project) {
            super(project);
            this.project = project;
            setTitle("Add Tomcat Server");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(450, 200));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Name
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JTextField("Tomcat Server");
            panel.add(nameField, gbc);

            // Path
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Tomcat Home:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            JPanel pathPanel = new JPanel(new BorderLayout());
            pathField = new JTextField();
            JButton browseButton = new JButton("Browse...");
            browseButton.addActionListener(e -> browseTomcatHome());
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            panel.add(pathPanel, gbc);

            // Version (auto-detected)
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Version:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            versionField = new JTextField();
            versionField.setEditable(false);
            versionField.setBackground(Color.LIGHT_GRAY);
            panel.add(versionField, gbc);

            return panel;
        }

        private void browseTomcatHome() {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
            descriptor.setTitle("Select Tomcat Installation Directory");

            VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
            if (file != null) {
                pathField.setText(file.getPath());
                detectVersion(file.getPath());
            }
        }

        private void detectVersion(String path) {
            try {
                Optional<TomcatInfo> detected = TomcatServerManagerState.createTomcatInfo(path);
                if (detected.isPresent()) {
                    versionField.setText(detected.get().getVersion());
                    if (nameField.getText().equals("Tomcat Server")) {
                        nameField.setText(detected.get().getName());
                    }
                } else {
                    versionField.setText("Unknown - Invalid Tomcat installation");
                }
            } catch (Exception e) {
                versionField.setText("Error detecting version");
            }
        }

        @Override
        protected void doOKAction() {
            String name = nameField.getText().trim();
            String path = pathField.getText().trim();

            if (name.isEmpty() || path.isEmpty()) {
                Messages.showErrorDialog("Please fill in all required fields", "Invalid Input");
                return;
            }

            if (!new File(path).exists()) {
                Messages.showErrorDialog("Tomcat installation directory does not exist", "Invalid Path");
                return;
            }

            try {
                Optional<TomcatInfo> created = TomcatServerManagerState.createTomcatInfo(path, serverInfo -> name);
                if (created.isPresent()) {
                    tomcatInfo = created.get();
                    super.doOKAction();
                } else {
                    Messages.showErrorDialog("Invalid Tomcat installation directory", "Invalid Installation");
                }
            } catch (Exception e) {
                Messages.showErrorDialog("Failed to create Tomcat server: " + e.getMessage(), "Error");
            }
        }

        public TomcatInfo getTomcatInfo() {
            return tomcatInfo;
        }
    }

    /**
     * Edit existing Tomcat server dialog
     */
    private static class EditTomcatServerDialog extends DialogWrapper {
        private final Project project;
        private final TomcatInfo originalServer;
        private JTextField nameField;
        private JTextField pathField;
        private JTextField versionField;
        private TomcatInfo updatedInfo;

        public EditTomcatServerDialog(@NotNull Project project, @NotNull TomcatInfo server) {
            super(project);
            this.project = project;
            this.originalServer = server;
            setTitle("Edit Tomcat Server");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(450, 200));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Name
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JTextField(originalServer.getName());
            panel.add(nameField, gbc);

            // Path
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Tomcat Home:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            JPanel pathPanel = new JPanel(new BorderLayout());
            pathField = new JTextField(originalServer.getPath());
            JButton browseButton = new JButton("Browse...");
            browseButton.addActionListener(e -> browseTomcatHome());
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            panel.add(pathPanel, gbc);

            // Version
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Version:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            versionField = new JTextField(originalServer.getVersion());
            versionField.setEditable(false);
            versionField.setBackground(Color.LIGHT_GRAY);
            panel.add(versionField, gbc);

            return panel;
        }

        private void browseTomcatHome() {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
            descriptor.setTitle("Select Tomcat Installation Directory");

            VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
            if (file != null) {
                pathField.setText(file.getPath());
                detectVersion(file.getPath());
            }
        }

        private void detectVersion(String path) {
            try {
                Optional<TomcatInfo> detected = TomcatServerManagerState.createTomcatInfo(path);
                if (detected.isPresent()) {
                    versionField.setText(detected.get().getVersion());
                } else {
                    versionField.setText("Unknown - Invalid Tomcat installation");
                }
            } catch (Exception e) {
                versionField.setText("Error detecting version");
            }
        }

        @Override
        protected void doOKAction() {
            String name = nameField.getText().trim();
            String path = pathField.getText().trim();

            if (name.isEmpty() || path.isEmpty()) {
                Messages.showErrorDialog("Please fill in all required fields", "Invalid Input");
                return;
            }

            if (!new File(path).exists()) {
                Messages.showErrorDialog("Tomcat installation directory does not exist", "Invalid Path");
                return;
            }

            try {
                Optional<TomcatInfo> created = TomcatServerManagerState.createTomcatInfo(path, serverInfo -> name);
                if (created.isPresent()) {
                    updatedInfo = created.get();
                    super.doOKAction();
                } else {
                    Messages.showErrorDialog("Invalid Tomcat installation directory", "Invalid Installation");
                }
            } catch (Exception e) {
                Messages.showErrorDialog("Failed to update Tomcat server: " + e.getMessage(), "Error");
            }
        }

        public TomcatInfo getTomcatInfo() {
            return updatedInfo;
        }
    }

    /**
     * Auto-detect Tomcat installations dialog
     */
    private static class AutoDetectDialog extends DialogWrapper {
        private final Project project;
        private JBList<TomcatInfo> detectedList;
        private DefaultListModel<TomcatInfo> detectedModel;
        private JButton scanButton;

        public AutoDetectDialog(@NotNull Project project) {
            super(project);
            this.project = project;
            setTitle("Auto-Detect Tomcat Servers");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(500, 300));

            // Instructions
            JLabel instructions = new JLabel("<html>Scanning common Tomcat installation locations...<br>" +
                    "Select servers to add to your configuration:</html>");
            instructions.setBorder(JBUI.Borders.empty(10));
            panel.add(instructions, BorderLayout.NORTH);

            // Detected servers list
            detectedModel = new DefaultListModel<>();
            detectedList = new JBList<>(detectedModel);
            detectedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            detectedList.setCellRenderer(new TomcatServerRenderer());
            panel.add(new JBScrollPane(detectedList), BorderLayout.CENTER);

            // Scan button
            JPanel buttonPanel = new JPanel(new FlowLayout());
            scanButton = new JButton("Scan Again");
            scanButton.addActionListener(e -> scanForTomcatServers());
            buttonPanel.add(scanButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);

            // Initial scan
            scanForTomcatServers();

            return panel;
        }

        private void scanForTomcatServers() {
            detectedModel.clear();

            // Common Tomcat installation paths
            String[] commonPaths = {
                    "/usr/local/tomcat",
                    "/opt/tomcat",
                    "/usr/share/tomcat",
                    System.getProperty("user.home") + "/tomcat",
                    System.getProperty("user.home") + "/apache-tomcat",
                    "C:\\Program Files\\Apache Software Foundation\\Tomcat",
                    "C:\\tomcat",
                    "/Applications/tomcat"
            };

            for (String path : commonPaths) {
                File tomcatDir = new File(path);
                if (tomcatDir.exists() && tomcatDir.isDirectory()) {
                    try {
                        Optional<TomcatInfo> detected = TomcatServerManagerState.createTomcatInfo(path);
                        if (detected.isPresent()) {
                            detectedModel.addElement(detected.get());
                        }
                    } catch (Exception e) {
                        // Ignore invalid installations
                    }
                }
            }

            // Also scan subdirectories of common parent directories
            String[] parentDirs = {
                    "/opt",
                    System.getProperty("user.home"),
                    "C:\\Program Files"
            };

            for (String parentDir : parentDirs) {
                scanParentDirectory(parentDir);
            }

            if (detectedModel.isEmpty()) {
                TomcatInfo noServers = new TomcatInfo();
                noServers.setName("No Tomcat installations found");
                noServers.setVersion("Try manual configuration");
                noServers.setPath("");
                detectedModel.addElement(noServers);
            }
        }

        private void scanParentDirectory(String parentPath) {
            File parentDir = new File(parentPath);
            if (parentDir.exists() && parentDir.isDirectory()) {
                File[] children = parentDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory() && child.getName().toLowerCase().contains("tomcat")) {
                            try {
                                Optional<TomcatInfo> detected = TomcatServerManagerState.createTomcatInfo(child.getAbsolutePath());
                                if (detected.isPresent()) {
                                    detectedModel.addElement(detected.get());
                                }
                            } catch (Exception e) {
                                // Ignore invalid installations
                            }
                        }
                    }
                }
            }
        }

        public List<TomcatInfo> getDetectedServers() {
            List<TomcatInfo> selected = detectedList.getSelectedValuesList();
            // Filter out the "no servers found" placeholder
            return selected.stream()
                    .filter(server -> !server.getName().equals("No Tomcat installations found"))
                    .toList();
        }
    }
}