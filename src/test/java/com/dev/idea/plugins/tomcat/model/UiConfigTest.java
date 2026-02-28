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
        @DisplayName("show logs page false by default")
        void showLogsDefault() {
            assertFalse(new UiConfig().isShowLogsPage());
            assertFalse(UiConfig.DEFAULT_SHOW_LOGS_PAGE);
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
            original.setShowLogsPage(true); // implies activateToolWindow=true
            original.setAllowMultipleInstances(true);

            UiConfig copy = new UiConfig(original);
            assertEquals(original.isActivateToolWindow(), copy.isActivateToolWindow());
            assertEquals(original.isShowLogsPage(), copy.isShowLogsPage());
            assertEquals(original.isAllowMultipleInstances(), copy.isAllowMultipleInstances());
        }
    }

    @Nested
    @DisplayName("invariant: showLogsPage requires activateToolWindow")
    class Invariants {

        @Test
        @DisplayName("disabling tool window clears show logs page")
        void disablingToolWindowClearsLogs() {
            UiConfig config = new UiConfig();
            config.setShowLogsPage(true);
            assertTrue(config.isShowLogsPage());

            config.setActivateToolWindow(false);
            assertFalse(config.isShowLogsPage());
            assertFalse(config.isActivateToolWindow());
        }

        @Test
        @DisplayName("enabling show logs page implies activate tool window")
        void enableLogsImpliesActivate() {
            UiConfig config = new UiConfig();
            config.setActivateToolWindow(false);
            assertFalse(config.isActivateToolWindow());

            config.setShowLogsPage(true);
            assertTrue(config.isActivateToolWindow());
            assertTrue(config.isShowLogsPage());
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

        @Test
        @DisplayName("shouldShowLogsPage requires both flags")
        void shouldShowLogsPage() {
            UiConfig config = new UiConfig();
            assertFalse(config.shouldShowLogsPage()); // activateToolWindow=true, showLogsPage=false

            config.setShowLogsPage(true);
            assertTrue(config.shouldShowLogsPage()); // both true

            config.setActivateToolWindow(false);
            assertFalse(config.shouldShowLogsPage()); // activateToolWindow=false clears showLogsPage
        }
    }

    @Nested
    @DisplayName("resetToDefaults")
    class ResetToDefaults {

        @Test
        @DisplayName("resets all fields to defaults")
        void resetsAll() {
            UiConfig config = new UiConfig();
            config.setShowLogsPage(true);
            config.setAllowMultipleInstances(true);

            config.resetToDefaults();
            assertTrue(config.isActivateToolWindow());
            assertFalse(config.isShowLogsPage());
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
            original.setShowLogsPage(true);
            UiConfig cloned = original.clone();
            assertEquals(original, cloned);
        }

        @Test
        @DisplayName("clone is independent")
        void cloneIndependent() {
            UiConfig original = new UiConfig();
            original.setShowLogsPage(true);
            UiConfig cloned = original.clone();
            cloned.setActivateToolWindow(false);
            assertTrue(original.isShowLogsPage());
            assertFalse(cloned.isShowLogsPage());
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
            assertTrue(str.contains("showLogsPage="));
            assertTrue(str.contains("multiInstance="));
        }
    }
}
