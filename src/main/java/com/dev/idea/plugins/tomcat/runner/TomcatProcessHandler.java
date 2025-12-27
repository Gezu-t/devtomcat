package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Professional Enterprise Tomcat Process Handler
 * Provides comprehensive enterprise-level process management capabilities
 *
 * Enterprise Process Management Features:
 * - Professional deployment status monitoring with real-time feedback
 * - Advanced error detection and intelligent recovery suggestions
 * - Comprehensive log parsing for intelligent deployment tracking
 * - Professional server startup/shutdown monitoring with metrics
 * - Enterprise memory and performance monitoring
 * - Advanced console integration with structured output management
 *
 * Author: Gezahegn Lemma (Gezu)
 * Project: DevTomcat Plugin
 * Created: 6/9/25
 */
public class TomcatProcessHandler extends KillableColoredProcessHandler implements ProcessListener {

	// Professional deployment status patterns
	private static final Pattern ENTERPRISE_DEPLOYMENT_PATTERN = Pattern.compile(
			"(?i).*(deploy|deployment|artifact|exploded|war).*(?:started|deployed|successful|completed|finished).*"
	);

	private static final Pattern ENTERPRISE_STARTUP_PATTERN = Pattern.compile(
			"(?i).*server startup in (\\d+).*(?:ms|milliseconds).*"
	);

	private static final Pattern ENTERPRISE_JMX_PATTERN = Pattern.compile(
			"(?i).*jmx.*(?:started|enabled|listening).*port\\s*(\\d+).*"
	);

	private static final Pattern ENTERPRISE_ERROR_PATTERN = Pattern.compile(
			"(?i).*(error|exception|failed|severe|fatal|cannot|unable).*"
	);

	private static final Pattern ENTERPRISE_WARNING_PATTERN = Pattern.compile(
			"(?i).*(warn|warning|deprecated|problem).*"
	);

	private static final Pattern ENTERPRISE_CONTEXT_PATTERN = Pattern.compile(
			"(?i).*context\\s+\\[([^\\]]+)\\].*(?:started|deployed|initialized).*"
	);

	private static final Pattern ENTERPRISE_MEMORY_PATTERN = Pattern.compile(
			"(?i).*memory:\\s*(\\d+).*(?:mb|gb|bytes).*"
	);

	private static final Pattern ENTERPRISE_PERFORMANCE_PATTERN = Pattern.compile(
			"(?i).*(performance|throughput|response time|latency).*"
	);

	private final TomcatRunConfiguration configuration;
	private final TomcatDeploymentLogger deploymentLogger;
	private final String configurationName;

	// Professional monitoring state
	private boolean serverStartupDetected = false;
	private boolean deploymentCompleted = false;
	private boolean jmxEnabled = false;
	private long startupTime = System.currentTimeMillis();
	private int errorCount = 0;
	private int warningCount = 0;
	private StringBuilder performanceMetrics = new StringBuilder();

	public TomcatProcessHandler(@NotNull Process process,
								@NotNull String commandLine,
								@NotNull Charset charset,
								@NotNull TomcatDeploymentLogger deploymentLogger,
								@NotNull TomcatRunConfiguration configuration) {
		super(process, commandLine, charset);

		this.configuration = configuration;
		this.deploymentLogger = deploymentLogger;
		this.configurationName = configuration.getName();
		this.jmxEnabled = configuration.isJmxEnabled();

		// Add process listener for professional monitoring
		addProcessListener(this);

		System.out.println("DevTomcat: Professional process handler initialized for " + configurationName);

		// Initialize enterprise monitoring
		initializeEnterpriseMonitoring();
	}

	/**
	 * Initialize enterprise-grade monitoring capabilities
	 */
	private void initializeEnterpriseMonitoring() {
		// Professional feature detection and logging
		StringBuilder features = new StringBuilder("DevTomcat Process Monitoring Features: ");

		if (configuration.isJmxEnabled()) {
			features.append("JMX(").append(configuration.getJmxPort()).append(") ");
		}

		if (configuration.isHotDeploymentEnabled()) {
			features.append("HotDeploy ");
		}

		if (!configuration.getLogFileConfigurations().isEmpty()) {
			features.append("LogFiles(").append(configuration.getLogFileConfigurations().size()).append(") ");
		}

		System.out.println("DevTomcat: " + features.toString());
		deploymentLogger.logServerInfo("Enterprise process monitoring initialized");
	}

	@Override
	public void startNotified(@NotNull ProcessEvent event) {
		deploymentLogger.logServerInfo("Professional Tomcat process started");
		deploymentLogger.logDeploymentStart(configurationName);

		// Professional startup monitoring
		System.out.println("DevTomcat: Enterprise process monitoring active for " + configurationName);

		// Initialize performance tracking
		startupTime = System.currentTimeMillis();
		performanceMetrics.append("Startup initiated at ").append(startupTime).append("; ");
	}

