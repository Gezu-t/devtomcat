package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Path;
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
        assertEquals(0, dc.getDeployments().size());
        assertFalse(dc.isHotDeploymentEnabled());
        assertFalse(dc.isUpdateClassesAndResources());
        assertFalse(dc.isPreserveSessions());
    }

    @Test
    @DisplayName("preserveSessions getter and setter")
    void preserveSessions() {
        DeploymentConfig dc = new DeploymentConfig();
        assertFalse(dc.isPreserveSessions());
        dc.setPreserveSessions(true);
        assertTrue(dc.isPreserveSessions());
    }

    @Nested
    @DisplayName("typed storage")
    class TypedStorage {

        private Deployment external() {
            return new ExternalFileDeployment(Path.of("/projects/X/app-1.0.0.war"), "/app", false);
        }

        private Deployment moduleBacked() {
            return new ModuleBackedDeployment(
                    DeploymentPointers.detachedModulePointer("web-module"),
                    Path.of("/projects/X/target/web-module"), "/web", true);
        }

        @Test
        @DisplayName("add and remove deployments, hasArtifacts tracks non-empty")
        void addRemove() {
            DeploymentConfig dc = new DeploymentConfig();
            Deployment d = external();

            assertTrue(dc.addDeployment(d));
            assertEquals(1, dc.getDeployments().size());
            assertTrue(dc.hasArtifacts());

            assertTrue(dc.removeDeployment(d));
            assertEquals(0, dc.getDeployments().size());
            assertFalse(dc.hasArtifacts());
        }

        @Test
        @DisplayName("clone shares nothing mutable with the original")
        void cloneIndependence() {
            DeploymentConfig original = new DeploymentConfig();
            original.addDeployment(external());
            original.setHotDeploymentEnabled(true);

            DeploymentConfig cloned = original.clone();
            assertEquals(original, cloned);
            assertEquals(1, cloned.getDeployments().size());
            assertTrue(cloned.isHotDeploymentEnabled());

            cloned.setDeployments(null);
            assertEquals(1, original.getDeployments().size(), "clearing the clone must not touch the original");
        }

        @Test
        @DisplayName("equals and hashCode are based on the typed list plus the three flags")
        void typedEqualsAndHashCode() {
            DeploymentConfig a = new DeploymentConfig();
            a.addDeployment(external());
            a.setHotDeploymentEnabled(true);
            DeploymentConfig b = new DeploymentConfig();
            b.addDeployment(external());
            b.setHotDeploymentEnabled(true);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());

            b.setHotDeploymentEnabled(false);
            assertNotEquals(a, b);

            b.setHotDeploymentEnabled(true);
            b.setPreserveSessions(true);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("addDeployment dedups on value equality, not identity")
        void addDeploymentDedupsByValue() {
            DeploymentConfig dc = new DeploymentConfig();
            assertTrue(dc.addDeployment(external()));
            assertFalse(dc.addDeployment(external()), "separately-built equal deployment must dedup");
            assertEquals(1, dc.getDeployments().size());

            assertTrue(dc.addDeployment(moduleBacked()), "different deployment must still add");
            assertTrue(dc.removeDeployment(moduleBacked()), "remove works on value equality too");
        }

        @Test
        @DisplayName("artifact-backed dedup ignores last-known path — identity is name + context")
        void artifactBackedDedupIgnoresLastKnownPath() {
            DeploymentConfig dc = new DeploymentConfig();
            assertTrue(dc.addDeployment(new ArtifactBackedDeployment(
                    DeploymentPointers.detachedArtifactPointer("app-1.0.0"),
                    "/a", "/projects/X/out", false)));
            assertFalse(dc.addDeployment(new ArtifactBackedDeployment(
                    DeploymentPointers.detachedArtifactPointer("app-1.0.0"),
                    "/a", "/projects/Y/out", false)),
                    "same artifact name + context must dedup even when the paths differ");
            assertEquals(1, dc.getDeployments().size());
        }

        @Test
        @DisplayName("setDeployments filters nulls and null clears")
        void setDeploymentsFiltersNulls() {
            DeploymentConfig dc = new DeploymentConfig();
            List<Deployment> list = new ArrayList<>();
            list.add(external());
            list.add(null);
            list.add(moduleBacked());

            dc.setDeployments(list);
            assertEquals(2, dc.getDeployments().size());

            dc.setDeployments(null);
            assertEquals(0, dc.getDeployments().size());
        }

        @Test
        @DisplayName("getDeployments returns a defensive snapshot")
        void getDeploymentsDefensiveCopy() {
            DeploymentConfig dc = new DeploymentConfig();
            dc.addDeployment(external());

            dc.getDeployments().clear();
            assertEquals(1, dc.getDeployments().size());
        }
    }
}
