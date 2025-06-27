package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.setting.TomcatServerManagerState;
import com.dev.idea.plugins.tomcat.ui.server.dialogs.TomcatServerConfigurationDialog;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Application Server Section - Single Responsibility
 * Handles ONLY Tomcat server selection and configuration
 */
public class ApplicationServerSection implements ConfigurationSection {

    private final Project project;
    private JComboBox<TomcatInfo> serverComboBox;
    private JButton configureButton;
    private JPanel panel;

    public ApplicationServerSection(Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createTitledBorder("Application Server"));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(8, 8, 8, 8);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Application server:"), gbc);

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(8, 15, 8, 10);
            serverComboBox = new JComboBox<>();
            serverComboBox.setRenderer(new TomcatInfoRenderer());
            serverComboBox.setPreferredSize(new Dimension(250, 25));
            panel.add(serverComboBox, gbc);

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.insets = JBUI.insets(8, 0, 8, 8);
            configureButton = new JButton("Configure...");
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

        System.out.println("DevTomcat: Loaded " + tomcatServers.size() + " Tomcat servers");
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        TomcatInfo configTomcatInfo = configuration.getTomcatInfo();
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

    public TomcatInfo getSelectedTomcatServer() {
        return (TomcatInfo) serverComboBox.getSelectedItem();
    }

    private void openTomcatServerConfiguration() {
        try {
            TomcatServerConfigurationDialog dialog = new TomcatServerConfigurationDialog(project);
            if (dialog.showAndGet()) {
                loadConfiguration();
                System.out.println("DevTomcat: Tomcat server configuration updated");
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error opening server configuration: " + e.getMessage());
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