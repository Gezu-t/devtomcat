package com.dev.idea.plugins.tomcat.model;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Port Configuration for Tomcat server.
 */
public class PortConfig implements Serializable, Cloneable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_HTTPS_PORT = 8443;
    public static final int DEFAULT_JMX_PORT = 1099;
    public static final int DEFAULT_AJP_PORT = 8009;
    public static final int DEFAULT_SHUTDOWN_PORT = 8005;
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int PRIVILEGED_PORT_THRESHOLD = 1024;

    private int http = DEFAULT_HTTP_PORT;
    private int https = DEFAULT_HTTPS_PORT;
    private int jmx = DEFAULT_JMX_PORT;
    private int ajp = DEFAULT_AJP_PORT;
    private int shutdown = DEFAULT_SHUTDOWN_PORT;
    private boolean httpsEnabled;
    private boolean jmxEnabled;
    private boolean ajpEnabled;

    // Preferred-vs-resolved port snapshot.
    // 0 = no snapshot, getPreferredHttp() falls through to http.
    private int preferredHttp = 0;
    private int preferredShutdown = 0;

    // Port-conflict policy.
    @NotNull
    private PortStrategy strategy = PortStrategy.AUTO_BUMP;

    public PortConfig() {}

    public PortConfig(int http, int shutdown) {
        this.http = http;
        this.shutdown = shutdown;
    }

    public PortConfig(int http, int https, int jmx, int shutdown) {
        this.http = http;
        this.https = https;
        this.jmx = jmx;
        this.shutdown = shutdown;
    }

    public PortConfig(@NotNull PortConfig other) {
        Objects.requireNonNull(other, "PortConfig cannot be null");
        this.http = other.http;
        this.https = other.https;
        this.jmx = other.jmx;
        this.ajp = other.ajp;
        this.shutdown = other.shutdown;
        this.httpsEnabled = other.httpsEnabled;
        this.jmxEnabled = other.jmxEnabled;
        this.ajpEnabled = other.ajpEnabled;
        this.preferredHttp = other.preferredHttp;
        this.preferredShutdown = other.preferredShutdown;
        this.strategy = other.strategy;
    }

    @NotNull public PortStrategy getStrategy() { return strategy; }
    public void setStrategy(@NotNull PortStrategy strategy) { this.strategy = strategy; }

    public int getHttp() { return http; }

    // UI/external setter: explicit user intent — clears preferred snapshot.
    public void setHttp(int port) {
        if (port == this.http) return;
        this.http = port;
        this.preferredHttp = 0;
    }

    // Launcher-only setter: snapshots current http into preferred BEFORE overwriting.
    public void setHttpResolved(int port) {
        if (port == this.http) return;
        if (this.preferredHttp <= 0 && this.http > 0) {
            this.preferredHttp = this.http;
        }
        this.http = port;
    }

    public int getPreferredHttp() {
        return preferredHttp > 0 ? preferredHttp : http;
    }

    // Serializer-only persistence hook. Use setHttp/setHttpResolved elsewhere.
    public void setPreferredHttp(int port) { this.preferredHttp = Math.max(0, port); }

    public int getHttps() { return https; }
    public void setHttps(int port) { this.https = port; }

    public int getJmx() { return jmx; }
    public void setJmx(int port) { this.jmx = port; }

    public int getAjp() { return ajp; }
    public void setAjp(int port) { this.ajp = port; }

    public int getShutdown() { return shutdown; }

    public void setShutdown(int port) {
        if (port == this.shutdown) return;
        this.shutdown = port;
        this.preferredShutdown = 0;
    }

    public void setShutdownResolved(int port) {
        if (port == this.shutdown) return;
        if (this.preferredShutdown <= 0 && this.shutdown > 0) {
            this.preferredShutdown = this.shutdown;
        }
        this.shutdown = port;
    }

    public int getPreferredShutdown() {
        return preferredShutdown > 0 ? preferredShutdown : shutdown;
    }

    public void setPreferredShutdown(int port) { this.preferredShutdown = Math.max(0, port); }

    public boolean isHttpsEnabled() { return httpsEnabled; }
    public void setHttpsEnabled(boolean enabled) { this.httpsEnabled = enabled; }

    public boolean isJmxEnabled() { return jmxEnabled; }
    public void setJmxEnabled(boolean enabled) { this.jmxEnabled = enabled; }

    public boolean isAjpEnabled() { return ajpEnabled; }
    public void setAjpEnabled(boolean enabled) { this.ajpEnabled = enabled; }

    @NotNull
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        validatePort(http, "HTTP", result);
        validatePort(shutdown, "Shutdown", result);
        if (httpsEnabled) validatePort(https, "HTTPS", result);
        if (jmxEnabled) validatePort(jmx, "JMX", result);
        if (ajpEnabled) validatePort(ajp, "AJP", result);
        checkConflicts(result);
        return result;
    }

    private void validatePort(int port, String name, ValidationResult result) {
        if (port < MIN_PORT || port > MAX_PORT) {
            result.addError(name + " port must be between " + MIN_PORT + "-" + MAX_PORT);
        }
        // Port availability (socket binding) is intentionally NOT checked here — this method
        // runs on the EDT during live validation. Availability is checked at launch time
        // by PortConflictDetector and TomcatJavaParametersBuilder.
        if (port < PRIVILEGED_PORT_THRESHOLD) {
            result.addWarning(name + " port " + port + " requires admin privileges");
        }
    }

    private void checkConflicts(ValidationResult result) {
        Set<Integer> used = new HashSet<>();
        checkPortConflict(http, "HTTP", used, result);
        checkPortConflict(shutdown, "Shutdown", used, result);
        if (httpsEnabled) checkPortConflict(https, "HTTPS", used, result);
        if (jmxEnabled) checkPortConflict(jmx, "JMX", used, result);
        if (ajpEnabled) checkPortConflict(ajp, "AJP", used, result);
    }

    private void checkPortConflict(int port, String name, Set<Integer> used, ValidationResult result) {
        if (!used.add(port)) {
            result.addError("Port " + port + " is used by multiple services");
        }
    }


    @NotNull
    @Override
    public PortConfig clone() { return new PortConfig(this); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortConfig that)) return false;
        return http == that.http && https == that.https && jmx == that.jmx && ajp == that.ajp &&
                shutdown == that.shutdown && httpsEnabled == that.httpsEnabled && jmxEnabled == that.jmxEnabled && ajpEnabled == that.ajpEnabled;
    }

    @Override
    public int hashCode() { return Objects.hash(http, https, jmx, ajp, shutdown, httpsEnabled, jmxEnabled, ajpEnabled); }

    @NotNull
    @Override
    public String toString() {
        return "PortConfig{http=" + http + ", https=" + https + ", jmx=" + jmx + ", ajp=" + ajp +
                ", shutdown=" + shutdown + ", httpsEnabled=" + httpsEnabled + ", jmxEnabled=" + jmxEnabled + ", ajpEnabled=" + ajpEnabled + '}';
    }
}