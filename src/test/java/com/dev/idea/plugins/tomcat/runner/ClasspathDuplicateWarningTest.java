package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.diagnostics.WarClasspathDuplicateScanner.DuplicateGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The duplicate warning omits what is not the user's packaging problem and reports the rest by location set. */
@DisplayName("classpath duplicate warning")
class ClasspathDuplicateWarningTest {

    private static DuplicateGroup group(String path, String... locations) {
        return new DuplicateGroup(path, List.of(locations));
    }

    @Nested
    @DisplayName("vs the sync's own overlays")
    class OwnOverlays {

        @Test
        @DisplayName("a classes+jar pair recorded as an overlay of that jar is omitted")
        void recordedOverlayOmitted() {
            List<DuplicateGroup> groups = List.of(
                    group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar"));
            Map<String, Set<String>> coverage = Map.of("WEB-INF/lib/lib-alpha-1.0.jar", Set.of("org/example/A.class"));

            assertEquals(List.of(), ClasspathDuplicateWarning.withoutOwnOverlays(groups, coverage));
        }

        @Test
        @DisplayName("an uncovered path, a different jar, or a third location stays reported")
        void everythingElseStays() {
            DuplicateGroup uncovered = group("org/example/B.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar");
            DuplicateGroup otherJar = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-beta-1.0.jar");
            DuplicateGroup threeWay = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");
            DuplicateGroup jarOnly = group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");
            Map<String, Set<String>> coverage = Map.of("WEB-INF/lib/lib-alpha-1.0.jar", Set.of("org/example/A.class"));

            List<DuplicateGroup> groups = List.of(uncovered, otherJar, threeWay, jarOnly);
            assertEquals(groups, ClasspathDuplicateWarning.withoutOwnOverlays(groups, coverage));
        }

        @Test
        @DisplayName("no manifest coverage: everything stays reported")
        void noCoverageKeepsAll() {
            List<DuplicateGroup> groups = List.of(group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar"));
            assertEquals(groups, ClasspathDuplicateWarning.withoutOwnOverlays(groups, Map.of()));
        }
    }

    @Nested
    @DisplayName("vs preflight's conflicting-JARs warning")
    class SameLibraryVersions {

        @Test
        @DisplayName("one library at two versions is omitted — preflight already named the pair")
        void twoVersionsOmitted() {
            List<DuplicateGroup> groups = List.of(
                    group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-alpha-2.3.1.jar"),
                    group("org/example/B.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-alpha-2.3.1.jar", "WEB-INF/lib/lib-alpha-2.4.0-rc1.jar"));
            assertEquals(List.of(), ClasspathDuplicateWarning.withoutSameLibraryVersions(groups));
        }

        @Test
        @DisplayName("two different libraries, a classes+jar pair, or a mixed set stays reported")
        void crossLibraryStays() {
            DuplicateGroup twoLibraries = group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");
            DuplicateGroup classesAndJar = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar");
            DuplicateGroup versionsPlusClasses = group("org/example/A.class", "WEB-INF/classes/", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-alpha-2.0.jar");
            DuplicateGroup versionsPlusOther = group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-alpha-2.0.jar", "WEB-INF/lib/lib-beta-1.0.jar");

            List<DuplicateGroup> groups = List.of(twoLibraries, classesAndJar, versionsPlusClasses, versionsPlusOther);
            assertEquals(groups, ClasspathDuplicateWarning.withoutSameLibraryVersions(groups));
        }

        @Test
        @DisplayName("uses preflight's own name rule, so the two warnings never disagree")
        void sameRuleAsPreflight() {
            String a = "lib-alpha-1.0.jar";
            String b = "lib-alpha-2.0.jar";
            assertEquals(TomcatPreflightValidator.extractJarBaseName(a), TomcatPreflightValidator.extractJarBaseName(b));
            assertEquals(List.of(), ClasspathDuplicateWarning.withoutSameLibraryVersions(
                    List.of(group("x.properties", "WEB-INF/lib/" + a, "WEB-INF/lib/" + b))));
        }

        @Test
        @DisplayName("an unversioned jar beside a versioned one is the same library to both")
        void unversionedJar() {
            String plain = "lib-alpha.jar";
            String versioned = "lib-alpha-1.0.jar";
            assertEquals(TomcatPreflightValidator.extractJarBaseName(plain), TomcatPreflightValidator.extractJarBaseName(versioned));
            assertEquals(List.of(), ClasspathDuplicateWarning.withoutSameLibraryVersions(
                    List.of(group("org/example/A.class", "WEB-INF/lib/" + plain, "WEB-INF/lib/" + versioned))));
        }
    }

    @Nested
    @DisplayName("format")
    class Format {

        @Test
        @DisplayName("one line per location set, largest first, each path counted once")
        void groupedByLocationSet() {
            List<DuplicateGroup> groups = List.of(
                    group("app-config.xml", "WEB-INF/classes/", "WEB-INF/lib/lib-beta-1.0.jar"),
                    group("org/example/A.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-gamma-1.0.jar"),
                    group("org/example/B.class", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-gamma-1.0.jar"));

            String text = ClasspathDuplicateWarning.format("web-module", groups);
            List<String> lines = List.of(text.split("\n"));

            assertEquals("Classpath duplicates in deployed artifact 'web-module' — 3 paths are packaged more than once:", lines.get(0));
            assertEquals("  - 2 paths in WEB-INF/lib/lib-alpha-1.0.jar + WEB-INF/lib/lib-gamma-1.0.jar: org/example/A.class, org/example/B.class", lines.get(1));
            assertEquals("  - 1 path in WEB-INF/classes/ + WEB-INF/lib/lib-beta-1.0.jar: app-config.xml", lines.get(2));
            assertTrue(lines.get(3).startsWith("First match wins"), lines.get(3));
            assertEquals(4, lines.size());
        }

        @Test
        @DisplayName("a large group shows three examples, not the whole list")
        void largeGroupShowsExamples() {
            List<DuplicateGroup> groups = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                groups.add(group(String.format("org/example/C%02d.class", i), "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar"));
            }

            String text = ClasspathDuplicateWarning.format("web-module", groups);
            List<String> lines = List.of(text.split("\n"));

            assertEquals(3, lines.size(), text);
            assertEquals("  - 40 paths in WEB-INF/lib/lib-alpha-1.0.jar + WEB-INF/lib/lib-beta-1.0.jar"
                    + " (e.g. org/example/C00.class, org/example/C01.class, org/example/C02.class)", lines.get(1));
            assertFalse(text.contains("C03.class"));
        }

        @Test
        @DisplayName("three paths are all listed; four switch to examples")
        void exampleBoundary() {
            List<DuplicateGroup> three = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) three.add(group("p" + i + ".xml", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar"));
            assertTrue(ClasspathDuplicateWarning.format("web-module", three).contains(": p0.xml, p1.xml, p2.xml\n"));

            List<DuplicateGroup> four = new java.util.ArrayList<>(three);
            four.add(group("p3.xml", "WEB-INF/lib/lib-alpha-1.0.jar", "WEB-INF/lib/lib-beta-1.0.jar"));
            String text = ClasspathDuplicateWarning.format("web-module", four);
            assertTrue(text.contains(" (e.g. p0.xml, p1.xml, p2.xml)\n"), text);
            assertFalse(text.contains("p3.xml"));
        }

        @Test
        @DisplayName("singular wording for one path")
        void singular() {
            String text = ClasspathDuplicateWarning.format("web-module",
                    List.of(group("app-config.xml", "WEB-INF/classes/", "WEB-INF/lib/lib-beta-1.0.jar")));
            assertTrue(text.startsWith("Classpath duplicates in deployed artifact 'web-module' — 1 path is packaged more than once:"), text);
            assertTrue(text.contains("\n  - 1 path in WEB-INF/classes/ + WEB-INF/lib/lib-beta-1.0.jar: app-config.xml\n"), text);
        }
    }
}
