package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.ui.server.sections.*;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * Professional Server Configuration Tab - Clean Architecture
 *
 * This class follows Single Responsibility Principle by delegating
 * specific concerns to dedicated section classes.
 *
 * Provides professional IDE-level Tomcat Server configuration:
 * - Application server section (no module selection)
 * - Open browser section
 * - VM options section (user defines own, no pre-population)
 * - On 'Update' action section
 * - JRE configuration section
 * - Tomcat settings section (ONLY HTTP and JMX ports)
 * - Before launch section
 * - Bottom options section
 *
 * @author Gezahegn Lemma (Gezu)
 * @version 3.1 - Professional Clean Architecture
 */
public class ServerConfigurationTab extends JPanel {

    private final Project project;

    // === SECTION COMPONENTS (Clean Separation - Professional Layout) ===
    private final ApplicationServerSection serverSection;
    private final BrowserLaunchSection browserSection;
    private final VmOptionsSection vmSection;
    private final UpdateActionsSection updateSection;
    private final JreConfigurationSection jreSection;
    private final TomcatSettingsSection tomcatSection;
    private final BeforeLaunchSection beforeLaunchSection;
    private final BottomOptionsSection bottomSection;

    private final List<ConfigurationSection> allSections;

    public ServerConfigurationTab(@NotNull Project project) {
        this.project = project;

        // Initialize all sections - Professional order and components
        this.serverSection = new ApplicationServerSection(project);
        this.browserSection = new BrowserLaunchSection(project);
        this.vmSection = new VmOptionsSection();
        this.updateSection = new UpdateActionsSection();
        this.jreSection = new JreConfigurationSection(project);
        this.tomcatSection = new TomcatSettingsSection();
        this.beforeLaunchSection = new BeforeLaunchSection();
        this.bottomSection = new BottomOptionsSection();

        // Store all sections for easy iteration - Professional IDE order
        this.allSections = Arrays.asList(
                serverSection,           // Application server dropdown + Configure button
                browserSection,          // Open browser section
                vmSection,              // VM options (empty by default)
                updateSection,          // On 'Update' action dropdown + Show dialog
                jreSection,             // JRE dropdown + configure button
                tomcatSection,          // Tomcat Server Settings (HTTP + JMX ports only)
                beforeLaunchSection,    // Collapsible Before launch with Build task
                bottomSection           // Bottom checkboxes
        );

        initializeLayout();
        loadAllConfigurations();

        System.out.println("DevTomcat: Professional ServerConfigurationTab initialized with " +
                allSections.size() + " sections");
    }

    /**
     * Simple layout initialization - delegates UI creation to sections
     * Updated to match Ultimate's exact layout spacing
     */
    private void initializeLayout() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        JPanel mainPanel = createMainPanel();

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Create main panel with all sections - Ultimate spacing and layout
     */
    private JPanel createMainPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = JBUI.insets(8, 0, 8, 0); // Professional IDE spacing

