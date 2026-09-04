package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.model.ArtifactBackedDeployment;
import com.dev.idea.plugins.tomcat.model.ExternalFileDeployment;
import com.dev.idea.plugins.tomcat.model.ModuleBackedDeployment;
import com.intellij.openapi.module.Module;
import com.dev.idea.plugins.tomcat.model.ModuleRef;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.ArtifactPointer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link DeploymentModuleResolver#resolveAll} — the ALL-packaged-modules
 * variant that widens only the scoped hot-reload compile
 * ({@link DeploymentCompileScope}) while the single-module {@link
 * DeploymentModuleResolver#resolve} keeps backing the launch classpath.
 *
 * <p>Only the platform-free dispatch arms are exercised here (external → empty,
 * module-backed → its one module). The artifact-backed arm delegates to
 * {@code DeployedClassesSync.collectPackagedModules}, which is pinned by its
 * own mock-tree tests; its {@code ArtifactManager.getInstance} call needs a
 * platform fixture and is covered by integration.
 */
class DeploymentModuleResolverTest {

    private final Project project = mock(Project.class);

    @Test
    @DisplayName("external file deployment resolves to no modules")
    void externalIsEmpty() {
        ExternalFileDeployment d = new ExternalFileDeployment(Path.of("/tmp/x"), "/c", true);
        assertTrue(DeploymentModuleResolver.resolveAll(d, project).isEmpty());
    }

    @Test
    @DisplayName("module-backed deployment resolves to exactly its module")
    void moduleBackedResolvesToItsModule() {
        Module module = mock(Module.class);
        ModuleRef ptr = mock(ModuleRef.class);
        when(ptr.getModule()).thenReturn(module);
        ModuleBackedDeployment d = new ModuleBackedDeployment(ptr, Path.of("/out"), "/c", true);

        assertEquals(Set.of(module), DeploymentModuleResolver.resolveAll(d, project));
    }

    @Test
    @DisplayName("module-backed with a deleted module resolves to empty")
    void moduleBackedDeletedIsEmpty() {
        ModuleRef ptr = mock(ModuleRef.class);
        when(ptr.getModuleName()).thenReturn("gone");
        when(ptr.getModule()).thenReturn(null);
        ModuleBackedDeployment d = new ModuleBackedDeployment(ptr, Path.of("/out"), "/c", true);

        assertTrue(DeploymentModuleResolver.resolveAll(d, project).isEmpty());
    }

    @Test
    @DisplayName("artifact-backed with a deleted artifact resolves to empty (no platform call)")
    void artifactDeletedIsEmpty() {
        ArtifactPointer ptr = mock(ArtifactPointer.class);
        when(ptr.getArtifactName()).thenReturn("ghost");
        when(ptr.getArtifact()).thenReturn(null);
        ArtifactBackedDeployment d = new ArtifactBackedDeployment(ptr, "/c");

        assertTrue(DeploymentModuleResolver.resolveAll(d, project).isEmpty());
    }
}
