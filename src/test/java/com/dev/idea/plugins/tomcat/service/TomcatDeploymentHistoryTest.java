package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatDeploymentHistory")
class TomcatDeploymentHistoryTest {

    private TomcatDeploymentHistory history;

    @BeforeEach
    void setUp() {
        history = new TomcatDeploymentHistory();
        // Initialize with empty state
        history.loadState(new TomcatDeploymentHistory.HistoryState());
    }

    @Test
    @DisplayName("initial state has no entries")
    void initialEmpty() {
        assertTrue(history.getEntries().isEmpty());
    }

    @Test
    @DisplayName("startEntry creates entry with config name and timestamp")
    void startEntryCreatesEntry() {
        TomcatDeploymentHistory.HistoryEntry entry = history.startEntry("MyConfig");
        assertEquals("MyConfig", entry.configName);
        assertTrue(entry.timestampEpochMs > 0);
    }

    @Test
    @DisplayName("recordCompleted adds entry newest first")
    void recordCompletedAddsNewestFirst() {
        TomcatDeploymentHistory.HistoryEntry e1 = history.startEntry("Config1");
        e1.success = true;
        history.recordCompleted(e1);

        TomcatDeploymentHistory.HistoryEntry e2 = history.startEntry("Config2");
        e2.success = true;
        history.recordCompleted(e2);

        List<TomcatDeploymentHistory.HistoryEntry> entries = history.getEntries();
        assertEquals(2, entries.size());
        assertEquals("Config2", entries.get(0).configName);
        assertEquals("Config1", entries.get(1).configName);
    }

    @Test
    @DisplayName("max entries enforced at 50")
    void maxEntriesEnforced() {
        for (int i = 0; i < 60; i++) {
            TomcatDeploymentHistory.HistoryEntry e = history.startEntry("Config" + i);
            history.recordCompleted(e);
        }
        assertEquals(50, history.getEntries().size());
        // Newest should be Config59 (added last)
        assertEquals("Config59", history.getEntries().get(0).configName);
    }

    @Test
    @DisplayName("getEntriesForConfig filters by name")
    void getEntriesForConfig() {
        TomcatDeploymentHistory.HistoryEntry e1 = history.startEntry("A");
        history.recordCompleted(e1);
        TomcatDeploymentHistory.HistoryEntry e2 = history.startEntry("B");
        history.recordCompleted(e2);
        TomcatDeploymentHistory.HistoryEntry e3 = history.startEntry("A");
        history.recordCompleted(e3);

        List<TomcatDeploymentHistory.HistoryEntry> aEntries = history.getEntriesForConfig("A");
        assertEquals(2, aEntries.size());
        List<TomcatDeploymentHistory.HistoryEntry> bEntries = history.getEntriesForConfig("B");
        assertEquals(1, bEntries.size());
    }

    @Test
    @DisplayName("getLastEntry returns most recent for config")
    void getLastEntry() {
        TomcatDeploymentHistory.HistoryEntry e1 = history.startEntry("MyApp");
        e1.durationMs = 100;
        history.recordCompleted(e1);

        TomcatDeploymentHistory.HistoryEntry e2 = history.startEntry("MyApp");
        e2.durationMs = 200;
        history.recordCompleted(e2);

        TomcatDeploymentHistory.HistoryEntry last = history.getLastEntry("MyApp");
        assertNotNull(last);
        assertEquals(200, last.durationMs);
    }

    @Test
    @DisplayName("getLastEntry returns null for unknown config")
    void getLastEntryUnknown() {
        assertNull(history.getLastEntry("NonExistent"));
    }

    @Test
    @DisplayName("renameConfiguration migrates matching entries")
    void renameConfiguration() {
        TomcatDeploymentHistory.HistoryEntry old = history.startEntry("OldName");
        history.recordCompleted(old);
        TomcatDeploymentHistory.HistoryEntry other = history.startEntry("Other");
        history.recordCompleted(other);

        history.renameConfiguration("OldName", "NewName");

        assertNull(history.getLastEntry("OldName"));
        TomcatDeploymentHistory.HistoryEntry renamed = history.getLastEntry("NewName");
        assertNotNull(renamed);
        assertEquals("NewName", renamed.configName);
        assertNotNull(history.getLastEntry("Other"));
    }

