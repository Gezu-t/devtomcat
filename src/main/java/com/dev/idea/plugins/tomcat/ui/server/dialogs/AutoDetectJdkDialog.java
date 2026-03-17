package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans common system paths for JDK installations and lets the user
 * multi-select which ones to import.
 */
class AutoDetectJdkDialog extends DialogWrapper {

    private final DefaultListModel<JREConfigurationDialog.JdkInfo> detectedModel = new DefaultListModel<>();
    private JBList<JREConfigurationDialog.JdkInfo> detectedList;

    AutoDetectJdkDialog(@NotNull Project project) {
        super(project);
        setTitle("Auto-Detect JDKs");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(500), JBUI.scale(300)));

        JBLabel instructions = new JBLabel("<html>Scanning common JDK installation locations...<br>" +
                "Select JDKs to add to your configuration:</html>");
        instructions.setBorder(JBUI.Borders.empty(10));
        panel.add(instructions, BorderLayout.NORTH);

        detectedList = new JBList<>(detectedModel);
        detectedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        detectedList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends JREConfigurationDialog.JdkInfo> list,
                                                 JREConfigurationDialog.JdkInfo value, int index,
                                                 boolean selected, boolean hasFocus) {
                if (value != null) {
                    append(value.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    if (!value.getVersion().isEmpty()) {
                        append(" " + value.getVersion(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    if (!value.getPath().isEmpty()) {
                        append(" - " + value.getPath(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                    setIcon(AllIcons.Nodes.PpJdk);
                }
            }
        });
        panel.add(new JBScrollPane(detectedList), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton scanButton = new JButton("Scan Again");
        scanButton.addActionListener(e -> scanForJdks());
        buttonPanel.add(scanButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        scanForJdks();
        return panel;
    }

    private void scanForJdks() {
        detectedModel.clear();

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

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            File javaHomeDir = new File(javaHome);
            if (javaHomeDir.exists() && isValidJdk(javaHomeDir)) {
                detectedModel.addElement(new JREConfigurationDialog.JdkInfo(
                        "JAVA_HOME JDK", "From environment", javaHome, false));
            }
        }

        if (detectedModel.isEmpty()) {
            detectedModel.addElement(new JREConfigurationDialog.JdkInfo(
                    "No JDK installations found", "Try manual configuration", "", false));
        }
    }

    private void scanDirectory(String parentPath) {
        File parentDir = new File(parentPath);
        if (!parentDir.exists() || !parentDir.isDirectory()) return;
        File[] children = parentDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && isValidJdk(child)) {
                String name = child.getName();
                detectedModel.addElement(new JREConfigurationDialog.JdkInfo(
                        name, extractVersion(name), child.getAbsolutePath(), false));
            }
        }
    }

    private static boolean isValidJdk(File dir) {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(dir, "bin/" + exe).exists();
    }

    private static String extractVersion(String dirName) {
        if (dirName.contains("-")) {
            for (String part : dirName.split("-")) {
                if (part.matches("\\d+.*")) return "JDK " + part;
            }
        }
        return "JDK";
    }

    @NotNull
    List<JREConfigurationDialog.JdkInfo> getDetectedJdks() {
        return detectedList.getSelectedValuesList().stream()
                .filter(jdk -> !jdk.getName().equals("No JDK installations found"))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
