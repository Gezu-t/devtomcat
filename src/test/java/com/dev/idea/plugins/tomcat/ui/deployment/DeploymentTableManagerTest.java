package com.dev.idea.plugins.tomcat.ui.deployment;

import com.dev.idea.plugins.tomcat.model.DeploymentRow;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeploymentTableManager")
class DeploymentTableManagerTest {

    private static DeploymentRow newRow(String name, String ctx) {
        // External kind — the only typed flavour constructible without a Project.
        return DeploymentRow.of(new ExternalFileDeployment(Path.of("/tmp/" + name), ctx, false));
    }

    @Test
    @DisplayName("updateSelectedDeployment fires deploymentChangeListener so browser URL follows edit-dialog context changes")
    void updateSelectedFiresDeploymentChangeListener() {
        // The Deployment tab has two paths that mutate a row's context:
        //   - inline context field (updateSelectedContext)   — fires deploymentChangeListener ✓
        //   - edit dialog (updateSelectedDeployment)         — previously did NOT fire it
        //
        // The asymmetry meant that editing a context path through the dialog
        // saved the new path but left the browser URL pinned to the old one.
        // This test pins the corrected behaviour.
        DeploymentTableManager manager = new DeploymentTableManager();

        DeploymentRow row = newRow("myapp", "/myapp");
        manager.addDeployment(row);

        AtomicReference<String> lastContextSeenByBrowserHook = new AtomicReference<>();
        manager.setDeploymentChangeListener(lastContextSeenByBrowserHook::set);

        // Simulate what the edit dialog does: mutate the selected row,
        // then ask the table manager to re-publish it.
        row.setContextPath("/myapp-renamed");
        manager.updateSelectedDeployment(row);

        String delivered = lastContextSeenByBrowserHook.get();
        assertNotNull(delivered, "deploymentChangeListener must fire after edit-dialog mutation");
        assertEquals("/myapp-renamed", delivered,
                "listener must receive the updated context path so the browser URL can follow");
    }

    // -------------------------------------------------------------------------
    // isContextPathTakenByOthers — duplicate-context guard for the edit dialog
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isContextPathTakenByOthers: rejects a context already used by a different row")
    void rejectsContextTakenByDifferentArtifact() {
        // Set up two rows with DIFFERENT context paths up-front, because
        // addDeployment() auto-bumps a duplicate (e.g. /shared → /shared-2).
        // We then probe row-b's context "except a" — that's the dialog's
        // real question: "if the user re-types b's context into a's editor,
        // is it taken?". The answer must be yes.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentRow a = newRow("a", "/myapp");
        DeploymentRow b = newRow("b", "/other");
        manager.addDeployment(a);
        manager.addDeployment(b);

        assertTrue(manager.isContextPathTakenByOthers("/other", a),
                "context held by another row must be reported as taken");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: ignores the row being edited (identity match)")
    void ignoresArtifactBeingEdited() {
        // Critical behaviour for the edit dialog: when the user opens the
        // dialog on row-a and re-types /myapp (its OWN context), we must
        // NOT report it as taken — otherwise editing without changing the
        // context would always fail validation.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentRow a = newRow("a", "/myapp");
        manager.addDeployment(a);

        assertFalse(manager.isContextPathTakenByOthers("/myapp", a),
                "the row being edited must NOT count itself as a collision");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: returns false when no other row uses the context")
    void returnsFalseWhenNoCollision() {
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentRow a = newRow("a", "/a");
        DeploymentRow b = newRow("b", "/b");
        manager.addDeployment(a);
        manager.addDeployment(b);

        assertFalse(manager.isContextPathTakenByOthers("/c", a),
                "a context not used by any row must not be reported as taken");
    }

    @Test
    @DisplayName("isContextPathTakenByOthers: identity-based exclusion is robust to mid-edit mutations")
    void identityBasedExclusionIsRobustToMutation() {
        // The edit dialog mutates the row in place. If the matching used
        // ".getContextPath()" of "except", a partial-edit mid-validation could
        // mismatch and falsely include the edited row as a collision.
        // Reference identity (==) sidesteps that entire class of bug.
        DeploymentTableManager manager = new DeploymentTableManager();
        DeploymentRow a = newRow("a", "/oldcontext");
        DeploymentRow b = newRow("b", "/other");
        manager.addDeployment(a);
        manager.addDeployment(b);

        // Simulate dialog mid-edit: row-a's context already mutated to
        // the new value. Asking whether the new value collides "except a"
        // must still ignore a (by reference) regardless of what a's stored
        // context says now.
        a.setContextPath("/newcontext");

        assertFalse(manager.isContextPathTakenByOthers("/newcontext", a),
                "identity-based exclusion must hold even when the row's stored context was mutated mid-dialog");
    }

    // -------------------------------------------------------------------------
    // addDeployment — collision-suffix candidates must be normalized
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addDeployment: three root-context rows get distinct auto-bumped contexts (no duplicate /-2)")
    void rootContextCollisionsProduceDistinctContexts() {
        // All three default to the root context "/". The collision auto-bump
        // strips the trailing slash to base "" and builds candidates like "-2".
        // Stored contexts are always slash-prefixed ("/-2"), so unless the
        // candidate is normalized before the taken-check, the raw "-2" never
        // matches "/-2" and the third row is handed the same "/-2" — a
        // duplicate that the launch validator then rejects.
        DeploymentTableManager manager = new DeploymentTableManager();
        manager.addDeployment(newRow("a", "/"));
        manager.addDeployment(newRow("b", "/"));
        manager.addDeployment(newRow("c", "/"));

        java.util.Set<String> contexts = new java.util.HashSet<>();
        for (DeploymentRow d : manager.getRows()) {
            contexts.add(d.getContextPath());
        }

        assertEquals(3, contexts.size(),
                "each root-defaulting row must receive a distinct, normalized context path");
    }
}
