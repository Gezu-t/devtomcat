package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DeploymentConfigurationTab extends JPanel {

    private final Project project;
    private JBTable deploymentTable;
    private DefaultTableModel tableModel;
    private JButton addButton;
    private JButton removeButton;
    private JButton editButton;
    private JPanel tableContainer;
    private List<DeploymentArtifact> deploymentArtifacts = new ArrayList<>();
    private JTextField applicationContextField;
    private JCheckBox deployApplicationsCheckBox;
    private JTextField webDirectoryField;
    private JButton browseWebDirButton;

    public DeploymentConfigurationTab(@NotNull Project project) {
        this.project = project;
        initializeUI();
        loadDefaultArtifacts();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        initializeTable();
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(createDeploymentArtifactsSection(), BorderLayout.CENTER);
        mainPanel.add(createContextConfigurationSection(), BorderLayout.SOUTH);
        add(mainPanel, BorderLayout.CENTER);
    }

    private void initializeTable() {
        String[] columnNames = {"Artifact", "Type", "Server Path"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        deploymentTable = new JBTable(tableModel);
        deploymentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploymentTable.setRowHeight(25);
        deploymentTable.getSelectionModel().addListSelectionListener(e -> updateButtonStates());

        if (deploymentTable.getColumnModel().getColumnCount() >= 3) {
            deploymentTable.getColumnModel().getColumn(0).setPreferredWidth(200);
            deploymentTable.getColumnModel().getColumn(1).setPreferredWidth(120);
            deploymentTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        }
    }

    private JPanel createDeploymentArtifactsSection() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Deploy at the server startup");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(titleLabel, BorderLayout.NORTH);

        tableContainer = new JPanel(new BorderLayout());
        JScrollPane scrollPane = new JScrollPane(deploymentTable);
        scrollPane.setPreferredSize(new Dimension(600, 150));
        scrollPane.setBorder(BorderFactory.createLoweredBevelBorder());
        tableContainer.add(scrollPane, BorderLayout.CENTER);
        panel.add(tableContainer, BorderLayout.CENTER);
        panel.add(createArtifactButtonsPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createArtifactButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(JBUI.Borders.emptyTop(5));

        addButton = new JButton("Artifact...");
        removeButton = new JButton("-");
        editButton = new JButton("Edit");

        addButton.setPreferredSize(new Dimension(80, 25));
        removeButton.setPreferredSize(new Dimension(40, 25));
        editButton.setPreferredSize(new Dimension(60, 25));

        addButton.addActionListener(e -> showArtifactSelectionDialog());
        removeButton.addActionListener(e -> removeArtifact());
        editButton.addActionListener(e -> editArtifact());

        updateButtonStates();

        panel.add(addButton);
        panel.add(removeButton);
        panel.add(editButton);
        return panel;
    }

    private void showArtifactSelectionDialog() {
        ArtifactSelectionDialog dialog = new ArtifactSelectionDialog(project);
        if (dialog.showAndGet()) {
            List<String> selectedArtifacts = dialog.getSelectedArtifacts();
            for (String artifactName : selectedArtifacts) {
                addSelectedArtifact(artifactName);
            }
        }
    }

    private void addSelectedArtifact(String artifactName) {
        String type = artifactName.contains("exploded") ? "war exploded" : "war";
        String name = artifactName.replace(":war exploded", "").replace(":war", "");
        String contextPath = "/" + name.toLowerCase();
        String localPath = determineLocalPath(name, type);

        DeploymentArtifact artifact = new DeploymentArtifact(artifactName, type, contextPath, localPath);
        deploymentArtifacts.add(artifact);
        addArtifactToTable(artifact);

        int newRow = tableModel.getRowCount() - 1;
        deploymentTable.setRowSelectionInterval(newRow, newRow);
        updateButtonStates();
        updateContextFromSelectedArtifact();
    }

    private String determineLocalPath(String name, String type) {
        String basePath = project.getBasePath();
        if (basePath == null) return "";

        if (type.equals("war exploded")) {
            String[] webDirs = {"src/main/webapp", "web", "WebContent", "src/webapp"};
            for (String webDir : webDirs) {
                File dir = new File(basePath, webDir);
                if (dir.exists()) {
                    return dir.getAbsolutePath();
                }
            }
            return basePath + "/src/main/webapp";
        } else {
            return basePath + "/target/" + name + ".war";
        }
    }

    private JPanel createContextConfigurationSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Application Context"));
        panel.setBorder(JBUI.Borders.emptyTop(15));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Application context:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.7;
        applicationContextField = new JTextField();
        panel.add(applicationContextField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        deployApplicationsCheckBox = new JCheckBox("Deploy applications configured in Tomcat instance", true);
        panel.add(deployApplicationsCheckBox, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        panel.add(new JLabel("Web directory:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0.7;
        JPanel webDirPanel = new JPanel(new BorderLayout());
        webDirectoryField = new JTextField();
        browseWebDirButton = new JButton("Browse");
        browseWebDirButton.addActionListener(e -> browseWebDirectory());

        webDirPanel.add(webDirectoryField, BorderLayout.CENTER);
        webDirPanel.add(browseWebDirButton, BorderLayout.EAST);
        panel.add(webDirPanel, gbc);

        return panel;
    }

    private void loadDefaultArtifacts() {
        String projectPath = project.getBasePath();
        if (projectPath != null) {
            String[] webDirs = {"src/main/webapp", "web", "WebContent", "src/webapp"};

            for (String webDir : webDirs) {
                File webDirectory = new File(projectPath, webDir);
                if (webDirectory.exists() && webDirectory.isDirectory()) {
                    String projectName = project.getName();
                    String contextPath = "/" + projectName.toLowerCase();

                    DeploymentArtifact artifact = new DeploymentArtifact(
                            projectName + ":war exploded",
                            "war exploded",
                            contextPath,
                            webDirectory.getAbsolutePath()
                    );

                    deploymentArtifacts.add(artifact);
                    addArtifactToTable(artifact);

                    if (deploymentTable.getRowCount() > 0) {
                        deploymentTable.setRowSelectionInterval(0, 0);
                    }

                    applicationContextField.setText(contextPath);
                    webDirectoryField.setText(webDirectory.getAbsolutePath());
                    break;
                }
            }
        }
        updateButtonStates();
    }

    private void removeArtifact() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < deploymentArtifacts.size()) {
            deploymentArtifacts.remove(selectedRow);
            tableModel.removeRow(selectedRow);

            if (deploymentArtifacts.isEmpty()) {
                applicationContextField.setText("");
                webDirectoryField.setText("");
            } else {
                int newSelection = Math.min(selectedRow, deploymentArtifacts.size() - 1);
                if (newSelection >= 0) {
                    deploymentTable.setRowSelectionInterval(newSelection, newSelection);
                    updateContextFromSelectedArtifact();
                }
            }
            updateButtonStates();
        }
    }

    private void editArtifact() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < deploymentArtifacts.size()) {
            DeploymentArtifact artifact = deploymentArtifacts.get(selectedRow);

            JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
            JTextField nameField = new JTextField(artifact.getName());
            JTextField contextField = new JTextField(artifact.getServerPath());
            JTextField pathField = new JTextField(artifact.getLocalPath());

            panel.add(new JLabel("Artifact Name:"));
            panel.add(nameField);
            panel.add(new JLabel("Context Path:"));
            panel.add(contextField);
            panel.add(new JLabel("Local Path:"));
            panel.add(pathField);

            int result = JOptionPane.showConfirmDialog(this, panel, "Edit Artifact", JOptionPane.OK_CANCEL_OPTION);

            if (result == JOptionPane.OK_OPTION) {
                artifact.setName(nameField.getText().trim());
                artifact.setServerPath(contextField.getText().trim());
                artifact.setLocalPath(pathField.getText().trim());

                tableModel.setValueAt(artifact.getName(), selectedRow, 0);
                tableModel.setValueAt(artifact.getType(), selectedRow, 1);
                tableModel.setValueAt(artifact.getServerPath(), selectedRow, 2);

                updateContextFromSelectedArtifact();
            }
        }
    }

    private void browseWebDirectory() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            webDirectoryField.setText(file.getPath());
            int selectedRow = deploymentTable.getSelectedRow();
            if (selectedRow >= 0 && selectedRow < deploymentArtifacts.size()) {
                deploymentArtifacts.get(selectedRow).setLocalPath(file.getPath());
            }
        }
    }

    private void updateButtonStates() {
        boolean hasSelection = deploymentTable.getSelectedRow() >= 0;
        removeButton.setEnabled(hasSelection);
        editButton.setEnabled(hasSelection);
    }

    private void updateContextFromSelectedArtifact() {
        int selectedRow = deploymentTable.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < deploymentArtifacts.size()) {
            DeploymentArtifact artifact = deploymentArtifacts.get(selectedRow);
            applicationContextField.setText(artifact.getServerPath());
            webDirectoryField.setText(artifact.getLocalPath());
        }
    }

    private void addArtifactToTable(DeploymentArtifact artifact) {
        tableModel.addRow(new Object[]{
                artifact.getName(),
                artifact.getType(),
                artifact.getServerPath()
        });
    }

    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        deploymentArtifacts.clear();
        tableModel.setRowCount(0);

        // Load deployment artifacts from configuration
        List<TomcatRunConfiguration.DeploymentArtifact> configArtifacts = config.getDeploymentArtifacts();
        if (configArtifacts != null) {
            for (TomcatRunConfiguration.DeploymentArtifact configArtifact : configArtifacts) {
                DeploymentArtifact artifact = new DeploymentArtifact(
                        configArtifact.getName(),
                        configArtifact.getType(),
                        configArtifact.getServerPath(),
                        configArtifact.getLocalPath()
                );
                deploymentArtifacts.add(artifact);
                addArtifactToTable(artifact);
            }
        }

        // Fallback to docBase/contextPath if no artifacts
        if (deploymentArtifacts.isEmpty()) {
            String docBase = config.getDocBase();
            String contextPath = config.getContextPath();

            if (docBase != null && !docBase.trim().isEmpty()) {
                String name = extractArtifactName(contextPath);
                File docBaseFile = new File(docBase);
                String type = docBaseFile.isDirectory() ? "war exploded" : "war";
                String serverPath = contextPath != null && !contextPath.trim().isEmpty() ? contextPath : "/" + name;

                DeploymentArtifact artifact = new DeploymentArtifact(name + ":" + type, type, serverPath, docBase);
                deploymentArtifacts.add(artifact);
                addArtifactToTable(artifact);
            }
        }

        if (deploymentTable.getRowCount() > 0) {
            deploymentTable.setRowSelectionInterval(0, 0);
        }

        if (applicationContextField != null) {
            applicationContextField.setText(config.getContextPath() != null ? config.getContextPath() : "");
        }
        if (webDirectoryField != null) {
            webDirectoryField.setText(config.getDocBase() != null ? config.getDocBase() : "");
        }
        if (deployApplicationsCheckBox != null) {
            deployApplicationsCheckBox.setSelected(true);
        }
        updateButtonStates();
    }

    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        String contextPath = applicationContextField != null ? applicationContextField.getText().trim() : "";
        String webDirectory = webDirectoryField != null ? webDirectoryField.getText().trim() : "";

        if (!contextPath.isEmpty() && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }

        // Convert our DeploymentArtifacts to TomcatRunConfiguration.DeploymentArtifacts
        List<TomcatRunConfiguration.DeploymentArtifact> configArtifacts = new ArrayList<>();
        for (DeploymentArtifact artifact : deploymentArtifacts) {
            configArtifacts.add(new TomcatRunConfiguration.DeploymentArtifact(
                    artifact.getName(),
                    artifact.getType(),
                    artifact.getServerPath(),
                    artifact.getLocalPath()
            ));
        }
        config.setDeploymentArtifacts(configArtifacts);

        config.setContextPath(contextPath);
        config.setDocBase(webDirectory);

        if (!deploymentArtifacts.isEmpty()) {
            DeploymentArtifact primary = deploymentArtifacts.get(0);
            config.setDocBase(primary.getLocalPath());
            config.setContextPath(primary.getServerPath());
        }
    }

    private String extractArtifactName(String contextPath) {
        if (contextPath == null || contextPath.trim().isEmpty()) {
            return project.getName();
        }
        String name = contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
        return name.isEmpty() ? "ROOT" : name;
    }

    private static class ArtifactSelectionDialog extends DialogWrapper {
        private final Project project;
        private JBList<String> artifactsList;
        private DefaultListModel<String> listModel;

        public ArtifactSelectionDialog(Project project) {
            super(project);
            this.project = project;
            setTitle("Select Artifacts to Deploy");

            // Initialize list components BEFORE calling init()
            listModel = new DefaultListModel<>();
            artifactsList = new JBList<>(listModel);
            artifactsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(400, 300));

            JLabel descriptionLabel = new JLabel("Selected artifacts will be deployed at server startup");
            descriptionLabel.setBorder(JBUI.Borders.emptyBottom(10));
            panel.add(descriptionLabel, BorderLayout.NORTH);

            populateArtifactsList();

            JScrollPane scrollPane = new JScrollPane(artifactsList);
            scrollPane.setPreferredSize(new Dimension(380, 200));
            panel.add(scrollPane, BorderLayout.CENTER);

            return panel;
        }

        private void populateArtifactsList() {
            String projectName = project.getName();
            listModel.addElement(projectName + ":war exploded");
            listModel.addElement(projectName + ":war");

            if (listModel.getSize() > 0) {
                artifactsList.setSelectedIndex(0);
            }
        }

        public List<String> getSelectedArtifacts() {
            return artifactsList.getSelectedValuesList();
        }
    }

    public static class DeploymentArtifact {
        private String name;
        private String type;
        private String serverPath;
        private String localPath;

        public DeploymentArtifact(String name, String type, String serverPath, String localPath) {
            this.name = name != null ? name : "";
            this.type = type != null ? type : "war";
            this.serverPath = serverPath != null ? serverPath : "/";
            this.localPath = localPath != null ? localPath : "";
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getServerPath() { return serverPath; }
        public String getLocalPath() { return localPath; }

        public void setName(String name) { this.name = name != null ? name : ""; }
        public void setType(String type) { this.type = type != null ? type : "war"; }
        public void setServerPath(String serverPath) { this.serverPath = serverPath != null ? serverPath : "/"; }
        public void setLocalPath(String localPath) { this.localPath = localPath != null ? localPath : ""; }

        @Override
        public String toString() {
            return name + " (" + type + ") -> " + serverPath;
        }
    }
}