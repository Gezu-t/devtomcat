package com.dev.idea.plugins.tomcat.serviceview;

import com.dev.idea.plugins.tomcat.model.Deployment;
import com.dev.idea.plugins.tomcat.model.DeploymentPointers;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatArtifactItem identity")
class TomcatArtifactItemTest {

    /** Headless typed fixture — name-only module pointer, no platform services. */
    private static Deployment deployment(String name, String contextPath, boolean exploded) {
        return new ModuleBackedDeployment(
                DeploymentPointers.detachedModuleRef(name),
                Path.of("/some/path"), contextPath, exploded);
    }

    private static TomcatArtifactItem item(Deployment d, String host, int port) {
        return new TomcatArtifactItem(d, "cfg", host, false, port, null);
    }

    @Test
    @DisplayName("same display name but different context path are distinct rows")
    void differentContextPathIsDistinct() {
        TomcatArtifactItem one = item(deployment("app", "/one", false), "localhost", 8080);
        TomcatArtifactItem two = item(deployment("app", "/two", false), "localhost", 8080);
        assertNotEquals(one, two, "same name, different context path must not collapse to one node");
    }

    @Test
    @DisplayName("same display name but different packaging are distinct rows")
    void differentTypeIsDistinct() {
        TomcatArtifactItem war = item(deployment("app", "/app", false), "localhost", 8080);
        TomcatArtifactItem exploded = item(deployment("app", "/app", true), "localhost", 8080);
        assertNotEquals(war, exploded);
    }

    @Test
    @DisplayName("identity ignores mutable state (host/port); equal items have equal hashCodes")
    void identityIgnoresMutableState() {
        TomcatArtifactItem a = item(deployment("app", "/app", false), "localhost", 8080);
        TomcatArtifactItem b = item(deployment("app", "/app", false), "other-host", 9090);
        assertEquals(a, b, "host/port are state, not identity — same row");
        assertEquals(a.hashCode(), b.hashCode(), "equal items must have equal hashCodes");
    }
}
