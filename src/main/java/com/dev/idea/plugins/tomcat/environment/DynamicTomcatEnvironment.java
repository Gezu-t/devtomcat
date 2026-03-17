package com.dev.idea.plugins.tomcat.environment;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic Tomcat Environment Detector — registry-driven configuration.
 *
 * <p>All configuration reads are delegated to {@link RegistryHelper} which
 * eliminates try/catch boilerplate by providing null-safe accessors with
 * sensible defaults.
 *
 * <p>100% NULL-SAFE — All values have sensible defaults, no exceptions propagate.
 */
public final class DynamicTomcatEnvironment {

    // Registry keys
    private static final String REG_ENVIRONMENT_MODE     = "devtomcat.environment.mode";
    private static final String REG_DEFAULT_HTTP_PORT     = "devtomcat.default.http.port";
    private static final String REG_DEFAULT_SHUTDOWN_PORT = "devtomcat.default.shutdown.port";
    private static final String REG_DEFAULT_HTTPS_PORT    = "devtomcat.default.https.port";
    private static final String REG_DEFAULT_JMX_PORT      = "devtomcat.default.jmx.port";
    private static final String REG_JMX_ENABLED           = "devtomcat.enable.jmx.monitoring";
    private static final String REG_HTTPS_ENABLED         = "devtomcat.enable.https";
    private static final String REG_HOT_DEPLOYMENT        = "devtomcat.enable.hot.deployment";
    private static final String REG_DEFAULT_XMX           = "devtomcat.default.xmx";
    private static final String REG_DEFAULT_XMS           = "devtomcat.default.xms";
    private static final String REG_SHOW_TIMESTAMPS       = "devtomcat.log.show.timestamps";
    private static final String REG_FAST_SHUTDOWN         = "devtomcat.dev.fast.shutdown";
    private static final String REG_AUTO_BROWSER          = "devtomcat.auto.browser.launch";
    private static final String REG_AUTO_CONFIG           = "devtomcat.enable.auto.configuration";

    private static final String JDK_JAVA_OPTIONS_KEY = "JDK_JAVA_OPTIONS";
    private static final String ENV_JDK_JAVA_OPTIONS =
            "--add-opens=java.base/java.lang=ALL-UNNAMED " +
            "--add-opens=java.base/java.io=ALL-UNNAMED " +
            "--add-opens=java.base/java.util=ALL-UNNAMED " +
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED " +
            "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED";

    private DynamicTomcatEnvironment() {}

    // =========================================================================
    // Environment Mode
    // =========================================================================

    public enum EnvironmentMode {
        DEVELOPMENT("development"),
        STAGING("staging"),
        PRODUCTION("production");

        private final String value;

        EnvironmentMode(String value) { this.value = value; }

        @NotNull
        public String getValue() { return value; }

        @NotNull
        public static EnvironmentMode fromString(@Nullable String mode) {
            if (StringUtil.isEmpty(mode)) return DEVELOPMENT;
            String normalized = mode.toLowerCase().trim();
            if (normalized.contains("prod")) return PRODUCTION;
            if (normalized.contains("stag")) return STAGING;
            return DEVELOPMENT;
        }
    }

    /**
     * Detects environment mode using priority chain:
     * system property → Spring profile → env var → Registry → DEVELOPMENT.
     */
    @NotNull
    public static EnvironmentMode getCurrentMode() {
        String sysProp = System.getProperty("tomcat.environment");
        if (!StringUtil.isEmpty(sysProp)) return EnvironmentMode.fromString(sysProp);

        String springProfiles = System.getProperty("spring.profiles.active");
        if (!StringUtil.isEmpty(springProfiles)) return EnvironmentMode.fromString(springProfiles);

        String envVar = System.getenv("TOMCAT_ENV");
        if (!StringUtil.isEmpty(envVar)) return EnvironmentMode.fromString(envVar);

        envVar = System.getenv("SPRING_PROFILES_ACTIVE");
        if (!StringUtil.isEmpty(envVar)) return EnvironmentMode.fromString(envVar);

        String registryMode = RegistryHelper.getString(REG_ENVIRONMENT_MODE, "");
        if (!registryMode.isEmpty()) return EnvironmentMode.fromString(registryMode);

        return EnvironmentMode.DEVELOPMENT;
    }

    // =========================================================================
    // Port Configuration
    // =========================================================================

    public static int getHttpPort()     { return RegistryHelper.getInt(REG_DEFAULT_HTTP_PORT, 8080); }
    public static int getShutdownPort() { return RegistryHelper.getInt(REG_DEFAULT_SHUTDOWN_PORT, 8005); }
    public static int getHttpsPort()    { return RegistryHelper.getInt(REG_DEFAULT_HTTPS_PORT, 8443); }
    public static int getJmxPort()      { return RegistryHelper.getInt(REG_DEFAULT_JMX_PORT, 1099); }

    // =========================================================================
    // Feature Flags
    // =========================================================================

