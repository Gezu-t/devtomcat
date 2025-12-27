package com.dev.idea.plugins.tomcat.model.remote;

    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;

    import java.util.Objects;
    import java.util.regex.Pattern;

    /**
     * Remote Tomcat Deployment Configuration via Manager API
     *
     * Encapsulates settings for deploying applications to remote Tomcat servers
     * via the Tomcat Manager REST API:
     * - Manager URL and credentials
     * - Authentication configuration
     * - Deployment validation
     *
     * <p>100% NULL-SAFE — All fields validated, sensible defaults provided
     * <p>Immutable-Ready — Supports cloning for safe configuration passing
     * <p>Logging-Enabled — Debug logging for configuration changes
     * <p>Validation-Ready — URL and credential validation built-in
     *
     * <p>Default Configuration:
     * <ul>
     *   <li>Manager URL: http://localhost:8080/manager</li>
     *   <li>Username: admin</li>
     *   <li>Password: (empty)</li>
     *   <li>Use Credentials: false</li>
     * </ul>
     *
     * <p>Manager URL Format:
     * Valid examples:
     * <ul>
     *   <li>http://localhost:8080/manager</li>
     *   <li>http://localhost:8080/manager/text</li>
     *   <li>https://example.com:8443/manager</li>
     * </ul>
     *
     * Author: Gezahegn Lemma (Gezu)
     * Project: DevTomcat Plugin
     * Created: 6/9/25
     */
    public class RemoteConfig {

        private static final Logger LOG = Logger.getInstance(RemoteConfig.class);

        // Defaults
        private static final String DEFAULT_MANAGER_URL = "http://localhost:8080/manager";
        private static final String DEFAULT_USERNAME = "admin";
        private static final String DEFAULT_PASSWORD = "";

        // Validation constants
        private static final Pattern MANAGER_URL_PATTERN = Pattern.compile(
                "^https?://[a-zA-Z0-9.-]+(:\\d{2,5})?/manager(/\\w+)?$"
        );
        private static final int MIN_PASSWORD_LENGTH = 0;
        private static final int MAX_PASSWORD_LENGTH = 256;
        private static final int MIN_USERNAME_LENGTH = 1;
        private static final int MAX_USERNAME_LENGTH = 128;
        private static final int MIN_PORT = 1;
        private static final int MAX_PORT = 65535;

        // =====================================================================
        // FIELDS
        // =====================================================================

        private String managerUrl;
        private String username;
        private String password;
        private boolean useCredentials;

        // =====================================================================
        // CONSTRUCTORS
        // =====================================================================

        /**
         * Creates remote configuration with default values.
         *
         * <p>Defaults:
         * - Manager URL: http://localhost:8080/manager
         * - Username: admin
         * - Password: (empty)
         * - Use Credentials: false
         */
        public RemoteConfig() {
            this.managerUrl = DEFAULT_MANAGER_URL;
            this.username = DEFAULT_USERNAME;
            this.password = DEFAULT_PASSWORD;
            this.useCredentials = false;

            LOG.debug("RemoteConfig created with defaults: url=" + managerUrl + ", username=" + username);
        }

        /**
         * Creates remote configuration with specified values.
         *
         * @param managerUrl the manager API URL (cannot be null)
         * @param username the username (cannot be null)
         * @param password the password (can be empty)
         * @param useCredentials whether to use credentials
         */
        public RemoteConfig(@NotNull String managerUrl, @NotNull String username,
                           @NotNull String password, boolean useCredentials) {
            this.setManagerUrl(managerUrl);
            this.setUsername(username);
            this.setPassword(password);
            this.setUseCredentials(useCredentials);

            LOG.debug("RemoteConfig created: url=" + this.managerUrl + ", username=" + this.username +
                    ", useCredentials=" + useCredentials);
        }

        // =====================================================================
        // GETTERS & SETTERS
        // =====================================================================

        /**
         * Get the Tomcat Manager API URL.
         *
         * <p>Never returns null — returns default if not set.
         *
         * @return the manager URL (never null)
         */
        @NotNull
        public String getManagerUrl() {
            return StringUtil.notNullize(managerUrl, DEFAULT_MANAGER_URL).trim();
        }

        /**
         * Set the Tomcat Manager API URL with validation.
         *
         * <p>URL must be in format: http(s)://host:port/manager[/text]
         * Invalid URLs are rejected with warning log.
         *
         * @param url the manager URL (cannot be null)
         * @throws NullPointerException if url is null
         */
        public void setManagerUrl(@NotNull String url) {
            Objects.requireNonNull(url, "Manager URL cannot be null");

            String normalized = url.trim();

            if (normalized.isEmpty()) {
                LOG.warn("Manager URL is empty, using default: " + DEFAULT_MANAGER_URL);
                this.managerUrl = DEFAULT_MANAGER_URL;
                return;
            }

            if (!isValidManagerUrl(normalized)) {
                LOG.warn("Invalid Manager URL format: " + url + ", using default: " + DEFAULT_MANAGER_URL);
                this.managerUrl = DEFAULT_MANAGER_URL;
            } else {
                this.managerUrl = normalized;
                LOG.debug("Manager URL set to: " + normalized);
            }
        }

        /**
         * Get the username for authentication.
         *
         * <p>Never returns null — returns default if not set.
         *
         * @return the username (never null)
         */
        @NotNull
        public String getUsername() {
            return StringUtil.notNullize(username, DEFAULT_USERNAME);
        }

        /**
         * Set the username with validation.
         *
         * <p>Username must be 1-128 characters.
         * Empty usernames default to "admin".
         *
         * @param username the username (cannot be null)
         * @throws NullPointerException if username is null
         */
        public void setUsername(@NotNull String username) {
            Objects.requireNonNull(username, "Username cannot be null");

            String normalized = username.trim();

            if (normalized.isEmpty() || normalized.length() > MAX_USERNAME_LENGTH) {
                LOG.warn("Invalid username length, using default: " + DEFAULT_USERNAME);
                this.username = DEFAULT_USERNAME;
            } else {
                this.username = normalized;
                LOG.debug("Username set to: " + normalized);
            }
        }

        /**
         * Get the password for authentication.
         *
         * <p>Never returns null — returns empty string if not set.
         *
         * @return the password (never null)
         */
        @NotNull
        public String getPassword() {
            return password != null ? password : "";
        }

        /**
         * Set the password with validation.
         *
         * <p>Password can be empty but must not exceed 256 characters.
         * Null passwords are converted to empty string.
         *
         * @param password the password (can be null or empty)
         */
        public void setPassword(String password) {
            if (password == null || password.isEmpty()) {
                this.password = "";
                LOG.debug("Password cleared");
            } else if (password.length() > MAX_PASSWORD_LENGTH) {
                LOG.warn("Password too long (max 256 chars), clearing for security");
                this.password = "";
            } else {
                this.password = password;
                LOG.debug("Password set securely");
            }
        }

        /**
         * Check if credentials should be used for authentication.
         *
         * @return true if credentials are enabled
         */
        public boolean isUseCredentials() {
            return useCredentials;
        }

        /**
         * Set whether to use credentials for authentication.
         *
         * @param use true to enable credential authentication
         */
        public void setUseCredentials(boolean use) {
            this.useCredentials = use;
            LOG.debug("Use credentials enabled: " + use);
        }

        // =====================================================================
        // VALIDATION METHODS
        // =====================================================================

        /**
         * Validate the manager URL format.
         *
         * <p>Valid format: http(s)://host:port/manager[/text]
         *
         * @param url the URL to validate (cannot be null)
         * @return true if URL is valid format
         */
        private boolean isValidManagerUrl(@NotNull String url) {
            Objects.requireNonNull(url, "URL cannot be null");

            // Check basic pattern
            if (!MANAGER_URL_PATTERN.matcher(url).matches()) {
                LOG.debug("Manager URL does not match pattern: " + url);
                return false;
            }

            // Extract and validate port if present
            try {
                if (url.contains(":")) {
                    String portStr = url.split(":")[2].split("/")[0];
                    int port = Integer.parseInt(portStr);

                    if (port < MIN_PORT || port > MAX_PORT) {
                        LOG.debug("Invalid port in Manager URL: " + port);
                        return false;
                    }
                }
            } catch (Exception e) {
                LOG.debug("Error parsing Manager URL port: " + url, e);
                return false;
            }

            return true;
        }

        /**
         * Check if remote configuration is valid.
         *
         * <p>Valid configuration must have:
         * - Valid manager URL format
         * - Valid username (if credentials enabled)
         * - Valid password length (if credentials enabled)
         *
         * @return true if configuration is valid
         */
        public boolean isValid() {
            // Always validate URL
            if (!isValidManagerUrl(getManagerUrl())) {
                LOG.warn("Remote config invalid: bad manager URL");
                return false;
            }

            // If credentials are enabled, validate them
            if (useCredentials) {
                String user = getUsername();
                String pass = getPassword();

                if (user.isEmpty() || user.length() > MAX_USERNAME_LENGTH) {
                    LOG.warn("Remote config invalid: bad username");
                    return false;
                }

                if (pass.length() > MAX_PASSWORD_LENGTH) {
                    LOG.warn("Remote config invalid: bad password length");
                    return false;
                }
            }

            LOG.debug("Remote config is valid");
            return true;
        }

        /**
         * Check if credentials are properly configured.
         *
         * <p>Returns true if credentials are enabled AND username is not empty.
         *
         * @return true if credentials are ready to use
         */
        public boolean hasValidCredentials() {
            return useCredentials && !getUsername().isEmpty() && getPassword().length() <= MAX_PASSWORD_LENGTH;
        }

        // =====================================================================
        // EQUALITY & HASHING
        // =====================================================================

        /**
         * Check if two remote configurations are equal.
         *
         * <p>Compares all fields: url, username, password, and useCredentials.
         *
         * @param o the object to compare (can be null)
         * @return true if configurations are identical
         */
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

        /**
         * Generate hash code for remote configuration.
         *
         * @return hash code based on all fields
         */
        @Override
        public int hashCode() {
            return Objects.hash(getManagerUrl(), getUsername(), getPassword(), useCredentials);
        }

        // =====================================================================
        // CLONING & STRING REPRESENTATION
        // =====================================================================

        /**
         * Create a deep copy of this remote configuration.
         *
         * <p>Returns a new instance with identical values.
         * Safe to modify the clone without affecting the original.
         *
         * @return cloned configuration (never null)
         */
        @NotNull
        @Override
        public RemoteConfig clone() {
            RemoteConfig clone = new RemoteConfig();
            clone.managerUrl = this.managerUrl;
            clone.username = this.username;
            clone.password = this.password;
            clone.useCredentials = this.useCredentials;

            LOG.debug("RemoteConfig cloned successfully");
            return clone;
        }

        /**
         * Get human-readable string representation.
         *
         * <p>Format: "RemoteConfig{url=http://..., username=admin, useCredentials=false}"
         * Password is masked for security.
         *
         * @return formatted string (never null)
         */
        @NotNull
        @Override
        public String toString() {
            return "RemoteConfig{" +
                    "managerUrl='" + getManagerUrl() + '\'' +
                    ", username='" + getUsername() + '\'' +
                    ", password='" + (getPassword().isEmpty() ? "(empty)" : "****") + '\'' +
                    ", useCredentials=" + useCredentials +
                    '}';
        }
    }