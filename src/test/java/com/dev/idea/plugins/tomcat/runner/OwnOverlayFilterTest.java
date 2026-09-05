package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner.DuplicateGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The duplicate warning omits the class sync's own jar overlays and nothing else. */
@DisplayName("duplicate warning vs the sync's own overlays")
class OwnOverlayFilterTest {

    private static DuplicateGroup group(String path, String... locations) {
        return new DuplicateGroup(path, List.of(locations));
    }

    @Test
    @DisplayName("a classes+jar pair recorded as an overlay of that jar is omitted")
    void recordedOverlayOmitted() {
        List<DuplicateGroup> groups = List.of(
                group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar"));
        Map<String, Set<String>> coverage = Map.of("WEB-INF/lib/lib-alpha-1.0.jar", Set.of("org/example/A.class"));

        assertEquals(List.of(), LocalDeploymentStrategy.withoutOwnOverlays(groups, coverage));
    }

    @Test
    @DisplayName("an uncovered path, a different jar, or a third location stays reported")
    void genuineDuplicatesKept() {
        DuplicateGroup uncovered = group("org/example/B.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar");
        DuplicateGroup otherJar = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-beta-1.0.jar");
        DuplicateGroup threeWay = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");
        DuplicateGroup jarOnly = group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");
        Map<String, Set<String>> coverage = Map.of("WEB-INF/lib/lib-alpha-1.0.jar", Set.of("org/example/A.class"));

        assertEquals(List.of(uncovered, otherJar, threeWay, jarOnly),
                LocalDeploymentStrategy.withoutOwnOverlays(List.of(uncovered, otherJar, threeWay, jarOnly), coverage));
    }

    @Test
    @DisplayName("no manifest coverage: everything stays reported")
    void noCoverageKeepsAll() {
        List<DuplicateGroup> groups = List.of(group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar"));
        assertEquals(groups, LocalDeploymentStrategy.withoutOwnOverlays(groups, Map.of()));
    }
}
