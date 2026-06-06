package com.dev.idea.plugins.tomcat.stats;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Locale;


/**
 * Tracks Tomcat startup times across runs per configuration.
 *
 * Persists startup time history using IntelliJ's state management
 * and provides trend analysis (faster/slower compared to previous run).
 * This is a DevTomcat-exclusive feature — no other IDE or plugin tracks
 * Tomcat startup performance over time.
 *
 * <p>This is a <b>project-level</b> service so that two projects with
 * identically named configurations each keep their own history.
 *
 * @author Gezahegn Lemma (Gezu)
 */
@Service(Service.Level.PROJECT)
@State(
        name = "DevTomcatStartupTimeTracker",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class StartupTimeTracker implements PersistentStateComponent<StartupTimeTracker.State> {

    private static final Logger LOG = Logger.getInstance(StartupTimeTracker.class);

    /** Maximum number of startup times to keep per configuration. */
    private static final int MAX_HISTORY_SIZE = 20;

    /**
     * Lower bound (ms) below which a reported startup time is rejected as
     * implausible. A complete server start initialises its connector(s) and
     * deploys at least one web application; on any real JVM that work cannot
     * finish in a few milliseconds. A value below this floor therefore signals a
     * mis-parsed number or a non-startup log line that slipped through — not a
     * genuine cold boot — and recording it would corrupt the best/average/trend
     * the user relies on. Kept deliberately low so it only ever rejects the
     * physically impossible, never a merely-fast real startup.
     */
    static final long MIN_PLAUSIBLE_STARTUP_MS = 100;

    private volatile State myState = new State();

    /** Required by the IntelliJ service framework for project-level services. */
    public StartupTimeTracker(@NotNull Project project) {
        // Project reference not needed — state is persisted by the platform.
    }

    /** Constructor for unit tests that run without a real Project. */
    public StartupTimeTracker() {
    }

    @NotNull
    public static StartupTimeTracker getInstance(@NotNull Project project) {
        return project.getService(StartupTimeTracker.class);
    }

    /**
     * Persistent state container.
     * Uses standard collection types for IntelliJ XmlSerializer compatibility.
     */
    public static class State {
        /** Map of configuration name → list of startup times in ms (most recent last). */
        public Map<String, List<Long>> startupTimes = new LinkedHashMap<>();
    }

    @Override
    public synchronized @NotNull State getState() {
        // Return a defensive copy so callers (e.g. StartupTimeTrendDialog)
        // do not read or mutate the live internal state.
        State copy = new State();
        for (Map.Entry<String, List<Long>> entry : myState.startupTimes.entrySet()) {
            copy.startupTimes.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        myState = state;
    }

    /**
     * Record a startup time for a given configuration.
     *
     * @param configName the run configuration name
     * @param startupTimeMs startup time in milliseconds
     * @return {@code true} if the sample was recorded; {@code false} if it was
     *         rejected as implausible (see {@link #MIN_PLAUSIBLE_STARTUP_MS})
     */
    public synchronized boolean recordStartupTime(@NotNull String configName, long startupTimeMs) {
        if (startupTimeMs < MIN_PLAUSIBLE_STARTUP_MS) {
            // Negative is the established "no measurement" sentinel — drop it
            // silently. A positive-but-too-small value is a genuine anomaly, so
            // surface it so a recurring mis-parse is diagnosable from the log.
            if (startupTimeMs >= 0) {
                LOG.info("Ignoring implausible startup time for '" + configName + "': "
                        + startupTimeMs + "ms is below the " + MIN_PLAUSIBLE_STARTUP_MS
                        + "ms floor for a complete server startup.");
            }
            return false;
        }

        List<Long> times = myState.startupTimes.computeIfAbsent(configName, k -> new ArrayList<>());
        times.add(startupTimeMs);

        // Keep only the most recent entries
        if (times.size() > MAX_HISTORY_SIZE) {
            times.subList(0, times.size() - MAX_HISTORY_SIZE).clear();
        }

        LOG.info("Recorded startup time for '" + configName + "': " + startupTimeMs + "ms");
        return true;
    }

    /**
     * Get the previous startup time for a configuration (the one before the current run).
     *
     * @param configName the run configuration name
     * @return the previous startup time in ms, or -1 if no previous data
     */
    public synchronized long getPreviousStartupTime(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        if (times == null || times.size() < 2) return -1;
        return times.get(times.size() - 2);
    }

    /**
     * Get the last recorded startup time.
     *
     * @param configName the run configuration name
     * @return the last startup time in ms, or -1 if no data
     */
    public synchronized long getLastStartupTime(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        if (times == null || times.isEmpty()) return -1;
        return times.get(times.size() - 1);
    }

    /**
     * Get the average startup time across all recorded runs.
     *
     * @param configName the run configuration name
     * @return the average in ms, or -1 if no data
     */
    public synchronized long getAverageStartupTime(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        if (times == null || times.isEmpty()) return -1;
        return (long) times.stream().mapToLong(Long::longValue).average().orElse(-1);
    }

    /**
     * Get the fastest (minimum) startup time recorded.
     *
     * @param configName the run configuration name
     * @return the fastest time in ms, or -1 if no data
     */
    public synchronized long getFastestStartupTime(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        if (times == null || times.isEmpty()) return -1;
        return times.stream().mapToLong(Long::longValue).min().orElse(-1);
    }

    /**
     * Get the number of recorded runs for a configuration.
     *
     * @param configName the run configuration name
     * @return the number of recorded runs
     */
    public synchronized int getRunCount(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        return times != null ? times.size() : 0;
    }

    /**
     * Get all startup times for a configuration (most recent last).
     *
     * @param configName the run configuration name
     * @return unmodifiable list of startup times, or empty list
     */
    @NotNull
    public synchronized List<Long> getStartupHistory(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        // Snapshot inside the lock: the stored list is mutated by recordStartupTime()
        // (add + trim) on the output-reader thread, so returning an unmodifiable
        // wrapper over the live list would let a UI reader hit a concurrent
        // modification. Long elements are immutable, so a shallow copy suffices.
        return times != null ? List.copyOf(times) : Collections.emptyList();
    }

    /**
     * Format a comparison message between current and previous startup time.
     * Returns a human-readable trend message suitable for console display.
     *
     * @param configName the configuration name
     * @param currentTimeMs the current startup time
     * @return formatted comparison string, or empty if no previous data
     */
    @NotNull
    public synchronized String formatComparison(@NotNull String configName, long currentTimeMs) {
        long previousTime = getPreviousStartupTime(configName);
        if (previousTime < 0) {
            int count = getRunCount(configName);
            if (count <= 1) {
                return "First recorded startup for '" + configName + "'";
            }
            return "";
        }

        long diff = currentTimeMs - previousTime;
        String trend;
        if (diff > 0) {
            trend = "↑ " + formatDuration(diff) + " slower than last run";
        } else if (diff < 0) {
            trend = "↓ " + formatDuration(Math.abs(diff)) + " faster than last run";
        } else {
            trend = "Same as last run";
        }

        long average = getAverageStartupTime(configName);
        long fastest = getFastestStartupTime(configName);
        int runs = getRunCount(configName);

        // Locale.ROOT so the run count formats with a stable decimal/grouping
        // representation regardless of the user's IDE locale — same reason
        // formatDuration() uses Locale.ROOT below.
        return String.format(Locale.ROOT, "%s (avg: %s, best: %s, runs: %d)",
                trend, formatDuration(average), formatDuration(fastest), runs);
    }

    /**
     * Migrates startup time data from one configuration name to another.
     * Called when a run configuration is renamed so that trend data
     * follows the configuration instead of being orphaned.
     *
     * @param oldName the previous configuration name
     * @param newName the new configuration name
     */
    public synchronized void renameConfiguration(@NotNull String oldName, @NotNull String newName) {
        List<Long> times = myState.startupTimes.remove(oldName);
        if (times != null) {
            myState.startupTimes.put(newName, times);
        }
    }

    /**
     * Clear all tracked data for a configuration.
     */
    public synchronized void clearHistory(@NotNull String configName) {
        myState.startupTimes.remove(configName);
    }

    /**
     * Clear all tracked data.
     */
    public synchronized void clearAll() {
        myState.startupTimes.clear();
    }

    /**
     * Format milliseconds into a human-readable duration string.
     */
    @NotNull
    static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        double seconds = ms / 1000.0;
        // Locale.ROOT pins the decimal separator to '.' across every IDE locale.
        // Without it, German/French/etc. users would see "3,5s" while en_US users
        // see "3.5s" — and StartupTimeTrackerTest's hard-coded expectation
        // ("3.5s") would silently break in CI running under a non-English locale.
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }
}
