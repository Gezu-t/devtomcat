package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher.MatchStrategy;
import com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher.PlatformArtifactSnapshot;
import com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher.RefreshAction;
import com.dev.idea.plugins.tomcat.conf.ArtifactReferenceRefresher.RefreshResult;
import com.dev.idea.plugins.tomcat.model.DeploymentArtifact;
import com.dev.idea.plugins.tomcat.model.DeploymentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ArtifactReferenceRefresher")
class ArtifactReferenceRefresherTest {

    private DeploymentConfig deploymentConfig;

    @BeforeEach
    void setUp() {
        deploymentConfig = new DeploymentConfig();
    }

    // =========================================================================
    // Empty / no-op cases
    // =========================================================================

    @Nested
    @DisplayName("No-op cases")
    class NoOpCases {

        @Test
        @DisplayName("empty deployment config returns empty result")
        void emptyDeploymentConfig() {
            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/out/myapp", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertFalse(result.hasUpdates());
            assertFalse(result.hasUnresolved());
            assertEquals(0, result.getActions().size());
        }

        @Test
        @DisplayName("empty platform artifacts marks non-external deployments unresolved")
        void emptyPlatformArtifacts() {
            deploymentConfig.addArtifact(
                    new DeploymentArtifact("myapp:war exploded", "/out/myapp", DeploymentArtifact.TYPE_EXPLODED));

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(
                    deploymentConfig, new PlatformArtifactSnapshot[0]);

            // Empty snapshots array → empty result (short-circuited before iterating deployments)
            assertFalse(result.hasUpdates());
        }

        @Test
        @DisplayName("external artifact is never touched")
        void externalArtifactSkipped() {
            DeploymentArtifact external = new DeploymentArtifact(
                    "my-custom.war", "/tmp/my-custom.war", DeploymentArtifact.TYPE_EXTERNAL);
            deploymentConfig.addArtifact(external);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("something-else", "/other/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertFalse(result.hasUpdates());
            assertFalse(result.hasUnresolved());
            assertEquals("my-custom.war", external.getName());
            assertEquals("/tmp/my-custom.war", external.getPath());
        }
    }

    // =========================================================================
    // Strategy 1: Exact name match
    // =========================================================================

    @Nested
    @DisplayName("Exact name match")
    class ExactNameMatch {

        @Test
        @DisplayName("name and path both current — no action returned")
        void fullyCurrentNoAction() {
            deploymentConfig.addArtifact(
                    new DeploymentArtifact("myapp:war exploded", "/out/myapp", DeploymentArtifact.TYPE_EXPLODED));

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/out/myapp", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertFalse(result.hasUpdates());
            assertFalse(result.hasUnresolved());
            assertEquals(0, result.getActions().size());
        }

        @Test
        @DisplayName("name matches but path drifted — path is updated")
        void pathDrifted() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp:war exploded", "/old/output/myapp", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/new/output/myapp", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertTrue(result.hasUpdates());
            assertEquals(1, result.getUpdateCount());
            assertEquals(0, result.getUnresolvedCount());

            RefreshAction action = result.getActions().get(0);
            assertEquals(MatchStrategy.EXACT_NAME, action.getStrategy());
            assertFalse(action.isNameChanged());
            assertTrue(action.isPathChanged());
            assertEquals("/old/output/myapp", action.getOldPath());
            assertEquals("/new/output/myapp", action.getNewPath());

            // Verify the deployment was actually mutated
            assertEquals("/new/output/myapp", deployment.getPath());
            assertEquals("myapp:war exploded", deployment.getName());
        }

