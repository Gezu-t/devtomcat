package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("DeploymentTableManager")
class DeploymentTableManagerTest {

    private static DeploymentArtifact newArtifact(String name, String ctx) {
        DeploymentArtifact a = new DeploymentArtifact();
        a.setName(name);
        a.setPath("/tmp/" + name);
        a.setType(DeploymentArtifact.TYPE_WAR);
        a.setApplicationContext(ctx);
        a.setServerPath(ctx);
        return a;
    }

    @Test
    @DisplayName("updateSelectedDeployment fires deploymentChangeListener so browser URL follows edit-dialog context changes")
    void updateSelectedFiresDeploymentChangeListener() {
        // The Deployment tab has two paths that mutate an artifact's context:
        //   - inline context field (updateSelectedContext)   — fires deploymentChangeListener ✓
        //   - edit dialog (updateSelectedDeployment)         — previously fired ONLY selectionChangeListener
        //
        // The asymmetry meant that editing a context path through the dialog
        // saved the new path but left the browser URL pinned to the old one.
        // This test pins the corrected behaviour.
        DeploymentTableManager manager = new DeploymentTableManager();

        DeploymentArtifact artifact = newArtifact("myapp", "/myapp");
        manager.addDeployment(artifact);

        AtomicReference<String> lastContextSeenByBrowserHook = new AtomicReference<>();
        manager.setDeploymentChangeListener(lastContextSeenByBrowserHook::set);

        // Simulate what the edit dialog does: mutate the selected artifact,
        // then ask the table manager to re-publish it.
        artifact.setApplicationContext("/myapp-renamed");
        manager.updateSelectedDeployment(artifact);

        String delivered = lastContextSeenByBrowserHook.get();
        assertNotNull(delivered, "deploymentChangeListener must fire after edit-dialog mutation");
        assertEquals("/myapp-renamed", delivered,
                "listener must receive the updated context path so the browser URL can follow");
    }

    @Test
    @DisplayName("updateSelectedDeployment also keeps selectionChangeListener firing for Before Launch sync")
    void updateSelectedAlsoFiresSelectionListener() {
        DeploymentTableManager manager = new DeploymentTableManager();

        DeploymentArtifact artifact = newArtifact("app", "/app");
        manager.addDeployment(artifact);

        AtomicReference<DeploymentArtifact> seen = new AtomicReference<>();
        manager.setSelectionChangeListener(seen::set);

        artifact.setApplicationContext("/app2");
        manager.updateSelectedDeployment(artifact);

        // Before Launch sync keys on this listener; the fix must not remove it.
        assertNotNull(seen.get(),
                "selectionChangeListener must still fire after the fix so Before Launch stays in sync");
    }
}
