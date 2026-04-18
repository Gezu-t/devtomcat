package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UiConfig")
class UiConfigTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("activate tool window true by default")
        void activateDefault() {
            assertTrue(new UiConfig().isActivateToolWindow());
            assertTrue(UiConfig.DEFAULT_ACTIVATE_TOOL_WINDOW);
        }

        @Test
        @DisplayName("allow multiple instances false by default")
        void multipleInstancesDefault() {
            assertFalse(new UiConfig().isAllowMultipleInstances());
            assertFalse(UiConfig.DEFAULT_ALLOW_MULTIPLE_INSTANCES);
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields")
        void copiesAll() {
            UiConfig original = new UiConfig();
            original.setActivateToolWindow(false);
            original.setAllowMultipleInstances(true);

            UiConfig copy = new UiConfig(original);
            assertEquals(original.isActivateToolWindow(), copy.isActivateToolWindow());
            assertEquals(original.isAllowMultipleInstances(), copy.isAllowMultipleInstances());
        }
    }

    @Nested
    @DisplayName("shouldShow methods")
    class ShouldShowMethods {

        @Test
        @DisplayName("shouldShowToolWindow follows activateToolWindow")
        void shouldShowToolWindow() {
            UiConfig config = new UiConfig();
            assertTrue(config.shouldShowToolWindow());
            config.setActivateToolWindow(false);
            assertFalse(config.shouldShowToolWindow());
        }
    }

    @Nested
    @DisplayName("resetToDefaults")
    class ResetToDefaults {

        @Test
        @DisplayName("resets all fields to defaults")
        void resetsAll() {
            UiConfig config = new UiConfig();
            config.setActivateToolWindow(false);
            config.setAllowMultipleInstances(true);

            config.resetToDefaults();
            assertTrue(config.isActivateToolWindow());
            assertFalse(config.isAllowMultipleInstances());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            UiConfig original = new UiConfig();
            original.setAllowMultipleInstances(true);
            UiConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            UiConfig original = new UiConfig();
            original.setActivateToolWindow(true);
            UiConfig cloned = original.clone();
            cloned.setActivateToolWindow(false);
            assertTrue(original.isActivateToolWindow());
            assertFalse(cloned.isActivateToolWindow());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same state is equal")
        void sameStateEqual() {
            UiConfig a = new UiConfig();
            UiConfig b = new UiConfig();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different state not equal")
        void differentStateNotEqual() {
            UiConfig a = new UiConfig();
            UiConfig b = new UiConfig();
            b.setAllowMultipleInstances(true);
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes all field names")
        void includesFields() {
            String str = new UiConfig().toString();
            assertTrue(str.contains("activate="));
            assertTrue(str.contains("multiInstance="));
        }
    }
}
