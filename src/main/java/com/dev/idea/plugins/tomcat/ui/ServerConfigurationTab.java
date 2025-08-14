package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.sections.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Server Configuration Tab
 * Main configuration panel for Tomcat server settings. This tab provides
 * a comprehensive UI for configuring all aspects of a Tomcat run configuration
 * including server selection, ports, VM options, and deployment settings.
 * The UI is organized into logical sections following the Single Responsibility
 * Principle, with each section handling a specific aspect of the configuration.
 *
 * @author Dev Tomcat Team
 */
public class ServerConfigurationTab extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ServerConfigurationTab.class);

    // Store the last configuration for comparison
    private TomcatRunConfiguration lastConfiguration;

    // === SECTION COMPONENTS ===
    private final ApplicationServerSection serverSection;
    private final BrowserLaunchSection browserSection;
    private final VmOptionsSection vmSection;
    private final TomcatSettingsSection tomcatSection;
    private final BottomOptionsSection bottomSection;

    private final List<ConfigurationSection> allSections;

    /**
     * Create a new server configuration tab
     *
     * @param project The current project
     */
    public ServerConfigurationTab(@NotNull Project project) {

        // Initialize all sections
        this.serverSection = new ApplicationServerSection(project);
        this.browserSection = new BrowserLaunchSection(project);
        this.vmSection = new VmOptionsSection();
        UpdateActionsSection updateSection = new UpdateActionsSection();
        JreConfigurationSection jreSection = new JreConfigurationSection(project);
        this.tomcatSection = new TomcatSettingsSection();
        BeforeLaunchSection beforeLaunchSection = new BeforeLaunchSection();
        this.bottomSection = new BottomOptionsSection();

        // Store all sections for easy iteration
        this.allSections = Arrays.asList(
                serverSection,
                browserSection,
                vmSection,
                updateSection,
                jreSection,
                tomcatSection,
                beforeLaunchSection,
                bottomSection
        );

        initializeLayout();
        loadAllConfigurations();

        // Register for disposal TODO: it need a check up for this part because you
        //  need to use the parent class not like this one and let me know when you are done for this part.
        Disposer.register(project, this);

        LOG.info("ServerConfigurationTab initialized with " + allSections.size() + " sections");
    }

    /**
     * Initialize the UI layout
     */
    private void initializeLayout() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        JPanel mainPanel = createMainPanel();

        JBScrollPane scrollPane = new JBScrollPane(mainPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Create the main panel with all sections
     */
    private JPanel createMainPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = JBUI.insets(8, 0);

        // Add all sections
        int row = 0;
        for (ConfigurationSection section : allSections) {
            gbc.gridy = row++;

            // Adjust constraints based on section needs
            if (section.shouldFillVertically()) {
                gbc.weighty = section instanceof VmOptionsSection ? 0.2 : 0.1;
                gbc.fill = GridBagConstraints.BOTH;
            } else {
                gbc.weighty = 0.0;
                gbc.fill = GridBagConstraints.HORIZONTAL;
            }

            panel.add(section.createPanel(), gbc);
        }

        // Add glue at bottom
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    /**
     * Load configurations for all sections
     */
    private void loadAllConfigurations() {
        allSections.forEach(ConfigurationSection::loadConfiguration);
    }

    /**
     * Reset configuration from TomcatRunConfiguration
     *
     * @param configuration The configuration to load
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        LOG.debug("Loading configuration into ServerTab");

        this.lastConfiguration = configuration;

        allSections.forEach(section -> {
            try {
                section.resetFrom(configuration);
            } catch (Exception e) {
                LOG.error("Error resetting section " + section.getClass().getSimpleName(), e);
            }
        });

        LOG.info("Configuration loaded - Server: " +
                (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "None") +
                ", HTTP Port: " + configuration.getPort() +
                ", JMX: " + (configuration.isJmxEnabled() ? configuration.getJmxPort() : "disabled"));
    }

    /**
     * Apply configuration to TomcatRunConfiguration
     *
     * @param configuration The configuration to update
     * @throws ConfigurationException if validation fails
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        LOG.debug("Applying ServerTab configuration");

        // Validate first
        validateSettings();

        // Apply configurations from all sections
        for (ConfigurationSection section : allSections) {
            try {
                section.applyTo(configuration);
            } catch (ConfigurationException e) {
                LOG.error("Error applying section " + section.getClass().getSimpleName(), e);
                throw e;
            } catch (Exception e) {
                LOG.error("Unexpected error in section " + section.getClass().getSimpleName(), e);
                throw new ConfigurationException("Error applying configuration: " + e.getMessage());
            }
        }

        LOG.info("Configuration applied - Server: " +
                (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "None") +
                ", Ports: HTTP=" + configuration.getPort() + ", JMX=" + configuration.getJmxPort());
    }

    /**
     * Validate all settings
     *
     * @throws ConfigurationException if validation fails
     */
    public void validateSettings() throws ConfigurationException {
        // Check if server is selected
        if (!hasSelectedTomcatServer()) {
            throw new ConfigurationException("Please select a Tomcat server");
        }

        // Validate HTTP port
        int httpPort = getHttpPort();
        if (httpPort < 1 || httpPort > 65535) {
            throw new ConfigurationException("HTTP port must be between 1 and 65535");
        }

        // Validate JMX port if enabled
        if (isJmxEnabled()) {
            int jmxPort = getJmxPort();
            if (jmxPort < 1 || jmxPort > 65535) {
                throw new ConfigurationException("JMX port must be between 1 and 65535");
            }

            if (httpPort == jmxPort) {
                throw new ConfigurationException("HTTP and JMX ports cannot be the same");
            }
        }

        // Validate all sections
        for (ConfigurationSection section : allSections) {
            if (!section.isValid()) {
                throw new ConfigurationException("Invalid configuration in " +
                        section.getClass().getSimpleName());
            }
        }
    }

    /**
     * Check if the configuration has been modified
     *
     * @param configuration The configuration to compare against
     * @return true if modified
     */
    public boolean isModified(@NotNull TomcatRunConfiguration configuration) {
        // Check if we have a different configuration object
        if (lastConfiguration != configuration) {
            return true;
        }

        // Check major settings for modifications
        try {
            // Server selection
            if (configuration.getTomcatInfo() != serverSection.getSelectedTomcatServer()) {
                return true;
            }

            // Port settings
            if (!Objects.equals(configuration.getPort(), getHttpPort())) {
                return true;
            }

            if (!Objects.equals(configuration.getJmxPort(), getJmxPort())) {
                return true;
            }

            if (configuration.isJmxEnabled() != isJmxEnabled()) {
                return true;
            }

            // VM options
            String currentVmOptions = configuration.getVmOptions();
            String uiVmOptions = getVmOptions();
            if (!Objects.equals(currentVmOptions, uiVmOptions)) {
                return true;
            }

            // Browser settings
            if (configuration.isAfterLaunchEnabled() != isAfterLaunchEnabled()) {
                return true;
            }

            if (!Objects.equals(configuration.getBrowserUrl(), getBrowserUrl())) {
                return true;
            }

            // Tool window settings
            if (configuration.isActivateToolWindow() != isActivateToolWindowEnabled()) {
                return true;
            }

            if (configuration.isFocusToolWindow() != isFocusToolWindowEnabled()) {
                return true;
            }

        } catch (Exception e) {
            LOG.warn("Error checking modifications", e);
            return true;
        }

        return false;
    }

    /**
     * Dispose of resources
     */
    @Override
    public void dispose() {
        LOG.debug("Disposing ServerConfigurationTab");

        // Dispose any disposable sections
        for (ConfigurationSection section : allSections) {
            if (section instanceof Disposable) {
                Disposer.dispose((Disposable) section);
            }
        }
    }

    // === DELEGATION METHODS ===

    public boolean hasSelectedTomcatServer() {
        return serverSection.getSelectedTomcatServer() != null;
    }

    public int getHttpPort() {
        return tomcatSection.getHttpPort();
    }

    public int getJmxPort() {
        return tomcatSection.getJmxPort();
    }

    public boolean isJmxEnabled() {
        return tomcatSection.isJmxEnabled();
    }

    public String getVmOptions() {
        return vmSection.getVmOptions();
    }

    public String getBrowserUrl() {
        return browserSection.getUrl();
    }

    public boolean isAfterLaunchEnabled() {
        return browserSection.isAfterLaunchEnabled();
    }

    public boolean isActivateToolWindowEnabled() {
        return bottomSection.isActivateToolWindowEnabled();
    }

    public boolean isFocusToolWindowEnabled() {
        return bottomSection.isFocusToolWindowEnabled();
    }


}