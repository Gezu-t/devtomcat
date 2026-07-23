package com.dev.idea.plugins.tomcat.ui.deployment;

import com.intellij.openapi.module.Module;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the stale-artifact filter's identity comparison.
 *
 * <p>The filter drops artifacts whose source module no longer exists. Its two
 * sides are derived differently — the artifact side is stripped to a base name
 * ({@code web:war exploded} → {@code web}), the module side comes from the
 * project model — so the module side must carry EVERY spelling a module
 * answers to. Otherwise a project with qualified module names (Gradle's
 * default, Maven with "use qualified names") has every artifact judged
 * orphaned and the Deployment tab reports "no deployable artifacts found"
 * while the build output sits on disk.
 */
@DisplayName("ArtifactSelectionHandler stale-artifact filter")
class ArtifactSelectionHandlerTest {

    private static Module moduleNamed(String name) {
        Module m = mock(Module.class);
        when(m.getName()).thenReturn(name);
        return m;
    }

    @Nested
    @DisplayName("activeNameSpellings")
    class ActiveNameSpellings {

        @Test
        @DisplayName("a qualified module name also answers to its stem")
        void qualifiedNameIncludesStem() {
            Set<String> spellings = ArtifactSelectionHandler.activeNameSpellings(
                    moduleNamed("myapp.web"));

            assertTrue(spellings.contains("myapp.web"), "the raw name must stay");
            assertTrue(spellings.contains("web"),
                    "the stem is what an artifact's base name strips down to");
        }

        @Test
        @DisplayName("a plain module name yields itself and nothing empty")
        void plainName() {
            Set<String> spellings = ArtifactSelectionHandler.activeNameSpellings(
                    moduleNamed("web-module"));

            assertTrue(spellings.contains("web-module"));
            assertFalse(spellings.contains(""), "an empty spelling would match everything");
        }

        @Test
        @DisplayName("case is normalized — the filter compares lowercase")
        void caseNormalized() {
            assertTrue(ArtifactSelectionHandler.activeNameSpellings(moduleNamed("MyApp.Web"))
                    .contains("web"));
        }

        @Test
        @DisplayName("a trailing dot cannot produce an empty spelling")
        void trailingDotIsSafe() {
            assertFalse(ArtifactSelectionHandler.activeNameSpellings(moduleNamed("web."))
                    .contains(""));
        }
    }

    @Nested
    @DisplayName("hasActiveSourceModule")
    class HasActiveSourceModule {

        @Test
        @DisplayName("an artifact of a QUALIFIED-name module is kept, not filtered as orphaned")
        void qualifiedModuleArtifactSurvives() {
            // The regression: active set built from raw names only ({"myapp.web"})
            // never matches the stripped artifact base ("web"), so a perfectly
            // valid artifact was dropped and the user saw "no artifacts found".
            Set<String> active = ArtifactSelectionHandler.activeNameSpellings(
                    moduleNamed("myapp.web"));

            assertTrue(ArtifactSelectionHandler.hasActiveSourceModule("web:war exploded", active),
                    "a Gradle/qualified-name project must still offer its artifacts");
            assertTrue(ArtifactSelectionHandler.hasActiveSourceModule("web.war", active));
        }

        @Test
        @DisplayName("a genuinely orphaned artifact is still filtered out")
        void orphanStillFiltered() {
            Set<String> active = ArtifactSelectionHandler.activeNameSpellings(
                    moduleNamed("myapp.web"));

            assertFalse(ArtifactSelectionHandler.hasActiveSourceModule("deleted-module:war", active),
                    "the filter must still drop artifacts of removed/renamed modules");
        }

        @Test
        @DisplayName("an unstrippable name is kept — never filter what cannot be judged")
        void emptyBaseNameKept() {
            assertTrue(ArtifactSelectionHandler.hasActiveSourceModule("", Set.of("web")));
        }
    }
}
