package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behavioral contract for {@link TomcatReadActions}, exercised without a
 * platform fixture. The helpers must work in BOTH environments: under a real
 * IntelliJ Application they take a read action; with no Application (plain
 * unit tests, like this one when run in isolation) they run the computable
 * directly instead of failing on a missing platform. The assertions are
 * branch-agnostic — they hold whichever environment the test JVM provides.
 */
@DisplayName("TomcatReadActions")
class TomcatReadActionsTest {

    @Test
    @DisplayName("compute returns the computable's value")
    void computeReturnsValue() {
        assertEquals("result", TomcatReadActions.compute(() -> "result"));
    }

    @Test
    @DisplayName("compute propagates unchecked exceptions unchanged")
    void computePropagatesUnchecked() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TomcatReadActions.compute(() -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", thrown.getMessage());
    }
}
