package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpdateConfig")
class UpdateConfigTest {

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("update action constants")
        void actionConstants() {
            assertEquals("update_resources", UpdateConfig.UPDATE_RESOURCES);
            assertEquals("update_classes_and_resources", UpdateConfig.UPDATE_CLASSES_AND_RESOURCES);
            assertEquals("redeploy", UpdateConfig.REDEPLOY);
            assertEquals("restart_server", UpdateConfig.RESTART_SERVER);
            assertEquals("do_nothing", UpdateConfig.DO_NOTHING);
        }

        @Test
        @DisplayName("default on update is update_classes_and_resources")
        void defaultOnUpdate() {
            assertEquals(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES, UpdateConfig.DEFAULT_ON_UPDATE);
        }

        @Test
        @DisplayName("default on frame deactivation is do_nothing")
        void defaultOnFrame() {
            assertEquals(UpdateConfig.DO_NOTHING, UpdateConfig.DEFAULT_ON_FRAME_DEACTIVATION);
        }
    }

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("sets default values")
        void defaultValues() {
            UpdateConfig config = new UpdateConfig();
            assertEquals(UpdateConfig.DEFAULT_ON_UPDATE, config.getOnUpdate());
            assertEquals(UpdateConfig.DEFAULT_ON_FRAME_DEACTIVATION, config.getOnFrameDeactivation());
            assertTrue(config.isShowUpdateDialog());
            assertTrue(config.isShowFrameDeactivationDialog());
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields")
        void copiesAll() {
            UpdateConfig original = new UpdateConfig();
            original.setOnUpdate(UpdateConfig.REDEPLOY);
            original.setOnFrameDeactivation(UpdateConfig.UPDATE_RESOURCES);
            original.setShowUpdateDialog(false);
            original.setShowFrameDeactivationDialog(false);

            UpdateConfig copy = new UpdateConfig(original);
            assertEquals(original.getOnUpdate(), copy.getOnUpdate());
            assertEquals(original.getOnFrameDeactivation(), copy.getOnFrameDeactivation());
            assertEquals(original.isShowUpdateDialog(), copy.isShowUpdateDialog());
            assertEquals(original.isShowFrameDeactivationDialog(), copy.isShowFrameDeactivationDialog());
        }
    }

    @Nested
    @DisplayName("setOnUpdate validation")
    class SetOnUpdateValidation {

        @Test
        @DisplayName("accepts valid action")
        void acceptsValid() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.REDEPLOY);
            assertEquals(UpdateConfig.REDEPLOY, config.getOnUpdate());
        }

        @Test
        @DisplayName("invalid action falls back to default")
        void invalidFallsBack() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate("invalid_action");
            assertEquals(UpdateConfig.DEFAULT_ON_UPDATE, config.getOnUpdate());
        }

        @Test
        @DisplayName("all valid actions accepted")
        void allValidActions() {
            UpdateConfig config = new UpdateConfig();
            for (String action : new String[]{
                    UpdateConfig.UPDATE_RESOURCES, UpdateConfig.UPDATE_CLASSES_AND_RESOURCES,
                    UpdateConfig.REDEPLOY, UpdateConfig.RESTART_SERVER, UpdateConfig.DO_NOTHING}) {
                config.setOnUpdate(action);
                assertEquals(action, config.getOnUpdate());
            }
        }
    }

    @Nested
    @DisplayName("setOnFrameDeactivation validation")
    class SetOnFrameDeactivationValidation {

        @Test
        @DisplayName("accepts valid action")
        void acceptsValid() {
            UpdateConfig config = new UpdateConfig();
            config.setOnFrameDeactivation(UpdateConfig.UPDATE_RESOURCES);
            assertEquals(UpdateConfig.UPDATE_RESOURCES, config.getOnFrameDeactivation());
        }

        @Test
        @DisplayName("invalid action falls back to default")
        void invalidFallsBack() {
            UpdateConfig config = new UpdateConfig();
            config.setOnFrameDeactivation("bad_action");
            assertEquals(UpdateConfig.DEFAULT_ON_UPDATE, config.getOnFrameDeactivation());
        }
    }

    @Nested
    @DisplayName("hot deployment queries")
    class HotDeploymentQueries {

        @Test
        @DisplayName("isHotDeploymentEnabled for update_classes_and_resources")
        void hotDeployClassesAndResources() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.UPDATE_CLASSES_AND_RESOURCES);
            assertTrue(config.isHotDeploymentEnabled());
            assertTrue(config.isUpdateClassesEnabled());
        }

        @Test
        @DisplayName("isHotDeploymentEnabled for update_resources")
        void hotDeployResources() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.UPDATE_RESOURCES);
            assertTrue(config.isHotDeploymentEnabled());
            assertFalse(config.isUpdateClassesEnabled());
        }

        @Test
        @DisplayName("redeploy is not hot deployment")
        void redeployNotHot() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.REDEPLOY);
            assertFalse(config.isHotDeploymentEnabled());
            assertTrue(config.isRedeployOnUpdate());
        }

        @Test
        @DisplayName("restart is not hot deployment")
        void restartNotHot() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.RESTART_SERVER);
            assertFalse(config.isHotDeploymentEnabled());
            assertTrue(config.isRestartOnUpdate());
        }

        @Test
        @DisplayName("do_nothing is not hot deployment")
        void doNothingNotHot() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.DO_NOTHING);
            assertFalse(config.isHotDeploymentEnabled());
        }
    }

    @Nested
    @DisplayName("frame deactivation queries")
    class FrameDeactivationQueries {

        @Test
        @DisplayName("do_nothing means inactive")
        void doNothingInactive() {
            UpdateConfig config = new UpdateConfig();
            assertFalse(config.isFrameDeactivationActive());
        }

        @Test
        @DisplayName("any action means active")
        void actionMeansActive() {
            UpdateConfig config = new UpdateConfig();
            config.setOnFrameDeactivation(UpdateConfig.UPDATE_RESOURCES);
            assertTrue(config.isFrameDeactivationActive());
        }
    }

    @Nested
    @DisplayName("resetToDefaults")
    class ResetToDefaults {

        @Test
        @DisplayName("resets all fields")
        void resetsAll() {
            UpdateConfig config = new UpdateConfig();
            config.setOnUpdate(UpdateConfig.RESTART_SERVER);
            config.setOnFrameDeactivation(UpdateConfig.REDEPLOY);
            config.setShowUpdateDialog(false);
            config.setShowFrameDeactivationDialog(false);

            config.resetToDefaults();
            assertEquals(UpdateConfig.DEFAULT_ON_UPDATE, config.getOnUpdate());
            assertEquals(UpdateConfig.DEFAULT_ON_FRAME_DEACTIVATION, config.getOnFrameDeactivation());
            assertTrue(config.isShowUpdateDialog());
            assertTrue(config.isShowFrameDeactivationDialog());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            UpdateConfig original = new UpdateConfig();
            original.setOnUpdate(UpdateConfig.REDEPLOY);
            UpdateConfig cloned = original.clone();
            assertEquals(original, cloned);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("same state is equal")
        void sameStateEqual() {
            UpdateConfig a = new UpdateConfig();
            UpdateConfig b = new UpdateConfig();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different onUpdate not equal")
        void differentOnUpdateNotEqual() {
            UpdateConfig a = new UpdateConfig();
            UpdateConfig b = new UpdateConfig();
            b.setOnUpdate(UpdateConfig.RESTART_SERVER);
            assertNotEquals(a, b);
        }
    }
}
