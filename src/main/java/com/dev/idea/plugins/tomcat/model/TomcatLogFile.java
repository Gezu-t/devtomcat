package com.dev.idea.plugins.tomcat.model;

import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.execution.configurations.PredefinedLogFile;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Represents a Tomcat log file that can be monitored during execution.
 * Provides factory methods for standard Tomcat log files (Catalina, Localhost, Access, etc.).
 */
public class TomcatLogFile {

    private static final Logger LOG = Logger.getInstance(TomcatLogFile.class);

    public static final String TOMCAT_CATALINA_OUT_ID = "Tomcat Catalina Out";
    public static final String TOMCAT_CATALINA_LOG_ID = "Tomcat Catalina Log";
    public static final String TOMCAT_LOCALHOST_LOG_ID = "Tomcat Localhost Log";
    public static final String TOMCAT_ACCESS_LOG_ID = "Tomcat Access Log";
    public static final String TOMCAT_MANAGER_LOG_ID = "Tomcat Manager Log";
    public static final String TOMCAT_HOST_MANAGER_LOG_ID = "Tomcat Host Manager Log";

    private static final String CATALINA_OUT_FILENAME = "catalina.out";
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

    /**
     * Resolves the glob pattern (e.g. {@code catalina.*.log}) to today's concrete
     * filename (e.g. {@code catalina.2026-03-02.log}).  Tomcat names its daily
     * log files with the ISO-8601 date, so replacing {@code *} with today's date
     * produces the path IntelliJ can open without URI-validation errors.
     */
    @NotNull
    public String resolveTodayFilename() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return filenamePattern.replace("*", today);
    }

    /**
     * Builds the full log-file path for today, using string concatenation to
     * avoid passing glob characters through {@link Path#resolve}.
     */
    @NotNull
    public String resolveFullPath(@NotNull Path logsDirPath) {
        return logsDirPath + java.io.File.separator + resolveTodayFilename();
    }

    /**
     * Builds the path pattern string for IntelliJ's {@link LogFileOptions}.
     * Uses the original glob pattern (e.g. {@code catalina.*.log}) so that
     * IntelliJ's log console can resolve and watch the file dynamically.
     * <p>
     * This avoids passing glob characters through {@link Path#resolve} (which
     * would cause URI-validation errors) by using direct string concatenation.
     */
    @NotNull
    public String resolvePathPattern(@NotNull Path logsDirPath) {
        return logsDirPath + java.io.File.separator + filenamePattern;
    }

    @NotNull
    public LogFileOptions createLogFileOptions(@NotNull Path logsDirPath) {
        Objects.requireNonNull(logsDirPath, "Logs directory path cannot be null");

        try {
            String path = resolveFullPath(logsDirPath);
            LogFileOptions opts = new LogFileOptions(id, path, enabledByDefault);
            LOG.debug("Created LogFileOptions: id=" + id + ", path=" + path);
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
            String path = resolveFullPath(logsDirPath);
            LogFileOptions opts = new LogFileOptions(file.getId(), path, file.isEnabled());
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
    public static TomcatLogFile createCatalinaOut() {
        // Disabled by default: when launching Java directly (not via catalina.sh),
        // stdout/stderr goes to IntelliJ's Console tab, not to catalina.out file.
        return new TomcatLogFile(TOMCAT_CATALINA_OUT_ID, CATALINA_OUT_FILENAME, false,
                "Main Tomcat console output (stdout/stderr)");
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
                createCatalinaOut(),
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
