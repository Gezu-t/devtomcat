package com.dev.idea.plugins.tomcat.logging;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tomcat Log Manager
 *
 * Centralized management of console views and log file monitoring for the
 * Dev Tomcat plugin. This manager provides:
 * - Multiple named console view management
 * - Log file monitoring configuration
 * - Thread-safe operations
 * - Proper resource disposal
 * - Integration with IntelliJ's console system
 *
 * @author Dev Tomcat Team
 * @see ConsoleView
 * @see LogFileConfiguration
 */
public class TomcatLogManager implements Disposable {

	private static final Logger LOG = Logger.getInstance(TomcatLogManager.class);

	// Console view management
	@NotNull private final Project project;
	@NotNull private final Map<String, ConsoleView> consoleViews = new ConcurrentHashMap<>();
	@NotNull private final Map<String, LogFileMonitor> logFileMonitors = new ConcurrentHashMap<>();
	@NotNull private final List<LogManagerListener> listeners = new CopyOnWriteArrayList<>();

	@Nullable private ConsoleView mainConsoleView;
	private volatile boolean disposed = false;

	/**
	 * Create a new log manager for the project
	 *
	 * @param project The current project
	 */
	public TomcatLogManager(@NotNull Project project) {
		this.project = project;
		Disposer.register(project, this);
		LOG.debug("Created Tomcat Log Manager for project: " + project.getName());
	}

	// === CONSOLE VIEW MANAGEMENT ===

	/**
	 * Set the main console view
	 *
	 * @param consoleView The main console view
	 */
	public void setMainConsoleView(@NotNull ConsoleView consoleView) {
		checkNotDisposed();

		if (mainConsoleView != null && mainConsoleView != consoleView) {
			removeConsoleView("main");
		}

		this.mainConsoleView = consoleView;
		consoleViews.put("main", consoleView);

		LOG.debug("Set main console view");
		notifyListeners(listener -> listener.onConsoleViewAdded("main", consoleView));
	}

	/**
	 * Get the main console view
	 *
	 * @return The main console view or null
	 */
	@Nullable
	public ConsoleView getMainConsoleView() {
		return mainConsoleView;
	}

	/**
	 * Add a named console view
	 *
	 * @param name The console name
	 * @param consoleView The console view
	 */
	public void addConsoleView(@NotNull String name, @NotNull ConsoleView consoleView) {
		checkNotDisposed();

		ConsoleView previous = consoleViews.put(name, consoleView);
		if (previous != null && previous != consoleView) {
			LOG.debug("Replaced console view: " + name);
		}

		LOG.debug("Added console view: " + name);
		notifyListeners(listener -> listener.onConsoleViewAdded(name, consoleView));
	}

	/**
	 * Remove a console view
	 *
	 * @param name The console name to remove
	 * @return The removed console view or null
	 */
	@Nullable
	public ConsoleView removeConsoleView(@NotNull String name) {
		checkNotDisposed();

		ConsoleView removed = consoleViews.remove(name);
		if (removed != null) {
			if ("main".equals(name) && removed == mainConsoleView) {
				mainConsoleView = null;
			}

			LOG.debug("Removed console view: " + name);
			notifyListeners(listener -> listener.onConsoleViewRemoved(name, removed));
		}

		return removed;
	}

	/**
	 * Get a console view by name
	 *
	 * @param name The console name
	 * @return The console view or null
	 */
	@Nullable
	public ConsoleView getConsoleView(@NotNull String name) {
		return consoleViews.get(name);
	}

	/**
	 * Get all console view names
	 *
	 * @return Array of console names
	 */
	@NotNull
	public String[] getConsoleViewNames() {
		return consoleViews.keySet().toArray(new String[0]);
	}

	/**
	 * Get all console views
	 *
	 * @return Map of console names to views
	 */
	@NotNull
	public Map<String, ConsoleView> getAllConsoleViews() {
		return new HashMap<>(consoleViews);
	}

	/**
	 * Check if a console view exists
	 *
	 * @param name The console name
	 * @return true if the console exists
	 */
	public boolean hasConsoleView(@NotNull String name) {
		return consoleViews.containsKey(name);
	}

	// === LOG FILE MONITORING ===

	/**
	 * Add log file monitoring
	 *
	 * @param config The log file configuration
	 */
	public void addLogFile(@NotNull LogFileConfiguration config) {
		checkNotDisposed();

		if (!config.isValid()) {
			LOG.warn("Invalid log file configuration: " + config);
			return;
		}

		LogFileMonitor monitor = new LogFileMonitor(config);
		LogFileMonitor previous = logFileMonitors.put(config.getId(), monitor);

		if (previous != null) {
			previous.stop();
			LOG.debug("Replaced log file monitor: " + config.getId());
		}

		// Start monitoring if active
		if (config.isActive()) {
			monitor.start();
		}

		LOG.debug("Added log file monitor: " + config.getId() + " at " + config.getFilePath());
		notifyListeners(listener -> listener.onLogFileAdded(config));
	}

	/**
	 * Remove log file monitoring
	 *
	 * @param id The log file ID
	 */
	public void removeLogFile(@NotNull String id) {
		checkNotDisposed();

		LogFileMonitor monitor = logFileMonitors.remove(id);
		if (monitor != null) {
			monitor.stop();
			LOG.debug("Removed log file monitor: " + id);
			notifyListeners(listener -> listener.onLogFileRemoved(id));
		}
	}