    public static boolean isJmxEnabled()            { return RegistryHelper.getBool(REG_JMX_ENABLED, false); }
    public static boolean isHttpsEnabled()          { return RegistryHelper.getBool(REG_HTTPS_ENABLED, false); }
    public static boolean isHotDeploymentEnabled()  { return RegistryHelper.getBool(REG_HOT_DEPLOYMENT, true); }
    public static boolean shouldShowTimestamps()    { return RegistryHelper.getBool(REG_SHOW_TIMESTAMPS, true); }
    public static boolean isFastShutdownEnabled()   { return RegistryHelper.getBool(REG_FAST_SHUTDOWN, false); }
    public static boolean shouldAutoLaunchBrowser() { return RegistryHelper.getBool(REG_AUTO_BROWSER, true); }
    public static boolean isAutoConfigEnabled()     { return RegistryHelper.getBool(REG_AUTO_CONFIG, true); }

    // =========================================================================
    // Memory Configuration
    // =========================================================================

    @NotNull public static String getXmxValue() { return RegistryHelper.getString(REG_DEFAULT_XMX, "1024m"); }
    @NotNull public static String getXmsValue() { return RegistryHelper.getString(REG_DEFAULT_XMS, "512m"); }

    // =========================================================================
    // JVM Options Builders
    // =========================================================================

    @NotNull
    public static String buildJavaOpts() {
        StringBuilder opts = new StringBuilder();
        EnvironmentMode mode = getCurrentMode();

        opts.append("-Xmx").append(getXmxValue()).append(" ");
        opts.append("-Xms").append(getXmsValue()).append(" ");
        opts.append("-Dfile.encoding=UTF-8 ");

        switch (mode) {
            case PRODUCTION -> opts.append("-server -XX:+UseG1GC -XX:MaxGCPauseMillis=200 ")
                                    .append("-XX:+UseStringDeduplication ")
                                    .append("-Djava.security.egd=file:/dev/./urandom ");
            case STAGING    -> opts.append("-server -XX:+UseG1GC ");
            case DEVELOPMENT -> {
                opts.append("-XX:+UseG1GC -ea ");
                if (isFastShutdownEnabled()) {
                    opts.append("-Dorg.apache.catalina.startup.EXIT_ON_INIT_FAILURE=true ");
                }
            }
        }

        return opts.toString().trim();
    }

    /**
     * Builds CATALINA_OPTS for standalone Tomcat.
     * Only includes properties that Tomcat's Catalina process actually uses.
     * Spring Boot properties (-Dserver.port, -Dspring.profiles.active, etc.)
     * are intentionally excluded — they have no effect on standalone Tomcat
     * and mislead users into thinking they control Tomcat's port binding.
     * Tomcat ports are configured via server.xml connectors.
     */
    @NotNull
    public static String buildCatalinaOpts() {
        StringBuilder opts = new StringBuilder();
        EnvironmentMode mode = getCurrentMode();

        opts.append("-Dfile.encoding=UTF-8 ");

        if (shouldShowTimestamps()) {
            opts.append("-Djava.util.logging.SimpleFormatter.format=")
                    .append("\"[%1$tF %1$tT] [%4$-7s] %5$s %n\" ");
        }

        switch (mode) {
            case PRODUCTION  -> opts.append("-server -Djava.awt.headless=true ");
            case STAGING     -> opts.append("-Dlogging.level.root=INFO ");
            case DEVELOPMENT -> {} // no extra opts
        }

        return opts.toString().trim();
    }

    // =========================================================================
    // Environment Variables
    // =========================================================================

    @NotNull
    public static Map<String, String> buildEnvironmentVariables() {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("JAVA_OPTS", buildJavaOpts());
        envVars.put("CATALINA_OPTS", buildCatalinaOpts());
        envVars.put(JDK_JAVA_OPTIONS_KEY, ENV_JDK_JAVA_OPTIONS);
        envVars.put("TOMCAT_PLUGIN_ENV", getCurrentMode().getValue());
        envVars.put("TZ", "UTC");
        return envVars;
    }

    // =========================================================================
    // JMX Security
    // =========================================================================

    public static boolean shouldSecureJmx() {
        EnvironmentMode mode = getCurrentMode();
        return mode == EnvironmentMode.PRODUCTION || mode == EnvironmentMode.STAGING;
    }

    public static boolean shouldEnableSpringDevTools() {
        return getCurrentMode() == EnvironmentMode.DEVELOPMENT;
    }

    public static boolean getJmxAuthenticationEnabled() { return shouldSecureJmx(); }
    public static boolean getJmxSslEnabled()            { return getCurrentMode() == EnvironmentMode.PRODUCTION; }

    // =========================================================================
    // Display Helpers
    // =========================================================================

    @NotNull public static String getEnvironmentName()        { return getCurrentMode().getValue(); }
    @NotNull public static String getEnvironmentDisplayName() {
        String name = getCurrentMode().getValue();
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    public static boolean isDevelopmentMode() { return getCurrentMode() == EnvironmentMode.DEVELOPMENT; }
    public static boolean isProductionMode()  { return getCurrentMode() == EnvironmentMode.PRODUCTION; }
    public static boolean isStagingMode()     { return getCurrentMode() == EnvironmentMode.STAGING; }

    @NotNull
    public static String getConfigurationSummary() {
        return String.format(
                "Environment: %s, HTTP: %d, Shutdown: %d, HTTPS: %s, JMX: %s, HotDeploy: %s",
                getEnvironmentDisplayName(),
                getHttpPort(),
                getShutdownPort(),
                isHttpsEnabled() ? getHttpsPort() : "disabled",
                isJmxEnabled() ? getJmxPort() : "disabled",
                isHotDeploymentEnabled() ? "enabled" : "disabled"
        );
    }
}