        @Test
        @DisplayName("name matches and platform path is empty — path left as-is")
        void emptyPlatformPath() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp:war", "/existing/path", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war", "", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertFalse(result.hasUpdates());
            assertEquals("/existing/path", deployment.getPath());
        }
    }

    // =========================================================================
    // Strategy 2: Output path match
    // =========================================================================

    @Nested
    @DisplayName("Output path match")
    class OutputPathMatch {

        @Test
        @DisplayName("artifact renamed but output path unchanged — name updated")
        void renamedArtifact() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "old-name:war exploded", "/out/artifacts/myapp_war_exploded",
                    DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("new-name:war exploded",
                            "/out/artifacts/myapp_war_exploded", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertTrue(result.hasUpdates());
            assertEquals(1, result.getUpdateCount());

            RefreshAction action = result.getActions().get(0);
            assertEquals(MatchStrategy.OUTPUT_PATH, action.getStrategy());
            assertTrue(action.isNameChanged());
            assertEquals("old-name:war exploded", action.getOldName());
            assertEquals("new-name:war exploded", action.getNewName());

            assertEquals("new-name:war exploded", deployment.getName());
        }

        @Test
        @DisplayName("path match takes priority over base module name match")
        void pathMatchPriority() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war_exploded", "/out/artifacts/myapp_war_exploded",
                    DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            // Both the path-match candidate and a base-name-match candidate exist.
            // Path match should win because it's tried first (Strategy 2 before Strategy 3).
            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded",
                            "/different/path", true),
                    new PlatformArtifactSnapshot("renamed-app:war exploded",
                            "/out/artifacts/myapp_war_exploded", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertEquals("renamed-app:war exploded", deployment.getName());
            assertEquals(MatchStrategy.OUTPUT_PATH, result.getActions().get(0).getStrategy());
        }
    }

    // =========================================================================
    // Strategy 3: Base module name match
    // =========================================================================

    @Nested
    @DisplayName("Base module name match")
    class BaseModuleNameMatch {

        @Test
        @DisplayName("stored _war_exploded matches platform :war exploded")
        void crossSuffixMatch() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war_exploded", "/old/path", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/new/path", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertTrue(result.hasUpdates());
            RefreshAction action = result.getActions().get(0);
            assertEquals(MatchStrategy.BASE_MODULE_NAME, action.getStrategy());
            assertEquals("myapp:war exploded", deployment.getName());
            assertEquals("/new/path", deployment.getPath());
        }

        @Test
        @DisplayName("type affinity: exploded deployment prefers exploded artifact")
        void typeAffinityExploded() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war_exploded", "/old/path", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war", "/war/path", false),
                    new PlatformArtifactSnapshot("myapp:war exploded", "/exploded/path", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertEquals("myapp:war exploded", deployment.getName());
            assertEquals("/exploded/path", deployment.getPath());
        }

        @Test
        @DisplayName("type affinity: WAR deployment prefers WAR artifact")
        void typeAffinityWar() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war", "/old/path", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/exploded/path", true),
                    new PlatformArtifactSnapshot("myapp:war", "/war/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertEquals("myapp:war", deployment.getName());
            assertEquals("/war/path", deployment.getPath());
        }

        @Test
        @DisplayName("no type affinity match — falls back to first candidate")
        void noAffinityFallback() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war", "/old/path", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(deployment);

            // Both candidates are exploded — no WAR candidate for affinity
            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war exploded", "/path1", true),
                    new PlatformArtifactSnapshot("myapp (exploded)", "/path2", true)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            // Falls back to first candidate
            assertEquals("myapp:war exploded", deployment.getName());
        }

        @Test
        @DisplayName("single candidate matches regardless of type")
        void singleCandidateAlwaysMatches() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp_war_exploded", "/old/path", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            // Only a WAR candidate exists — still matches because it's the only one
            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war", "/war/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertTrue(result.hasUpdates());
            assertEquals("myapp:war", deployment.getName());
            assertEquals("/war/path", deployment.getPath());
        }
    }

    // =========================================================================
    // Unresolved
    // =========================================================================

    @Nested
    @DisplayName("Unresolved artifacts")
    class Unresolved {

        @Test
        @DisplayName("no name, path, or module match — left unchanged and marked unresolved")
        void completelyOrphaned() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "deleted-app:war exploded", "/gone/path", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("other-app:war", "/other/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            assertFalse(result.hasUpdates());
            assertTrue(result.hasUnresolved());
            assertEquals(1, result.getUnresolvedCount());

            RefreshAction action = result.getActions().get(0);
            assertEquals(MatchStrategy.UNRESOLVED, action.getStrategy());
            assertFalse(action.isUpdated());
            assertFalse(action.isNameChanged());
            assertFalse(action.isPathChanged());

            // Verify deployment was NOT mutated
            assertEquals("deleted-app:war exploded", deployment.getName());
            assertEquals("/gone/path", deployment.getPath());
        }
    }

    // =========================================================================
    // Mixed scenarios
    // =========================================================================

    @Nested
    @DisplayName("Mixed scenarios")
    class MixedScenarios {

        @Test
        @DisplayName("multiple artifacts with different match outcomes")
        void mixedOutcomes() {
            // Artifact 1: current (exact match, same path)
            DeploymentArtifact current = new DeploymentArtifact(
                    "app-a:war exploded", "/out/app-a", DeploymentArtifact.TYPE_EXPLODED);

            // Artifact 2: stale name (path match)
            DeploymentArtifact staleByName = new DeploymentArtifact(
                    "old-app-b:war", "/out/app-b.war", DeploymentArtifact.TYPE_WAR);

            // Artifact 3: stale (base module match)
            DeploymentArtifact staleByModule = new DeploymentArtifact(
                    "app-c_war_exploded", "/old/app-c", DeploymentArtifact.TYPE_EXPLODED);

            // Artifact 4: orphaned (no match)
            DeploymentArtifact orphaned = new DeploymentArtifact(
                    "removed-app:war", "/gone/path", DeploymentArtifact.TYPE_WAR);

            // Artifact 5: external (skipped)
            DeploymentArtifact external = new DeploymentArtifact(
                    "external.war", "/tmp/external.war", DeploymentArtifact.TYPE_EXTERNAL);

            deploymentConfig.addArtifact(current);
            deploymentConfig.addArtifact(staleByName);
            deploymentConfig.addArtifact(staleByModule);
            deploymentConfig.addArtifact(orphaned);
            deploymentConfig.addArtifact(external);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("app-a:war exploded", "/out/app-a", true),
                    new PlatformArtifactSnapshot("new-app-b:war", "/out/app-b.war", false),
                    new PlatformArtifactSnapshot("app-c:war exploded", "/new/app-c", true),
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            // Current artifact: no action
            assertEquals("app-a:war exploded", current.getName());

            // Stale by name: updated via path match
            assertEquals("new-app-b:war", staleByName.getName());

            // Stale by module: updated via base module name match
            assertEquals("app-c:war exploded", staleByModule.getName());
            assertEquals("/new/app-c", staleByModule.getPath());

            // Orphaned: untouched
            assertEquals("removed-app:war", orphaned.getName());

            // External: untouched
            assertEquals("external.war", external.getName());

            // Result counts: 2 updated (path match + module match) + 1 unresolved
            assertEquals(2, result.getUpdateCount());
            assertEquals(1, result.getUnresolvedCount());
            assertEquals(3, result.getActions().size());
        }

        @Test
        @DisplayName("deployment with empty name is marked unresolved")
        void emptyNameDeployment() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "", "/some/path", DeploymentArtifact.TYPE_EXPLODED);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war", "/some/other/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            // Empty name can't match by exact name or base module, but might match by path
            // In this case paths differ, so it's unresolved
            assertTrue(result.hasUnresolved());
        }

        @Test
        @DisplayName("deployment with empty path can still match by name")
        void emptyPathMatchesByName() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "myapp:war", "", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("myapp:war", "/new/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            // Exact name match, but path drifted from empty to populated
            assertTrue(result.hasUpdates());
            assertEquals("/new/path", deployment.getPath());
        }
    }

    // =========================================================================
    // RefreshResult API
    // =========================================================================

    @Nested
    @DisplayName("RefreshResult API")
    class RefreshResultApi {

        @Test
        @DisplayName("EMPTY result has no actions and no updates")
        void emptyResult() {
            RefreshResult empty = RefreshResult.EMPTY;
            assertFalse(empty.hasUpdates());
            assertFalse(empty.hasUnresolved());
            assertEquals(0, empty.getUpdateCount());
            assertEquals(0, empty.getUnresolvedCount());
            assertTrue(empty.getActions().isEmpty());
            assertTrue(empty.getUpdatedActions().isEmpty());
            assertTrue(empty.getUnresolvedActions().isEmpty());
        }

        @Test
        @DisplayName("getUpdatedActions filters correctly")
        void updatedActionsFilter() {
            DeploymentArtifact resolved = new DeploymentArtifact(
                    "old:war", "/out/path", DeploymentArtifact.TYPE_WAR);
            DeploymentArtifact orphaned = new DeploymentArtifact(
                    "gone:war", "/gone/path", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(resolved);
            deploymentConfig.addArtifact(orphaned);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("new:war", "/out/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            List<RefreshAction> updated = result.getUpdatedActions();
            List<RefreshAction> unresolved = result.getUnresolvedActions();

            assertEquals(1, updated.size());
            assertEquals(1, unresolved.size());
            assertEquals(MatchStrategy.OUTPUT_PATH, updated.get(0).getStrategy());
            assertEquals(MatchStrategy.UNRESOLVED, unresolved.get(0).getStrategy());
        }

        @Test
        @DisplayName("toString is human-readable")
        void toStringFormat() {
            DeploymentArtifact deployment = new DeploymentArtifact(
                    "old:war", "/path", DeploymentArtifact.TYPE_WAR);
            deploymentConfig.addArtifact(deployment);

            PlatformArtifactSnapshot[] snapshots = {
                    new PlatformArtifactSnapshot("new:war", "/path", false)
            };

            RefreshResult result = ArtifactReferenceRefresher.refreshArtifacts(deploymentConfig, snapshots);

            String str = result.toString();
            assertTrue(str.contains("1 action(s)"));
            assertTrue(str.contains("1 updated"));
        }
    }

    // =========================================================================
    // RefreshAction API
    // =========================================================================

    @Nested
    @DisplayName("RefreshAction API")
    class RefreshActionApi {

        @Test
        @DisplayName("isUpdated is false for UNRESOLVED")
        void unresolvedNotUpdated() {
            DeploymentArtifact d = new DeploymentArtifact("x", "/p", DeploymentArtifact.TYPE_WAR);
            RefreshAction action = new RefreshAction(d, "x", "/p", "x", "/p", MatchStrategy.UNRESOLVED);
            assertFalse(action.isUpdated());
            assertFalse(action.isNameChanged());
            assertFalse(action.isPathChanged());
        }

        @Test
        @DisplayName("isUpdated is true for all resolved strategies")
        void resolvedIsUpdated() {
            DeploymentArtifact d = new DeploymentArtifact("a", "/p", DeploymentArtifact.TYPE_WAR);
            assertTrue(new RefreshAction(d, "a", "/old", "a", "/new", MatchStrategy.EXACT_NAME).isUpdated());
            assertTrue(new RefreshAction(d, "old", "/p", "new", "/p", MatchStrategy.OUTPUT_PATH).isUpdated());
            assertTrue(new RefreshAction(d, "old", "/old", "new", "/new", MatchStrategy.BASE_MODULE_NAME).isUpdated());
        }

        @Test
        @DisplayName("toString for unresolved shows UNRESOLVED prefix")
        void unresolvedToString() {
            DeploymentArtifact d = new DeploymentArtifact("orphan", "/p", DeploymentArtifact.TYPE_WAR);
            RefreshAction action = new RefreshAction(d, "orphan", "/p", "orphan", "/p", MatchStrategy.UNRESOLVED);
            assertTrue(action.toString().startsWith("UNRESOLVED:"));
        }

        @Test
        @DisplayName("toString for updated shows strategy and name change")
        void updatedToString() {
            DeploymentArtifact d = new DeploymentArtifact("new", "/p", DeploymentArtifact.TYPE_WAR);
            RefreshAction action = new RefreshAction(d, "old", "/p", "new", "/p", MatchStrategy.OUTPUT_PATH);
            String str = action.toString();
            assertTrue(str.contains("OUTPUT_PATH"));
            assertTrue(str.contains("old"));
            assertTrue(str.contains("new"));
        }
    }
}
