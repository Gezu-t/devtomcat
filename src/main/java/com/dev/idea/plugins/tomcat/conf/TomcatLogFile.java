package com.dev.idea.plugins.tomcat.conf;

import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.execution.configurations.PredefinedLogFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tomcat Log File Configuration
 *
 * Represents a log file that can be monitored during Tomcat execution.
 * This class defines standard Tomcat log files and provides utilities
 * for creating log file options for the IntelliJ run configuration.
 *
 * Standard Tomcat logs include:
 * - Catalina log: Main server log
 * - Localhost log: Host-specific application logs
 * - Access log: HTTP access logs
 * - Manager log: Manager application logs
 * - Host Manager log: Host manager application logs
 *
 * @author Dev Tomcat Team
 * @see LogFileOptions
 * @see PredefinedLogFile
 */
public class TomcatLogFile {

    // Standard Tomcat log file IDs
    public static final String TOMCAT_CATALINA_LOG_ID = "Tomcat Catalina Log";
    public static final String TOMCAT_LOCALHOST_LOG_ID = "Tomcat Localhost Log";
    public static final String TOMCAT_ACCESS_LOG_ID = "Tomcat Access Log";
    public static final String TOMCAT_MANAGER_LOG_ID = "Tomcat Manager Log";
    public static final String TOMCAT_HOST_MANAGER_LOG_ID = "Tomcat Host Manager Log";

    // Standard log file patterns
    private static final String CATALINA_LOG_PATTERN = "catalina.%s.log";
    private static final String LOCALHOST_LOG_PATTERN = "localhost.%s.log";
    private static final String ACCESS_LOG_PATTERN = "localhost_access_log.%s.txt";
    private static final String MANAGER_LOG_PATTERN = "manager.%s.log";
    private static final String HOST_MANAGER_LOG_PATTERN = "host-manager.%s.log";

    // Date pattern for log files
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    private final String id;
    private final String filenamePattern;
    private final boolean enabledByDefault;
    private final String description;

    /**
     * Creates a new Tomcat log file configuration
     *
     * @param id The unique identifier for this log file
     * @param filenamePattern The filename pattern (may include date placeholders)
     * @param enabledByDefault Whether this log should be enabled by default
     * @param description A description of what this log contains
     */
    public TomcatLogFile(@NotNull String id,
                         @NotNull String filenamePattern,
                         boolean enabledByDefault,
                         @NotNull String description) {
        this.id = id;
        this.filenamePattern = filenamePattern;
        this.enabledByDefault = enabledByDefault;
        this.description = description;
    }

    /**
     * Creates a log file configuration with default enabled state
     *
     * @param id The unique identifier
     * @param filenamePattern The filename pattern
     */
    public TomcatLogFile(@NotNull String id, @NotNull String filenamePattern) {
        this(id, filenamePattern, true, "");
    }

    /**
     * Get the unique identifier for this log file
     *
     * @return The log file ID
     */
    @NotNull
    public String getId() {
        return id;
    }

    /**
     * Get the filename pattern
     *
     * @return The filename pattern
     */
    @NotNull
    public String getFilenamePattern() {
        return filenamePattern;
    }

    /**
     * Check if this log file should be enabled by default
     *
     * @return True if enabled by default
     */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /**
     * Get the description of this log file
     *
     * @return The description
     */
    @NotNull
    public String getDescription() {
        return description;
    }

    /**
     * Create log file options for IntelliJ run configuration
     *
     * @param logsDirPath The path to the logs directory (null for default)
     * @return Configured LogFileOptions
     */
    @NotNull
    public LogFileOptions createLogFileOptions(@Nullable Path logsDirPath) {
        Path logsPath = logsDirPath != null ? logsDirPath : Paths.get("logs");
        String pattern = logsPath.resolve(filenamePattern).toString();

        // Add wildcard for date-based log rotation
        if (!pattern.contains("*")) {
            pattern += ".*";
        }

        return new LogFileOptions(id, pattern, enabledByDefault);
    }

