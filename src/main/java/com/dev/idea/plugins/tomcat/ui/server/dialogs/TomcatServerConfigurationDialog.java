package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class TomcatServerConfigurationDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(TomcatServerConfigurationDialog.class);

    private final Project project;
    private final DefaultListModel<TomcatInfo> listModel = new DefaultListModel<>();
    private final JBList<TomcatInfo> serverList = new JBList<>(listModel);
    private final JBTextField nameField = new JBTextField();
    private final TextFieldWithBrowseButton homeField = new TextFieldWithBrowseButton();
    private final JBLabel versionLabel = new JBLabel();
    private final TextFieldWithBrowseButton baseField = new TextFieldWithBrowseButton();
    private final Tree librariesTree = new Tree(new DefaultMutableTreeNode("Classes"));
    private boolean updatingDetails;
    private final LibraryStateController libraryController = new LibraryStateController();

    public TomcatServerConfigurationDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("Application Servers");
        init();
        loadServers();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(520)));
        panel.add(createSplitPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createSplitPanel() {
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer(new ServerListRenderer());
        serverList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedServerIntoDetails();
            }
        });

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(serverList)
                .setAddAction(button -> addServer())
                .setRemoveAction(button -> removeSelectedServer())
                .disableUpDownActions();

        JPanel listPanel = decorator.createPanel();
        listPanel.setPreferredSize(new Dimension(JBUI.scale(210), JBUI.scale(460)));

        JPanel detailsPanel = createDetailsPanel();
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailsPanel);
        splitPane.setResizeWeight(0.32);
        splitPane.setDividerLocation(JBUI.scale(220));
        splitPane.setBorder(JBUI.Borders.empty());
        return splitPane;
    }

    private JPanel createDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.emptyLeft(12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(8, 0, 8, 8);

        // Name
        form.add(new JBLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(nameField, gbc);

        // Tomcat Home
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JBLabel("Tomcat Home:"), gbc);

        com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                homeField, "Tomcat Home", "Select Tomcat installation directory",
                project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(homeField, gbc);

        // Tomcat Version
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JBLabel("Tomcat Version:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(versionLabel, gbc);

        // Tomcat base directory
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JBLabel("Tomcat base directory:"), gbc);

        com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil.addBrowseFolderListener(
                baseField, "Tomcat Base Directory", "Select Tomcat base directory (CATALINA_BASE)",
                project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(baseField, gbc);

        panel.add(form, BorderLayout.NORTH);

        // Libraries section
        JPanel librariesPanel = createLibrariesPanel();
        panel.add(librariesPanel, BorderLayout.CENTER);

        bindDetailEditors();
        setDetailFieldsEnabled(false);
        return panel;
    }

    private JPanel createLibrariesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.emptyTop(16));

        JPanel headerPanel = new JPanel(new BorderLayout());
        JBLabel librariesLabel = new JBLabel("Libraries");
        librariesLabel.setBorder(JBUI.Borders.emptyBottom(2));
        headerPanel.add(librariesLabel, BorderLayout.NORTH);

        JBLabel hintLabel = new JBLabel("Defaults show Tomcat API jars only. Custom entries are stored per server.");
        hintLabel.setForeground(com.intellij.util.ui.NamedColorUtil.getInactiveTextColor());
        hintLabel.setFont(com.intellij.util.ui.JBFont.small());
        hintLabel.setBorder(JBUI.Borders.emptyBottom(6));
        headerPanel.add(hintLabel, BorderLayout.SOUTH);

        panel.add(headerPanel, BorderLayout.NORTH);

        librariesTree.setRootVisible(true);
        librariesTree.setShowsRootHandles(true);
        librariesTree.setCellRenderer(new LibraryTreeRenderer());

        ToolbarDecorator libraryDecorator = ToolbarDecorator.createDecorator(librariesTree)
                .setAddAction(button -> addLibraryJar())
                .setRemoveAction(button -> removeSelectedLibraryNode())
                .disableUpDownActions();

        panel.add(libraryDecorator.createPanel(), BorderLayout.CENTER);
        return panel;
    }

    private void addLibraryJar() {
        com.intellij.openapi.fileChooser.FileChooserDescriptor descriptor =
                new com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, true, true, false, true)
                        .withTitle("Select Library JARs")
                        .withDescription("Choose JAR files to add to the library");
        com.intellij.openapi.vfs.VirtualFile[] files =
                com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null);
        if (files.length == 0) return;

        DefaultTreeModel model = (DefaultTreeModel) librariesTree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        for (com.intellij.openapi.vfs.VirtualFile file : files) {
            root.add(new DefaultMutableTreeNode(file.getPath()));
        }
        model.reload();
        for (int i = 0; i < librariesTree.getRowCount(); i++) {
            librariesTree.expandRow(i);
        }
        persistTreeToSelectedServer();
    }

    private void removeSelectedLibraryNode() {
        javax.swing.tree.TreePath path = librariesTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.isRoot()) return;
        DefaultTreeModel model = (DefaultTreeModel) librariesTree.getModel();
        model.removeNodeFromParent(node);
        persistTreeToSelectedServer();
    }

    private void persistTreeToSelectedServer() {
        TomcatInfo selected = serverList.getSelectedValue();
        if (selected == null) return;
        libraryController.persistLibraries(selected, collectTreeLibraries());
    }

    private List<String> collectTreeLibraries() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) librariesTree.getModel().getRoot();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            Object userObject = child.getUserObject();
            if (userObject instanceof String str) {
                paths.add(str);
            }
        }
        return paths;
    }

    private void bindDetailEditors() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSelectedServerFromDetails();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSelectedServerFromDetails();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSelectedServerFromDetails();
            }
        };

        nameField.getDocument().addDocumentListener(listener);
        homeField.getTextField().getDocument().addDocumentListener(listener);
        baseField.getTextField().getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateBaseFromField(); }
            @Override public void removeUpdate(DocumentEvent e) { updateBaseFromField(); }
            @Override public void changedUpdate(DocumentEvent e) { updateBaseFromField(); }
        });
    }

    private void updateBaseFromField() {
        if (updatingDetails) return;
        TomcatInfo selected = serverList.getSelectedValue();
        if (selected != null) {
            selected.setCatalinaBase(baseField.getText().trim());
        }
    }

    private void loadServers() {
        listModel.clear();
        for (TomcatInfo info : TomcatServerManagerState.getInstance().getTomcatInfos()) {
            listModel.addElement(info);
        }

        if (!listModel.isEmpty()) {
            serverList.setSelectedIndex(0);
        } else {
            loadSelectedServerIntoDetails();
        }
    }

    private void addServer() {
        TomcatInfo info = new TomcatInfo();
        info.setName(generateServerName());
        listModel.addElement(info);
        serverList.setSelectedValue(info, true);
    }

    private void removeSelectedServer() {
        TomcatInfo selected = serverList.getSelectedValue();
        if (selected == null) {
            return;
        }

        int result = Messages.showYesNoDialog(
                project,
                "Remove application server '" + selected.getName() + "'?",
                "Remove Application Server",
                Messages.getQuestionIcon()
        );
        if (result != Messages.YES) {
            return;
        }

        int removedIndex = serverList.getSelectedIndex();
        listModel.removeElement(selected);
        if (!listModel.isEmpty()) {
            serverList.setSelectedIndex(Math.min(removedIndex, listModel.size() - 1));
        } else {
            loadSelectedServerIntoDetails();
        }
    }

    private void loadSelectedServerIntoDetails() {
        updatingDetails = true;
        try {
            TomcatInfo selected = serverList.getSelectedValue();
            boolean enabled = selected != null;
            setDetailFieldsEnabled(enabled);
            if (selected == null) {
                nameField.setText("");
                homeField.setText("");
                versionLabel.setText("");
                baseField.setText("");
                updateLibrariesTree("");
                return;
            }

            nameField.setText(selected.getName());
            homeField.setText(selected.getPath());
            versionLabel.setText(selected.getVersion());
            baseField.setText(selected.getCatalinaBase());
            libraryController.onServerLoaded(selected);
            updateLibrariesTree(selected.getPath());
        } finally {
            updatingDetails = false;
        }
    }

    private void updateSelectedServerFromDetails() {
        if (updatingDetails) {
            return;
        }

        TomcatInfo selected = serverList.getSelectedValue();
        if (selected == null) {
            return;
        }

        selected.setName(nameField.getText().trim());
        String home = homeField.getText().trim();
        selected.setPath(home);

        Optional<TomcatInfo> detected = detectTomcatInfo(home);
        updatingDetails = true;
        try {
            if (detected.isPresent()) {
                TomcatInfo info = detected.get();
                selected.setVersion(info.getVersion());
                if (selected.getName().isEmpty()) {
                    selected.setName(info.getName());
                    nameField.setText(selected.getName());
                }
                versionLabel.setText(info.getVersion());
                // Set catalinaBase to home if not already customized
                if (selected.getCatalinaBase().equals(selected.getPath()) ||
                        selected.getCatalinaBase().isEmpty()) {
                    selected.setCatalinaBase(home);
                    baseField.setText(home);
                }
                libraryController.onHomeChanged(selected, home, true);
            } else {
                selected.setVersion("");
                versionLabel.setText("");
                if (selected.getCatalinaBase().isEmpty()) {
                    selected.setCatalinaBase(home);
                    baseField.setText(home);
                }
            }
        } finally {
            updatingDetails = false;
        }

        updateLibrariesTree(home);
        serverList.repaint();
    }

    private void setDetailFieldsEnabled(boolean enabled) {
        nameField.setEnabled(enabled);
        homeField.setEnabled(enabled);
        baseField.setEnabled(enabled);
        librariesTree.setEnabled(enabled);
    }

    private void updateLibrariesTree(@Nullable String tomcatHome) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Classes");
        TomcatInfo selected = serverList.getSelectedValue();

        if (selected != null) {
            for (String path : libraryController.resolveLibraries(selected, tomcatHome)) {
                root.add(new DefaultMutableTreeNode(path));
            }
        }
        librariesTree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < librariesTree.getRowCount(); i++) {
            librariesTree.expandRow(i);
        }
    }

    @Override
    protected void doOKAction() {
        for (int i = 0; i < listModel.size(); i++) {
            TomcatInfo info = listModel.getElementAt(i);
            if (info.getName().isBlank()) {
                Messages.showErrorDialog(project, "Application server name cannot be empty.", "Invalid Configuration");
                serverList.setSelectedIndex(i);
                return;
            }
            if (info.getPath().isBlank()) {
                Messages.showErrorDialog(project, "Tomcat Home cannot be empty.", "Invalid Configuration");
                serverList.setSelectedIndex(i);
                return;
            }
            if (!new File(info.getPath()).exists()) {
                Messages.showErrorDialog(project, "Tomcat Home does not exist: " + info.getPath(), "Invalid Configuration");
                serverList.setSelectedIndex(i);
                return;
            }
        }

        // Commit the dialog's local list to persistent state
        List<TomcatInfo> committed = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            committed.add(listModel.getElementAt(i));
        }
        TomcatServerManagerState.getInstance().setTomcatInfos(committed);

        super.doOKAction();
    }

    private String generateServerName() {
        String base = "Tomcat";
        if (!isNameUsedInList(base)) {
            return base;
        }

        int index = 2;
        while (isNameUsedInList(base + " " + index)) {
            index++;
        }
        return base + " " + index;
    }

    private boolean isNameUsedInList(@NotNull String name) {
        for (int i = 0; i < listModel.size(); i++) {
            if (name.equals(listModel.getElementAt(i).getName())) {
                return true;
            }
        }
        return false;
    }

    private Optional<TomcatInfo> detectTomcatInfo(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        return TomcatServerManagerState.tryCreateTomcatInfo(path);
    }

    // =========================================================================
    // Custom Renderers
    // =========================================================================

    private static final class ServerListRenderer extends com.intellij.ui.SimpleListCellRenderer<TomcatInfo> {
        @Override
        public void customize(@NotNull JList<? extends TomcatInfo> list, TomcatInfo value, int index,
                              boolean selected, boolean hasFocus) {
            if (value != null) {
                setText(value.getName().isBlank() ? "Unnamed server" : value.getName());
                setIcon(AllIcons.Nodes.Deploy);
            }
        }
    }

    private static final class LibraryTreeRenderer extends com.intellij.ui.ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof String str) {
                    if (node.isRoot()) {
                        setIcon(AllIcons.Nodes.PpLibFolder);
                        append("Classes");
                    } else {
                        setIcon(AllIcons.FileTypes.Archive);
                        append(str);
                    }
                }
            }
        }
    }
}
