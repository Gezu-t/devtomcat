package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.ui.ServerConfigurationTab;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Dev Tomcat Runner Settings Editor
 *
 * This class provides a compatibility layer between IntelliJ's SettingsEditor
 * system and the Dev Tomcat ServerConfigurationTab. It acts as an adapter,
 * delegating the actual UI and configuration logic to ServerConfigurationTab
 * while conforming to the SettingsEditor interface required by IntelliJ.
 *
 * The editor is responsible for:
 * - Creating the configuration UI component
 * - Loading configuration data into the UI
 * - Applying UI changes back to the configuration
 * - Validating configuration changes
 *
 * @author Dev Tomcat Team
 * @see SettingsEditor
 * @see ServerConfigurationTab
 * @see TomcatRunConfiguration
 */
public class TomcatRunnerSettingsEditor extends SettingsEditor<TomcatRunConfiguration> {

    private static final Logger LOG = Logger.getInstance(TomcatRunnerSettingsEditor.class);

    private final ServerConfigurationTab serverTab;
    private final Project project;

    /**
     * Creates a new settings editor for Tomcat run configurations
     *
     * @param project The current project context
     */
    public TomcatRunnerSettingsEditor(@NotNull Project project) {
        this.project = project;
        this.serverTab = new ServerConfigurationTab(project);

        LOG.debug("Created Dev Tomcat settings editor for project: " + project.getName());
    }

    /**
     * Reset the editor UI from the given configuration
     *
     * This method is called when the configuration dialog is opened or
     * when the user clicks "Reset" button. It should load all settings
     * from the configuration into the UI components.
     *
     * @param configuration The configuration to load settings from
     */
    @Override
    protected void resetEditorFrom(@NotNull TomcatRunConfiguration configuration) {
        try {
            LOG.debug("Resetting editor from configuration: " + configuration.getName());
            serverTab.resetFrom(configuration);
        } catch (Exception e) {
            LOG.error("Failed to reset editor from configuration", e);
            // Show error to user but don't crash
            JOptionPane.showMessageDialog(
                    serverTab,
                    "Failed to load configuration: " + e.getMessage(),
                    "Configuration Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Apply the editor UI settings to the given configuration
     *
     * This method is called when the user clicks "OK" or "Apply" button.
     * It should validate the UI input and update the configuration with
     * the new settings.
     *
     * @param configuration The configuration to apply settings to
     * @throws ConfigurationException If validation fails or settings cannot be applied
     */
    @Override
    protected void applyEditorTo(@NotNull TomcatRunConfiguration configuration)
            throws ConfigurationException {
        try {
            LOG.debug("Applying editor settings to configuration: " + configuration.getName());

            // Validate before applying
            validateSettings();

            // Apply the settings
            serverTab.applyTo(configuration);

            // Log configuration summary for debugging
            LOG.debug("Configuration applied: " + configuration.getConfigurationSummary());

        } catch (ConfigurationException e) {
            // Re-throw configuration exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap other exceptions in ConfigurationException
            LOG.error("Failed to apply editor settings to configuration", e);
            throw new ConfigurationException(
                    "Failed to apply configuration: " + e.getMessage(),
                    "Configuration Error"
            );
        }
    }

    /**
     * Create the editor UI component
     *
     * This method is called once when the settings dialog is first opened.
     * It should return the main UI component that will be displayed in
     * the configuration dialog.
     *
     * @return The main UI component for this editor
     */
    @Override
    @NotNull
    protected JComponent createEditor() {
        LOG.debug("Creating Dev Tomcat configuration editor UI");
        return serverTab;
    }

    /**
     * Validate the current settings in the UI
     *
     * This method performs validation of the UI input before applying
     * changes to the configuration. It should throw ConfigurationException
     * if any validation fails.
     *
     * @throws ConfigurationException If validation fails
     */
    private void validateSettings() throws ConfigurationException {
        // Delegate validation to the server tab
        serverTab.validateSettings();

        // Additional validation can be added here if needed
        // For example, checking for port conflicts, file permissions, etc.
    }

    /**
     * Dispose of any resources held by this editor
     *
     * This method is called when the editor is no longer needed.
     * Override this if you need to clean up resources.
     */
    @Override
    protected void disposeEditor() {
        LOG.debug("Disposing Dev Tomcat settings editor");

        // Clean up any resources if needed
        serverTab.dispose();

        super.disposeEditor();
    }

    /**
     * Get the project associated with this editor
     *
     * @return The current project
     */
    @NotNull
    public Project getProject() {
        return project;
    }

    /**
     * Get the server configuration tab component
     *
     * This method is provided for testing and advanced use cases
     * where direct access to the UI component is needed.
     *
     * @return The ServerConfigurationTab instance
     */
    @NotNull
    public ServerConfigurationTab getServerTab() {
        return serverTab;
    }

    /**
     * Check if the editor has been modified
     *
     * This can be used to enable/disable the "Apply" button based on
     * whether the user has made any changes.
     *
     * @param configuration The current configuration
     * @return True if the editor has unsaved changes
     */
    public boolean isModified(@NotNull TomcatRunConfiguration configuration) {
        try {
            return serverTab.isModified(configuration);
        } catch (Exception e) {
            LOG.warn("Failed to check if editor is modified", e);
            return false;
        }
    }
}