package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.update.DeployedClassesSync;
import com.dev.idea.plugins.tomcat.update.WebResourcesSync;
import com.dev.idea.plugins.tomcat.utils.TomcatProjectUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Decides, once per launch and after deployment, whether Tomcat's {@code work/}
 * (compiled JSPs, generated servlet sources) can be kept from the previous run.
 *
 * <p>Wiping {@code work/} on every launch is safe but makes the first request
 * after every start recompile every JSP. Jasper itself recompiles a JSP whose
 * source is newer than its class; what it cannot see is a change in the classes
 * or libraries the compiled JSP was linked against. So the rule is conservative:
 * {@code work/} survives only when a fingerprint of everything a compiled JSP
 * depends on is identical to the one recorded at the previous launch — kept as
 * plain {@code key=value} lines, so a clear can say which line changed —
 *
 * <ul>
 *   <li>the Tomcat home and version, and the JDK;</li>
 *   <li>the deployment set: each context, its resolved path, whether it is
 *       exploded, whether it is valid;</li>
 *   <li>per exploded deployment: the class-sync and web-resources manifests'
 *       size+mtime (each is rewritten only when the synced content changed, so
 *       this is an exact "WEB-INF/classes as synced changed" signal) and the
 *       {@code WEB-INF/lib} listing with every jar's size+mtime;</li>
 *   <li>per WAR deployment: the WAR's size+mtime;</li>
 *   <li>and, when the class sync could not cover every artifact, a nonce — an
 *       artifact the sync did not see is one whose classes may have changed
 *       unobserved, so its {@code work/} is never kept.</li>
 * </ul>
 *
 * <p>Any doubt clears. A kept {@code work/} is still swept for symlinks, the
 * one thing the old unconditional wipe removed for safety rather than freshness.
 */
public final class WorkDirectoryKeeper {

    private static final Logger LOG = Logger.getInstance(WorkDirectoryKeeper.class);

    /** Lives beside {@code work/}, not inside it, so the wipe cannot erase its own record. */
    static final String MARKER = ".devtomcat-work.fingerprint";

    private WorkDirectoryKeeper() {}

    @NotNull
    public static String fingerprint(@NotNull List<Deployment> deployments,
                                     @Nullable String tomcatHome,
                                     @Nullable String tomcatVersion,
                                     @Nullable String jdkHome,
                                     boolean syncIncomplete) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("tomcat=").append(tomcatHome).append('|').append(tomcatVersion).append('\n');
        sb.append("jdk=").append(jdkHome).append('\n');
        List<Deployment> sorted = new ArrayList<>(deployments);
        sorted.sort(Comparator.comparing(Deployment::getContextPath).thenComparing(Deployment::getDisplayName));
        for (Deployment d : sorted) {
            Path root = d.getResolvedPath();
            sb.append("app=").append(d.getContextPath()).append('|').append(root)
              .append("|exploded=").append(d.isExploded()).append("|valid=").append(d.isValid()).append('\n');
            if (root == null) continue;
            // Every per-deployment line is keyed by its context, so a clear can name
            // exactly which app's manifest or jar changed (several apps package the
            // same jar name; keys must not collide across them).
            String key = d.getContextPath() + "/";
            if (d.isExploded() || Files.isDirectory(root)) {
                appendStamp(sb, key + "classes-manifest",
                        DeployedClassesSync.classSyncManifestFor(root.resolve(TomcatConstants.WEB_INF_CLASSES_PATH)));
                appendStamp(sb, key + "web-manifest", WebResourcesSync.webResourcesManifestFor(root));
                appendLibListing(sb, key, root.resolve(TomcatConstants.WEB_INF_LIB_PATH));
            } else {
                appendStamp(sb, key + "war", root);
            }
        }
        if (syncIncomplete) {
            sb.append("sync-incomplete=").append(System.nanoTime()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Keeps {@code work/} when {@code fingerprint} equals the one recorded at the
     * previous launch, otherwise clears it and records the new fingerprint.
     *
     * @return {@code true} when {@code work/} was kept
     */
    public static boolean apply(@NotNull Path catalinaBase,
                                @NotNull String fingerprint,
                                @Nullable TomcatDeploymentLogger logger) throws IOException {
        Path marker = catalinaBase.resolve(MARKER);
        Path work = catalinaBase.resolve(TomcatConstants.DIR_WORK);
        String previous = Files.isRegularFile(marker)
                ? Files.readString(marker, StandardCharsets.UTF_8).trim() : null;
        // Trailing newline of the text form must not defeat the comparison.
        fingerprint = fingerprint.trim();
        if (fingerprint.equals(previous) && Files.isDirectory(work)) {
            int links = TomcatConfigPreparer.sanitizeWorkSymlinks(catalinaBase);
            LOG.info("work/ kept for " + catalinaBase + " (fingerprint unchanged)");
            if (logger != null) {
                logger.logServerInfo("work/ kept: nothing the compiled JSPs depend on changed since the last launch"
                        + (links > 0 ? " (" + links + " symlink(s) removed)" : ""));
            }
            return true;
        }
        TomcatConfigPreparer.cleanWorkDirectory(catalinaBase);
        TomcatProjectUtils.atomicWriteString(marker, fingerprint);
        String why = previous == null ? "first launch in this run directory" : "changed: " + firstDifference(previous, fingerprint);
        LOG.info("work/ cleared for " + catalinaBase + " (" + why + ")");
        if (logger != null) {
            logger.logServerInfo("work/ cleared: " + why);
        }
        return false;
    }

    private static void appendStamp(@NotNull StringBuilder sb, @NotNull String label, @NotNull Path file) {
        sb.append(label).append('=');
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            sb.append(a.size()).append('|').append(a.lastModifiedTime().toMillis());
        } catch (IOException absent) {
            sb.append("absent");
        }
        sb.append('\n');
    }

    private static void appendLibListing(@NotNull StringBuilder sb, @NotNull String key, @NotNull Path lib) {
        if (!Files.isDirectory(lib)) {
            sb.append(key).append("lib=absent\n");
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(lib)) {
            s.forEach(jars::add);
        } catch (IOException e) {
            sb.append(key).append("lib=unreadable|").append(System.nanoTime()).append('\n');   // doubt clears
            return;
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path jar : jars) {
            appendStamp(sb, key + "lib/" + jar.getFileName(), jar);
        }
    }

    /** The first fingerprint line that differs, as {@code key: old → new}; "no visible difference" if none. */
    @NotNull
    static String firstDifference(@NotNull String previous, @NotNull String current) {
        Map<String, String> old = lines(previous);
        Map<String, String> now = lines(current);
        for (Map.Entry<String, String> e : now.entrySet()) {
            String was = old.get(e.getKey());
            if (was == null) return e.getKey() + " added (" + e.getValue() + ")";
            if (!was.equals(e.getValue())) return e.getKey() + ": " + was + " → " + e.getValue();
        }
        for (String key : old.keySet()) {
            if (!now.containsKey(key)) return key + " removed";
        }
        return "no visible difference";
    }

    private static Map<String, String> lines(@NotNull String text) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : text.split("\\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.putIfAbsent(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }
}
