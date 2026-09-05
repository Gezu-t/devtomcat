package com.dev.idea.plugins.tomcat.diagnostics;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans a deployed exploded WAR for classpath duplicates — files that exist
 * at the same logical relative path in two or more classpath sources
 * ({@code WEB-INF/classes/} and the JARs in {@code WEB-INF/lib/}).
 *
 * <p>Why this exists: many frameworks and libraries audit their own resources
 * for uniqueness, refusing to start when the same logical resource resolves
 * to multiple classpath URIs. The specific exception varies (each framework
 * names its own error class and error text), the underlying cause does not.
 * Surfacing duplicates pre-launch gives the user a chance to spot the build-
 * configuration issue before the framework's error message points the blame
 * elsewhere. Library-agnostic by design — no pattern matches against any
 * library's specific error wording or class names.
 *
 * <h2>What counts as benign</h2>
 *
 * <p>Many paths legitimately appear in many JARs and are NOT classpath bugs.
 * The scanner filters these out with three rules grounded in the JAR, JPMS and
 * Java-language specifications — never in any library's file names:
 *
 * <ul>
 *   <li><b>Metadata under {@code META-INF/} that is not a class</b>: the JAR
 *       spec reserves {@code META-INF/} for per-JAR metadata, and that is
 *       where every convention that is duplicated <em>by design</em> lives —
 *       manifests, licences and notices, Maven coordinates,
 *       {@code ServiceLoader} entries (merged at runtime by spec),
 *       spec-allowed multi-instance descriptors (persistence, CDI beans,
 *       web fragments), native and native-image bindings, and every
 *       framework's own configuration-discovery files, whatever it calls
 *       them. Treating the whole area as mergeable is what keeps this rule
 *       complete for libraries the plugin has never heard of.</li>
 *   <li><b>Multi-release overrides</b>: {@code META-INF/versions/*} — the same
 *       logical class compiled for different JVM versions, selected by the
 *       runtime; the one place a {@code .class} under {@code META-INF/} is
 *       expected.</li>
 *   <li><b>JPMS and package descriptors</b>: {@code module-info.class} is a
 *       per-JAR descriptor; {@code package-info.class} legitimately appears
 *       in every JAR contributing to a split package.</li>
 * </ul>
 *
 * <p>What survives the filter: application-level configuration files (XML,
 * YAML, properties), real {@code .class} files at the same path, and any
 * other resource the user's frameworks may genuinely care about. The user's
 * build packaged it more than once — they need to know.
 */
public final class WarClasspathDuplicateScanner {

    private static final Logger LOG = Logger.getInstance(WarClasspathDuplicateScanner.class);

    /**
     * One duplicate finding: a logical resource path and the list of
     * classpath locations it appears in. Locations are human-readable
     * strings — {@code "WEB-INF/classes/"} or
     * {@code "WEB-INF/lib/<jar-name>.jar"}.
     */
    public record DuplicateGroup(@NotNull String logicalPath,
                                 @NotNull List<String> locations) {}

    private static final String WEB_INF = "WEB-INF";
    private static final String CLASSES_DIR = "classes";
    private static final String LIB_DIR = "lib";
    private static final String JAR_EXT = ".jar";
    private static final String CLASSES_LOCATION_LABEL = "WEB-INF/classes/";
    private static final String LIB_LOCATION_PREFIX = "WEB-INF/lib/";

    private static final String META_INF = "META-INF/";
    private static final String MULTI_RELEASE = "META-INF/versions/";
    private static final String CLASS_EXT = ".class";
    private static final String MODULE_DESCRIPTOR = "module-info.class";
    private static final String PACKAGE_DESCRIPTOR = "package-info.class";

    private WarClasspathDuplicateScanner() {}

    /**
     * Walks the deployed exploded artifact's classpath sources and returns
     * logical paths that appear in 2+ locations after the benign filter.
     * Returns an empty list when {@code artifactPath} has no {@code WEB-INF/}
     * (defensive — the deployment hasn't actually been built into the
     * exploded shape this method scans). Never throws on per-JAR I/O
     * failures; corrupted or unreadable JARs are logged and skipped.
     *
     * <p>Performance: walks {@code WEB-INF/classes/} once recursively, plus
     * one ZIP-table read per JAR in {@code WEB-INF/lib/}. For a typical
     * webapp with 100-200 dependency JARs the scan completes in tens of
     * milliseconds and is safe to run on every launch.
     */
    @NotNull
    public static List<DuplicateGroup> scan(@NotNull Path artifactPath) {
        Path webInf = artifactPath.resolve(WEB_INF);
        if (!Files.isDirectory(webInf)) return Collections.emptyList();

        Map<String, List<String>> pathToLocations = new HashMap<>();

        // 1. Walk WEB-INF/classes/ recursively, recording each regular file's
        //    path relative to WEB-INF/classes/.
        Path classesDir = webInf.resolve(CLASSES_DIR);
        if (Files.isDirectory(classesDir)) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String relPath = classesDir.relativize(p).toString().replace('\\', '/');
                    if (isBenign(relPath)) return;
                    pathToLocations.computeIfAbsent(relPath, k -> new ArrayList<>())
                            .add(CLASSES_LOCATION_LABEL);
                });
            } catch (IOException e) {
                LOG.debug("Could not walk " + classesDir + " for duplicate scan: " + e.getMessage());
            }
        }

        // 2. Enumerate every JAR's entries in WEB-INF/lib/, recording each
        //    non-directory entry's path.
        Path libDir = webInf.resolve(LIB_DIR);
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> stream = Files.list(libDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(JAR_EXT))
                      .forEach(jarPath -> indexJarEntries(jarPath, pathToLocations));
            } catch (IOException e) {
                LOG.debug("Could not list " + libDir + " for duplicate scan: " + e.getMessage());
            }
        }

        // 3. Return paths with 2+ locations, sorted for stable output.
        List<DuplicateGroup> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : pathToLocations.entrySet()) {
            if (entry.getValue().size() >= 2) {
                List<String> sortedLocations = new ArrayList<>(entry.getValue());
                Collections.sort(sortedLocations);
                duplicates.add(new DuplicateGroup(entry.getKey(), sortedLocations));
            }
        }
        duplicates.sort(Comparator.comparing(DuplicateGroup::logicalPath));
        return duplicates;
    }

    private static void indexJarEntries(@NotNull Path jarPath,
                                        @NotNull Map<String, List<String>> pathToLocations) {
        String jarLocation = LIB_LOCATION_PREFIX + jarPath.getFileName().toString();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String relPath = entry.getName();
                if (isBenign(relPath)) continue;
                pathToLocations.computeIfAbsent(relPath, k -> new ArrayList<>())
                        .add(jarLocation);
            }
        } catch (IOException e) {
            // A corrupted or unreadable JAR shouldn't crash the scan. The user
            // will see this in the IDE log; the launch itself will surface any
            // resulting ClassNotFoundException with much louder symptoms.
            LOG.debug("Could not read " + jarPath + " for duplicate scan: " + e.getMessage());
        }
    }

    /**
     * Visible for testing.
     */
    static boolean isBenign(@NotNull String relPath) {
        if (relPath.startsWith(MULTI_RELEASE)) return true;
        if (relPath.startsWith(META_INF)) return !relPath.endsWith(CLASS_EXT);
        if (relPath.equals(MODULE_DESCRIPTOR)) return true;
        return relPath.equals(PACKAGE_DESCRIPTOR) || relPath.endsWith("/" + PACKAGE_DESCRIPTOR);
    }
}
