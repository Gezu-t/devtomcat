package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.SeparatorComponent;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Dialog for managing JDK/JRE entries used by Tomcat run configurations.
 * Delegates Add/Edit to {@link JdkEditorDialog} and auto-detection to {@link AutoDetectJdkDialog}.
 */
public class JREConfigurationDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(JREConfigurationDialog.class);

    private final Project project;
    private JBList<JdkInfo> jdkList;
    private DefaultListModel<JdkInfo> listModel;
    private JButton editButton;
    private JButton removeButton;
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

        listModel = new DefaultListModel<>();
        jdkList = new JBList<>(listModel);
        jdkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jdkList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends JdkInfo> list, JdkInfo value,
                                                 int index, boolean selected, boolean hasFocus) {
                if (value != null) {
                    append(value.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    if (!value.getVersion().isEmpty()) {
                        append(" " + value.getVersion(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        if (!value.getPath().isEmpty()) {
                            append(" - " + value.getPath(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        }
                    }
                    setIcon(AllIcons.Nodes.PpJdk);
                }
            }
        });
        jdkList.addListSelectionListener(e -> updateButtonStates());

        mainPanel.add(createInstructionsPanel(), BorderLayout.NORTH);
        mainPanel.add(new JBScrollPane(jdkList), BorderLayout.CENTER);
        mainPanel.add(createButtonsPanel(), BorderLayout.EAST);

        return mainPanel;
    }

    private JPanel createInstructionsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        JBLabel titleLabel = new JBLabel("Available JDKs/JREs:");
        titleLabel.setFont(JBFont.label().asBold());
        panel.add(titleLabel, BorderLayout.WEST);

        JBLabel helpLabel = new JBLabel("Select a Java runtime environment for Tomcat execution");
        helpLabel.setFont(JBFont.small());
        helpLabel.setForeground(com.intellij.ui.JBColor.GRAY);
        panel.add(helpLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createButtonsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JButton addButton = new JButton("Add...");
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

        gbc.gridy = 3;
        gbc.insets = JBUI.insets(15, 5, 5, 5);
        panel.add(new SeparatorComponent(), gbc);

        JButton detectButton = new JButton("Auto-Detect");
        detectButton.addActionListener(this::autoDetectJdks);
        detectButton.setToolTipText("Automatically detect JDK installations");
        gbc.gridy = 4;
        gbc.insets = JBUI.insets(5);
        panel.add(detectButton, gbc);

        return panel;
    }

    private void loadJdks() {
        listModel.clear();
        if (!project.isDefault()) {
            listModel.addElement(new JdkInfo("Project SDK", "Uses project's configured JDK", "", true));
        }
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (sdk.getSdkType() instanceof JavaSdk) {
                String version = sdk.getVersionString() != null ? sdk.getVersionString() : "Unknown";
                String path = sdk.getHomePath() != null ? sdk.getHomePath() : "";
                listModel.addElement(new JdkInfo(sdk.getName(), version, path, false));
            }
        }
        if (listModel.getSize() > 0) {
            jdkList.setSelectedIndex(0);
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        JdkInfo selected = jdkList.getSelectedValue();
        boolean canEdit = selected != null && !selected.isProjectSdk();
        editButton.setEnabled(canEdit);
        removeButton.setEnabled(canEdit);
    }

    private void addJdk(ActionEvent e) {
        JdkEditorDialog dialog = new JdkEditorDialog(project, null);
        if (dialog.showAndGet()) {
            JdkInfo newJdk = dialog.getJdkInfo();
            if (newJdk != null) {
                listModel.addElement(newJdk);
                jdkList.setSelectedValue(newJdk, true);
                updateButtonStates();
                LOG.debug("DevTomcat: Added JDK: " + newJdk.getName());
            }
        }
    }

    private void editJdk(ActionEvent e) {
        JdkInfo selected = jdkList.getSelectedValue();
        if (selected == null || selected.isProjectSdk()) return;

        JdkEditorDialog dialog = new JdkEditorDialog(project, selected);
        if (dialog.showAndGet()) {
            JdkInfo updated = dialog.getJdkInfo();
            if (updated != null) {
                selected.setName(updated.getName());
                selected.setPath(updated.getPath());
                selected.setVersion(updated.getVersion());
                int index = listModel.indexOf(selected);
                listModel.setElementAt(selected, index);
                jdkList.repaint();
            }
        }
    }

    private void removeJdk(ActionEvent e) {
        JdkInfo selected = jdkList.getSelectedValue();
        if (selected == null || selected.isProjectSdk()) return;

        if (Messages.showYesNoDialog("Remove JDK '" + selected.getName() + "'?",
                "Remove JDK", Messages.getQuestionIcon()) == Messages.YES) {
            listModel.removeElement(selected);
            updateButtonStates();
        }
    }

    private void autoDetectJdks(ActionEvent e) {
        AutoDetectJdkDialog dialog = new AutoDetectJdkDialog(project);
        if (dialog.showAndGet()) {
            List<JdkInfo> detected = dialog.getDetectedJdks();
            for (JdkInfo jdk : detected) {
                listModel.addElement(jdk);
            }
            updateButtonStates();
            if (!detected.isEmpty()) {
                Messages.showInfoMessage("Detected and added " + detected.size() + " JDK(s)",
                        "Auto-Detection Complete");
            }
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

    /**
     * Represents a JDK/JRE entry in the configuration dialog.
     */
    public static class JdkInfo {
        private String name;
        private String version;
        private String path;
        private final boolean isProjectSdk;

        public JdkInfo(String name, String version, String path, boolean isProjectSdk) {
            this.name = name;
            this.version = version;
            this.path = path;
            this.isProjectSdk = isProjectSdk;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isProjectSdk() { return isProjectSdk; }

        @Override
        public String toString() { return name + " (" + version + ")"; }
    }
}
