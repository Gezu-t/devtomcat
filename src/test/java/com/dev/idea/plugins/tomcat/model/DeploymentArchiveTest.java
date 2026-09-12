package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Contract: never name an archive nothing established. */
@DisplayName("DeploymentArchive")
class DeploymentArchiveTest {

    @Nested
    @DisplayName("reading an archive off a name or path")
    class Reading {

        @Test
        @DisplayName("recognises the war and ear extensions, in any case")
        void recognisesExtensions() {
            assertEquals(DeploymentArchive.WAR, DeploymentArchive.ofFileName("web-module.war"));
            assertEquals(DeploymentArchive.WAR, DeploymentArchive.ofFileName("web-module.WAR"));
            assertEquals(DeploymentArchive.EAR, DeploymentArchive.ofFileName("suite.ear"));
            assertEquals(DeploymentArchive.EAR, DeploymentArchive.ofFileName("suite.Ear"));
        }

        @Test
        @DisplayName("an extension-less directory establishes nothing")
        void directoryIsUnknown() {
            // An exploded tree carries no marker saying which archive it came from.
            // Guessing war here is the defect this type was introduced to prevent.
            assertEquals(DeploymentArchive.UNKNOWN, DeploymentArchive.ofFileName("web-module"));
            assertEquals(DeploymentArchive.UNKNOWN,
                    DeploymentArchive.ofPath(Path.of("/projects/X/build/web-module")));
        }

        @Test
        @DisplayName("a non-deployable extension establishes nothing")
        void otherExtensionIsUnknown() {
            assertEquals(DeploymentArchive.UNKNOWN, DeploymentArchive.ofFileName("shared-lib.jar"));
            assertEquals(DeploymentArchive.UNKNOWN, DeploymentArchive.ofFileName("notes.txt"));
        }

        @Test
        @DisplayName("reads the last path segment, not the whole path")
        void readsLastSegment() {
            // A parent directory named like an archive must not decide the answer.
            assertEquals(DeploymentArchive.UNKNOWN,
                    DeploymentArchive.ofPath(Path.of("/projects/app.war/staging/exploded")));
            assertEquals(DeploymentArchive.WAR,
                    DeploymentArchive.ofPath(Path.of("/projects/build/app-1.0.war")));
        }

        @Test
        @DisplayName("null-safe")
        void nullSafe() {
            assertEquals(DeploymentArchive.UNKNOWN, DeploymentArchive.ofFileName(null));
            assertEquals(DeploymentArchive.UNKNOWN, DeploymentArchive.ofPath(null));
        }
    }

    @Nested
    @DisplayName("display suffix")
    class Suffix {

        @Test
        @DisplayName("spells out both axes independently")
        void bothAxes() {
            assertEquals(TomcatConstants.ARTIFACT_SUFFIX_WAR_EXPLODED,
                    DeploymentArchive.WAR.displaySuffix(true));
            assertEquals(TomcatConstants.ARTIFACT_SUFFIX_WAR,
                    DeploymentArchive.WAR.displaySuffix(false));
            assertEquals(TomcatConstants.ARTIFACT_SUFFIX_EAR_EXPLODED,
                    DeploymentArchive.EAR.displaySuffix(true));
            assertEquals(TomcatConstants.ARTIFACT_SUFFIX_EAR,
                    DeploymentArchive.EAR.displaySuffix(false));
        }

        @Test
        @DisplayName("UNKNOWN yields no suffix in either packing")
        void unknownHasNoSuffix() {
            // Not ":war", and not ":war exploded" either — the caller renders the
            // bare name, which is the point.
            assertNull(DeploymentArchive.UNKNOWN.displaySuffix(true));
            assertNull(DeploymentArchive.UNKNOWN.displaySuffix(false));
        }
    }
}
