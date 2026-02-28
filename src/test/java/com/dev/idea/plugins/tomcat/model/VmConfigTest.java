package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VmConfig")
class VmConfigTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("default VM options is empty")
        void defaultEmpty() {
            VmConfig config = new VmConfig();
            assertEquals("", config.getVmOptions());
            assertFalse(config.hasVmOptions());
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies VM options")
        void copiesOptions() {
            VmConfig original = new VmConfig();
            original.setVmOptions("-Xmx512m -Xms256m");
            VmConfig copy = new VmConfig(original);
            assertEquals("-Xmx512m -Xms256m", copy.getVmOptions());
        }
    }

    @Nested
    @DisplayName("setVmOptions")
    class SetVmOptions {

        @Test
        @DisplayName("sets non-null value")
        void setsValue() {
            VmConfig config = new VmConfig();
            config.setVmOptions("-Xmx1g");
            assertEquals("-Xmx1g", config.getVmOptions());
            assertTrue(config.hasVmOptions());
        }

        @Test
        @DisplayName("null sets empty string")
        void nullSetsEmpty() {
            VmConfig config = new VmConfig();
            config.setVmOptions("-Xmx1g");
            config.setVmOptions(null);
            assertEquals("", config.getVmOptions());
            assertFalse(config.hasVmOptions());
        }
    }

    @Nested
    @DisplayName("parseVmOptions")
    class ParseVmOptions {

        @Test
        @DisplayName("empty options returns empty list")
        void emptyReturnsEmpty() {
            VmConfig config = new VmConfig();
            assertTrue(config.parseVmOptions().isEmpty());
        }

        @Test
        @DisplayName("parses space-separated options")
        void parsesSpaceSeparated() {
            VmConfig config = new VmConfig();
            config.setVmOptions("-Xmx512m -Xms256m -XX:+UseG1GC");
            List<String> opts = config.parseVmOptions();
            assertEquals(3, opts.size());
            assertEquals("-Xmx512m", opts.get(0));
            assertEquals("-Xms256m", opts.get(1));
            assertEquals("-XX:+UseG1GC", opts.get(2));
        }

        @Test
        @DisplayName("handles multiple spaces")
        void handlesMultipleSpaces() {
            VmConfig config = new VmConfig();
            config.setVmOptions("  -Xmx1g   -Xms512m  ");
            List<String> opts = config.parseVmOptions();
            assertEquals(2, opts.size());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            VmConfig original = new VmConfig();
            original.setVmOptions("-Xmx1g");
            VmConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            VmConfig original = new VmConfig();
            original.setVmOptions("-Xmx1g");
            VmConfig cloned = original.clone();
            cloned.setVmOptions("-Xmx2g");
            assertEquals("-Xmx1g", original.getVmOptions());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same options are equal")
        void sameOptionsEqual() {
            VmConfig a = new VmConfig();
            a.setVmOptions("-Xmx1g");
            VmConfig b = new VmConfig();
            b.setVmOptions("-Xmx1g");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different options not equal")
        void differentNotEqual() {
            VmConfig a = new VmConfig();
            a.setVmOptions("-Xmx1g");
            VmConfig b = new VmConfig();
            b.setVmOptions("-Xmx2g");
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("empty shows empty")
        void emptyShowsEmpty() {
            assertTrue(new VmConfig().toString().contains("empty"));
        }

        @Test
        @DisplayName("non-empty shows set")
        void nonEmptyShowsSet() {
            VmConfig config = new VmConfig();
            config.setVmOptions("-Xmx1g");
            assertTrue(config.toString().contains("set"));
        }
    }
}
