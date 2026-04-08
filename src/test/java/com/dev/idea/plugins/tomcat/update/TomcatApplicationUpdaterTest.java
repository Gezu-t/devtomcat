package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.UpdateConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-logic static methods in TomcatApplicationUpdater.
 *
 * <p>The instance methods (performUpdate, executeUpdate, etc.) require IntelliJ
 * Platform infrastructure (Project, CompilerManager, RunManager, etc.) and are not
 * tested here. Only the stateless static helper is covered.
 */
@DisplayName("TomcatApplicationUpdater")
class TomcatApplicationUpdaterTest {

    @Nested
    @DisplayName("mapActionToDisplay")
    class MapActionToDisplayTests {

        @Test
        @DisplayName("UPDATE_RESOURCES maps to 'Update resources'")
        void updateResources() {
            assertEquals("Update resources",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.UPDATE_RESOURCES));
        }

        @Test
        @DisplayName("UPDATE_CLASSES_AND_RESOURCES maps to 'Update classes and resources'")
        void updateClassesAndResources() {
            assertEquals("Update classes and resources",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES));
        }

        @Test
        @DisplayName("REDEPLOY maps to 'Redeploy'")
        void redeploy() {
            assertEquals("Redeploy",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.REDEPLOY));
        }

        @Test
        @DisplayName("RESTART_SERVER maps to 'Restart server'")
        void restartServer() {
            assertEquals("Restart server",
                    TomcatApplicationUpdater.mapActionToDisplay(UpdateConfig.RESTART_SERVER));
        }

        @Test
        @DisplayName("unknown action passes through unchanged")
        void unknownActionPassThrough() {
            assertEquals("some_custom_action",
                    TomcatApplicationUpdater.mapActionToDisplay("some_custom_action"));
        }

        @Test
        @DisplayName("empty string passes through unchanged")
        void emptyStringPassThrough() {
            assertEquals("",
                    TomcatApplicationUpdater.mapActionToDisplay(""));
        }
    }
}
