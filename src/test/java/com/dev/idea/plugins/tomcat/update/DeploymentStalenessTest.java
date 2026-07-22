package com.dev.idea.plugins.tomcat.update;

import com.dev.idea.plugins.tomcat.logging.TomcatDeploymentLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the platform-free core of DeploymentStaleness: the
 * short-circuit newer-output walk, the three-valued verdict, and the
 * launch-path warning wiring (gate + balloon injected).
 */
@DisplayName("DeploymentStaleness")
class DeploymentStalenessTest {

    private static Path write(Path file, long mtimeMillis) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
        return file;
    }

    @Nested
    @DisplayName("findOutputNewerThan — existence of one strictly-newer file")
    class FindOutputNewerThan {

        @Test
        @DisplayName("a newer file deep in a root is found")
        void newerDeepFileFound(@TempDir Path tmp) throws Exception {
            Path root = tmp.resolve("out");
            write(root.resolve("a/b/App.class"), 50_000L);
            Path newer = write(root.resolve("a/b/c/Newer.class"), 300_000L);

            DeploymentStaleness.NewerFile hit =
                    DeploymentStaleness.findOutputNewerThan(200_000L, List.of(root));

            assertNotNull(hit);
            assertEquals(newer, hit.file());
            assertEquals(300_000L, hit.mtimeMillis());
        }

        @Test
        @DisplayName("all files at or before the target mtime → null (equal is NOT newer)")
        void equalOrOlderIsNotNewer(@TempDir Path tmp) throws Exception {
            Path root = tmp.resolve("out");
            write(root.resolve("Old.class"), 100_000L);
            write(root.resolve("Boundary.class"), 200_000L);

            assertNull(DeploymentStaleness.findOutputNewerThan(200_000L, List.of(root)),
                    "the contract is STRICTLY newer");
        }

        @Test
        @DisplayName("a missing root contributes no evidence")
        void missingRootIgnored(@TempDir Path tmp) {
            assertNull(DeploymentStaleness.findOutputNewerThan(
                    0L, List.of(tmp.resolve("does-not-exist"))));
        }

        @Test
        @DisplayName("the walk stops at the first hit — never a full-tree scan")
        void shortCircuitStopsAtFirstHit(@TempDir Path tmp) throws Exception {
            Path root = tmp.resolve("out");
            // Every file qualifies, so whichever is visited first terminates the walk.
            for (int i = 0; i < 5; i++) {
                write(root.resolve("p" + i + "/F" + i + ".class"), 300_000L + i);
            }
            AtomicInteger visited = new AtomicInteger();

            DeploymentStaleness.NewerFile hit = DeploymentStaleness.findOutputNewerThan(
                    100_000L, List.of(root), f -> visited.incrementAndGet());

            assertNotNull(hit);
            assertEquals(1, visited.get(),
                    "TERMINATE must fire on the first qualifying file");
        }
    }

    @Nested
    @DisplayName("verdictFor — three-valued outcome")
    class VerdictFor {

        @Test
        @DisplayName("no resolvable modules → UNKNOWN, never STALE")
        void emptyIsUnknown() {
            DeploymentStaleness.Verdict v = DeploymentStaleness.verdictFor(0L, Map.of());
            assertEquals(DeploymentStaleness.Kind.UNKNOWN, v.kind());
            assertFalse(v.isStale());
        }

        @Test
        @DisplayName("a newer output file yields STALE with the module named")
        void staleCarriesEvidence(@TempDir Path tmp) throws Exception {
            Path root = tmp.resolve("out");
            Path newer = write(root.resolve("App.class"), 500_000L);

            DeploymentStaleness.Verdict v = DeploymentStaleness.verdictFor(
                    200_000L, Map.of("web-module", List.of(root)));

            assertTrue(v.isStale());
            assertEquals("web-module", v.moduleName());
            assertEquals(newer, v.newerOutput());
            assertEquals(300_000L, v.newerByMillis());
        }

        @Test
        @DisplayName("all outputs older → FRESH")
        void olderOutputsAreFresh(@TempDir Path tmp) throws Exception {
            Path root = tmp.resolve("out");
            write(root.resolve("App.class"), 100_000L);

            assertEquals(DeploymentStaleness.Kind.FRESH,
                    DeploymentStaleness.verdictFor(200_000L,
                            Map.of("web-module", List.of(root))).kind());
        }
    }

    @Nested
    @DisplayName("warnStaleWarAtLaunch — console every launch, balloon once per session")
    class WarnAtLaunch {

        private final TomcatDeploymentLogger logger = mock(TomcatDeploymentLogger.class);

        @Test
        @DisplayName("stale: console warning plus one gated balloon; repeat launch is console-only")
        void staleWarnsOnceViaBalloon(@TempDir Path tmp) {
            DeploymentStaleness.Verdict stale = DeploymentStaleness.Verdict.stale(
                    "web-module", tmp.resolve("out/App.class"), 90_000L);
            SessionNotificationGate gate = new SessionNotificationGate();
            AtomicInteger balloons = new AtomicInteger();

            DeploymentStaleness.warnStaleWarAtLaunch("app-1.0.0.war", stale, logger,
                    gate, "config-1", (t, c) -> balloons.incrementAndGet());
            DeploymentStaleness.warnStaleWarAtLaunch("app-1.0.0.war", stale, logger,
                    gate, "config-1", (t, c) -> balloons.incrementAndGet());

            assertEquals(1, balloons.get());
            verify(logger, org.mockito.Mockito.times(2))
                    .logServerWarning(contains("stale WAR"));
        }

        @Test
        @DisplayName("fresh: fully silent")
        void freshIsSilent() {
            DeploymentStaleness.warnStaleWarAtLaunch("app-1.0.0.war",
                    DeploymentStaleness.Verdict.fresh(), logger,
                    new SessionNotificationGate(), "config-1",
                    (t, c) -> fail("no balloon for a fresh WAR"));
            verifyNoInteractions(logger);
        }
    }

    @Nested
    @DisplayName("describeAge — coarse human scale")
    class DescribeAge {

        @Test
        void boundaries() {
            assertEquals("moments", DeploymentStaleness.describeAge(400L));
            assertEquals("45 s", DeploymentStaleness.describeAge(45_000L));
            assertEquals("90 s", DeploymentStaleness.describeAge(90_000L));
            assertEquals("30 min", DeploymentStaleness.describeAge(30 * 60_000L));
            assertEquals("5 h", DeploymentStaleness.describeAge(5 * 3_600_000L));
            assertEquals("3 day(s)", DeploymentStaleness.describeAge(72 * 3_600_000L));
        }
    }
}
