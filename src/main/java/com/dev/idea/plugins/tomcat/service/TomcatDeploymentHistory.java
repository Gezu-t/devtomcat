package com.dev.idea.plugins.tomcat.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Tracks deployment history across runs. Persists to project-level storage
 * so history survives IDE restarts. DevTomcat exclusive — IntelliJ Ultimate
 * has no equivalent deployment history feature.
 *
 * <p>Records: configuration name, timestamp, duration, artifact names,
 * success/failure, error count, startup time.
 */
@Service(Service.Level.PROJECT)
@State(name = "TomcatDeploymentHistory", storages = @Storage("devtomcat-history.xml"))
public final class TomcatDeploymentHistory implements PersistentStateComponent<TomcatDeploymentHistory.HistoryState> {

    private static final int MAX_ENTRIES = 50;

    private HistoryState state = new HistoryState();

    public static TomcatDeploymentHistory getInstance(@NotNull Project project) {
        return project.getService(TomcatDeploymentHistory.class);
    }

    // --- Persistent State ---

    public static final class HistoryState {
        public List<HistoryEntry> entries = new ArrayList<>();
    }

    public static final class HistoryEntry {
        public String configName = "";
        public long timestampEpochMs;
        public long durationMs;
        public long startupTimeMs;
        public List<String> artifactNames = new ArrayList<>();
        public boolean success;
        public int exitCode;
        public int errorCount;
        public int warningCount;

        /** No-arg constructor for serialization */
        public HistoryEntry() {}

        HistoryEntry(@NotNull String configName) {
            this.configName = configName;
            this.timestampEpochMs = Instant.now().toEpochMilli();
        }

        @NotNull
        public String getFormattedTimestamp() {
            return java.time.LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestampEpochMs),
                    java.time.ZoneId.systemDefault()
            ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        @NotNull
        public String getSummary() {
            String status = success ? "OK" : "FAILED (exit " + exitCode + ")";
            String artifacts = artifactNames.isEmpty() ? "no artifacts" :
                    String.join(", ", artifactNames);
            return String.format("[%s] %s — %s — %dms — %s — %d errors, %d warnings",
                    getFormattedTimestamp(), configName, status, durationMs,
                    artifacts, errorCount, warningCount);
        }
    }

    @Override
    public @Nullable HistoryState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull HistoryState loaded) {
        this.state = loaded;
    }

    // --- Recording API (called from TomcatProcessHandler) ---

    @NotNull
    public HistoryEntry startEntry(@NotNull String configName) {
        return new HistoryEntry(configName);
    }

    public void recordCompleted(@NotNull HistoryEntry entry) {
        synchronized (state) {
            state.entries.add(0, entry); // newest first
            while (state.entries.size() > MAX_ENTRIES) {
                state.entries.remove(state.entries.size() - 1);
            }
        }
    }

    // --- Query API ---

    @NotNull
    public List<HistoryEntry> getEntries() {
        synchronized (state) {
            return Collections.unmodifiableList(new ArrayList<>(state.entries));
        }
    }

    @NotNull
    public List<HistoryEntry> getEntriesForConfig(@NotNull String configName) {
        List<HistoryEntry> result = new ArrayList<>();
        synchronized (state) {
            for (HistoryEntry e : state.entries) {
                if (configName.equals(e.configName)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    @Nullable
    public HistoryEntry getLastEntry(@NotNull String configName) {
        synchronized (state) {
            for (HistoryEntry e : state.entries) {
                if (configName.equals(e.configName)) {
                    return e;
                }
            }
        }
        return null;
    }

    public void clearHistory() {
        synchronized (state) {
            state.entries.clear();
        }
    }

    public int getConsecutiveFailures(@NotNull String configName) {
        int count = 0;
        synchronized (state) {
            for (HistoryEntry e : state.entries) {
                if (!configName.equals(e.configName)) continue;
                if (!e.success) count++;
                else break;
            }
        }
        return count;
    }
}
