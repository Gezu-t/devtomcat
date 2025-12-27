package com.dev.idea.plugins.tomcat.conf;

                import com.dev.idea.plugins.tomcat.model.*;
                import com.dev.idea.plugins.tomcat.model.debug.DebugConfig;
                import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
                import com.dev.idea.plugins.tomcat.runner.TomcatCommandLineState;
                import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
                import com.dev.idea.plugins.tomcat.ui.TomcatConfigurationEditor;
                import com.intellij.execution.Executor;
                import com.intellij.execution.configurations.*;
                import com.intellij.execution.runners.ExecutionEnvironment;
                import com.intellij.openapi.diagnostic.Logger;
                import com.intellij.openapi.options.SettingsEditor;
                import com.intellij.openapi.project.Project;
                import com.intellij.openapi.util.InvalidDataException;
                import com.intellij.openapi.util.WriteExternalException;
                import com.intellij.openapi.util.text.StringUtil;
                import org.jdom.Element;
                import org.jetbrains.annotations.NotNull;
                import org.jetbrains.annotations.Nullable;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Objects;
                import java.util.concurrent.atomic.AtomicBoolean;

                /**
                 * Dev Tomcat Run Configuration — Ultimate Clone for Community Edition
                 *
                 * <p>Core responsibilities:
                 * <ul>
                 *   <li>Implements IntelliJ RunConfiguration interface</li>
                 *   <li>Holds configuration model (TomcatConfigurationData)</li>
                 *   <li>Provides type-safe, null-safe getters for UI</li>
                 *   <li>Delegates complex logic to helper classes</li>
                 * </ul>
                 *
                 * <p>Decoupled logic:
                 * <ul>
                 *   <li>Initialization → TomcatConfigurationInitializer</li>
                 *   <li>XML Persistence → TomcatConfigurationSerializer</li>
                 *   <li>Validation → TomcatConfigurationValidator</li>
                 *   <li>Cloning → TomcatConfigurationCloner</li>
                 * </ul>
                 *
                 * <p>100% NULL-SAFE — All getters with safe defaults, comprehensive error handling.
                 */
                public class TomcatRunConfiguration extends LocatableConfigurationBase<TomcatRunConfiguration> {

                    private static final Logger LOG = Logger.getInstance(TomcatRunConfiguration.class);

                    // === CORE MODEL ===
                    private final TomcatConfigurationData configData = new TomcatConfigurationData();
                    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
                    private List<String> portValidationWarnings = new ArrayList<>();

                    // === CONSTRUCTOR ===
                    public TomcatRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, String name) {
                        super(project, factory, name);
                        try {
                            TomcatConfigurationInitializer.initialize(this);
                        } catch (Exception e) {
                            LOG.error("Failed to initialize configuration: " + name, e);
                        }
                    }

                    // === RUN CONFIGURATION INTERFACE ===
                    @NotNull
                    @Override
                    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
                        return new TomcatConfigurationEditor(getProject());
                    }

                    @Override
                    @Nullable
                    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) {
                        try {
                            return new TomcatCommandLineState(env, this);
                        } catch (Exception e) {
                            LOG.error("Failed to create run profile state for: " + getName(), e);
                            return null;
                        }
                    }

                    @Override
                    public void checkConfiguration() throws RuntimeConfigurationException {
                        try {
                            TomcatConfigurationValidator.validate(this);
                        } catch (RuntimeConfigurationException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeConfigurationException("Configuration validation error: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    @NotNull
                    public RunConfiguration clone() {
                        try {
                            return TomcatConfigurationCloner.clone(this);
                        } catch (Exception e) {
                            LOG.error("Failed to clone configuration: " + getName(), e);
                            throw new RuntimeException("Cannot clone configuration", e);
                        }
                    }

                    // === MODE HELPERS ===
                    public boolean isLocalMode() {
                        String mode = configData.getServerMode();
                        return "Local".equals(mode);
                    }

                    public boolean isRemoteMode() {
                        String mode = configData.getServerMode();
                        return "Remote".equals(mode);
                    }

                    // === TYPE-SAFE UI GETTERS (NULL-SAFE WITH DEFAULTS) ===

                    /**
                     * Get context path with safe default.
                     *
                     * @return context path or "/" if null/empty
                     */
                    @NotNull
                    public String getContextPathSafe() {
                        String path = configData.getContextPath();
                        return StringUtil.notNullize(path, "/");
                    }

                    // === PORT GETTERS (from PortConfig) ===

                    /**
                     * Get HTTP port with safe fallback.
                     *
                     * @return HTTP port or default port 8080
                     */
                    public int getHttpPortSafe() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null) {
                                int port = pc.getHttp();
                                if (port > 0) return port;
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting HTTP port, using default", e);
                        }
                        return 8080; // Default Tomcat HTTP port
                    }

                    /**
                     * Get shutdown port with safe fallback.
                     *
                     * @return shutdown port or default port 8005
                     */
                    public int getShutdownPortSafe() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null) {
                                int port = pc.getShutdown();
                                if (port > 0) return port;
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting shutdown port, using default", e);
                        }
                        return 8005; // Default Tomcat shutdown port
                    }

                    /**
                     * Get HTTPS port with safe fallback.
                     *
                     * @return HTTPS port or default port 8443
                     */
                    public int getHttpsPortSafe() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null && pc.isHttpsEnabled()) {
                                int port = pc.getHttps();
                                if (port > 0) return port;
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting HTTPS port, using default", e);
                        }
                        return 8443; // Default HTTPS port
                    }

                    /**
                     * Get JMX port with safe fallback.
                     *
                     * @return JMX port or 9010 if JMX enabled, else 0
                     */
                    public int getJmxPortSafe() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null && pc.isJmxEnabled()) {
                                int port = pc.getJmx();
                                if (port > 0) return port;
                                return 9010; // Default JMX port
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting JMX port", e);
                        }
                        return 0; // JMX disabled
                    }

                    /**
                     * Check if HTTPS is enabled.
                     *
                     * @return true if HTTPS is enabled, false otherwise
                     */
                    public boolean isHttpsEnabled() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            return pc != null && pc.isHttpsEnabled();
                        } catch (Exception e) {
                            LOG.warn("Error checking HTTPS enabled", e);
                            return false;
                        }
                    }

                    /**
                     * Check if JMX is enabled.
                     *
                     * @return true if JMX is enabled, false otherwise
                     */
                    public boolean isJmxEnabled() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            return pc != null && pc.isJmxEnabled();
                        } catch (Exception e) {
                            LOG.warn("Error checking JMX enabled", e);
                            return false;
                        }
                    }

                    // === DEBUG GETTERS ===

                    /**
                     * Get debug port with safe fallback.
                     *
                     * @return debug port or default port 5005
                     */
                    public int getDebugPortSafe() {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            if (dc != null) {
                                int port = dc.getPort();
                                if (port > 0) return port;
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting debug port, using default", e);
                        }
                        return 5005; // Default Java debug port
                    }

                    /**
                     * Get debug transport with safe default.
                     *
                     * @return debug transport or "Socket" if null/empty
                     */
                    @NotNull
                    public String getDebugTransportSafe() {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            if (dc != null) {
                                String transport = dc.getTransport();
                                if (StringUtil.isNotEmpty(transport)) {
                                    return transport;
                                }
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting debug transport, using default", e);
                        }
                        return "Socket";
                    }

                    /**
                     * Check if module classpath should be used.
                     *
                     * @return true if module classpath enabled, false otherwise
                     */
                    public boolean isUseModuleClasspath() {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            return dc != null && dc.isUseModuleClasspath();
                        } catch (Exception e) {
                            LOG.warn("Error checking module classpath", e);
                            return true; // Default to true
                        }
                    }

                    // === REMOTE GETTERS ===

                    /**
                     * Get manager URL with safe default.
                     *
                     * @return manager URL or empty string if null/not configured
                     */
                    @NotNull
                    public String getManagerUrlSafe() {
                        try {
                            RemoteConfig rc = configData.getRemoteConfig();
                            if (rc != null) {
                                String url = rc.getManagerUrl();
                                return StringUtil.notNullize(url);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting manager URL", e);
                        }
                        return "";
                    }

                    /**
                     * Get remote username with safe default.
                     *
                     * @return username or empty string if null/not configured
                     */
                    @NotNull
                    public String getRemoteUsernameSafe() {
                        try {
                            RemoteConfig rc = configData.getRemoteConfig();
                            if (rc != null) {
                                String username = rc.getUsername();
                                return StringUtil.notNullize(username);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting remote username", e);
                        }
                        return "";
                    }

                    /**
                     * Get remote password with safe default.
                     *
                     * @return password or empty string if null/not configured
                     */
                    @NotNull
                    public String getRemotePasswordSafe() {
                        try {
                            RemoteConfig rc = configData.getRemoteConfig();
                            if (rc != null) {
                                String password = rc.getPassword();
                                return StringUtil.notNullize(password);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error getting remote password", e);
                        }
                        return "";
                    }

                    /**
                     * Check if remote credentials should be used.
                     *
                     * @return true if remote credentials enabled, false otherwise
                     */
                    public boolean isUseRemoteCredentials() {
                        try {
                            RemoteConfig rc = configData.getRemoteConfig();
                            return rc != null && rc.isUseCredentials();
                        } catch (Exception e) {
                            LOG.warn("Error checking remote credentials", e);
                            return false;
                        }
                    }

                    // === DELEGATE GETTERS/SETTERS ===

                    /**
                     * Get the underlying configuration data object.
                     *
                     * @return TomcatConfigurationData (never null)
                     */
                    @NotNull
                    public TomcatConfigurationData getConfigData() {
                        if (configData == null) {
                            LOG.error("ConfigData is null! This should never happen. Creating new instance.");
                            return new TomcatConfigurationData();
                        }
                        return configData;
                    }

                    // === LEGACY (REMOVE IN v2.0) ===
                    @Deprecated
                    private String docBase = "";

                    @Deprecated
                    public String getDocBase() {
                        return docBase;
                    }

                    @Deprecated
                    public void setDocBase(String docBase) {
                        this.docBase = docBase;
                    }

                    // === XML PERSISTENCE ===

                    @Override
                    public void writeExternal(@NotNull Element element) throws WriteExternalException {
                        Objects.requireNonNull(element, "Element cannot be null");

                        try {
                            super.writeExternal(element);

                            if (!isUpdating.getAndSet(true)) {
                                try {
                                    TomcatConfigurationSerializer.write(this, element);
                                    LOG.debug("Wrote configuration: " + getName());
                                } finally {
                                    isUpdating.set(false);
                                }
                            }
                        } catch (WriteExternalException e) {
                            LOG.error("Failed to write configuration: " + getName(), e);
                            throw e;
                        } catch (Exception e) {
                            LOG.error("Unexpected error writing configuration: " + getName(), e);
                            throw new WriteExternalException("Configuration write error: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void readExternal(@NotNull Element element) throws InvalidDataException {
                        Objects.requireNonNull(element, "Element cannot be null");

                        try {
                            super.readExternal(element);
                            TomcatConfigurationSerializer.read(this, element);
                            TomcatConfigurationInitializer.refresh(this);
                            LOG.debug("Read configuration: " + getName());
                        } catch (InvalidDataException e) {
                            LOG.error("Failed to read configuration: " + getName(), e);
                            throw e;
                        } catch (Exception e) {
                            LOG.error("Unexpected error reading configuration: " + getName(), e);
                            throw new InvalidDataException("Configuration read error: " + e.getMessage(), e);
                        }
                    }

                    /**
                     * Refresh dynamic configuration settings.
                     *
                     * <p>Thread-safe refresh that prevents concurrent updates.
                     */
                    public void refreshDynamicConfiguration() {
                        if (isUpdating.compareAndSet(false, true)) {
                            try {
                                TomcatConfigurationInitializer.refresh(this);
                                LOG.debug("Refreshed dynamic configuration: " + getName());
                            } catch (Exception e) {
                                LOG.error("Failed to refresh configuration: " + getName(), e);
                            } finally {
                                isUpdating.set(false);
                            }
                        } else {
                            LOG.debug("Refresh already in progress for: " + getName());
                        }
                    }

                    // === CONFIGURATION SUMMARY (FOR LOGGING & DEBUGGING) ===

                    /**
                     * Get a string summary of the current configuration.
                     *
                     * <p>Useful for logging and debugging. Handles all null cases safely.
                     *
                     * @return configuration summary
                     */
                    @NotNull
                    public String getConfigurationSummary() {
                        try {
                            // === TOMCAT SERVER INFO ===
                            String server = "None";
                            try {
                                TomcatInfo info = configData.getTomcatInfo();
                                if (info != null) {
                                    String name = StringUtil.notNullize(info.getName(), "Unknown");
                                    String version = StringUtil.notNullize(info.getVersion(), "?");
                                    server = name + " (" + version + ")";
                                }
                            } catch (Exception e) {
                                LOG.debug("Error getting Tomcat info for summary", e);
                            }

                            // === PORT INFO ===
                            String portInfo = "N/A";
                            try {
                                PortConfig pc = configData.getPortConfig();
                                if (pc != null) {
                                    String httpsStr = pc.isHttpsEnabled() ? String.valueOf(pc.getHttps()) : "off";
                                    String jmxStr = pc.isJmxEnabled() ? String.valueOf(pc.getJmx()) : "off";
                                    portInfo = String.format("HTTP:%d | HTTPS:%s | JMX:%s", pc.getHttp(), httpsStr, jmxStr);
                                }
                            } catch (Exception e) {
                                LOG.debug("Error getting port info for summary", e);
                            }

                            // === DEBUG INFO ===
                            String debugStr = "N/A";
                            try {
                                DebugConfig dc = configData.getDebugConfig();
                                if (dc != null) {
                                    debugStr = "Port:" + dc.getPort() + ", Transport:" + dc.getTransport();
                                }
                            } catch (Exception e) {
                                LOG.debug("Error getting debug info for summary", e);
                            }

                            // === ARTIFACT INFO ===
                            int artifactCount = 0;
                            try {
                                var deploymentConfig = configData.getDeploymentConfig();
                                if (deploymentConfig != null) {
                                    List<DeploymentArtifact> artifacts = deploymentConfig.getArtifacts();
                                    if (artifacts != null) {
                                        artifactCount = artifacts.size();
                                    }
                                }
                            } catch (Exception e) {
                                LOG.debug("Error getting artifact count for summary", e);
                            }

                            // === MODE & REMOTE INFO ===
                            String mode = StringUtil.notNullize(configData.getServerMode(), "Local");
                            String remoteInfo = "";
                            try {
                                if (isRemoteMode()) {
                                    String managerUrl = getManagerUrlSafe();
                                    if (StringUtil.isNotEmpty(managerUrl)) {
                                        remoteInfo = " | Manager: " + managerUrl;
                                    }
                                }
                            } catch (Exception e) {
                                LOG.debug("Error getting remote info for summary", e);
                            }

                            return String.format(
                                    "Tomcat[%s] | Mode:%s%s | %s | Debug:%s | Context:%s | Artifacts:%d",
                                    server, mode, remoteInfo,
                                    portInfo, debugStr,
                                    getContextPathSafe(), artifactCount
                            );
                        } catch (Exception e) {
                            LOG.error("Failed to generate configuration summary for: " + getName(), e);
                            return "Configuration Summary: Error";
                        }
                    }

                    // === PORT VALIDATION WARNINGS (FOR UI) ===

                    /**
                     * Set port validation warnings to display in UI.
                     *
                     * @param warnings list of warning messages
                     */
                    public void setPortValidationWarnings(@Nullable List<String> warnings) {
                        this.portValidationWarnings = warnings != null ? warnings : new ArrayList<>();
                    }

                    /**
                     * Get port validation warnings.
                     *
                     * @return list of warning messages (never null)
                     */
                    @NotNull
                    public List<String> getPortValidationWarnings() {
                        return portValidationWarnings;
                    }

                    /**
                     * Get HTTP port from PortConfig.
                     *
                     * @return HTTP port or null if not configured
                     */
                    @Nullable
                    public Integer getHttpPort() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null) {
                                int port = pc.getHttp();
                                if (port > 0) return port;
                            }

                            // No fallback: TomcatInfo represents the installation (name/version/path) and does not define ports.
                            return null;
                        } catch (Exception e) {
                            LOG.warn("Error getting HTTP port", e);
                            return null;
                        }
                    }

                    /**
                     * Get HTTPS port from PortConfig.
                     *
                     * @return HTTPS port or null if not configured
                     */
                    @Nullable
                    public Integer getHttpsPort() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null && pc.isHttpsEnabled()) {
                                int port = pc.getHttps();
                                if (port > 0) return port;
                            }

                            // No fallback: TomcatInfo represents the installation (name/version/path) and does not define ports.
                            return null;
                        } catch (Exception e) {
                            LOG.warn("Error getting HTTPS port", e);
                            return null;
                        }
                    }

                    /**
                     * Get shutdown port from PortConfig.
                     *
                     * @return shutdown port or null if not configured
                     */
                    @Nullable
                    public Integer getShutdownPort() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null) {
                                int port = pc.getShutdown();
                                if (port > 0) return port;
                            }

                            // No fallback: TomcatInfo represents the installation (name/version/path) and does not define ports.
                            return null;
                        } catch (Exception e) {
                            LOG.warn("Error getting shutdown port", e);
                            return null;
                        }
                    }

                    /**
                     * Get JMX port from PortConfig.
                     *
                     * @return JMX port or null if not configured
                     */
                    @Nullable
                    public Integer getJmxPort() {
                        try {
                            PortConfig pc = configData.getPortConfig();
                            if (pc != null && pc.isJmxEnabled()) {
                                int port = pc.getJmx();
                                if (port > 0) return port;
                            }

                            // No fallback: TomcatInfo represents the installation (name/version/path) and does not define ports.
                            return null;
                        } catch (Exception e) {
                            LOG.warn("Error getting JMX port", e);
                            return null;
                        }
                    }

                    // === CONVENIENCE SETTER METHODS FOR UI ===

                    /**
                     * Set HTTP port in PortConfig.
                     *
                     * @param port the HTTP port to set
                     */
                    public void setHttpPort(@Nullable Integer port) {
                        try {
                            if (port != null && port > 0) {
                                configData.getPortConfig().setHttp(port);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting HTTP port", e);
                        }
                    }

                    /**
                     * Set HTTPS port in PortConfig.
                     *
                     * @param port the HTTPS port to set
                     */
                    public void setHttpsPort(@Nullable Integer port) {
                        try {
                            if (port != null && port > 0) {
                                configData.getPortConfig().setHttps(port);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting HTTPS port", e);
                        }
                    }

                    /**
                     * Set shutdown port in PortConfig.
                     *
                     * @param port the shutdown port to set
                     */
                    public void setShutdownPort(@Nullable Integer port) {
                        try {
                            if (port != null && port > 0) {
                                configData.getPortConfig().setShutdown(port);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting shutdown port", e);
                        }
                    }

                    /**
                     * Set JMX port in PortConfig.
                     *
                     * @param port the JMX port to set
                     */
                    public void setJmxPort(@Nullable Integer port) {
                        try {
                            if (port != null && port > 0) {
                                configData.getPortConfig().setJmx(port);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting JMX port", e);
                        }
                    }

                    /**
                     * Set TomcatInfo.
                     *
                     * @param info the TomcatInfo to set
                     */
                    public void setTomcatInfo(@Nullable TomcatInfo info) {
                        configData.setTomcatInfo(info);
                    }

                    /**
                     * Get TomcatInfo.
                     *
                     * @return TomcatInfo or null
                     */
                    @Nullable
                    public TomcatInfo getTomcatInfo() {
                        return configData.getTomcatInfo();
                    }

                    /**
                     * Get VM options.
                     *
                     * @return VM options string or empty string
                     */
                    @NotNull
                    public String getVmOptions() {
                        try {
                            String opts = configData.getVmConfig().getVmOptions();
                            return StringUtil.notNullize(opts);
                        } catch (Exception e) {
                            LOG.warn("Error getting VM options", e);
                            return "";
                        }
                    }

                    /**
                     * Set VM options.
                     *
                     * @param vmOptions the VM options to set
                     */
                    public void setVmOptions(@Nullable String vmOptions) {
                        try {
                            configData.getVmConfig().setVmOptions(vmOptions);
                        } catch (Exception e) {
                            LOG.warn("Error setting VM options", e);
                        }
                    }

                    /**
                     * Check if hot deployment is enabled.
                     *
                     * @return true if hot deployment is enabled
                     */
                    public boolean isHotDeploymentEnabled() {
                        try {
                            return configData.getDeploymentConfig().isHotDeploymentEnabled();
                        } catch (Exception e) {
                            LOG.warn("Error checking hot deployment", e);
                            return false;
                        }
                    }

                    /**
                     * Set hot deployment enabled.
                     *
                     * @param enabled true to enable hot deployment
                     */
                    public void setHotDeploymentEnabled(boolean enabled) {
                        try {
                            configData.getDeploymentConfig().setHotDeploymentEnabled(enabled);
                        } catch (Exception e) {
                            LOG.warn("Error setting hot deployment", e);
                        }
                    }

                    /**
                     * Check if preserve sessions is enabled.
                     *
                     * @return true if preserve sessions is enabled
                     */
                    public boolean isPreserveSessions() {
                        try {
                            return configData.getDeploymentConfig().isPreserveSessions();
                        } catch (Exception e) {
                            LOG.warn("Error checking preserve sessions", e);
                            return false;
                        }
                    }

                    /**
                     * Set preserve sessions enabled.
                     *
                     * @param preserve true to enable preserve sessions
                     */
                    public void setPreserveSessions(boolean preserve) {
                        try {
                            configData.getDeploymentConfig().setPreserveSessions(preserve);
                        } catch (Exception e) {
                            LOG.warn("Error setting preserve sessions", e);
                        }
                    }

                    /**
                     * Get context path.
                     *
                     * @return context path or "/" if not configured
                     */
                    @NotNull
                    public String getContextPath() {
                        return getContextPathSafe();
                    }

                    /**
                     * Set context path.
                     *
                     * @param contextPath the context path to set
                     */
                    public void setContextPath(@Nullable String contextPath) {
                        configData.setContextPath(contextPath);
                    }

                    /**
                     * Get log file configurations.
                     *
                     * @return list of log file paths (never null)
                     */
                    @NotNull
                    public java.util.List<String> getLogFileConfigurations() {
                        try {
                            java.util.List<String> logFiles = configData.getLogFileConfig().getLogFiles();
                            return logFiles != null ? logFiles : new java.util.ArrayList<>();
                        } catch (Exception e) {
                            LOG.warn("Error getting log file configurations", e);
                            return new java.util.ArrayList<>();
                        }
                    }

                    /**
                     * Get environment variables.
                     *
                     * @return map of environment variables (never null)
                     */
                    @NotNull
                    public java.util.Map<String, String> getEnvironmentVariables() {
                        try {
                            java.util.Map<String, String> env = configData.getVmConfig().getEnvironmentVariables();
                            return env != null ? env : new java.util.HashMap<>();
                        } catch (Exception e) {
                            LOG.warn("Error getting environment variables", e);
                            return new java.util.HashMap<>();
                        }
                    }

                    /**
                     * Set environment variables.
                     *
                     * @param envVars the environment variables map
                     */
                    public void setEnvironmentVariables(@NotNull java.util.Map<String, String> envVars) {
                        try {
                            configData.getVmConfig().setEnvironmentVariables(envVars);
                        } catch (Exception e) {
                            LOG.warn("Error setting environment variables", e);
                        }
                    }

                    /**
                     * Check if parent environment variables should be passed.
                     *
                     * @return true if parent envs should be passed
                     */
                    public boolean isPassParentEnvs() {
                        try {
                            return configData.getVmConfig().isPassParentEnvs();
                        } catch (Exception e) {
                            LOG.warn("Error checking pass parent envs", e);
                            return true;
                        }
                    }

                    /**
                     * Set whether to pass parent environment variables.
                     *
                     * @param passParentEnvs true to pass parent envs
                     */
                    public void setPassParentEnvs(boolean passParentEnvs) {
                        try {
                            configData.getVmConfig().setPassParentEnvs(passParentEnvs);
                        } catch (Exception e) {
                            LOG.warn("Error setting pass parent envs", e);
                        }
                    }

                    /**
                     * Get debug port.
                     *
                     * @return debug port number
                     */
                    public int getDebugPort() {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            return dc != null ? dc.getPort() : 5005;
                        } catch (Exception e) {
                            LOG.warn("Error getting debug port", e);
                            return 5005;
                        }
                    }

                    /**
                     * Set debug port.
                     *
                     * @param port the debug port number
                     */
                    public void setDebugPort(int port) {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            if (dc != null) {
                                dc.setPort(port);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting debug port", e);
                        }
                    }

                    /**
                     * Get debug transport.
                     *
                     * @return debug transport type
                     */
                    @NotNull
                    public String getDebugTransport() {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            return dc != null ? dc.getTransport() : "Socket";
                        } catch (Exception e) {
                            LOG.warn("Error getting debug transport", e);
                            return "Socket";
                        }
                    }

                    /**
                     * Set debug transport.
                     *
                     * @param transport the debug transport type
                     */
                    public void setDebugTransport(@NotNull String transport) {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            if (dc != null) {
                                dc.setTransport(transport);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting debug transport", e);
                        }
                    }

                    /**
                     * Set whether to use module classpath.
                     *
                     * @param useModuleClasspath true to use module classpath
                     */
                    public void setUseModuleClasspath(boolean useModuleClasspath) {
                        try {
                            DebugConfig dc = configData.getDebugConfig();
                            if (dc != null) {
                                dc.setUseModuleClasspath(useModuleClasspath);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting use module classpath", e);
                        }
                    }

                    /**
                     * Set startup script path.
                     *
                     * @param startupScript the startup script path
                     */
                    public void setStartupScript(String startupScript) {
                        try {
                            // Store in configuration data - for now just log
                            LOG.debug("Startup script set to: " + startupScript);
                        } catch (Exception e) {
                            LOG.warn("Error setting startup script", e);
                        }
                    }

                    /**
                     * Set shutdown script path.
                     *
                     * @param shutdownScript the shutdown script path
                     */
                    public void setShutdownScript(String shutdownScript) {
                        try {
                            // Store in configuration data - for now just log
                            LOG.debug("Shutdown script set to: " + shutdownScript);
                        } catch (Exception e) {
                            LOG.warn("Error setting shutdown script", e);
                        }
                    }

                    /**
                     * Check if update classes and resources is enabled.
                     *
                     * @return true if update classes and resources is enabled
                     */
                    public boolean isUpdateClassesAndResources() {
                        try {
                            return configData.getDeploymentConfig().isUpdateClassesAndResources();
                        } catch (Exception e) {
                            LOG.warn("Error checking update classes and resources", e);
                            return false;
                        }
                    }

                    // === UI GETTERS AND SETTERS ===

                    /**
                     * Check if tool window should be activated.
                     *
                     * @return true if tool window should be activated
                     */
                    public boolean isActivateToolWindow() {
                        try {
                            UiConfig ui = configData.getUiConfig();
                            return ui != null && ui.isActivateToolWindow();
                        } catch (Exception e) {
                            LOG.warn("Error checking activate tool window", e);
                            return UiConfig.DEFAULT_ACTIVATE_TOOL_WINDOW;
                        }
                    }

                    /**
                     * Set whether tool window should be activated.
                     *
                     * @param activate true to activate tool window
                     */
                    public void setActivateToolWindow(boolean activate) {
                        try {
                            UiConfig ui = configData.getUiConfig();
                            if (ui != null) {
                                ui.setActivateToolWindow(activate);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting activate tool window", e);
                        }
                    }

                    /**
                     * Check if tool window should be focused.
                     *
                     * @return true if tool window should be focused
                     */
                    public boolean isFocusToolWindow() {
                        try {
                            UiConfig ui = configData.getUiConfig();
                            return ui != null && ui.isFocusToolWindow();
                        } catch (Exception e) {
                            LOG.warn("Error checking focus tool window", e);
                            return UiConfig.DEFAULT_FOCUS_TOOL_WINDOW;
                        }
                    }

                    /**
                     * Set whether tool window should be focused.
                     *
                     * @param focus true to focus tool window
                     */
                    public void setFocusToolWindow(boolean focus) {
                        try {
                            UiConfig ui = configData.getUiConfig();
                            if (ui != null) {
                                ui.setFocusToolWindow(focus);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting focus tool window", e);
                        }
                    }

                    // === BROWSER GETTERS AND SETTERS ===

                    /**
                     * Check if browser should be opened after launch.
                     *
                     * @return true if browser should be opened after launch
                     */
                    public boolean isAfterLaunchEnabled() {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            return bc != null && bc.isAfterLaunchEnabled();
                        } catch (Exception e) {
                            LOG.warn("Error checking after launch enabled", e);
                            return true; // Default to true
                        }
                    }

                    /**
                     * Set whether browser should be opened after launch.
                     *
                     * @param enabled true to open browser after launch
                     */
                    public void setAfterLaunchEnabled(boolean enabled) {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            if (bc != null) {
                                bc.setAfterLaunchEnabled(enabled);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting after launch enabled", e);
                        }
                    }

                    /**
                     * Get browser URL.
                     *
                     * @return browser URL (never null)
                     */
                    @NotNull
                    public String getBrowserUrl() {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            return bc != null ? bc.getBrowserUrl() : "";
                        } catch (Exception e) {
                            LOG.warn("Error getting browser URL", e);
                            return "";
                        }
                    }

                    /**
                     * Set browser URL.
                     *
                     * @param url the URL to set
                     */
                    public void setBrowserUrl(@NotNull String url) {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            if (bc != null) {
                                bc.setBrowserUrl(url);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting browser URL", e);
                        }
                    }

                    /**
                     * Get browser name.
                     *
                     * @return browser name (never null)
                     */
                    @NotNull
                    public String getBrowserName() {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            return bc != null ? bc.getBrowserName() : "System Default";
                        } catch (Exception e) {
                            LOG.warn("Error getting browser name", e);
                            return "System Default";
                        }
                    }

                    /**
                     * Set browser name.
                     *
                     * @param browserName the browser name to set
                     */
                    public void setBrowserName(@NotNull String browserName) {
                        try {
                            BrowserConfig bc = configData.getBrowserConfig();
                            if (bc != null) {
                                bc.setBrowserName(browserName);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error setting browser name", e);
                        }
                    }

                }