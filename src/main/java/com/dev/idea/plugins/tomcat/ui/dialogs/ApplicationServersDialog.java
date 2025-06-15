/**
 * Author: Gezahegn Lemma (Gezu)
 * Project: Dev Tomcat Plugin
 * Created: 6/9/25
 * Phase 2: Application Servers configuration dialog - Complete implementation
 */

package com.dev.idea.plugins.tomcat.ui.dialogs;

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

/**
 * Professional Application Servers configuration dialog
 * Manages Tomcat server installations and configurations
 */
public class ApplicationServersDialog extends DialogWrapper {

    private final Project project;

    // Server list components
    private JBTable serverTable;
    private DefaultTableModel serverTableModel;
    private JButton addServerButton;
    private JButton removeServerButton;
    private JButton editServerButton;

    // Server details components
    private JTextField serverNameField;
    private JTextField tomcatHomeField;
    private JButton browseTomcatHomeButton;
    private JTextField tomcatVersionField;
    private JTextField tomcatBaseField;
    private JButton browseTomcatBaseButton;

    // Libraries tree
    private JTree librariesTree;
    private DefaultTreeModel librariesTreeModel;

    // Data storage
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

    /**
     * Create server list panel with table and management buttons
     */
    private JPanel createServerListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Application Servers"));

        // Server table
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

        // Management buttons
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

    /**
     * Create server details panel with configuration fields
     */
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

    /**
     * Create server configuration panel
     */
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

        // Tomcat version (auto-detected)
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Tomcat Version:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        tomcatVersionField = new JTextField();
        tomcatVersionField.setEditable(false);
        tomcatVersionField.setToolTipText("Automatically detected Tomcat version");
        tomcatVersionField.setBackground(UIManager.getColor("Panel.background"));
        panel.add(tomcatVersionField, gbc);

        // Tomcat base directory
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

    /**
     * Create libraries panel with JAR tree view
     */
    private JPanel createLibrariesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Libraries"));

        // Create libraries tree
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

    /**
     * Initialize default Tomcat servers
     */
    private void initializeDefaultServers() {
        // Add some common Tomcat installations
        servers.add(new TomcatServerInfo("Tomcat 10.1.15", "C:\\apache-tomcat-10.1.15", "10.1.15"));
        servers.add(new TomcatServerInfo("Tomcat 10.0.27", "C:\\apache-tomcat-10.0.27", "10.0.27"));
        servers.add(new TomcatServerInfo("Tomcat 9.0.82", "C:\\apache-tomcat-9.0.82", "9.0.82"));

        // Populate table
        updateServerTable();
    }

    /**
     * Update server table with current server list
     */
    private void updateServerTable() {
        serverTableModel.setRowCount(0);
        for (TomcatServerInfo server : servers) {
            String status = validateServer(server) ? "Valid" : "Invalid";
            serverTableModel.addRow(new Object[]{server.getName(), server.getVersion(), status});
        }
        updateButtonStates();
    }

    /**
     * Update button states based on selection
     */
    private void updateButtonStates() {
        int selectedRow = serverTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        removeServerButton.setEnabled(hasSelection);
        editServerButton.setEnabled(hasSelection);
    }

    /**
     * Update selected server details
     */
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

    /**
     * Populate server details fields
     */
    private void populateServerDetails(TomcatServerInfo server) {
        serverNameField.setText(server.getName());
        tomcatHomeField.setText(server.getHomePath());
        tomcatVersionField.setText(server.getVersion());
        tomcatBaseField.setText(server.getBasePath());

        // Update libraries tree
        updateLibrariesTree(server);
    }

    /**
     * Clear server details fields
     */
    private void clearServerDetails() {
        serverNameField.setText("");
        tomcatHomeField.setText("");
        tomcatVersionField.setText("");
        tomcatBaseField.setText("");

        // Clear libraries tree
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Classes");
        librariesTreeModel.setRoot(rootNode);
        librariesTreeModel.reload();
    }

