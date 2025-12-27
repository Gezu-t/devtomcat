package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.ui.deployment.ArtifactSelectionHandler;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentConfigurationPanel;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentTableManager;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.ui.components.JBTabbedPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TomcatConfigurationEditor extends SettingsEditor<TomcatRunConfiguration> {
    private static final Logger LOG = Logger.getInstance(TomcatConfigurationEditor.class);
    private final Project project;
    private TomcatRunConfiguration currentConfiguration;
    private final AtomicBoolean isResetting = new AtomicBoolean(false);
    private final AtomicBoolean isApplying = new AtomicBoolean(false);
    private final AtomicBoolean editorInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isDisposing = new AtomicBoolean(false);
    private ServerConfigurationTab serverTab;
    private DeploymentConfigurationPanel deploymentTab;
    private LogsConfigurationTab logsTab;
    private StartupConnectionTab startupConnectionTab;
    private CodeCoverageTab codeCoverageTab;
    private JBTabbedPane tabbedPane;

    public TomcatConfigurationEditor(@NotNull Project project) {
        this.project = project;
        LOG.info("DevTomcat: Initializing Ultimate-style configuration editor for project: " + project.getName());
    }

    @Override
    protected void resetEditorFrom(@NotNull TomcatRunConfiguration configuration) {
        if (isResetting.get() || isApplying.get() || isDisposing.get()) return;
        if (!isResetting.compareAndSet(false, true)) return;

        try {
            LOG.debug("DevTomcat: Resetting editor from configuration: " + configuration.getName());
            this.currentConfiguration = (TomcatRunConfiguration) configuration.clone();
            if (editorInitialized.get() && tabbedPane != null) {
                resetAllTabs(configuration);
                LOG.info("DevTomcat: Configuration loaded successfully - " + getConfigurationSummary(configuration));
            } else {
                LOG.debug("DevTomcat: Editor not initialized, configuration will be applied after UI creation");
            }
        } catch (Exception e) {
            LOG.error("DevTomcat: Error resetting editor from configuration", e);
            notifyError("Failed to load configuration: " + e.getMessage());
        } finally {
            isResetting.set(false);
        }
    }

    private void resetAllTabs(@NotNull TomcatRunConfiguration configuration) {
        try {
            if (serverTab != null) {
                serverTab.resetFrom(configuration);
                LOG.debug("DevTomcat: Server tab reset complete");
            }
            if (deploymentTab != null) {
                deploymentTab.resetFrom(configuration);
                LOG.debug("DevTomcat: Deployment tab reset - " +
                        configuration.getConfigData().getDeploymentConfig().getArtifacts().size() + " artifacts");
            }
            if (logsTab != null) {
                logsTab.resetFrom(configuration);
                LOG.debug("DevTomcat: Logs tab reset - " +
                        configuration.getLogFileConfigurations().size() + " log files");
            }
            if (startupConnectionTab != null) {
                startupConnectionTab.resetFrom(configuration);
                LOG.debug("DevTomcat: Startup/Connection tab reset");
            }
            if (codeCoverageTab != null) {
                codeCoverageTab.resetFrom(configuration);
                LOG.debug("DevTomcat: Code Coverage tab reset");
            }
        } catch (Exception e) {
            LOG.error("DevTomcat: Error resetting tabs", e);
            throw e;
        }
    }

    @Override
    protected void applyEditorTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        if (isApplying.get() || isResetting.get() || isDisposing.get()) return;
        if (!editorInitialized.get() || tabbedPane == null) return;
        if (!isApplying.compareAndSet(false, true)) return;

        try {
            LOG.debug("DevTomcat: Applying editor settings to configuration");
            validateAllTabs();
            applyAllTabs(configuration);
            LOG.info("DevTomcat: Configuration applied successfully - " + getConfigurationSummary(configuration));
        } catch (ConfigurationException e) {
            LOG.warn("DevTomcat: Configuration validation failed: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            LOG.error("DevTomcat: Unexpected error applying configuration", e);
            throw new ConfigurationException("Failed to apply configuration: " + e.getMessage());
        } finally {
            isApplying.set(false);
        }
    }

    private void validateAllTabs() throws ConfigurationException {
        if (serverTab != null) serverTab.validateSettings();
        if (deploymentTab != null && !deploymentTab.isValid()) {
            throw new ConfigurationException("Invalid deployment configuration");
        }
        // Add validation for other tabs as needed
    }

    private void applyAllTabs(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        if (serverTab != null) {
            serverTab.applyTo(configuration);
            LOG.debug("DevTomcat: Server tab applied");
        }
        if (deploymentTab != null) {
            deploymentTab.applyTo(configuration);
            LOG.debug("DevTomcat: Deployment tab applied - " +
                    configuration.getConfigData().getDeploymentConfig().getArtifacts().size() + " artifacts");
        }
        if (logsTab != null) {
            logsTab.applyTo(configuration);
            LOG.debug("DevTomcat: Logs tab applied - " +
                    configuration.getLogFileConfigurations().size() + " log files");
        }
        if (startupConnectionTab != null) {
            startupConnectionTab.applyTo(configuration);
            LOG.debug("DevTomcat: Startup/Connection tab applied - " +
                    configuration.getEnvironmentVariables().size() + " environment variables");
        }
        if (codeCoverageTab != null) {
            codeCoverageTab.applyTo(configuration);
            LOG.debug("DevTomcat: Code Coverage tab applied");
        }
    }

    @Override
    @NotNull
    protected JComponent createEditor() {
        if (isDisposing.get()) return createErrorPanel("Editor is being disposed");

        try {
            LOG.info("DevTomcat: Creating Ultimate-style 5-tab configuration interface");
            if (currentConfiguration == null) {
                currentConfiguration = createTemplateConfiguration();
            }
            tabbedPane = new JBTabbedPane();
            createAllTabs();
            editorInitialized.set(true);
            if (currentConfiguration != null) {
                LOG.debug("DevTomcat: Applying delayed configuration");
                SwingUtilities.invokeLater(() -> {
                    if (!isDisposing.get()) resetEditorFrom(currentConfiguration);
                });
            }
            LOG.info("DevTomcat: Configuration interface created successfully");
            return tabbedPane;
        } catch (Exception e) {
            LOG.error("DevTomcat: Critical error creating editor", e);
            return createErrorPanel("Failed to create configuration interface: " + e.getMessage());
        }
    }

    private void createAllTabs() {
        createServerTab();
        createDeploymentTab();
        createLogsTab();
        createStartupConnectionTab();
        createCodeCoverageTab();
    }

    private void createServerTab() {
        try {
            // PASS CONFIG HERE
            serverTab = new ServerConfigurationTab(project, currentConfiguration);
            tabbedPane.addTab("Server", serverTab);
            LOG.debug("DevTomcat: Server tab created with dynamic Local/Remote");
        } catch (Exception e) {
            LOG.error("DevTomcat: Failed to create Server tab", e);
            tabbedPane.addTab("Server", createErrorPanel("Server tab error: " + e.getMessage()));
        }
    }

    private void createDeploymentTab() {
        try {
            // Create shared table manager for both panel and handler
            DeploymentTableManager tableManager = new DeploymentTableManager();
            ArtifactSelectionHandler selectionHandler = new ArtifactSelectionHandler(
                    project,
                    ArtifactManager.getInstance(project),
                    tableManager
            );

            deploymentTab = new DeploymentConfigurationPanel(
                    project,
                    tableManager,
                    selectionHandler
            );
            deploymentTab.setParentEditor(this);

            tabbedPane.addTab("Deployment", deploymentTab);
            LOG.debug("DevTomcat: Deployment tab created");
        } catch (Exception e) {
            LOG.error("DevTomcat: Failed to create Deployment tab", e);
            tabbedPane.addTab("Deployment", createErrorPanel("Deployment tab error: " + e.getMessage()));
        }
    }

    private void createLogsTab() {
        try {
            logsTab = new LogsConfigurationTab(project, null);
            tabbedPane.addTab("Logs", logsTab);
            LOG.debug("DevTomcat: Logs tab created");
        } catch (Exception e) {
            LOG.error("DevTomcat: Failed to create Logs tab", e);
            tabbedPane.addTab("Logs", createErrorPanel("Logs tab error: " + e.getMessage()));
        }
    }

    private void createStartupConnectionTab() {
        try {
            TomcatRunConfiguration tempConfig = currentConfiguration != null ?
                    currentConfiguration : createTemplateConfiguration();
            if (tempConfig == null) {
                throw new IllegalStateException("No configuration available for Startup/Connection tab");
            }
            startupConnectionTab = new StartupConnectionTab(project, tempConfig);
            tabbedPane.addTab("Startup/Connection", startupConnectionTab);
            LOG.debug("DevTomcat: Startup/Connection tab created");
        } catch (Exception e) {
            LOG.error("DevTomcat: Failed to create Startup/Connection tab", e);
            tabbedPane.addTab("Startup/Connection", createErrorPanel("Startup/Connection tab error: " + e.getMessage()));
        }
    }

    private void createCodeCoverageTab() {
        try {
            codeCoverageTab = new CodeCoverageTab(project);
            tabbedPane.addTab("Code Coverage", codeCoverageTab);
            LOG.debug("DevTomcat: Code Coverage tab created");
        } catch (Exception e) {
            LOG.error("DevTomcat: Failed to create Code Coverage tab", e);
            tabbedPane.addTab("Code Coverage", createErrorPanel("Code Coverage tab error: " + e.getMessage()));
        }
    }

    private JPanel createErrorPanel(String errorMessage) {
        JPanel panel = new JPanel();
        panel.add(new JLabel("⚠ " + errorMessage));
        return panel;
    }

    public boolean isEventsSuppressed() {
        return isResetting.get() || isApplying.get() || isDisposing.get();
    }

    private String getConfigurationSummary(@NotNull TomcatRunConfiguration configuration) {
        return String.format("Server: %s, HTTP: %d, JMX: %s, Artifacts: %d, Logs: %d",
                configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "None",
                configuration.getHttpPort(),
                configuration.isJmxEnabled() ? configuration.getJmxPort() : "disabled",
                configuration.getConfigData().getDeploymentConfig().getArtifacts().size(),
                configuration.getLogFileConfigurations().size());
    }

    @Nullable
    private TomcatRunConfiguration createTemplateConfiguration() {
        try {
            TomcatRunConfigurationType type = ConfigurationTypeUtil.findConfigurationType(TomcatRunConfigurationType.class);
            ConfigurationFactory[] factories = type != null ? type.getConfigurationFactories() : null;
            if (factories != null && factories.length > 0) {
                // Use the factory so dynamic defaults and metadata are applied exactly as the IDE expects.
                return (TomcatRunConfiguration) factories[0].createTemplateConfiguration(project);
            }
            LOG.warn("DevTomcat: No configuration factory available for template configuration");
        } catch (Exception e) {
            LOG.warn("DevTomcat: Failed to create template configuration", e);
        }
        return null;
    }
    private void notifyError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                tabbedPane, message, "Configuration Error", JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void disposeEditor() {
        isDisposing.set(true);
        LOG.debug("DevTomcat: Disposing configuration editor");
        editorInitialized.set(false);
        isResetting.set(false);
        isApplying.set(false);
        currentConfiguration = null;
        serverTab = null;
        deploymentTab = null;
        logsTab = null;
        startupConnectionTab = null;
        codeCoverageTab = null;
        tabbedPane = null;
        super.disposeEditor();
    }

    @NotNull
    public Project getProject() {
        return project;
    }
}
