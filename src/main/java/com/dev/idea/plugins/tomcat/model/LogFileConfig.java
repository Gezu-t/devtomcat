package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Log File Configuration for Tomcat.
 */
public class LogFileConfig implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final int MAX_LOG_FILES = 100;
    private static final int MAX_PATH_LENGTH = 1024;

    @NotNull
    private String id = "";

    @NotNull
    private List<String> logFiles = new ArrayList<>();

    private boolean showStdoutConsole = true;
    private boolean showStderrConsole = true;
    private boolean saveConsoleToFile = false;
    @NotNull
    private String saveConsoleFilePath = "";

    public LogFileConfig() {
        this.id = generateId();
    }

    public LogFileConfig(@Nullable LogFileConfig other) {
        if (other != null) {
            this.id = other.id;
            if (other.logFiles != null) {
                this.logFiles.addAll(other.logFiles);
            }
            this.showStdoutConsole = other.showStdoutConsole;
            this.showStderrConsole = other.showStderrConsole;
            this.saveConsoleToFile = other.saveConsoleToFile;
            this.saveConsoleFilePath = other.saveConsoleFilePath;
        } else {
            this.id = generateId();
        }
    }

    @NotNull
    public String getId() {
        return id;
    }

    public void setId(@NotNull String id) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
    }

    private String generateId() {
        return "logfile-" + java.util.UUID.randomUUID();
    }

    @NotNull
    public List<String> getLogFiles() {
        return new ArrayList<>(logFiles);
    }

    @Nullable
    public String getLogFile(int index) {
        return (index >= 0 && index < logFiles.size()) ? logFiles.get(index) : null;
    }

    public int getLogFileCount() {
        return logFiles.size();
    }

    public boolean hasLogFiles() {
        return !logFiles.isEmpty();
    }

    public void setLogFiles(@Nullable List<String> logFiles) {
        this.logFiles.clear();
        if (logFiles == null) return;

        logFiles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(p -> !p.isEmpty() && p.length() <= MAX_PATH_LENGTH)
                .limit(MAX_LOG_FILES)
                .forEach(this.logFiles::add);
    }

    public boolean addLogFile(@NotNull String logFile) {
        Objects.requireNonNull(logFile, "Log file path cannot be null");
        String normalized = logFile.trim();

        if (normalized.isEmpty() || normalized.length() > MAX_PATH_LENGTH) return false;
        if (logFiles.contains(normalized)) return false;
        if (logFiles.size() >= MAX_LOG_FILES) return false;

        logFiles.add(normalized);
        return true;
    }

    public boolean removeLogFile(@NotNull String logFile) {
        Objects.requireNonNull(logFile, "Log file path cannot be null");
        return logFiles.remove(logFile.trim());
    }

    @Nullable
    public String removeLogFileAt(int index) {
        return (index >= 0 && index < logFiles.size()) ? logFiles.remove(index) : null;
    }

    public void clearLogFiles() {
        logFiles.clear();
    }

    public boolean isValid() {
        return hasLogFiles() && logFiles.stream()
                .allMatch(f -> f != null && !f.trim().isEmpty() && f.length() <= MAX_PATH_LENGTH);
    }

    public boolean isShowStdoutConsole() { return showStdoutConsole; }
    public void setShowStdoutConsole(boolean show) { this.showStdoutConsole = show; }

    public boolean isShowStderrConsole() { return showStderrConsole; }
    public void setShowStderrConsole(boolean show) { this.showStderrConsole = show; }

    public boolean isSaveConsoleToFile() { return saveConsoleToFile; }
    public void setSaveConsoleToFile(boolean save) { this.saveConsoleToFile = save; }

    @NotNull
    public String getSaveConsoleFilePath() { return saveConsoleFilePath; }
    public void setSaveConsoleFilePath(@NotNull String path) { this.saveConsoleFilePath = Objects.requireNonNull(path); }

    public boolean filesExist() {
        return logFiles.stream()
                .filter(p -> p != null && !p.trim().isEmpty())
                .map(p -> new File(p.trim()))
                .allMatch(f -> f.exists() && f.canRead());
    }

    @NotNull
    @Override
    public LogFileConfig clone() {
        try {
            LogFileConfig clone = (LogFileConfig) super.clone();
            clone.id = this.id;
            clone.logFiles = new ArrayList<>(this.logFiles);
            clone.showStdoutConsole = this.showStdoutConsole;
            clone.showStderrConsole = this.showStderrConsole;
            clone.saveConsoleToFile = this.saveConsoleToFile;
            clone.saveConsoleFilePath = this.saveConsoleFilePath;
            return clone;
        } catch (CloneNotSupportedException e) {
            return new LogFileConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogFileConfig that)) return false;
        return showStdoutConsole == that.showStdoutConsole &&
                showStderrConsole == that.showStderrConsole &&
                saveConsoleToFile == that.saveConsoleToFile &&
                logFiles.equals(that.logFiles) &&
                saveConsoleFilePath.equals(that.saveConsoleFilePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logFiles, showStdoutConsole, showStderrConsole, saveConsoleToFile, saveConsoleFilePath);
    }

    @NotNull
    @Override
    public String toString() {
        return "LogFileConfig{logFiles=" + logFiles.size() + "}";
    }
}