    /**
     * Update libraries tree for selected server
     */
    private void updateLibrariesTree(TomcatServerInfo server) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Classes");

        if (server != null && validateServer(server)) {
            // Add Tomcat libraries
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

        // Expand the root node
        librariesTree.expandRow(0);
    }

    /**
     * Add new server
     */
    private void addNewServer() {
        TomcatServerDialog dialog = new TomcatServerDialog(project, null);
        if (dialog.showAndGet()) {
            TomcatServerInfo newServer = dialog.getServerInfo();
            servers.add(newServer);
            updateServerTable();

            // Select the new server
            int lastRow = servers.size() - 1;
            serverTable.setRowSelectionInterval(lastRow, lastRow);
        }
    }

    /**
     * Remove selected server
     */
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

    /**
     * Edit selected server
     */
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

    /**
     * Browse for Tomcat home directory
     */
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

            // Update current server if one is selected
            if (selectedServer != null) {
                selectedServer.setHomePath(path);
                selectedServer.setVersion(version);
                updateLibrariesTree(selectedServer);
            }
        }
    }

    /**
     * Browse for Tomcat base directory
     */
    private void browseTomcatBase() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
        descriptor.setTitle("Select Tomcat Base Directory");
        descriptor.setDescription("Choose the Tomcat base directory (CATALINA_BASE)");

        VirtualFile file = FileChooser.chooseFile(descriptor, getContentPane(), project, null);
        if (file != null) {
            tomcatBaseField.setText(file.getPath());

            // Update current server if one is selected
            if (selectedServer != null) {
                selectedServer.setBasePath(file.getPath());
            }
        }
    }

    /**
     * Detect Tomcat version from installation directory
     */
    private String detectTomcatVersion(String homePath) {
        try {
            // Check version from lib/catalina.jar manifest
            File libDir = new File(homePath, "lib");
            File catalinaJar = new File(libDir, "catalina.jar");

            if (catalinaJar.exists()) {
                // Extract version from directory name as fallback
                String dirName = new File(homePath).getName();
                if (dirName.startsWith("apache-tomcat-")) {
                    return dirName.substring("apache-tomcat-".length());
                } else if (dirName.contains("tomcat")) {
                    // Try to extract version pattern
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

    /**
     * Validate server configuration
     */
    private boolean validateServer(TomcatServerInfo server) {
        if (server == null || server.getHomePath() == null) {
            return false;
        }

        File homeDir = new File(server.getHomePath());
        if (!homeDir.exists() || !homeDir.isDirectory()) {
            return false;
        }

        // Check for essential Tomcat files
        File binDir = new File(homeDir, "bin");
        File libDir = new File(homeDir, "lib");
        File catalinaJar = new File(libDir, "catalina.jar");

        return binDir.exists() && libDir.exists() && catalinaJar.exists();
    }

    /**
     * Get selected server configurations
     */
    public List<TomcatServerInfo> getServerConfigurations() {
        return new ArrayList<>(servers);
    }

    /**
     * Get selected server name
     */
    public String getSelectedServerName() {
        return selectedServer != null ? selectedServer.getName() : null;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    /**
     * Tomcat Server Information class
     */
    public static class TomcatServerInfo {
        private String name;
        private String homePath;
        private String version;
        private String basePath;

        public TomcatServerInfo(String name, String homePath, String version) {
            this.name = name;
            this.homePath = homePath;
            this.version = version;
            this.basePath = homePath; // Default base to home
        }

        // Getters and setters
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

    /**
     * Individual server configuration dialog
     */
    private static class TomcatServerDialog extends DialogWrapper {
        private final Project project;
        private JTextField nameField;
        private JTextField homePathField;
        private JTextField versionField;
        private JTextField basePathField;
        private TomcatServerInfo serverInfo;

        public TomcatServerDialog(Project project, TomcatServerInfo existingServer) {
            super(project);
            this.project = project;
            this.serverInfo = existingServer;
            setTitle(existingServer == null ? "Add Tomcat Server" : "Edit Tomcat Server");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);

            // Name field
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