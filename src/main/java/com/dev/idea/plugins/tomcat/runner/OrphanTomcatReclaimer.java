package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Kills orphan Tomcat processes left over from prior launches of a run
 * configuration before a new launch begins, so their ports free up before
 * port-conflict detection runs.
 *
 * <p>When the IDE exits without cleanly stopping a launch (crash, force-quit,
 * sandbox restart), the JVM keeps running and holds its HTTP/shutdown/JMX
 * ports. Without this reclaim, every subsequent launch bumps up to the next
 * free slot, and the user sees the dialog's seed permanently drift away from
 * the actually-bound port shown in Services.
 *
 * <h2>Identification</h2>
 * Orphans are matched by the {@code -Dcatalina.base=<this config's base>}
 * substring in the process command line. The base path is per-configuration
 * (derived from {@link TomcatProjectUtils#getCatalinaBase}), so nothing
 * else on the machine can legitimately match.
 *
 * <h2>Parallel-run children</h2>
 * Parallel-run launches use {@code <config-base>/.runs/<runId>/} as their
 * catalina.base. A reclaim against the parent config must catch those too.
 * The match logic permits the marker to be followed by a path separator
 * ({@code /} or {@code \}) in addition to whitespace or end-of-string.
 *
 * <h2>False-positive guard (fixed in this extraction)</h2>
 * Configs whose names share a prefix (e.g. {@code "MyApp"} and
 * {@code "MyApp2"}) used to collide because plain {@code contains} would
 * report {@code -Dcatalina.base=/path/MyApp} as a substring of
 * {@code -Dcatalina.base=/path/MyApp2}. {@link #matchesOrphanMarker} now
 * requires a boundary character (whitespace, path separator, or end of
 * string) immediately after the marker — so {@code MyApp2} is correctly
 * rejected when reclaiming {@code MyApp}. The
 * {@link OrphanTomcatReclaimerTest} fixture pins this contract.
 *
 * <h2>Termination strategy</h2>
 * Sends {@link ProcessHandle#destroy} first (Tomcat catches SIGTERM and
 * shuts down cleanly when its shutdown port is reachable). After a 1.5s
 * grace period, force-kills any survivors via {@link ProcessHandle#destroyForcibly}.
 * A short post-kill sleep gives the OS time to release the sockets so the
 * next port probe doesn't race {@code TIME_WAIT}.
 *
 * <p>Failures at any step are logged and swallowed — this is best-effort,
 * not a gate. A failure to scan processes or kill an orphan should not
 * block a launch.
 */
final class OrphanTomcatReclaimer {

    private static final Logger LOG = Logger.getInstance(OrphanTomcatReclaimer.class);

    private static final String MARKER_PREFIX = "-Dcatalina.base=";
    private static final long GRACE_PERIOD_MS = 1500;
    private static final long POST_KILL_SOCKET_RELEASE_MS = 200;

    private final TomcatRunConfiguration configuration;
    private final TomcatDeploymentLogger deploymentLogger;

    OrphanTomcatReclaimer(@NotNull TomcatRunConfiguration configuration,
                          @NotNull TomcatDeploymentLogger deploymentLogger) {
        this.configuration = configuration;
        this.deploymentLogger = deploymentLogger;
    }

    /**
     * Find and terminate all orphan Tomcat processes for this configuration.
     * Returns immediately (no-op) if the catalina.base can't be resolved or
     * no orphans are running.
     */
    void reclaim() {
        Path configBase = resolveConfigBase();
        if (configBase == null) return;

        String marker = buildCatalinaBaseMarker(configBase);
        long selfPid = ProcessHandle.current().pid();

        List<ProcessHandle> orphans = scanForOrphans(marker, selfPid);
        if (orphans.isEmpty()) return;

        LOG.warn("Reclaiming " + orphans.size() + " orphan Tomcat process(es) from prior launches"
                + " of '" + configuration.getName() + "' at base " + configBase);

        terminateOrphans(orphans);
    }

    /**
     * Resolve the stable per-configuration catalina.base. Passes
     * {@code runId=null} so we get the config-level base — that's the
     * prefix shared by both single-instance launches and the
     * {@code .runs/<runId>/} children of parallel-run launches.
     */
    @Nullable
    private Path resolveConfigBase() {
        try {
            return TomcatProjectUtils.getCatalinaBase(configuration, null);
        } catch (Exception e) {
            LOG.debug("Skipping orphan reclaim: catalina.base not resolvable yet", e);
            return null;
        }
    }

    /**
     * Build the JVM-property marker substring used to identify orphan
     * processes by their command line. Pure function — package-visible so
     * it can be unit-tested without standing up a runtime.
     */
    @NotNull
    static String buildCatalinaBaseMarker(@NotNull Path configBase) {
        return MARKER_PREFIX + configBase.toAbsolutePath().toString();
    }

    /**
     * Test whether a process command line belongs to an orphan of this
     * configuration. Matches when {@code marker} is found in
     * {@code commandLine} AND is immediately followed by a boundary
     * character: whitespace, path separator ({@code /} or {@code \}), or
     * end of string.
     *
     * <p>The boundary check is what prevents the false-positive collision
     * between similarly-named configs (e.g. {@code MyApp} matching
     * {@code MyApp2}'s command line).
     *
     * <p>Path-separator boundaries are required for parallel-run children
     * whose catalina.base is {@code <config-base>/.runs/<runId>/}. Both
     * Unix ({@code /}) and Windows ({@code \}) separators are accepted so
     * the predicate is correct on either platform regardless of which
     * style the JVM happened to render in the command line.
     */
    static boolean matchesOrphanMarker(@Nullable String commandLine, @NotNull String marker) {
        if (commandLine == null) return false;
        int idx = commandLine.indexOf(marker);
        if (idx < 0) return false;
        int end = idx + marker.length();
        if (end >= commandLine.length()) return true;
        char next = commandLine.charAt(end);
        return Character.isWhitespace(next) || next == '/' || next == '\\';
    }

    /**
     * Scan all OS processes for orphans matching the configuration's marker.
     * Excludes the IDE's own JVM by PID. Returns an empty list (not null)
     * on scan failure, with the failure logged as a warning — a broken
     * scan must not block the launch.
     */
    @NotNull
    private List<ProcessHandle> scanForOrphans(@NotNull String marker, long selfPid) {
        try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
            return all
                    .filter(ProcessHandle::isAlive)
                    .filter(p -> p.pid() != selfPid)
                    .filter(p -> p.info().commandLine()
                            .map(cl -> matchesOrphanMarker(cl, marker))
                            .orElse(false))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warn("Orphan scan failed; continuing without reclaim", e);
            return List.of();
        }
    }

    /**
     * Three-stage termination: polite SIGTERM, grace-period await, then
     * force-kill any survivors. Most orphans don't have a working shutdown
     * port (that's exactly why they're orphans), so the polite step rarely
     * succeeds — but try it anyway so we don't SIGKILL a clean-shutdown-
     * capable JVM unnecessarily.
     */
    private void terminateOrphans(@NotNull List<ProcessHandle> orphans) {
        // Stage 1: polite SIGTERM.
        for (ProcessHandle p : orphans) {
            try { p.destroy(); } catch (Exception ignored) { /* try next */ }
        }

        // Stage 2: grace period — wait for clean exits.
        long graceDeadlineMs = System.currentTimeMillis() + GRACE_PERIOD_MS;
        for (ProcessHandle p : orphans) {
            long remaining = graceDeadlineMs - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                p.onExit().get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Still alive after grace period — will be force-killed below.
            }
        }

        // Stage 3: force-kill survivors.
        List<Long> forceKilled = new ArrayList<>();
        for (ProcessHandle p : orphans) {
            if (!p.isAlive()) continue;
            try {
                if (p.destroyForcibly()) {
                    forceKilled.add(p.pid());
                }
            } catch (Exception e) {
                LOG.debug("destroyForcibly failed for pid=" + p.pid(), e);
            }
        }

        // Brief pause so the OS releases the sockets before the next port
        // probe runs. The SO_REUSEADDR probe in PortUtils.tryBind makes
        // TIME_WAIT transparent, so 200ms is enough.
        try {
            Thread.sleep(POST_KILL_SOCKET_RELEASE_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        deploymentLogger.logServerWarning("Reclaimed " + orphans.size()
                + " orphan Tomcat process(es) from prior launches of this configuration"
                + (forceKilled.isEmpty() ? "" : " (force-killed: " + forceKilled + ")"));
    }
}
