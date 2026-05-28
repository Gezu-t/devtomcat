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
 * The scanner filters these out so the warning only fires on real concerns:
 *
 * <ul>
 *   <li><b>JAR housekeeping</b>: {@code META-INF/MANIFEST.MF},
 *       {@code META-INF/INDEX.LIST}, {@code META-INF/DEPENDENCIES} — every JAR
 *       has its own copy by convention.</li>
 *   <li><b>License / notice files</b>: {@code META-INF/LICENSE*},
 *       {@code META-INF/NOTICE*}, {@code META-INF/README*} — each JAR ships
 *       its own attribution.</li>
 *   <li><b>{@code ServiceLoader} entries</b>: {@code META-INF/services/*} —
 *       the Java spec mandates multiple JARs can contribute and they are
 *       merged at runtime.</li>
 *   <li><b>Multi-release JAR overrides</b>: {@code META-INF/versions/*} — the
 *       same logical class compiled for different JVM versions, selected by
 *       the runtime; not a true duplicate.</li>
 *   <li><b>Per-artifact metadata</b>: {@code META-INF/maven/*} — every JAR
 *       carries its own Maven coordinate, by definition unique per JAR.</li>
 *   <li><b>Spec-allowed multi-instance descriptors</b>:
 *       {@code META-INF/persistence.xml}, {@code META-INF/orm.xml},
 *       {@code META-INF/beans.xml}, {@code META-INF/web-fragment.xml} — the
 *       JPA / CDI / Servlet specs explicitly allow multiple instances.</li>
 *   <li><b>Configuration discovery files commonly merged at runtime</b>:
 *       {@code META-INF/spring.factories}, {@code META-INF/spring/*} (Spring
 *       Boot auto-configuration entries are by design assembled across
 *       multiple JARs).</li>
 *   <li><b>Native + GraalVM bindings</b>: {@code META-INF/native/*},
 *       {@code META-INF/native-image/*} — per-platform/per-image artifacts.</li>
 *   <li><b>{@code package-info.class}</b>: legitimately appears in any JAR
 *       that contributes to a split package.</li>
 *   <li><b>{@code module-info.class}</b>: per-JAR JPMS descriptor, never a
 *       collision.</li>
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

    /**
     * Exact paths whose duplication across the classpath is universally
     * expected. Match by full string equality, case-sensitive (JAR entries
     * are case-sensitive per the spec).
     */
    private static final Set<String> BENIGN_EXACT_PATHS = Set.of(
            "META-INF/MANIFEST.MF",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/persistence.xml",
            "META-INF/orm.xml",
            "META-INF/beans.xml",
            "META-INF/web-fragment.xml",
            "META-INF/jandex.idx",
            "META-INF/io.netty.versions.properties",
            "META-INF/spring.factories",
            "META-INF/spring.handlers",
            "META-INF/spring.schemas",
            "META-INF/spring.tooling",
            "META-INF/additional-spring-configuration-metadata.json",
            "META-INF/spring-configuration-metadata.json",
            "META-INF/spring-autoconfigure-metadata.properties",
            "META-INF/spring-devtools.properties",
            "module-info.class"
    );

    /**
     * Path prefixes whose entries are universally expected to coexist across
     * multiple JARs. Match by {@code String#startsWith}, case-sensitive.
     */
    private static final List<String> BENIGN_PREFIXES = List.of(
            "META-INF/services/",       // ServiceLoader spec — merged
            "META-INF/maven/",          // per-JAR Maven coordinate
            "META-INF/versions/",       // multi-release JAR overrides
            "META-INF/native/",         // native library bindings
            "META-INF/native-image/",   // GraalVM hints
            "META-INF/spring/",         // Spring Boot 3+ auto-config — merged
            "META-INF/LICENSE",         // LICENSE, LICENSE.txt, LICENSE.md, etc.
            "META-INF/NOTICE",          // NOTICE variants
            "META-INF/README",          // README variants
            "META-INF/proguard/"        // proguard configs per artifact
    );

    /**
     * Path suffixes whose entries are universally expected to coexist across
     * multiple JARs.
     */
    private static final List<String> BENIGN_SUFFIXES = List.of(
            "/package-info.class"       // split-package contributions
    );

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
        if (BENIGN_EXACT_PATHS.contains(relPath)) return true;
        for (String prefix : BENIGN_PREFIXES) {
            if (relPath.startsWith(prefix)) return true;
        }
        for (String suffix : BENIGN_SUFFIXES) {
            if (relPath.endsWith(suffix)) return true;
        }
        return false;
    }
}
