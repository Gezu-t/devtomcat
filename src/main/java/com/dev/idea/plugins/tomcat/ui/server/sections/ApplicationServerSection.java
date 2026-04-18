package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.TomcatServerConfigurationDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Application Server section.
 *
 * <p>The run configuration persists an embedded {@link TomcatInfo} snapshot so it can
 * survive cross-machine transport. That snapshot can drift from the registered list
 * (IDs regenerate, paths change, XML gets hand-edited). This section goes through
 * {@link TomcatServerManagerState#resolve(TomcatInfo)} for every read so the combo,
 * validator, and downstream callers share a single interpretation:
 * <ul>
 *   <li>Resolver hits a registered instance → the UI selects that live instance and
 *       {@link #applyTo} writes it back, upgrading any drifted ID.</li>
 *   <li>Resolver misses → the persisted snapshot is injected into the combo as a
 *       <b>dangling</b> item (warning-styled, blocked by the validator). The UI never
 *       silently misrepresents saved state; the user sees what's there and can either
 *       re-register via Configure or pick a different server.</li>
 * </ul>
 */
public class ApplicationServerSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(ApplicationServerSection.class);

    private final Project project;
    private ComboBox<TomcatInfo> serverComboBox;
    private JPanel panel;

    /**
     * Identity-keyed set of dangling items currently shown in the combo. Identity
     * (not equals) because {@link TomcatInfo#equals} is ID-based and a dangling
     * snapshot may share an ID with a registered entry that merely hasn't reloaded yet —
     * we don't want a live item falsely flagged as dangling from a previous open.
     */
    private final Set<TomcatInfo> danglingItems =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public ApplicationServerSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            panel.setBorder(JBUI.Borders.empty(2, 0, 0, 0));

            GridBagConstraints gbc = new GridBagConstraints();
            serverComboBox = new ComboBox<>();
            serverComboBox.setRenderer(new TomcatInfoRenderer(danglingItems));
            ConfigurationSection.addLabelAndField(panel, gbc, 0,
                    new JBLabel("Application server:"), serverComboBox);

            ConfigurationSection.addConfigureButton(
                    panel, gbc, e -> openTomcatServerConfiguration());
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        serverComboBox.removeAllItems();
        danglingItems.clear();

        TomcatServerManagerState serverManager = TomcatServerManagerState.getInstance();
        List<TomcatInfo> tomcatServers = serverManager.getTomcatInfos();

        for (TomcatInfo tomcatInfo : tomcatServers) {
            serverComboBox.addItem(tomcatInfo);
        }

        LOG.debug("Loaded " + tomcatServers.size() + " Tomcat servers");
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        TomcatInfo persisted = configuration.getConfigData().getTomcatInfo();
        if (persisted == null) {
            serverComboBox.setSelectedItem(null);
            return;
        }

        TomcatInfo resolved = TomcatServerManagerState.getInstance().resolve(persisted);
        if (resolved != null) {
            // Select the canonical live instance. If the persisted reference had a
            // drifted ID, applyTo() will write back the canonical one next time the
            // user hits Apply — self-healing reconciliation.
            serverComboBox.setSelectedItem(resolved);
            return;
        }

        // Dangling: the persisted server is not in the registered list by any key.
        // Inject the snapshot so the UI reflects what's saved; the validator will
        // block Run/Apply until the user re-registers it or picks another server.
        injectAsDangling(persisted);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        TomcatInfo selected = getSelectedTomcatServer();
        if (selected == null) return;

        // Whether the selection is live or dangling, write it back verbatim. A live
        // selection with a drifted persisted ID gets automatically canonicalized here
        // (because resetFrom selected the registered instance). A dangling selection
        // is preserved so the user doesn't silently lose state — validateSettings()
        // will have already blocked Apply in that case, but defense in depth.
        configuration.setTomcatInfo(selected);
    }

    @Override
    public boolean isConfigurationValid() {
        TomcatInfo selected = getSelectedTomcatServer();
        return selected != null && !danglingItems.contains(selected);
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        TomcatInfo persisted = config.getConfigData().getTomcatInfo();
        TomcatInfo selected = getSelectedTomcatServer();

        // equals() is ID-based. If resolve() canonicalized a drifted ID, isModified
        // correctly returns true — Apply will persist the reconciled reference.
        return !Objects.equals(persisted, selected);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();

        TomcatInfo selected = getSelectedTomcatServer();
        if (selected == null) {
            errors.add(new ValidationInfo("No Tomcat server selected", serverComboBox));
            return errors;
        }

        if (danglingItems.contains(selected)) {
            String name = selected.getName();
            String path = selected.getPath();
            String displayName = !name.isEmpty() ? name : (!path.isEmpty() ? path : "(unnamed)");
            errors.add(new ValidationInfo(
                    "Tomcat server '" + displayName + "' is no longer registered."
                            + " Open Configure to add it, or select a different server.",
                    serverComboBox));
            return errors;
        }

        try {
            selected.validate();
        } catch (IllegalStateException e) {
            errors.add(new ValidationInfo(e.getMessage(), serverComboBox));
        }
        return errors;
    }

    public TomcatInfo getSelectedTomcatServer() {
        return (TomcatInfo) serverComboBox.getSelectedItem();
    }

    private void openTomcatServerConfiguration() {
        try {
            TomcatInfo previouslySelected = getSelectedTomcatServer();
            TomcatServerConfigurationDialog dialog = new TomcatServerConfigurationDialog(project);
            boolean accepted = dialog.showAndGet();
            if (accepted) {
                loadConfiguration();
                restoreSelection(previouslySelected);
                LOG.debug("Tomcat server configuration updated");
            }
        } catch (Exception e) {
            LOG.error("Error opening server configuration", e);
            Messages.showErrorDialog(project, "Failed to open server configuration: " + e.getMessage(), "Error");
        }
    }

    /**
     * Restore the previously-selected server after the Configure dialog rebuilds
     * the registered list. Goes through {@link TomcatServerManagerState#resolve}
     * so ID drift caused by remove-and-re-add inside the dialog is transparently
     * reconciled — the user never sees their selection go empty because an edit
     * regenerated a UUID.
     */
    private void restoreSelection(@Nullable TomcatInfo previous) {
        if (serverComboBox.getItemCount() == 0) return;
        if (previous == null) {
            serverComboBox.setSelectedIndex(0);
            return;
        }
        TomcatInfo resolved = TomcatServerManagerState.getInstance().resolve(previous);
        if (resolved != null) {
            serverComboBox.setSelectedItem(resolved);
            return;
        }
        // Previous server is no longer registered after the Configure edit.
        // Surface it as dangling rather than silently picking another.
        injectAsDangling(previous);
    }

    private void injectAsDangling(@NotNull TomcatInfo snapshot) {
        serverComboBox.addItem(snapshot);
        danglingItems.add(snapshot);
        serverComboBox.setSelectedItem(snapshot);
        LOG.warn("Persisted Tomcat server is not registered (id=" + snapshot.getId()
                + ", name=" + snapshot.getName() + ", path=" + snapshot.getPath()
                + "); shown as dangling");
    }

    /**
     * List cell renderer that styles dangling items distinctly (warning icon,
     * red foreground, parenthetical suffix, and a tooltip explaining the state).
     * Live items render unchanged.
     */
    private static class TomcatInfoRenderer extends SimpleListCellRenderer<TomcatInfo> {
        private final Set<TomcatInfo> dangling;

        TomcatInfoRenderer(@NotNull Set<TomcatInfo> dangling) {
            this.dangling = dangling;
        }

        @Override
        public void customize(@NotNull JList<? extends TomcatInfo> list, TomcatInfo value, int index,
                              boolean selected, boolean hasFocus) {
            if (value == null) {
                setText("");
                setIcon(null);
                setToolTipText(null);
                return;
            }
            String name = value.getName();
            String version = value.getVersion();
            String label;
            if (!version.isEmpty() && !name.contains(version)) {
                label = name + " " + version;
            } else {
                label = name;
            }
            boolean isDangling = dangling.contains(value);
            if (isDangling) {
                setText(label + "  (not registered)");
                setIcon(AllIcons.General.Warning);
                setForeground(JBColor.RED);
                setToolTipText("<html>This server is saved in the run configuration but is not"
                        + " registered in <b>Application Servers</b>.<br/>"
                        + "Path: " + value.getPath() + "<br/>"
                        + "Open <b>Configure…</b> to re-register it, or select a different server.</html>");
            } else {
                setText(label);
                setIcon(null);
                setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                setToolTipText("Tomcat " + version + " at " + value.getPath());
            }
        }
    }
}