	/**
	 * Get log file configuration
	 *
	 * @param id The log file ID
	 * @return The configuration or null
	 */
	@Nullable
	public LogFileConfiguration getLogFileConfiguration(@NotNull String id) {
		LogFileMonitor monitor = logFileMonitors.get(id);
		return monitor != null ? monitor.getConfiguration() : null;
	}

	/**
	 * Get all log file configurations
	 *
	 * @return List of all configurations
	 */
	@NotNull
	public List<LogFileConfiguration> getAllLogFileConfigurations() {
		List<LogFileConfiguration> configs = new ArrayList<>();
		for (LogFileMonitor monitor : logFileMonitors.values()) {
			configs.add(monitor.getConfiguration());
		}
		return configs;
	}

	/**
	 * Update log file monitoring state
	 *
	 * @param id The log file ID
	 * @param active Whether to activate monitoring
	 */
	public void setLogFileActive(@NotNull String id, boolean active) {
		checkNotDisposed();

		LogFileMonitor monitor = logFileMonitors.get(id);
		if (monitor != null) {
			monitor.getConfiguration().setActive(active);
			if (active) {
				monitor.start();
			} else {
				monitor.stop();
			}
			LOG.debug("Set log file " + id + " active: " + active);
		}
	}

	/**
	 * Clear all log files
	 */
	public void clearAllLogFiles() {
		checkNotDisposed();

		for (LogFileMonitor monitor : logFileMonitors.values()) {
			monitor.stop();
		}
		logFileMonitors.clear();

		LOG.debug("Cleared all log file monitors");
		notifyListeners(LogManagerListener::onAllLogFilesRemoved);
	}

	// === UTILITY METHODS ===

	/**
	 * Write message to a specific console
	 *
	 * @param consoleName The console name
	 * @param message The message to write
	 * @param contentType The content type
	 */
	public void writeToConsole(@NotNull String consoleName,
							   @NotNull String message,
							   @NotNull ConsoleViewContentType contentType) {
		ConsoleView console = getConsoleView(consoleName);
		if (console != null) {
			console.print(message + "\n", contentType);
		} else {
			LOG.warn("Console not found: " + consoleName);
		}
	}

	/**
	 * Clear all console views
	 */
	public void clearAll() {
		checkNotDisposed();

		// Clear console views
		for (Map.Entry<String, ConsoleView> entry : consoleViews.entrySet()) {
			entry.getValue().clear();
		}

		LOG.debug("Cleared all console views");
	}

	/**
	 * Get the project
	 *
	 * @return The project instance
	 */
	@NotNull
	public Project getProject() {
		return project;
	}

	// === LISTENER MANAGEMENT ===

	/**
	 * Add a log manager listener
	 *
	 * @param listener The listener to add
	 */
	public void addListener(@NotNull LogManagerListener listener) {
		listeners.add(listener);
	}

	/**
	 * Remove a log manager listener
	 *
	 * @param listener The listener to remove
	 */
	public void removeListener(@NotNull LogManagerListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Notify all listeners
	 */
	private void notifyListeners(@NotNull java.util.function.Consumer<LogManagerListener> action) {
		for (LogManagerListener listener : listeners) {
			try {
				action.accept(listener);
			} catch (Exception e) {
				LOG.error("Error notifying listener", e);
			}
		}
	}

	// === DISPOSAL ===

	@Override
	public void dispose() {
		if (disposed) {
			return;
		}

		disposed = true;

		// Stop all monitors
		for (LogFileMonitor monitor : logFileMonitors.values()) {
			monitor.stop();
		}

		// Clear collections
		consoleViews.clear();
		logFileMonitors.clear();
		listeners.clear();
		mainConsoleView = null;

		LOG.debug("Disposed Tomcat Log Manager");
	}

	/**
	 * Check if disposed
	 */
	private void checkNotDisposed() {
		if (disposed) {
			throw new IllegalStateException("Log manager is already disposed");
		}
	}

	// === INNER CLASSES ===

	/**
	 * Log file monitor (placeholder for actual implementation)
	 */
	private static class LogFileMonitor {
		private final LogFileConfiguration configuration;
		private volatile boolean running = false;

		LogFileMonitor(@NotNull LogFileConfiguration configuration) {
			this.configuration = configuration;
		}

		void start() {
			if (!running) {
				running = true;
				LOG.debug("Started monitoring: " + configuration.getAlias());
				// TODO: Implement actual file monitoring
			}
		}

		void stop() {
			if (running) {
				running = false;
				LOG.debug("Stopped monitoring: " + configuration.getAlias());
				// TODO: Stop file monitoring
			}
		}

		@NotNull
		LogFileConfiguration getConfiguration() {
			return configuration;
		}
	}

	/**
	 * Listener interface for log manager events
	 */
	public interface LogManagerListener {
		/**
		 * Called when a console view is added
		 */
		default void onConsoleViewAdded(@NotNull String name, @NotNull ConsoleView consoleView) {}

		/**
		 * Called when a console view is removed
		 */
		default void onConsoleViewRemoved(@NotNull String name, @NotNull ConsoleView consoleView) {}

		/**
		 * Called when a log file is added
		 */
		default void onLogFileAdded(@NotNull LogFileConfiguration configuration) {}

		/**
		 * Called when a log file is removed
		 */
		default void onLogFileRemoved(@NotNull String alias) {}

		/**
		 * Called when all log files are removed
		 */
		default void onAllLogFilesRemoved() {}
	}
}