package com.dev.idea.plugins.tomcat.model.remote;

    import com.dev.idea.plugins.tomcat.TomcatConstants;
    import com.intellij.openapi.diagnostic.Logger;
    import com.intellij.openapi.util.text.StringUtil;
    import org.jetbrains.annotations.NotNull;

    import java.util.Objects;
    import java.util.regex.Pattern;

    /**
     * Remote Tomcat deployment configuration via the Manager API.
     * Encapsulates manager URL, credentials, and validation.
     */
    public class RemoteConfig {

        private static final Logger LOG = Logger.getInstance(RemoteConfig.class);

        private static final String DEFAULT_MANAGER_URL = "http://localhost:8080/manager";
        private static final String DEFAULT_USERNAME = "admin";
        private static final int MAX_PASSWORD_LENGTH = 256;
        private static final int MAX_USERNAME_LENGTH = 128;
        private static final int MIN_PORT = 1;
        private static final int MAX_PORT = 65535;

        private static final Pattern MANAGER_URL_PATTERN = Pattern.compile(
                "^https?://[a-zA-Z0-9.-]+(:\\d{2,5})?/manager(/\\w+)?$"
        );

        private String managerUrl;
        private String username;
        private String password;
        private boolean useCredentials;

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

            if (normalized.isEmpty() || normalized.length() > MAX_USERNAME_LENGTH) {
                LOG.warn("Invalid username length, using default: " + DEFAULT_USERNAME);
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

        private boolean isValidManagerUrl(@NotNull String url) {
            if (!MANAGER_URL_PATTERN.matcher(url).matches()) {
                return false;
            }
            try {
                if (url.contains(":")) {
                    String portStr = url.split(":")[2].split("/")[0];
                    int port = Integer.parseInt(portStr);
                    return port >= MIN_PORT && port <= MAX_PORT;
                }
            } catch (Exception e) {
                return false;
            }
            return true;
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