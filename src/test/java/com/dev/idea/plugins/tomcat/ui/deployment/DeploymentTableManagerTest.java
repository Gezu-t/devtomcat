package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // -------------------------------------------------------------------------
    // isContextPathTakenByOthers — duplicate-context guard for the edit dialog
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isContextPathTakenByOthers: rejects a context already used by a different artifact")
    void rejectsContextTakenByDifferentArtifact() {
        // Set up two artifacts with DIFFERENT context paths up-front, because
        // addDeployment() auto-bumps a duplicate (e.g. /shared → /shared-2).
        // We then probe artifact-b's context "except a" — that's the dialog's
        // real question: "if the user re-types b's context into a's editor,
        // is it taken?". The answer must be yes.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentArtifact a = newArtifact("a", "/myapp");
        DeploymentArtifact b = newArtifact("b", "/other");
        manager.addDeployment(a);
        manager.addDeployment(b);

        assertTrue(manager.isContextPathTakenByOthers("/other", a),
                "context held by another artifact must be reported as taken");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: ignores the artifact being edited (identity match)")
    void ignoresArtifactBeingEdited() {
        // Critical behaviour for the edit dialog: when the user opens the
        // dialog on artifact-a and re-types /myapp (its OWN context), we must
        // NOT report it as taken — otherwise editing without changing the
        // context would always fail validation.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentArtifact a = newArtifact("a", "/myapp");
        manager.addDeployment(a);

        assertFalse(manager.isContextPathTakenByOthers("/myapp", a),
                "the artifact being edited must NOT count itself as a collision");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: returns false when no other artifact uses the context")
    void returnsFalseWhenNoCollision() {
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentArtifact a = newArtifact("a", "/a");
        DeploymentArtifact b = newArtifact("b", "/b");
        manager.addDeployment(a);
        manager.addDeployment(b);

        assertFalse(manager.isContextPathTakenByOthers("/c", a),
                "a context not used by any artifact must not be reported as taken");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: identity-based exclusion is robust to mid-edit mutations")
    void identityBasedExclusionIsRobustToMutation() {
        // The edit dialog mutates the artifact in place. If the matching used
        // ".getContextPath()" of "except", a partial-edit mid-validation could
        // mismatch and falsely include the edited artifact as a collision.
        // Reference identity (==) sidesteps that entire class of bug.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentArtifact a = newArtifact("a", "/oldcontext");
        DeploymentArtifact b = newArtifact("b", "/other");
        manager.addDeployment(a);
        manager.addDeployment(b);

        // Simulate dialog mid-edit: artifact-a's context already mutated to
        // the new value. Asking whether the new value collides "except a"
        // must still ignore a (by reference) regardless of what a's stored
        // context says now.
        a.setApplicationContext("/newcontext");

        assertFalse(manager.isContextPathTakenByOthers("/newcontext", a),
                "identity-based exclusion must hold even when the artifact's stored context was mutated mid-dialog");
    }
}
