package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentConfig")
class DeploymentConfigTest {

    @Test
    @DisplayName("defaults are empty and disabled")
    void defaultValues() {
        DeploymentConfig dc = new DeploymentConfig();
        assertFalse(dc.hasArtifacts());
        assertEquals(0, dc.getArtifactCount());
        assertFalse(dc.isHotDeploymentEnabled());
        assertFalse(dc.isUpdateClassesAndResources());
        assertFalse(dc.isPreserveSessions());
        assertEquals("", dc.getDeploymentPath());
    }

    @Test
    @DisplayName("add and remove artifacts")
    void addRemoveArtifacts() {
        DeploymentConfig dc = new DeploymentConfig();
        DeploymentArtifact art = new DeploymentArtifact("myapp", "/path/to/myapp.war", "war");

        assertTrue(dc.addArtifact(art));
        assertEquals(1, dc.getArtifactCount());
        assertTrue(dc.hasArtifacts());

        // Duplicate not added
        assertFalse(dc.addArtifact(art));
        assertEquals(1, dc.getArtifactCount());

        assertTrue(dc.removeArtifact(art));
        assertEquals(0, dc.getArtifactCount());
    }

    @Test
    @DisplayName("setArtifacts filters nulls")
    void setArtifactsFiltersNulls() {
        DeploymentConfig dc = new DeploymentConfig();
        List<DeploymentArtifact> list = new ArrayList<>();
        list.add(new DeploymentArtifact("app1", "/path1", "war"));
        list.add(null);
        list.add(new DeploymentArtifact("app2", "/path2", "war"));

        dc.setArtifacts(list);
        assertEquals(2, dc.getArtifactCount());
    }

    @Test
    @DisplayName("setArtifacts with null clears list")
    void setArtifactsNull() {
        DeploymentConfig dc = new DeploymentConfig();
        dc.addArtifact(new DeploymentArtifact("app", "/path", "war"));
        dc.setArtifacts(null);
        assertEquals(0, dc.getArtifactCount());
    }

    @Test
    @DisplayName("getArtifacts returns defensive copy")
    void getArtifactsDefensiveCopy() {
        DeploymentConfig dc = new DeploymentConfig();
        dc.addArtifact(new DeploymentArtifact("app", "/path", "war"));

        List<DeploymentArtifact> list = dc.getArtifacts();
        list.clear(); // Modify the returned list
        assertEquals(1, dc.getArtifactCount()); // Original unaffected
    }

    @Test
    @DisplayName("getArtifact by index bounds checking")
    void getArtifactByIndex() {
        DeploymentConfig dc = new DeploymentConfig();
        dc.addArtifact(new DeploymentArtifact("app", "/path", "war"));

        assertNotNull(dc.getArtifact(0));
        assertNull(dc.getArtifact(-1));
        assertNull(dc.getArtifact(1));
    }

    @Test
    @DisplayName("getArtifactByName finds correct artifact")
    void getArtifactByName() {
        DeploymentConfig dc = new DeploymentConfig();
        dc.addArtifact(new DeploymentArtifact("app1", "/path1", "war"));
        dc.addArtifact(new DeploymentArtifact("app2", "/path2", "war"));

        DeploymentArtifact found = dc.getArtifactByName("app2");
        assertNotNull(found);
        assertEquals("/path2", found.getPath());

        assertNull(dc.getArtifactByName("nonexistent"));
    }

    @Test
    @DisplayName("clone produces deep copy of artifacts")
    void cloneDeepCopiesArtifacts() {
        DeploymentConfig original = new DeploymentConfig();
        original.addArtifact(new DeploymentArtifact("app", "/path", "war"));
        original.setHotDeploymentEnabled(true);
        original.setDeploymentPath("/deploy");

        DeploymentConfig cloned = original.clone();
        assertEquals(original, cloned);
        assertEquals(1, cloned.getArtifactCount());
        assertTrue(cloned.isHotDeploymentEnabled());

        // Mutating clone doesn't affect original
        cloned.clearArtifacts();
        assertEquals(1, original.getArtifactCount());
    }

    @Test
    @DisplayName("copy constructor deep copies")
    void copyConstructor() {
        DeploymentConfig original = new DeploymentConfig();
        original.addArtifact(new DeploymentArtifact("app", "/path", "war"));
        original.setHotDeploymentEnabled(true);

        DeploymentConfig copy = new DeploymentConfig(original);
        assertEquals(1, copy.getArtifactCount());
        assertTrue(copy.isHotDeploymentEnabled());

        copy.clearArtifacts();
        assertEquals(1, original.getArtifactCount());
    }

    @Test
    @DisplayName("copy constructor with null creates empty config")
    void copyConstructorNull() {
        DeploymentConfig dc = new DeploymentConfig(null);
        assertFalse(dc.hasArtifacts());
        assertFalse(dc.isHotDeploymentEnabled());
    }

    @Test
    @DisplayName("removeArtifactAt returns removed artifact")
    void removeArtifactAt() {
        DeploymentConfig dc = new DeploymentConfig();
        DeploymentArtifact art = new DeploymentArtifact("app", "/path", "war");
        dc.addArtifact(art);

        DeploymentArtifact removed = dc.removeArtifactAt(0);
        assertNotNull(removed);
        assertEquals("app", removed.getName());
        assertEquals(0, dc.getArtifactCount());

        assertNull(dc.removeArtifactAt(0));
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void equalsAndHashCode() {
        DeploymentConfig a = new DeploymentConfig();
        a.addArtifact(new DeploymentArtifact("app", "/path", "war"));
        a.setHotDeploymentEnabled(true);

        DeploymentConfig b = new DeploymentConfig();
        b.addArtifact(new DeploymentArtifact("app", "/path", "war"));
        b.setHotDeploymentEnabled(true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setHotDeploymentEnabled(false);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("preserveSessions getter and setter")
    void preserveSessions() {
        DeploymentConfig dc = new DeploymentConfig();
        assertFalse(dc.isPreserveSessions());
        dc.setPreserveSessions(true);
        assertTrue(dc.isPreserveSessions());
    }
}
