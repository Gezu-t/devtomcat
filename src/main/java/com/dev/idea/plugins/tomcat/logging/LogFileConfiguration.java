package com.dev.idea.plugins.tomcat.logging;

import com.dev.idea.plugins.tomcat.conf.TomcatLogFile;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log File Configuration
 *
 * Represents a log file configuration for monitoring during Tomcat execution.
 * This class supports:
 * - Variable substitution ($CATALINA_BASE, $CATALINA_HOME, $DATE)
 * - Log file filtering options
 * - Active/inactive state management
 * - Integration with IntelliJ's log viewing system
 *
 * @author Dev Tomcat Team
 * @see TomcatLogFile
 */
public class LogFileConfiguration implements Serializable, Cloneable {

	private static final long serialVersionUID = 1L;

	// Variable patterns for substitution
	private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{?([A-Z_]+)\\}?");
	private static final String DATE_FORMAT = "yyyy-MM-dd";

	// Fields
	@NotNull private String id;           // Unique identifier
	@NotNull private String alias;        // Display name
	@NotNull private String filePath;     // File path with variables
	@NotNull private String description;  // User-friendly description
	private boolean active;               // Whether to monitor this log
	private boolean skipContent;          // Skip initial file content
	private boolean showAllMessages;      // Show all messages (no filtering)

	/**
	 * Default constructor
	 */
	public LogFileConfiguration() {
		this.id = "";
		this.alias = "";
		this.filePath = "";
		this.description = "";
		this.active = true;
		this.skipContent = false;
		this.showAllMessages = true;
	}

	/**
	 * Constructor for backward compatibility with TomcatRunConfiguration
	 * This is the constructor called when reading from XML
	 */
	public LogFileConfiguration(@Nullable String id, @Nullable String path, boolean enabled) {
		this.id = StringUtil.notNullize(id);
		this.alias = StringUtil.notNullize(id); // Use id as alias for backward compatibility
		this.filePath = StringUtil.notNullize(path);
		this.active = enabled;
		this.description = "";
		this.skipContent = false;
		this.showAllMessages = true;
	}

//	/**
//	 * Constructor with basic parameters
//	 */
//	public LogFileConfiguration(@NotNull String alias, @NotNull String filePath, boolean active) {
//		this.id = alias; // Use alias as id if not specified
//		this.alias = alias;
//		this.filePath = filePath;
//		this.active = active;
//		this.description = "";
//		this.skipContent = false;
//		this.showAllMessages = true;
//	}

	/**
	 * Constructor with basic parameters and description
	 */
	public LogFileConfiguration(@NotNull String alias, @NotNull String filePath, boolean active, @Nullable String description) {
		this.id = alias; // Use alias as id if not specified
		this.alias = alias;
		this.filePath = filePath;
		this.active = active;
		this.description = StringUtil.notNullize(description);
		this.skipContent = false;
		this.showAllMessages = true;
	}

	/**
	 * Constructor with id, alias, path and active state
	 */
	public LogFileConfiguration(@NotNull String id, @NotNull String alias, @NotNull String filePath, boolean active) {
		this.id = id;
		this.alias = alias;
		this.filePath = filePath;
		this.active = active;
		this.description = "";
		this.skipContent = false;
		this.showAllMessages = true;
	}

	/**
	 * Constructor with id, alias, path, active state and description
	 */
	public LogFileConfiguration(@NotNull String id, @NotNull String alias, @NotNull String filePath, boolean active, @Nullable String description) {
		this.id = id;
		this.alias = alias;
		this.filePath = filePath;
		this.active = active;
		this.description = StringUtil.notNullize(description);
		this.skipContent = false;
		this.showAllMessages = true;
	}

	/**
	 * Full constructor
	 */
	public LogFileConfiguration(@NotNull String id,
								@NotNull String alias,
								@NotNull String filePath,
								boolean active,
								@Nullable String description,
								boolean skipContent,
								boolean showAllMessages) {
		this.id = id;
		this.alias = alias;
		this.filePath = filePath;
		this.active = active;
		this.description = StringUtil.notNullize(description);
		this.skipContent = skipContent;
		this.showAllMessages = showAllMessages;
	}

