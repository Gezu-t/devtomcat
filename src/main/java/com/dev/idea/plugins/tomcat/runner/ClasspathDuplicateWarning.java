package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner.DuplicateGroup;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters and formats the pre-launch classpath-duplicate warning, grouped by location set. */
final class ClasspathDuplicateWarning {

    private ClasspathDuplicateWarning() {}

    static final String CLASSES_LOCATION = "WEB-INF/classes/";
    static final String LIB_LOCATION_PREFIX = "WEB-INF/lib/";
    static final int EXAMPLES_PER_GROUP = 3;

    /** Drops classes+jar pairs the manifest records as the class sync's own overlay of that jar. */
    @NotNull
    static List<DuplicateGroup> withoutOwnOverlays(@NotNull List<DuplicateGroup> groups,
                                                   @NotNull Map<String, Set<String>> overlayCoverage) {
        if (overlayCoverage.isEmpty()) return groups;
        List<DuplicateGroup> kept = new ArrayList<>();
        for (DuplicateGroup group : groups) {
            if (!isOwnOverlay(group, overlayCoverage)) kept.add(group);
        }
        return kept;
    }

    private static boolean isOwnOverlay(@NotNull DuplicateGroup group,
                                        @NotNull Map<String, Set<String>> overlayCoverage) {
        List<String> locations = group.locations();
        if (locations.size() != 2 || !locations.contains(CLASSES_LOCATION)) return false;
        String jar = locations.get(0).equals(CLASSES_LOCATION) ? locations.get(1) : locations.get(0);
        Set<String> covered = overlayCoverage.get(jar);
        return covered != null && covered.contains(group.logicalPath());
    }

    /** Drops groups that are one library at several versions: preflight already reports that pair. */
    @NotNull
    static List<DuplicateGroup> withoutSameLibraryVersions(@NotNull List<DuplicateGroup> groups) {
        List<DuplicateGroup> kept = new ArrayList<>();
        for (DuplicateGroup group : groups) {
            if (!isOneLibraryAtSeveralVersions(group.locations())) kept.add(group);
        }
        return kept;
    }

    private static boolean isOneLibraryAtSeveralVersions(@NotNull List<String> locations) {
        String base = null;
        for (String location : locations) {
            if (!location.startsWith(LIB_LOCATION_PREFIX)) return false;
            String jarBase = TomcatPreflightValidator.extractJarBaseName(
                    location.substring(LIB_LOCATION_PREFIX.length()));
            if (base == null) {
                base = jarBase;
            } else if (!base.equals(jarBase)) {
                return false;
            }
        }
        return base != null;
    }

    /** One line per location set, largest first, with up to {@link #EXAMPLES_PER_GROUP} example paths. */
    @NotNull
    static String format(@NotNull String artifactName, @NotNull List<DuplicateGroup> groups) {
        Map<List<String>, List<String>> pathsByLocations = new LinkedHashMap<>();
        for (DuplicateGroup group : groups) {
            pathsByLocations.computeIfAbsent(group.locations(), k -> new ArrayList<>())
                            .add(group.logicalPath());
        }
        List<Map.Entry<List<String>, List<String>>> sets = new ArrayList<>(pathsByLocations.entrySet());
        sets.sort(Comparator.<Map.Entry<List<String>, List<String>>>comparingInt(e -> -e.getValue().size())
                            .thenComparing(e -> String.join(" + ", e.getKey())));

        StringBuilder msg = new StringBuilder();
        msg.append("Classpath duplicates in deployed artifact '").append(artifactName).append("' — ")
           .append(groups.size()).append(groups.size() == 1 ? " path is" : " paths are")
           .append(" packaged more than once:");
        for (Map.Entry<List<String>, List<String>> set : sets) {
            List<String> paths = set.getValue();
            msg.append("\n  - ").append(paths.size()).append(paths.size() == 1 ? " path in " : " paths in ")
               .append(String.join(" + ", set.getKey()));
            if (paths.size() <= EXAMPLES_PER_GROUP) {
                msg.append(": ").append(String.join(", ", paths));
            } else {
                msg.append(" (e.g. ").append(String.join(", ", paths.subList(0, EXAMPLES_PER_GROUP))).append(")");
            }
        }
        msg.append("\nFirst match wins in classloader resolution; frameworks that enumerate")
           .append(" all instances of a resource (strict-classpath audits) may refuse to start.")
           .append(" To fix: update your build so each resource is packaged in only one")
           .append(" location, or — if the duplication is intentional — configure the")
           .append(" framework that's auditing the classpath to tolerate duplicates.");
        return msg.toString();
    }
}
