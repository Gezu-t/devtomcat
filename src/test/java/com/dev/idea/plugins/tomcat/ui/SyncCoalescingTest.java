package com.dev.idea.plugins.tomcat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the coalescing pattern used by
 * {@link TomcatConfigurationEditor#scheduleBeforeLaunchSync} to collapse
 * rapid-fire listener invocations into a single sync operation.
 *
 * <p>During {@code resetFrom()}, each of 8 artifact additions fires both
 * an artifact-list-changed and a selection-changed event (~19 invocations).
 * The coalescing guard ensures only one sync is scheduled on the EDT.
 */
@DisplayName("Sync Coalescing Pattern")
class SyncCoalescingTest {

    @Test
    @DisplayName("coalesces rapid-fire invocations into single scheduled sync")
    void coalescesMultipleInvocations() {
        AtomicBoolean syncScheduled = new AtomicBoolean(false);
        AtomicInteger scheduledCount = new AtomicInteger(0);

        // Simulate 19 rapid-fire listener invocations (8 artifacts × 2 events + clearAll + select)
        for (int i = 0; i < 19; i++) {
            if (syncScheduled.compareAndSet(false, true)) {
                // This would be invokeLater() in real code
                scheduledCount.incrementAndGet();
            }
        }

        assertEquals(1, scheduledCount.get(),
                "Only one sync should be scheduled despite 19 listener invocations");
    }

    @Test
    @DisplayName("allows re-scheduling after previous sync completes")
    void allowsReschedulingAfterCompletion() {
        AtomicBoolean syncScheduled = new AtomicBoolean(false);
        AtomicInteger scheduledCount = new AtomicInteger(0);

        // First batch: 8 rapid invocations
        for (int i = 0; i < 8; i++) {
            if (syncScheduled.compareAndSet(false, true)) {
                scheduledCount.incrementAndGet();
            }
        }

        // Simulate sync completion (invokeLater callback resets the flag)
        syncScheduled.set(false);

        // Second batch: 8 more rapid invocations (user adds more artifacts)
        for (int i = 0; i < 8; i++) {
            if (syncScheduled.compareAndSet(false, true)) {
                scheduledCount.incrementAndGet();
            }
        }

        assertEquals(2, scheduledCount.get(),
                "Two syncs should be scheduled: one per batch");
    }

    @Test
    @DisplayName("does not schedule when events are suppressed")
    void skipsWhenSuppressed() {
        AtomicBoolean syncScheduled = new AtomicBoolean(false);
        AtomicBoolean isResetting = new AtomicBoolean(true); // simulates reset in progress
        AtomicInteger scheduledCount = new AtomicInteger(0);

        for (int i = 0; i < 8; i++) {
            // Mirrors the guard in TomcatConfigurationEditor listener callbacks
            if (isResetting.get()) continue;
            if (syncScheduled.compareAndSet(false, true)) {
                scheduledCount.incrementAndGet();
            }
        }

        assertEquals(0, scheduledCount.get(),
                "No sync should be scheduled while resetting");
    }
}
