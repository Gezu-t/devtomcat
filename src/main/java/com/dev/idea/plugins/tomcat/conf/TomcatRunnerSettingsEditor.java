package com.dev.idea.plugins.tomcat.conf;

                 import com.dev.idea.plugins.tomcat.ui.ServerConfigurationTab;
                 import com.intellij.execution.configurations.RuntimeConfigurationException;
                 import com.intellij.openapi.diagnostic.Logger;
                 import com.intellij.openapi.options.ConfigurationException;
                 import com.intellij.openapi.options.SettingsEditor;
                 import com.intellij.openapi.project.Project;
                 import com.intellij.openapi.ui.Messages;
                 import org.jetbrains.annotations.NotNull;

                 import javax.swing.*;
                 import java.util.Objects;

                 /**
                  * Dev Tomcat Runner Settings Editor
                  *
                  * <p>Ultimate-grade adapter with CORRECT IntelliJ Logger and UI usage.
                  *
                  * <p>Responsibilities:
                  * <ul>
                  *   <li>Reset editor from TomcatRunConfiguration</li>
                  *   <li>Apply editor changes to configuration</li>
                  *   <li>Manage ServerConfigurationTab lifecycle</li>
                  *   <li>Handle validation errors with proper UI feedback</li>
                  * </ul>
                  *
                  * <p>100% NULL-SAFE — All null checks with comprehensive error handling
                  */
                 public class TomcatRunnerSettingsEditor extends SettingsEditor<TomcatRunConfiguration> {

                     private static final Logger LOG = Logger.getInstance(TomcatRunnerSettingsEditor.class);

                     private final ServerConfigurationTab serverTab;
                     private final Project project;

                     /**
                      * Create a new settings editor for the given project.
                      *
                      * @param project the IDE project (cannot be null)
                      */
                     public TomcatRunnerSettingsEditor(@NotNull Project project) {
                         Objects.requireNonNull(project, "Project cannot be null");

                         this.project = project;
                         this.serverTab = new ServerConfigurationTab(project, null);
                         LOG.debug("Created Dev Tomcat settings editor for project: " + project.getName());
                     }

                     /**
                      * Reset the editor UI from the given configuration.
                      *
                      * <p>Loads all configuration values into the UI components.
                      *
                      * @param configuration the configuration to load from (cannot be null)
                      */
                     @Override
                     protected void resetEditorFrom(@NotNull TomcatRunConfiguration configuration) {
                         Objects.requireNonNull(configuration, "Configuration cannot be null");

                         try {
                             LOG.debug("Resetting editor from configuration: " + configuration.getName());
                             serverTab.resetFrom(configuration);
                             LOG.debug("Editor reset successfully");
                         } catch (Exception e) {
                             LOG.error("Failed to reset editor from configuration: " + configuration.getName(), e);
                             showError("Failed to load configuration:\n" + e.getMessage());
                         }
                     }

                     /**
                      * Apply the editor UI changes to the given configuration.
                      *
                      * <p>Process:
                      * <ul>
                      *   <li>Apply UI values to configuration</li>
                      *   <li>Validate the configuration</li>
                      *   <li>Show error dialog on validation failure</li>
                      * </ul>
                      *
                      * @param configuration the configuration to apply to (cannot be null)
                      * @throws ConfigurationException if validation fails
                      */
                     @Override
                     protected void applyEditorTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
                         Objects.requireNonNull(configuration, "Configuration cannot be null");

                         try {
                             LOG.debug("Applying editor settings to configuration: " + configuration.getName());

                             // === APPLY UI TO CONFIGURATION ===
                             serverTab.applyTo(configuration);
                             LOG.debug("UI values applied to configuration");

                            // === VALIDATE CONFIGURATION ===
                            try {
                                TomcatConfigurationValidator.validate(configuration);
                                LOG.debug("Configuration validation passed: " + configuration.getName());
                            } catch (RuntimeConfigurationException e) {
                                LOG.warn("Configuration validation failed: " + e.getLocalizedMessage());
                                throw new ConfigurationException(e.getLocalizedMessage(), "Tomcat Configuration Error");
                            }

                        } catch (ConfigurationException e) {
                            LOG.error("Configuration exception during apply: " + e.getLocalizedMessage());
                            throw e;
                        } catch (Exception e) {
                            LOG.error("Unexpected error applying configuration: " + e.getLocalizedMessage(), e);
                            throw new ConfigurationException("Failed to apply configuration: " + e.getLocalizedMessage(), "Tomcat Configuration Error");
                        }
                    }

                     /**
                      * Create the editor UI component.
                      *
                      * @return the ServerConfigurationTab component
                      */
                     @Override
                     @NotNull
                     protected JComponent createEditor() {
                         LOG.debug("Creating Dev Tomcat configuration editor UI");
                         return serverTab;
                     }

                     /**
                      * Dispose of the editor when closed.
                      *
                      * <p>Cleans up the ServerConfigurationTab and calls parent dispose.
                      */
                     @Override
                     protected void disposeEditor() {
                         LOG.debug("Disposing Dev Tomcat settings editor");
                         try {
                             serverTab.dispose();
                         } catch (Exception e) {
                             LOG.warn("Error disposing server tab", e);
                         }
                         super.disposeEditor();
                     }

                     /**
                      * Get the project associated with this editor.
                      *
                      * @return the project (never null)
                      */
                     @NotNull
                     public Project getProject() {
                         return project;
                     }

                     /**
                      * Get the server configuration tab.
                      *
                      * @return the ServerConfigurationTab (never null)
                      */
                     @NotNull
                     public ServerConfigurationTab getServerTab() {
                         return serverTab;
                     }

                     /**
                      * Show an error dialog to the user.
                      *
                      * @param message the error message (cannot be null)
                      */
                     private void showError(@NotNull String message) {
                         Objects.requireNonNull(message, "Error message cannot be null");

                         try {
                             Messages.showErrorDialog(serverTab, message, "Tomcat Configuration Error");
                             LOG.debug("Error dialog shown: " + message);
                         } catch (Exception e) {
                             LOG.warn("Failed to show error dialog", e);
                         }
                     }
                 }
