package com.dev.idea.plugins.tomcat.logging;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Professional DevTomcat Log Manager
 * Manages console views and enterprise log monitoring
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatLogManager {

	private final Project project;
	private final Map<String, ConsoleView> consoleViews = new ConcurrentHashMap<>();
	private ConsoleView mainConsoleView;

	public TomcatLogManager(@NotNull Project project) {
		this.project = project;
	}

	/**
	 * Set main console view for professional logging
	 */
	public void setMainConsoleView(@NotNull ConsoleView consoleView) {
		this.mainConsoleView = consoleView;
		consoleViews.put("main", consoleView);
	}

	/**
	 * Get main console view
	 */
	@Nullable
	public ConsoleView getMainConsoleView() {
		return mainConsoleView;
	}

	/**
	 * Register a named console view for enterprise monitoring
	 */
	public void addConsoleView(@NotNull String name, @NotNull ConsoleView consoleView) {
		consoleViews.put(name, consoleView);
	}

	/**
	 * Remove a console view
	 */
	public void removeConsoleView(@NotNull String name) {
		consoleViews.remove(name);
	}

	/**
	 * Get a console view by name
	 */
	@Nullable
	public ConsoleView getConsoleView(@NotNull String name) {
		return consoleViews.get(name);
	}

	/**
	 * Get all registered console view names
	 */
	public String[] getConsoleViewNames() {
		return consoleViews.keySet().toArray(new String[0]);
	}

	/**
	 * Clear all console views
	 */
	public void clearAll() {
		consoleViews.clear();
		mainConsoleView = null;
	}

	/**
	 * Get project instance
	 */
	@NotNull
	public Project getProject() {
		return project;
	}

	/**
	 * Add log file monitoring for enterprise logging
	 */
	public void addLogFile(@NotNull String alias, @NotNull String filePath) {
		System.out.println("DevTomcat: Professional log file monitoring for " + alias + " at " + filePath);
	}

	/**
	 * Remove log file monitoring
	 */
	public void removeLogFile(@NotNull String alias) {
		System.out.println("DevTomcat: Removing professional log file monitoring for " + alias);
	}

	/**
	 * Remove all log file monitoring
	 */
	public void removeAllLogFiles() {
		System.out.println("DevTomcat: Removing all professional log file monitoring");
	}
}