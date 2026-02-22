package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ApplicationServersDialog extends DialogWrapper {

    private final Project project;

    private JBTable serverTable;
    private DefaultTableModel serverTableModel;
    private JButton addServerButton;
    private JButton removeServerButton;
    private JButton editServerButton;

    private JTextField serverNameField;
    private JTextField tomcatHomeField;
    private JButton browseTomcatHomeButton;
    private JTextField tomcatVersionField;
    private JTextField tomcatBaseField;
    private JButton browseTomcatBaseButton;

    private JTree librariesTree;
    private DefaultTreeModel librariesTreeModel;

    private List<TomcatServerInfo> servers;
    private TomcatServerInfo selectedServer;

    public ApplicationServersDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        this.servers = new ArrayList<>();
        initializeDefaultServers();
        setTitle("Application Servers");
        setSize(800, 600);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(800, 600));

        // Create split pane for server list and details
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(300);

        // Left panel - server list
        splitPane.setLeftComponent(createServerListPanel());

        // Right panel - server details
        splitPane.setRightComponent(createServerDetailsPanel());

        mainPanel.add(splitPane, BorderLayout.CENTER);

        return mainPanel;
    }

    private JPanel createServerListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Application Servers"));

        String[] columnNames = {"Name", "Version", "Status"};
        serverTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        serverTable = new JBTable(serverTableModel);
        serverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedServer();
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(serverTable);
        tableScrollPane.setPreferredSize(new Dimension(280, 300));
        panel.add(tableScrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        addServerButton = new JButton("+");
        addServerButton.setToolTipText("Add new server");
        addServerButton.addActionListener(e -> addNewServer());

        removeServerButton = new JButton("-");
        removeServerButton.setToolTipText("Remove selected server");
        removeServerButton.addActionListener(e -> removeSelectedServer());

        editServerButton = new JButton("Edit");
        editServerButton.setToolTipText("Edit selected server");
        editServerButton.addActionListener(e -> editSelectedServer());

        buttonPanel.add(addServerButton);
        buttonPanel.add(removeServerButton);
        buttonPanel.add(editServerButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        // Update initial button states
        updateButtonStates();

        return panel;
    }

    private JPanel createServerDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Server configuration panel
        JPanel configPanel = createServerConfigPanel();

        // Libraries panel
        JPanel librariesPanel = createLibrariesPanel();

        // Split between config and libraries
        JSplitPane detailsSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        detailsSplitPane.setTopComponent(configPanel);
        detailsSplitPane.setBottomComponent(librariesPanel);
        detailsSplitPane.setDividerLocation(250);

        panel.add(detailsSplitPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createServerConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Server Configuration"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;

        // Server name
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        serverNameField = new JTextField();
        serverNameField.setToolTipText("Display name for this server configuration");
        panel.add(serverNameField, gbc);

        // Tomcat home directory
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Tomcat Home:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.8;

        JPanel homePanel = new JPanel(new BorderLayout());
        tomcatHomeField = new JTextField();
        tomcatHomeField.setToolTipText("Path to Tomcat installation directory");
        browseTomcatHomeButton = new JButton("Browse");
        browseTomcatHomeButton.addActionListener(e -> browseTomcatHome());

        homePanel.add(tomcatHomeField, BorderLayout.CENTER);
        homePanel.add(browseTomcatHomeButton, BorderLayout.EAST);
        panel.add(homePanel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Tomcat Version:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        tomcatVersionField = new JTextField();
        tomcatVersionField.setEditable(false);
        tomcatVersionField.setToolTipText("Automatically detected Tomcat version");
        tomcatVersionField.setBackground(UIManager.getColor("Panel.background"));
        panel.add(tomcatVersionField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Tomcat Base:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.8;

        JPanel basePanel = new JPanel(new BorderLayout());
        tomcatBaseField = new JTextField();
        tomcatBaseField.setToolTipText("Path to Tomcat base directory (CATALINA_BASE)");
        browseTomcatBaseButton = new JButton("Browse");
        browseTomcatBaseButton.addActionListener(e -> browseTomcatBase());

        basePanel.add(tomcatBaseField, BorderLayout.CENTER);
        basePanel.add(browseTomcatBaseButton, BorderLayout.EAST);
        panel.add(basePanel, gbc);

        return panel;
    }

    private JPanel createLibrariesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Libraries"));

        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Classes");
        librariesTreeModel = new DefaultTreeModel(rootNode);
        librariesTree = new JTree(librariesTreeModel);
        librariesTree.setRootVisible(true);
        librariesTree.setShowsRootHandles(true);

        JScrollPane treeScrollPane = new JScrollPane(librariesTree);
        treeScrollPane.setPreferredSize(new Dimension(480, 200));
        panel.add(treeScrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void initializeDefaultServers() {
        servers.add(new TomcatServerInfo("Tomcat 10.1.15", "C:\\apache-tomcat-10.1.15", "10.1.15"));
        servers.add(new TomcatServerInfo("Tomcat 10.0.27", "C:\\apache-tomcat-10.0.27", "10.0.27"));
        servers.add(new TomcatServerInfo("Tomcat 9.0.82", "C:\\apache-tomcat-9.0.82", "9.0.82"));

        updateServerTable();
    }

    private void updateServerTable() {
        serverTableModel.setRowCount(0);
        for (TomcatServerInfo server : servers) {
            String status = validateServer(server) ? "Valid" : "Invalid";
            serverTableModel.addRow(new Object[]{server.getName(), server.getVersion(), status});
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        int selectedRow = serverTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeServerButton.setEnabled(hasSelection);
        editServerButton.setEnabled(hasSelection);
    }

    private void updateSelectedServer() {
        int selectedRow = serverTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < servers.size()) {
            selectedServer = servers.get(selectedRow);
            populateServerDetails(selectedServer);
        } else {
            selectedServer = null;
            clearServerDetails();
        }
        updateButtonStates();
    }

    private void populateServerDetails(TomcatServerInfo server) {
        serverNameField.setText(server.getName());
        tomcatHomeField.setText(server.getHomePath());
        tomcatVersionField.setText(server.getVersion());
        tomcatBaseField.setText(server.getBasePath());

        updateLibrariesTree(server);
    }

    private void clearServerDetails() {
        serverNameField.setText("");
        tomcatHomeField.setText("");
        tomcatVersionField.setText("");
        tomcatBaseField.setText("");

        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Classes");
        librariesTreeModel.setRoot(rootNode);
        librariesTreeModel.reload();
    }

    private void updateLibrariesTree(TomcatServerInfo server) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Classes");

        if (server != null && validateServer(server)) {
            File libDir = new File(server.getHomePath(), "lib");
            if (libDir.exists() && libDir.isDirectory()) {
                File[] jarFiles = libDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (jarFiles != null) {
                    for (File jarFile : jarFiles) {
                        DefaultMutableTreeNode jarNode = new DefaultMutableTreeNode(jarFile.getName());
                        rootNode.add(jarNode);
                    }
                }
            }
        }

        librariesTreeModel.setRoot(rootNode);
        librariesTreeModel.reload();

        librariesTree.expandRow(0);
    }

    private void addNewServer() {
        TomcatServerDialog dialog = new TomcatServerDialog(project, null);
        if (dialog.showAndGet()) {
            TomcatServerInfo newServer = dialog.getServerInfo();
            servers.add(newServer);
            updateServerTable();

            int lastRow = servers.size() - 1;
            serverTable.setRowSelectionInterval(lastRow, lastRow);
        }
    }

    private void removeSelectedServer() {
        int selectedRow = serverTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < servers.size()) {
            TomcatServerInfo server = servers.get(selectedRow);

            int result = Messages.showYesNoDialog(
                    getContentPane(),
                    "Remove server '" + server.getName() + "'?",
                    "Remove Server",
                    Messages.getQuestionIcon()
            );

            if (result == Messages.YES) {
                servers.remove(selectedRow);
                updateServerTable();
                clearServerDetails();
            }
        }
    }

    private void editSelectedServer() {
        int selectedRow = serverTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < servers.size()) {
            TomcatServerInfo server = servers.get(selectedRow);
            TomcatServerDialog dialog = new TomcatServerDialog(project, server);
            if (dialog.showAndGet()) {
                TomcatServerInfo updatedServer = dialog.getServerInfo();
                servers.set(selectedRow, updatedServer);
                updateServerTable();
                populateServerDetails(updatedServer);
            }
        }
    }

    private void browseTomcatHome() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
        descriptor.setTitle("Select Tomcat Home Directory");
        descriptor.setDescription("Choose the Tomcat installation directory");

        VirtualFile file = FileChooser.chooseFile(descriptor, getContentPane(), project, null);
        if (file != null) {
            String path = file.getPath();
            tomcatHomeField.setText(path);

            // Auto-detect version
            String version = detectTomcatVersion(path);
            if (version != null) {
                tomcatVersionField.setText(version);
            }

            // Set default base path
            if (tomcatBaseField.getText().trim().isEmpty()) {
                tomcatBaseField.setText(path);
            }

            if (selectedServer != null) {
                selectedServer.setHomePath(path);
                selectedServer.setVersion(version);
                updateLibrariesTree(selectedServer);
            }
        }
    }

    private void browseTomcatBase() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
        descriptor.setTitle("Select Tomcat Base Directory");
        descriptor.setDescription("Choose the Tomcat base directory (CATALINA_BASE)");

        VirtualFile file = FileChooser.chooseFile(descriptor, getContentPane(), project, null);
        if (file != null) {
            tomcatBaseField.setText(file.getPath());

            if (selectedServer != null) {
                selectedServer.setBasePath(file.getPath());
            }
        }
    }

    private String detectTomcatVersion(String homePath) {
        try {
            File libDir = new File(homePath, "lib");
            File catalinaJar = new File(libDir, "catalina.jar");

            if (catalinaJar.exists()) {
                String dirName = new File(homePath).getName();
                if (dirName.startsWith("apache-tomcat-")) {
                    return dirName.substring("apache-tomcat-".length());
                } else if (dirName.contains("tomcat")) {
                    String[] parts = dirName.split("-");
                    for (String part : parts) {
                        if (part.matches("\\d+\\.\\d+.*")) {
                            return part;
                        }
                    }
                }
            }

            return "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private boolean validateServer(TomcatServerInfo server) {
        if (server == null || server.getHomePath() == null) {
            return false;
        }

        File homeDir = new File(server.getHomePath());
        if (!homeDir.exists() || !homeDir.isDirectory()) {
            return false;
        }

        File binDir = new File(homeDir, "bin");
        File libDir = new File(homeDir, "lib");
        File catalinaJar = new File(libDir, "catalina.jar");

        return binDir.exists() && libDir.exists() && catalinaJar.exists();
    }

    public List<TomcatServerInfo> getServerConfigurations() {
        return new ArrayList<>(servers);
    }

    public String getSelectedServerName() {
        return selectedServer != null ? selectedServer.getName() : null;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    public static class TomcatServerInfo {
        private String name;
        private String homePath;
        private String version;
        private String basePath;

        public TomcatServerInfo(String name, String homePath, String version) {
            this.name = name;
            this.homePath = homePath;
            this.version = version;
            this.basePath = homePath;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getHomePath() { return homePath; }
        public void setHomePath(String homePath) { this.homePath = homePath; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }

        @Override
        public String toString() {
            return name + " (" + version + ")";
        }
    }

    private static class TomcatServerDialog extends DialogWrapper {
        private JTextField nameField;
        private JTextField homePathField;
        private JTextField versionField;
        private JTextField basePathField;
        private TomcatServerInfo serverInfo;

        public TomcatServerDialog(Project project, TomcatServerInfo existingServer) {
            super(project);
            this.serverInfo = existingServer;
            setTitle(existingServer == null ? "Add Tomcat Server" : "Edit Tomcat Server");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);

            gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JTextField(20);
            panel.add(nameField, gbc);

            // Home path field
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Tomcat Home:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            homePathField = new JTextField(30);
            panel.add(homePathField, gbc);

            // Version field
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Version:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            versionField = new JTextField(10);
            panel.add(versionField, gbc);

            // Base path field
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JLabel("Tomcat Base:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            basePathField = new JTextField(30);
            panel.add(basePathField, gbc);

            // Populate fields if editing
            if (serverInfo != null) {
                nameField.setText(serverInfo.getName());
                homePathField.setText(serverInfo.getHomePath());
                versionField.setText(serverInfo.getVersion());
                basePathField.setText(serverInfo.getBasePath());
            }

            return panel;
        }

        public TomcatServerInfo getServerInfo() {
            if (serverInfo == null) {
                serverInfo = new TomcatServerInfo(
                        nameField.getText().trim(),
                        homePathField.getText().trim(),
                        versionField.getText().trim()
                );
            } else {
                serverInfo.setName(nameField.getText().trim());
                serverInfo.setHomePath(homePathField.getText().trim());
                serverInfo.setVersion(versionField.getText().trim());
            }
            serverInfo.setBasePath(basePathField.getText().trim());
            return serverInfo;
        }
    }
}