package com.dev.idea.plugins.tomcat.model.remote;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Remote Tomcat deployment configuration via the Manager API.
 * Encapsulates manager URL, credentials, and validation.
 */
public class RemoteConfig {

    private static final Logger LOG = Logger.getInstance(RemoteConfig.class);

    private static final String DEFAULT_MANAGER_URL = "http://" + TomcatConstants.DEFAULT_HOST + ":" + TomcatConstants.DEFAULT_PORT + "/manager";
    private static final String DEFAULT_USERNAME = "admin";
    private static final int MAX_PASSWORD_LENGTH = 256;
    private static final int MAX_USERNAME_LENGTH = 128;

    /**
     * Validates a Tomcat Manager URL.
     * Accepts: http(s)://host[:port]/manager[/subpath]
     * The host may be a hostname, IPv4 literal, or bracketed IPv6 literal.
     * Port must be 1-65535 if present.
     *
     * <p>Supported bracketed IPv6 forms (all reachable today):
     * <ul>
     *   <li>basic: {@code [::1]}, {@code [2001:db8::1]}</li>
     *   <li>IPv4-mapped: {@code [::ffff:192.0.2.1]}</li>
     *   <li>zone-qualified: {@code [fe80::1%25en0]}</li>
     * </ul>
     *
     * <p>The regex is a loose structural guard that would pass additional
     * RFC 3986 IP-literal shapes (notably {@code IPvFuture}), but
     * {@link #isValidManagerUrl} delegates to {@link java.net.URI} as the
     * authoritative validator — and this JDK's {@code URI} parser rejects
     * {@code IPvFuture}. That narrower JDK-imposed contract is what this
     * class actually accepts; the regex is kept loose only so the common
     * forms above reach the URI validator unfiltered.
     */
    public static final Pattern MANAGER_URL_PATTERN = Pattern.compile(
            "^https?://(?:\\[[^\\s/\\]]+]|[a-zA-Z0-9.-]+)(:\\d{1,5})?/manager(/\\w+)?$"
    );

    private String managerUrl;
    private String username;
    private String password;
    private boolean useCredentials;
    /** Set after PasswordSafe or legacy password has been resolved by the deserializer. */
    private volatile boolean credentialsResolved;

    public RemoteConfig() {
        this.managerUrl = DEFAULT_MANAGER_URL;
        this.username = DEFAULT_USERNAME;
        this.password = "";
        this.useCredentials = false;
    }

    public RemoteConfig(@NotNull String managerUrl, @NotNull String username,
                        @NotNull String password, boolean useCredentials) {
        this.setManagerUrl(managerUrl);
        this.setUsername(username);
        this.setPassword(password);
        this.setUseCredentials(useCredentials);
    }

    @NotNull
    public String getManagerUrl() {
        return StringUtil.notNullize(managerUrl, DEFAULT_MANAGER_URL).trim();
    }

    public void setManagerUrl(@NotNull String url) {
        Objects.requireNonNull(url, "Manager URL cannot be null");
        String normalized = url.trim();

        if (normalized.isEmpty()) {
            LOG.warn("Manager URL is empty, using default: " + DEFAULT_MANAGER_URL);
            this.managerUrl = DEFAULT_MANAGER_URL;
        } else if (!isValidManagerUrl(normalized)) {
            LOG.warn("Invalid Manager URL format: " + url + ", using default: " + DEFAULT_MANAGER_URL);
            this.managerUrl = DEFAULT_MANAGER_URL;
        } else {
            this.managerUrl = normalized;
        }
    }

    @NotNull
    public String getUsername() {
        return StringUtil.notNullize(username, DEFAULT_USERNAME);
    }

    public void setUsername(@NotNull String username) {
        Objects.requireNonNull(username, "Username cannot be null");
        String normalized = username.trim();

        if (normalized.length() > MAX_USERNAME_LENGTH) {
            LOG.warn("Username too long (max " + MAX_USERNAME_LENGTH + " chars), using default: " + DEFAULT_USERNAME);
            this.username = DEFAULT_USERNAME;
        } else {
            this.username = normalized;
        }
    }

    @NotNull
    public String getPassword() {
        return password != null ? password : "";
    }

    public void setPassword(String password) {
        if (password == null || password.isEmpty()) {
            this.password = "";
        } else if (password.length() > MAX_PASSWORD_LENGTH) {
            LOG.warn("Password too long (max " + MAX_PASSWORD_LENGTH + " chars), clearing for security");
            this.password = "";
        } else {
            this.password = password;
        }
    }

    public boolean isUseCredentials() {
        return useCredentials;
    }

    public void setUseCredentials(boolean use) {
        this.useCredentials = use;
    }

    public boolean isCredentialsResolved() {
        return credentialsResolved;
    }

    public void setCredentialsResolved(boolean resolved) {
        this.credentialsResolved = resolved;
    }

    /**
     * Validates whether the given URL matches the expected Tomcat Manager format
     * and has a valid port number (1-65535) if specified.
     *
     * @param url the URL to validate
     * @return true if the URL is a valid Manager URL
     */
    public static boolean isValidManagerUrl(@NotNull String url) {
        if (!MANAGER_URL_PATTERN.matcher(url).matches()) {
            return false;
        }
        // URI parsing is the authoritative validator — the regex accepts shapes
        // that can't be hand-parsed correctly (the bracketed IPv6 host has its
        // own embedded colons that the old string-scan logic confused with the
        // port separator). URI also enforces a valid host and port range.
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return false;
            }
            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isValid() {
        if (!isValidManagerUrl(getManagerUrl())) {
            return false;
        }
        if (useCredentials) {
            String user = getUsername();
            if (user.isEmpty() || user.length() > MAX_USERNAME_LENGTH) {
                return false;
            }
            if (getPassword().length() > MAX_PASSWORD_LENGTH) {
                return false;
            }
        }
        return true;
    }

    public boolean hasValidCredentials() {
        return useCredentials && !getUsername().isEmpty() && getPassword().length() <= MAX_PASSWORD_LENGTH;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RemoteConfig that = (RemoteConfig) o;
        return useCredentials == that.useCredentials &&
                Objects.equals(getManagerUrl(), that.getManagerUrl()) &&
                Objects.equals(getUsername(), that.getUsername()) &&
                Objects.equals(getPassword(), that.getPassword());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getManagerUrl(), getUsername(), getPassword(), useCredentials);
    }

    @NotNull
    @Override
    public RemoteConfig clone() {
        RemoteConfig clone = new RemoteConfig();
        clone.managerUrl = this.managerUrl;
        clone.username = this.username;
        clone.password = this.password;
        clone.useCredentials = this.useCredentials;
        clone.credentialsResolved = this.credentialsResolved;
        return clone;
    }

    /** Password is masked for security. */
    @NotNull
    @Override
    public String toString() {
        return String.format("RemoteConfig{url='%s', username='%s', password='%s', useCredentials=%b}",
                getManagerUrl(), getUsername(),
                getPassword().isEmpty() ? TomcatConstants.PASSWORD_EMPTY : TomcatConstants.PASSWORD_MASKED,
                useCredentials);
    }
}