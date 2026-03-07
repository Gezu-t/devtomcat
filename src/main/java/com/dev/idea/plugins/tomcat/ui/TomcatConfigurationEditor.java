package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.utils.ConfigExportImport;
import com.dev.idea.plugins.tomcat.ui.deployment.ArtifactSelectionHandler;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentConfigurationPanel;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentTableManager;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.impl.ConfigurationSettingsEditorWrapper;
import com.intellij.ide.DataManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class TomcatConfigurationEditor extends SettingsEditor<TomcatRunConfiguration> {
    private static final Logger LOG = Logger.getInstance(TomcatConfigurationEditor.class);
    private final Project project;
    private TomcatRunConfiguration currentConfiguration;
    private final AtomicBoolean isResetting = new AtomicBoolean(false);
    private final AtomicBoolean isApplying = new AtomicBoolean(false);
    private final AtomicBoolean editorInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isDisposing = new AtomicBoolean(false);
    private DeploymentTableManager deploymentTableManager;
    private ServerConfigurationTab serverTab;
    private DeploymentConfigurationPanel deploymentTab;
    private LogsConfigurationTab logsTab;
    private StartupConnectionTab startupConnectionTab;
    private CodeCoverageTab codeCoverageTab;
    private JBTabbedPane tabbedPane;

    public TomcatConfigurationEditor(@NotNull Project project) {
        this.project = project;
        LOG.info("DevTomcat: Initializing configuration editor for project: " + project.getName());
    }

    @Override
    protected void resetEditorFrom(@NotNull TomcatRunConfiguration configuration) {
        if (isResetting.get() || isApplying.get() || isDisposing.get()) return;
        if (!isResetting.compareAndSet(false, true)) return;

        try {
            LOG.debug("DevTomcat: Resetting editor from configuration: " + configuration.getName());
            this.currentConfiguration = (TomcatRunConfiguration) configuration.clone();
            // Sync on the clone, not the live config, to avoid side-effects during reset
            currentConfiguration.syncBeforeLaunchWithDeployments();
            if (editorInitialized.get() && tabbedPane != null) {
                resetAllTabs(currentConfiguration);
                syncBeforeLaunchPanelWithSelectedDeployment();
                LOG.info("DevTomcat: Configuration loaded successfully - " + getConfigurationSummary(currentConfiguration));
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
            // Do NOT sync Before Launch tasks here — BeforeRunStepsPanel.doApply()
            // runs AFTER applyEditorTo() and would overwrite our changes.
            LOG.debug("DevTomcat: Configuration applied successfully - " + getConfigurationSummary(configuration));
        } catch (ConfigurationException e) {
            LOG.debug("DevTomcat: Configuration validation failed: " + e.getMessage());
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
        if (deploymentTab != null && !deploymentTab.isConfigurationValid()) {
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
            LOG.info("DevTomcat: Creating 5-tab configuration interface");
            if (currentConfiguration == null) {
                currentConfiguration = createTemplateConfiguration();
            }
            tabbedPane = new JBTabbedPane();
            createAllTabs();

            // IntelliJ can call resetEditorFrom() before createEditor() depending on lifecycle/test harness.
            // Re-apply the current configuration after tabs exist so Deployment/Logs are not left at defaults.
            if (currentConfiguration != null) {
                resetAllTabs(currentConfiguration);
                syncBeforeLaunchPanelWithSelectedDeployment();
            }

            // Revalidate tab content on switch to ensure proper layout
            // and refresh computed env vars when Startup/Connection tab becomes visible
            tabbedPane.addChangeListener(e -> {
                if (tabbedPane == null || isDisposing.get()) return;
                int idx = tabbedPane.getSelectedIndex();
                if (idx >= 0) {
                    Component comp = tabbedPane.getComponentAt(idx);
                    if (comp instanceof JComponent jc) {
                        jc.revalidate();
                        jc.repaint();
                    }
                    // Refresh env vars when switching to Startup/Connection tab
                    // so CATALINA_OPTS reflects current VM options from Server tab
                    if (comp == startupConnectionTab && currentConfiguration != null) {
                        try {
                            // Apply server tab changes to get current VM options
                            if (serverTab != null) {
                                serverTab.applyTo(currentConfiguration);
                            }
                            startupConnectionTab.refreshComputedEnvVars(currentConfiguration);
                        } catch (Exception ex) {
                            LOG.debug("DevTomcat: Could not refresh env vars on tab switch", ex);
                        }
                    }
                    LOG.info("DevTomcat: Tab switched to [" + tabbedPane.getTitleAt(idx) + "] index=" + idx);
                }
            });

            editorInitialized.set(true);
            LOG.info("DevTomcat: Configuration interface created successfully with " + tabbedPane.getTabCount() + " tabs");

            // Wrap tabs in a panel with Export/Import toolbar
            JPanel editorPanel = new JPanel(new BorderLayout());
            editorPanel.add(createExportImportToolbar(), BorderLayout.NORTH);
            editorPanel.add(tabbedPane, BorderLayout.CENTER);
            return editorPanel;
        } catch (Throwable t) {
            LOG.error("DevTomcat: Critical error creating editor", t);
            return createErrorPanel("Failed to create configuration interface: " + t.getMessage());
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
            serverTab = new ServerConfigurationTab(project, currentConfiguration);
            tabbedPane.addTab("Server", serverTab);
            LOG.debug("DevTomcat: Server tab created with dynamic Local/Remote");
        } catch (Throwable t) {
            LOG.error("DevTomcat: Failed to create Server tab", t);
            tabbedPane.addTab("Server", createErrorPanel("Server tab error: " + t.getMessage()));
        }
    }

    private void createDeploymentTab() {
        try {
            DeploymentTableManager tableManager = new DeploymentTableManager();
            LOG.info("DevTomcat: DeploymentTableManager created");

            ArtifactManager artifactMgr = null;
            try {
                artifactMgr = ArtifactManager.getInstance(project);
                LOG.info("DevTomcat: ArtifactManager obtained: " + (artifactMgr != null));
            } catch (Throwable t) {
                LOG.warn("DevTomcat: ArtifactManager not available: " + t.getMessage());
            }

            ArtifactSelectionHandler selectionHandler = new ArtifactSelectionHandler(
                    project,
                    artifactMgr,
                    tableManager
            );
            deploymentTableManager = tableManager;
            LOG.info("DevTomcat: ArtifactSelectionHandler created");

            // Wire deployment changes to update browser URL on Server tab
            tableManager.setDeploymentChangeListener(contextPath -> {
                if (serverTab != null) {
                    serverTab.updateBrowserUrlContext(contextPath);
                }
            });

            // Keep the Before Launch artifact task aligned with all configured deployments.
            final ArtifactManager capturedArtifactMgr = artifactMgr;
            tableManager.setArtifactListChangeListener(() -> {
                if (isEventsSuppressed() || tabbedPane == null || capturedArtifactMgr == null) return;
                syncBeforeLaunchPanelWithSelectedDeployment(capturedArtifactMgr);
            });
            tableManager.setSelectionChangeListener(artifact -> {
                if (isEventsSuppressed() || tabbedPane == null || capturedArtifactMgr == null) return;
                syncBeforeLaunchPanelWithSelectedDeployment(capturedArtifactMgr);
            });

            deploymentTab = new DeploymentConfigurationPanel(
                    project,
                    tableManager,
                    selectionHandler
            );
            LOG.info("DevTomcat: DeploymentConfigurationPanel created");

            tabbedPane.addTab("Deployment", deploymentTab);
            LOG.info("DevTomcat: Deployment tab added to pane");
        } catch (Throwable t) {
            LOG.error("DevTomcat: Failed to create Deployment tab", t);
            tabbedPane.addTab("Deployment", createErrorPanel("Deployment tab error: " + t.getMessage()));
        }
    }

    private void createLogsTab() {
        try {
            LOG.info("DevTomcat: Creating Logs tab...");
            logsTab = new LogsConfigurationTab(project, null);
            LOG.info("DevTomcat: LogsConfigurationTab created");
            tabbedPane.addTab("Logs", logsTab);
            LOG.info("DevTomcat: Logs tab added to pane");
        } catch (Throwable t) {
            LOG.error("DevTomcat: Failed to create Logs tab", t);
            tabbedPane.addTab("Logs", createErrorPanel("Logs tab error: " + t.getMessage()));
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
        } catch (Throwable t) {
            LOG.error("DevTomcat: Failed to create Startup/Connection tab", t);
            tabbedPane.addTab("Startup/Connection", createErrorPanel("Startup/Connection tab error: " + t.getMessage()));
        }
    }

    private void createCodeCoverageTab() {
        try {
            codeCoverageTab = new CodeCoverageTab(project);
            tabbedPane.addTab("Code Coverage", codeCoverageTab);
            LOG.debug("DevTomcat: Code Coverage tab created");
        } catch (Throwable t) {
            LOG.error("DevTomcat: Failed to create Code Coverage tab", t);
            tabbedPane.addTab("Code Coverage", createErrorPanel("Code Coverage tab error: " + t.getMessage()));
        }
    }

    private JPanel createErrorPanel(String errorMessage) {
        JPanel panel = new JPanel(new BorderLayout());
        com.intellij.ui.components.JBLabel label = new com.intellij.ui.components.JBLabel("<html><b>Error:</b> " + errorMessage + "</html>");
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    public boolean isEventsSuppressed() {
        return isResetting.get() || isApplying.get() || isDisposing.get();
    }

    /**
     * Replaces the live Before Launch artifact steps so they reflect all deployment
     * artifacts consolidated into a single Build Artifacts task, while preserving non-artifact tasks.
     * This matches IntelliJ Ultimate's behaviour of showing "Build N artifacts" as one entry.
     */
    private void syncBeforeLaunchPanelWithSelectedDeployment(@NotNull ArtifactManager artifactManager) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ConfigurationSettingsEditorWrapper wrapper = findEditorWrapper();
                if (wrapper == null) {
                    LOG.warn("DevTomcat: ConfigurationSettingsEditorWrapper not found — cannot sync Build Artifact steps");
                    return;
                }

                java.util.List<com.intellij.execution.BeforeRunTask<?>> existingSteps =
                        new java.util.ArrayList<>(wrapper.getStepsBeforeLaunch());
                java.util.List<com.intellij.execution.BeforeRunTask<?>> updatedSteps = new java.util.ArrayList<>();
                for (com.intellij.execution.BeforeRunTask<?> task : existingSteps) {
                    if (!(task instanceof BuildArtifactsBeforeRunTask)) {
                        updatedSteps.add(task);
                    }
                }

                // Consolidate all deployment artifacts into a single BuildArtifactsBeforeRunTask
                java.util.List<DeploymentArtifact> allDeployments = deploymentTableManager != null
                        ? deploymentTableManager.getDeployments()
                        : java.util.Collections.emptyList();

                if (!allDeployments.isEmpty()) {
                    BuildArtifactsBeforeRunTask buildTask = new BuildArtifactsBeforeRunTask(project);
                    for (DeploymentArtifact deployment : allDeployments) {
                        Artifact matched = findMatchingArtifact(deployment, artifactManager);
                        if (matched != null) {
                            buildTask.addArtifact(matched);
                        }
                    }
                    if (!buildTask.getArtifactPointers().isEmpty()) {
                        buildTask.setEnabled(true);
                        updatedSteps.add(buildTask);
                        LOG.info("DevTomcat: Before Launch consolidated " +
                                buildTask.getArtifactPointers().size() + " artifact(s) into single Build task");
                    }
                }

                wrapper.replaceBeforeLaunchSteps(updatedSteps);
                wrapper.fireStepsBeforeRunChanged();
            } catch (Exception e) {
                LOG.warn("DevTomcat: Error syncing Build Artifact steps", e);
            }
        });
    }

    private void syncBeforeLaunchPanelWithSelectedDeployment() {
        if (tabbedPane == null) return;

        try {
            ArtifactManager artifactManager = ArtifactManager.getInstance(project);
            syncBeforeLaunchPanelWithSelectedDeployment(artifactManager);
        } catch (Throwable t) {
            LOG.debug("DevTomcat: ArtifactManager unavailable while syncing selected deployment", t);
        }
    }

    @Nullable
    private Artifact findMatchingArtifact(@Nullable DeploymentArtifact deployment,
                                          @NotNull ArtifactManager artifactManager) {
        if (deployment == null || deployment.getName().isEmpty()) {
            return null;
        }

        String deployName = deployment.getName();

        // 1. Exact case-insensitive match
        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (deployName.equalsIgnoreCase(artifact.getName())) {
                return artifact;
            }
        }

        // 2. Base module name match (e.g. "webapp-one_war_exploded" matches "webapp-one:war exploded")
        String deployBase = extractBaseModuleName(deployName);
        for (Artifact artifact : artifactManager.getArtifacts()) {
            if (deployBase.equals(extractBaseModuleName(artifact.getName()))) {
                return artifact;
            }
        }

        return null;
    }

    private static String extractBaseModuleName(String name) {
        return com.dev.idea.plugins.tomcat.utils.ContextPathUtils.extractBaseModuleName(name);
    }

    /**
     * Locates the {@link ConfigurationSettingsEditorWrapper} by walking up the
     * Swing component hierarchy and querying each ancestor's DataContext.
     */
    @Nullable
    private ConfigurationSettingsEditorWrapper findEditorWrapper() {
        if (tabbedPane == null) return null;

        // Walk up the component tree — the wrapper registers itself as a DataProvider
        // on a parent component above our tabbedPane
        Component comp = tabbedPane;
        while (comp != null) {
            try {
                ConfigurationSettingsEditorWrapper wrapper = ConfigurationSettingsEditorWrapper
                        .CONFIGURATION_EDITOR_KEY
                        .getData(DataManager.getInstance().getDataContext(comp));
                if (wrapper != null) return wrapper;
            } catch (Exception ignored) {
                // DataContext might not be available for some components
            }
            comp = comp.getParent();
        }
        return null;
    }

    private String getConfigurationSummary(@NotNull TomcatRunConfiguration configuration) {
        try {
            var tomcatInfo = configuration.getTomcatInfo();
            var httpPort = configuration.getHttpPort();
            return String.format("Server: %s, HTTP: %s, JMX: %s, Artifacts: %d, Logs: %d",
                    tomcatInfo != null ? tomcatInfo.getName() : "None",
                    httpPort != null ? httpPort : "N/A",
                    configuration.isJmxEnabled() ? String.valueOf(configuration.getJmxPort()) : "disabled",
                    configuration.getConfigData().getDeploymentConfig().getArtifacts().size(),
                    configuration.getLogFileConfigurations().size());
        } catch (Exception e) {
            return "summary unavailable";
        }
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
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Configuration Error"));
    }

    // =========================================================================
    // Export / Import
    // =========================================================================

    @NotNull
    private JPanel createExportImportToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(2)));
        toolbar.setBorder(JBUI.Borders.empty(0, 0, 2, 4));

        com.intellij.ui.components.labels.LinkLabel<Void> exportLink =
                new com.intellij.ui.components.labels.LinkLabel<>("Export", com.intellij.icons.AllIcons.ToolbarDecorator.Export, (label, data) -> exportConfiguration());
        exportLink.setToolTipText("Export this configuration to an XML file for sharing");

        com.intellij.ui.components.labels.LinkLabel<Void> importLink =
                new com.intellij.ui.components.labels.LinkLabel<>("Import", com.intellij.icons.AllIcons.ToolbarDecorator.Import, (label, data) -> importConfiguration());
        importLink.setToolTipText("Import configuration from an XML file");

        toolbar.add(exportLink);
        toolbar.add(importLink);
        return toolbar;
    }

    private void exportConfiguration() {
        if (currentConfiguration == null) {
            Messages.showWarningDialog(project, "No configuration to export.", "Export");
            return;
        }
        try {
            // Apply current editor state to a temp config so we export what the user sees
            TomcatRunConfiguration tempConfig = (TomcatRunConfiguration) currentConfiguration.clone();
            applyAllTabs(tempConfig);

            FileSaverDescriptor descriptor = new FileSaverDescriptor(
                    "Export DevTomcat Configuration", "Save configuration as XML", "xml");
            VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                    .createSaveFileDialog(descriptor, project)
                    .save(currentConfiguration.getName() + ".xml");
            if (wrapper != null) {
                File file = wrapper.getFile();
                ConfigExportImport.exportToFile(tempConfig.getConfigData(), file);
                Messages.showInfoMessage(project,
                        "Configuration exported to:\n" + file.getAbsolutePath(), "Export Successful");
                LOG.info("DevTomcat: Configuration exported to " + file.getAbsolutePath());
            }
        } catch (Exception ex) {
            LOG.warn("DevTomcat: Export failed", ex);
            Messages.showErrorDialog(project, "Export failed: " + ex.getMessage(), "Export Error");
        }
    }

    private void importConfiguration() {
        try {
            var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                    .withTitle("Import DevTomcat Configuration")
                    .withDescription("Select an XML configuration file exported from DevTomcat");
            VirtualFile[] files = com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null);
            if (files.length == 0) return;

            File file = new File(files[0].getPath());
            var importedData = ConfigExportImport.importFromFile(file);

            if (currentConfiguration == null) {
                currentConfiguration = createTemplateConfiguration();
            }
            if (currentConfiguration != null) {
                // Merge imported data into current configuration
                currentConfiguration.getConfigData().copyFrom(importedData);
                resetAllTabs(currentConfiguration);
                Messages.showInfoMessage(project,
                        "Configuration imported from:\n" + file.getAbsolutePath(), "Import Successful");
                LOG.info("DevTomcat: Configuration imported from " + file.getAbsolutePath());
            }
        } catch (Exception ex) {
            LOG.warn("DevTomcat: Import failed", ex);
            Messages.showErrorDialog(project, "Import failed: " + ex.getMessage(), "Import Error");
        }
    }

    @Override
    public void disposeEditor() {
        isDisposing.set(true);
        LOG.debug("DevTomcat: Disposing configuration editor");
        editorInitialized.set(false);
        isResetting.set(false);
        isApplying.set(false);
        currentConfiguration = null;
        if (serverTab != null) {
            serverTab.dispose();
            serverTab = null;
        }
        deploymentTab = null;
        logsTab = null;
        startupConnectionTab = null;
        codeCoverageTab = null;
        deploymentTableManager = null;
        tabbedPane = null;
        super.disposeEditor();
    }

    @NotNull
    public Project getProject() {
        return project;
    }
}