    @Test
    @DisplayName("clearHistory removes all entries")
    void clearHistory() {
        history.recordCompleted(history.startEntry("A"));
        history.recordCompleted(history.startEntry("B"));
        assertFalse(history.getEntries().isEmpty());

        history.clearHistory();
        assertTrue(history.getEntries().isEmpty());
    }

    @Test
    @DisplayName("getConsecutiveFailures counts from newest")
    void consecutiveFailures() {
        TomcatDeploymentHistory.HistoryEntry e1 = history.startEntry("App");
        e1.success = true;
        history.recordCompleted(e1);

        TomcatDeploymentHistory.HistoryEntry e2 = history.startEntry("App");
        e2.success = false;
        history.recordCompleted(e2);

        TomcatDeploymentHistory.HistoryEntry e3 = history.startEntry("App");
        e3.success = false;
        history.recordCompleted(e3);

        assertEquals(2, history.getConsecutiveFailures("App"));
    }

    @Test
    @DisplayName("getConsecutiveFailures stops at first success")
    void consecutiveFailuresStopsAtSuccess() {
        TomcatDeploymentHistory.HistoryEntry e1 = history.startEntry("App");
        e1.success = false;
        history.recordCompleted(e1);

        TomcatDeploymentHistory.HistoryEntry e2 = history.startEntry("App");
        e2.success = true;
        history.recordCompleted(e2);

        TomcatDeploymentHistory.HistoryEntry e3 = history.startEntry("App");
        e3.success = false;
        history.recordCompleted(e3);

        // newest first: e3(fail), e2(success) → stops at e2, count=1
        assertEquals(1, history.getConsecutiveFailures("App"));
    }

    @Test
    @DisplayName("getConsecutiveFailures returns 0 for all success")
    void noConsecutiveFailures() {
        TomcatDeploymentHistory.HistoryEntry e = history.startEntry("App");
        e.success = true;
        history.recordCompleted(e);

        assertEquals(0, history.getConsecutiveFailures("App"));
    }

    @Test
    @DisplayName("HistoryEntry getSummary includes key fields")
    void entrySummaryFormat() {
        TomcatDeploymentHistory.HistoryEntry e = history.startEntry("TestApp");
        e.success = true;
        e.durationMs = 5000;
        e.errorCount = 0;
        e.warningCount = 2;
        e.artifactNames.add("myapp.war");

        String summary = e.getSummary();
        assertTrue(summary.contains("TestApp"));
        assertTrue(summary.contains("OK"));
        assertTrue(summary.contains("5000ms"));
        assertTrue(summary.contains("myapp.war"));
        assertTrue(summary.contains("0 errors"));
        assertTrue(summary.contains("2 warnings"));
    }

    @Test
    @DisplayName("HistoryEntry getSummary for failure")
    void entrySummaryFailure() {
        TomcatDeploymentHistory.HistoryEntry e = history.startEntry("FailApp");
        e.success = false;
        e.exitCode = 1;

        String summary = e.getSummary();
        assertTrue(summary.contains("FAILED"));
        assertTrue(summary.contains("exit 1"));
    }

    @Test
    @DisplayName("HistoryEntry getSummary distinguishes artifact deployment failures")
    void entrySummaryArtifactFailure() {
        TomcatDeploymentHistory.HistoryEntry e = history.startEntry("FailApp");
        e.success = false;
        e.artifactFailure = true;
        e.exitCode = 0;

        String summary = e.getSummary();
        assertTrue(summary.contains("FAILED"));
        assertTrue(summary.contains("artifact deployment"));
    }

    @Test
    @DisplayName("getState and loadState round-trip")
    void stateRoundTrip() {
        history.recordCompleted(history.startEntry("RoundTrip"));

        TomcatDeploymentHistory.HistoryState saved = history.getState();
        assertNotNull(saved);
        assertEquals(1, saved.entries.size());

        TomcatDeploymentHistory restored = new TomcatDeploymentHistory();
        restored.loadState(saved);
        assertEquals(1, restored.getEntries().size());
        assertEquals("RoundTrip", restored.getEntries().get(0).configName);
    }

    @Test
    @DisplayName("getEntries returns unmodifiable list")
    void entriesUnmodifiable() {
        history.recordCompleted(history.startEntry("X"));
        assertThrows(UnsupportedOperationException.class,
                () -> history.getEntries().add(new TomcatDeploymentHistory.HistoryEntry()));
    }
}
