package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.utils.TomcatProgress;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.SystemInfo;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans common system paths for JDK installations and lets the user
 * multi-select which ones to import.
 */
class AutoDetectJdkDialog extends DialogWrapper {

    private final DefaultListModel<JREConfigurationDialog.JdkInfo> detectedModel = new DefaultListModel<>();
    private JBList<JREConfigurationDialog.JdkInfo> detectedList;
    private JBLabel instructionsLabel;
    private JButton scanButton;
    private final Project project;

    AutoDetectJdkDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("Auto-Detect JDKs");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(500), JBUI.scale(300)));

        instructionsLabel = new JBLabel();
        instructionsLabel.setBorder(JBUI.Borders.empty(10));
        panel.add(instructionsLabel, BorderLayout.NORTH);

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
        scanButton = new JButton("Scan Again");
        scanButton.addActionListener(e -> scanForJdks());
        buttonPanel.add(scanButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        scanForJdks();
        return panel;
    }

    /**
     * Kicks off a JDK scan. The platform discovery and filesystem walk are blocking
     * disk/registry I/O, so they run on a pooled thread — the modal dialog never
     * freezes — and the results are published to the list on the EDT. Re-entrant via
     * "Scan Again", which is disabled while a scan is in flight.
     */
    private void scanForJdks() {
        scanButton.setEnabled(false);
        instructionsLabel.setText("<html>Scanning common JDK installation locations…</html>");
        detectedModel.clear();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<JREConfigurationDialog.JdkInfo> found = collectJdks();
            // ModalityState.any(): the runnable only mutates this dialog's own Swing
            // state, so it is safe to run while the modal is up and must not wait for it.
            ApplicationManager.getApplication().invokeLater(() -> publishResults(found), ModalityState.any());
        });
    }

    /**
     * Off-EDT: gather candidate JDKs from platform discovery, curated install roots,
     * and {@code JAVA_HOME}, deduped by canonical path. Touches no Swing state.
     */
    private List<JREConfigurationDialog.JdkInfo> collectJdks() {
        // Canonical paths track what's already added, so a JDK surfaced by both
        // platform discovery and the curated fallback below appears only once.
        Set<String> seen = new HashSet<>();
        List<JREConfigurationDialog.JdkInfo> found = new ArrayList<>();

        // 1. Authoritative: the platform's own OS-aware JDK discovery. JetBrains
        //    maintains it and keeps it current (~/.jdks where the IDE downloads
        //    JDKs, Homebrew, sdkman, the Windows registry, current vendor dirs),
        //    so it finds installs the curated list below misses or names wrongly.
        try {
            // suggestHomePaths(Project) is the current, non-deprecated overload
            // (introduced in 251; our sinceBuild floor is 251). It runs the
            // platform's project-aware JDK discovery — safe here on a pooled thread.
            for (String home : JavaSdk.getInstance().suggestHomePaths(project)) {
                addJdkCandidate(new File(home), seen, found);
            }
        } catch (Throwable t) {
            TomcatProgress.rethrowIfControlFlow(t);
            // Discovery must never break the dialog — the curated scan still runs.
        }

        // 2. Fallback: curated well-known install roots, for setups platform
        //    discovery misses. Kept as a union member, not the primary source.
        String[] commonPaths = {
                "/usr/lib/jvm",
                "/Library/Java/JavaVirtualMachines",
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Foundation",
                "C:\\Program Files\\AdoptOpenJDK",
                System.getProperty("user.home") + "/.sdkman/candidates/java"
        };
        for (String path : commonPaths) {
            scanDirectory(path, seen, found);
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            addJdkCandidate(new File(javaHome), seen, found);
        }
        return found;
    }

    /** EDT: replace the list contents with the freshly discovered JDKs. */
    private void publishResults(@NotNull List<JREConfigurationDialog.JdkInfo> found) {
        detectedModel.clear();
        for (JREConfigurationDialog.JdkInfo info : found) {
            detectedModel.addElement(info);
        }
        if (detectedModel.isEmpty()) {
            detectedModel.addElement(new JREConfigurationDialog.JdkInfo(
                    "No JDK installations found", "Try manual configuration", "", false));
        }
        instructionsLabel.setText("<html>Select the JDK installations to add to your configuration:</html>");
        scanButton.setEnabled(true);
    }

    private void scanDirectory(String parentPath, @NotNull Set<String> seen,
                               @NotNull List<JREConfigurationDialog.JdkInfo> out) {
        File parentDir = new File(parentPath);
        if (!parentDir.exists() || !parentDir.isDirectory()) return;
        File[] children = parentDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            addJdkCandidate(child, seen, out);
        }
    }

    /**
     * Adds {@code dir} to {@code out} as a detected JDK if it is a valid JDK home
     * not already listed (deduped by canonical path). Shared by platform discovery,
     * the curated directory scan, and the JAVA_HOME check so all three agree on
     * validation and de-duplication.
     */
    private void addJdkCandidate(@NotNull File dir, @NotNull Set<String> seen,
                                 @NotNull List<JREConfigurationDialog.JdkInfo> out) {
        if (!dir.isDirectory() || !isValidJdk(dir)) return;
        String canonical;
        try {
            canonical = dir.getCanonicalPath();
        } catch (IOException e) {
            canonical = dir.getAbsolutePath();
        }
        if (!seen.add(canonical)) return;
        String name = dir.getName();
        out.add(new JREConfigurationDialog.JdkInfo(
                name, extractVersion(name), dir.getAbsolutePath(), false));
    }

    // Package-private for AutoDetectJdkDialogTest.
    static boolean isValidJdk(File dir) {
        // Use IntelliJ's SystemInfo rather than ad-hoc parsing of os.name. The
        // previous implementation called {@code os.name.toLowerCase()} without
        // a locale argument: under a Turkish locale, "Windows" lowercases to
        // "wındows" (dotless ı) and the {@code .contains("win")} check returned
        // false — JDK auto-detection then looked for "bin/java" instead of
        // "bin/java.exe" and reported every Windows JDK as invalid.
        String exe = SystemInfo.isWindows ? "java.exe" : "java";
        return new File(dir, "bin/" + exe).exists();
    }

    // Package-private for AutoDetectJdkDialogTest.
    static String extractVersion(String dirName) {
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
