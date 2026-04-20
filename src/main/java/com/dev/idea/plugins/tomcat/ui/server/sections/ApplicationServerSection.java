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
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Application Server section.
 *
 * <p>The run configuration persists an embedded {@link TomcatInfo} snapshot. Every
 * read goes through {@link TomcatServerManagerState#resolve(TomcatInfo)} so the
 * combo, validator, and runtime share one interpretation. Registration is
 * <b>required to launch</b> — an embedded snapshot whose path happens to exist is
 * not enough. The UI keeps two visual states for unresolved references so the user
 * can tell the situations apart, but both block Run:
 * <ul>
 *   <li><b>Registered</b> — resolver hits a registered instance. The UI selects it
 *       and {@link #applyTo} writes it back, upgrading any drifted ID.</li>
 *   <li><b>Unregistered</b> (usable path) — snapshot not in the registered list,
 *       but its path exists. Warning icon + "(not registered)" suffix + tooltip
 *       pointing to Configure. Blocks the validator with a "not registered"
 *       error so the toolbar Run can't silently launch.</li>
 *   <li><b>Broken</b> — resolver misses <i>and</i> the path is empty or missing.
 *       Red decoration + hard validator error about the path.</li>
 * </ul>
 */
public class ApplicationServerSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(ApplicationServerSection.class);

    private final Project project;
    private ComboBox<TomcatInfo> serverComboBox;
    private JPanel panel;

    /**
     * Identity-keyed sets of injected snapshots currently shown in the combo.
     * Identity (not equals) because {@link TomcatInfo#equals} is ID-based — a
     * snapshot may share an ID with a registered entry from an older reload
     * and we don't want a live item falsely decorated. Membership is mutually
     * exclusive: an item is either usable-but-unregistered, or broken.
     */
    private final Set<TomcatInfo> unregisteredButUsable =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TomcatInfo> brokenItems =
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
            serverComboBox.setRenderer(new TomcatInfoRenderer(unregisteredButUsable, brokenItems));
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
        unregisteredButUsable.clear();
        brokenItems.clear();

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

        TomcatInfo resolved = TomcatServerManagerState.getInstance().resolveOrAutoRegister(persisted);
        if (resolved != null) {
            // Select the canonical live instance. If the persisted reference had a
            // drifted ID, applyTo() will write back the canonical one next time the
            // user hits Apply — self-healing reconciliation.
            //
            // resolveOrAutoRegister may have just added a brand-new entry for a
            // persisted-but-unregistered Tomcat; our combo was populated from
            // loadConfiguration() before that, so top it up now.
            boolean present = false;
            for (int i = 0; i < serverComboBox.getItemCount(); i++) {
                if (Objects.equals(serverComboBox.getItemAt(i), resolved)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                serverComboBox.addItem(resolved);
            }
            serverComboBox.setSelectedItem(resolved);
            return;
        }

        // Unresolved. Classify by whether the runtime could actually launch it:
        // the runtime accepts any snapshot whose path exists on disk (portable VCS
        // imports), so a usable path means "warning" not "error". Only an empty or
        // missing path is a hard block.
        injectUnresolved(persisted);
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        TomcatInfo selected = getSelectedTomcatServer();
        if (selected == null) return;

        // Whether the selection is live, usable-but-unregistered, or broken, write
        // it back verbatim. A live selection with a drifted persisted ID gets
        // canonicalized here (resetFrom already selected the registered instance).
        // A broken selection is preserved so the user doesn't silently lose state —
        // validateSettings() blocks Apply for broken, not for usable.
        configuration.setTomcatInfo(selected);
    }

    @Override
    public boolean isConfigurationValid() {
        TomcatInfo selected = getSelectedTomcatServer();
        if (selected == null) return false;
        // Registration required: unregistered-but-usable now blocks too, matching
        // the runtime and TomcatConfigurationValidator. The renderer still
        // distinguishes the two cases visually.
        return !brokenItems.contains(selected) && !unregisteredButUsable.contains(selected);
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

        if (brokenItems.contains(selected)) {
            String name = selected.getName();
            String path = selected.getPath();
            String displayName = !name.isEmpty() ? name : (!path.isEmpty() ? path : "(unnamed)");
            String pathClause = path.isEmpty() ? "no path is set" : "path '" + path + "' does not exist";
            errors.add(new ValidationInfo(
                    "Tomcat server '" + displayName + "' cannot be launched — " + pathClause + "."
                            + " Open Configure to register a server, or select a different one.",
                    serverComboBox));
            return errors;
        }

        if (unregisteredButUsable.contains(selected)) {
            String name = selected.getName();
            String path = selected.getPath();
            String displayName = !name.isEmpty() ? name : (!path.isEmpty() ? path : "(unnamed)");
            errors.add(new ValidationInfo(
                    "Tomcat server '" + displayName + "' is not registered."
                            + " Open Configure to add it to Application Servers,"
                            + " or select a different server.",
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
        injectUnresolved(previous);
    }

    /**
     * Inject a snapshot the resolver couldn't match. Both branches block Run;
     * the path-based split is purely for user messaging so they can tell
     * "needs registration" (path exists, just add it to Application Servers)
     * from "broken configuration" (path is missing or empty too).
     */
    private void injectUnresolved(@NotNull TomcatInfo snapshot) {
        serverComboBox.addItem(snapshot);
        String path = snapshot.getPath();
        boolean pathExists = !path.isEmpty() && new File(path).isDirectory();
        if (pathExists) {
            unregisteredButUsable.add(snapshot);
            LOG.warn("Persisted Tomcat server is not registered"
                    + " (id=" + snapshot.getId() + ", name=" + snapshot.getName()
                    + ", path=" + path + "); blocking Run");
        } else {
            brokenItems.add(snapshot);
            LOG.warn("Persisted Tomcat server is not registered and path is missing"
                    + " (id=" + snapshot.getId() + ", name=" + snapshot.getName()
                    + ", path=" + path + "); blocking Run");
        }
        serverComboBox.setSelectedItem(snapshot);
    }

    /**
     * List cell renderer that styles unresolved items distinctly. Both
     * variants block Run — the visual split exists so users can see at a
     * glance whether the fix is "just register this server" or "fix the path
     * too."
     * <ul>
     *   <li>Unregistered (path exists) → warning icon + "(not registered)"
     *       suffix + tooltip pointing to Configure. Default text color so it
     *       doesn't scream "broken" — the user just needs to register.</li>
     *   <li>Broken → red text + warning icon + "(not launchable)" suffix +
     *       tooltip explaining the path problem.</li>
     *   <li>Registered → unchanged default rendering.</li>
     * </ul>
     */
    private static class TomcatInfoRenderer extends SimpleListCellRenderer<TomcatInfo> {
        private final Set<TomcatInfo> usable;
        private final Set<TomcatInfo> broken;

        TomcatInfoRenderer(@NotNull Set<TomcatInfo> usable, @NotNull Set<TomcatInfo> broken) {
            this.usable = usable;
            this.broken = broken;
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

            if (broken.contains(value)) {
                setText(label + "  (not launchable)");
                setIcon(AllIcons.General.Warning);
                setForeground(JBColor.RED);
                setToolTipText("<html>This server is saved in the run configuration but cannot be"
                        + " launched — the path is empty or does not exist on disk.<br/>"
                        + "Path: " + (value.getPath().isEmpty() ? "(empty)" : value.getPath()) + "<br/>"
                        + "Open <b>Configure…</b> to register a server, or select a different one.</html>");
            } else if (usable.contains(value)) {
                setText(label + "  (not registered)");
                setIcon(AllIcons.General.Warning);
                setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                setToolTipText("<html>This server is saved in the run configuration but is not"
                        + " registered in <b>Application Servers</b>. Run is blocked until you"
                        + " register it — the path exists on disk, so adding it via <b>Configure…</b>"
                        + " is enough.<br/>"
                        + "Path: " + value.getPath() + "</html>");
            } else {
                setText(label);
                setIcon(null);
                setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                setToolTipText("Tomcat " + version + " at " + value.getPath());
            }
        }
    }
}
