package com.dev.idea.plugins.tomcat.ui;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed Tomcat Configuration Editor
 * Prevents infinite loops and provides stable 5-tab interface
 *
 * @author Gezahegn Lemma (Gezu)
 */
public class TomcatConfigurationEditor extends SettingsEditor<TomcatRunConfiguration> {

    private final Project project;
    private TomcatRunConfiguration currentConfiguration;

    // ENHANCED loop prevention with atomic flags
    private final AtomicBoolean isResetting = new AtomicBoolean(false);
    private final AtomicBoolean isApplying = new AtomicBoolean(false);
    private final AtomicBoolean editorInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isDisposing = new AtomicBoolean(false);

    // Tab panels
    private ServerConfigurationTab serverTab;
    private DeploymentConfigurationTab deploymentTab;
    private LogsConfigurationTab logsTab;
    private StartupConnectionTab startupConnectionTab;
    private CodeCoverageTab codeCoverageTab;

    // Main UI
    private JTabbedPane tabbedPane;

    public TomcatConfigurationEditor(@NotNull Project project) {
        this.project = project;
        System.out.println("DevTomcat: Using professional 5-tab interface");
        System.out.println("DevTomcat: TomcatConfigurationEditor created for project: " + project.getName());
    }

    @Override
    protected void resetEditorFrom(@NotNull TomcatRunConfiguration configuration) {
        // CRITICAL: Prevent all types of loops
        if (isResetting.get() || isApplying.get() || isDisposing.get()) {
            return;
        }

        // Double-check locking pattern for thread safety
        if (!isResetting.compareAndSet(false, true)) {
            return;
        }

        try {
            System.out.println("DevTomcat: Starting configuration reset");
            this.currentConfiguration = (TomcatRunConfiguration) configuration.clone(); // Work with clone to prevent interference

            // Only reset if editor is fully initialized
            if (editorInitialized.get() && tabbedPane != null) {
                resetAllTabs(configuration);
                System.out.println("DevTomcat: Reset all tabs from configuration successfully");
            } else {
                System.out.println("DevTomcat: Editor not initialized yet, configuration will be applied when tabs are created");
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error in resetEditorFrom: " + e.getMessage());
        } finally {
            isResetting.set(false);
        }
    }

    private void resetAllTabs(@NotNull TomcatRunConfiguration configuration) {
        try {
            if (serverTab != null) {
                serverTab.resetFrom(configuration);
                System.out.println("DevTomcat: Loading professional configuration into ServerTab");
                System.out.println("DevTomcat: Professional configuration loaded - Server: " +
                        (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "Apache Tomcat/11.0.8") +
                        ", HTTP Port: " + configuration.getPort() + ", JMX Port: " + configuration.getJmxPort());
            }

            if (deploymentTab != null) {
                deploymentTab.resetFrom(configuration);
                System.out.println("DevTomcat: Configuration loaded - " +
                        configuration.getDeploymentArtifacts().size() + " artifact deployments");
            }

            if (logsTab != null) {
                logsTab.resetFrom(configuration);
                System.out.println("DevTomcat: Reset logs configuration");
            }

            if (startupConnectionTab != null) {
                startupConnectionTab.resetFrom(configuration);
                System.out.println("DevTomcat: Reset startup/connection configuration");
            }

            if (codeCoverageTab != null) {
                codeCoverageTab.resetFrom(configuration);
                System.out.println("DevTomcat: Reset code coverage configuration");
            }
        } catch (Exception e) {
            System.err.println("DevTomcat: Error resetting tabs: " + e.getMessage());
        }
    }

    @Override
    protected void applyEditorTo(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        // CRITICAL: Prevent all types of loops
        if (isApplying.get() || isResetting.get() || isDisposing.get()) {
            return;
        }

        if (!editorInitialized.get() || tabbedPane == null) {
            return;
        }

        // Double-check locking pattern for thread safety
        if (!isApplying.compareAndSet(false, true)) {
            return;
        }

        try {
            System.out.println("DevTomcat: Starting configuration apply");
            applyAllTabs(configuration);
            System.out.println("DevTomcat: Applied all tabs to configuration successfully");
        } catch (ConfigurationException e) {
            System.err.println("DevTomcat: Configuration error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("DevTomcat: Unexpected error in apply: " + e.getMessage());
            throw new ConfigurationException("Failed to apply configuration: " + e.getMessage());
        } finally {
            isApplying.set(false);
        }
    }

    private void applyAllTabs(@NotNull TomcatRunConfiguration configuration) throws ConfigurationException {
        if (serverTab != null) {
            serverTab.applyTo(configuration);
            System.out.println("DevTomcat: Applying professional ServerTab configuration");
            System.out.println("DevTomcat: Professional configuration applied successfully - Server: " +
                    (configuration.getTomcatInfo() != null ? configuration.getTomcatInfo().getName() : "Apache Tomcat/11.0.8") +
                    ", Ports: HTTP=" + configuration.getPort() + ", JMX=" + configuration.getJmxPort());
            System.out.println("DevTomcat: Applied professional ServerTab configuration");
        }

        if (deploymentTab != null) {
            deploymentTab.applyTo(configuration);
            System.out.println("DevTomcat: Applied artifact deployment configuration - " +
                    configuration.getDeploymentArtifacts().size() + " artifacts");
        }

        if (logsTab != null) {
            logsTab.applyTo(configuration);
            int activeCount = (int) configuration.getLogFileConfigurations().stream().mapToLong(log -> log.isActive() ? 1 : 0).sum();
            System.out.println("DevTomcat: Applied " + configuration.getLogFileConfigurations().size() +
                    " log configurations (" + activeCount + " active)");
        }

        if (startupConnectionTab != null) {
            startupConnectionTab.applyTo(configuration);
            System.out.println("DevTomcat: Applied startup/connection configuration with " +
                    configuration.getEnvironmentVariables().size() + " environment variables");
        }

        if (codeCoverageTab != null) {
            codeCoverageTab.applyTo(configuration);
            System.out.println("DevTomcat: Applied code coverage patterns - 0 include, 0 exclude");
        }
    }

    @Override
    protected @NotNull JComponent createEditor() {
        if (isDisposing.get()) {
            return new JPanel();
        }

        try {
            System.out.println("DevTomcat: Creating professional 5-tab interface");

            // Create main tabbed pane
            tabbedPane = new JTabbedPane();

            // Create all tabs with proper error handling
            createAllTabs();

            // Mark as initialized AFTER all tabs are created
            editorInitialized.set(true);

            // Apply delayed configuration if available
            if (currentConfiguration != null) {
                System.out.println("DevTomcat: Applying delayed configuration to newly created tabs");
                SwingUtilities.invokeLater(() -> {
                    if (!isDisposing.get()) {
                        resetEditorFrom(currentConfiguration);
                    }
                });
            }

            System.out.println("DevTomcat: Created professional 5-tab interface");
            return tabbedPane;

        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating editor: " + e.getMessage());
            e.printStackTrace();

            // Return error panel instead of empty panel
            JPanel errorPanel = new JPanel();
            errorPanel.add(new JLabel("Error creating configuration interface: " + e.getMessage()));
            return errorPanel;
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
            serverTab = new ServerConfigurationTab(project);
            tabbedPane.addTab("Server", serverTab);
            System.out.println("DevTomcat: Added complete Server tab");
        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating Server tab: " + e.getMessage());
            tabbedPane.addTab("Server", createErrorPanel("Server tab error: " + e.getMessage()));
        }
    }

    private void createDeploymentTab() {
        try {
            deploymentTab = new DeploymentConfigurationTab(project);
            tabbedPane.addTab("Deployment", deploymentTab);
            System.out.println("DevTomcat: Added complete Deployment tab with loop prevention");
        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating Deployment tab: " + e.getMessage());
            tabbedPane.addTab("Deployment", createErrorPanel("Deployment tab error: " + e.getMessage()));
        }
    }

    private void createLogsTab() {
        try {
            logsTab = new LogsConfigurationTab(project, currentConfiguration);
            tabbedPane.addTab("Logs", logsTab);
            System.out.println("DevTomcat: Added complete Logs tab");
        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating Logs tab: " + e.getMessage());
            tabbedPane.addTab("Logs", createErrorPanel("Logs tab error: " + e.getMessage()));
        }
    }

    private void createStartupConnectionTab() {
        try {
            startupConnectionTab = new StartupConnectionTab(project, currentConfiguration);
            tabbedPane.addTab("Startup/Connection", startupConnectionTab);
            System.out.println("DevTomcat: Added complete Startup/Connection tab");
        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating Startup/Connection tab: " + e.getMessage());
            tabbedPane.addTab("Startup/Connection", createErrorPanel("Startup/Connection tab error: " + e.getMessage()));
        }
    }

    private void createCodeCoverageTab() {
        try {
            codeCoverageTab = new CodeCoverageTab(project);
            tabbedPane.addTab("Code Coverage", codeCoverageTab);
            System.out.println("DevTomcat: Added complete Code Coverage tab");
        } catch (Exception e) {
            System.err.println("DevTomcat: Error creating Code Coverage tab: " + e.getMessage());
            tabbedPane.addTab("Code Coverage", createErrorPanel("Code Coverage tab error: " + e.getMessage()));
        }
    }

    private JPanel createErrorPanel(String errorMessage) {
        JPanel panel = new JPanel();
        panel.add(new JLabel("Error: " + errorMessage));
        return panel;
    }

    // === PUBLIC API FOR LOOP PREVENTION ===

    public boolean isCurrentlyResetting() {
        return isResetting.get();
    }

    public boolean isCurrentlyApplying() {
        return isApplying.get();
    }

    public boolean isEventsSuppressed() {
        return isResetting.get() || isApplying.get() || isDisposing.get();
    }

    // === CLEANUP ===

    @Override
    public void disposeEditor() {
        isDisposing.set(true);
        System.out.println("DevTomcat: Disposing configuration editor");

        // Reset all flags
        editorInitialized.set(false);
        isResetting.set(false);
        isApplying.set(false);

        // Clear references
        currentConfiguration = null;
        serverTab = null;
        deploymentTab = null;
        logsTab = null;
        startupConnectionTab = null;
        codeCoverageTab = null;
        tabbedPane = null;

        super.disposeEditor();
    }
}