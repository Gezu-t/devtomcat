package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DeploymentSource")
class DeploymentSourceTest {

    @Test
    @DisplayName("fromSerialized defaults to INTELLIJ_ARTIFACT for absent or unknown values")
    void fromSerializedDefaults() {
        assertEquals(DeploymentSource.INTELLIJ_ARTIFACT, DeploymentSource.fromSerialized(null));
        assertEquals(DeploymentSource.INTELLIJ_ARTIFACT, DeploymentSource.fromSerialized("bogus"));
    }

    @Test
    @DisplayName("fromSerialized round-trips every enum name")
    void fromSerializedRoundTrips() {
        assertEquals(DeploymentSource.INTELLIJ_ARTIFACT, DeploymentSource.fromSerialized("INTELLIJ_ARTIFACT"));
        assertEquals(DeploymentSource.AUTO_DETECTED, DeploymentSource.fromSerialized("AUTO_DETECTED"));
        assertEquals(DeploymentSource.EXTERNAL, DeploymentSource.fromSerialized("EXTERNAL"));
    }
}
