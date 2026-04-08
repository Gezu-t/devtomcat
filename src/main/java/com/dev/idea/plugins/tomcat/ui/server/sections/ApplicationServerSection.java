package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.TomcatServerConfigurationDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Application Server Section
 * Handles Tomcat server selection and configuration
 */
public class ApplicationServerSection implements ConfigurationSection {

    private static final Logger LOG = Logger.getInstance(ApplicationServerSection.class);

    private final Project project;
    private ComboBox<TomcatInfo> serverComboBox;
    private JButton configureButton;
    private JPanel panel;

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
            serverComboBox.setRenderer(new TomcatInfoRenderer());
            ConfigurationSection.addLabelAndField(panel, gbc, 0,
                    new JBLabel("Application server:"), serverComboBox);

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(2, 0, 2, 0);
            configureButton = new JButton("Configure...");
            configureButton.addActionListener(e -> openTomcatServerConfiguration());
            Dimension comboSize = serverComboBox.getPreferredSize();
            Dimension buttonSize = configureButton.getPreferredSize();
            configureButton.setPreferredSize(new Dimension(
                    Math.max(buttonSize.width, JBUI.scale(96)),
                    comboSize.height
            ));
            panel.add(configureButton, gbc);
        }
        return panel;
    }

    @Override
    public void loadConfiguration() {
        serverComboBox.removeAllItems();

        TomcatServerManagerState serverManager = TomcatServerManagerState.getInstance();
        List<TomcatInfo> tomcatServers = serverManager.getTomcatInfos();

        for (TomcatInfo tomcatInfo : tomcatServers) {
            serverComboBox.addItem(tomcatInfo);
        }

        LOG.debug("Loaded " + tomcatServers.size() + " Tomcat servers");
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        TomcatInfo configTomcatInfo = configuration.getConfigData().getTomcatInfo();
        if (configTomcatInfo != null) {
            serverComboBox.setSelectedItem(configTomcatInfo);
        }
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        TomcatInfo selectedTomcat = getSelectedTomcatServer();
        if (selectedTomcat != null) {
            configuration.setTomcatInfo(selectedTomcat);
        }
    }

    @Override
    public boolean isConfigurationValid() {
        return getSelectedTomcatServer() != null;
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        TomcatInfo configTomcat = config.getConfigData().getTomcatInfo();
        TomcatInfo selectedTomcat = getSelectedTomcatServer();

        // Check if selection changed
        return !Objects.equals(configTomcat, selectedTomcat);
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();

        TomcatInfo selectedTomcat = getSelectedTomcatServer();
        if (selectedTomcat == null) {
            errors.add(new ValidationInfo("Please select a Tomcat server", serverComboBox));
        } else {
            // Validate that the selected server path exists
            try {
                selectedTomcat.validate();
            } catch (IllegalStateException e) {
                errors.add(new ValidationInfo(e.getMessage(), serverComboBox));
            }
        }

        return errors;
    }

    public TomcatInfo getSelectedTomcatServer() {
        return (TomcatInfo) serverComboBox.getSelectedItem();
    }

    private void openTomcatServerConfiguration() {
        try {
            TomcatServerConfigurationDialog dialog = new TomcatServerConfigurationDialog(project);
            boolean accepted = dialog.showAndGet();
            if (accepted) {
                loadConfiguration();
                LOG.debug("Tomcat server configuration updated");
            }
        } catch (Exception e) {
            LOG.error("Error opening server configuration", e);
            Messages.showErrorDialog(project, "Failed to open server configuration: " + e.getMessage(), "Error");
        }
    }

    private static class TomcatInfoRenderer extends com.intellij.ui.SimpleListCellRenderer<TomcatInfo> {
        @Override
        public void customize(@NotNull JList<? extends TomcatInfo> list, TomcatInfo value, int index,
                              boolean selected, boolean hasFocus) {
            if (value != null) {
                String name = value.getName();
                String version = value.getVersion();
                if (!version.isEmpty() && !name.contains(version)) {
                    setText(name + " " + version);
                } else {
                    setText(name);
                }
                setToolTipText("Tomcat " + version + " at " + value.getPath());
            }
        }
    }
}
