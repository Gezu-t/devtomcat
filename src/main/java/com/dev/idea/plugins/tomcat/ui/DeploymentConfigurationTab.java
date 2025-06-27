package com.dev.idea.plugins.tomcat.ui;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.ui.deployment.*;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Professional IntelliJ Artifact-Integrated Deployment Configuration Tab
 * Refactored for professional maintainability and separation of concerns
 *
 * This main class coordinates between different components while keeping
 * the codebase clean and maintainable.
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin - Ultimate Integration
 */
public class DeploymentConfigurationTab extends JPanel {

    // Core components
    private final Project project;
    private final ArtifactManager artifactManager;

    // Delegated managers
    private final DeploymentTableManager tableManager;
    private final ArtifactSelectionHandler selectionHandler;
    private final DeploymentConfigurationPanel configPanel;

    // Parent coordination
    private TomcatConfigurationEditor parentEditor;

    // Synchronization state
    private volatile boolean isResetting = false;
    private volatile boolean isApplying = false;

    public DeploymentConfigurationTab(@NotNull Project project) {
        this.project = project;
        this.artifactManager = initializeArtifactManager();

        // Initialize managers with delegation pattern
        this.tableManager = new DeploymentTableManager();
        this.selectionHandler = new ArtifactSelectionHandler(project, artifactManager, tableManager);
        this.configPanel = new DeploymentConfigurationPanel(project, tableManager, selectionHandler);

        initializeUI();

        // Load default configuration if not in operation
        if (!isInGlobalOperation()) {
            loadDefaultConfiguration();
        }

        System.out.println("DevTomcat: Professional Deployment tab initialized with delegation pattern");
    }

    /**
     * Initialize ArtifactManager with proper error handling
     */
    private ArtifactManager initializeArtifactManager() {
        try {
            ArtifactManager manager = ArtifactManager.getInstance(project);
            if (manager != null) {
                System.out.println("DevTomcat: ArtifactManager initialized successfully");
            } else {
                System.err.println("DevTomcat: ArtifactManager.getInstance() returned null");
            }
            return manager;
        } catch (Exception e) {
            System.err.println("DevTomcat: Error initializing ArtifactManager: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Set parent editor for loop prevention coordination
     */
    public void setParentEditor(TomcatConfigurationEditor parentEditor) {
        this.parentEditor = parentEditor;
        configPanel.setParentEditor(parentEditor);
        System.out.println("DevTomcat: DeploymentTab linked to parent editor");
    }

    /**
     * Check if any global operation is in progress
     */
    private boolean isInGlobalOperation() {
        boolean parentSuppressed = (parentEditor != null && parentEditor.isEventsSuppressed());
        boolean localSuppressed = isResetting || isApplying;
        return parentSuppressed || localSuppressed;
    }

    /**
     * Initialize UI using the configuration panel
     */
    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));

        // Add the configuration panel which handles all UI
        add(configPanel, BorderLayout.CENTER);
    }

    /**
     * Load default configuration using selection handler
     */
    private void loadDefaultConfiguration() {
        try {
            System.out.println("DevTomcat: Loading default deployment configuration...");

            if (artifactManager == null) {
                System.err.println("DevTomcat: ArtifactManager not available - manual configuration required");
                return;
            }

            // Delegate to selection handler for auto-detection
            List<Artifact> webArtifacts = selectionHandler.detectWebArtifacts();

            if (!webArtifacts.isEmpty()) {
                // Add first web artifact automatically
                Artifact firstArtifact = webArtifacts.get(0);
                selectionHandler.addArtifact(firstArtifact);

                // Set application context
                String contextPath = selectionHandler.generateContextPath(
                        firstArtifact.getName(),
                        firstArtifact.getArtifactType()
                );
                configPanel.setApplicationContext(contextPath);

                System.out.println("DevTomcat: Auto-loaded artifact: " + firstArtifact.getName());
            } else {
                System.out.println("DevTomcat: No web artifacts found for auto-loading");
            }

        } catch (Exception e) {
            System.err.println("DevTomcat: Error loading default configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reset configuration from TomcatRunConfiguration
     */
    public void resetFrom(@NotNull TomcatRunConfiguration config) {
        if (isResetting || isApplying || isInGlobalOperation()) {
            System.out.println("DevTomcat: Skipping recursive resetFrom");
            return;
        }

        isResetting = true;

        try {
            System.out.println("DevTomcat: Resetting deployment configuration");

            // Clear existing data
            tableManager.clearAll();

            // Load artifacts from configuration
            List<DeploymentArtifact> configArtifacts = config.getDeploymentArtifacts();
            if (configArtifacts != null && !configArtifacts.isEmpty()) {
                System.out.println("DevTomcat: Loading " + configArtifacts.size() + " artifacts from config");

                for (DeploymentArtifact artifact : configArtifacts) {
                    DeploymentArtifact deployment = new DeploymentArtifact(
                            selectionHandler.findArtifactByName(artifact.getName()),
                            artifact.getName(),
                            artifact.getType(),
                            artifact.getServerPath(),
                            artifact.getLocalPath()
                    );
                    tableManager.addDeployment(deployment);
                }
            } else {
                // Auto-detect if no configured artifacts
                if (!isInGlobalOperation()) {
                    loadDefaultConfiguration();
                }
            }

            // Reset UI components
            configPanel.resetFrom(config);

            System.out.println("DevTomcat: Reset completed - " + tableManager.getDeploymentCount() + " deployments");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error during reset: " + e.getMessage());
            e.printStackTrace();
        } finally {
            isResetting = false;
        }
    }

    /**
     * Apply configuration to TomcatRunConfiguration
     */
    public void applyTo(@NotNull TomcatRunConfiguration config) throws ConfigurationException {
        if (isApplying || isResetting || isInGlobalOperation()) {
            System.out.println("DevTomcat: Skipping recursive applyTo");
            return;
        }

        isApplying = true;

        try {
            System.out.println("DevTomcat: Applying deployment configuration");

            // Apply UI settings
            configPanel.applyTo(config);

            // Apply deployment artifacts
            List<DeploymentArtifact> configArtifacts =
                    tableManager.getDeployments().stream()
                            .map(deployment -> new DeploymentArtifact(
                                    deployment.getDisplayName(),
                                    deployment.getType(),
                                    deployment.getServerPath(),
                                    deployment.getLocalPath()
                            ))
                            .collect(java.util.stream.Collectors.toList());

            config.setDeploymentArtifacts(configArtifacts);

            // Apply primary artifact settings
            if (!configArtifacts.isEmpty()) {
                DeploymentArtifact primary = configArtifacts.get(0);
                config.setDocBase(primary.getLocalPath());
                // Note: Context path is set by configPanel.applyTo()
            }

            System.out.println("DevTomcat: Apply completed - " + configArtifacts.size() + " artifacts");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error during apply: " + e.getMessage());
            throw new ConfigurationException("Failed to apply deployment configuration: " + e.getMessage());
        } finally {
            isApplying = false;
        }
    }
}