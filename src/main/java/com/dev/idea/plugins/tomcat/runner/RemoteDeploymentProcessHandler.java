package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.remote.RemoteConfig;
import com.dev.idea.plugins.tomcat.utils.CredentialResolver;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process handler for remote-mode Tomcat run configurations.
 *
 * <p>Replaces the previous dual-Tomcat behaviour where a remote-mode launch
 * <i>also</i> forked a local Tomcat JVM that did nothing useful. The local
 * JVM is gone — this handler runs only the Tomcat Manager API deploy on a
 * background pooled thread, writes progress to its own console, and reports
 * termination when the deploy completes.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #startNotify()} kicks off a background deploy task. The
 *       handler stays in the "running" state for the duration of the deploy.</li>
 *   <li>The deploy task calls {@link TomcatManagerDeployer#testConnection()}
 *       first; on connectivity failure the task aborts and the handler
 *       terminates with exit code 1.</li>
 *   <li>Each artifact is deployed in turn via
 *       {@link TomcatManagerDeployer#deployWithProgress}. The handler's
 *       termination flag is threaded as the abort predicate so a user-clicked
 *       Stop interrupts the chunk-by-chunk WAR upload mid-flight, not just
 *       between artifacts.</li>
 *   <li>When the deploy finishes (success, failure, or cancellation), the
 *       handler calls {@code notifyProcessTerminated} with an exit code that
 *       reflects the outcome (0 = all artifacts deployed; non-zero = at
 *       least one failure, or the run was cancelled/stopped).</li>
 * </ul>
 *
 * <h2>Why this is a {@link ProcessHandler} and not an
 * {@code OSProcessHandler}</h2>
 * There is no OS process to wrap — the work is a sequence of HTTP requests
 * made from this JVM. Extending the abstract base class lets the IDE's
 * console / Services panel / stop button work normally without faking a
 * process. The trade-off is that {@link #getProcessInput()} returns
 * {@code null} (no stdin) and the handler emits text via
 * {@link #notifyTextAvailable} rather than reading from a real stream.
 *
 * <h2>Lifecycle listener integration</h2>
 * The handler dispatches the same artifact-level events as the local-mode
 * pipeline ({@code onArtifactDeploying} / {@code onArtifactDeployed} /
 * {@code onArtifactFailed} / {@code onArtifactCancelled}) so the Services
 * panel renders per-artifact status the same way it does for local runs.
 */
public final class RemoteDeploymentProcessHandler extends ProcessHandler {

    private static final Logger LOG = Logger.getInstance(RemoteDeploymentProcessHandler.class);

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;
    private final TomcatLifecycleListener lifecycleListener;
    private final String configurationName;

    /** Flips to {@code true} on the first Stop / abort signal; the upload loop polls this. */
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    /** Flips to {@code true} the moment the deploy task body starts running. */
    private final AtomicBoolean taskEntered = new AtomicBoolean(false);

    /** Future for the deploy task — cancelled on destroyProcessImpl(). */
    private volatile Future<?> deployFuture;

    public RemoteDeploymentProcessHandler(@NotNull TomcatRunConfiguration configuration,
                                          @NotNull Project project,
                                          @NotNull TomcatDeploymentLogger deploymentLogger) {
        this.configuration = configuration;
        this.deploymentLogger = deploymentLogger;
        this.configurationName = configuration.getName();
        // The lifecycle listener fans events out to the Services panel, deployment
        // history, and startup-time tracker — same chain the local-mode handler
        // uses, just driven from a different source.
        this.lifecycleListener = TomcatLifecycleListener.forConfiguration(
                configuration,
                // PipelineLogger is local-mode-specific (catalina output filtering).
                // For remote we don't have a pipeline, so a no-op is appropriate.
                noOpPipelineLogger());
    }

    @Override
    public void startNotify() {
        super.startNotify();
        notifyTextAvailable("DevTomcat: Remote deployment starting...\n", ProcessOutputTypes.SYSTEM);

        deployFuture = ApplicationManager.getApplication().executeOnPooledThread(this::runDeployTask);
    }

    /** The main deploy work; runs on a pooled background thread. */
    private void runDeployTask() {
        taskEntered.set(true);
        int exitCode = 0;
        try {
            RemoteConfig remoteConfig = configuration.getConfigData().getRemoteConfig();
            // Defence in depth: RemoteDeploymentRunProfileState already validated
            // this, but a clear console message beats an opaque NPE if the model
            // shape ever changes underneath us.
            if (remoteConfig == null || !remoteConfig.isValid()) {
                writeError("Remote configuration is not valid; aborting deployment.");
                exitCode = 1;
                return;
            }
            // Credentials must be resolved before the first HTTP call — the
            // PasswordSafe lookup is async otherwise and would race the deploy.
            // This runs on the pooled thread (not the EDT); a no-op if the
            // config was already resolved by the deserializer's async task.
            CredentialResolver.ensureResolved(remoteConfig);

            // Fail-fast missing-password gate. Moved off the launch (EDT) path
            // — see RemoteDeploymentRunProfileState.execute — so the blocking
            // PasswordSafe lookup runs here on the pooled thread. Do this before
            // any HTTP call so we don't push a WAR only to get a 401.
            if (remoteConfig.isUseCredentials() && remoteConfig.getPassword().isEmpty()) {
                writeError("Remote deployment requires credentials but no password was found."
                        + " Configure credentials in the run configuration's Server tab"
                        + " (or store them in PasswordSafe).");
                exitCode = 1;
                return;
            }

            String managerUrl = remoteConfig.getManagerUrl();
            writeInfo("Target: " + managerUrl);
            warnIfCredentialsOverPlainHttp(remoteConfig, managerUrl);

            TomcatManagerDeployer deployer = new TomcatManagerDeployer(remoteConfig);

            // 1. Connectivity probe — give a clear, fast error before pushing WARs
            //    across the network only to find out the Manager URL is wrong.
            writeInfo("Testing connection to Tomcat Manager...");
            String error = deployer.testConnection();
            if (abortRequested.get()) {
                writeInfo("Deployment cancelled before any artifact was sent.");
                exitCode = 1;
                return;
            }
            if (error != null) {
                writeError("Connection failed: " + error);
                exitCode = 1;
                return;
            }
            writeInfo("Connection OK.");

            // 2. Per-deployment deploy. Skip structurally-invalid entries rather
            //    than aborting the whole task — RunConfigurationValidator already
            //    gates structurally-invalid configs at Apply time, so an invalid
            //    entry here usually means the file got deleted between save and
            //    launch, which is recoverable per-deployment.
            List<Deployment> deployments = configuration.getDeployments().stream()
                    .filter(Deployment::isValid)
                    .toList();
            if (deployments.isEmpty()) {
                writeWarning("No valid artifacts configured. Nothing to deploy.");
                return;
            }

            int success = 0;
            int failed = 0;
            int total = deployments.size();
            for (int i = 0; i < total; i++) {
                if (abortRequested.get()) {
                    writeWarning("Deployment cancelled. " + success + "/" + total + " artifact(s) succeeded before stop.");
                    exitCode = 1;
                    return;
                }
                Deployment deployment = deployments.get(i);
                String artifactName = deployment.getDisplayName();
                writeInfo("[" + (i + 1) + "/" + total + "] Deploying '" + artifactName
                        + "' to " + deployment.getContextPath() + "...");
                lifecycleListener.onArtifactDeploying(configurationName, artifactName);

                // No ProgressIndicator — we surface progress directly to the
                // console via the deployer's logger calls. The BooleanSupplier
                // abort check polls our termination flag so a user Stop
                // interrupts mid-upload, not just between artifacts.
                TomcatManagerDeployer.DeployResult result =
                        deployer.deployWithProgress(deployment, deploymentLogger, null, abortRequested::get);

                switch (result) {
                    case SUCCESS -> {
                        success++;
                        lifecycleListener.onArtifactDeployed(configurationName, artifactName);
                    }
                    case FAILED -> {
                        failed++;
                        lifecycleListener.onArtifactFailed(configurationName, artifactName);
                    }
                    case CANCELLED -> {
                        lifecycleListener.onArtifactCancelled(configurationName, artifactName);
                        writeWarning("Deployment cancelled mid-artifact: " + artifactName);
                        exitCode = 1;
                        return;
                    }
                }
            }

            writeInfo("Remote deployment complete: " + success + "/" + total + " artifact(s) deployed"
                    + (failed > 0 ? " (" + failed + " failed)" : ""));
            exitCode = failed > 0 ? 1 : 0;

        } catch (Throwable t) {
            LOG.warn("Remote deployment task failed for " + configurationName, t);
            writeError("Deployment task failed: " + t.getMessage());
            exitCode = 1;
        } finally {
            // Always terminate — for a finite deploy task, "running forever" would
            // confuse the Services panel and prevent the user from re-running the
            // config until they manually clicked Stop.
            notifyProcessTerminated(exitCode);
            // Mirror TomcatProcessHandler.processTerminated: dispose the logger once
            // the deploy task ends so its ConsoleView reference is released and any
            // late relay call hits the disposed guard instead of a torn-down console.
            // Idempotent, so a double dispose is harmless.
            deploymentLogger.dispose();
        }
    }

    @Override
    protected void destroyProcessImpl() {
        abortRequested.set(true);
        Future<?> f = deployFuture;
        if (f != null) {
            f.cancel(true);
        }
        // Normally the deploy loop sees the abortRequested flip on its next poll
        // and runDeployTask's finally block owns termination — so we don't
        // double-emit here. The one exception: if the pooled task was cancelled
        // before its body ever started (f.cancel on a not-yet-run task), the
        // finally block never runs and the handler would hang in TERMINATING
        // forever. Self-terminate in that case. If the task is entering
        // concurrently, notifyProcessTerminated coalesces via its
        // TERMINATING->TERMINATED CAS and deploymentLogger.dispose() is
        // idempotent, so the racing double call is harmless.
        if (!taskEntered.get()) {
            notifyProcessTerminated(1);
            deploymentLogger.dispose();
        }
    }

    @Override
    protected void detachProcessImpl() {
        // Detach means "close my console but let the work continue." For a
        // remote deploy that's reasonable — the user might want to send the
        // WAR and walk away. Treat detach as a process-end signal to the IDE
        // but leave the task running on the pooled thread.
        notifyProcessDetached();
    }

    @Override
    public boolean detachIsDefault() {
        // Stop means stop — don't silently leave a deploy running in the
        // background when the user clicked the red square.
        return false;
    }

    @Override
    public @Nullable OutputStream getProcessInput() {
        // There is no underlying OS process; the deploy is HTTP calls from
        // this JVM. The IDE never tries to write to stdin if this is null.
        return null;
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    // These helpers emit only through the process stream (notifyTextAvailable),
    // which the attached ConsoleView prints. They deliberately do NOT also route
    // through deploymentLogger: the same ConsoleView is set on the logger
    // (RemoteDeploymentRunProfileState.execute), so a logServerX call would print
    // every line a second time with a timestamp/[INFO] prefix. idea.log
    // diagnostics come from the direct LOG.* calls below.
    private void writeInfo(@NotNull String msg) {
        notifyTextAvailable(msg + "\n", ProcessOutputTypes.STDOUT);
        LOG.info(msg);
    }

    private void writeWarning(@NotNull String msg) {
        notifyTextAvailable(msg + "\n", ProcessOutputTypes.STDOUT);
        LOG.warn(msg);
    }

    private void writeError(@NotNull String msg) {
        notifyTextAvailable(msg + "\n", ProcessOutputTypes.STDERR);
        LOG.warn(msg);
    }

    /**
     * Emits a one-time warning when Manager credentials will travel over an
     * unencrypted {@code http://} connection to a non-loopback host. Base64
     * Basic auth is not encryption, so on a remote host the credentials cross
     * the network in cleartext. Loopback targets are exempt (nothing leaves the
     * machine). This warns only — it never blocks the deploy.
     */
    private void warnIfCredentialsOverPlainHttp(@NotNull RemoteConfig remoteConfig, @NotNull String managerUrl) {
        if (!remoteConfig.isUseCredentials()) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(managerUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme)) {
                return; // https (or unexpected scheme) — no cleartext exposure
            }
            String host = uri.getHost();
            if (host == null) {
                return;
            }
            boolean loopback = host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.equals("[::1]");
            if (loopback) {
                return; // never leaves the machine
            }
            writeWarning("Warning: sending Manager credentials over unencrypted HTTP to " + host
                    + "; enable 'Use HTTPS' in the Server tab if the server supports it.");
        } catch (IllegalArgumentException malformed) {
            // Unparseable URL — the deploy itself will fail with a clearer error;
            // nothing useful to warn about here.
        }
    }

    /**
     * A no-op {@link TomcatOutputPipeline.PipelineLogger} for the lifecycle
     * listener composite. The remote-deploy flow doesn't go through the
     * Catalina output pipeline (there's no Catalina output — the local JVM
     * is gone), so the logger's catalina-specific hooks are inert here. The
     * five abstract methods have to be implemented explicitly because the
     * interface marks them abstract (intentionally — pipeline analyzers
     * rely on these calls in the local-mode path).
     */
    private static @NotNull TomcatOutputPipeline.PipelineLogger noOpPipelineLogger() {
        return new TomcatOutputPipeline.PipelineLogger() {
            @Override public void logServerStartup(long durationMs) {}
            @Override public void logDeploymentSuccess(@NotNull String artifactName, long durationMs) {}
            @Override public void logServerInfo(@NotNull String message) {}
            @Override public void logServerError(@NotNull String message) {}
            @Override public void logServerWarning(@NotNull String message) {}
        };
    }
}
