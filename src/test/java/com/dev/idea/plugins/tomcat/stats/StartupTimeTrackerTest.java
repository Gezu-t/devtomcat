package com.dev.idea.plugins.tomcat.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StartupTimeTracker")
class StartupTimeTrackerTest {

    private StartupTimeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new StartupTimeTracker();
        tracker.loadState(new StartupTimeTracker.State());
    }

    // =========================================================================
    // Recording
    // =========================================================================

    @Nested
    @DisplayName("Recording startup times")
    class Recording {

        @Test
        @DisplayName("records a single startup time")
        void recordSingle() {
            tracker.recordStartupTime("MyApp", 3000);
            assertEquals(3000, tracker.getLastStartupTime("MyApp"));
            assertEquals(1, tracker.getRunCount("MyApp"));
        }

        @Test
        @DisplayName("records multiple startup times")
        void recordMultiple() {
            tracker.recordStartupTime("MyApp", 3000);
            tracker.recordStartupTime("MyApp", 3500);
            tracker.recordStartupTime("MyApp", 2800);
            assertEquals(2800, tracker.getLastStartupTime("MyApp"));
            assertEquals(3, tracker.getRunCount("MyApp"));
        }

        @Test
        @DisplayName("negative times are ignored")
        void negativeIgnored() {
            tracker.recordStartupTime("MyApp", -100);
            assertEquals(-1, tracker.getLastStartupTime("MyApp"));
            assertEquals(0, tracker.getRunCount("MyApp"));
        }

        @Test
        @DisplayName("different configs are tracked separately")
        void separateConfigs() {
            tracker.recordStartupTime("App1", 1000);
            tracker.recordStartupTime("App2", 2000);
            assertEquals(1000, tracker.getLastStartupTime("App1"));
            assertEquals(2000, tracker.getLastStartupTime("App2"));
        }
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @BeforeEach
        void addData() {
            tracker.recordStartupTime("MyApp", 3000);
            tracker.recordStartupTime("MyApp", 4000);
            tracker.recordStartupTime("MyApp", 2000);
        }

        @Test
        @DisplayName("average startup time calculates correctly")
        void averageTime() {
            assertEquals(3000, tracker.getAverageStartupTime("MyApp"));
        }

        @Test
        @DisplayName("fastest startup time is minimum")
        void fastestTime() {
            assertEquals(2000, tracker.getFastestStartupTime("MyApp"));
        }

        @Test
        @DisplayName("previous startup time returns second-to-last")
        void previousTime() {
            assertEquals(4000, tracker.getPreviousStartupTime("MyApp"));
        }

        @Test
        @DisplayName("unknown config returns -1 for all stats")
        void unknownConfig() {
            assertEquals(-1, tracker.getLastStartupTime("Unknown"));
            assertEquals(-1, tracker.getAverageStartupTime("Unknown"));
            assertEquals(-1, tracker.getFastestStartupTime("Unknown"));
            assertEquals(-1, tracker.getPreviousStartupTime("Unknown"));
            assertEquals(0, tracker.getRunCount("Unknown"));
        }
    }

    // =========================================================================
    // History
    // =========================================================================

    @Nested
    @DisplayName("Startup history")
    class History {

        @Test
        @DisplayName("returns empty list for unknown config")
        void emptyHistory() {
            List<Long> history = tracker.getStartupHistory("Unknown");
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("history preserves order (oldest first)")
        void orderedHistory() {
            tracker.recordStartupTime("MyApp", 1000);
            tracker.recordStartupTime("MyApp", 2000);
            tracker.recordStartupTime("MyApp", 3000);
            List<Long> history = tracker.getStartupHistory("MyApp");
            assertEquals(List.of(1000L, 2000L, 3000L), history);
        }

        @Test
        @DisplayName("history is limited to max size")
        void limitedHistory() {
            for (int i = 0; i < 25; i++) {
                tracker.recordStartupTime("MyApp", i * 100L);
            }
            // Max history is 20
            assertEquals(20, tracker.getRunCount("MyApp"));
        }
    }

    // =========================================================================
    // Comparison formatting
    // =========================================================================

    @Nested
    @DisplayName("Comparison formatting")
    class Formatting {

        @Test
        @DisplayName("first run returns first-run message")
        void firstRun() {
            tracker.recordStartupTime("MyApp", 3000);
            String msg = tracker.formatComparison("MyApp", 3000);
            assertTrue(msg.contains("First recorded"));
        }

        @Test
        @DisplayName("slower run shows up arrow")
        void slowerRun() {
            tracker.recordStartupTime("MyApp", 3000);
            tracker.recordStartupTime("MyApp", 4000);
            String msg = tracker.formatComparison("MyApp", 4000);
            assertTrue(msg.contains("↑"), "Expected up arrow for slower: " + msg);
            assertTrue(msg.contains("slower"));
        }

        @Test
        @DisplayName("faster run shows down arrow")
        void fasterRun() {
            tracker.recordStartupTime("MyApp", 3000);
            tracker.recordStartupTime("MyApp", 2000);
            String msg = tracker.formatComparison("MyApp", 2000);
            assertTrue(msg.contains("↓"), "Expected down arrow for faster: " + msg);
            assertTrue(msg.contains("faster"));
        }

        @Test
        @DisplayName("same time shows same message")
        void sameTime() {
            tracker.recordStartupTime("MyApp", 3000);
            tracker.recordStartupTime("MyApp", 3000);
            String msg = tracker.formatComparison("MyApp", 3000);
            assertTrue(msg.contains("Same"));
        }
    }

    // =========================================================================
    // Duration formatting
    // =========================================================================

    @Nested
    @DisplayName("Duration formatting")
    class DurationFormat {

        @Test
        @DisplayName("milliseconds formatted correctly")
        void milliseconds() {
            assertEquals("500ms", StartupTimeTracker.formatDuration(500));
        }

        @Test
        @DisplayName("seconds formatted correctly")
        void seconds() {
            assertEquals("3.0s", StartupTimeTracker.formatDuration(3000));
            assertEquals("3.5s", StartupTimeTracker.formatDuration(3500));
        }
    }

    // =========================================================================
    // Clear
    // =========================================================================

    @Nested
    @DisplayName("Clearing data")
    class Clear {

        @Test
        @DisplayName("clearHistory removes single config")
        void clearSingle() {
            tracker.recordStartupTime("App1", 1000);
            tracker.recordStartupTime("App2", 2000);
            tracker.clearHistory("App1");
            assertEquals(-1, tracker.getLastStartupTime("App1"));
            assertEquals(2000, tracker.getLastStartupTime("App2"));
        }

        @Test
        @DisplayName("clearAll removes everything")
        void clearAll() {
            tracker.recordStartupTime("App1", 1000);
            tracker.recordStartupTime("App2", 2000);
            tracker.clearAll();
            assertEquals(-1, tracker.getLastStartupTime("App1"));
            assertEquals(-1, tracker.getLastStartupTime("App2"));
        }
    }

    // =========================================================================
    // State persistence
    // =========================================================================

    @Nested
    @DisplayName("State persistence")
    class Persistence {

        @Test
        @DisplayName("getState returns current state")
        void getState() {
            tracker.recordStartupTime("MyApp", 3000);
            StartupTimeTracker.State state = tracker.getState();
            assertNotNull(state);
            assertTrue(state.startupTimes.containsKey("MyApp"));
        }

        @Test
        @DisplayName("loadState restores data")
        void loadState() {
            tracker.recordStartupTime("MyApp", 3000);
            StartupTimeTracker.State saved = tracker.getState();

            StartupTimeTracker newTracker = new StartupTimeTracker();
            newTracker.loadState(saved);
            assertEquals(3000, newTracker.getLastStartupTime("MyApp"));
        }
    }
}
