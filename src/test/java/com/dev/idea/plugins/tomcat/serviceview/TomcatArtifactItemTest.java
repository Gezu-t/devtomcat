package com.dev.idea.plugins.tomcat.serviceview;

import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatArtifactItem identity")
class TomcatArtifactItemTest {

    private static DeploymentArtifact artifact(String name, String contextPath, String type) {
        DeploymentArtifact a = new DeploymentArtifact(name, "/some/path", type);
        a.setContextPath(contextPath);
        return a;
    }

    private static TomcatArtifactItem item(DeploymentArtifact a, String host, int port) {
        return new TomcatArtifactItem(a, "cfg", host, false, port, null);
    }

    @Test
    @DisplayName("same display name but different context path are distinct rows")
    void differentContextPathIsDistinct() {
        TomcatArtifactItem one = item(artifact("app", "/one", DeploymentArtifact.TYPE_WAR), "localhost", 8080);
        TomcatArtifactItem two = item(artifact("app", "/two", DeploymentArtifact.TYPE_WAR), "localhost", 8080);
        assertNotEquals(one, two, "same name, different context path must not collapse to one node");
    }

    @Test
    @DisplayName("same display name but different type are distinct rows")
    void differentTypeIsDistinct() {
        TomcatArtifactItem war = item(artifact("app", "/app", DeploymentArtifact.TYPE_WAR), "localhost", 8080);
        TomcatArtifactItem exploded = item(artifact("app", "/app", DeploymentArtifact.TYPE_EXPLODED), "localhost", 8080);
        assertNotEquals(war, exploded);
    }

    @Test
    @DisplayName("identity ignores mutable state (host/port); equal items have equal hashCodes")
    void identityIgnoresMutableState() {
        TomcatArtifactItem a = item(artifact("app", "/app", DeploymentArtifact.TYPE_WAR), "localhost", 8080);
        TomcatArtifactItem b = item(artifact("app", "/app", DeploymentArtifact.TYPE_WAR), "other-host", 9090);
        assertEquals(a, b, "host/port are state, not identity — same row");
        assertEquals(a.hashCode(), b.hashCode(), "equal items must have equal hashCodes");
    }
}
