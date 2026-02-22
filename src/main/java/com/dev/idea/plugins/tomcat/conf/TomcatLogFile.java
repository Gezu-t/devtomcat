package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.execution.configurations.PredefinedLogFile;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a Tomcat log file that can be monitored during execution.
 * Provides factory methods for standard Tomcat log files (Catalina, Localhost, Access, etc.).
 */
public class TomcatLogFile {

    private static final Logger LOG = Logger.getInstance(TomcatLogFile.class);

    public static final String TOMCAT_CATALINA_LOG_ID = "Tomcat Catalina Log";
    public static final String TOMCAT_LOCALHOST_LOG_ID = "Tomcat Localhost Log";
    public static final String TOMCAT_ACCESS_LOG_ID = "Tomcat Access Log";
    public static final String TOMCAT_MANAGER_LOG_ID = "Tomcat Manager Log";
    public static final String TOMCAT_HOST_MANAGER_LOG_ID = "Tomcat Host Manager Log";

    private static final String CATALINA_LOG_PATTERN = "catalina.*.log";
    private static final String LOCALHOST_LOG_PATTERN = "localhost.*.log";
    private static final String ACCESS_LOG_PATTERN = "localhost_access_log.*.txt";
    private static final String MANAGER_LOG_PATTERN = "manager.*.log";
    private static final String HOST_MANAGER_LOG_PATTERN = "host-manager.*.log";

    private final String id;
    private final String filenamePattern;
    private final boolean enabledByDefault;
    private final String description;

    public TomcatLogFile(@NotNull String id,
                         @NotNull String filenamePattern,
                         boolean enabledByDefault,
                         @NotNull String description) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.filenamePattern = Objects.requireNonNull(filenamePattern, "Filename pattern cannot be null");
        this.description = Objects.requireNonNull(description, "Description cannot be null");

        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID cannot be empty");
        }
        if (filenamePattern.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename pattern cannot be empty");
        }

        this.enabledByDefault = enabledByDefault;
        LOG.debug("Created TomcatLogFile: id=" + id + ", pattern=" + filenamePattern);
    }

    public TomcatLogFile(@NotNull String id, @NotNull String filenamePattern) {
        this(id, filenamePattern, true, "");
    }

    @NotNull
    public String getId() { return id; }

    @NotNull
    public String getFilenamePattern() { return filenamePattern; }

    public boolean isEnabledByDefault() { return enabledByDefault; }

    @NotNull
    public String getDescription() { return description; }

    @NotNull
    public LogFileOptions createLogFileOptions(@Nullable Path logsDirPath) {
        Objects.requireNonNull(logsDirPath, "Logs directory path cannot be null");

        try {
            Path fullPath = logsDirPath.resolve(filenamePattern);
            String pattern = fullPath.toString();
            LogFileOptions opts = new LogFileOptions(id, pattern, enabledByDefault);
            LOG.debug("Created LogFileOptions: id=" + id + ", pattern=" + pattern);
            return opts;
        } catch (Exception e) {
            LOG.error("Failed to create LogFileOptions for: " + id, e);
            throw new IllegalStateException("Cannot create log file options for: " + id, e);
        }
    }

    @NotNull
    public LogFileOptions createLogFileOptions(@NotNull PredefinedLogFile file,
                                               @NotNull Path logsDirPath) {
        Objects.requireNonNull(file, "PredefinedLogFile cannot be null");
        Objects.requireNonNull(logsDirPath, "Logs directory path cannot be null");

        try {
            Path fullPath = logsDirPath.resolve(filenamePattern);
            String pattern = fullPath.toString();
            LogFileOptions opts = new LogFileOptions(file.getId(), pattern, file.isEnabled());
            LOG.debug("Created LogFileOptions from PredefinedLogFile: id=" + file.getId());
            return opts;
        } catch (Exception e) {
            LOG.error("Failed to create LogFileOptions from PredefinedLogFile: " + file.getId(), e);
            throw new IllegalStateException("Cannot create log file options from predefined file", e);
        }
    }

    @NotNull
    public PredefinedLogFile createPredefinedLogFile() {
        return new PredefinedLogFile(id, enabledByDefault);
    }

    @NotNull
    public PredefinedLogFile createPredefinedLogFile(boolean enabled) {
        return new PredefinedLogFile(id, enabled);
    }

    @NotNull
    public static TomcatLogFile createCatalinaLog() {
        return new TomcatLogFile(TOMCAT_CATALINA_LOG_ID, CATALINA_LOG_PATTERN, true,
                "Main Tomcat server log");
    }

    @NotNull
    public static TomcatLogFile createLocalhostLog() {
        return new TomcatLogFile(TOMCAT_LOCALHOST_LOG_ID, LOCALHOST_LOG_PATTERN, true,
                "Application-specific logs for the default host");
    }

    @NotNull
    public static TomcatLogFile createAccessLog() {
        return new TomcatLogFile(TOMCAT_ACCESS_LOG_ID, ACCESS_LOG_PATTERN, false,
                "HTTP access logs");
    }

    @NotNull
    public static TomcatLogFile createManagerLog() {
        return new TomcatLogFile(TOMCAT_MANAGER_LOG_ID, MANAGER_LOG_PATTERN, false,
                "Manager application logs");
    }

    @NotNull
    public static TomcatLogFile createHostManagerLog() {
        return new TomcatLogFile(TOMCAT_HOST_MANAGER_LOG_ID, HOST_MANAGER_LOG_PATTERN, false,
                "Host Manager application logs");
    }

    @NotNull
    public static TomcatLogFile[] getStandardLogFiles() {
        return new TomcatLogFile[]{
                createCatalinaLog(),
                createLocalhostLog(),
                createAccessLog(),
                createManagerLog(),
                createHostManagerLog()
        };
    }

    @NotNull
    public static TomcatLogFile[] getDefaultEnabledLogFiles() {
        return new TomcatLogFile[]{
                createCatalinaLog(),
                createLocalhostLog()
        };
    }

    @Override
    public String toString() {
        return String.format("TomcatLogFile{id='%s', pattern='%s', enabled=%s}",
                id, filenamePattern, enabledByDefault);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TomcatLogFile that = (TomcatLogFile) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
