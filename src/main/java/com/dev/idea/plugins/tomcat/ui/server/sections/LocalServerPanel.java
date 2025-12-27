package com.dev.idea.plugins.tomcat.ui.server.sections;

                    import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
                    import com.dev.idea.plugins.tomcat.model.PortConfig;
                    import com.dev.idea.plugins.tomcat.model.ValidationResult;
                    import com.intellij.openapi.ui.Messages;
                    import com.intellij.openapi.ui.ValidationInfo;
                    import com.intellij.ui.JBIntSpinner;
                    import com.intellij.ui.components.JBLabel;
                    import com.intellij.ui.components.JBTextField;
                    import com.intellij.util.ui.JBUI;
                    import org.jetbrains.annotations.NotNull;

                    import javax.swing.*;
                    import java.awt.*;
                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.Map;

                    public class LocalServerPanel extends JPanel implements ConfigurationSection {

                        private final JBTextField tomcatHomeField = new JBTextField(40);
                        private final JBIntSpinner httpPortField = new JBIntSpinner(PortConfig.DEFAULT_HTTP_PORT, PortConfig.MIN_PORT, PortConfig.MAX_PORT);
                        private final JBIntSpinner shutdownPortField = new JBIntSpinner(PortConfig.DEFAULT_SHUTDOWN_PORT, PortConfig.MIN_PORT, PortConfig.MAX_PORT);
                        private final JCheckBox httpsEnabled = new JCheckBox("Enable HTTPS");
                        private final JBIntSpinner httpsPortField = new JBIntSpinner(PortConfig.DEFAULT_HTTPS_PORT, PortConfig.MIN_PORT, PortConfig.MAX_PORT);
                        private final JCheckBox jmxEnabled = new JCheckBox("Enable JMX");
                        private final JBIntSpinner jmxPortField = new JBIntSpinner(PortConfig.DEFAULT_JMX_PORT, PortConfig.MIN_PORT, PortConfig.MAX_PORT);

                        private final JButton autoFixButton = new JButton("Auto-Fix Ports");
                        private final JButton suggestButton = new JButton("Suggest Ports");
                        private final JBLabel warningLabel = new JBLabel("");

                        private TomcatRunConfiguration config;

                        public LocalServerPanel() {
                            setLayout(new GridBagLayout());
                            initComponents();
                            setupListeners();
                        }

                        private void initComponents() {
                            GridBagConstraints gbc = new GridBagConstraints();
                            gbc.insets = JBUI.insets(5);
                            gbc.anchor = GridBagConstraints.WEST;
                            gbc.fill = GridBagConstraints.HORIZONTAL;

                            int y = 0;

                            addSectionLabel(gbc, y++, "Local Server");

                            addRow(gbc, y++, "Tomcat Home:", tomcatHomeField, true);

                            JPanel portRow = new JPanel(new GridLayout(1, 4, 10, 0));
                            portRow.add(new JBLabel("HTTP Port:"));
                            portRow.add(httpPortField);
                            portRow.add(new JBLabel("Shutdown Port:"));
                            portRow.add(shutdownPortField);
                            gbc.gridwidth = 3;
                            add(portRow, gbc);
                            y++;

                            gbc.gridwidth = 1;
                            gbc.gridx = 0; gbc.gridy = y;
                            add(httpsEnabled, gbc);
                            gbc.gridx = 1; gbc.gridwidth = 2;
                            add(httpsPortField, gbc);
                            y++;

                            gbc.gridx = 0; gbc.gridy = y;
                            add(jmxEnabled, gbc);
                            gbc.gridx = 1; gbc.gridwidth = 2;
                            add(jmxPortField, gbc);
                            y++;

                            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                            buttonPanel.add(autoFixButton);
                            buttonPanel.add(suggestButton);
                            gbc.gridx = 0; gbc.gridy = y++; gbc.gridwidth = 3;
                            add(buttonPanel, gbc);

                            gbc.gridx = 0; gbc.gridy = y++; gbc.gridwidth = 3;
                            warningLabel.setBorder(JBUI.Borders.empty(5, 0));
                            add(warningLabel, gbc);
                        }

                        private void addSectionLabel(GridBagConstraints gbc, int y, String text) {
                            gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 3;
                            JBLabel label = new JBLabel(text);
                            label.setFont(label.getFont().deriveFont(Font.BOLD));
                            add(label, gbc);
                        }

                        private void addRow(GridBagConstraints gbc, int y, String label, JComponent field, boolean fullWidth) {
                            gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 1;
                            add(new JBLabel(label), gbc);
                            gbc.gridx = 1; gbc.gridwidth = fullWidth ? 2 : 1;
                            add(field, gbc);
                        }

                        private void setupListeners() {
                            httpsEnabled.addActionListener(e -> httpsPortField.setEnabled(httpsEnabled.isSelected()));
                            jmxEnabled.addActionListener(e -> jmxPortField.setEnabled(jmxEnabled.isSelected()));

                            autoFixButton.addActionListener(e -> autoFixPorts());
                            suggestButton.addActionListener(e -> showPortSuggestions());

                            Runnable updateValidation = () -> {
                                if (config != null) updateValidation(config);
                            };
                            httpPortField.addChangeListener(e -> updateValidation.run());
                            shutdownPortField.addChangeListener(e -> updateValidation.run());
                            httpsPortField.addChangeListener(e -> updateValidation.run());
                            jmxPortField.addChangeListener(e -> updateValidation.run());
                            httpsEnabled.addActionListener(e -> updateValidation.run());
                            jmxEnabled.addActionListener(e -> updateValidation.run());
                        }

                        @Override
                        public void loadConfiguration() {
                            // Initialize any dropdowns or load default port configurations if needed
                        }

                        @Override
                        public void resetFrom(@NotNull TomcatRunConfiguration config) {
                            this.config = config;
                            PortConfig pc = config.getConfigData().getPortConfig();

                            tomcatHomeField.setText(config.getConfigData().getTomcatInfo() != null
                                    ? config.getConfigData().getTomcatInfo().getPath() : "");

                            httpPortField.setValue(pc.getHttp());
                            shutdownPortField.setValue(pc.getShutdown());
                            httpsEnabled.setSelected(pc.isHttpsEnabled());
                            httpsPortField.setValue(pc.getHttps());
                            jmxEnabled.setSelected(pc.isJmxEnabled());
                            jmxPortField.setValue(pc.getJmx());

                            updateEnabledState();
                            updateValidation(config);
                        }

                        @Override
                        public void applyTo(@NotNull TomcatRunConfiguration config) {
                            PortConfig pc = config.getConfigData().getPortConfig();
                            pc.setHttp((int) httpPortField.getValue());
                            pc.setShutdown((int) shutdownPortField.getValue());
                            pc.setHttpsEnabled(httpsEnabled.isSelected());
                            pc.setHttps(httpsEnabled.isSelected() ? (int) httpsPortField.getValue() : PortConfig.DEFAULT_HTTPS_PORT);
                            pc.setJmxEnabled(jmxEnabled.isSelected());
                            pc.setJmx(jmxEnabled.isSelected() ? (int) jmxPortField.getValue() : PortConfig.DEFAULT_JMX_PORT);
                        }

                        @Override
                        public boolean isModified(@NotNull TomcatRunConfiguration config) {
                            PortConfig pc = config.getConfigData().getPortConfig();
                            return (int) httpPortField.getValue() != pc.getHttp() ||
                                    (int) shutdownPortField.getValue() != pc.getShutdown() ||
                                    httpsEnabled.isSelected() != pc.isHttpsEnabled() ||
                                    (httpsEnabled.isSelected() && (int) httpsPortField.getValue() != pc.getHttps()) ||
                                    jmxEnabled.isSelected() != pc.isJmxEnabled() ||
                                    (jmxEnabled.isSelected() && (int) jmxPortField.getValue() != pc.getJmx());
                        }

                        @Override
                        public List<ValidationInfo> validateSettings() {
                            List<ValidationInfo> errors = new ArrayList<>();
                            if (tomcatHomeField.getText().trim().isEmpty()) {
                                errors.add(new ValidationInfo("Tomcat home is required", tomcatHomeField));
                            }
                            return errors;
                        }

                        private void updateEnabledState() {
                            httpsPortField.setEnabled(httpsEnabled.isSelected());
                            jmxPortField.setEnabled(jmxEnabled.isSelected());
                        }

                        private void updateValidation(TomcatRunConfiguration config) {
                            PortConfig pc = config.getConfigData().getPortConfig();
                            PortConfig temp = new PortConfig(
                                    (int) httpPortField.getValue(),
                                    (int) httpsPortField.getValue(),
                                    (int) jmxPortField.getValue(),
                                    (int) shutdownPortField.getValue()
                            );
                            temp.setHttpsEnabled(httpsEnabled.isSelected());
                            temp.setJmxEnabled(jmxEnabled.isSelected());

                            ValidationResult result = temp.validate();
                            config.setPortValidationWarnings(result.getWarnings());

                            if (result.hasWarnings()) {
                                warningLabel.setText("<html><font color='#FF8C00'>" + String.join("<br>", result.getWarnings()) + "</font></html>");
                            } else {
                                warningLabel.setText("");
                            }
                        }

                        private void autoFixPorts() {
                            // Check for port conflicts and auto-fix by finding next available ports
                            PortConfig current = new PortConfig(
                                    (int) httpPortField.getValue(),
                                    (int) httpsPortField.getValue(),
                                    (int) jmxPortField.getValue(),
                                    (int) shutdownPortField.getValue()
                            );
                            current.setHttpsEnabled(httpsEnabled.isSelected());
                            current.setJmxEnabled(jmxEnabled.isSelected());

                            ValidationResult result = current.validate();
                            if (result.hasWarnings()) {
                                // Auto-fix by finding next available ports
                                int http = PortConfig.findNextAvailablePort((int) httpPortField.getValue());
                                int shutdown = PortConfig.findNextAvailablePort((int) shutdownPortField.getValue());
                                int https = httpsEnabled.isSelected() ? PortConfig.findNextAvailablePort((int) httpsPortField.getValue()) : PortConfig.DEFAULT_HTTPS_PORT;
                                int jmx = jmxEnabled.isSelected() ? PortConfig.findNextAvailablePort((int) jmxPortField.getValue()) : PortConfig.DEFAULT_JMX_PORT;

                                httpPortField.setValue(http);
                                shutdownPortField.setValue(shutdown);
                                if (httpsEnabled.isSelected()) httpsPortField.setValue(https);
                                if (jmxEnabled.isSelected()) jmxPortField.setValue(jmx);

                                Messages.showInfoMessage("Ports auto-fixed successfully!", "Auto-Fix");
                            } else {
                                Messages.showInfoMessage("No conflicts detected.", "Auto-Fix");
                            }
                        }

                        private void showPortSuggestions() {
                            JPopupMenu menu = new JPopupMenu();

                            // Create suggestions for common ports
                            Map<String, List<Integer>> suggestions = Map.of(
                                "HTTP", List.of(8080, 8081, 8082, 9090, 9091),
                                "HTTPS", List.of(8443, 8444, 9443, 9444),
                                "JMX", List.of(1099, 1098, 1097, 9999, 9998),
                                "Shutdown", List.of(8005, 8006, 8007, 8008, 8009)
                            );

                            suggestions.forEach((name, ports) -> {
                                JMenu serviceMenu = new JMenu(name);
                                for (int port : ports) {
                                    JMenuItem item = new JMenuItem(String.valueOf(port));
                                    item.addActionListener(e -> {
                                        switch (name) {
                                            case "HTTP" -> httpPortField.setValue(port);
                                            case "HTTPS" -> httpsPortField.setValue(port);
                                            case "JMX" -> jmxPortField.setValue(port);
                                            case "Shutdown" -> shutdownPortField.setValue(port);
                                        }
                                    });
                                    serviceMenu.add(item);
                                }
                                menu.add(serviceMenu);
                            });

                            menu.show(suggestButton, 0, suggestButton.getHeight());
                        }

                        @Override
                        public JPanel createPanel() {
                            return this;
                        }

                        @Override
                        public boolean shouldFillVertically() {
                            return false;
                        }

                        public void dispose() {
                            // Clean up listeners and resources
                            autoFixButton.removeActionListener(autoFixButton.getActionListeners()[0]);
                            suggestButton.removeActionListener(suggestButton.getActionListeners()[0]);
                        }
                    }
