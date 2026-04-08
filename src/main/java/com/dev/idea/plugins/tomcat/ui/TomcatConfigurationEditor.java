package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.RuntimeEnvResolver;
import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
import com.dev.idea.plugins.tomcat.utils.ConfigExportImport;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.ui.deployment.ArtifactSelectionHandler;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentConfigurationPanel;
import com.dev.idea.plugins.tomcat.ui.deployment.DeploymentTableManager;
import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.impl.ConfigurationSettingsEditorWrapper;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TomcatConfigurationEditor extends SettingsEditor<TomcatRunConfiguration> {
    private static final Logger LOG = Logger.getInstance(TomcatConfigurationEditor.class);
    private final Project project;
    private TomcatRunConfiguration currentConfiguration;
    private final AtomicBoolean isResetting = new AtomicBoolean(false);
    private final AtomicBoolean isApplying = new AtomicBoolean(false);
    private final AtomicBoolean editorInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isDisposing = new AtomicBoolean(false);
    private final AtomicBoolean syncScheduled = new AtomicBoolean(false);
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
            // Refresh stale artifact references before UI population — catches renames
            // that occurred since the configuration was last serialized or the dialog was
            // last opened. Must run before syncBeforeLaunchWithDeployments() so the
            // Before Launch task matcher sees current artifact names.
            refreshArtifactReferences(currentConfiguration);
            // Sync on the clone, not the live config, to avoid side-effects during reset
            currentConfiguration.syncBeforeLaunchWithDeployments();
            if (editorInitialized.get() && tabbedPane != null) {
                reconcileTabsForMode();
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
            LOG.debug("DevTomcat: Configuration validation failed: " + e.getTitle());
            throw e;
        } catch (Exception e) {
            LOG.error("DevTomcat: Unexpected error applying configuration", e);
            throw new ConfigurationException("Failed to apply configuration: " + e.getLocalizedMessage());
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

        // Ensure computed env vars (JAVA_OPTS from VM options) are synced into
        // runner settings for all executor modes, regardless of tab visit order.
        TomcatConfigurationData configData = configuration.getConfigData();
        for (String mode : new String[]{"Run", "Debug", "Coverage", "Profile"}) {
            RuntimeEnvResolver.ensureComputedEnvVars(configData, mode);
        }
    }

    @Override
    @NotNull
    protected JComponent createEditor() {
        if (isDisposing.get()) return createErrorPanel("Editor is being disposed");

        try {
            LOG.info("DevTomcat: Creating configuration interface");
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
                    // so JAVA_OPTS reflects current VM options from Server tab
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
            installBeforeLaunchSyncOnShow(editorPanel);
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
        // Code Coverage is only applicable to local mode — remote server
        // runs independently and can't be instrumented from the plugin.
        if (!isRemoteMode()) {
            createCodeCoverageTab();
        }
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

            // Wire deployment changes to update browser URL on Server tab.
            // Guard with isEventsSuppressed() so bulk resetFrom() loading does NOT
            // overwrite the user's saved custom URL with auto-generated context paths.
            tableManager.setDeploymentChangeListener(contextPath -> {
                if (isEventsSuppressed()) return;
                if (serverTab != null) {
                    serverTab.updateBrowserUrlContext(contextPath);
                }
            });

            // Keep the Before Launch artifact task aligned with all configured deployments.
            // Use coalescing to avoid repeated sync during bulk resetFrom() operations.
            final ArtifactManager capturedArtifactMgr = artifactMgr;
            tableManager.setArtifactListChangeListener(() -> {
                if (isEventsSuppressed() || tabbedPane == null || capturedArtifactMgr == null) return;
                scheduleBeforeLaunchSync(capturedArtifactMgr);
            });
            tableManager.setSelectionChangeListener(artifact -> {
                if (isEventsSuppressed() || tabbedPane == null || capturedArtifactMgr == null) return;
                scheduleBeforeLaunchSync(capturedArtifactMgr);
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
        JBLabel label = new JBLabel("<html><b>Error:</b> " + errorMessage + "</html>");
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Reconciles stored deployment artifact references against the current project state.
     * Detects artifacts/modules that were renamed since the configuration was last saved
     * and updates the stored name/path to match. This prevents the Deployment tab from
     * showing orphaned references and ensures Before Launch tasks resolve correctly.
     *
     * <p>Runs inside {@code resetEditorFrom()} before tabs are populated, so the user
     * sees current artifact names from the moment the dialog opens. Failures are logged
     * and swallowed — a failed refresh must never block the editor from opening.
     */
    private void refreshArtifactReferences(@NotNull TomcatRunConfiguration config) {
        try {
            ArtifactReferenceRefresher.RefreshResult result =
                    ArtifactReferenceRefresher.refresh(config);
            if (result.hasUpdates()) {
                LOG.info("DevTomcat: Refreshed " + result.getUpdateCount() +
                        " stale artifact reference(s) in editor");
                for (ArtifactReferenceRefresher.RefreshAction action : result.getUpdatedActions()) {
                    LOG.info("DevTomcat:   " + action);
                }
            }
        } catch (Exception e) {
            LOG.debug("DevTomcat: Artifact reference refresh skipped in editor: " + e.getMessage());
        }
    }

    public boolean isEventsSuppressed() {
        return isResetting.get() || isApplying.get() || isDisposing.get();
    }

    /**
     * Returns {@code true} when the current configuration is in remote server mode.
     * Remote mode omits the Code Coverage tab and adjusts Startup/Connection content.
     */
    private boolean isRemoteMode() {
        return currentConfiguration != null && currentConfiguration.isRemoteMode();
    }

    /**
     * Ensures the tab set matches the current server mode.
     * Adds or removes the Code Coverage tab as needed.
     */
    private void reconcileTabsForMode() {
        if (tabbedPane == null) return;

        boolean remote = isRemoteMode();

        // Find existing Code Coverage tab index
        int coverageIdx = -1;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if ("Code Coverage".equals(tabbedPane.getTitleAt(i))) {
                coverageIdx = i;
                break;
            }
        }

        if (remote && coverageIdx >= 0) {
            // Remote mode: remove Code Coverage tab
            tabbedPane.removeTabAt(coverageIdx);
            codeCoverageTab = null;
            LOG.info("DevTomcat: Removed Code Coverage tab (remote mode)");
        } else if (!remote && coverageIdx < 0) {
            // Local mode: add Code Coverage tab back
            createCodeCoverageTab();
            if (codeCoverageTab != null && currentConfiguration != null) {
                codeCoverageTab.resetFrom(currentConfiguration);
            }
            LOG.info("DevTomcat: Added Code Coverage tab (local mode)");
        }

        // Update Startup/Connection tab for mode-specific content
        if (startupConnectionTab != null) {
            startupConnectionTab.setRemoteMode(remote);
        }
    }

    /**
     * Coalesces multiple rapid-fire listener invocations (e.g. during resetFrom() loading
     * 8 artifacts) into a single sync on the next EDT cycle.
     */
    private void scheduleBeforeLaunchSync(@NotNull ArtifactManager artifactManager) {
        if (!syncScheduled.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            syncScheduled.set(false);
            if (!isEventsSuppressed() && tabbedPane != null) {
                syncBeforeLaunchPanelWithSelectedDeployment(artifactManager);
            }
        });
    }

    /**
     * Replaces the live Before Launch artifact steps so they reflect all deployment
     * artifacts consolidated into a single Build Artifacts task, while preserving non-artifact tasks.
     * This matches IntelliJ Ultimate's behaviour of showing "Build N artifacts" as one entry.
     */
    private void syncBeforeLaunchPanelWithSelectedDeployment(@NotNull ArtifactManager artifactManager) {
        try {
            ConfigurationSettingsEditorWrapper wrapper = findEditorWrapper();
            if (wrapper == null) {
                // Wrapper unavailable — editor not yet attached to dialog hierarchy.
                // Defer one retry on the next EDT cycle when the component tree is realized.
                LOG.debug("DevTomcat: EditorWrapper not found, deferring sync");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isEventsSuppressed() || tabbedPane == null) return;
                    ConfigurationSettingsEditorWrapper retryWrapper = findEditorWrapper();
                    if (retryWrapper != null) {
                        doSyncBeforeLaunch(retryWrapper, artifactManager);
                    } else {
                        LOG.debug("DevTomcat: EditorWrapper still absent after defer — sync skipped");
                    }
                });
                return;
            }

            doSyncBeforeLaunch(wrapper, artifactManager);
        } catch (Exception e) {
            LOG.warn("DevTomcat: Error syncing Build Artifact steps", e);
        }
    }

    private void doSyncBeforeLaunch(@NotNull ConfigurationSettingsEditorWrapper wrapper,
                                     @NotNull ArtifactManager artifactManager) {
        List<BeforeRunTask<?>> existingSteps = new ArrayList<>(wrapper.getStepsBeforeLaunch());
        List<BeforeRunTask<?>> updatedSteps = new ArrayList<>();
        for (BeforeRunTask<?> task : existingSteps) {
            if (!(task instanceof BuildArtifactsBeforeRunTask)) {
                updatedSteps.add(task);
            }
        }

        // Ensure a "Build" (Make) task is always present — matches IntelliJ Ultimate's
        // Tomcat behaviour where the Before Launch list always starts with "Build".
        boolean hasMake = updatedSteps.stream()
                .anyMatch(t -> t instanceof CompileStepBeforeRun.MakeBeforeRunTask);
        if (!hasMake) {
            CompileStepBeforeRun.MakeBeforeRunTask makeTask =
                    new CompileStepBeforeRun.MakeBeforeRunTask();
            makeTask.setEnabled(true);
            updatedSteps.add(0, makeTask);
            LOG.info("DevTomcat: Added default Build (Make) task to Before Launch panel");
        }

        List<DeploymentArtifact> allDeployments = deploymentTableManager != null
                ? deploymentTableManager.getDeployments()
                : Collections.emptyList();

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

    /**
     * The configuration wrapper may not be discoverable during the first reset/sync pass
     * because the editor has not yet been attached to the dialog hierarchy.
     * Re-sync once the panel becomes visible so preloaded deployment artifacts are
     * reflected in the live Before Launch panel without requiring another user action.
     */
    private void installBeforeLaunchSyncOnShow(@NotNull JComponent editorPanel) {
        editorPanel.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (!editorPanel.isShowing() || isEventsSuppressed()) {
                return;
            }
            syncBeforeLaunchPanelWithSelectedDeployment();
        });
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
        return ContextPathUtils.extractBaseModuleName(name);
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
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isDisposing.get()) return;
            Messages.showErrorDialog(project, message, "Configuration Error");
        });
    }

    // =========================================================================
    // Export / Import
    // =========================================================================

    @NotNull
    private JPanel createExportImportToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(2)));
        toolbar.setBorder(JBUI.Borders.empty(0, 0, 2, 4));

        LinkLabel<Void> exportLink =
                new LinkLabel<>("Export", AllIcons.ToolbarDecorator.Export, (label, data) -> exportConfiguration());
        exportLink.setToolTipText("Export this configuration to an XML file for sharing");

        LinkLabel<Void> importLink =
                new LinkLabel<>("Import", AllIcons.ToolbarDecorator.Import, (label, data) -> importConfiguration());
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

            FileChooserDescriptor chooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false)
                    .withTitle("Export DevTomcat Configuration")
                    .withDescription("Choose directory to save configuration XML");
            VirtualFile dir = FileChooser.chooseFile(chooserDescriptor, project, null);
            if (dir != null) {
                String fileName = currentConfiguration.getName() + ".xml";
                File file = new File(dir.getPath(), fileName);
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
        if (deploymentTab != null) {
            deploymentTab.dispose();
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