    /**
     * Create log file options with predefined log file
     *
     * @param file The predefined log file
     * @param logsDirPath The path to the logs directory
     * @return Configured LogFileOptions
     */
    @NotNull
    public LogFileOptions createLogFileOptions(@NotNull PredefinedLogFile file,
                                               @Nullable Path logsDirPath) {
        Path logsPath = logsDirPath != null ? logsDirPath : Paths.get("logs");
        String pattern = logsPath.resolve(filenamePattern).toString();

        // Add wildcard for date-based log rotation
        if (!pattern.contains("*")) {
            pattern += ".*";
        }

        return new LogFileOptions(file.getId(), pattern, file.isEnabled());
    }

    /**
     * Create a predefined log file from this configuration
     *
     * @return PredefinedLogFile instance
     */
    @NotNull
    public PredefinedLogFile createPredefinedLogFile() {
        return new PredefinedLogFile(id, enabledByDefault);
    }

    /**
     * Create a predefined log file with custom enabled state
     *
     * @param enabled Whether the log file should be enabled
     * @return PredefinedLogFile instance
     */
    @NotNull
    public PredefinedLogFile createPredefinedLogFile(boolean enabled) {
        return new PredefinedLogFile(id, enabled);
    }

    // === FACTORY METHODS FOR STANDARD TOMCAT LOGS ===

    /**
     * Create Catalina log file configuration
     *
     * @return TomcatLogFile for Catalina logs
     */
    @NotNull
    public static TomcatLogFile createCatalinaLog() {
        return new TomcatLogFile(
                TOMCAT_CATALINA_LOG_ID,
                CATALINA_LOG_PATTERN,
                true,
                "Main Tomcat server log containing startup, shutdown, and error information"
        );
    }

    /**
     * Create Localhost log file configuration
     *
     * @return TomcatLogFile for localhost logs
     */
    @NotNull
    public static TomcatLogFile createLocalhostLog() {
        return new TomcatLogFile(
                TOMCAT_LOCALHOST_LOG_ID,
                LOCALHOST_LOG_PATTERN,
                true,
                "Application-specific logs for the default host"
        );
    }

    /**
     * Create Access log file configuration
     *
     * @return TomcatLogFile for access logs
     */
    @NotNull
    public static TomcatLogFile createAccessLog() {
        return new TomcatLogFile(
                TOMCAT_ACCESS_LOG_ID,
                ACCESS_LOG_PATTERN,
                false,
                "HTTP access logs showing all requests to the server"
        );
    }

    /**
     * Create Manager log file configuration
     *
     * @return TomcatLogFile for manager logs
     */
    @NotNull
    public static TomcatLogFile createManagerLog() {
        return new TomcatLogFile(
                TOMCAT_MANAGER_LOG_ID,
                MANAGER_LOG_PATTERN,
                false,
                "Logs for the Tomcat Manager application"
        );
    }

    /**
     * Create Host Manager log file configuration
     *
     * @return TomcatLogFile for host manager logs
     */
    @NotNull
    public static TomcatLogFile createHostManagerLog() {
        return new TomcatLogFile(
                TOMCAT_HOST_MANAGER_LOG_ID,
                HOST_MANAGER_LOG_PATTERN,
                false,
                "Logs for the Tomcat Host Manager application"
        );
    }

    /**
     * Get all standard Tomcat log file configurations
     *
     * @return Array of standard TomcatLogFile configurations
     */
    @NotNull
    public static TomcatLogFile[] getStandardLogFiles() {
        return new TomcatLogFile[] {
                createCatalinaLog(),
                createLocalhostLog(),
                createAccessLog(),
                createManagerLog(),
                createHostManagerLog()
        };
    }

    /**
     * Get default enabled log files
     *
     * @return Array of log files that should be enabled by default
     */
    @NotNull
    public static TomcatLogFile[] getDefaultEnabledLogFiles() {
        return new TomcatLogFile[] {
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