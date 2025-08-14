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
 * DevTomcat Deployment Configuration Tab - Corrected Version
 * Professional deployment management for Apache Tomcat
 *
 * This version works with the existing DeploymentArtifact model
 * that has serverPath, localPath, and applicationContext fields.
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 */
public class DeploymentConfigurationTab extends JPanel {

    // Core components
    private final Project project;
    private final ArtifactManager artifactManager;

    // UI Components
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

        // Initialize managers with clear responsibilities
        this.tableManager = new DeploymentTableManager();
        this.selectionHandler = new ArtifactSelectionHandler(project, artifactManager, tableManager);
        this.configPanel = new DeploymentConfigurationPanel(project, tableManager, selectionHandler);

        initializeUI();

        // Load default configuration if available
        if (!isInGlobalOperation()) {
            loadDefaultConfiguration();
        }

        System.out.println("DevTomcat: Deployment tab initialized");
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
     * Set parent editor for coordination
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
     * Initialize UI components - Simplified layout
     */
    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(5));

        // Main content is just the configuration panel
        // It contains the table, toolbar, and bottom options
        add(configPanel, BorderLayout.CENTER);
    }

    /**
     * Load default configuration
     */
    private void loadDefaultConfiguration() {
        try {
            System.out.println("DevTomcat: Loading default deployment configuration...");

            if (artifactManager == null) {
                System.err.println("DevTomcat: ArtifactManager not available - manual configuration required");
                return;
            }

            // Auto-detect web artifacts
            List<Artifact> webArtifacts = selectionHandler.detectWebArtifacts();

            if (!webArtifacts.isEmpty()) {
                // Add first web artifact automatically with generated context
                Artifact firstArtifact = webArtifacts.get(0);
                selectionHandler.addArtifact(firstArtifact);
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

                for (DeploymentArtifact savedArtifact : configArtifacts) {
                    // Try to find the IntelliJ artifact
                    Artifact intellijArtifact = selectionHandler.findArtifactByName(savedArtifact.getName());

                    DeploymentArtifact deployment;
                    if (intellijArtifact != null) {
                        // Create from IntelliJ artifact preserving saved values
                        deployment = DeploymentArtifact.fromIntellijArtifact(
                                intellijArtifact,
                                savedArtifact.getApplicationContext()
                        );
                        // Preserve other saved values
                        deployment.setServerPath(savedArtifact.getServerPath());
                        deployment.setLocalPath(savedArtifact.getLocalPath());
                        deployment.setDeployed(savedArtifact.isDeployed());
                        deployment.setUsingDefaultContext(savedArtifact.isUsingDefaultContext());
                    } else {
                        // Use saved data (external source or missing artifact)
                        deployment = savedArtifact.clone();
                    }

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

            // Get deployments from table (includes any inline edits)
            List<DeploymentArtifact> deployments = tableManager.getDeployments();

            // Save to configuration
            config.setDeploymentArtifacts(deployments);

            // Set primary context path and docBase for backward compatibility
            if (!deployments.isEmpty()) {
                DeploymentArtifact primary = deployments.get(0);
                config.setContextPath(primary.getApplicationContext());
                config.setDocBase(primary.getLocalPath());
            } else {
                config.setContextPath("/");
                config.setDocBase("");
            }

            System.out.println("DevTomcat: Apply completed - " + deployments.size() + " artifacts");

        } catch (Exception e) {
            System.err.println("DevTomcat: Error during apply: " + e.getMessage());
            throw new ConfigurationException("Failed to apply deployment configuration: " + e.getMessage());
        } finally {
            isApplying = false;
        }
    }

    /**
     * Validate the deployment configuration
     */
    public void validateConfiguration() throws ConfigurationException {
        // Validate each deployment
        List<DeploymentArtifact> deployments = tableManager.getDeployments();

        if (deployments.isEmpty()) {
            throw new ConfigurationException("At least one deployment artifact must be configured");
        }

        // Check for duplicate contexts
        for (int i = 0; i < deployments.size(); i++) {
            String context1 = deployments.get(i).getApplicationContext();

            // Validate context path format
            if (!isValidContextPath(context1)) {
                throw new ConfigurationException(
                        "Invalid context path '" + context1 + "' for artifact: " + deployments.get(i).getName()
                );
            }

            // Check for duplicates
            for (int j = i + 1; j < deployments.size(); j++) {
                String context2 = deployments.get(j).getApplicationContext();
                if (context1.equals(context2)) {
                    throw new ConfigurationException(
                            "Duplicate context path '" + context1 + "' for artifacts: " +
                                    deployments.get(i).getName() + " and " + deployments.get(j).getName()
                    );
                }
            }
        }

        // Validate each artifact
        for (DeploymentArtifact deployment : deployments) {
            try {
                deployment.validate();
            } catch (IllegalStateException e) {
                throw new ConfigurationException(
                        "Invalid deployment configuration for " + deployment.getName() + ": " + e.getMessage()
                );
            }
        }
    }

    /**
     * Validate context path
     */
    private boolean isValidContextPath(String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }

        if (!context.equals("/") && !context.startsWith("/")) {
            return false;
        }

        // Check for valid URL characters
        return context.matches("^/[a-zA-Z0-9\\-_.~!$&'()*+,;=:@/]*$");
    }
}