        // Add all sections in professional order with proper spacing
        int row = 0;
        for (ConfigurationSection section : allSections) {
            gbc.gridy = row++;

            // Special handling for sections that need vertical space (VM options, Before launch)
            if (section instanceof VmOptionsSection) {
                gbc.weighty = 0.2;
                gbc.fill = GridBagConstraints.BOTH;
            } else if (section instanceof BeforeLaunchSection) {
                gbc.weighty = 0.1;
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
     * Reset configuration from TomcatRunConfiguration - Professional IDE alignment
     */
    public void resetFrom(@NotNull TomcatRunConfiguration configuration) {
        System.out.println("DevTomcat: Loading professional configuration into ServerTab");

        allSections.forEach(section -> section.resetFrom(configuration));

        System.out.println("DevTomcat: Professional configuration loaded - Server: " +
                (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "None") +
                ", HTTP Port: " + (configuration.getPort() != null ? configuration.getPort() : "8080") +
                ", JMX Port: " + (configuration.getJmxPort() != null ? configuration.getJmxPort() : "1099"));
    }

    /**
     * Apply configuration to TomcatRunConfiguration - Professional IDE alignment
     */
    public void applyTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        System.out.println("DevTomcat: Applying professional ServerTab configuration");

        // Apply configurations from all sections with validation
        for (ConfigurationSection section : allSections) {
            try {
                section.applyTo(configuration);
            } catch (ConfigurationException e) {
                System.err.println("DevTomcat: Error applying " + section.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }

        System.out.println("DevTomcat: Professional configuration applied successfully - " +
                "Server: " + (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "None") +
                ", Ports: HTTP=" + configuration.getPort() + ", JMX=" + configuration.getJmxPort());
    }

    /**
     * Validate all sections
     */
    public boolean isConfigurationValid() {
        return allSections.stream().allMatch(ConfigurationSection::isValid);
    }

    // === DELEGATION METHODS FOR EXTERNAL ACCESS - Professional IDE Pattern ===

    public boolean hasSelectedTomcatServer() {
        return serverSection.getSelectedTomcatServer() != null;
    }

    public String getSelectedTomcatServerName() {
        return serverSection.getSelectedTomcatServer() != null ?
                serverSection.getSelectedTomcatServer().getName() : "None";
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

    public String getUpdateAction() {
        return updateSection.getSelectedAction();
    }

    public boolean isShowDialogEnabled() {
        return updateSection.isShowDialogEnabled();
    }

    public String getSelectedJRE() {
        return jreSection.getSelectedJRE();
    }

    public boolean isDeployAppsEnabled() {
        return tomcatSection.isDeployAppsEnabled();
    }

    public boolean isPreserveSessionsEnabled() {
        return tomcatSection.isPreserveSessionsEnabled();
    }

    public java.util.List<String> getBeforeLaunchTasks() {
        return beforeLaunchSection.getTasks();
    }

    public boolean isActivateToolWindowEnabled() {
        return bottomSection.isActivateToolWindowEnabled();
    }

    public boolean isFocusToolWindowEnabled() {
        return bottomSection.isFocusToolWindowEnabled();
    }

    // === PROFESSIONAL IDE CONFIGURATION SUMMARY ===

    public String getConfigurationSummary() {
        StringBuilder summary = new StringBuilder("DevTomcat Professional Configuration Summary:\n");
        summary.append("├─ Server: ").append(getSelectedTomcatServerName()).append("\n");
        summary.append("├─ HTTP Port: ").append(getHttpPort()).append("\n");
        summary.append("├─ JMX Port: ").append(getJmxPort()).append(" (").append(isJmxEnabled() ? "enabled" : "disabled").append(")\n");
        summary.append("├─ VM Options: ").append(getVmOptions().isEmpty() ? "None" : "Configured").append("\n");
        summary.append("├─ Browser Launch: ").append(isAfterLaunchEnabled() ? "Enabled" : "Disabled").append("\n");
        summary.append("├─ Update Action: ").append(getUpdateAction()).append("\n");
        summary.append("├─ JRE: ").append(getSelectedJRE()).append("\n");
        summary.append("├─ Deploy Apps: ").append(isDeployAppsEnabled() ? "Yes" : "No").append("\n");
        summary.append("├─ Preserve Sessions: ").append(isPreserveSessionsEnabled() ? "Yes" : "No").append("\n");
        summary.append("├─ Before Launch Tasks: ").append(getBeforeLaunchTasks().size()).append("\n");
        summary.append("└─ Tool Window: Activate=").append(isActivateToolWindowEnabled()).append(", Focus=").append(isFocusToolWindowEnabled()).append("\n");
        return summary.toString();
    }

    // === PROFESSIONAL IDE VALIDATION ===

    public String validateConfiguration() {
        StringBuilder errors = new StringBuilder();

        if (!hasSelectedTomcatServer()) {
            errors.append("- No Tomcat server selected\n");
        }

        if (getHttpPort() < 1 || getHttpPort() > 65535) {
            errors.append("- Invalid HTTP port: ").append(getHttpPort()).append("\n");
        }

        if (isJmxEnabled() && (getJmxPort() < 1 || getJmxPort() > 65535)) {
            errors.append("- Invalid JMX port: ").append(getJmxPort()).append("\n");
        }

        if (getHttpPort() == getJmxPort() && isJmxEnabled()) {
            errors.append("- HTTP and JMX ports cannot be the same\n");
        }

        return errors.toString();
    }
}