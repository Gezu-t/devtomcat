package com.dev.idea.plugins.tomcat.environment;

    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.registry.Registry;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;
    import org.jetbrains.annotations.Nullable;

    import java.util.HashMap;
    import java.util.Map;

    /**
     * Dynamic Tomcat Environment Detector
     * Complete replacement for TomcatEnvironmentBuilder with registry-driven configuration.
     *
     * <p>Responsibilities:
     * <ul>
     *   <li>Detect environment mode (Development/Staging/Production)</li>
     *   <li>Provide registry-based port configuration</li>
     *   <li>Build dynamic JVM options (JAVA_OPTS, CATALINA_OPTS)</li>
     *   <li>Generate environment variables for Tomcat startup</li>
     *   <li>Handle JMX, HTTPS, hot deployment settings</li>
     *   <li>Support Spring profiles and system properties</li>
     * </ul>
     *
     * <p>100% NULL-SAFE — All values have sensible defaults, no exceptions propagate
     * <p>Registry-driven — All configuration through IntelliJ Registry (not hardcoded)
     * <p>Environment-aware — Different JVM tuning for dev/staging/production
     *
     * Author: Gezahegn Lemma (Gezu)
     * Project: DevTomcat Plugin
     * Created: 6/9/25
     */
    public final class DynamicTomcatEnvironment {

        private static final Logger LOG = Logger.getInstance(DynamicTomcatEnvironment.class);

        // Registry keys matching existing pattern
        private static final String REG_ENVIRONMENT_MODE = "devtomcat.environment.mode";
        private static final String REG_DEFAULT_HTTP_PORT = "devtomcat.default.http.port";
        private static final String REG_DEFAULT_SHUTDOWN_PORT = "devtomcat.default.shutdown.port";
        private static final String REG_DEFAULT_HTTPS_PORT = "devtomcat.default.https.port";
        private static final String REG_DEFAULT_JMX_PORT = "devtomcat.default.jmx.port";
        private static final String REG_JMX_ENABLED = "devtomcat.enable.jmx.monitoring";
        private static final String REG_HTTPS_ENABLED = "devtomcat.enable.https";
        private static final String REG_HOT_DEPLOYMENT = "devtomcat.enable.hot.deployment";
        private static final String REG_DEFAULT_XMX = "devtomcat.default.xmx";
        private static final String REG_DEFAULT_XMS = "devtomcat.default.xms";
        private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";
        private static final String REG_FAST_SHUTDOWN = "devtomcat.dev.fast.shutdown";
        private static final String REG_AUTO_BROWSER = "devtomcat.auto.browser.launch";
        private static final String REG_AUTO_CONFIG = "devtomcat.enable.auto.configuration";

        private static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";
        private static final String ENV_JDK_JAVA_OPTIONS =
                "--add-opens=java.base/java.lang=ALL-UNNAMED " +
                "--add-opens=java.base/java.io=ALL-UNNAMED " +
                "--add-opens=java.base/java.util=ALL-UNNAMED " +
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED " +
                "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED";

        private DynamicTomcatEnvironment() {}


        public enum EnvironmentMode {
            DEVELOPMENT("development"),
            STAGING("staging"),
            PRODUCTION("production");

            private final String value;

            EnvironmentMode(String value) {
                this.value = value;
            }

            /**
             * Get the string value of this environment mode.
             *
             * @return the mode value (never null)
             */
            @NotNull
            public String getValue() {
                return value;
            }

            /**
             * Parse environment mode from string with intelligent fallback.
             *
             * @param mode the mode string (can be null)
             * @return the parsed mode or DEVELOPMENT if null/empty (never null)
             */
            @NotNull
            public static EnvironmentMode fromString(@Nullable String mode) {
                if (StringUtil.isEmpty(mode)) {
                    return DEVELOPMENT;
                }

                String normalized = mode.toLowerCase().trim();
                if (normalized.contains("prod")) {
                    return PRODUCTION;
                }
                if (normalized.contains("staging") || normalized.contains("stage")) {
                    return STAGING;
                }
                return DEVELOPMENT;
            }
        }

        /**
         * Get current environment mode with comprehensive fallback chain.
         *
         * <p>Detection priority:
         * <ol>
         *   <li>System property `tomcat.environment`</li>
         *   <li>Spring profiles system property</li>
         *   <li>Environment variable `TOMCAT_ENV`</li>
         *   <li>Environment variable `SPRING_PROFILES_ACTIVE`</li>
         *   <li>IntelliJ Registry setting</li>
         *   <li>Default to DEVELOPMENT</li>
         * </ol>
         *
         * @return the detected environment mode (never null)
         */
        @NotNull
        public static EnvironmentMode getCurrentMode() {
            // Priority 1: System property
            String sysProp = System.getProperty("tomcat.environment");
            if (!StringUtil.isEmpty(sysProp)) {
                LOG.debug("Environment mode from system property: " + sysProp);
                return EnvironmentMode.fromString(sysProp);
            }

            // Priority 2: Spring profiles
            String springProfiles = System.getProperty("spring.profiles.active");
            if (!StringUtil.isEmpty(springProfiles)) {
                LOG.debug("Environment mode from Spring profiles: " + springProfiles);
                return EnvironmentMode.fromString(springProfiles);
            }

            // Priority 3: Environment variables
            String envVar = System.getenv("TOMCAT_ENV");
            if (!StringUtil.isEmpty(envVar)) {
                LOG.debug("Environment mode from TOMCAT_ENV: " + envVar);
                return EnvironmentMode.fromString(envVar);
            }

            envVar = System.getenv("SPRING_PROFILES_ACTIVE");
            if (!StringUtil.isEmpty(envVar)) {
                LOG.debug("Environment mode from SPRING_PROFILES_ACTIVE: " + envVar);
                return EnvironmentMode.fromString(envVar);
            }

            // Priority 4: Registry (with proper default handling)
            try {
                String registryMode = Registry.stringValue(REG_ENVIRONMENT_MODE);
                if (!StringUtil.isEmpty(registryMode)) {
                    LOG.debug("Environment mode from Registry: " + registryMode);
                    return EnvironmentMode.fromString(registryMode);
                }
            } catch (Exception e) {
                LOG.debug("Registry key not found, using default: " + e.getMessage());
            }

            // Final fallback
            LOG.debug("Using default environment mode: DEVELOPMENT");
            return EnvironmentMode.DEVELOPMENT;
        }

        /**
         * Get HTTP port from Registry with sensible default.
         *
         * @return the HTTP port (default 8080 if not configured)
         */
        public static int getHttpPort() {
            try {
                int port = Registry.intValue(REG_DEFAULT_HTTP_PORT);
                LOG.debug("HTTP port from Registry: " + port);
                return port;
            } catch (Exception e) {
                LOG.debug("HTTP port not in Registry, using default 8080");
                return 8080;
            }
        }

        /**
         * Get Tomcat shutdown port from Registry with sensible default.
         *
         * @return the shutdown port (default 8005 if not configured)
         */
        public static int getShutdownPort() {
            try {
                int port = Registry.intValue(REG_DEFAULT_SHUTDOWN_PORT);
                LOG.debug("Shutdown port from Registry: " + port);
                return port;
            } catch (Exception e) {
                LOG.debug("Shutdown port not in Registry, using default 8005");
                return 8005;
            }
        }

        /**
         * Get HTTPS port from Registry with sensible default.
         *
         * @return the HTTPS port (default 8443 if not configured)
         */
        public static int getHttpsPort() {
            try {
                int port = Registry.intValue(REG_DEFAULT_HTTPS_PORT);
                LOG.debug("HTTPS port from Registry: " + port);
                return port;
            } catch (Exception e) {
                LOG.debug("HTTPS port not in Registry, using default 8443");
                return 8443;
            }
        }

        /**
         * Get JMX port from Registry with sensible default.
         *
         * @return the JMX port (default 1099 if not configured)
         */
        public static int getJmxPort() {
            try {
                int port = Registry.intValue(REG_DEFAULT_JMX_PORT);
                LOG.debug("JMX port from Registry: " + port);
                return port;
            } catch (Exception e) {
                LOG.debug("JMX port not in Registry, using default 1099");
                return 1099;
            }
        }

        /**
         * Check if JMX monitoring is enabled.
         *
         * @return true if JMX enabled (default false for safety)
         */
        public static boolean isJmxEnabled() {
            try {
                boolean enabled = Registry.is(REG_JMX_ENABLED);
                LOG.debug("JMX enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("JMX setting not in Registry, using default: false");
                return false;
            }
        }

        /**
         * Check if HTTPS is enabled.
         *
         * @return true if HTTPS enabled (default false for safety)
         */
        public static boolean isHttpsEnabled() {
            try {
                boolean enabled = Registry.is(REG_HTTPS_ENABLED);
                LOG.debug("HTTPS enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("HTTPS setting not in Registry, using default: false");
                return false;
            }
        }

        /**
         * Check if hot deployment is enabled.
         *
         * @return true if hot deployment enabled (default true for development)
         */
        public static boolean isHotDeploymentEnabled() {
            try {
                boolean enabled = Registry.is(REG_HOT_DEPLOYMENT);
                LOG.debug("Hot deployment enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("Hot deployment setting not in Registry, using default: true");
                return true;
            }
        }

        /**
         * Check if console timestamps should be shown.
         *
         * @return true if timestamps enabled (default true for development)
         */
        public static boolean shouldShowTimestamps() {
            try {
                boolean enabled = Registry.is(REG_SHOW_TIMESTAMPS);
                LOG.debug("Timestamps enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("Timestamps setting not in Registry, using default: true");
                return true;
            }
        }

        /**
         * Check if fast shutdown is enabled.
         *
         * @return true if fast shutdown enabled (default false for safety)
         */
        public static boolean isFastShutdownEnabled() {
            try {
                boolean enabled = Registry.is(REG_FAST_SHUTDOWN);
                LOG.debug("Fast shutdown enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("Fast shutdown setting not in Registry, using default: false");
                return false;
            }
        }

        /**
         * Check if browser should be auto-launched.
         *
         * @return true if auto-launch enabled (default true for development)
         */
        public static boolean shouldAutoLaunchBrowser() {
            try {
                boolean enabled = Registry.is(REG_AUTO_BROWSER);
                LOG.debug("Auto-launch browser from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("Auto-launch setting not in Registry, using default: true");
                return true;
            }
        }

        /**
         * Check if auto-configuration is enabled.
         *
         * @return true if auto-config enabled (default true)
         */
        public static boolean isAutoConfigEnabled() {
            try {
                boolean enabled = Registry.is(REG_AUTO_CONFIG);
                LOG.debug("Auto-config enabled from Registry: " + enabled);
                return enabled;
            } catch (Exception e) {
                LOG.debug("Auto-config setting not in Registry, using default: true");
                return true;
            }
        }

        /**
         * Get maximum heap size from Registry.
         *
         * @return the Xmx value (default 1024m if not configured)
         */
        @NotNull
        public static String getXmxValue() {
            try {
                String value = Registry.stringValue(REG_DEFAULT_XMX);
                if (!StringUtil.isEmpty(value)) {
                    LOG.debug("Xmx from Registry: " + value);
                    return value;
                }
            } catch (Exception e) {
                LOG.debug("Xmx not in Registry, using default");
            }
            LOG.debug("Using default Xmx: 1024m");
            return "1024m";
        }

        /**
         * Get initial heap size from Registry.
         *
         * @return the Xms value (default 512m if not configured)
         */
        @NotNull
        public static String getXmsValue() {
            try {
                String value = Registry.stringValue(REG_DEFAULT_XMS);
                if (!StringUtil.isEmpty(value)) {
                    LOG.debug("Xms from Registry: " + value);
                    return value;
                }
            } catch (Exception e) {
                LOG.debug("Xms not in Registry, using default");
            }
            LOG.debug("Using default Xms: 512m");
            return "512m";
        }

        /**
         * Build complete JAVA_OPTS string dynamically based on environment.
         *
         * <p>Includes:
         * <ul>
         *   <li>Heap configuration (Xms, Xmx)</li>
         *   <li>File encoding (UTF-8)</li>
         *   <li>GC tuning (G1GC for all modes)</li>
         *   <li>Production-specific options (server mode, string dedup)</li>
         *   <li>Development-specific options (assertions, fast shutdown)</li>
         * </ul>
         *
         * @return the complete JAVA_OPTS string (never null)
         */
        @NotNull
        public static String buildJavaOpts() {
            StringBuilder opts = new StringBuilder();
            EnvironmentMode mode = getCurrentMode();

            // Memory from Registry
            String xmx = getXmxValue();
            String xms = getXmsValue();

            opts.append("-Xmx").append(xmx).append(" ");
            opts.append("-Xms").append(xms).append(" ");

            // Standard encoding
            opts.append("-Dfile.encoding=UTF-8 ");

            // Environment-specific JVM options
            switch (mode) {
                case PRODUCTION:
                    opts.append("-server -XX:+UseG1GC -XX:MaxGCPauseMillis=200 ");
                    opts.append("-XX:+UseStringDeduplication ");
                    opts.append("-Djava.security.egd=file:/dev/./urandom ");
                    LOG.debug("Built JAVA_OPTS for PRODUCTION mode");
                    break;
                case STAGING:
                    opts.append("-server -XX:+UseG1GC ");
                    LOG.debug("Built JAVA_OPTS for STAGING mode");
                    break;
                case DEVELOPMENT:
                    opts.append("-XX:+UseG1GC ");
                    opts.append("-ea "); // Enable assertions in dev

                    // Fast shutdown if enabled in Registry
                    if (isFastShutdownEnabled()) {
                        opts.append("-Dorg.apache.catalina.startup.EXIT_ON_INIT_FAILURE=true ");
                    }
                    LOG.debug("Built JAVA_OPTS for DEVELOPMENT mode");
                    break;
            }

            String result = opts.toString().trim();
            LOG.debug("Final JAVA_OPTS: " + result);
            return result;
        }

        /**
         * Build complete CATALINA_OPTS string dynamically based on environment.
         *
         * <p>Includes:
         * <ul>
         *   <li>File encoding (UTF-8)</li>
         *   <li>Spring profiles</li>
         *   <li>Server port (HTTP or HTTPS)</li>
         *   <li>Logging format with timestamps (if enabled)</li>
         *   <li>Environment-specific options</li>
         * </ul>
         *
         * @return the complete CATALINA_OPTS string (never null)
         */
        @NotNull
        public static String buildCatalinaOpts() {
            StringBuilder opts = new StringBuilder();
            EnvironmentMode mode = getCurrentMode();

            // Standard encoding
            opts.append("-Dfile.encoding=UTF-8 ");

            // Environment profile
            opts.append("-Dspring.profiles.active=").append(mode.getValue()).append(" ");

            // HTTPS configuration
            if (isHttpsEnabled()) {
                opts.append("-Dserver.port=").append(getHttpsPort()).append(" ");
                opts.append("-Dserver.ssl.enabled=true ");
                LOG.debug("CATALINA_OPTS configured for HTTPS");
            } else {
                opts.append("-Dserver.port=").append(getHttpPort()).append(" ");
                LOG.debug("CATALINA_OPTS configured for HTTP");
            }

            // Timestamp formatting from Registry
            if (shouldShowTimestamps()) {
                opts.append("-Djava.util.logging.SimpleFormatter.format=")
                        .append("\"[%1$tF %1$tT] [%4$-7s] %5$s %n\" ");
            }

            // Environment-specific options
            switch (mode) {
                case PRODUCTION:
                    opts.append("-server -Djava.awt.headless=true ");
                    LOG.debug("Built CATALINA_OPTS for PRODUCTION mode");
                    break;
                case STAGING:
                    opts.append("-Dlogging.level.root=INFO ");
                    LOG.debug("Built CATALINA_OPTS for STAGING mode");
                    break;
                case DEVELOPMENT:
                    LOG.debug("Built CATALINA_OPTS for DEVELOPMENT mode");
                    break;
            }

            String result = opts.toString().trim();
            LOG.debug("Final CATALINA_OPTS: " + result);
            return result;
        }

        /**
         * Build complete environment variables map for Tomcat process.
         *
         * <p>Includes:
         * <ul>
         *   <li>JAVA_OPTS</li>
         *   <li>CATALINA_OPTS</li>
         *   <li>JDK_JAVA_OPTIONS (JDK 9+ module system)</li>
         *   <li>TOMCAT_PLUGIN_ENV (environment indicator)</li>
         *   <li>TZ (timezone UTC)</li>
         * </ul>
         *
         * @return the environment variables map (never null)
         */
        @NotNull
        public static Map<String, String> buildEnvironmentVariables() {
            Map<String, String> envVars = new HashMap<>();

            // Core JVM options
            envVars.put("JAVA_OPTS", buildJavaOpts());
            envVars.put("CATALINA_OPTS", buildCatalinaOpts());

            // JDK module system options
            envVars.put(JDK_JAVA_OPTIONS, ENV_JDK_JAVA_OPTIONS);

            // Environment indicator
            envVars.put("TOMCAT_PLUGIN_ENV", getCurrentMode().getValue());

            // Timezone
            envVars.put("TZ", "UTC");

            LOG.debug("Built environment variables map with " + envVars.size() + " entries");
            return envVars;
        }

        /**
         * Determine if JMX should be secured based on environment.
         *
         * @return true for production/staging modes
         */
        public static boolean shouldSecureJmx() {
            EnvironmentMode mode = getCurrentMode();
            boolean shouldSecure = mode == EnvironmentMode.PRODUCTION || mode == EnvironmentMode.STAGING;
            LOG.debug("JMX should be secured: " + shouldSecure);
            return shouldSecure;
        }

        /**
         * Check if Spring Dev Tools should be enabled.
         *
         * @return true only for development mode
         */
        public static boolean shouldEnableSpringDevTools() {
            boolean shouldEnable = getCurrentMode() == EnvironmentMode.DEVELOPMENT;
            LOG.debug("Spring Dev Tools should be enabled: " + shouldEnable);
            return shouldEnable;
        }

        /**
         * Get JMX authentication requirement based on environment.
         *
         * @return true for production/staging modes
         */
        public static boolean getJmxAuthenticationEnabled() {
            boolean enabled = shouldSecureJmx();
            LOG.debug("JMX authentication enabled: " + enabled);
            return enabled;
        }

        /**
         * Get JMX SSL requirement based on environment.
         *
         * @return true only for production mode
         */
        public static boolean getJmxSslEnabled() {
            boolean enabled = getCurrentMode() == EnvironmentMode.PRODUCTION;
            LOG.debug("JMX SSL enabled: " + enabled);
            return enabled;
        }

        /**
         * Get environment mode name in lowercase.
         *
         * @return the environment name (never null)
         */
        @NotNull
        public static String getEnvironmentName() {
            return getCurrentMode().getValue();
        }

        /**
         * Get environment mode name with proper capitalization for display.
         *
         * @return the display name (never null)
         */
        @NotNull
        public static String getEnvironmentDisplayName() {
            String name = getCurrentMode().getValue();
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        /**
         * Check if currently in development mode.
         *
         * @return true if environment mode is DEVELOPMENT
         */
        public static boolean isDevelopmentMode() {
            return getCurrentMode() == EnvironmentMode.DEVELOPMENT;
        }

        /**
         * Check if currently in production mode.
         *
         * @return true if environment mode is PRODUCTION
         */
        public static boolean isProductionMode() {
            return getCurrentMode() == EnvironmentMode.PRODUCTION;
        }

        /**
         * Check if currently in staging mode.
         *
         * @return true if environment mode is STAGING
         */
        public static boolean isStagingMode() {
            return getCurrentMode() == EnvironmentMode.STAGING;
        }

        /**
         * Get human-readable summary of current configuration.
         *
         * <p>Useful for logging and debugging configuration state.
         *
         * @return the configuration summary string (never null)
         */
        @NotNull
        public static String getConfigurationSummary() {
            String summary = String.format(
                    "Environment: %s, HTTP: %d, Shutdown: %d, HTTPS: %s, JMX: %s, HotDeploy: %s",
                    getEnvironmentDisplayName(),
                    getHttpPort(),
                    getShutdownPort(),
                    isHttpsEnabled() ? getHttpsPort() : "disabled",
                    isJmxEnabled() ? getJmxPort() : "disabled",
                    isHotDeploymentEnabled() ? "enabled" : "disabled"
            );
            LOG.debug("Configuration summary: " + summary);
            return summary;
        }
    }