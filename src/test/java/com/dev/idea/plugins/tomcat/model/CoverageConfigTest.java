package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CoverageConfig")
class CoverageConfigTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("empty include patterns by default")
        void emptyInclude() {
            CoverageConfig config = new CoverageConfig();
            assertTrue(config.getIncludePatterns().isEmpty());
        }

        @Test
        @DisplayName("empty exclude patterns by default")
        void emptyExclude() {
            CoverageConfig config = new CoverageConfig();
            assertTrue(config.getExcludePatterns().isEmpty());
        }
    }

    @Nested
    @DisplayName("setIncludePatterns")
    class SetIncludePatterns {

        @Test
        @DisplayName("sets patterns")
        void setsPatterns() {
            CoverageConfig config = new CoverageConfig();
            config.setIncludePatterns(Arrays.asList("com.example.*", "org.test.*"));
            assertEquals(2, config.getIncludePatterns().size());
            assertEquals("com.example.*", config.getIncludePatterns().get(0));
        }

        @Test
        @DisplayName("returns defensive copy")
        void defensiveCopy() {
            CoverageConfig config = new CoverageConfig();
            config.setIncludePatterns(Arrays.asList("com.example.*"));
            List<String> patterns = config.getIncludePatterns();
            patterns.add("extra");
            assertEquals(1, config.getIncludePatterns().size());
        }
    }

    @Nested
    @DisplayName("setExcludePatterns")
    class SetExcludePatterns {

        @Test
        @DisplayName("sets patterns")
        void setsPatterns() {
            CoverageConfig config = new CoverageConfig();
            config.setExcludePatterns(Arrays.asList("*.test.*", "*.generated.*"));
            assertEquals(2, config.getExcludePatterns().size());
        }

        @Test
        @DisplayName("returns defensive copy")
        void defensiveCopy() {
            CoverageConfig config = new CoverageConfig();
            config.setExcludePatterns(Arrays.asList("*.test.*"));
            List<String> patterns = config.getExcludePatterns();
            patterns.add("extra");
            assertEquals(1, config.getExcludePatterns().size());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            CoverageConfig original = new CoverageConfig();
            original.setIncludePatterns(Arrays.asList("com.example.*"));
            original.setExcludePatterns(Arrays.asList("*.test.*"));

            CoverageConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            CoverageConfig original = new CoverageConfig();
            original.setIncludePatterns(Arrays.asList("com.example.*"));

            CoverageConfig cloned = original.clone();
            cloned.setIncludePatterns(Arrays.asList("com.other.*"));

            assertEquals("com.example.*", original.getIncludePatterns().get(0));
            assertEquals("com.other.*", cloned.getIncludePatterns().get(0));
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same patterns are equal")
        void samePatternsEqual() {
            CoverageConfig a = new CoverageConfig();
            a.setIncludePatterns(Arrays.asList("com.*"));
            CoverageConfig b = new CoverageConfig();
            b.setIncludePatterns(Arrays.asList("com.*"));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different patterns not equal")
        void differentPatternsNotEqual() {
            CoverageConfig a = new CoverageConfig();
            a.setIncludePatterns(Arrays.asList("com.*"));
            CoverageConfig b = new CoverageConfig();
            b.setIncludePatterns(Arrays.asList("org.*"));
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("empty configs are equal")
        void emptyEqual() {
            assertEquals(new CoverageConfig(), new CoverageConfig());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes counts")
        void includesCounts() {
            CoverageConfig config = new CoverageConfig();
            config.setIncludePatterns(Arrays.asList("a", "b"));
            config.setExcludePatterns(Arrays.asList("c"));
            String str = config.toString();
            assertTrue(str.contains("include=2"));
            assertTrue(str.contains("exclude=1"));
        }
    }
}
