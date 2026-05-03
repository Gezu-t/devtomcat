package com.dev.idea.plugins.tomcat.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        @Test
        @DisplayName("decimal separator stays '.' under non-English locales (Locale.ROOT contract)")
        void decimalSeparatorStableAcrossLocales() {
            // The bug this regression-guards: String.format("%.1fs", x) without an
            // explicit Locale uses the JVM default. In de_DE / fr_FR / es_ES /
            // most non-English locales the decimal separator is ',' — so the
            // user would see "↑ 3,5s slower than last run" while every other
            // log line and test assertion uses "."s.
            //
            // Pin both directions: under a German default, output must STILL
            // be "3.5s", proving the production code passes Locale.ROOT.
            Locale previousDefault = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                assertEquals("3.5s", StartupTimeTracker.formatDuration(3500),
                        "formatDuration must use Locale.ROOT — German locale must NOT mangle '.' to ','");
                assertEquals("3.0s", StartupTimeTracker.formatDuration(3000),
                        "formatDuration must use Locale.ROOT — German locale must NOT mangle '.' to ','");
            } finally {
                Locale.setDefault(previousDefault);
            }
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
        @DisplayName("renameConfiguration migrates startup history")
        void renameConfiguration() {
            tracker.recordStartupTime("OldName", 1000);
            tracker.recordStartupTime("OldName", 1500);

            tracker.renameConfiguration("OldName", "NewName");

            assertEquals(-1, tracker.getLastStartupTime("OldName"));
            assertEquals(1500, tracker.getLastStartupTime("NewName"));
            assertEquals(2, tracker.getRunCount("NewName"));
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
        @DisplayName("getState returns defensive copy of current state")
        void getState() {
            tracker.recordStartupTime("MyApp", 3000);
            StartupTimeTracker.State state = tracker.getState();
            assertNotNull(state);
            assertTrue(state.startupTimes.containsKey("MyApp"));

            // Verify it is a defensive copy: mutating the returned state
            // must not affect the tracker's internal data.
            state.startupTimes.clear();
            assertEquals(3000, tracker.getLastStartupTime("MyApp"),
                    "Clearing the returned state must not affect internal data");
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

    // =========================================================================
    // Thread safety
    // =========================================================================

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent recordStartupTime does not lose data")
        void concurrentRecording() throws InterruptedException {
            int threadCount = 10;
            int recordsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final String configName = "Config-" + t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < recordsPerThread; i++) {
                            tracker.recordStartupTime(configName, (i + 1) * 100L);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // release all threads at once
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
            executor.shutdown();

            assertEquals(0, errors.get(), "No exceptions should occur during concurrent recording");
            for (int t = 0; t < threadCount; t++) {
                int count = tracker.getRunCount("Config-" + t);
                // History is capped at MAX_HISTORY_SIZE (20), so count should be min(records, 20)
                assertTrue(count > 0 && count <= recordsPerThread,
                        "Config-" + t + " should have records, got: " + count);
            }
        }

        @Test
        @DisplayName("loadState during concurrent reads does not throw")
        void loadStateDuringReads() throws InterruptedException {
            tracker.recordStartupTime("MyApp", 1000);
            tracker.recordStartupTime("MyApp", 2000);

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final boolean isWriter = (t == 0); // one writer, rest readers
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 200; i++) {
                            if (isWriter) {
                                StartupTimeTracker.State newState = new StartupTimeTracker.State();
                                tracker.loadState(newState);
                            } else {
                                tracker.getLastStartupTime("MyApp");
                                tracker.getAverageStartupTime("MyApp");
                                tracker.formatComparison("MyApp", 1500);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
            executor.shutdown();

            assertEquals(0, errors.get(), "No exceptions during concurrent loadState + reads");
        }
    }
}
