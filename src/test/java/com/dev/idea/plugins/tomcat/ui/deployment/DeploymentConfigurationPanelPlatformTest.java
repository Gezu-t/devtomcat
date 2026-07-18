package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.conf.TomcatRunConfigurationType;
import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Pins the Deployment tab's storage discipline across the resetFrom/applyTo
 * round trip: rows are seeded from the STORED deployment list, so an untouched
 * OK/Apply writes back value-identical entries. Seeding from the resolved view
 * instead would bake the resolver's read-only Community-edition fold (dangling
 * artifact→module) into storage on any Apply, permanently destroying artifact
 * provenance for entries the user never edited — exactly the mutation
 * {@code DeploymentResolver}'s never-mutates-storage contract forbids.
 */
public class DeploymentConfigurationPanelPlatformTest extends BasePlatformTestCase {

    private TomcatRunConfiguration createConfig(String name) {
        TomcatRunConfigurationType type = new TomcatRunConfigurationType();
        return new TomcatRunConfiguration(
                getProject(), type.getConfigurationFactories()[0], name);
    }

    private DeploymentConfigurationPanel createPanel(DeploymentTableManager tableManager) {
        ArtifactSelectionHandler handler =
                new ArtifactSelectionHandler(getProject(), null, tableManager);
        return new DeploymentConfigurationPanel(getProject(), tableManager, handler);
    }

    public void testUntouchedApplyKeepsFoldedEntryArtifactBacked() throws Exception {
        TomcatRunConfiguration cfg = createConfig("DeploymentRoundTrip");
        DeploymentConfig deploymentConfig = cfg.getConfigData().getDeploymentConfig();

        // Dangling artifact-backed entry whose stored name resolves to a project
        // module — the designed Community-edition state, which the resolver's
        // read-only view folds to module-backed.
        Deployment stored = ArtifactBackedDeployment.ofName(
                getProject(), getModule().getName(), "/app",
                "/projects/X/target/web-module", true);
        deploymentConfig.setDeployments(List.of(stored));

        // Sanity: the resolved view really does fold this entry.
        assertInstanceOf(deploymentConfig.getDeployments(getProject()).get(0),
                ModuleBackedDeployment.class);

        DeploymentTableManager tableManager = new DeploymentTableManager();
        DeploymentConfigurationPanel panel = createPanel(tableManager);
        try {
            panel.resetFrom(cfg);
            panel.applyTo(cfg);
        } finally {
            panel.dispose();
        }

        ArtifactBackedDeployment kept = assertInstanceOf(
                deploymentConfig.getDeployments().get(0), ArtifactBackedDeployment.class);
        assertEquals(getModule().getName(), kept.getArtifactName());
        assertEquals("/app", kept.getContextPath());
        assertEquals("/projects/X/target/web-module", kept.getLastKnownPath());
        assertTrue("last-known packaging must survive the round trip",
                kept.getLastKnownExploded());
    }

    public void testContextPathEditAppliesWithoutChangingEntryKind() throws Exception {
        TomcatRunConfiguration cfg = createConfig("DeploymentContextEdit");
        DeploymentConfig deploymentConfig = cfg.getConfigData().getDeploymentConfig();
        deploymentConfig.setDeployments(List.of(ArtifactBackedDeployment.ofName(
                getProject(), getModule().getName(), "/app",
                "/projects/X/target/web-module", true)));

        DeploymentTableManager tableManager = new DeploymentTableManager();
        DeploymentConfigurationPanel panel = createPanel(tableManager);
        try {
            panel.resetFrom(cfg);
            tableManager.getRows().get(0).setContextPath("/renamed");
            panel.applyTo(cfg);
        } finally {
            panel.dispose();
        }

        ArtifactBackedDeployment edited = assertInstanceOf(
                deploymentConfig.getDeployments().get(0), ArtifactBackedDeployment.class);
        assertEquals("/renamed", edited.getContextPath());
        assertEquals("/projects/X/target/web-module", edited.getLastKnownPath());
        assertTrue(edited.getLastKnownExploded());
    }
}
