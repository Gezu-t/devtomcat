package com.dev.idea.plugins.tomcat.conf;

    import com.dev.idea.plugins.tomcat.model.PortConfig;
    import com.dev.idea.plugins.tomcat.model.TomcatConfigurationData;
    import com.intellij.execution.impl.ConsoleViewImpl;
    import com.intellij.execution.ui.ConsoleViewContentType;
    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.registry.Registry;
    import com.intellij.openapi.util.text.StringUtil;
    import com.intellij.util.Url;
    import com.intellij.util.Urls;
    import org.jetbrains.annotations.NotNull;
    import org.jetbrains.annotations.Nullable;

    import java.time.LocalDateTime;
    import java.time.format.DateTimeFormatter;
    import java.util.*;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;

    /**
     * Dev Tomcat Server Console View
     *
     * <p>Ultimate-grade console: intelligent log parsing, deployment tracking,
     * clickable URLs, and **100% MODERN MODEL** (no legacy methods).
     */
    public class ServerConsoleView extends ConsoleViewImpl {

        private static final Logger LOG = Logger.getInstance(ServerConsoleView.class);

        // Registry
        private static final String REG_SHOW_TIMESTAMPS = "devtomcat.log.show.timestamps";

        // Time
        private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        // Patterns
        private static final Pattern HTTP_CONNECTOR_PATTERN = Pattern.compile("http-nio-(\\d+)");
        private static final Pattern HTTPS_CONNECTOR_PATTERN = Pattern.compile("https-jsse-nio-(\\d+)");
        private static final Pattern AJP_CONNECTOR_PATTERN = Pattern.compile("ajp-nio-(\\d+)");
        private static final Pattern DEPLOYMENT_START_PATTERN = Pattern.compile("Deploying web application.*?(?:directory|archive).*?\\[(.+?)\\]");
        private static final Pattern DEPLOYMENT_FINISH_PATTERN = Pattern.compile("Deployment of web application.*?\\[(.+?)\\].*?finished");
        private static final Pattern SERVER_STARTUP_PATTERN = Pattern.compile("Server startup in (\\d+) ms");
        private static final Pattern ERROR_PATTERN = Pattern.compile("(ERROR|SEVERE|FATAL)\\s*[:|-](.*)");
        private static final Pattern WARNING_PATTERN = Pattern.compile("(WARN|WARNING)\\s*[:|-](.*)");

        private enum OutputCategory {
            SERVER_LIFECYCLE("SERVER", ConsoleViewContentType.SYSTEM_OUTPUT),
            DEPLOYMENT("DEPLOY", ConsoleViewContentType.SYSTEM_OUTPUT),
            ERROR("ERROR", ConsoleViewContentType.ERROR_OUTPUT),
            WARNING("WARN", ConsoleViewContentType.LOG_WARNING_OUTPUT),
            INFO("INFO", ConsoleViewContentType.NORMAL_OUTPUT),
            DEBUG("DEBUG", ConsoleViewContentType.LOG_DEBUG_OUTPUT);

            private final String prefix;
            private final ConsoleViewContentType contentType;

            OutputCategory(String prefix, ConsoleViewContentType contentType) {
                this.prefix = prefix;
                this.contentType = contentType;
            }
        }

        // State
        private final TomcatRunConfiguration configuration;
        private final TomcatConfigurationData data;
        private final PortConfig pc;

        private final Set<String> httpPorts = ConcurrentHashMap.newKeySet();
        private final Set<String> httpsPorts = ConcurrentHashMap.newKeySet();
        private final Set<String> ajpPorts = ConcurrentHashMap.newKeySet();
        private final Map<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();
        private volatile boolean serverReady = false;
        private final long startTime = System.currentTimeMillis();
        private final boolean showTimestamps;

        public ServerConsoleView(@NotNull TomcatRunConfiguration configuration) {
            super(configuration.getProject(), true);
            this.configuration = Objects.requireNonNull(configuration, "Configuration cannot be null");
            this.data = configuration.getConfigData();
            this.pc = data.getPortConfig();
            this.showTimestamps = Registry.is(REG_SHOW_TIMESTAMPS);
            printServerHeader();
        }

        @Override
        public void print(@NotNull String text, @NotNull ConsoleViewContentType contentType) {
            if (text.trim().isEmpty()) {
                super.print(text, contentType);
                return;
            }

            OutputCategory category = categorizeOutput(text);
            String enhancedText = enhanceOutput(text, category);
            super.print(enhancedText, category.contentType);
            processOutputForState(text);
        }

        private void printServerHeader() {
            String header = String.format(
                    "\n╔════════════════════════════════════════════════════════════════╗\n" +
                            "║ Dev Tomcat Server Console                                      ║\n" +
                            "╠════════════════════════════════════════════════════════════════╣\n" +
                            "║ Project: %-53s ║\n" +
                            "║ Configuration: %-47s ║\n" +
                            "║ Started: %-53s ║\n" +
                            "╚════════════════════════════════════════════════════════════════╝\n\n",
                    truncate(configuration.getProject().getName(), 53),
                    truncate(configuration.getName(), 47),
                    LocalDateTime.now().format(TIMESTAMP_FORMAT)
            );
            super.print(header, ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        private OutputCategory categorizeOutput(String text) {
            if (ERROR_PATTERN.matcher(text).find()) return OutputCategory.ERROR;
            if (WARNING_PATTERN.matcher(text).find()) return OutputCategory.WARNING;
            if (text.contains("Deploy") || text.contains("deployment")) return OutputCategory.DEPLOYMENT;
            if (text.contains("Server startup") || text.contains("Catalina.start") || text.contains("Stopping")) {
                return OutputCategory.SERVER_LIFECYCLE;
            }
            if (text.contains("DEBUG") || text.contains("FINE")) return OutputCategory.DEBUG;
            return OutputCategory.INFO;
        }

        private String enhanceOutput(String text, OutputCategory category) {
            StringBuilder sb = new StringBuilder();
            if (showTimestamps && !text.trim().isEmpty()) {
                sb.append("[").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append("] ");
            }
            if (category != OutputCategory.INFO && category != OutputCategory.DEBUG) {
                sb.append("[").append(category.prefix).append("] ");
            }
            sb.append(text);
            if (!text.endsWith("\n")) sb.append("\n");
            return sb.toString();
        }

        private void processOutputForState(String text) {
            if (text.trim().startsWith("at ") || text.trim().startsWith("Caused by:")) return;

            parseConnectorInfo(text);
            trackDeploymentProgress(text);
            checkServerStartup(text);
        }

        private void parseConnectorInfo(String text) {
            Matcher m;
            if ((m = HTTP_CONNECTOR_PATTERN.matcher(text)).find()) {
                String port = m.group(1);
                if (httpPorts.add(port)) {
                    printConnectorInfo("HTTP", port);
                }
            }
            if ((m = HTTPS_CONNECTOR_PATTERN.matcher(text)).find()) {
                String port = m.group(1);
                if (httpsPorts.add(port)) {
                    printConnectorInfo("HTTPS", port);
                }
            }
            if ((m = AJP_CONNECTOR_PATTERN.matcher(text)).find()) {
                String port = m.group(1);
                if (ajpPorts.add(port)) {
                    printConnectorInfo("AJP", port);
                }
            }
        }

        private void printConnectorInfo(String type, String port) {
            super.print(formatWithTimestamp("✓ " + type + " connector initialized on port " + port + "\n"),
                    ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        private void trackDeploymentProgress(String text) {
            Matcher m;
            if ((m = DEPLOYMENT_START_PATTERN.matcher(text)).find()) {
                String context = m.group(1);
                deploymentStartTimes.put(context, System.currentTimeMillis());
                super.print(formatWithTimestamp("→ Deploying application: " + getApplicationName(context) + "\n"),
                        ConsoleViewContentType.SYSTEM_OUTPUT);
            }
            if ((m = DEPLOYMENT_FINISH_PATTERN.matcher(text)).find()) {
                String context = m.group(1);
                Long start = deploymentStartTimes.remove(context);
                if (start != null) {
                    long duration = System.currentTimeMillis() - start;
                    super.print(formatWithTimestamp("✓ Deployed successfully: " + getApplicationName(context) + " (took " + duration + " ms)\n"),
                            ConsoleViewContentType.SYSTEM_OUTPUT);
                }
            }
        }

        private void checkServerStartup(String text) {
            Matcher m = SERVER_STARTUP_PATTERN.matcher(text);
            if (m.find() && !serverReady) {
                serverReady = true;
                String startupTime = m.group(1);

                // Fallback: use configured HTTP port if no connectors detected
                if (httpPorts.isEmpty() && httpsPorts.isEmpty()) {
                    httpPorts.add(String.valueOf(pc.getHttp()));
                }

                printServerReadyMessage(startupTime);
            }
        }

        private void printServerReadyMessage(String startupTime) {
            StringBuilder msg = new StringBuilder("\n");
            msg.append("╔════════════════════════════════════════════════════════════════╗\n");
            msg.append("║ Server Started Successfully                                    ║\n");
            msg.append("╠════════════════════════════════════════════════════════════════╣\n");
            msg.append(String.format("║ Startup time: %-48s ║\n", startupTime + " ms"));
            msg.append(String.format("║ Hot deployment: %-45s ║\n",
                    data.getDeploymentConfig().isHotDeploymentEnabled() ? "Enabled" : "Disabled"));

            if (pc.isJmxEnabled()) {
                msg.append(String.format("║ JMX monitoring: Port %-40s ║\n", pc.getJmx()));
            }

            msg.append("╠════════════════════════════════════════════════════════════════╣\n");
            msg.append("║ Application URLs:                                              ║\n");

            List<Url> urls = buildApplicationUrls();
            for (Url url : urls) {
                String urlStr = url.toExternalForm();
                msg.append(String.format("║ • %-59s ║\n", truncate(urlStr, 59)));
            }

            msg.append("╚════════════════════════════════════════════════════════════════╝\n\n");
            super.print(msg.toString(), ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        private List<Url> buildApplicationUrls() {
            List<Url> urls = new ArrayList<>();
            String contextPath = data.getContextPath();
            if (contextPath == null) {
                contextPath = "/";
            }
            String path = StringUtil.trimStart(contextPath, "/");
            if (path.isEmpty()) path = "/"; else path = "/" + path;

            // HTTP
            for (String port : httpPorts) {
                try {
                    boolean isDefault = "80".equals(port);
                    String authority = "localhost" + (isDefault ? "" : ":" + port);
                    urls.add(Urls.newHttpUrl(authority, path));
                } catch (Exception e) {
                    LOG.debug("Failed to create HTTP URL for port: " + port, e);
                }
            }

            // HTTPS
            for (String port : httpsPorts) {
                try {
                    boolean isDefault = "443".equals(port);
                    String authority = "localhost" + (isDefault ? "" : ":" + port);
                    urls.add(Urls.newUrl("https", authority, path));
                } catch (Exception e) {
                    LOG.debug("Failed to create HTTPS URL for port: " + port, e);
                }
            }

            return urls;
        }

        private String formatWithTimestamp(String text) {
            return showTimestamps ? "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + text : text;
        }

        private String getApplicationName(String context) {
            if (context == null || context.isEmpty() || "/".equals(context) || "ROOT".equals(context)) return "ROOT (/)";
            return context.startsWith("/") ? context : "/" + context;
        }

        private String truncate(@Nullable String str, int maxLength) {
            if (str == null || str.isEmpty()) return "";
            return str.length() <= maxLength ? str : str.substring(0, maxLength - 3) + "...";
        }

        // === PUBLIC API ===

        /**
         * Prints an error message to the console.
         *
         * @param message the error message to print
         */
        public void printError(@NotNull String message) {
            print("[ERROR] " + message + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        }

        /**
         * Prints a warning message to the console.
         *
         * @param message the warning message to print
         */
        public void printWarning(@NotNull String message) {
            print("[WARN] " + message + "\n", ConsoleViewContentType.LOG_WARNING_OUTPUT);
        }

        /**
         * Prints an info message to the console.
         *
         * @param message the info message to print
         */
        public void printInfo(@NotNull String message) {
            print("[INFO] " + message + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
        }

        /**
         * Checks if the server is ready.
         *
         * @return true if server startup is complete
         */
        public boolean isServerReady() {
            return serverReady;
        }

        /**
         * Gets the HTTP ports detected during server startup.
         *
         * @return unmodifiable set of HTTP port numbers
         */
        public Set<String> getHttpPorts() {
            return new HashSet<>(httpPorts);
        }

        /**
         * Gets the HTTPS ports detected during server startup.
         *
         * @return unmodifiable set of HTTPS port numbers
         */
        public Set<String> getHttpsPorts() {
            return new HashSet<>(httpsPorts);
        }

        /**
         * Gets the total startup time in milliseconds.
         *
         * @return elapsed time since console creation
         */
        public long getStartupTime() {
            return System.currentTimeMillis() - startTime;
        }
    }