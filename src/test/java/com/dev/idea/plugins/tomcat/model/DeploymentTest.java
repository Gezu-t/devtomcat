package com.dev.idea.plugins.tomcat.model;

import com.intellij.openapi.module.Module;
import com.dev.idea.plugins.tomcat.model.ModuleRef;
import com.intellij.openapi.project.Project;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactPointer;
import com.intellij.packaging.artifacts.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 of the typed-deployment refactor: the sealed {@link Deployment}
 * hierarchy and its three concrete shapes. These tests pin down the
 * contracts (kind, context-path normalisation, equality, pointer
 * delegation) before any call-site migration begins.
 */
class DeploymentTest {

    private final Project project = mock(Project.class);

    @Nested
    @DisplayName("ExternalFileDeployment")
    class External {

        @Test
        @DisplayName("kind is EXTERNAL")
        void kind() {
            ExternalFileDeployment d = new ExternalFileDeployment(
                    Path.of("/tmp/x.war"), "/foo", false);
            assertEquals(DeploymentKind.EXTERNAL, d.getKind());
        }

        @Test
        @DisplayName("display name is the file's last segment")
        void displayName() {
            ExternalFileDeployment d = new ExternalFileDeployment(
                    Path.of("/a/b/c/my-app.war"), "/foo", false);
            assertEquals("my-app.war", d.getDisplayName());
        }

        @Test
        @DisplayName("resolved path is the external path verbatim")
        void resolvedPath() {
            Path p = Path.of("/x/y/z");
            ExternalFileDeployment d = new ExternalFileDeployment(p, "/foo", true);
            assertEquals(p, d.getResolvedPath());
        }

        @Test
        @DisplayName("isValid reflects file existence on disk")
        void isValid(@TempDir Path dir) throws Exception {
            Path present = Files.createFile(dir.resolve("real.war"));
            Path absent = dir.resolve("ghost.war");

            assertTrue(new ExternalFileDeployment(present, "/a", false).isValid());
            assertFalse(new ExternalFileDeployment(absent, "/a", false).isValid());
        }

        @Test
        @DisplayName("context path normalisation: empty → '/'")
        void emptyContextNormalised() {
            assertEquals("/",
                    new ExternalFileDeployment(Path.of("/x"), "", false).getContextPath());
            assertEquals("/",
                    new ExternalFileDeployment(Path.of("/x"), "   ", false).getContextPath());
        }

        @Test
        @DisplayName("context path normalisation: leading slash added if missing")
        void contextPrefixedWithSlash() {
            assertEquals("/foo",
                    new ExternalFileDeployment(Path.of("/x"), "foo", false).getContextPath());
            assertEquals("/foo",
                    new ExternalFileDeployment(Path.of("/x"), "/foo", false).getContextPath());
        }

        @Test
        @DisplayName("equality / hashCode based on (path, context, exploded)")
        void equalityContract() {
            ExternalFileDeployment a = new ExternalFileDeployment(Path.of("/x"), "/c", false);
            ExternalFileDeployment b = new ExternalFileDeployment(Path.of("/x"), "/c", false);
            ExternalFileDeployment c = new ExternalFileDeployment(Path.of("/x"), "/c", true);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }
    }

    @Nested
    @DisplayName("ArtifactBackedDeployment")
    class ArtifactBacked {

        @Test
        @DisplayName("kind is ARTIFACT")
        void kind() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("foo");
            assertEquals(DeploymentKind.ARTIFACT,
                    new ArtifactBackedDeployment(ptr, "/foo").getKind());
        }

        @Test
        @DisplayName("display name delegates to pointer's current name (rename-tracking)")
        void displayNameFromPointer() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("renamed-artifact");

