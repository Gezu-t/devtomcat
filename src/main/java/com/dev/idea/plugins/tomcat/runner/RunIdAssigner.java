package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Assigns and remembers the per-launch identifier used to isolate the
 * CATALINA_BASE for parallel-run launches.
 *
 * <h2>Two outcomes</h2>
 * <ul>
 *   <li><b>Effective parallel-run</b> ({@code Allow parallel run} on AND no
 *       pinned CATALINA_BASE): {@link #resolve} returns a stable
 *       {@code "run-<base36-id>"} string used to derive the per-launch base
 *       directory.</li>
 *   <li><b>Single-instance</b> ({@code Allow parallel run} off, OR pinned
 *       base disables isolation): {@link #resolve} returns {@code null}.</li>
 * </ul>
 *
 * <h2>Pinned-base guard</h2>
 * {@link TomcatProjectUtils#getCatalinaBase} deliberately returns the user's
 * pinned directory regardless of {@code runId}, so assigning a runId in
 * pinned mode would cause the per-run cleanup in {@link TomcatProcessHandler}
 * to walk and delete the pinned directory on process exit — data loss.
 * The pinned-base path is detected via
 * {@link TomcatRunConfiguration#isParallelRunEffective()} and short-circuits
 * id assignment.
 *
 * <h2>Idempotence + warning-once</h2>
 * The launcher calls {@link #resolve} multiple times per launch (from
 * {@code createJavaParameters} and {@code startProcess}); subsequent calls
 * must return the same id and must not emit duplicate warnings.
 * {@link AtomicBoolean} flags guard both side effects.
 *
 * <h2>Id format</h2>
 * Derived from {@link ExecutionEnvironment#getExecutionId()} —
 * unique per launch within the IDE session and stable across the lifetime
 * of this assigner. Encoded as base-36 with a {@code run-} prefix:
 * <ul>
 *   <li>The prefix makes the directory easy to identify on disk and
 *       guarantees no collision with legitimate config names.</li>
 *   <li>{@link Long#toUnsignedString(long, int)} avoids a leading dash on
 *       pathological negative IDs.</li>
 * </ul>
 */
final class RunIdAssigner {

    private static final Logger LOG = Logger.getInstance(RunIdAssigner.class);
    private static final String RUN_ID_PREFIX = "run-";
    private static final int BASE_36 = 36;

    private final TomcatRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private final TomcatDeploymentLogger deploymentLogger;

    @Nullable private volatile String runId;
    private final AtomicBoolean pinnedWarningEmitted = new AtomicBoolean(false);
    private final AtomicBoolean parallelInfoEmitted = new AtomicBoolean(false);

    RunIdAssigner(@NotNull TomcatRunConfiguration configuration,
                  @NotNull ExecutionEnvironment environment,
                  @NotNull TomcatDeploymentLogger deploymentLogger) {
        this.configuration = configuration;
        this.environment = environment;
        this.deploymentLogger = deploymentLogger;
    }

    /**
     * Resolve the per-launch ID. Idempotent: subsequent calls return the
     * same value (or both return {@code null} for single-instance).
     *
     * @return the per-launch id (parallel mode), or {@code null} (single-instance)
     */
    @Nullable
    String resolve() {
        if (!configuration.isParallelRunEffective()) {
            warnIfCheckboxOnButPinned();
            return null;
        }
        String current = runId;
        if (current != null) return current;

        synchronized (this) {
            current = runId;
            if (current != null) return current;
            long executionId = environment.getExecutionId();
            current = formatRunId(executionId);
            runId = current;
        }
        emitParallelRunInfoOnce(current);
        return current;
    }

    /**
     * Format an execution id as the on-disk run identifier. Pure function;
     * package-visible so tests can pin the encoding without standing up
     * an {@link ExecutionEnvironment}.
     *
     * <p>Uses {@link Long#toUnsignedString} so {@link Long#MIN_VALUE} and
     * other negative ids encode to a positive-looking suffix. Without this
     * the directory name could begin with {@code -}, which on some shells
     * is parsed as a flag and breaks downstream tooling.
     */
    @NotNull
    static String formatRunId(long executionId) {
        return RUN_ID_PREFIX + Long.toUnsignedString(executionId, BASE_36);
    }

    /**
     * Emit a single warning when the user has the parallel-run checkbox on
     * but a pinned CATALINA_BASE disables isolation. Without the warning
     * the user sees rerun behaviour that contradicts the checkbox state
     * with no explanation.
     */
    private void warnIfCheckboxOnButPinned() {
        if (!configuration.isAllowMultipleInstances()) return;
        if (!pinnedWarningEmitted.compareAndSet(false, true)) return;

        String pinned = configuration.getConfigData().getCatalinaBase();
        LOG.warn("Allow parallel run: CATALINA_BASE is pinned to '" + pinned
                + "' — parallel isolation disabled, using single-instance semantics "
                + "(Update dialog on rerun) to prevent shared-directory collisions.");
        deploymentLogger.logServerWarning(
                "Allow parallel run is ignored because CATALINA_BASE is pinned. "
                        + "Unset the pinned base to enable per-run isolation.");
    }

    /**
     * Emit a single info-level message announcing the per-launch isolation
     * directory the first time we assign a runId. Duplicate emissions on
     * subsequent {@link #resolve} calls are suppressed.
     */
    private void emitParallelRunInfoOnce(@NotNull String current) {
        if (!parallelInfoEmitted.compareAndSet(false, true)) return;
        deploymentLogger.logServerInfo(
                "Parallel run active: isolated CATALINA_BASE under .runs/" + current
                        + "/. Log tabs for this launch point at the isolated base's logs/ subfolder.");
    }
}
