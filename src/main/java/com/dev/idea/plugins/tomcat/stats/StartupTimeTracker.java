package com.dev.idea.plugins.tomcat.stats;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Tracks Tomcat startup times across runs per configuration.
 *
 * Persists startup time history using IntelliJ's state management
 * and provides trend analysis (faster/slower compared to previous run).
 * This is a DevTomcat-exclusive feature — no other IDE or plugin tracks
 * Tomcat startup performance over time.
 *
 * @author Gezahegn Lemma (Gezu)
 */
@Service(Service.Level.APP)
@State(
        name = "DevTomcatStartupTimeTracker",
        storages = @Storage("devtomcat-startup-stats.xml")
)
public final class StartupTimeTracker implements PersistentStateComponent<StartupTimeTracker.State> {

    private static final Logger LOG = Logger.getInstance(StartupTimeTracker.class);

    /** Maximum number of startup times to keep per configuration. */
    private static final int MAX_HISTORY_SIZE = 20;

    private State myState = new State();

    /**
     * Persistent state container.
     */
    public static class State {
        /** Map of configuration name → list of startup times in ms (most recent last). */
        public Map<String, List<Long>> startupTimes = new LinkedHashMap<>();
    }

    @Nullable
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    /**
     * Record a startup time for a given configuration.
     *
     * @param configName the run configuration name
     * @param startupTimeMs startup time in milliseconds
     */
    public void recordStartupTime(@NotNull String configName, long startupTimeMs) {
        if (startupTimeMs < 0) return;

        List<Long> times = myState.startupTimes.computeIfAbsent(configName, k -> new ArrayList<>());
        times.add(startupTimeMs);

        // Keep only the most recent entries
        while (times.size() > MAX_HISTORY_SIZE) {
            times.remove(0);
        }

        LOG.info("Recorded startup time for '" + configName + "': " + startupTimeMs + "ms");
    }

    /**
     * Get the previous startup time for a configuration (the one before the current run).
     *
     * @param configName the run configuration name
     * @return the previous startup time in ms, or -1 if no previous data
     */
    public long getPreviousStartupTime(@NotNull String configName) {
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
    public long getLastStartupTime(@NotNull String configName) {
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
    public long getAverageStartupTime(@NotNull String configName) {
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
    public long getFastestStartupTime(@NotNull String configName) {
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
    public int getRunCount(@NotNull String configName) {
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
    public List<Long> getStartupHistory(@NotNull String configName) {
        List<Long> times = myState.startupTimes.get(configName);
        return times != null ? Collections.unmodifiableList(times) : Collections.emptyList();
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
    public String formatComparison(@NotNull String configName, long currentTimeMs) {
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

        return String.format("%s (avg: %s, best: %s, runs: %d)",
                trend, formatDuration(average), formatDuration(fastest), runs);
    }

    /**
     * Clear all tracked data for a configuration.
     */
    public void clearHistory(@NotNull String configName) {
        myState.startupTimes.remove(configName);
    }

    /**
     * Clear all tracked data.
     */
    public void clearAll() {
        myState.startupTimes.clear();
    }

    /**
     * Format milliseconds into a human-readable duration string.
     */
    @NotNull
    static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        double seconds = ms / 1000.0;
        return String.format("%.1fs", seconds);
    }
}
