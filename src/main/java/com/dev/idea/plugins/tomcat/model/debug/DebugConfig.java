package com.dev.idea.plugins.tomcat.model.debug;

    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;

    import java.util.Objects;

    /**
     * Debug Configuration for Tomcat JDWP
     *
     * Encapsulates JDWP (Java Debug Wire Protocol) settings for remote debugging:
     * - Port configuration for debug connection
     * - Transport protocol selection (Socket or SharedMemory)
     * - Classpath configuration for module debugging
     *
     * <p>100% NULL-SAFE — All fields validated, sensible defaults provided
     * <p>Immutable-Ready — Supports cloning for safe configuration passing
     * <p>Logging-Enabled — Debug logging for configuration changes
     *
     * <p>Debug Transport Options:
     * <ul>
     *   <li>Socket — TCP/IP connection (recommended for remote debugging)</li>
     *   <li>SharedMemory — Windows-only, local machine debugging</li>
     * </ul>
     *
     * <p>Default Configuration:
     * <ul>
     *   <li>Port: 1099 (standard Java debug port)</li>
     *   <li>Transport: Socket (works across platforms)</li>
     *   <li>Classpath: Use standard classpath (not module-isolated)</li>
     * </ul>
     *
     * Author: Gezahegn Lemma (Gezu)
     * Project: DevTomcat Plugin
     * Created: 6/9/25
     */
    public class DebugConfig {

        private static final Logger LOG = Logger.getInstance(DebugConfig.class);

        // Default values
        private static final int DEFAULT_DEBUG_PORT = 5005;
        private static final String DEFAULT_TRANSPORT = "Socket";

        // Valid transport options
        private static final String TRANSPORT_SOCKET = "Socket";
        private static final String TRANSPORT_SHARED_MEMORY = "SharedMemory";

        // Port constraints
        private static final int MIN_DEBUG_PORT = 1024;
        private static final int MAX_DEBUG_PORT = 65535;

        // =====================================================================
        // FIELDS
        // =====================================================================

        private int port;
        private String transport;
        private boolean useModuleClasspath;

        // =====================================================================
        // CONSTRUCTORS
        // =====================================================================

        /**
         * Creates debug configuration with default values.
         *
         * <p>Default Configuration:
         * - Port: 5005 (standard JDWP debug port)
         * - Transport: Socket
         * - Module Classpath: false (use standard classpath)
         */
        public DebugConfig() {
            this.port = DEFAULT_DEBUG_PORT;
            this.transport = DEFAULT_TRANSPORT;
            this.useModuleClasspath = false;

            LOG.debug("DebugConfig created with defaults: port=" + port + ", transport=" + transport);
        }

        /**
         * Creates debug configuration with specified values.
         *
         * @param port the debug port (1024-65535)
         * @param transport the transport type (Socket or SharedMemory)
         * @param useModuleClasspath whether to use module-isolated classpath
         */
        public DebugConfig(int port, @NotNull String transport, boolean useModuleClasspath) {
            this.setPort(port);
            this.setTransport(transport);
            this.setUseModuleClasspath(useModuleClasspath);

            LOG.debug("DebugConfig created: port=" + port + ", transport=" + transport +
                    ", useModuleClasspath=" + useModuleClasspath);
        }

        // =====================================================================
        // GETTERS & SETTERS
        // =====================================================================

        /**
         * Get the debug port.
         *
         * @return the port number (1024-65535)
         */
        public int getPort() {
            return port;
        }

        /**
         * Set the debug port with validation.
         *
         * <p>Port must be between 1024-65535 to avoid system/reserved ports.
         * Invalid ports are rejected with a warning log.
         *
         * @param port the port number to set (1024-65535)
         */
        public void setPort(int port) {
            if (port < MIN_DEBUG_PORT || port > MAX_DEBUG_PORT) {
                LOG.warn("Invalid debug port: " + port + ", using default " + DEFAULT_DEBUG_PORT);
                this.port = DEFAULT_DEBUG_PORT;
            } else {
                this.port = port;
                LOG.debug("Debug port set to: " + port);
            }
        }

        /**
         * Get the debug transport protocol.
         *
         * <p>Returns Socket or SharedMemory. Never returns null.
         *
         * @return the transport type (never null)
         */
        @NotNull
        public String getTransport() {
            return StringUtil.notNullize(transport, DEFAULT_TRANSPORT);
        }

        /**
         * Set the debug transport protocol with validation.
         *
         * <p>Accepts "Socket" or "SharedMemory".
         * Invalid values default to Socket.
         *
         * @param transport the transport type (cannot be null)
         * @throws NullPointerException if transport is null
         */
        public void setTransport(@NotNull String transport) {
            Objects.requireNonNull(transport, "Transport cannot be null");

            String normalized = transport.trim();

            if (!TRANSPORT_SOCKET.equalsIgnoreCase(normalized) &&
                    !TRANSPORT_SHARED_MEMORY.equalsIgnoreCase(normalized)) {
                LOG.warn("Invalid transport: " + transport + ", using default " + DEFAULT_TRANSPORT);
                this.transport = DEFAULT_TRANSPORT;
            } else {
                this.transport = normalized;
                LOG.debug("Debug transport set to: " + transport);
            }
        }

        /**
         * Check if module-isolated classpath is enabled.
         *
         * <p>When true, debug only runs with the selected module's classpath.
         * When false, uses the complete project classpath.
         *
         * @return true if module classpath is enabled
         */
        public boolean isUseModuleClasspath() {
            return useModuleClasspath;
        }

        /**
         * Set whether to use module-isolated classpath.
         *
         * @param use true to use module classpath, false for project classpath
         */
        public void setUseModuleClasspath(boolean use) {
            this.useModuleClasspath = use;
            LOG.debug("Module classpath enabled: " + use);
        }

        // =====================================================================
        // EQUALITY & HASHING
        // =====================================================================

        /**
         * Check if two debug configurations are equal.
         *
         * <p>Compares all fields: port, transport, and useModuleClasspath.
         *
         * @param o the object to compare (can be null)
         * @return true if configurations are identical
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DebugConfig that = (DebugConfig) o;

            return port == that.port &&
                    useModuleClasspath == that.useModuleClasspath &&
                    Objects.equals(transport, that.transport);
        }

        /**
         * Generate hash code for debug configuration.
         *
         * @return hash code based on all fields
         */
        @Override
        public int hashCode() {
            return Objects.hash(port, transport, useModuleClasspath);
        }

        // =====================================================================
        // CLONING & STRING REPRESENTATION
        // =====================================================================

        /**
         * Create a deep copy of this debug configuration.
         *
         * <p>Returns a new instance with identical values.
         * Safe to modify the clone without affecting the original.
         *
         * @return cloned configuration (never null)
         */
        @NotNull
        @Override
        public DebugConfig clone() {
            DebugConfig clone = new DebugConfig();
            clone.port = this.port;
            clone.transport = this.transport;
            clone.useModuleClasspath = this.useModuleClasspath;

            LOG.debug("DebugConfig cloned successfully");
            return clone;
        }

        /**
         * Get human-readable string representation.
         *
         * <p>Format: "DebugConfig{port=5005, transport=Socket, useModuleClasspath=false}"
         *
         * @return formatted string (never null)
         */
        @NotNull
        @Override
        public String toString() {
            return "DebugConfig{" +
                    "port=" + port +
                    ", transport='" + getTransport() + '\'' +
                    ", useModuleClasspath=" + useModuleClasspath +
                    '}';
        }

        // =====================================================================
        // VALIDATION UTILITIES
        // =====================================================================

        /**
         * Check if debug configuration is valid.
         *
         * <p>Valid configuration must have:
         * - Port between 1024-65535
         * - Transport set to Socket or SharedMemory
         *
         * @return true if configuration is valid
         */
        public boolean isValid() {
            boolean portValid = port >= MIN_DEBUG_PORT && port <= MAX_DEBUG_PORT;
            boolean transportValid = TRANSPORT_SOCKET.equalsIgnoreCase(transport) ||
                    TRANSPORT_SHARED_MEMORY.equalsIgnoreCase(transport);

            boolean valid = portValid && transportValid;

            if (!valid) {
                LOG.warn("Invalid debug configuration: port=" + port + ", transport=" + transport);
            }

            return valid;
        }

        /**
         * Get debug connection string for JDWP.
         *
         * <p>Returns the connection string used to connect debugger to target VM:
         * Example: "dt_socket,server=y,suspend=n,address=5005"
         *
         * @return JDWP connection string (never null)
         */
        @NotNull
        public String getJdwpConnectionString() {
            String transportName = TRANSPORT_SOCKET.equalsIgnoreCase(transport) ? "dt_socket" : "dt_shmem";
            return transportName + ",server=y,suspend=n,address=" + port;
        }

        /**
         * Get debug VM argument for Tomcat startup.
         *
         * <p>Returns the complete -agentlib argument:
         * Example: "-agentlib:jdwp=dt_socket,server=y,suspend=n,address=5005"
         *
         * @return VM argument ready for JVM (never null)
         */
        @NotNull
        public String getDebugVmArgument() {
            return "-agentlib:jdwp=" + getJdwpConnectionString();
        }
    }