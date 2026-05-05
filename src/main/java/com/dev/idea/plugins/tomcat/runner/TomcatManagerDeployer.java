package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.utils.ContextPathUtils;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.progress.ProgressIndicator;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.BooleanSupplier;

/**
 * Deploys artifacts to a remote Tomcat instance via the Manager API (text interface).
 *
 * <p>Uses the Tomcat Manager text commands:
 * <ul>
 *   <li>{@code /text/deploy?path=/ctx&war=file:...} — deploy a WAR file</li>
 *   <li>{@code /text/undeploy?path=/ctx} — undeploy an existing context</li>
 *   <li>{@code /text/list} — list deployed applications</li>
 *   <li>{@code PUT /text/deploy?path=/ctx} — upload & deploy WAR via HTTP PUT</li>
 * </ul>
 *
 * <p>Requires the {@code manager-script} role in {@code tomcat-users.xml}.
 *
 * @see <a href="https://tomcat.apache.org/tomcat-9.0-doc/manager-howto.html">Tomcat Manager Howto</a>
 */
public final class TomcatManagerDeployer {

    /** Tri-state result distinguishing success, failure, and user cancellation. */
    public enum DeployResult { SUCCESS, FAILED, CANCELLED }

    private static final Logger LOG = Logger.getInstance(TomcatManagerDeployer.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 120_000; // WAR uploads can be slow
    private static final int MAX_RESPONSE_CHARS = 64 * 1024; // 64 KB cap for response reading
    private static final String TEXT_ENDPOINT = "/text";
    private static final String OK_PREFIX = "OK -";

    private final RemoteConfig config;

    public TomcatManagerDeployer(@NotNull RemoteConfig config) {
        this.config = config;
    }

    /**
     * Deploys a single artifact to the remote Tomcat via Manager API.
     *
     * <p>For WAR files, uses HTTP PUT to upload the file content.
     * For exploded directories, uses the {@code war=file:} parameter (requires filesystem access on the server).
     *
     * @return true if deployment succeeded
     */
    public boolean deploy(@NotNull DeploymentArtifact artifact, @Nullable TomcatDeploymentLogger logger) {
        return deployWithProgress(artifact, logger, null) == DeployResult.SUCCESS;
    }

    /**
     * Deploys with optional progress reporting for WAR uploads.
     * Returns a tri-state result so callers can distinguish cancellation from failure.
     *
     * <p>Equivalent to calling {@link #deployWithProgress(DeploymentArtifact,
     * TomcatDeploymentLogger, ProgressIndicator, BooleanSupplier)} with a
     * no-op abort check; preserved for callers that don't have a
     * process-state predicate to thread through.
     */
    @NotNull
    public DeployResult deployWithProgress(@NotNull DeploymentArtifact artifact,
                                            @Nullable TomcatDeploymentLogger logger,
                                            @Nullable ProgressIndicator indicator) {
        return deployWithProgress(artifact, logger, indicator, () -> false);
    }

    /**
     * Deploys with optional progress reporting and an abort predicate that is
     * polled <em>during</em> the chunk-by-chunk WAR upload (not just between
     * artifacts). Use this overload from callers that need to abort an
     * in-flight upload when something other than the user clicking Cancel
     * happens (e.g. the local Tomcat process terminating mid-upload from
     * {@link TomcatProcessHandler}).
     *
     * @param abortCheck predicate polled inside the upload loop; returning
     *                   {@code true} aborts the upload with
     *                   {@link DeployResult#CANCELLED}. Must not block.
     */
    @NotNull
    public DeployResult deployWithProgress(@NotNull DeploymentArtifact artifact,
                                            @Nullable TomcatDeploymentLogger logger,
                                            @Nullable ProgressIndicator indicator,
                                            @NotNull BooleanSupplier abortCheck) {
        if (indicator != null && indicator.isCanceled()) {
            log(logger, "Deployment skipped (cancelled): " + artifact.getDisplayName());
            return DeployResult.CANCELLED;
        }
        if (abortCheck.getAsBoolean()) {
            log(logger, "Deployment skipped (process terminating): " + artifact.getDisplayName());
            return DeployResult.CANCELLED;
        }

        String contextPath = normalizeContextPath(artifact.getContextPath());
        log(logger, "Deploying '" + artifact.getDisplayName() + "' to " + contextPath + " ...");

        try {
            undeploy(contextPath, null);

            if (DeploymentArtifact.TYPE_WAR.equals(artifact.getType())) {
                return deployWarViaPut(artifact, contextPath, logger, indicator, abortCheck);
            } else {
                return deployExplodedViaPath(artifact, contextPath, logger)
                        ? DeployResult.SUCCESS : DeployResult.FAILED;
            }
        } catch (Exception e) {
            LOG.warn("Remote deployment failed: " + artifact.getDisplayName(), e);
            logError(logger, "Deployment failed: " + e.getMessage());
            return DeployResult.FAILED;
        }
    }

    /**
     * Undeploys an application from the remote Tomcat.
     *
     * @return true if undeploy succeeded
     */
    public boolean undeploy(@NotNull String contextPath, @Nullable TomcatDeploymentLogger logger) {
        String normalized = normalizeContextPath(contextPath);
        try {
            // Encode the path; isValidContextPath permits '&'/'=' which would inject extra query params.
            String url = getManagerUrl() + TEXT_ENDPOINT + "/undeploy?path=" + encodePathParam(normalized);
            String response = executeGet(url);
            boolean success = response != null && response.startsWith(OK_PREFIX);
            if (success && logger != null) {
                log(logger, "Undeployed context: " + normalized);
            }
            return success;
        } catch (Exception e) {
            LOG.debug("Undeploy failed (may not exist): " + normalized, e);
            return false;
        }
    }

    /**
     * Lists deployed applications on the remote Tomcat.
     *
     * @return the list response, or null on failure
     */
    @Nullable
    public String listDeployments() {
        try {
            String url = getManagerUrl() + TEXT_ENDPOINT + "/list";
            return executeGet(url);
        } catch (Exception e) {
            LOG.warn("Failed to list remote deployments", e);
            return null;
        }
    }

    /**
     * Tests connectivity to the remote Tomcat Manager.
     *
     * @return null if OK, or an error message describing the problem
     */
    @Nullable
    public String testConnection() {
        try {
            String url = getManagerUrl() + TEXT_ENDPOINT + "/list";
            HttpURLConnection conn = openConnection(url);
            try {
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();

                if (code == 401 || code == 403) {
                    return "Authentication failed (HTTP " + code + "). Check username/password and ensure the user has the 'manager-script' role.";
                }
                if (code == 404) {
                    return "Tomcat Manager not found (HTTP 404). Ensure the Manager app is deployed at the configured URL.";
                }

                String response = readResponse(conn);
                if (response == null) {
                    return "No response from Tomcat Manager (HTTP " + code + ")";
                }
                if (response.startsWith(OK_PREFIX)) {
                    return null; // success
                }

                // Detect HTML responses (non-Manager endpoint, e.g. another app on that port)
                if (response.contains("<html") || response.contains("<HTML") || response.contains("<!DOCTYPE")) {
                    return "The server at this address is not a Tomcat Manager (HTTP " + code
                            + "). Ensure the URL points to the Tomcat Manager app (usually /manager).";
                }

                return "Unexpected response (HTTP " + code + "): " + truncate(response, 150);
            } finally {
                conn.disconnect();
            }
        } catch (java.net.ConnectException e) {
            return "Connection refused. Is Tomcat running on the configured host and port?";
        } catch (java.net.UnknownHostException e) {
            return "Unknown host: " + e.getMessage() + ". Check the hostname.";
        } catch (java.net.SocketTimeoutException e) {
            return "Connection timed out. The server may be unreachable.";
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    // ======== Internal deployment methods ========

    /**
     * Deploys a WAR file by uploading it via HTTP PUT to the Manager API.
     */
    private DeployResult deployWarViaPut(@NotNull DeploymentArtifact artifact,
                                         @NotNull String contextPath,
                                         @Nullable TomcatDeploymentLogger logger,
                                         @Nullable ProgressIndicator indicator,
                                         @NotNull BooleanSupplier abortCheck) throws IOException {
        Path warFile = Path.of(artifact.getPath());
        if (!Files.exists(warFile)) {
            logError(logger, "WAR file not found: " + artifact.getPath());
            return DeployResult.FAILED;
        }

        long fileSize = Files.size(warFile);
        log(logger, "Uploading WAR (" + formatSize(fileSize) + ") via PUT...");
        if (indicator != null) {
            indicator.setText("Uploading " + artifact.getDisplayName());
            indicator.setIndeterminate(false);
            indicator.setFraction(0.0);
        }

        String url = getManagerUrl() + TEXT_ENDPOINT + "/deploy?path="
                + encodePathParam(contextPath) + "&update=true";
        HttpURLConnection conn = openConnection(url);
        try {
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(fileSize);

            try (OutputStream out = conn.getOutputStream();
                 InputStream in = Files.newInputStream(warFile)) {
                byte[] buffer = new byte[8192];
                long uploaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (indicator != null && indicator.isCanceled()) {
                        log(logger, "Upload cancelled by user");
                        return DeployResult.CANCELLED;
                    }
                    if (abortCheck.getAsBoolean()) {
                        // Local Tomcat process entered terminating/terminated state
                        // while the upload was in flight. Stop sending chunks so the
                        // task is not left holding a half-pushed WAR.
                        log(logger, "Upload aborted (local Tomcat process terminating)");
                        return DeployResult.CANCELLED;
                    }
                    out.write(buffer, 0, read);
                    uploaded += read;
                    if (indicator != null && fileSize > 0) {
                        indicator.setFraction((double) uploaded / fileSize);
                        indicator.setText2(formatSize(uploaded) + " / " + formatSize(fileSize));
                    }
                }
                out.flush();
            }

            if (indicator != null) {
                indicator.setText2("Waiting for server response...");
                indicator.setIndeterminate(true);
            }

            String response = readResponse(conn);
            boolean success = response != null && response.startsWith(OK_PREFIX);
            if (success) {
                log(logger, "Deployed WAR successfully: " + artifact.getDisplayName());
            } else {
                logError(logger, "Deploy failed: " + truncate(response, 300));
            }
            return success ? DeployResult.SUCCESS : DeployResult.FAILED;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Deploys an exploded directory using the {@code war=file:} parameter.
     * This requires the directory to be accessible from the Tomcat server's filesystem.
     */
    private boolean deployExplodedViaPath(@NotNull DeploymentArtifact artifact,
                                           @NotNull String contextPath,
                                           @Nullable TomcatDeploymentLogger logger) throws IOException {
        String docBase = artifact.getPath().replace('\\', '/');
        if (docBase.contains("..")) {
            throw new IOException("Deployment path must not contain '..': " + docBase);
        }
        String url = getManagerUrl() + TEXT_ENDPOINT + "/deploy?path=" + encodePathParam(contextPath)
                + "&war=" + encodeUrl("file:" + docBase) + "&update=true";

        log(logger, "Deploying exploded directory: " + docBase);

        String response = executeGet(url);
        boolean success = response != null && response.startsWith(OK_PREFIX);
        if (success) {
            log(logger, "Deployed exploded artifact successfully: " + artifact.getDisplayName());
        } else {
            logError(logger, "Deploy failed: " + truncate(response, 300));
        }
        return success;
    }

    // ======== HTTP helpers ========

    @NotNull
    private HttpURLConnection openConnection(@NotNull String urlStr) throws IOException {
        URI uri = URI.create(urlStr);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Unsupported URL scheme: " + scheme + " (only http/https allowed)");
        }
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);

        if (config.isUseCredentials()) {
            String credentials = config.getUsername() + ":" + config.getPassword();
            String encoded = Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }

        return conn;
    }

    @Nullable
    private String executeGet(@NotNull String urlStr) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        try {
            conn.setRequestMethod("GET");
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    @Nullable
    private static String readResponse(@NotNull HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return code + " (no body)";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
                if (sb.length() >= MAX_RESPONSE_CHARS) {
                    sb.append("\n... (response truncated)");
                    break;
                }
            }
            return sb.toString();
        }
    }

    // ======== Utility methods ========

    @NotNull
    private String getManagerUrl() {
        String url = config.getManagerUrl();
        // Strip trailing slash
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @NotNull
    private static String normalizeContextPath(@Nullable String contextPath) {
        return ContextPathUtils.normalizeContextPath(contextPath);
    }

    @NotNull
    private static String encodeUrl(@NotNull String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Encodes a context path for {@code path=} query params; preserves '/' which Tomcat Manager prefers raw. */
    @NotNull
    private static String encodePathParam(@NotNull String contextPath) {
        return java.net.URLEncoder.encode(contextPath, StandardCharsets.UTF_8)
                .replace("%2F", "/")
                .replace("%2f", "/");
    }

    @NotNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @NotNull
    private static String truncate(@Nullable String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void log(@Nullable TomcatDeploymentLogger logger, @NotNull String msg) {
        LOG.info("DevTomcat Remote: " + msg);
        if (logger != null) {
            logger.logServerInfo(msg);
        }
    }

    private static void logError(@Nullable TomcatDeploymentLogger logger, @NotNull String msg) {
        LOG.warn("DevTomcat Remote: " + msg);
        if (logger != null) {
            logger.logServerError(msg);
        }
    }
}
