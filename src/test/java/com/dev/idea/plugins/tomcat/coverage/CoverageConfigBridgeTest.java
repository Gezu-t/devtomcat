package com.dev.idea.plugins.tomcat.coverage;

import com.dev.idea.plugins.tomcat.model.CoverageConfig;
import com.intellij.ui.classFilter.ClassFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link CoverageConfigBridge} — the translation layer
 * between DevTomcat's user-facing include/exclude strings and IntelliJ's
 * {@link ClassFilter} array shape. Keeping this layer separately tested
 * means a regression in pattern hygiene surfaces immediately without
 * spinning up the IntelliJ platform.
 */
@DisplayName("CoverageConfigBridge")
class CoverageConfigBridgeTest {

    @Nested
    @DisplayName("toClassFilters")
    class ToClassFilters {

        @Test
        @DisplayName("includes become INCLUDE=true, excludes become INCLUDE=false — both ENABLED")
        void splitsIncludesAndExcludes() {
            CoverageConfig c = new CoverageConfig();
            c.setIncludePatterns(Arrays.asList("com.foo.*", "com.foo.Bar"));
            c.setExcludePatterns(Arrays.asList("com.foo.test.*"));

            ClassFilter[] filters = CoverageConfigBridge.toClassFilters(c);

            assertEquals(3, filters.length);
            assertEquals("com.foo.*", filters[0].getPattern());
            assertTrue(filters[0].isInclude());
            assertTrue(filters[0].isEnabled());

            assertEquals("com.foo.Bar", filters[1].getPattern());
            assertTrue(filters[1].isInclude());
            assertTrue(filters[1].isEnabled());

            assertEquals("com.foo.test.*", filters[2].getPattern());
            assertFalse(filters[2].isInclude());
            assertTrue(filters[2].isEnabled());
        }

        @Test
        @DisplayName("drops null, blank, and whitespace-only entries")
        void dropsNullsAndBlanks() {
            CoverageConfig c = new CoverageConfig();
            c.setIncludePatterns(Arrays.asList("com.foo.*", null, "", "   ", "com.bar.*"));
            c.setExcludePatterns(Arrays.asList(null, "\t"));

            ClassFilter[] filters = CoverageConfigBridge.toClassFilters(c);

            // Nulls/blanks silently matching everything is a correctness bug —
            // an empty include pattern instructs most coverage agents to
            // match any class. Drop them at the boundary.
            assertEquals(2, filters.length);
            assertEquals("com.foo.*", filters[0].getPattern());
            assertEquals("com.bar.*", filters[1].getPattern());
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void trimsWhitespace() {
            CoverageConfig c = new CoverageConfig();
            c.setIncludePatterns(Arrays.asList("  com.foo.*  ", "\tcom.bar.*\n"));

            ClassFilter[] filters = CoverageConfigBridge.toClassFilters(c);

            assertEquals(2, filters.length);
            assertEquals("com.foo.*", filters[0].getPattern());
            assertEquals("com.bar.*", filters[1].getPattern());
        }

        @Test
        @DisplayName("deduplicates within a single list (includes, then excludes)")
        void deduplicatesWithinList() {
            CoverageConfig c = new CoverageConfig();
            c.setIncludePatterns(Arrays.asList("com.foo.*", "com.foo.*", "com.bar.*"));
            c.setExcludePatterns(Arrays.asList("com.test.*", "com.test.*"));

            ClassFilter[] filters = CoverageConfigBridge.toClassFilters(c);

            assertEquals(3, filters.length);
            assertEquals("com.foo.*", filters[0].getPattern());
            assertEquals("com.bar.*", filters[1].getPattern());
            assertEquals("com.test.*", filters[2].getPattern());
        }

        @Test
        @DisplayName("empty config produces empty array, not null")
        void emptyConfigProducesEmptyArray() {
            ClassFilter[] filters = CoverageConfigBridge.toClassFilters(new CoverageConfig());
            assertNotNull(filters);
            assertEquals(0, filters.length);
        }
    }

    @Nested
    @DisplayName("fromClassFilters")
    class FromClassFilters {

        @Test
        @DisplayName("round-trips back into include/exclude string lists")
        void roundTripsByInclude() {
            ClassFilter include = enabledFilter("com.foo.*", true);
            ClassFilter exclude = enabledFilter("com.test.*", false);

            CoverageConfig reconstructed =
                    CoverageConfigBridge.fromClassFilters(new ClassFilter[]{include, exclude});

            assertEquals(List.of("com.foo.*"), reconstructed.getIncludePatterns());
            assertEquals(List.of("com.test.*"), reconstructed.getExcludePatterns());
        }

        @Test
        @DisplayName("drops disabled filters rather than silently re-enabling them")
        void dropsDisabledFilters() {
            // Platform keeps disabled entries for history; DevTomcat's string
            // model has no enabled/disabled concept, so round-tripping them
            // would flip them back on.
            ClassFilter enabled = enabledFilter("com.foo.*", true);
            ClassFilter disabled = new ClassFilter("com.bar.*");
            disabled.setInclude(true);
            disabled.setEnabled(false);

            CoverageConfig reconstructed =
                    CoverageConfigBridge.fromClassFilters(new ClassFilter[]{enabled, disabled});

            assertEquals(List.of("com.foo.*"), reconstructed.getIncludePatterns());
            assertEquals(List.of(), reconstructed.getExcludePatterns());
        }

        @Test
        @DisplayName("null filter entries are tolerated, not a crash")
        void tolerantOfNullEntries() {
            ClassFilter real = enabledFilter("com.foo.*", true);
            CoverageConfig reconstructed =
                    CoverageConfigBridge.fromClassFilters(new ClassFilter[]{null, real, null});

            assertEquals(List.of("com.foo.*"), reconstructed.getIncludePatterns());
        }
    }

    @Nested
    @DisplayName("pattern string extractors")
    class PatternStringExtractors {

        @Test
        @DisplayName("includePatternStrings returns only enabled include patterns")
        void includePatternStringsFiltersCorrectly() {
            ClassFilter include = enabledFilter("com.foo.*", true);
            ClassFilter exclude = enabledFilter("com.test.*", false);
            ClassFilter disabledInclude = new ClassFilter("com.disabled.*");
            disabledInclude.setInclude(true);
            disabledInclude.setEnabled(false);

            String[] includes = CoverageConfigBridge.includePatternStrings(
                    new ClassFilter[]{include, exclude, disabledInclude});

            assertArrayEquals(new String[]{"com.foo.*"}, includes);
        }

        @Test
        @DisplayName("excludePatternStrings returns only enabled exclude patterns")
        void excludePatternStringsFiltersCorrectly() {
            ClassFilter include = enabledFilter("com.foo.*", true);
            ClassFilter exclude1 = enabledFilter("com.test.*", false);
            ClassFilter exclude2 = enabledFilter("com.legacy.*", false);

            String[] excludes = CoverageConfigBridge.excludePatternStrings(
                    new ClassFilter[]{include, exclude1, exclude2});

            assertArrayEquals(new String[]{"com.test.*", "com.legacy.*"}, excludes);
        }

        @Test
        @DisplayName("empty array returns empty, not null")
        void emptyArray() {
            assertEquals(0, CoverageConfigBridge.includePatternStrings(new ClassFilter[0]).length);
            assertEquals(0, CoverageConfigBridge.excludePatternStrings(new ClassFilter[0]).length);
        }
    }

    private static ClassFilter enabledFilter(String pattern, boolean include) {
        ClassFilter f = new ClassFilter(pattern);
        f.setInclude(include);
        f.setEnabled(true);
        return f;
    }
}
