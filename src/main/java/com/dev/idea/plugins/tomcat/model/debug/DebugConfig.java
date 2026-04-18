package com.dev.idea.plugins.tomcat.model.debug;

    import com.dev.idea.plugins.tomcat.TomcatConstants;
    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;

    import java.util.Objects;

    /**
     * JDWP debug configuration for Tomcat.
     * Encapsulates port, transport protocol (Socket only), and classpath scope.
     * Shared Memory transport is not supported; legacy configs are normalized to Socket on load.
     */
    public class DebugConfig {

        private static final Logger LOG = Logger.getInstance(DebugConfig.class);

        public static final int DEFAULT_DEBUG_PORT = 5005;
        private static final int MIN_DEBUG_PORT = 1024;
        private static final int MAX_DEBUG_PORT = 65535;

        private int port;
        private String transport;
        private boolean useModuleClasspath;

        public DebugConfig() {
            this.port = DEFAULT_DEBUG_PORT;
            this.transport = TomcatConstants.TRANSPORT_SOCKET;
            this.useModuleClasspath = false;
        }

        public DebugConfig(int port, @NotNull String transport, boolean useModuleClasspath) {
            this.setPort(port);
            this.setTransport(transport);
            this.setUseModuleClasspath(useModuleClasspath);
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            if (port < MIN_DEBUG_PORT || port > MAX_DEBUG_PORT) {
                LOG.warn("Invalid debug port: " + port + ", using default " + DEFAULT_DEBUG_PORT);
                this.port = DEFAULT_DEBUG_PORT;
            } else {
                this.port = port;
            }
        }

        @NotNull
        public String getTransport() {
            return StringUtil.notNullize(transport, TomcatConstants.TRANSPORT_SOCKET);
        }

        public void setTransport(@NotNull String transport) {
            Objects.requireNonNull(transport, "Transport cannot be null");
            String normalized = transport.trim();
            if (!TomcatConstants.TRANSPORT_SOCKET.equalsIgnoreCase(normalized)) {
                LOG.warn("Unsupported transport '" + transport + "' (Shared Memory is not supported) — normalizing to " + TomcatConstants.TRANSPORT_SOCKET);
                this.transport = TomcatConstants.TRANSPORT_SOCKET;
            } else {
                this.transport = normalized;
            }
        }

        public boolean isUseModuleClasspath() {
            return useModuleClasspath;
        }

        public void setUseModuleClasspath(boolean use) {
            this.useModuleClasspath = use;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DebugConfig that = (DebugConfig) o;
            return port == that.port &&
                    useModuleClasspath == that.useModuleClasspath &&
                    Objects.equals(transport, that.transport);
        }

        @Override
        public int hashCode() {
            return Objects.hash(port, transport, useModuleClasspath);
        }

        @NotNull
        @Override
        public DebugConfig clone() {
            DebugConfig clone = new DebugConfig();
            clone.port = this.port;
            clone.transport = this.transport;
            clone.useModuleClasspath = this.useModuleClasspath;
            return clone;
        }

        @NotNull
        @Override
        public String toString() {
            return String.format("DebugConfig{port=%d, transport='%s', useModuleClasspath=%b}",
                    port, getTransport(), useModuleClasspath);
        }

        public boolean isValid() {
            return port >= MIN_DEBUG_PORT && port <= MAX_DEBUG_PORT;
        }

        /** Returns JDWP connection string, e.g. "dt_socket,server=y,suspend=n,address=5005" */
        @NotNull
        public String getJdwpConnectionString() {
            return String.format(TomcatConstants.JDWP_CONNECTION_FORMAT, TomcatConstants.JDWP_TRANSPORT_SOCKET, port);
        }

        /** Returns complete -agentlib VM argument for debug startup. */
        @NotNull
        public String getDebugVmArgument() {
            return TomcatConstants.JDWP_AGENT_PREFIX + getJdwpConnectionString();
        }
    }