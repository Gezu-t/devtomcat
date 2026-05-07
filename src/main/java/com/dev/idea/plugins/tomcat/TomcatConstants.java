package com.dev.idea.plugins.tomcat;

/**
 * Centralized constants for the DevTomcat plugin.
 * Eliminates magic strings scattered across UI and configuration code.
 */
public final class TomcatConstants {

    private TomcatConstants() {}

    // --- Plugin Identity ---
    public static final String NOTIFICATION_GROUP_ID = "DevTomcat";

    // --- Server Modes ---
    public static final String MODE_LOCAL = "Local";
    public static final String MODE_REMOTE = "Remote";

    // --- Startup/Connection Modes ---
    public static final String RUN_MODE = "Run";
    public static final String DEBUG_MODE = "Debug";
    public static final String COVERAGE_MODE = "Coverage";

    // --- Debug Transport ---
    public static final String TRANSPORT_SOCKET = "Socket";

    // --- JDWP Transport Names (JVM spec identifiers for -agentlib:jdwp) ---
    public static final String JDWP_TRANSPORT_SOCKET = "dt_socket";
    /**
     * JDWP connection format for Java 9 and later. Args: transport name, port.
     * The {@code *:port} address syntax was added in Java 9 (JDK-8041435) so the
     * agent listens on all interfaces explicitly. Earlier JVMs reject this form
     * with {@code TRANSPORT_INIT(510)} and refuse to start.
     */
    public static final String JDWP_CONNECTION_FORMAT = "%s,server=y,suspend=n,address=*:%d";
    /**
     * JDWP connection format for Java 8 and earlier. Args: transport name, port.
     * Omits the {@code *:} wildcard host because Java 8 does not understand it.
     * The no-host form binds to all interfaces on Java 8 (same effective coverage
     * as the wildcard on later JVMs).
     */
    public static final String JDWP_CONNECTION_FORMAT_JAVA_8 = "%s,server=y,suspend=n,address=%d";
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
    public static final int DEFAULT_PORT_NUMBER = 8080;

    // --- Environment Variable Names (passed to Tomcat process) ---
    public static final String ENV_HTTP_PORT = "TOMCAT_HTTP_PORT";
    public static final String ENV_SHUTDOWN_PORT = "TOMCAT_SHUTDOWN_PORT";
    public static final String ENV_HTTPS_PORT = "TOMCAT_HTTPS_PORT";
    public static final String ENV_JMX_PORT = "TOMCAT_JMX_PORT";
    public static final String ENV_AJP_PORT = "TOMCAT_AJP_PORT";
    public static final String ENV_DEBUG_PORT = "TOMCAT_DEBUG_PORT";
    public static final String ENV_JDWP_OPTS = "TOMCAT_JDWP_OPTS";
    public static final String ENV_JAVA_OPTS = "JAVA_OPTS";
    public static final String ENV_CATALINA_OPTS = "CATALINA_OPTS";
    public static final String ENV_JPDA_OPTS = "JPDA_OPTS";
    public static final String ENV_JPDA_ADDRESS = "JPDA_ADDRESS";
    public static final String ENV_JPDA_TRANSPORT = "JPDA_TRANSPORT";
    public static final String ENV_JPDA_SUSPEND = "JPDA_SUSPEND";

    // --- Artifact Naming Suffixes (IntelliJ convention) ---
    public static final String ARTIFACT_SUFFIX_WAR_EXPLODED = ":war exploded";
    public static final String ARTIFACT_SUFFIX_EAR_EXPLODED = ":ear exploded";
    public static final String ARTIFACT_SUFFIX_WEBAPP_EXPLODED = ":web application exploded";
    public static final String ARTIFACT_SUFFIX_WAR = ":war";
    public static final String ARTIFACT_SUFFIX_EAR = ":ear";
    public static final String ARTIFACT_SUFFIX_WEBAPP_ARCHIVE = ":web application archive";

    // --- Deployment ---
    public static final String DEPLOY_OPTION_ARTIFACT = "Artifact...";
    public static final String DEPLOY_OPTION_EXTERNAL = "External Source...";

    // --- Catalina ---
    public static final String CATALINA_SCRIPT = "catalina";
    public static final String CATALINA_JPDA = "jpda";
    public static final String CATALINA_RUN = "run";
    public static final String CATALINA_START = "start";
    public static final String CATALINA_STOP = "stop";

    // --- CATALINA_BASE directory layout ---
    public static final String DIR_CONF = "conf";
    public static final String DIR_TEMP = "temp";
    public static final String DIR_LOGS = "logs";
    public static final String DIR_WEBAPPS = "webapps";
    public static final String DIR_WORK = "work";

    // --- Tomcat config file paths (relative to CATALINA_HOME or CATALINA_BASE) ---
    public static final String CONFIG_SERVER_XML = "conf/server.xml";
    public static final String CONFIG_WEB_XML = "conf/web.xml";
    public static final String CONFIG_CONTEXT_XML = "conf/context.xml";
    public static final String CONFIG_TOMCAT_USERS_XML = "conf/tomcat-users.xml";
    public static final String CONFIG_LOGGING_PROPERTIES = "conf/logging.properties";
    public static final String CONFIG_CATALINA_PROPERTIES = "conf/catalina.properties";
    public static final String CONTEXT_XML_DIR = "conf/Catalina/localhost";

    // --- Tomcat classpath JARs (relative to CATALINA_HOME) ---
    public static final String JAR_BOOTSTRAP = "bin/bootstrap.jar";
    public static final String JAR_TOMCAT_JULI = "bin/tomcat-juli.jar";

    // --- Connector protocol identifiers ---
    public static final String PROTOCOL_HTTP = "HTTP/1.1";
    public static final String PROTOCOL_HTTPS = "org.apache.coyote.http11.Http11NioProtocol";
    public static final String PROTOCOL_AJP = "AJP/1.3";
    public static final String SHUTDOWN_COMMAND = "SHUTDOWN";

    // --- Deployment ---
    public static final String ROOT_CONTEXT_NAME = "ROOT";
    public static final String WEB_INF = "WEB-INF";
    public static final String WEB_INF_LIB = "lib";
    public static final String WEB_INF_CLASSES = "classes";
    public static final String WEB_INF_CLASSES_PATH = WEB_INF + "/" + WEB_INF_CLASSES;
    public static final String WEB_INF_LIB_PATH = WEB_INF + "/" + WEB_INF_LIB;

    // --- Default Context ---
    public static final String DEFAULT_CONTEXT_PATH = "/";

    // --- Display Strings ---
    public static final String PASSWORD_MASKED = "****";
    public static final String PASSWORD_EMPTY = "(empty)";
}
