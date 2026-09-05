package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PhaseTimings")
class PhaseTimingsTest {

    @Test
    @DisplayName("renders phases in recording order with a thousands separator")
    void rendersInOrder() {
        PhaseTimings t = new PhaseTimings();
        t.record("ports", System.nanoTime() - 12_000_000L);
        t.record("deployments", System.nanoTime() - 1_900_000_000L);

        String s = t.summary();
        assertTrue(s.startsWith("ports 12 ms · deployments 1,9"), s);
        assertTrue(t.totalMs() >= 1_912, String.valueOf(t.totalMs()));
    }

    @Test
    @DisplayName("repeated phases accumulate")
    void repeatedPhasesAccumulate() {
        PhaseTimings t = new PhaseTimings();
        t.record("sync", System.nanoTime() - 100_000_000L);
        t.record("sync", System.nanoTime() - 100_000_000L);
        assertTrue(t.summary().matches("sync 20\\d ms"), t.summary());
    }

    @Test
    @DisplayName("empty when nothing was recorded")
    void emptyWhenNothingRecorded() {
        PhaseTimings t = new PhaseTimings();
        assertTrue(t.isEmpty());
        assertEquals("", t.summary());
        assertEquals(0, t.totalMs());
    }
}
