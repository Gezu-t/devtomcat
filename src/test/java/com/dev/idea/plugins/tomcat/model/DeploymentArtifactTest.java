package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentArtifact")
class DeploymentArtifactTest {

    @Test
    @DisplayName("default constructor creates empty artifact")
    void defaultConstructor() {
        DeploymentArtifact art = new DeploymentArtifact();
        assertEquals("", art.getName());
        assertEquals("", art.getPath());
        assertEquals("war", art.getType());
        assertEquals("/", art.getContextPath());
        assertTrue(art.isDeployed());
    }

    @Test
    @DisplayName("three-arg constructor sets fields")
    void threeArgConstructor() {
        DeploymentArtifact art = new DeploymentArtifact("myapp", "/path/to/myapp.war", "war");
        assertEquals("myapp", art.getName());
        assertEquals("/path/to/myapp.war", art.getPath());
        assertEquals("war", art.getType());
    }

    @Test
    @DisplayName("setName normalizes null to empty")
    void setNameNull() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setName(null);
        assertEquals("", art.getName());
    }

    @Test
    @DisplayName("setType defaults to war on null")
    void setTypeNull() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setType(null);
        assertEquals("war", art.getType());
    }

    @Test
    @DisplayName("setContextPath defaults to / on null or empty")
    void setContextPathDefault() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setContextPath(null);
        assertEquals("/", art.getContextPath());
        art.setContextPath("/myapp");
        assertEquals("/myapp", art.getContextPath());
    }

    @Test
    @DisplayName("contextPath aliases work correctly")
    void contextPathAliases() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setApplicationContext("/app");
        assertEquals("/app", art.getApplicationContext());
        assertEquals("/app", art.getContextPath());
        assertEquals("/app", art.getServerPath());

        art.setServerPath("/other");
        assertEquals("/other", art.getContextPath());
    }

    @Test
    @DisplayName("isUsingDefaultContext checks / and empty")
    void isUsingDefaultContext() {
        DeploymentArtifact art = new DeploymentArtifact();
        assertTrue(art.isUsingDefaultContext());

        art.setContextPath("/myapp");
        assertFalse(art.isUsingDefaultContext());
    }

    @Test
    @DisplayName("getDisplayName falls back to path filename")
    void displayNameFallback() {
        DeploymentArtifact art = new DeploymentArtifact();
        art.setPath("/path/to/myapp.war");
        assertEquals("myapp.war", art.getDisplayName());

        art.setName("My Application");
        assertEquals("My Application", art.getDisplayName());
    }

    @Test
    @DisplayName("clone produces equal independent copy")
    void cloneIsIndependent() {
        DeploymentArtifact original = new DeploymentArtifact("app", "/path", "war");
        original.setContextPath("/app");
        original.setDeployed(false);

        DeploymentArtifact cloned = original.clone();
        assertEquals(original, cloned);

        cloned.setName("other");
        assertNotEquals(original, cloned);
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void equalsAndHashCode() {
        DeploymentArtifact a = new DeploymentArtifact("app", "/path", "war");
        a.setContextPath("/app");
        DeploymentArtifact b = new DeploymentArtifact("app", "/path", "war");
        b.setContextPath("/app");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setName("other");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("type constants are defined")
    void typeConstants() {
        assertEquals("war", DeploymentArtifact.TYPE_WAR);
        assertEquals("exploded", DeploymentArtifact.TYPE_EXPLODED);
        assertEquals("external", DeploymentArtifact.TYPE_EXTERNAL);
    }
}
