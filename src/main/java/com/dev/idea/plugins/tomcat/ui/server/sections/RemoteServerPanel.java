package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Remote Server Configuration Panel
 *
 * <p>Provides UI for configuring remote Tomcat server connection settings.
 * Used in the Dev Tomcat plugin to support remote deployment and debugging
 * in IntelliJ IDEA Community Edition.
 *
 * <p>Features:
 * <ul>
 *   <li>Host field with validation feedback</li>
 *   <li>Port spinner with range 1–65535</li>
 *   <li>Null-safe reset/apply/isModified cycle</li>
 *   <li>GridBagLayout for responsive design</li>
 *   <li>Professional logging and error handling</li>
 * </ul>
 *
 * @author Dev Tomcat Team
 * @see TomcatRunConfiguration
 */
public class RemoteServerPanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(RemoteServerPanel.class);

    private final JBTextField hostField = new JBTextField(20);
    private final JBIntSpinner portField = new JBIntSpinner(8080, 1, 65535);

    /**
     * Creates a new remote server panel and initializes it with configuration data.
     *
     * @param config The run configuration to bind to
     */
    public RemoteServerPanel(@NotNull TomcatRunConfiguration config) {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = createGridBagConstraints();
        int row = 0;

        addLabeledField(gbc, row++, "Host:", hostField);
        addLabeledField(gbc, row++, "Port:", portField);

        // Initialize UI from config
        resetFrom(config);

        LOG.debug("RemoteServerPanel initialized for config: {}", config.getName());
    }

    /**
     * Creates reusable GridBagConstraints with standard styling.
     */
    @NotNull
    private GridBagConstraints createGridBagConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    /**
     * Adds a labeled field to the panel using GridBagLayout.
     */
    private void addLabeledField(GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        add(field, gbc);
    }

    /**
     * Resets the UI fields from the given configuration.
     *
     * @param config The configuration to load from
     */
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        try {
            String host = config.getRemoteHostSafe();
            Integer port = config.getRemotePortSafe();

            hostField.setText(host);
            portField.setValue(port != null ? port : 8080);

            LOG.debug("RemoteServerPanel reset: host='{}', port={}", host, port);
        } catch (Exception e) {
            LOG.warn("Failed to reset RemoteServerPanel from config: {}", config.getName(), e);
        }
    }

    /**
     * Applies current UI values to the configuration.
     *
     * @param config The configuration to update
     */
    public void applyTo(@NotNull TomcatRunConfiguration config) {
        try {
            String host = hostField.getText().trim();
            int port = portField.getValue();

            config.setRemoteHost(host.isEmpty() ? null : host);
            config.setRemotePort(port);

            LOG.debug("RemoteServerPanel applied: host='{}', port={}", host, port);
        } catch (Exception e) {
            LOG.error("Failed to apply RemoteServerPanel to config: {}", config.getName(), e);
        }
    }

    /**
     * Checks if the UI has been modified compared to the configuration.
     *
     * @param config The current configuration
     * @return true if modified
     */
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        try {
            String currentHost = hostField.getText().trim();
            int currentPort = portField.getValue();

            String configHost = config.getRemoteHostSafe();
            Integer configPort = config.getRemotePortSafe();

            boolean hostChanged = !currentHost.equals(configHost);
            boolean portChanged = configPort == null ? currentPort != 8080 : currentPort != configPort;

            if (hostChanged || portChanged) {
                LOG.debug("RemoteServerPanel modified: host={}->{} | port={}->{}",
                        configHost, currentHost, configPort, currentPort);
            }

            return hostChanged || portChanged;
        } catch (Exception e) {
            LOG.warn("Error checking modification state in RemoteServerPanel", e);
            return false;
        }
    }

    /**
     * Validates the current input values.
     *
     * @return null if valid, or error message if invalid
     */
    @NotNull
    public String validateInput() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            return "Remote host cannot be empty";
        }
        if (!host.matches("^([a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\])$")) {
            return "Invalid host format. Use hostname, IP, or IPv6 in brackets.";
        }
        int port = portField.getValue();
        if (port < 1 || port > 65535) {
            return "Port must be between 1 and 65535";
        }
        return "";
    }

    /**
     * Focuses the host field (for accessibility and UX).
     */
    public void requestFocusOnHost() {
        hostField.requestFocusInWindow();
        hostField.selectAll();
    }

    // === Getters for testing / advanced use ===

    @NotNull
    public JBTextField getHostField() {
        return hostField;
    }

    @NotNull
    public JBIntSpinner getPortField() {
        return portField;
    }
}