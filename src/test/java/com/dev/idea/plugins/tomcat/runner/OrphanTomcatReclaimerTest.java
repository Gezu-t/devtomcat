package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the testable parts of {@link OrphanTomcatReclaimer}:
 * the marker-construction and command-line-matching helpers.
 *
 * <p>The instance pipeline (process scan, polite/force termination, OS
 * sleep) cannot be unit-tested without mocking {@code ProcessHandle} —
 * those parts are exercised in integration via the full launch pipeline.
 *
 * <p>The matcher contract pinned here is the heart of orphan
 * identification, and a regression in it has two flavours of damage:
 * <ul>
 *   <li><b>Too eager</b> — kill a similarly-named config's process
 *       (e.g. {@code MyApp} reclaiming {@code MyApp2}). User loses work
 *       in the unrelated launch.</li>
 *   <li><b>Too narrow</b> — fail to catch a parallel-run child of the
 *       same config. The orphan keeps holding ports, and every relaunch
 *       pushes the seed further away from the user's intent.</li>
 * </ul>
 *
 * <p>Both are pinned with explicit cases below.
 */
@DisplayName("OrphanTomcatReclaimer — static helpers")
class OrphanTomcatReclaimerTest {

    @Nested
    @DisplayName("buildCatalinaBaseMarker(configBase)")
    class BuildCatalinaBaseMarker {

        @Test
        @DisplayName("emits -Dcatalina.base=<absolute path> for an absolute path")
        void absolutePath() {
            Path base = Paths.get(File.separator + "tmp" + File.separator + "MyApp");

            String marker = OrphanTomcatReclaimer.buildCatalinaBaseMarker(base);

            assertEquals("-Dcatalina.base=" + base.toAbsolutePath().toString(), marker);
        }

        @Test
        @DisplayName("normalizes a relative path to absolute (matches what JVM emits)")
        void relativePathBecomesAbsolute() {
            // The JVM always renders -Dcatalina.base with the absolute path.
            // Pin this so the marker we look for is always the absolute form,
            // regardless of how the caller resolved the path.
            Path relative = Paths.get("MyApp");

            String marker = OrphanTomcatReclaimer.buildCatalinaBaseMarker(relative);

            assertTrue(marker.startsWith("-Dcatalina.base="),
                    "marker prefix mandatory");
            String pathPart = marker.substring("-Dcatalina.base=".length());
            assertTrue(Paths.get(pathPart).isAbsolute(),
                    "marker must contain an absolute path, was: " + pathPart);
        }
    }

    @Nested
    @DisplayName("matchesOrphanMarker(commandLine, marker)")
    class MatchesOrphanMarker {

        private static final String MARKER = "-Dcatalina.base=/path/to/MyApp";

        @Test
        @DisplayName("matches when the marker is followed by a space (typical JVM cmd line)")
        void marker_followedBySpace_matches() {
            // The dominant shape of a JVM command line is space-separated
            // -D flags. This is the most common positive case.
            String cmd = "java -Dcatalina.home=/x " + MARKER + " -Dother=foo Bootstrap start";

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("matches when the marker is at end-of-string")
        void marker_atEndOfString_matches() {
            String cmd = "java -Dother=foo " + MARKER;

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("matches when the marker is followed by a tab (rare but valid)")
        void marker_followedByTab_matches() {
            // Some shells preserve tabs in argv. Character.isWhitespace
            // covers the full whitespace class — pin tab specifically.
            String cmd = "java " + MARKER + "\t-Dother=foo";

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("matches a parallel-run child whose base is <config-base>/.runs/<runId> (Unix sep)")
        void marker_followedByForwardSlash_matches() {
            // Parallel-run children's catalina.base is <config-base>/.runs/<runId>.
            // The reclaim sweeps these along with the parent — the per-run dir
            // doesn't get its own reclaim pass. Documented behaviour.
            String cmd = "java -Dcatalina.home=/x " + MARKER + "/.runs/run-abc Bootstrap start";

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER),
                    "parallel-run child must match parent reclaim");
        }

        @Test
        @DisplayName("matches a parallel-run child whose base uses Windows backslash separators")
        void marker_followedByBackslash_matches() {
            // On Windows, Path.toString uses backslash. The matcher must
            // accept both separator styles so the predicate is correct
            // regardless of how the JVM rendered the path.
            String winMarker = "-Dcatalina.base=C:\\Users\\g\\.idea\\tomcat\\MyApp";
            String cmd = "java " + winMarker + "\\.runs\\run-abc Bootstrap start";

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, winMarker),
                    "Windows-style parallel-run child must match");
        }

        @Test
        @DisplayName("does NOT match a similarly-named config (false-positive guard — bug fix)")
        void marker_followedByLetter_doesNotMatch() {
            // THE BUG FIX. Old code did plain contains() so "MyApp"'s marker
            // was a substring of "MyApp2"'s command line and the reclaim
            // happily killed the unrelated config's process. The boundary
            // check (whitespace/sep/EOS after marker) blocks this.
            String cmd = "java -Dcatalina.base=/path/to/MyApp2 -Dother=foo Bootstrap start";

            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER),
                    "marker for 'MyApp' must NOT match a 'MyApp2' command line");
        }

        @Test
        @DisplayName("does NOT match when followed by a digit (e.g. config name suffix)")
        void marker_followedByDigit_doesNotMatch() {
            String cmd = "java " + MARKER + "1234 -Dother=foo";

            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("does NOT match when followed by an alphanumeric name part")
        void marker_followedByDashedSuffix_doesNotMatch() {
            // Belt-and-braces: a config whose name extends ours with a dash.
            // Dashes aren't path separators or whitespace, so the boundary
            // check correctly rejects the match.
            String cmd = "java " + MARKER + "-extended -Dother=foo";

            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("returns false when the marker is absent entirely")
        void marker_absent_doesNotMatch() {
            String cmd = "java -Dcatalina.base=/different/place -Dother=foo Bootstrap start";

            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }

        @Test
        @DisplayName("returns false for a null command line")
        void nullCommandLine_returnsFalse() {
            // ProcessHandle.info().commandLine() is Optional<String> and can
            // be empty for some short-lived or restricted processes. Pin the
            // null-safe path so the orphan filter never NPEs on those.
            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker(null, MARKER));
        }

        @Test
        @DisplayName("returns false for an empty command line")
        void emptyCommandLine_returnsFalse() {
            assertFalse(OrphanTomcatReclaimer.matchesOrphanMarker("", MARKER));
        }

        @Test
        @DisplayName("matches at the start of the command line (no leading char)")
        void marker_atStartOfString_matches() {
            // Defensive: some invocations might place -D flags first.
            String cmd = MARKER + " -Dother=foo Bootstrap";

            assertTrue(OrphanTomcatReclaimer.matchesOrphanMarker(cmd, MARKER));
        }
    }
}
