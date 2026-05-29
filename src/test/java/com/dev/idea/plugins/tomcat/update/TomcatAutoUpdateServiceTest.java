package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link TomcatAutoUpdateService#shouldRun} and {@link TomcatAutoUpdateService#isHotSyncAction}
 * — the pure policy that decides whether an <em>automatic</em> update may fire.
 *
 * <p>The whole point of the module-backed guard is that an automatic trigger
 * never burns a whole-project compile that produces nothing deployable: the
 * hot-sync actions are gated on a module-backed deployment, while redeploy and
 * restart run regardless because they re-deploy artifacts irrespective of
 * module backing. These are platform-free string predicates, so the contract is
 * locked down without a live {@code Project}.
 */
class TomcatAutoUpdateServiceTest {

    @Test
    @DisplayName("DO_NOTHING never runs, with or without a module-backed deployment")
    void doNothingNeverRuns() {
        assertFalse(TomcatAutoUpdateService.shouldRun(UpdateConfig.DO_NOTHING, true));
        assertFalse(TomcatAutoUpdateService.shouldRun(UpdateConfig.DO_NOTHING, false));
    }

    @Test
    @DisplayName("Hot-sync actions run only when a module backs a deployment")
    void hotSyncRunsOnlyWithModuleBackedDeployment() {
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES, true));
        assertFalse(TomcatAutoUpdateService.shouldRun(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES, false));
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.UPDATE_RESOURCES, true));
        assertFalse(TomcatAutoUpdateService.shouldRun(UpdateConfig.UPDATE_RESOURCES, false));
    }

    @Test
    @DisplayName("Redeploy and restart run regardless of module backing")
    void redeployAndRestartRunRegardlessOfModuleBacking() {
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.REDEPLOY, false));
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.REDEPLOY, true));
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.RESTART_SERVER, false));
        assertTrue(TomcatAutoUpdateService.shouldRun(UpdateConfig.RESTART_SERVER, true));
    }

    @Test
    @DisplayName("An unrecognised action is treated as non-hot-sync and runs")
    void unknownActionRuns() {
        assertFalse(TomcatAutoUpdateService.isHotSyncAction("something_else"));
        assertTrue(TomcatAutoUpdateService.shouldRun("something_else", false));
    }

    @Test
    @DisplayName("Only update-classes and update-resources classify as hot-sync")
    void isHotSyncActionClassification() {
        assertTrue(TomcatAutoUpdateService.isHotSyncAction(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES));
        assertTrue(TomcatAutoUpdateService.isHotSyncAction(UpdateConfig.UPDATE_RESOURCES));
        assertFalse(TomcatAutoUpdateService.isHotSyncAction(UpdateConfig.REDEPLOY));
        assertFalse(TomcatAutoUpdateService.isHotSyncAction(UpdateConfig.RESTART_SERVER));
        assertFalse(TomcatAutoUpdateService.isHotSyncAction(UpdateConfig.DO_NOTHING));
    }

    @Test
    @DisplayName("Suppression is active during the update and cleared once it returns")
    void suppressionHeldDuringBodyThenCleared() {
        AtomicBoolean flag = new AtomicBoolean(false);
        boolean[] observedDuring = {false};
        // The body stands in for the update saving documents; a reader inside it
        // models the save listener checking the flag mid-update.
        TomcatAutoUpdateService.runSuppressed(flag, () -> observedDuring[0] = flag.get());
        assertTrue(observedDuring[0], "the save listener must see suppression active mid-update");
        assertFalse(flag.get(), "suppression must be cleared once the update returns");
    }

    @Test
    @DisplayName("Suppression is cleared even when the update throws")
    void suppressionClearedOnThrow() {
        AtomicBoolean flag = new AtomicBoolean(false);
        assertThrows(IllegalStateException.class, () ->
                TomcatAutoUpdateService.runSuppressed(flag, () -> {
                    throw new IllegalStateException("update failed");
                }));
        assertFalse(flag.get(), "a failed update must not leave save-triggering permanently suppressed");
    }

    @Test
    @DisplayName("Suppression starts cleared")
    void suppressionStartsCleared() {
        AtomicBoolean flag = new AtomicBoolean(false);
        // Sanity guard against a future refactor that forgets to reset between runs.
        TomcatAutoUpdateService.runSuppressed(flag, () -> { /* no-op */ });
        assertFalse(flag.get());
    }
}
