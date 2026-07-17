package com.dev.idea.plugins.tomcat.ui.server.sections;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.environment.DynamicTomcatEnvironment;
import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.dev.idea.plugins.tomcat.utils.PortUtils;
import com.dev.idea.plugins.tomcat.utils.PortValidator;
import com.dev.idea.plugins.tomcat.utils.SafeBrowseUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class TomcatSettingsSection implements ConfigurationSection {
    private static final Logger LOG = Logger.getInstance(TomcatSettingsSection.class);
    private final @Nullable Project project;
    private JBTextField httpPortField;
    private JBTextField httpsPortField;
    private JBTextField jmxPortField;
    private JBTextField ajpPortField;
    private JBTextField shutdownPortField;
    private TextFieldWithBrowseButton catalinaBaseField;
    private JBCheckBox deployAppsCheckBox;
    private JBCheckBox preserveSessionsCheckBox;
    private JBCheckBox allowMultipleInstancesCheckBox;
    private com.intellij.openapi.ui.ComboBox<com.dev.idea.plugins.tomcat.model.PortStrategy> portStrategyCombo;
    private JPanel panel;

    private Consumer<String> portChangeListener;
    /** Guard to distinguish programmatic setText() from user typing. */
    private boolean isSettingPort = false;
    private DocumentListener httpPortDocListener;

    public TomcatSettingsSection() {
        this(null);
    }

    public TomcatSettingsSection(@Nullable Project project) {
        this.project = project;
    }

    @Override
    @NotNull
    public JPanel createPanel() {
        if (panel == null) {
            panel = new JPanel(new VerticalLayout(0));
            panel.setBorder(JBUI.Borders.empty(0));

            panel.add(new TitledSeparator("Tomcat server settings"));

            JPanel formPanel = new JPanel(ConfigurationSection.createAlignedGridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            int row = 0;

            // Row 0: Port strategy (1.1.0).
            portStrategyCombo = new com.intellij.openapi.ui.ComboBox<>(
                    com.dev.idea.plugins.tomcat.model.PortStrategy.values());
            portStrategyCombo.setRenderer(new com.intellij.ui.SimpleListCellRenderer<com.dev.idea.plugins.tomcat.model.PortStrategy>() {
                @Override
                public void customize(@NotNull JList<? extends com.dev.idea.plugins.tomcat.model.PortStrategy> list,
                                      com.dev.idea.plugins.tomcat.model.PortStrategy s, int index,
                                      boolean selected, boolean hasFocus) {
                    if (s == null) { setText(""); return; }
                    setText(switch (s) {
                        case AUTO_BUMP          -> "Auto-bump (find next free port if busy)";
                        case RECLAIM_THEN_FAIL  -> "Reclaim then fail (kill own orphans, else fail)";
                        case STRICT             -> "Strict (fail if preferred port busy)";
                    });
                }
            });
            portStrategyCombo.setToolTipText("<html>How DevTomcat handles a busy preferred port at launch.<br>"
                    + "<b>Auto-bump</b>: pick the next available port (existing behavior).<br>"
                    + "<b>Reclaim then fail</b>: kill our own orphan Tomcats, fail if port is held by something else.<br>"
                    + "<b>Strict</b>: refuse to launch if the preferred port is busy.</html>");
            addPortRow(formPanel, gbc, row, new JBLabel("Port strategy:"), portStrategyCombo);

            // Row 1: HTTP port + Deploy checkbox
            row++;
            httpPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpPort()), 8);
            httpPortDocListener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { onPortChange(); }
                @Override public void removeUpdate(DocumentEvent e) { onPortChange(); }
                @Override public void changedUpdate(DocumentEvent e) { onPortChange(); }
                private void onPortChange() {
                    if (!isSettingPort && portChangeListener != null) {
                        portChangeListener.accept(httpPortField.getText().trim());
                    }
                }
            };
            httpPortField.getDocument().addDocumentListener(httpPortDocListener);
            addPortRow(formPanel, gbc, row, new JBLabel("HTTP port:"), httpPortField);
            deployAppsCheckBox = new JBCheckBox("Deploy applications configured in Tomcat instance");
            deployAppsCheckBox.setToolTipText("<html>When enabled, Tomcat's bundled web apps from the install's " +
                    "<code>webapps/</code> and <code>conf/Catalina/localhost/</code> directories " +
                    "(e.g. <i>manager</i>, <i>host-manager</i>, <i>ROOT</i>) deploy alongside the " +
                    "artifacts configured in this run configuration.<br>" +
                    "WAR files are hardlinked when the filesystem supports it; exploded web apps " +
                    "run in place via a synthesized context descriptor (no file duplication). " +
                    "IDE-managed artifacts always win on context-path collisions.</html>");
            deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
            addCheckBoxColumn(formPanel, gbc, deployAppsCheckBox);

            // Row 2: HTTPS port + Preserve sessions checkbox
            row++;
            httpsPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()), 8);
            addPortRow(formPanel, gbc, row, new JBLabel("HTTPS port:"), httpsPortField);
            preserveSessionsCheckBox = new JBCheckBox("Preserve sessions across restarts and redeploys");
            preserveSessionsCheckBox.setToolTipText("<html>When enabled, Tomcat serializes active HTTP sessions to " +
                    "<code>work/</code> on shutdown and reloads them on next start, so users stay logged in across an " +
                    "IDE-triggered restart or redeploy.<br>" +
                    "Session attributes must implement <code>Serializable</code>. Leave disabled when redeploying " +
                    "with schema or class changes that would break deserialization.</html>");
            preserveSessionsCheckBox.setSelected(false);
            addCheckBoxColumn(formPanel, gbc, preserveSessionsCheckBox);

            // Row 3: JMX port + Allow parallel run checkbox
            row++;
            jmxPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getJmxPort()), 8);
            addPortRow(formPanel, gbc, row, new JBLabel("JMX port:"), jmxPortField);
            allowMultipleInstancesCheckBox = new JBCheckBox("Allow parallel run");
            allowMultipleInstancesCheckBox.setToolTipText("Allow multiple instances of this configuration to run simultaneously");
            addCheckBoxColumn(formPanel, gbc, allowMultipleInstancesCheckBox);

            // Row 4: AJP port
            row++;
            ajpPortField = new JBTextField("", 8);
            addPortRow(formPanel, gbc, row, new JBLabel("AJP port:"), ajpPortField);

            // Row 5: Shutdown port
            row++;
            shutdownPortField = new JBTextField(String.valueOf(DynamicTomcatEnvironment.getShutdownPort()), 8);
            addPortRow(formPanel, gbc, row, new JBLabel("Shutdown port:"), shutdownPortField);

            // Row 6: CATALINA_BASE (spans both field columns for the browse button)
            // Note: Debug port/transport are configured in the Startup/Connection tab (Debug mode)
            // — server-wide settings live here, mode-specific settings live there. Values
            // persist to DebugConfig.
            row++;
            catalinaBaseField = new TextFieldWithBrowseButton();
            SafeBrowseUtil.addBrowseFolderListener(
                    catalinaBaseField, "Select CATALINA_BASE Directory", "Choose the base directory for this Tomcat instance",
                    project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
            catalinaBaseField.getTextField().setToolTipText("Leave empty to use default (auto-generated per configuration)");
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = JBUI.insets(2, 0, 2, 4);
            formPanel.add(new JBLabel("CATALINA_BASE:"), gbc);
            gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            formPanel.add(catalinaBaseField, gbc);
            gbc.gridwidth = 1;

            panel.add(formPanel);
        }
        return panel;
    }

    /**
     * Adds a label at column 0 and a fixed-width port field at column 1.
     * Column 2 (checkbox) is left for the caller to add when needed.
     */
    private static void addPortRow(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                   int row, @NotNull JComponent label, @NotNull JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2, 0, 2, 4);
        panel.add(label, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE;
        panel.add(field, gbc);
    }

    /** Adds a checkbox at column 2 (the wide trailing column for the current row). */
    private static void addCheckBoxColumn(@NotNull JPanel panel, @NotNull GridBagConstraints gbc,
                                          @NotNull JComponent checkBox) {
        gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(2, JBUI.scale(20), 2, 4);
        panel.add(checkBox, gbc);
    }

    public void setPortChangeListener(@Nullable Consumer<String> listener) {
        this.portChangeListener = listener;
    }

    @Override
    public void loadConfiguration() {
        isSettingPort = true;
        httpPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpPort()));
        isSettingPort = false;
        httpsPortField.setText(String.valueOf(DynamicTomcatEnvironment.getHttpsPort()));
        jmxPortField.setText(String.valueOf(DynamicTomcatEnvironment.getJmxPort()));
        ajpPortField.setText("");
        shutdownPortField.setText(String.valueOf(DynamicTomcatEnvironment.getShutdownPort()));
        catalinaBaseField.setText("");
        deployAppsCheckBox.setSelected(DynamicTomcatEnvironment.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(false);
        allowMultipleInstancesCheckBox.setSelected(false);
        portStrategyCombo.setSelectedItem(com.dev.idea.plugins.tomcat.model.PortStrategy.AUTO_BUMP);
    }

    @Override
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        Integer httpPort = configuration.getHttpPort();
        isSettingPort = true;
        httpPortField.setText(httpPort != null ? httpPort.toString() : DynamicTomcatEnvironment.getHttpPort() + "");
        isSettingPort = false;

        Integer httpsPort = configuration.getHttpsPort();
        httpsPortField.setText(httpsPort != null ? httpsPort.toString() : "");

        // When JMX is disabled, the field MUST stay empty — pre-filling with the
        // default port silently re-enables JMX on the next applyTo() because the
        // applyTo path treats "field has a number" as "user wants this connector
        // enabled". Mirrors the HTTPS / AJP rows above for the same reason.
        Integer jmxPort = configuration.getJmxPort();
        jmxPortField.setText(jmxPort != null ? jmxPort.toString() : "");

        Integer ajpPort = configuration.getConfigData().getPortConfig().isAjpEnabled()
                ? configuration.getConfigData().getPortConfig().getAjp()
                : null;
        ajpPortField.setText(ajpPort != null ? ajpPort.toString() : "");

        Integer shutdownPort = configuration.getShutdownPort();
        shutdownPortField.setText(shutdownPort != null ? shutdownPort.toString()
                : String.valueOf(DynamicTomcatEnvironment.getShutdownPort()));

        String catalinaBase = configuration.getConfigData().getCatalinaBase();
        catalinaBaseField.setText(catalinaBase != null ? catalinaBase : "");

        deployAppsCheckBox.setSelected(configuration.isHotDeploymentEnabled());
        preserveSessionsCheckBox.setSelected(configuration.isPreserveSessions());
        allowMultipleInstancesCheckBox.setSelected(configuration.isAllowMultipleInstances());
        portStrategyCombo.setSelectedItem(configuration.getConfigData().getPortConfig().getStrategy());

        LOG.debug("Reset Tomcat settings: HTTP=" + httpPortField.getText() +
                ", Shutdown=" + shutdownPortField.getText() +
                ", HTTPS=" + httpsPortField.getText() +
                ", JMX=" + jmxPortField.getText() +
                ", AJP=" + ajpPortField.getText() +
                ", CATALINA_BASE=" + catalinaBaseField.getText());
    }

    @Override
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        PortValidator.PortConfiguration portConfig =
                parsePortConfiguration(/* lenientShutdown */ false);

        PortValidator.validateOrThrow(portConfig);

        configuration.setHttpPort(portConfig.httpPort);
        configuration.setShutdownPort(portConfig.shutdownPort);
        configuration.setHttpsPort(portConfig.httpsPort);
        configuration.setJmxPort(portConfig.jmxPort);

        configuration.getConfigData().getPortConfig().setHttpsEnabled(portConfig.httpsEnabled);
        configuration.getConfigData().getPortConfig().setJmxEnabled(portConfig.jmxEnabled);
        configuration.getConfigData().getPortConfig().setAjpEnabled(portConfig.ajpEnabled);
        if (portConfig.ajpEnabled && portConfig.ajpPort != null) {
            configuration.getConfigData().getPortConfig().setAjp(portConfig.ajpPort);
        }

        String catalinaBase = catalinaBaseField.getText().trim();
        configuration.getConfigData().setCatalinaBase(catalinaBase.isEmpty() ? null : catalinaBase);

        configuration.setHotDeploymentEnabled(deployAppsCheckBox.isSelected());
        configuration.setPreserveSessions(preserveSessionsCheckBox.isSelected());
        configuration.setAllowMultipleInstances(allowMultipleInstancesCheckBox.isSelected());
        Object selectedStrategy = portStrategyCombo.getSelectedItem();
        if (selectedStrategy instanceof com.dev.idea.plugins.tomcat.model.PortStrategy s) {
            configuration.getConfigData().getPortConfig().setStrategy(s);
        }

        LOG.debug("Applied Tomcat settings - HTTP: " + portConfig.httpPort +
                ", Shutdown: " + portConfig.shutdownPort +
                ", HTTPS: " + (portConfig.httpsEnabled ? portConfig.httpsPort : "disabled") +
                ", JMX: " + (portConfig.jmxEnabled ? portConfig.jmxPort : "disabled") +
                ", AJP: " + (portConfig.ajpEnabled ? portConfig.ajpPort : "disabled") +
                ", Hot Deployment: " + deployAppsCheckBox.isSelected() +
                ", Preserve Sessions: " + preserveSessionsCheckBox.isSelected());
    }

    private Integer parseShutdownPort() {
        try {
            return PortUtils.parsePort(shutdownPortField.getText(), "Shutdown");
        } catch (ConfigurationException e) {
            return null;
        }
    }

    /**
     * Parses the port fields into the validator's input shape. The enabled flags
     * derive from presence — an empty optional-port field means disabled.
     *
     * @param lenientShutdown validation paths tolerate an unparsable shutdown port
     *                        (treated as absent) so the other ports still validate;
     *                        the apply path must instead throw on it
     */
    private PortValidator.PortConfiguration parsePortConfiguration(boolean lenientShutdown)
            throws ConfigurationException {
        Integer httpPort = PortUtils.parsePort(httpPortField.getText(), "HTTP");
        Integer httpsPort = PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
        Integer jmxPort = PortUtils.parsePort(jmxPortField.getText(), "JMX");
        Integer ajpPort = PortUtils.parsePort(ajpPortField.getText(), "AJP");
        Integer shutdownPort = lenientShutdown
                ? parseShutdownPort()
                : PortUtils.parsePort(shutdownPortField.getText(), "Shutdown");
        return PortValidator.PortConfiguration.builder()
                .httpPort(httpPort)
                .shutdownPort(shutdownPort)
                .httpsPort(httpsPort)
                .httpsEnabled(httpsPort != null)
                .jmxPort(jmxPort)
                .jmxEnabled(jmxPort != null)
                .ajpPort(ajpPort)
                .ajpEnabled(ajpPort != null)
                .build();
    }

    @Override
    public boolean isConfigurationValid() {
        try {
            return PortValidator.validate(parsePortConfiguration(true)).isValid();
        } catch (ConfigurationException e) {
            return false;
        }
    }

    @Override
    public boolean isModified(@NotNull TomcatRunConfiguration config) {
        try {
            Integer configHttpPort = config.getHttpPort();
            Integer currentHttpPort = PortUtils.parsePort(httpPortField.getText(), "HTTP");
            if (!Objects.equals(configHttpPort, currentHttpPort)) {
                return true;
            }

            Integer configShutdownPort = config.getShutdownPort();
            Integer currentShutdownPort = parseShutdownPort();
            if (!Objects.equals(configShutdownPort, currentShutdownPort)) {
                return true;
            }

            Integer configHttpsPort = config.getHttpsPort();
            Integer currentHttpsPort = PortUtils.parsePort(httpsPortField.getText(), "HTTPS");
            if (!Objects.equals(configHttpsPort, currentHttpsPort)) {
                return true;
            }

            Integer configJmxPort = config.getJmxPort();
            Integer currentJmxPort = PortUtils.parsePort(jmxPortField.getText(), "JMX");
            if (!Objects.equals(configJmxPort, currentJmxPort)) {
                return true;
            }

            Integer configAjpPort = config.getConfigData().getPortConfig().isAjpEnabled()
                    ? config.getConfigData().getPortConfig().getAjp()
                    : null;
            Integer currentAjpPort = PortUtils.parsePort(ajpPortField.getText(), "AJP");
            if (!Objects.equals(configAjpPort, currentAjpPort)) {
                return true;
            }

            if (config.isHotDeploymentEnabled() != deployAppsCheckBox.isSelected()) {
                return true;
            }
            if (config.isPreserveSessions() != preserveSessionsCheckBox.isSelected()) {
                return true;
            }
            if (config.isAllowMultipleInstances() != allowMultipleInstancesCheckBox.isSelected()) {
                return true;
            }

            String configCatalinaBase = config.getConfigData().getCatalinaBase();
            String currentCatalinaBase = catalinaBaseField.getText().trim();
            if (!Objects.equals(configCatalinaBase != null ? configCatalinaBase : "", currentCatalinaBase)) {
                return true;
            }

            com.dev.idea.plugins.tomcat.model.PortStrategy configStrategy =
                    config.getConfigData().getPortConfig().getStrategy();
            Object selected = portStrategyCombo.getSelectedItem();
            if (selected instanceof com.dev.idea.plugins.tomcat.model.PortStrategy current
                    && current != configStrategy) {
                return true;
            }

        } catch (ConfigurationException e) {
            LOG.warn("Error checking modifications", e);
            return true; // If we can't parse, assume modified
        }

        return false;
    }

    @Override
    @NotNull
    public List<ValidationInfo> validateSettings() {
        List<ValidationInfo> errors = new ArrayList<>();

        try {
            ValidationResult result = PortValidator.validate(parsePortConfiguration(true));

            if (!result.isValid()) {
                for (String error : result.getErrors()) {
                    errors.add(new ValidationInfo(error, httpPortField));
                }
            }

        } catch (ConfigurationException e) {
            errors.add(new ValidationInfo(e.getTitle(), httpPortField));
        }

        return errors;
    }

    @Override
    public void dispose() {
        if (httpPortField != null && httpPortDocListener != null) {
            httpPortField.getDocument().removeDocumentListener(httpPortDocListener);
            httpPortDocListener = null;
        }
        portChangeListener = null;
    }
}