	@Override
	public void processTerminated(@NotNull ProcessEvent event) {
		long duration = System.currentTimeMillis() - startupTime;
		int exitCode = event.getExitCode();

		if (exitCode == 0) {
			deploymentLogger.logServerInfo("Professional Tomcat process terminated successfully");
			System.out.println("DevTomcat: Enterprise process completed successfully in " + duration + "ms");
		} else {
			deploymentLogger.logServerError("Professional Tomcat process terminated with exit code: " + exitCode);
			System.err.println("DevTomcat: Enterprise process terminated with errors (exit code: " + exitCode + ")");
		}

		// Professional session summary
		generateSessionSummary(duration, exitCode);
	}

		@Override
		public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
			String text = event.getText();

			if (StringUtil.isNotEmpty(text)) {
				// Professional output analysis
				Key<?> typedOutput = outputType;
				analyzeEnterpriseOutput(text, typedOutput);
			}
		}

	/**
	 * Analyze output with enterprise-level intelligence
	 */
		private void analyzeEnterpriseOutput(@NotNull String text, @NotNull Key<?> outputType) {
			String cleanText = text.trim();

		if (cleanText.isEmpty()) {
			return;
		}

		// Professional deployment status detection
		analyzeDeploymentStatus(cleanText, outputType);

		// Enterprise error and warning detection
		analyzeErrorsAndWarnings(cleanText, outputType);

		// Professional performance monitoring
		analyzePerformanceMetrics(cleanText);

		// Enterprise JMX monitoring
		analyzeJmxStatus(cleanText);

		// Professional context deployment tracking
		analyzeContextDeployment(cleanText);

		// Enterprise memory monitoring
		analyzeMemoryUsage(cleanText);
	}

	/**
	 * Analyze deployment status with professional intelligence
	 */
		private void analyzeDeploymentStatus(@NotNull String text, @NotNull Key<?> outputType) {
		// Professional startup detection
		Matcher startupMatcher = ENTERPRISE_STARTUP_PATTERN.matcher(text);
		if (startupMatcher.find() && !serverStartupDetected) {
			serverStartupDetected = true;
			String startupTimeStr = startupMatcher.group(1);
			long duration = Long.parseLong(startupTimeStr);

			deploymentLogger.logServerStartup(duration);
			System.out.println("DevTomcat: Professional server startup completed in " + duration + "ms");

			performanceMetrics.append("Server startup: ").append(duration).append("ms; ");
		}

		// Professional deployment completion detection
		Matcher deploymentMatcher = ENTERPRISE_DEPLOYMENT_PATTERN.matcher(text);
		if (deploymentMatcher.find() && !deploymentCompleted) {
			deploymentCompleted = true;
			long totalTime = System.currentTimeMillis() - startupTime;

			deploymentLogger.logDeploymentSuccess(configurationName, totalTime);
			System.out.println("DevTomcat: Professional deployment completed successfully");

			performanceMetrics.append("Total deployment: ").append(totalTime).append("ms; ");
		}
	}

	/**
	 * Analyze errors and warnings with enterprise intelligence
	 */
		private void analyzeErrorsAndWarnings(@NotNull String text, @NotNull Key<?> outputType) {
		// Professional error detection
		if (ENTERPRISE_ERROR_PATTERN.matcher(text).find()) {
			errorCount++;
			deploymentLogger.logServerError("Enterprise error detected: " + text);

			// Professional error analysis and suggestions
			String suggestion = getErrorSuggestion(text);
			if (!suggestion.isEmpty()) {
				deploymentLogger.logServerInfo("Suggestion: " + suggestion);
			}
		}

		// Professional warning detection
		if (ENTERPRISE_WARNING_PATTERN.matcher(text).find()) {
			warningCount++;
			deploymentLogger.logServerWarning("Enterprise warning: " + text);

			// Professional warning analysis
			String optimization = getWarningOptimization(text);
			if (!optimization.isEmpty()) {
				deploymentLogger.logServerInfo("Optimization: " + optimization);
			}
		}
	}

	/**
	 * Analyze performance metrics with enterprise monitoring
	 */
	private void analyzePerformanceMetrics(@NotNull String text) {
		Matcher perfMatcher = ENTERPRISE_PERFORMANCE_PATTERN.matcher(text);
		if (perfMatcher.find()) {
			performanceMetrics.append("Performance data: ").append(text.trim()).append("; ");
			deploymentLogger.logServerInfo("Performance metric detected: " + text.trim());
		}
	}

	/**
	 * Analyze JMX status with professional monitoring
	 */
	private void analyzeJmxStatus(@NotNull String text) {
		if (jmxEnabled) {
			Matcher jmxMatcher = ENTERPRISE_JMX_PATTERN.matcher(text);
			if (jmxMatcher.find()) {
				String port = jmxMatcher.group(1);
				deploymentLogger.logServerInfo("Professional JMX monitoring active on port " + port);
				performanceMetrics.append("JMX port: ").append(port).append("; ");
			}
		}
	}

	/**
	 * Analyze context deployment with enterprise tracking
	 */
	private void analyzeContextDeployment(@NotNull String text) {
		Matcher contextMatcher = ENTERPRISE_CONTEXT_PATTERN.matcher(text);
		if (contextMatcher.find()) {
			String contextName = contextMatcher.group(1);
			deploymentLogger.logServerInfo("Professional context deployed: " + contextName);
			performanceMetrics.append("Context: ").append(contextName).append("; ");
		}
	}

	/**
	 * Analyze memory usage with enterprise monitoring
	 */
	private void analyzeMemoryUsage(@NotNull String text) {
		Matcher memoryMatcher = ENTERPRISE_MEMORY_PATTERN.matcher(text);
		if (memoryMatcher.find()) {
			String memory = memoryMatcher.group(1);
			deploymentLogger.logServerInfo("Professional memory usage: " + memory);
			performanceMetrics.append("Memory: ").append(memory).append("; ");
		}
	}

	/**
	 * Get professional error suggestions
	 */
	private String getErrorSuggestion(@NotNull String errorText) {
		String lowerError = errorText.toLowerCase();

		if (lowerError.contains("port") && lowerError.contains("bind")) {
			return "Port already in use - check if another Tomcat instance is running";
		}

		if (lowerError.contains("classnotfound")) {
			return "Missing dependency - verify classpath and Maven/Gradle dependencies";
		}

		if (lowerError.contains("outofmemory")) {
			return "Increase heap size using -Xmx parameter in VM options";
		}

		if (lowerError.contains("permission")) {
			return "Check file permissions and Tomcat directory access rights";
		}

		if (lowerError.contains("context")) {
			return "Verify web.xml configuration and context path settings";
		}

		return "";
	}

	/**
	 * Get professional warning optimizations
	 */
	private String getWarningOptimization(@NotNull String warningText) {
		String lowerWarning = warningText.toLowerCase();

		if (lowerWarning.contains("deprecated")) {
			return "Update to newer API version for better performance and security";
		}

		if (lowerWarning.contains("memory") || lowerWarning.contains("gc")) {
			return "Consider tuning garbage collection settings for better performance";
		}

		if (lowerWarning.contains("ssl") || lowerWarning.contains("security")) {
			return "Review security configuration for production deployment";
		}

		return "";
	}

	/**
	 * Generate professional session summary
	 */
	private void generateSessionSummary(long duration, int exitCode) {
		StringBuilder summary = new StringBuilder();
		summary.append("\n=== DevTomcat Professional Session Summary ===\n");
		summary.append("Configuration: ").append(configurationName).append("\n");
		summary.append("Total Duration: ").append(duration).append("ms\n");
		summary.append("Exit Code: ").append(exitCode).append("\n");
		summary.append("Server Startup: ").append(serverStartupDetected ? "Success" : "Not Detected").append("\n");
		summary.append("Deployment: ").append(deploymentCompleted ? "Success" : "Not Completed").append("\n");
		summary.append("Errors: ").append(errorCount).append("\n");
		summary.append("Warnings: ").append(warningCount).append("\n");

		if (jmxEnabled) {
			summary.append("JMX Monitoring: Enabled (Port ").append(configuration.getJmxPort()).append(")\n");
		}

		if (configuration.isHotDeploymentEnabled()) {
			summary.append("Hot Deployment: Enabled\n");
		}

		if (performanceMetrics.length() > 0) {
			summary.append("Performance Metrics: ").append(performanceMetrics.toString()).append("\n");
		}

		summary.append("=== End Summary ===\n");

		deploymentLogger.logServerInfo(summary.toString());
		System.out.println("DevTomcat: " + summary.toString());
	}

	/**
	 * Get enterprise process status
	 */
	public String getEnterpriseProcessStatus() {
		StringBuilder status = new StringBuilder();
		status.append("DevTomcat Professional Status: ");

		if (serverStartupDetected) {
			status.append("Server-Started ");
		}

		if (deploymentCompleted) {
			status.append("Deployment-Complete ");
		}

		if (jmxEnabled) {
			status.append("JMX-Active ");
		}

		status.append("Errors:").append(errorCount);
		status.append(" Warnings:").append(warningCount);

		return status.toString();
	}

	/**
	 * Get professional performance summary
	 */
	public String getPerformanceSummary() {
		if (performanceMetrics.length() == 0) {
			return "DevTomcat: No performance metrics collected";
		}

		return "DevTomcat Performance Summary: " + performanceMetrics.toString();
	}

	/**
	 * Check if enterprise monitoring is active
	 */
	public boolean isEnterpriseMonitoringActive() {
		return serverStartupDetected || deploymentCompleted || jmxEnabled;
	}

	/**
	 * Get professional monitoring metrics
	 */
	public String getMonitoringMetrics() {
		StringBuilder metrics = new StringBuilder();
		metrics.append("DevTomcat Monitoring Metrics: ");
		metrics.append("Runtime: ").append(System.currentTimeMillis() - startupTime).append("ms, ");
		metrics.append("Errors: ").append(errorCount).append(", ");
		metrics.append("Warnings: ").append(warningCount).append(", ");
		metrics.append("Features: ");

		if (jmxEnabled) metrics.append("JMX ");
		if (configuration.isHotDeploymentEnabled()) metrics.append("HotDeploy ");
		if (!configuration.getLogFileConfigurations().isEmpty()) metrics.append("LogMonitoring ");

		return metrics.toString();
	}
}
