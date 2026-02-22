package com.dev.idea.plugins.tomcat;

/**
 * Centralized constants for the DevTomcat plugin.
 * Eliminates magic strings scattered across UI and configuration code.
 */
public final class TomcatConstants {

    private TomcatConstants() {}

    // --- Server Modes ---
    public static final String MODE_LOCAL = "Local";
    public static final String MODE_REMOTE = "Remote";

    // --- Startup/Connection Modes ---
    public static final String RUN_MODE = "Run";
    public static final String DEBUG_MODE = "Debug";
    public static final String COVERAGE_MODE = "Coverage";

    // --- Debug Transport ---
    public static final String TRANSPORT_SOCKET = "Socket";
    public static final String TRANSPORT_SHARED_MEMORY = "Shared Memory";

    // --- JDWP Transport Names (JVM spec identifiers for -agentlib:jdwp) ---
    public static final String JDWP_TRANSPORT_SOCKET = "dt_socket";
    public static final String JDWP_TRANSPORT_SHMEM = "dt_shmem";
    /** JDWP connection format: transport, server mode, suspend policy, address. Args: transport name, port. */
    public static final String JDWP_CONNECTION_FORMAT = "%s,server=y,suspend=n,address=%d";
    public static final String JDWP_AGENT_PREFIX = "-agentlib:jdwp=transport=";

    // --- Browser Defaults ---
    public static final String BROWSER_SYSTEM_DEFAULT = "System Default";

    // --- JRE ---
    public static final String JRE_PROJECT_DEFAULT = "Project default";

    // --- Before Launch ---
    public static final String TASK_BUILD = "Build";

    // --- Update Actions ---
    public static final String ACTION_UPDATE_RESOURCES = "Update resources";
    public static final String ACTION_UPDATE_CLASSES_AND_RESOURCES = "Update classes and resources";
    public static final String ACTION_REDEPLOY = "Redeploy";
    public static final String ACTION_RESTART_SERVER = "Restart server";
    public static final String ACTION_DO_NOTHING = "Do nothing";

    // --- Network Defaults ---
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "8080";

    // --- Deployment ---
    public static final String DEPLOY_OPTION_ARTIFACT = "Artifact...";
    public static final String DEPLOY_OPTION_EXTERNAL = "External Source...";

    // --- Catalina ---
    public static final String CATALINA_SCRIPT = "catalina";
    public static final String CATALINA_RUN = "run";
    public static final String CATALINA_STOP = "stop";

    // --- Default Context ---
    public static final String DEFAULT_CONTEXT_PATH = "/";

    // --- Display Strings ---
    public static final String PASSWORD_MASKED = "****";
    public static final String PASSWORD_EMPTY = "(empty)";
}
