package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.JREConfigurationDialog;
import com.dev.idea.plugins.tomcat.utils.TomcatProgress;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.intellij.openapi.diagnostic.Logger;

public class JreConfigurationSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(JreConfigurationSection.class);

    private final Project project;
    private ComboBox<JreEntry> jreComboBox;
    private JPanel panel;
    private MessageBusConnection jdkTableConnection;
    private Disposable connectionDisposable;

    public JreConfigurationSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(0));
            GridBagConstraints gbc = new GridBagConstraints();
            jreComboBox = new ComboBox<>();
            jreComboBox.setRenderer(new JreEntryRenderer());
            ConfigurationSection.addLabelAndField(panel, gbc, 0,
                    new JBLabel("JRE:"), jreComboBox);

            ConfigurationSection.addConfigureButton(panel, gbc, e -> configureJRE());

            subscribeToJdkTableChanges();
        }
        return panel;
    }

    /**
     * Listens for project-SDK table changes so the combo refreshes if the user adds,
     * removes, or renames a JDK in another tab (Project Structure) while this editor
     * is open. Without the subscription the combo shows a stale list until the dialog
     * is reopened. Preserves the current selection across rebuilds when possible.
     */
    private void subscribeToJdkTableChanges() {
        try {
            connectionDisposable = Disposer.newDisposable("DevTomcat.JreConfigurationSection");
            jdkTableConnection = ApplicationManager.getApplication().getMessageBus()
                    .connect(connectionDisposable);
            jdkTableConnection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC,
                    new ProjectJdkTable.Listener() {
                        @Override public void jdkAdded(@NotNull Sdk jdk) { rebuildPreservingSelection(); }
                        @Override public void jdkRemoved(@NotNull Sdk jdk) { rebuildPreservingSelection(); }
                        @Override public void jdkNameChanged(@NotNull Sdk jdk, @NotNull String previousName) {
                            rebuildPreservingSelection();
                        }
                    });
        } catch (Exception e) {
            TomcatProgress.rethrowIfControlFlow(e);
            LOG.debug("Could not subscribe to JDK table changes", e);
        }
    }

    private void rebuildPreservingSelection() {
        if (jreComboBox == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (jreComboBox == null) return;
            JreEntry previous = (JreEntry) jreComboBox.getSelectedItem();
            String previousSdkName = previous != null ? previous.sdkName : null;
            boolean previousDefault = previous == null || previous.isDefault;
            loadConfiguration();
            if (previousDefault) {
                if (jreComboBox.getItemCount() > 0) jreComboBox.setSelectedIndex(0);
                return;
            }
            for (int i = 0; i < jreComboBox.getItemCount(); i++) {
                JreEntry entry = jreComboBox.getItemAt(i);
                if (Objects.equals(previousSdkName, entry.sdkName)) {
                    jreComboBox.setSelectedIndex(i);
                    return;
                }
            }
            // Previously-selected SDK was removed. Fall back to the project default.
            if (jreComboBox.getItemCount() > 0) jreComboBox.setSelectedIndex(0);
        });
    }

    @Override
    public void loadConfiguration() {
        jreComboBox.removeAllItems();

        // Add project default entry with actual SDK version info
        String projectSdkLabel = buildProjectSdkLabel();
        jreComboBox.addItem(new JreEntry(projectSdkLabel, null, true));

        // Add all configured Java SDKs
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (sdk.getSdkType() instanceof JavaSdk) {
                String version = sdk.getVersionString();
                String label = sdk.getName() + (version != null ? " (" + version + ")" : "");
                jreComboBox.addItem(new JreEntry(label, sdk.getName(), false));
            }
        }
    }

    private String buildProjectSdkLabel() {
        try {
            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk != null) {
                String version = projectSdk.getVersionString();
                String majorVersion = extractMajorVersion(version);
                if (majorVersion != null) {
                    return "Default (" + majorVersion + " - project SDK)";
                }
                return "Default (" + projectSdk.getName() + " - project SDK)";
            }
        } catch (Exception e) {
            TomcatProgress.rethrowIfControlFlow(e);
            LOG.debug("Could not detect project SDK", e);
        }
        return "Default (project SDK)";
    }

    /**
     * Extracts the user-facing Java major version from a version string. Handles
     * "17.0.2", "21.0.1+12-LTS", "1.8.0_351", and free-form prefixes like
     * "java version \"21.0.1\"".
     *
     * <p>Tries {@link Runtime.Version#parse} first for canonical Java 9+ strings.
     * Falls back to a digit-and-dot extraction so legacy "1.8.x" and quoted
     * vendor strings still resolve to a sensible number.
     */
    static String extractMajorVersion(String versionString) {
        if (versionString == null) return null;

        // Strip any non-version prefix (e.g. "java version "21.0.1+12"")
        // before handing to Runtime.Version.
        String trimmed = versionString.trim().replaceAll("^[^0-9]*", "");
        if (trimmed.isEmpty()) return null;

        // Cut at the first character that Runtime.Version.parse can't accept.
        // Permitted: digits, '.', '+', '-' (build/pre-release).
        int end = 0;
        while (end < trimmed.length() && isVersionChar(trimmed.charAt(end))) {
            end++;
        }
        String candidate = trimmed.substring(0, end);
        // Strip trailing punctuation that would trip the parser ("17." or "21+").
        while (!candidate.isEmpty()) {
            char last = candidate.charAt(candidate.length() - 1);
            if (last == '.' || last == '+' || last == '-') {
                candidate = candidate.substring(0, candidate.length() - 1);
            } else break;
        }
        if (candidate.isEmpty()) return null;

        try {
            Runtime.Version v = Runtime.Version.parse(candidate);
            int feature = v.feature();
            // feature() returns 1 for the legacy "1.x" scheme. The user-facing
            // major in that case is x (e.g. 1.8 -> 8).
            if (feature == 1 && v.version().size() > 1) {
                return String.valueOf(v.version().get(1));
            }
            return String.valueOf(feature);
        } catch (IllegalArgumentException ignored) {
            // Fall through to digit-only fallback for non-canonical strings.
        }

        // Fallback: pull the first dotted-numeric run and reuse the 1.x rule.
        String cleaned = candidate.replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty()) return null;
        String[] parts = cleaned.split("\\.");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        if ("1".equals(parts[0]) && parts.length > 1 && !parts[1].isEmpty()) {
            return parts[1];
        }
        return parts[0];
    }

    private static boolean isVersionChar(char c) {
        return Character.isDigit(c) || c == '.' || c == '+' || c == '-';
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        String saved = configuration.getConfigData().getJreSelection();
        if (saved == null || saved.isEmpty()
                || TomcatConstants.JRE_PROJECT_DEFAULT.equals(saved)) {
            if (jreComboBox.getItemCount() > 0) {
                jreComboBox.setSelectedIndex(0);
            }
        } else {
            boolean found = false;
            for (int i = 0; i < jreComboBox.getItemCount(); i++) {
                JreEntry entry = jreComboBox.getItemAt(i);
                if (saved.equals(entry.sdkName)) {
                    jreComboBox.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found && jreComboBox.getItemCount() > 0) {
                jreComboBox.setSelectedIndex(0);
            }
        }
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        JreEntry selected = (JreEntry) jreComboBox.getSelectedItem();
        if (selected != null) {
            String sdkName = selected.isDefault
                    ? TomcatConstants.JRE_PROJECT_DEFAULT
                    : selected.sdkName;
            configuration.getConfigData().setJreSelection(sdkName);
        }
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        JreEntry selected = (JreEntry) jreComboBox.getSelectedItem();
        if (selected == null) return false;
        String current = selected.isDefault
                ? TomcatConstants.JRE_PROJECT_DEFAULT
                : selected.sdkName;
        String saved = config.getConfigData().getJreSelection();
        return !Objects.equals(current, saved);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        return Collections.emptyList();
    }

    private void configureJRE() {
        try {
            JREConfigurationDialog dialog = new JREConfigurationDialog(project);
            if (dialog.showAndGet()) {
                JREConfigurationDialog.JdkInfo selectedJdk = dialog.getSelectedJdk();
                if (selectedJdk != null) {
                    loadConfiguration();
                    if (!selectedJdk.isProjectSdk()) {
                        // Select the matching SDK in the combo
                        for (int i = 0; i < jreComboBox.getItemCount(); i++) {
                            JreEntry entry = jreComboBox.getItemAt(i);
                            if (selectedJdk.getName().equals(entry.sdkName)) {
                                jreComboBox.setSelectedIndex(i);
                                break;
                            }
                        }
                    } else {
                        jreComboBox.setSelectedIndex(0);
                    }
                    LOG.debug("JRE configuration updated to: " + selectedJdk.getName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Error opening JRE configuration", e);
            Messages.showErrorDialog(project, "Failed to open JRE configuration: " + e.getMessage(), "Error");
        }
    }

    @Override
    public void dispose() {
        if (jdkTableConnection != null) {
            try { jdkTableConnection.disconnect(); } catch (Exception ignored) {}
            jdkTableConnection = null;
        }
        if (connectionDisposable != null) {
            try { Disposer.dispose(connectionDisposable); } catch (Exception ignored) {}
            connectionDisposable = null;
        }
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    private static class JreEntry {
        final String label;
        final String sdkName;
        final boolean isDefault;

        JreEntry(String label, String sdkName, boolean isDefault) {
            this.label = label;
            this.sdkName = sdkName;
            this.isDefault = isDefault;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class JreEntryRenderer extends com.intellij.ui.SimpleListCellRenderer<JreEntry> {
        @Override
        public void customize(@NotNull JList<? extends JreEntry> list, JreEntry value, int index,
                              boolean selected, boolean hasFocus) {
            if (value != null) {
                setText(value.label);
                setToolTipText(value.isDefault ? "Uses the project's configured SDK" : value.sdkName);
            }
        }
    }
}