	/**
	 * Copy constructor
	 *
	 * @param other Configuration to copy
	 */
	public LogFileConfiguration(@NotNull LogFileConfiguration other) {
		this.id = other.id;
		this.alias = other.alias;
		this.filePath = other.filePath;
		this.active = other.active;
		this.description = other.description;
		this.skipContent = other.skipContent;
		this.showAllMessages = other.showAllMessages;
	}

	// === VARIABLE RESOLUTION ===

	/**
	 * Resolve variables in file path
	 *
	 * Supported variables:
	 * - $CATALINA_BASE or ${CATALINA_BASE}
	 * - $CATALINA_HOME or ${CATALINA_HOME}
	 * - $DATE or ${DATE} (current date in yyyy-MM-dd format)
	 * - Any custom variables passed in the variables map
	 *
	 * @param variables Map of variable names to values
	 * @return Resolved file path
	 */
	@NotNull
	public String resolveFilePath(@NotNull Map<String, String> variables) {
		String resolved = filePath;

		// Create a copy of variables and add standard ones
		Map<String, String> allVariables = new HashMap<>(variables);
		allVariables.put("DATE", LocalDate.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT)));

		// Replace all variables
		Matcher matcher = VARIABLE_PATTERN.matcher(resolved);
		StringBuffer sb = new StringBuffer();

		while (matcher.find()) {
			String varName = matcher.group(1);
			String value = allVariables.get(varName);
			if (value != null) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
			}
		}
		matcher.appendTail(sb);

		return sb.toString();
	}

	/**
	 * Convenience method for resolving with Catalina paths
	 *
	 * @param catalinaBase CATALINA_BASE path
	 * @param catalinaHome CATALINA_HOME path
	 * @return Resolved file path
	 */
	@NotNull
	public String resolveFilePath(@Nullable String catalinaBase, @Nullable String catalinaHome) {
		Map<String, String> variables = new HashMap<>();
		if (catalinaBase != null) {
			variables.put("CATALINA_BASE", catalinaBase);
		}
		if (catalinaHome != null) {
			variables.put("CATALINA_HOME", catalinaHome);
		}
		return resolveFilePath(variables);
	}

	/**
	 * Get the resolved path as a Path object
	 *
	 * @param variables Variable map for resolution
	 * @return Path object
	 */
	@NotNull
	public Path getResolvedPath(@NotNull Map<String, String> variables) {
		return Paths.get(resolveFilePath(variables));
	}

	// === VALIDATION ===

	/**
	 * Check if this configuration is valid
	 *
	 * @return true if valid
	 */
	public boolean isValid() {
		return !StringUtil.isEmpty(id) &&
				!StringUtil.isEmpty(alias) &&
				!StringUtil.isEmpty(filePath);
	}

	/**
	 * Validate and throw exception if invalid
	 *
	 * @throws IllegalStateException if validation fails
	 */
	public void validate() {
		if (StringUtil.isEmpty(id)) {
			throw new IllegalStateException("Log file id cannot be empty");
		}
		if (StringUtil.isEmpty(alias)) {
			throw new IllegalStateException("Log file alias cannot be empty");
		}
		if (StringUtil.isEmpty(filePath)) {
			throw new IllegalStateException("Log file path cannot be empty");
		}
	}

	// === UTILITY METHODS ===

	/**
	 * Get display name for UI
	 *
	 * @return User-friendly display name
	 */
	@NotNull
	public String getDisplayName() {
		if (!description.trim().isEmpty()) {
			return alias + " - " + description;
		}
		return alias;
	}

	/**
	 * Check if this is a default Tomcat log file
	 */
	public boolean isDefaultTomcatLog() {
		return "catalina".equals(id) || "localhost".equals(id) ||
				"manager".equals(id) || "host-manager".equals(id);
	}

	/**
	 * Get the file pattern for log rotation matching
	 *
	 * @return Pattern with wildcards for matching rotated logs
	 */
	@NotNull
	public String getFilePattern() {
		// If the path already contains wildcards, return as-is
		if (filePath.contains("*") || filePath.contains("?")) {
			return filePath;
		}

		// Add wildcard for date-based rotation
		return filePath + ".*";
	}

	// === FACTORY METHODS ===

	/**
	 * Create Catalina log configuration
	 */
	@NotNull
	public static LogFileConfiguration createCatalinaLog() {
		return new LogFileConfiguration(
				"catalina",
				"Catalina",
				"$CATALINA_BASE/logs/catalina.out",
				true,
				"Main Tomcat server log",
				false,
				true
		);
	}

	/**
	 * Create Localhost log configuration
	 */
	@NotNull
	public static LogFileConfiguration createLocalhostLog() {
		return new LogFileConfiguration(
				"localhost",
				"Localhost",
				"$CATALINA_BASE/logs/localhost.$DATE.log",
				true,
				"Application logs for default host",
				true,
				true
		);
	}

	/**
	 * Create Access log configuration
	 */
	@NotNull
	public static LogFileConfiguration createAccessLog() {
		return new LogFileConfiguration(
				"access",
				"Access",
				"$CATALINA_BASE/logs/localhost_access_log.$DATE.txt",
				false,
				"HTTP access logs",
				true,
				true
		);
	}

	/**
	 * Create Manager log configuration
	 */
	@NotNull
	public static LogFileConfiguration createManagerLog() {
		return new LogFileConfiguration(
				"manager",
				"Manager",
				"$CATALINA_BASE/logs/manager.$DATE.log",
				false,
				"Tomcat Manager application log",
				true,
				true
		);
	}

	/**
	 * Create Host Manager log configuration
	 */
	@NotNull
	public static LogFileConfiguration createHostManagerLog() {
		return new LogFileConfiguration(
				"host-manager",
				"Host-Manager",
				"$CATALINA_BASE/logs/host-manager.$DATE.log",
				false,
				"Tomcat Host Manager log",
				true,
				true
		);
	}

	// === GETTERS AND SETTERS ===

	/**
	 * Get the unique identifier
	 */
	@NotNull
	public String getId() {
		return StringUtil.notNullize(id);
	}

	/**
	 * Set the unique identifier
	 */
	public void setId(@NotNull String id) {
		this.id = StringUtil.notNullize(id);
	}

	@NotNull
	public String getAlias() {
		return StringUtil.notNullize(alias);
	}

	public void setAlias(@NotNull String alias) {
		this.alias = StringUtil.notNullize(alias);
	}

	@NotNull
	public String getFilePath() {
		return StringUtil.notNullize(filePath);
	}

	public void setFilePath(@NotNull String filePath) {
		this.filePath = StringUtil.notNullize(filePath);
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	@NotNull
	public String getDescription() {
		return StringUtil.notNullize(description);
	}

	public void setDescription(@NotNull String description) {
		this.description = StringUtil.notNullize(description);
	}

	public boolean isSkipContent() {
		return skipContent;
	}

	public void setSkipContent(boolean skipContent) {
		this.skipContent = skipContent;
	}

	public boolean isShowAllMessages() {
		return showAllMessages;
	}

	public void setShowAllMessages(boolean showAllMessages) {
		this.showAllMessages = showAllMessages;
	}

	// === COMPATIBILITY METHODS ===

	/**
	 * Compatibility method for existing code
	 */
	@NotNull
	public String getPath() {
		return getFilePath();
	}

	/**
	 * Compatibility method for existing code
	 */
	public boolean isEnabled() {
		return isActive();
	}

	// === OBJECT METHODS ===

	@Override
	public LogFileConfiguration clone() {
		try {
			LogFileConfiguration cloned = (LogFileConfiguration) super.clone();
			// Ensure all string fields are not null
			cloned.id = StringUtil.notNullize(this.id);
			cloned.alias = StringUtil.notNullize(this.alias);
			cloned.filePath = StringUtil.notNullize(this.filePath);
			cloned.description = StringUtil.notNullize(this.description);
			return cloned;
		} catch (CloneNotSupportedException e) {
			// Should never happen
			return new LogFileConfiguration(this);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof LogFileConfiguration)) return false;

		LogFileConfiguration that = (LogFileConfiguration) o;

		return active == that.active &&
				skipContent == that.skipContent &&
				showAllMessages == that.showAllMessages &&
				Objects.equals(id, that.id) &&
				Objects.equals(alias, that.alias) &&
				Objects.equals(filePath, that.filePath) &&
				Objects.equals(description, that.description);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, alias, filePath, active,
				description, skipContent, showAllMessages);
	}

	@Override
	public String toString() {
		return String.format(
				"LogFileConfiguration{id='%s', alias='%s', path='%s', active=%s}",
				id, alias, filePath, active
		);
	}
}