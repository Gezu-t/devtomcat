package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.intellij.openapi.diagnostic.Logger;

public class JREConfigurationDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(JREConfigurationDialog.class);

    private final Project project;
    private JBList<JdkInfo> jdkList;
    private DefaultListModel<JdkInfo> listModel;
    private JButton addButton;
    private JButton editButton;
    private JButton removeButton;
    private JButton detectButton;
    private JdkInfo selectedJdk;

    public JREConfigurationDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("JRE Configuration");
        setSize(600, 400);
        init();
        loadJdks();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(JBUI.scale(580), JBUI.scale(350)));

        createJdkList();

        JPanel buttonsPanel = createButtonsPanel();

        // Layout
        mainPanel.add(new JBScrollPane(jdkList), BorderLayout.CENTER);
        mainPanel.add(buttonsPanel, BorderLayout.EAST);

        // Instructions at top
        JPanel instructionsPanel = createInstructionsPanel();
        mainPanel.add(instructionsPanel, BorderLayout.NORTH);

        return mainPanel;
    }

    private void createJdkList() {
        listModel = new DefaultListModel<>();
        jdkList = new JBList<>(listModel);
        jdkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jdkList.setCellRenderer(new JdkRenderer());
        jdkList.addListSelectionListener(e -> updateButtonStates());
    }

    private JPanel createButtonsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        addButton = new JButton("Add...");
        addButton.addActionListener(this::addJdk);
        gbc.gridy = 0;
        panel.add(addButton, gbc);

        editButton = new JButton("Edit...");
        editButton.addActionListener(this::editJdk);
        gbc.gridy = 1;
        panel.add(editButton, gbc);

        removeButton = new JButton("Remove");
        removeButton.addActionListener(this::removeJdk);
        gbc.gridy = 2;
        panel.add(removeButton, gbc);

        // Separator
        gbc.gridy = 3;
        gbc.insets = JBUI.insets(15, 5, 5, 5);
        panel.add(new com.intellij.ui.SeparatorComponent(), gbc);

        detectButton = new JButton("Auto-Detect");
        detectButton.addActionListener(this::autoDetectJdks);
        detectButton.setToolTipText("Automatically detect JDK installations");
        gbc.gridy = 4;
        gbc.insets = JBUI.insets(5);
        panel.add(detectButton, gbc);

        return panel;
    }

    private JPanel createInstructionsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        JBLabel titleLabel = new JBLabel("Available JDKs/JREs:");
        titleLabel.setFont(com.intellij.util.ui.JBFont.label().asBold());
        panel.add(titleLabel, BorderLayout.WEST);

        JBLabel helpLabel = new JBLabel("Select a Java runtime environment for Tomcat execution");
        helpLabel.setFont(com.intellij.util.ui.JBFont.small());
        helpLabel.setForeground(com.intellij.ui.JBColor.GRAY);
        panel.add(helpLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void loadJdks() {
        listModel.clear();

        // Add project SDK if available
        if (project.isDefault() == false) {
            JdkInfo projectSdk = new JdkInfo("Project SDK", "Uses project's configured JDK", "", true);
            listModel.addElement(projectSdk);
        }

        // Add configured SDKs from IntelliJ
        ProjectJdkTable jdkTable = ProjectJdkTable.getInstance();
        for (Sdk sdk : jdkTable.getAllJdks()) {
            if (sdk.getSdkType() instanceof JavaSdk) {
                String name = sdk.getName();
                String version = sdk.getVersionString() != null ? sdk.getVersionString() : "Unknown";
                String path = sdk.getHomePath() != null ? sdk.getHomePath() : "";

                JdkInfo jdkInfo = new JdkInfo(name, version, path, false);
                listModel.addElement(jdkInfo);
            }
        }

        // Select project SDK by default
        if (listModel.getSize() > 0) {
            jdkList.setSelectedIndex(0);
        }

        updateButtonStates();
    }

    private void updateButtonStates() {
        JdkInfo selected = jdkList.getSelectedValue();
        boolean hasSelection = selected != null;
        boolean canEdit = hasSelection && !selected.isProjectSdk();

        editButton.setEnabled(canEdit);
        removeButton.setEnabled(canEdit);
    }

    private void addJdk(ActionEvent e) {
        AddJdkDialog dialog = new AddJdkDialog(project);
        if (dialog.showAndGet()) {
            JdkInfo newJdk = dialog.getJdkInfo();
            if (newJdk != null) {
                listModel.addElement(newJdk);
                jdkList.setSelectedValue(newJdk, true);
                updateButtonStates();

                // Also add to IntelliJ's SDK table
                addToIntellijSdkTable(newJdk);
            }
        }
    }

    private void editJdk(ActionEvent e) {
        JdkInfo selected = jdkList.getSelectedValue();
        if (selected != null && !selected.isProjectSdk()) {
            EditJdkDialog dialog = new EditJdkDialog(project, selected);
            if (dialog.showAndGet()) {
                JdkInfo updated = dialog.getJdkInfo();
                if (updated != null) {
                    // Update the JDK in place
                    selected.setName(updated.getName());
                    selected.setPath(updated.getPath());
                    selected.setVersion(updated.getVersion());

                    // Refresh the list display
                    int index = listModel.indexOf(selected);
                    listModel.setElementAt(selected, index);
                    jdkList.repaint();
                }
            }
        }
    }

    private void removeJdk(ActionEvent e) {
        JdkInfo selected = jdkList.getSelectedValue();
        if (selected != null && !selected.isProjectSdk()) {
            int result = Messages.showYesNoDialog(
                    "Remove JDK '" + selected.getName() + "'?",
                    "Remove JDK",
                    Messages.getQuestionIcon()
            );

            if (result == Messages.YES) {
                listModel.removeElement(selected);
                updateButtonStates();

                // Also remove from IntelliJ's SDK table
                removeFromIntellijSdkTable(selected);
            }
        }
    }

    private void autoDetectJdks(ActionEvent e) {
        AutoDetectJdkDialog dialog = new AutoDetectJdkDialog(project);
        if (dialog.showAndGet()) {
            List<JdkInfo> detected = dialog.getDetectedJdks();
            for (JdkInfo jdk : detected) {
                listModel.addElement(jdk);
                addToIntellijSdkTable(jdk);
            }
            updateButtonStates();

            if (!detected.isEmpty()) {
                Messages.showInfoMessage(
                        "Detected and added " + detected.size() + " JDK(s)",
                        "Auto-Detection Complete"
                );
            }
        }
    }

    private void addToIntellijSdkTable(JdkInfo jdkInfo) {
        try {
            // This would integrate with IntelliJ's SDK management
            // For now, we'll just track it internally
            LOG.debug("DevTomcat: Would add JDK to IntelliJ: " + jdkInfo.getName());
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error adding JDK to IntelliJ", e);
        }
    }

    private void removeFromIntellijSdkTable(JdkInfo jdkInfo) {
        try {
            // This would integrate with IntelliJ's SDK management
            LOG.debug("DevTomcat: Would remove JDK from IntelliJ: " + jdkInfo.getName());
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error removing JDK from IntelliJ", e);
        }
    }

    @Override
    protected void doOKAction() {
        selectedJdk = jdkList.getSelectedValue();
        super.doOKAction();
    }

    public JdkInfo getSelectedJdk() {
        return selectedJdk;
    }

    public static class JdkInfo {
        private String name;
        private String version;
        private String path;
        private boolean isProjectSdk;

        public JdkInfo(String name, String version, String path, boolean isProjectSdk) {
            this.name = name;
            this.version = version;
            this.path = path;
            this.isProjectSdk = isProjectSdk;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public boolean isProjectSdk() { return isProjectSdk; }
        public void setProjectSdk(boolean projectSdk) { isProjectSdk = projectSdk; }

        @Override
        public String toString() {
            return name + " (" + version + ")";
        }
    }

    private static class JdkRenderer extends com.intellij.ui.ColoredListCellRenderer<JdkInfo> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends JdkInfo> list, JdkInfo value, int index,
                                             boolean selected, boolean hasFocus) {
            if (value != null) {
                append(value.getName(), com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (!value.getVersion().isEmpty()) {
                    append(" " + value.getVersion(), com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (!value.getPath().isEmpty()) {
                        append(" - " + value.getPath(), com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                }
                setIcon(com.intellij.icons.AllIcons.Nodes.PpJdk);
            }
        }
    }

    private static class AddJdkDialog extends DialogWrapper {
        private final Project project;
        private JBTextField nameField;
        private TextFieldWithBrowseButton pathField;
        private JBTextField versionField;
        private JdkInfo jdkInfo;

        public AddJdkDialog(@NotNull Project project) {
            super(project);
            this.project = project;
            setTitle("Add JDK");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(JBUI.scale(450), JBUI.scale(200)));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Name
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JBLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JBTextField("JDK", 20);
            panel.add(nameField, gbc);

            // Path
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JBLabel("JDK Home:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            pathField = new TextFieldWithBrowseButton();
            com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                    pathField, "Select JDK Installation Directory",
                    "Choose a JDK installation directory",
                    project,
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
            );
            pathField.getTextField().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                private void onPathChanged() {
                    String path = pathField.getText().trim();
                    if (!path.isEmpty()) detectVersion(path);
                }
            });
            panel.add(pathField, gbc);

            // Version (auto-detected)
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JBLabel("Version:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            versionField = new JBTextField();
            versionField.setEditable(false);
            panel.add(versionField, gbc);

            return panel;
        }

        private void detectVersion(String path) {
            try {
                File releaseFile = new File(path, "release");
                if (releaseFile.exists()) {
                    String version = "JDK detected";
                    versionField.setText(version);

                    if (nameField.getText().equals("JDK")) {
                        nameField.setText("JDK " + version);
                    }
                } else {
                    versionField.setText("Unknown - Check JDK installation");
                }
            } catch (Exception e) {
                versionField.setText("Error detecting version");
            }
        }

        @Override
        protected void doOKAction() {
            String name = nameField.getText().trim();
            String path = pathField.getText().trim();
            String version = versionField.getText().trim();

            if (name.isEmpty() || path.isEmpty()) {
                Messages.showErrorDialog("Please fill in all required fields", "Invalid Input");
                return;
            }

            File jdkDir = new File(path);
            if (!jdkDir.exists()) {
                Messages.showErrorDialog("JDK installation directory does not exist", "Invalid Path");
                return;
            }

            File binDir = new File(jdkDir, "bin");
            if (!binDir.exists()) {
                Messages.showErrorDialog("Invalid JDK installation - missing bin directory", "Invalid Installation");
                return;
            }

            jdkInfo = new JdkInfo(name, version, path, false);
            super.doOKAction();
        }

        public JdkInfo getJdkInfo() {
            return jdkInfo;
        }
    }

    private static class EditJdkDialog extends DialogWrapper {
        private final Project project;
        private final JdkInfo originalJdk;
        private JBTextField nameField;
        private TextFieldWithBrowseButton pathField;
        private JBTextField versionField;
        private JdkInfo updatedInfo;

        public EditJdkDialog(@NotNull Project project, @NotNull JdkInfo jdk) {
            super(project);
            this.project = project;
            this.originalJdk = jdk;
            setTitle("Edit JDK");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(JBUI.scale(450), JBUI.scale(200)));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.anchor = GridBagConstraints.WEST;

            // Name
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JBLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nameField = new JBTextField(originalJdk.getName(), 20);
            panel.add(nameField, gbc);

            // Path
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JBLabel("JDK Home:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            pathField = new TextFieldWithBrowseButton();
            pathField.setText(originalJdk.getPath());
            com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                    pathField, "Select JDK Installation Directory",
                    "Choose a JDK installation directory",
                    project,
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
            );
            pathField.getTextField().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onPathChanged(); }
                private void onPathChanged() {
                    String path = pathField.getText().trim();
                    if (!path.isEmpty()) detectVersion(path);
                }
            });
            panel.add(pathField, gbc);

            // Version
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            panel.add(new JBLabel("Version:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            versionField = new JBTextField(originalJdk.getVersion(), 20);
            versionField.setEditable(false);
            panel.add(versionField, gbc);

            return panel;
        }

        private void detectVersion(String path) {
            try {
                File releaseFile = new File(path, "release");
                if (releaseFile.exists()) {
                    String version = "JDK detected";
                    versionField.setText(version);
                } else {
                    versionField.setText("Unknown - Check JDK installation");
                }
            } catch (Exception e) {
                versionField.setText("Error detecting version");
            }
        }

        @Override
        protected void doOKAction() {
            String name = nameField.getText().trim();
            String path = pathField.getText().trim();
            String version = versionField.getText().trim();

            if (name.isEmpty() || path.isEmpty()) {
                Messages.showErrorDialog("Please fill in all required fields", "Invalid Input");
                return;
            }

            File jdkDir = new File(path);
            if (!jdkDir.exists()) {
                Messages.showErrorDialog("JDK installation directory does not exist", "Invalid Path");
                return;
            }

            File binDir = new File(jdkDir, "bin");
            if (!binDir.exists()) {
                Messages.showErrorDialog("Invalid JDK installation - missing bin directory", "Invalid Installation");
                return;
            }

            updatedInfo = new JdkInfo(name, version, path, false);
            super.doOKAction();
        }

        public JdkInfo getJdkInfo() {
            return updatedInfo;
        }
    }

    private static class AutoDetectJdkDialog extends DialogWrapper {
        private final Project project;
        private JBList<JdkInfo> detectedList;
        private DefaultListModel<JdkInfo> detectedModel;
        private JButton scanButton;

        public AutoDetectJdkDialog(@NotNull Project project) {
            super(project);
            this.project = project;
            setTitle("Auto-Detect JDKs");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(JBUI.scale(500), JBUI.scale(300)));

            // Instructions
            JBLabel instructions = new JBLabel("<html>Scanning common JDK installation locations...<br>" +
                    "Select JDKs to add to your configuration:</html>");
            instructions.setBorder(JBUI.Borders.empty(10));
            panel.add(instructions, BorderLayout.NORTH);

            // Detected JDKs list
            detectedModel = new DefaultListModel<>();
            detectedList = new JBList<>(detectedModel);
            detectedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            detectedList.setCellRenderer(new JdkRenderer());
            panel.add(new JBScrollPane(detectedList), BorderLayout.CENTER);

            // Scan button
            JPanel buttonPanel = new JPanel(new FlowLayout());
            scanButton = new JButton("Scan Again");
            scanButton.addActionListener(e -> scanForJdks());
            buttonPanel.add(scanButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);

            // Initial scan
            scanForJdks();

            return panel;
        }

        private void scanForJdks() {
            detectedModel.clear();

            // Common JDK installation paths
            String[] commonPaths = {
                    "/usr/lib/jvm",
                    "/Library/Java/JavaVirtualMachines",
                    "C:\\Program Files\\Java",
                    "C:\\Program Files\\Eclipse Foundation",
                    "C:\\Program Files\\AdoptOpenJDK",
                    System.getProperty("user.home") + "/.sdkman/candidates/java"
            };

            for (String path : commonPaths) {
                scanDirectory(path);
            }

            // Also check JAVA_HOME
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isEmpty()) {
                File javaHomeDir = new File(javaHome);
                if (javaHomeDir.exists() && isValidJdk(javaHomeDir)) {
                    JdkInfo jdk = new JdkInfo("JAVA_HOME JDK", "From environment", javaHome, false);
                    detectedModel.addElement(jdk);
                }
            }

            if (detectedModel.isEmpty()) {
                JdkInfo noJdks = new JdkInfo("No JDK installations found", "Try manual configuration", "", false);
                detectedModel.addElement(noJdks);
            }
        }

        private void scanDirectory(String parentPath) {
            File parentDir = new File(parentPath);
            if (parentDir.exists() && parentDir.isDirectory()) {
                File[] children = parentDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory() && isValidJdk(child)) {
                            String name = child.getName();
                            String version = extractVersion(name);
                            JdkInfo jdk = new JdkInfo(name, version, child.getAbsolutePath(), false);
                            detectedModel.addElement(jdk);
                        }
                    }
                }
            }
        }

        private boolean isValidJdk(File dir) {
            File binDir = new File(dir, "bin");
            File javaExe = new File(binDir, System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            return binDir.exists() && javaExe.exists();
        }

        private String extractVersion(String dirName) {
            // Extract version from directory names like "jdk-11.0.2", "java-17-openjdk", etc.
            if (dirName.contains("-")) {
                String[] parts = dirName.split("-");
                for (String part : parts) {
                    if (part.matches("\\d+.*")) {
                        return "JDK " + part;
                    }
                }
            }
            return "JDK";
        }

        public List<JdkInfo> getDetectedJdks() {
            List<JdkInfo> selected = detectedList.getSelectedValuesList();
            // Filter out the "no JDKs found" placeholder
            return selected.stream()
                    .filter(jdk -> !jdk.getName().equals("No JDK installations found"))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }
}
