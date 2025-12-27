package com.dev.idea.plugins.tomcat.conf;

 import com.intellij.execution.configurations.LogFileOptions;
 import com.intellij.execution.configurations.PredefinedLogFile;
 import com.intellij.openapi.diagnostic.Logger;
 import org.jetbrains.annotations.NotNull;
 import org.jetbrains.annotations.Nullable;

 import java.nio.file.Path;
 import java.util.Objects;

 /**
  * Tomcat Log File Configuration
  *
  * <p>Represents a log file that can be monitored during Tomcat execution.
  * Defines standard Tomcat log files and provides utilities for creating log file
  * options for the IntelliJ run configuration.
  *
  * <p>Standard Tomcat logs include:
  * <ul>
  *   <li>Catalina log: Main server log</li>
  *   <li>Localhost log: Host-specific application logs</li>
  *   <li>Access log: HTTP access logs</li>
  *   <li>Manager log: Manager application logs</li>
  *   <li>Host Manager log: Host manager application logs</li>
  * </ul>
  *
  * <p>Usage in `TomcatConfigurationInitializer`:
  * <pre>{@code
  * List<LogFileConfiguration> defaultLogs = new ArrayList<>();
  * defaultLogs.add(LogFileConfiguration.fromTomcatLogFile(TomcatLogFile.createCatalinaLog()));
  * defaultLogs.add(LogFileConfiguration.fromTomcatLogFile(TomcatLogFile.createLocalhostLog()));
  * config.getConfigData().setLogFileConfigurations(defaultLogs);
  * }</pre>
  *
  * <p>Usage in `TomcatConfigurationSerializer`:
  * <pre>{@code
  * for (LogFileConfiguration cfg : logs) {
  *     TomcatLogFile logFile = TomcatLogFile.fromConfiguration(cfg);
  *     LogFileOptions opts = logFile.createLogFileOptions(tomcatLogsPath);
  * }
  * }</pre>
  *
  * @author Dev Tomcat Team
  * @see LogFileOptions
  * @see PredefinedLogFile
  */
 public class TomcatLogFile {

     private static final Logger LOG = Logger.getInstance(TomcatLogFile.class);

     // Standard Tomcat log file IDs
     public static final String TOMCAT_CATALINA_LOG_ID = "Tomcat Catalina Log";
     public static final String TOMCAT_LOCALHOST_LOG_ID = "Tomcat Localhost Log";
     public static final String TOMCAT_ACCESS_LOG_ID = "Tomcat Access Log";
     public static final String TOMCAT_MANAGER_LOG_ID = "Tomcat Manager Log";
     public static final String TOMCAT_HOST_MANAGER_LOG_ID = "Tomcat Host Manager Log";

     // Standard log file patterns (Tomcat date format: yyyy-MM-dd)
     private static final String CATALINA_LOG_PATTERN = "catalina.*.log";
     private static final String LOCALHOST_LOG_PATTERN = "localhost.*.log";
     private static final String ACCESS_LOG_PATTERN = "localhost_access_log.*.txt";
     private static final String MANAGER_LOG_PATTERN = "manager.*.log";
     private static final String HOST_MANAGER_LOG_PATTERN = "host-manager.*.log";

     private final String id;
     private final String filenamePattern;
     private final boolean enabledByDefault;
     private final String description;

     /**
      * Creates a new Tomcat log file configuration.
      *
      * @param id                the unique identifier for this log file (not empty)
      * @param filenamePattern   the filename pattern with wildcards for date rotation
      * @param enabledByDefault  whether this log should be enabled by default
      * @param description       a description of what this log contains
      * @throws NullPointerException if id, filenamePattern, or description is null
      * @throws IllegalArgumentException if id or filenamePattern is empty
      */
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

     /**
      * Creates a log file configuration with default enabled state.
      *
      * @param id              the unique identifier
      * @param filenamePattern the filename pattern
      */
     public TomcatLogFile(@NotNull String id, @NotNull String filenamePattern) {
         this(id, filenamePattern, true, "");
     }

     /**
      * Get the unique identifier for this log file.
      *
      * @return the log file ID
      */
     @NotNull
     public String getId() {
         return id;
     }

     /**
      * Get the filename pattern (includes wildcards for date rotation).
      *
      * @return the filename pattern
      */
     @NotNull
     public String getFilenamePattern() {
         return filenamePattern;
     }

     /**
      * Check if this log file should be enabled by default.
      *
      * @return true if enabled by default
      */
     public boolean isEnabledByDefault() {
         return enabledByDefault;
     }

     /**
      * Get the description of this log file.
      *
      * @return the description
      */
     @NotNull
     public String getDescription() {
         return description;
     }

     /**
      * Create log file options for IntelliJ run configuration.
      *
      * <p>Usage in run configuration:
      * <pre>{@code
      * TomcatLogFile catalina = TomcatLogFile.createCatalinaLog();
      * LogFileOptions opts = catalina.createLogFileOptions(tomcatPath.resolve("logs"));
      * }</pre>
      *
      * @param logsDirPath the path to the logs directory (defaults to "logs" if null)
      * @return configured LogFileOptions ready for run configuration
      */
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

     /**
      * Create log file options from a predefined log file.
      *
      * <p>Usage:
      * <pre>{@code
      * PredefinedLogFile predefined = new PredefinedLogFile(TOMCAT_CATALINA_LOG_ID, true);
      * LogFileOptions opts = TomcatLogFile.createCatalinaLog()
      *     .createLogFileOptions(predefined, tomcatLogsPath);
      * }</pre>
      *
      * @param file         the predefined log file
      * @param logsDirPath  the path to the logs directory
      * @return configured LogFileOptions
      */
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

     /**
      * Create a predefined log file from this configuration.
      *
      * @return PredefinedLogFile instance
      */
     @NotNull
     public PredefinedLogFile createPredefinedLogFile() {
         return new PredefinedLogFile(id, enabledByDefault);
     }

     /**
      * Create a predefined log file with custom enabled state.
      *
      * @param enabled whether the log file should be enabled
      * @return PredefinedLogFile instance
      */
     @NotNull
     public PredefinedLogFile createPredefinedLogFile(boolean enabled) {
         return new PredefinedLogFile(id, enabled);
     }

     // === FACTORY METHODS FOR STANDARD TOMCAT LOGS ===

     /**
      * Create Catalina log file configuration.
      *
      * <p>Catalina logs contain main Tomcat server logs including startup, shutdown,
      * and error information.
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
      * Create Localhost log file configuration.
      *
      * <p>Localhost logs contain application-specific logs for the default host.
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
      * Create Access log file configuration.
      *
      * <p>Access logs show all HTTP requests to the server.
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
      * Create Manager log file configuration.
      *
      * <p>Manager logs contain logs for the Tomcat Manager application.
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
      * Create Host Manager log file configuration.
      *
      * <p>Host Manager logs contain logs for the Tomcat Host Manager application.
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
      * Get all standard Tomcat log file configurations.
      *
      * @return array of standard TomcatLogFile configurations
      */
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

     /**
      * Get default enabled log files (Catalina and Localhost).
      *
      * @return array of log files that should be enabled by default
      */
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