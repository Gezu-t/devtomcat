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
    private JPanel panel;

    public ApplicationServerSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1),
                    JBUI.Borders.empty(4, 4, 4, 4)
            ));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(4, 0, 4, 4);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Application server:"), gbc);

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(4, 8, 4, 8);
            serverComboBox = new ComboBox<>();
            serverComboBox.setRenderer(new TomcatInfoRenderer());
            serverComboBox.setPreferredSize(new Dimension(220, 25));
            panel.add(serverComboBox, gbc);

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(4, 0, 4, 0);
            JButton configureButton = new JButton("Configure...");
            configureButton.addActionListener(e -> openTomcatServerConfiguration());
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

        LOG.info("Loaded " + tomcatServers.size() + " Tomcat servers");
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
    public boolean isValid() {
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
            if (dialog.showAndGet()) {
                loadConfiguration();
                LOG.info("Tomcat server configuration updated");
            }
        } catch (Exception e) {
            LOG.error("Error opening server configuration", e);
            Messages.showErrorDialog(project, "Failed to open server configuration: " + e.getMessage(), "Error");
        }
    }

    private static class TomcatInfoRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof TomcatInfo) {
                TomcatInfo tomcatInfo = (TomcatInfo) value;
                setText(tomcatInfo.getName() + " (" + tomcatInfo.getVersion() + ")");
                setToolTipText("Tomcat " + tomcatInfo.getVersion() + " at " + tomcatInfo.getPath());
            }

            return this;
        }
    }
}
