package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
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
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Compatibility shim for Tomcat versions whose bundled BCEL parser cannot
 * read Java 9+ {@code module-info.class} files.
 *
 * <h2>The bug</h2>
 * Tomcat's annotation scanner walks every {@code .class} entry in
 * {@code WEB-INF/lib/*.jar} at startup and parses each via Tomcat's forked
 * BCEL implementation. Java 9 added two new constant-pool tags:
 * {@code CONSTANT_Module} (19) and {@code CONSTANT_Package} (20). The forked
 * BCEL on older Tomcat versions does not recognise them and throws
 * {@link RuntimeException} with the message
 * {@code "Invalid byte tag in constant pool: 19"}. The scanner logs the
 * exception at SEVERE and continues with the next class, so the deployment
 * still succeeds but the log is flooded with one stack trace per modular JAR.
 *
 * <h2>Affected versions</h2>
 * <ul>
 *   <li><b>Tomcat 7.x</b>: all versions. No fix backported (branch EOL March 2021).</li>
 *   <li><b>Tomcat 8.0.x</b>: all versions (branch EOL June 2018, no fix).</li>
 *   <li><b>Tomcat 8.5.x</b>: fixed in 8.5.51 (March 2020); 8.5.0 through 8.5.50 are affected.</li>
 *   <li><b>Tomcat 9.0.x</b>: fixed in 9.0.31 (January 2020); 9.0.0 through 9.0.30 are affected.</li>
 *   <li><b>Tomcat 10.x, 11.x</b>: never affected (BCEL fork already handled the new tags by 10.0.0).</li>
 * </ul>
 *
 * <h2>The fix</h2>
 * On affected Tomcats, add JARs that contain {@code module-info.class}
 * (either at the root or under {@code META-INF/versions/<n>/}, the
 * Multi-Release JAR layout) to the global skip list defined in
 * {@code CATALINA_BASE/conf/catalina.properties}. The property name varies
 * with version:
 * <ul>
 *   <li>Tomcat 7.x and 8.0.x: {@code tomcat.util.scan.DefaultJarScanner.jarsToSkip}</li>
 *   <li>Tomcat 8.5+ and 9+: {@code tomcat.util.scan.StandardJarScanFilter.jarsToSkip}</li>
 * </ul>
 * Both are read at JVM startup via {@code Properties.load} and merged into
 * {@code System} properties; appending an override line at the end of the
 * file extends the existing default skip list (which contains tomcat-*.jar,
 * el-api.jar, etc.) with our modular JAR names. {@link Properties#load}
 * applies later-wins semantics so a duplicated property with a longer value
 * supersedes the original.
 *
 * <p>The per-context {@code <JarScanFilter>} element introduced in Tomcat 8.5
 * is intentionally NOT used because Tomcat 7's {@code ContextRuleSet} has no
 * Digester rule for {@code Context/JarScanner/JarScanFilter}, so the element
 * is silently ignored and the launcher's run console fills with
 * {@code "No rules found matching 'Context/JarScanner/JarScanFilter'"}
 * warnings without any actual skip taking effect. The catalina.properties
 * channel is supported uniformly across every affected version and avoids
 * those warnings entirely.
 *
 * <p>The classes themselves still load at runtime through the normal
 * classloader path; the JVM uses the Multi-Release slice it understands and
 * the {@code module-info.class} is ignored on the classpath as it should be.
 *
 * <h2>What this does NOT change</h2>
 * <ul>
 *   <li>Modern Tomcats (10.x, 11.x, 8.5.51+, 9.0.31+) keep their existing
 *       full annotation scan; this shim only triggers on affected versions.</li>
 *   <li>Tomcat's TLD scanner is left alone; only pluggability (Servlet 3.0
 *       annotation) scanning is skipped, and only for the modular JARs that
 *       trigger the BCEL exception in the first place.</li>
 *   <li>{@code @WebServlet}, {@code @WebFilter}, etc. annotations on
 *       application code in {@code WEB-INF/classes} are still scanned. The
 *       skip applies only to the listed dependency JARs which historically
 *       do not declare such annotations (jackson, jaxb-api, byte-buddy,
 *       snakeyaml, etc.).</li>
 * </ul>
 *
 * @author Gezahegn Lemma (Gezu)
 */
final class BcelModuleInfoCompat {

    private static final Logger LOG = Logger.getInstance(BcelModuleInfoCompat.class);

    /** Multi-release JARs put alternate-version classes here. */
    private static final Pattern MULTI_RELEASE_MODULE_INFO =
            Pattern.compile("META-INF/versions/\\d+/module-info\\.class");

    /** Top-level module descriptor. */
    private static final String ROOT_MODULE_INFO = "module-info.class";

    /** First Tomcat 8.5.x with the BCEL fix. */
    private static final TomcatVersion TOMCAT_8_5_FIX = new TomcatVersion(8, 5, 51);

    /** First Tomcat 9.0.x with the BCEL fix. */
    private static final TomcatVersion TOMCAT_9_0_FIX = new TomcatVersion(9, 0, 31);

    /**
     * Property names Tomcat reads from {@code catalina.properties} (and
     * {@link System#getProperty}) to populate the JAR scan skip list. Order
     * matters: the FIRST name that already exists in the install's
     * {@code catalina.properties} is the one whose value we extend. Tomcat
     * 7.x and 8.0.x use {@code DefaultJarScanner}; 8.5+ uses
     * {@code StandardJarScanFilter}.
     */
    static final String[] SKIP_PROPERTY_NAMES = {
            "tomcat.util.scan.StandardJarScanFilter.jarsToSkip",
            "tomcat.util.scan.DefaultJarScanner.jarsToSkip"
    };

    /** Marker so subsequent runs can update our previous appendix in place rather than stacking duplicates. */
    static final String APPENDIX_MARKER = "# DevTomcat: BCEL/module-info compatibility appendix (auto-generated)";

    /** Closing marker for the auto-generated appendix block. */
    static final String APPENDIX_END_MARKER = "# DevTomcat: end of BCEL/module-info appendix";

    private BcelModuleInfoCompat() {}

    // ------------------------------------------------------------------ //
    // Version gate
    // ------------------------------------------------------------------ //

    /**
     * Returns {@code true} when {@code info} identifies a Tomcat version whose
     * bundled BCEL parser cannot read Java 9+ {@code module-info.class}.
     *
     * <p>Conservative on unparseable / unknown input: a {@code null} info,
     * empty version, or a version we cannot parse all returns {@code false}.
     * That keeps modern Tomcats from being incorrectly downgraded into the
     * skip-everything path. Affected users always have a parseable version
     * string written by Tomcat's own {@code ServerInfo.properties}.
     */
    static boolean isAffectedByBcelModuleInfoBug(@Nullable TomcatInfo info) {
        if (info == null) return false;
        TomcatVersion v = TomcatVersion.parse(info.getVersion());
        if (v == null) return false;

        if (v.major() == 7) return true;          // every 7.x is affected
        if (v.major() == 8) {
            if (v.minor() < 5) return true;       // 8.0.x is affected (and EOL)
            return v.compareTo(TOMCAT_8_5_FIX) < 0;
        }
        if (v.major() == 9 && v.minor() == 0) {
            return v.compareTo(TOMCAT_9_0_FIX) < 0;
        }
        return false;                              // 10.x, 11.x, future
    }

    // ------------------------------------------------------------------ //
    // JAR scan
    // ------------------------------------------------------------------ //

    /**
     * Walks {@code webInfLib} and returns the file names of JARs that contain
     * a {@code module-info.class} entry (root or multi-release). The order is
     * the directory's natural listing order so the resulting filter string is
     * deterministic for a given layout.
     *
     * <p>Never throws. I/O errors on individual JARs are logged at debug and
     * the JAR is skipped (treated as not modular); a failure to even list the
     * directory yields an empty result.
     */
    @NotNull
    static List<String> findJarsContainingModuleInfo(@NotNull Path webInfLib) {
        if (!Files.isDirectory(webInfLib)) return List.of();
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(webInfLib)) {
            stream.filter(BcelModuleInfoCompat::isJar)
                  .sorted(Comparator.comparing(Path::getFileName))
                  .forEach(jar -> {
                      if (jarContainsModuleInfo(jar)) {
                          matches.add(jar.getFileName().toString());
                      }
                  });
        } catch (IOException e) {
            LOG.debug("Could not enumerate WEB-INF/lib: " + e.getMessage());
        }
        return matches;
    }

    /**
     * Inspects a single JAR for a module descriptor. Public-package so a
     * targeted unit test can pin individual JAR fixtures without going through
     * directory scanning.
     */
    static boolean jarContainsModuleInfo(@NotNull Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Fast path: the modern non-MR layout puts module-info.class at the root.
            if (zip.getEntry(ROOT_MODULE_INFO) != null) {
                return true;
            }
            // Multi-Release JARs put it under META-INF/versions/N/.
            // Iterate directly so we do not allocate a Stream for cold paths.
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (MULTI_RELEASE_MODULE_INFO.matcher(name).matches()) {
                    return true;
                }
            }
            return false;
        } catch (ZipException e) {
            // Treat a corrupt JAR as "no module-info" rather than failing the
            // whole launch. Tomcat will log its own error when it hits the JAR.
            LOG.debug("Skipping non-readable JAR for module-info scan: " + jar
                    + " (" + e.getMessage() + ")");
            return false;
        } catch (IOException e) {
            LOG.debug("I/O error scanning JAR for module-info: " + jar
                    + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean isJar(@NotNull Path p) {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") && Files.isRegularFile(p);
    }

    // ------------------------------------------------------------------ //
    // catalina.properties skip-list injection (the actual workaround)
    // ------------------------------------------------------------------ //

    /**
     * Result of an attempted skip-list injection. Returned from
     * {@link #applyModuleInfoSkipToCatalinaProperties} so callers can decide
     * what (if anything) to surface in the run console.
     */
    enum InjectionOutcome {
        /** No-op: nothing to inject (no modular JARs detected). */
        NO_MODULAR_JARS,
        /** No-op: catalina.properties is missing or unreadable. */
        PROPERTIES_FILE_UNAVAILABLE,
        /** No-op: every modular JAR is already covered by an existing skip entry. */
        ALREADY_COVERED,
        /** Skip list was extended; deployments should now scan past the modular JARs. */
        APPENDED,
        /** Existing DevTomcat-managed appendix was refreshed in place (subsequent run). */
        REFRESHED
    }

    /**
     * Extends the {@code jarsToSkip} list in
     * {@code CATALINA_BASE/conf/catalina.properties} with the given modular
     * JAR names so Tomcat's JAR scanner skips annotation parsing for them.
     *
     * <p>Behavior:
     * <ol>
     *   <li>Reads the existing {@code catalina.properties} via
     *       {@link Properties#load} (handles multi-line {@code \}-continuation,
     *       comments, and encoding correctly).</li>
     *   <li>Determines the right property name. The FIRST entry in
     *       {@link #SKIP_PROPERTY_NAMES} that the file already defines wins;
     *       on Tomcat 7 / 8.0.x that is {@code DefaultJarScanner...}, on
     *       Tomcat 8.5+ that is {@code StandardJarScanFilter...}.</li>
     *   <li>Computes the union of the existing comma-separated entries and
     *       the modular JAR names, deduplicating, preserving order.</li>
     *   <li>If a previous DevTomcat-managed appendix block is present (between
     *       {@link #APPENDIX_MARKER} and {@link #APPENDIX_END_MARKER}), it is
     *       replaced in place. Otherwise, a fresh appendix block is appended
     *       to the file via temp-file + atomic move.</li>
     * </ol>
     *
     * <p>{@link Properties#load} applies later-wins semantics, so an override
     * at the end of the file supersedes the original definition. The original
     * lines (including the upstream Tomcat skip list of {@code tomcat-*.jar},
     * {@code el-api.jar}, etc.) are preserved as inline values in our override.
     *
     * <p>Never throws. I/O failures, parse errors, and missing files are
     * logged at warn or debug and the method returns the appropriate
     * {@link InjectionOutcome} so the caller can react (e.g. log an explanation
     * to the run console).
     *
     * @param catalinaBase the per-launch CATALINA_BASE directory
     * @param modularJars  modular JAR file names (e.g. {@code "jackson-core-2.17.0.jar"})
     * @param logger       optional run-console logger; receives a single info
     *                     line summarising what was injected
     * @return outcome of the operation
     */
    @NotNull
    static InjectionOutcome applyModuleInfoSkipToCatalinaProperties(
            @NotNull Path catalinaBase,
            @NotNull List<String> modularJars,
            @Nullable TomcatDeploymentLogger logger) {
        if (modularJars.isEmpty()) {
            return InjectionOutcome.NO_MODULAR_JARS;
        }
        Path props = catalinaBase.resolve("conf").resolve("catalina.properties");
        if (!Files.isRegularFile(props)) {
            LOG.debug("catalina.properties not found at " + props
                    + "; skipping module-info skip injection");
            return InjectionOutcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        // Load via Properties.load so we get the merged value of any \-continuation
        // lines and any later-wins overrides already present.
        Properties existing = new Properties();
        try (InputStream is = Files.newInputStream(props)) {
            existing.load(is);
        } catch (IOException e) {
            LOG.warn("Could not read catalina.properties for module-info skip injection: "
                    + e.getMessage());
            return InjectionOutcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        // Pick the property whose name the install already uses. Falls back to
        // both names when neither is present, which is unusual but defensive.
        List<String> targetProperties = new ArrayList<>();
        for (String name : SKIP_PROPERTY_NAMES) {
            if (existing.containsKey(name)) {
                targetProperties.add(name);
            }
        }
        if (targetProperties.isEmpty()) {
            targetProperties = new ArrayList<>(Arrays.asList(SKIP_PROPERTY_NAMES));
        }

        // Build the merged value for each target property. Skip if every
        // modular JAR is already in the existing list (idempotent on rerun).
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
            entries.addAll(modularJars);
            if (entries.size() == before) {
                // Already covers all our modular JARs.
                continue;
            }
            overrideLines.add(propName + "=" + String.join(",", entries));
            anyChange = true;
        }
        if (!anyChange) {
            return InjectionOutcome.ALREADY_COVERED;
        }

        String original;
        try {
            original = Files.readString(props);
        } catch (IOException e) {
            LOG.warn("Could not read catalina.properties contents: " + e.getMessage());
            return InjectionOutcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        String appendix = buildAppendix(overrideLines);
        String updated = replaceOrAppendAppendix(original, appendix);
        boolean refreshed = updated.equals(original)
                ? false
                : original.contains(APPENDIX_MARKER);

        if (updated.equals(original)) {
            // No textual change (the appendix was already present and unchanged).
            return InjectionOutcome.ALREADY_COVERED;
        }

        try {
            TomcatProjectUtils.atomicWriteString(props, updated);
        } catch (IOException e) {
            LOG.warn("Could not write catalina.properties for module-info skip injection: "
                    + e.getMessage(), e);
            return InjectionOutcome.PROPERTIES_FILE_UNAVAILABLE;
        }

        InjectionOutcome outcome = refreshed
                ? InjectionOutcome.REFRESHED
                : InjectionOutcome.APPENDED;
        if (logger != null) {
            String summary = "BCEL/module-info compatibility: "
                    + (refreshed ? "refreshed" : "appended")
                    + " skip-list override in catalina.properties for "
                    + modularJars.size() + " modular JAR(s) ("
                    + String.join(", ", targetProperties) + "). "
                    + "Annotation scan will bypass: " + modularJars;
            logger.logServerInfo(summary);
        }
        LOG.info("module-info skip injection " + outcome + ": "
                + modularJars + " into " + targetProperties);
        return outcome;
    }

    /**
     * Builds the auto-generated appendix block, including the begin/end markers
     * that {@link #replaceOrAppendAppendix} uses to find and replace it on
     * subsequent runs.
     */
    @NotNull
    private static String buildAppendix(@NotNull List<String> overrideLines) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(APPENDIX_MARKER).append('\n');
        sb.append("# Older Tomcat releases (7.x, 8.0.x, 8.5.<51, 9.0.<31) ship a BCEL\n");
        sb.append("# parser that throws ClassFormatException on Java 9+ module-info.class\n");
        sb.append("# (CONSTANT_Module tag 19). The line(s) below merge the modular JARs\n");
        sb.append("# from deployed webapps into the existing skip list so annotation\n");
        sb.append("# scanning bypasses them. Property name varies by Tomcat version;\n");
        sb.append("# Properties.load applies later-wins semantics for the override.\n");
        for (String line : overrideLines) {
            sb.append(line).append('\n');
        }
        sb.append(APPENDIX_END_MARKER).append('\n');
        return sb.toString();
    }

    /**
     * If {@code original} contains a previous DevTomcat-managed appendix
     * (delimited by {@link #APPENDIX_MARKER} / {@link #APPENDIX_END_MARKER}),
     * replaces it with {@code newAppendix}. Otherwise appends
     * {@code newAppendix} at the end of the content (preserving a trailing
     * newline). The marker-based replace prevents the file from growing
     * unboundedly across rebuild cycles in the IDE-managed CATALINA_BASE.
     */
    @NotNull
    static String replaceOrAppendAppendix(@NotNull String original, @NotNull String newAppendix) {
        int start = original.indexOf(APPENDIX_MARKER);
        if (start < 0) {
            // No prior appendix; append. Ensure the previous content ends with a newline.
            String base = original.endsWith("\n") ? original : original + "\n";
            return base + newAppendix;
        }
        int endMarkerIdx = original.indexOf(APPENDIX_END_MARKER, start);
        int end = endMarkerIdx < 0
                ? original.length()
                : endMarkerIdx + APPENDIX_END_MARKER.length();
        // Strip an optional trailing newline so we don't double up.
        if (end < original.length() && original.charAt(end) == '\n') {
            end++;
        }
        String before = original.substring(0, start);
        // Drop the leading blank-line delimiter we'd otherwise duplicate.
        if (before.endsWith("\n\n")) {
            before = before.substring(0, before.length() - 1);
        }
        String after = original.substring(end);
        return before + newAppendix + after;
    }

    // ------------------------------------------------------------------ //
    // Version parsing
    // ------------------------------------------------------------------ //

    /**
     * Parsed Tomcat version triple. Trailing build numbers ({@code 9.0.56.0})
     * are accepted; only the leading three components matter for the BCEL
     * version gate.
     */
    record TomcatVersion(int major, int minor, int patch) implements Comparable<TomcatVersion> {

        /** Parses a Tomcat version string. Returns {@code null} on any unparseable component. */
        @Nullable
        static TomcatVersion parse(@Nullable String version) {
            if (version == null) return null;
            String trimmed = version.trim();
            if (trimmed.isEmpty()) return null;
            String[] parts = trimmed.split("\\.");
            try {
                int major = Integer.parseInt(parts[0].trim());
                int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                int patch = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                if (major < 0 || minor < 0 || patch < 0) return null;
                return new TomcatVersion(major, minor, patch);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public int compareTo(@NotNull TomcatVersion other) {
            int c = Integer.compare(major, other.major);
            if (c != 0) return c;
            c = Integer.compare(minor, other.minor);
            if (c != 0) return c;
            return Integer.compare(patch, other.patch);
        }
    }
}
