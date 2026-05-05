package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Extends the {@code jarsToSkip} list in
 * {@code CATALINA_BASE/conf/catalina.properties} with additional JAR names so
 * Tomcat's annotation scanner skips the listed JARs at startup.
 *
 * <h2>Why this channel exists</h2>
 * The per-context {@code <JarScanFilter pluggabilitySkip="...">} element
 * works correctly on Tomcat 8.5+ but is silently rejected by Tomcat 7's
 * {@link org.apache.catalina.startup.ContextRuleSet} (the rule for
 * {@code Context/JarScanner/JarScanFilter} was added in 8.5). For every
 * affected version we therefore route skip-list extensions through
 * {@code catalina.properties}, which is loaded into {@code System}
 * properties at JVM startup and is honoured uniformly across versions.
 *
 * <h2>What the injector does</h2>
 * <ol>
 *   <li>Reads {@code catalina.properties} via {@link Properties#load} so
 *       multi-line {@code \}-continuation values are merged correctly.</li>
 *   <li>Auto-detects the right skip-property name. Tomcat 7 / 8.0.x use
 *       {@code tomcat.util.scan.DefaultJarScanner.jarsToSkip}; 8.5+ uses
 *       {@code tomcat.util.scan.StandardJarScanFilter.jarsToSkip}. The
 *       FIRST entry in {@link #SKIP_PROPERTY_NAMES} that the file already
 *       defines wins; if neither is defined, both are written.</li>
 *   <li>Computes the union of the existing comma-separated entries and
 *       the supplied JAR names, deduplicating, preserving order.</li>
 *   <li>Replaces a previous DevTomcat-managed appendix block in place
 *       (delimited by {@link #APPENDIX_MARKER} /
 *       {@link #APPENDIX_END_MARKER}) or appends a fresh one. Atomic
 *       file write via {@link TomcatProjectUtils#atomicWriteString}.</li>
 * </ol>
 *
 * <p>The previous DevTomcat-managed appendix could come from any prior
 * launch (this release or earlier) — we always replace in place so a
 * pinned {@code CATALINA_BASE} does not accumulate appendix blocks
 * across rebuild cycles.
 *
 * <p>Never throws. Failures (missing file, parse errors, write errors)
 * are logged at warn or debug and the method returns the appropriate
 * {@link Outcome} so callers can decide what to surface.
 *
 * @author Gezahegn Lemma (Gezu)
 */
final class JarSkipListInjector {

    private static final Logger LOG = Logger.getInstance(JarSkipListInjector.class);

    /**
     * Skip-property names Tomcat reads from {@code catalina.properties} (and
     * {@link System#getProperty}). Order matters: the FIRST name that
     * already exists in the install's {@code catalina.properties} is the one
     * whose value we extend.
     */
    static final String[] SKIP_PROPERTY_NAMES = {
            "tomcat.util.scan.StandardJarScanFilter.jarsToSkip",
            "tomcat.util.scan.DefaultJarScanner.jarsToSkip"
    };

    /**
     * Begin marker for the auto-generated appendix block. Subsequent runs
     * find this exact line and replace the block in place rather than
     * stacking duplicates. Stable across releases so an upgrade does not
     * leave a stranded older-marker block beside a newer one.
     */
    static final String APPENDIX_MARKER =
            "# DevTomcat: JAR scan compatibility appendix (auto-generated)";

    /** End marker, paired with {@link #APPENDIX_MARKER}. */
    static final String APPENDIX_END_MARKER =
            "# DevTomcat: end of JAR scan compatibility appendix";

    /**
     * Legacy markers from earlier releases (1.0.9 used a BCEL-specific
     * label). Recognised on read so an upgrade replaces the old block in
     * place, never appends a second appendix beside the legacy one.
     */
    private static final String[][] LEGACY_MARKER_PAIRS = {
            { "# DevTomcat: BCEL/module-info compatibility appendix (auto-generated)",
              "# DevTomcat: end of BCEL/module-info appendix" }
    };

    private JarSkipListInjector() {}

    /**
     * Outcome of a single {@link #applyToCatalinaProperties} call. Callers
     * use this to drive run-console messaging.
     */
    enum Outcome {
        /** Caller passed an empty JAR list. */
        NO_JARS,
        /** Properties file is missing or unreadable; no change made. */
        PROPERTIES_FILE_UNAVAILABLE,
        /** Every supplied JAR is already covered by an existing skip entry. */
        ALREADY_COVERED,
        /** Skip list extended; Tomcat will now scan past the listed JARs. */
        APPENDED,
        /** A previous DevTomcat-managed appendix was refreshed in place. */
        REFRESHED
    }

    /**
     * Extends the {@code jarsToSkip} list in
     * {@code CATALINA_BASE/conf/catalina.properties} with the given JARs.
     *
     * @param catalinaBase    the per-launch {@code CATALINA_BASE}
     * @param jarsToSkip      JAR file names to add (e.g. {@code "jackson-core-2.17.0.jar"})
     * @param reasonHeader    short human-readable explanation rendered in
     *                        the appendix's leading comment block; describes
     *                        why the skip is being applied so a user
     *                        inspecting the file later knows what owns it
     * @param logger          optional run-console logger; receives a single
     *                        info line summarising the injection
     */
    @NotNull
    static Outcome applyToCatalinaProperties(@NotNull Path catalinaBase,
                                             @NotNull List<String> jarsToSkip,
                                             @NotNull String reasonHeader,
                                             @Nullable TomcatDeploymentLogger logger) {
        if (jarsToSkip.isEmpty()) {
            return Outcome.NO_JARS;
        }
        Path props = catalinaBase.resolve("conf").resolve("catalina.properties");
        if (!Files.isRegularFile(props)) {
            LOG.debug("catalina.properties not found at " + props
                    + "; skipping JAR-scan skip injection");
            return Outcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        Properties existing = new Properties();
        try (InputStream is = Files.newInputStream(props)) {
            existing.load(is);
        } catch (IOException e) {
            LOG.warn("Could not read catalina.properties for JAR-scan skip injection: "
                    + e.getMessage());
            return Outcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        // Pick whichever skip property the install already uses; fall back
        // to writing both names if neither is defined (defensive).
        List<String> targetProperties = new ArrayList<>();
        for (String name : SKIP_PROPERTY_NAMES) {
            if (existing.containsKey(name)) {
                targetProperties.add(name);
            }
        }
        if (targetProperties.isEmpty()) {
            targetProperties = new ArrayList<>(Arrays.asList(SKIP_PROPERTY_NAMES));
        }

        // Build the merged value for each target. If every JAR is already
        // present the call is idempotent and returns ALREADY_COVERED.
        List<String> overrideLines = new ArrayList<>();
        boolean anyChange = false;
        for (String propName : targetProperties) {
            String currentValue = existing.getProperty(propName, "");
            Set<String> entries = new LinkedHashSet<>();
            for (String e : currentValue.split(",")) {
                String trimmed = e.trim();
                if (!trimmed.isEmpty()) entries.add(trimmed);
            }
            int before = entries.size();
            entries.addAll(jarsToSkip);
            if (entries.size() == before) continue;
            overrideLines.add(propName + "=" + String.join(",", entries));
            anyChange = true;
        }
        if (!anyChange) {
            return Outcome.ALREADY_COVERED;
        }

        String original;
        try {
            original = Files.readString(props);
        } catch (IOException e) {
            LOG.warn("Could not read catalina.properties contents: " + e.getMessage());
            return Outcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        boolean hadCurrent = original.contains(APPENDIX_MARKER);
        boolean hadLegacy = false;
        for (String[] pair : LEGACY_MARKER_PAIRS) {
            if (original.contains(pair[0])) {
                hadLegacy = true;
                break;
            }
        }
        String appendix = buildAppendix(reasonHeader, overrideLines);
        String updated = replaceOrAppendAppendix(original, appendix);
        if (updated.equals(original)) {
            return Outcome.ALREADY_COVERED;
        }

        try {
            TomcatProjectUtils.atomicWriteString(props, updated);
        } catch (IOException e) {
            LOG.warn("Could not write catalina.properties for JAR-scan skip injection: "
                    + e.getMessage(), e);
            return Outcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        Outcome outcome = (hadCurrent || hadLegacy) ? Outcome.REFRESHED : Outcome.APPENDED;
        if (logger != null) {
            logger.logServerInfo("JAR-scan compatibility ("
                    + (outcome == Outcome.REFRESHED ? "refreshed" : "appended")
                    + " in catalina.properties): "
                    + jarsToSkip.size() + " JAR(s) added to "
                    + String.join(", ", targetProperties)
                    + ". " + reasonHeader);
        }
        LOG.info("JAR-scan skip injection " + outcome + ": "
                + jarsToSkip + " into " + targetProperties);
        return outcome;
    }

    /** Builds the auto-generated appendix block, including begin/end markers. */
    @NotNull
    private static String buildAppendix(@NotNull String reasonHeader,
                                        @NotNull List<String> overrideLines) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(APPENDIX_MARKER).append('\n');
        // Reason header may be multi-line; prefix each line with '#' so
        // the entire block is valid catalina.properties syntax.
        for (String line : reasonHeader.split("\n", -1)) {
            sb.append("# ").append(line).append('\n');
        }
        sb.append("# Properties.load applies later-wins semantics, so the override\n");
        sb.append("# below supersedes the default skip list above.\n");
        for (String line : overrideLines) {
            sb.append(line).append('\n');
        }
        sb.append(APPENDIX_END_MARKER).append('\n');
        return sb.toString();
    }

    /**
     * If {@code original} contains a previous DevTomcat-managed appendix
     * (current marker or any legacy marker pair), replaces it with
     * {@code newAppendix}. Otherwise appends {@code newAppendix} at the end
     * of the content (preserving a trailing newline). The marker-based
     * replace prevents the file from growing across launch cycles.
     */
    @NotNull
    static String replaceOrAppendAppendix(@NotNull String original,
                                          @NotNull String newAppendix) {
        // Try current marker first; fall back to legacy markers in order.
        Range range = findMarkerRange(original, APPENDIX_MARKER, APPENDIX_END_MARKER);
        if (range == null) {
            for (String[] pair : LEGACY_MARKER_PAIRS) {
                range = findMarkerRange(original, pair[0], pair[1]);
                if (range != null) break;
            }
        }

        if (range == null) {
            String base = original.endsWith("\n") ? original : original + "\n";
            return base + newAppendix;
        }

        String before = original.substring(0, range.start());
        // Drop trailing blank-line delimiter before the existing block so
        // the replacement does not introduce a stray double-newline.
        if (before.endsWith("\n\n")) {
            before = before.substring(0, before.length() - 1);
        }
        String after = original.substring(range.end());
        return before + newAppendix + after;
    }

    /** Locates a marker pair in {@code text}, including the trailing newline of the end marker. */
    @Nullable
    private static Range findMarkerRange(@NotNull String text,
                                         @NotNull String beginMarker,
                                         @NotNull String endMarker) {
        int start = text.indexOf(beginMarker);
        if (start < 0) return null;
        int endMarkerIdx = text.indexOf(endMarker, start);
        int end = endMarkerIdx < 0 ? text.length() : endMarkerIdx + endMarker.length();
        if (end < text.length() && text.charAt(end) == '\n') end++;
        return new Range(start, end);
    }

    private record Range(int start, int end) {}
}
