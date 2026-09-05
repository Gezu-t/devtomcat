package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Pins the launch-preparation contract: the platform must NOT wrap
 * {@code createJavaParameters()} in a read action. Every model read on that
 * path takes its own short read action at its boundary instead, so the
 * filesystem work — the bulk of the time — never blocks IDE write actions.
 */
@DisplayName("TomcatCommandLineState read lock")
class TomcatCommandLineStateReadLockTest {

    @Test
    @DisplayName("launch preparation does not require the platform's read action")
    void preparationDoesNotHoldTheReadLock() {
        TomcatCommandLineState state = mock(TomcatCommandLineState.class, CALLS_REAL_METHODS);
        assertFalse(state.isReadActionRequired());
    }
}
