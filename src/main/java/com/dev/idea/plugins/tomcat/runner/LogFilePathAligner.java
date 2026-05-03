package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.conf.TomcatRunConfiguration;
import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.execution.configurations.LogFileOptions;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Aligns plugin-managed {@link LogFileOptions} on a {@link TomcatRunConfiguration}
 * with the catalina.base directory the current launch will actually use, so the
 * IDE's {@code RunContentBuilder} creates Log tabs that point at files Tomcat
 * is writing to.
 *
 * <h2>Why this is needed</h2>
 * {@link TomcatRunConfiguration#getAllLogFiles()} normally aligns paths to the
 * config-level {@code logs/} directory. That's correct for single-instance
 * launches, but in <b>parallel-run mode</b> each launch lives under
 * {@code <config>/.runs/<runId>/} and writes to {@code <config>/.runs/<runId>/logs/}
 * — pointing the tabs at the shared {@code <config>/logs/} dir means they never
 * receive any output and silently disappear from Services.
 *
 * <p>This aligner is the per-launch override: it rewrites the path of every
 * standard Tomcat log entry to the runtime base actually in use for this launch.
 *
 * <h2>What stays untouched</h2>
 * User-customised paths are preserved verbatim. Only entries whose stored path
 * matches the plugin-managed shape (a known standard filename pattern) get
 * rewritten — see {@link #matchesStandardFilename}.
 *
 * <h2>Why filename-based identification</h2>
 * The earlier directory-prefix test was broken: a {@link LogFileOptions} that
 * was persisted with a different IDE instance's system path (sandbox vs real
 * IDE) would slip past the prefix check and keep pointing at a non-existent
 * file forever — breaking Log tabs for any config that moved between sandbox
 * and production installs. Identifying by filename pattern is robust against
 * directory drift.
 */
final class LogFilePathAligner {

    private static final Logger LOG = Logger.getInstance(LogFilePathAligner.class);

    private final TomcatRunConfiguration configuration;

    LogFilePathAligner(@NotNull TomcatRunConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Realign all standard log entries on the configuration to the runtime
     * logs directory ({@code <config>/.runs/<runId>/logs/} for parallel mode,
     * {@code <config>/logs/} for single-instance). No-op when the runtime
     * logs directory cannot be resolved (catalina.base not yet established).
     *
     * @param runId the per-launch ID for parallel-run mode, or {@code null}
     *              for single-instance.
     */
    void align(@Nullable String runId) {
        Path runtimeLogsDir = TomcatProjectUtils.getLogsDirectory(configuration, runId);
        if (runtimeLogsDir == null) return;

        Map<String, TomcatLogFile> byId = indexStandardLogFilesById();

        for (LogFileOptions opt : configuration.getAllLogFiles()) {
            TomcatLogFile lf = byId.get(opt.getName());
            if (lf == null) continue; // unknown or user-added entry
            String currentPath = opt.getPathPattern();
            if (!matchesStandardFilename(currentPath, lf)) {
                continue; // user-customised filename — leave it alone
            }
            String aligned = lf.resolveFullPath(runtimeLogsDir);
            if (!aligned.equals(currentPath)) {
                opt.setPathPattern(aligned);
                LOG.debug("Aligned log path for '" + opt.getName()
                        + "': " + currentPath + " -> " + aligned);
            }
        }
    }

    @NotNull
    private static Map<String, TomcatLogFile> indexStandardLogFilesById() {
        Map<String, TomcatLogFile> byId = new HashMap<>();
        for (TomcatLogFile lf : TomcatLogFile.getStandardLogFiles()) {
            byId.put(lf.getId(), lf);
        }
        return byId;
    }

    /**
     * Test whether {@code currentPath} is a plugin-managed standard log entry
     * that should have its directory rewritten by {@link #align}, identified
     * by matching its <em>filename</em> against the log's pattern (so the
     * directory portion can drift freely without losing track of which entry
     * is which).
     *
     * <p>The filename is the substring after the last platform path separator;
     * if there's no separator the whole string is treated as a filename.
     *
     * <p>Pattern matching:
     * <ul>
     *   <li>Pattern with no {@code *} wildcard: exact-match.</li>
     *   <li>Pattern with one {@code *} (e.g. {@code catalina.*.log}): the
     *       filename must start with the prefix, end with the suffix, and
     *       be at least long enough to contain both — the wildcard
     *       represents at least zero characters.</li>
     * </ul>
     *
     * <p>A {@code null} or empty {@code currentPath} returns {@code true}
     * — fresh entries with no path yet still get aligned. Pinned by tests
     * so the empty-path branch isn't accidentally inverted.
     */
    static boolean matchesStandardFilename(@Nullable String currentPath, @NotNull TomcatLogFile lf) {
        if (currentPath == null || currentPath.isEmpty()) return true;
        int lastSep = currentPath.lastIndexOf(File.separator);
        String filename = lastSep < 0 ? currentPath : currentPath.substring(lastSep + 1);
        String pattern = lf.getFilenamePattern();
        int wildcardIdx = pattern.indexOf('*');
        if (wildcardIdx < 0) return filename.equals(pattern);
        String prefix = pattern.substring(0, wildcardIdx);
        String suffix = pattern.substring(wildcardIdx + 1);
        return filename.length() >= prefix.length() + suffix.length()
                && filename.startsWith(prefix)
                && filename.endsWith(suffix);
    }
}