            ArtifactBackedDeployment d = new ArtifactBackedDeployment(ptr, "/c");
            assertEquals("renamed-artifact", d.getDisplayName());
            assertEquals("renamed-artifact", d.getArtifactName());
        }

        @Test
        @DisplayName("resolved path comes from Artifact.getOutputFilePath")
        void resolvedPathFromArtifact() {
            Artifact artifact = mock(Artifact.class);
            when(artifact.getOutputFilePath()).thenReturn("/tmp/out/app");

            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("app");
            when(ptr.getArtifact()).thenReturn(artifact);

            ArtifactBackedDeployment d = new ArtifactBackedDeployment(ptr, "/c");
            assertEquals(Path.of("/tmp/out/app"), d.getResolvedPath());
        }

        @Test
        @DisplayName("resolved path is null when pointer can't find the artifact")
        void resolvedPathNullWhenArtifactMissing() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("ghost");
            when(ptr.getArtifact()).thenReturn(null);

            ArtifactBackedDeployment d = new ArtifactBackedDeployment(ptr, "/c");
            assertNull(d.getResolvedPath());
            assertFalse(d.isValid());
        }

        @Test
        @DisplayName("isExploded reads from artifact type id (substring match)")
        void isExplodedFromTypeId() {
            ArtifactType explodedType = mock(ArtifactType.class);
            when(explodedType.getId()).thenReturn("exploded-war");
            Artifact exploded = mock(Artifact.class);
            when(exploded.getArtifactType()).thenReturn(explodedType);

            ArtifactType packagedType = mock(ArtifactType.class);
            when(packagedType.getId()).thenReturn("war");
            Artifact packaged = mock(Artifact.class);
            when(packaged.getArtifactType()).thenReturn(packagedType);

            ArtifactPointer ptrExploded = mock(ArtifactPointer.class);
            when(ptrExploded.getArtifactName()).thenReturn("e");
            when(ptrExploded.getArtifact()).thenReturn(exploded);

            ArtifactPointer ptrPackaged = mock(ArtifactPointer.class);
            when(ptrPackaged.getArtifactName()).thenReturn("p");
            when(ptrPackaged.getArtifact()).thenReturn(packaged);

            assertTrue(new ArtifactBackedDeployment(ptrExploded, "/c").isExploded());
            assertFalse(new ArtifactBackedDeployment(ptrPackaged, "/c").isExploded());
        }

        @Test
        @DisplayName("isExploded on a generic-typed live artifact falls back to output shape")
        void isExplodedGenericTypeUsesOutputShape(@TempDir Path tempDir) {
            // Community Edition type ids ("plain"/"jar") carry no packaging
            // signal — the live verdict must come from the same full policy
            // that seeded lastKnownExploded at add time, not the id alone.
            ArtifactType plainType = mock(ArtifactType.class);
            when(plainType.getId()).thenReturn("plain");
            Artifact live = mock(Artifact.class);
            when(live.getArtifactType()).thenReturn(plainType);
            when(live.getName()).thenReturn("app-1.0.0");
            when(live.getOutputFilePath()).thenReturn(tempDir.toString());

            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("app-1.0.0");
            when(ptr.getArtifact()).thenReturn(live);

            // Stored packaging says war — the live directory output must win,
            // matching what add-time detection would have said.
            ArtifactBackedDeployment d = new ArtifactBackedDeployment(
                    ptr, "/c", tempDir.toString(), false);
            assertTrue(d.isExploded());
        }

        @Test
        @DisplayName("equality keys on the pointer's name + context path")
        void equality() {
            ArtifactPointer a = mock(ArtifactPointer.class);
            when(a.getArtifactName()).thenReturn("foo");
            ArtifactPointer b = mock(ArtifactPointer.class);
            when(b.getArtifactName()).thenReturn("foo");
            ArtifactPointer c = mock(ArtifactPointer.class);
            when(c.getArtifactName()).thenReturn("bar");

            assertEquals(new ArtifactBackedDeployment(a, "/p"),
                    new ArtifactBackedDeployment(b, "/p"));
            assertNotEquals(new ArtifactBackedDeployment(a, "/p"),
                    new ArtifactBackedDeployment(c, "/p"));
            assertNotEquals(new ArtifactBackedDeployment(a, "/p"),
                    new ArtifactBackedDeployment(a, "/q"));
        }
    }

    @Nested
    @DisplayName("ModuleBackedDeployment")
    class ModuleBacked {

        @Test
        @DisplayName("kind is MODULE")
        void kind() {
            ModuleRef ptr = mock(ModuleRef.class);
            when(ptr.getModuleName()).thenReturn("web-mod");
            ModuleBackedDeployment d = new ModuleBackedDeployment(
                    ptr, Path.of("/out"), "/c", true);
            assertEquals(DeploymentKind.MODULE, d.getKind());
        }

        @Test
        @DisplayName("getModule delegates to pointer")
        void moduleFromPointer() {
            Module module = mock(Module.class);
            ModuleRef ptr = mock(ModuleRef.class);
            when(ptr.getModuleName()).thenReturn("web-mod");
            when(ptr.getModule()).thenReturn(module);

            ModuleBackedDeployment d = new ModuleBackedDeployment(
                    ptr, Path.of("/out"), "/c", true);
            assertSame(module, d.getModule());
            assertEquals("web-mod", d.getDisplayName());
        }

        @Test
        @DisplayName("output path returned directly — does not consult the module")
        void outputPathDirect() {
            ModuleRef ptr = mock(ModuleRef.class);
            when(ptr.getModuleName()).thenReturn("m");

            Path out = Path.of("/some/output");
            ModuleBackedDeployment d = new ModuleBackedDeployment(ptr, out, "/c", true);
            assertEquals(out, d.getResolvedPath());
            assertEquals(out, d.getOutputPath());
        }

        @Test
        @DisplayName("isValid requires both module presence AND path existence")
        void isValidRequiresBoth(@TempDir Path dir) throws Exception {
            Path present = Files.createDirectory(dir.resolve("target-exploded"));
            Path absent = dir.resolve("nope");

            Module module = mock(Module.class);
            ModuleRef ptrLive = mock(ModuleRef.class);
            when(ptrLive.getModuleName()).thenReturn("m");
            when(ptrLive.getModule()).thenReturn(module);

            ModuleRef ptrStale = mock(ModuleRef.class);
            when(ptrStale.getModuleName()).thenReturn("m");
            when(ptrStale.getModule()).thenReturn(null);

            assertTrue(new ModuleBackedDeployment(ptrLive, present, "/c", true).isValid(),
                    "module present + path exists → valid");
            assertFalse(new ModuleBackedDeployment(ptrLive, absent, "/c", true).isValid(),
                    "module present but path missing → invalid");
            assertFalse(new ModuleBackedDeployment(ptrStale, present, "/c", true).isValid(),
                    "path exists but module deleted → invalid");
        }

        @Test
        @DisplayName("equality keys on (moduleName, output, context, exploded)")
        void equality() {
            ModuleRef a = mock(ModuleRef.class);
            when(a.getModuleName()).thenReturn("m");
            ModuleRef b = mock(ModuleRef.class);
            when(b.getModuleName()).thenReturn("m");

            assertEquals(new ModuleBackedDeployment(a, Path.of("/o"), "/c", true),
                    new ModuleBackedDeployment(b, Path.of("/o"), "/c", true));
            assertNotEquals(new ModuleBackedDeployment(a, Path.of("/o"), "/c", true),
                    new ModuleBackedDeployment(a, Path.of("/o"), "/c", false));
        }
    }

    @Nested
    @DisplayName("Sealed hierarchy: type-dispatch via instanceof patterns")
    class TypeDispatch {

        /**
         * Adding a new {@link Deployment} subclass requires updating this
         * method (otherwise the final {@code throw} fires at runtime, which
         * the tests below will catch). Java 17 doesn't support switch
         * patterns without preview, so we use {@code instanceof} chains —
         * less compile-time guarantee than a sealed switch, but still
         * keeps the dispatch table in one auditable place.
         */
        private String describe(Deployment d) {
            if (d instanceof ArtifactBackedDeployment a) return "artifact:" + a.getArtifactName();
            if (d instanceof ModuleBackedDeployment m)   return "module:" + m.getModuleName();
            if (d instanceof ExternalFileDeployment e)   return "external:" + e.getExternalPath();
            throw new IllegalStateException("Unhandled Deployment subtype: " + d.getClass());
        }

        @Test
        @DisplayName("ExternalFileDeployment dispatches to its branch")
        void external() {
            assertTrue(describe(new ExternalFileDeployment(Path.of("/x"), "/c", false))
                    .startsWith("external:"));
        }

        @Test
        @DisplayName("ArtifactBackedDeployment dispatches to its branch")
        void artifact() {
            ArtifactPointer ptr = mock(ArtifactPointer.class);
            when(ptr.getArtifactName()).thenReturn("the-art");
            assertEquals("artifact:the-art",
                    describe(new ArtifactBackedDeployment(ptr, "/c")));
        }

        @Test
        @DisplayName("ModuleBackedDeployment dispatches to its branch")
        void module() {
            ModuleRef ptr = mock(ModuleRef.class);
            when(ptr.getModuleName()).thenReturn("the-mod");
            assertEquals("module:the-mod",
                    describe(new ModuleBackedDeployment(ptr, Path.of("/o"), "/c", true)));
        }
    }
}
