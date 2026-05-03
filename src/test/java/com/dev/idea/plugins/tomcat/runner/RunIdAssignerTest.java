package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static helper in {@link RunIdAssigner}.
 *
 * <p>The {@code formatRunId} encoding is contractual — the on-disk
 * directory name {@code <config>/.runs/<runId>/} embeds it directly, and
 * downstream cleanup ({@link TomcatProcessHandler}'s per-run dir delete)
 * walks that path on process exit. A regression in the encoding leads to
 * one of two damages:
 * <ul>
 *   <li><b>Leading dash</b> (without {@link Long#toUnsignedString}) — the
 *       directory name like {@code -1234} is parsed as a flag by some
 *       shells / tooling.</li>
 *   <li><b>Encoding collision</b> (e.g. base 10 instead of base 36) — the
 *       directory names get unwieldy long for large execution ids; any
 *       change to the format must be deliberate.</li>
 * </ul>
 *
 * <p>The instance {@link RunIdAssigner#resolve} method is exercised by
 * the launch pipeline integration paths — it depends on
 * {@code TomcatRunConfiguration}, {@code ExecutionEnvironment}, and the
 * project, which require platform fixtures rather than pure unit tests.
 */
@DisplayName("RunIdAssigner — static helpers")
class RunIdAssignerTest {

    @Nested
    @DisplayName("formatRunId(executionId)")
    class FormatRunId {

        @Test
        @DisplayName("emits 'run-' prefix")
        void hasRunPrefix() {
            // The on-disk directory uses this string verbatim. The 'run-'
            // prefix makes the directory easy to spot among other folders
            // and guarantees no collision with legitimate config names
            // (which can't start with a hyphen-followed token in practice).
            assertTrue(RunIdAssigner.formatRunId(1L).startsWith("run-"),
                    "missing run- prefix");
        }

        @Test
        @DisplayName("encodes executionId in base 36 (compact, dir-name-safe)")
        void encodesInBase36() {
            // Base 36 keeps long ids short — Long.MAX_VALUE encodes to 13
            // chars vs 19 in decimal. Pin a known value: 36 in base 36 is
            // '10' (one zero), so id=36 must produce "run-10".
            assertEquals("run-10", RunIdAssigner.formatRunId(36L));
            assertEquals("run-z", RunIdAssigner.formatRunId(35L));
            assertEquals("run-0", RunIdAssigner.formatRunId(0L));
        }

        @Test
        @DisplayName("uses unsigned encoding so negative ids do not start with '-'")
        void negativeIdsHaveNoLeadingDash() {
            // The bug this prevents: Long.toString(-1, 36) = "-1", giving a
            // directory named "run--1". Some shells parse a leading "--" as
            // a flag terminator and downstream tooling breaks. Long.MIN_VALUE
            // is the worst case — Long.toString returns "-1y2p0ij32e8e8".
            // Long.toUnsignedString returns "1y2p0ij32e8e7" (no dash).
            String minResult = RunIdAssigner.formatRunId(Long.MIN_VALUE);
            String negOneResult = RunIdAssigner.formatRunId(-1L);

            assertFalse(minResult.contains("--"),
                    "Long.MIN_VALUE must not produce 'run--…', was: " + minResult);
            assertFalse(negOneResult.contains("--"),
                    "-1 must not produce 'run--1', was: " + negOneResult);
            // The id portion (after run-) must not start with a dash either.
            assertFalse(minResult.substring("run-".length()).startsWith("-"),
                    "encoded id must not start with -, was: " + minResult);
            assertFalse(negOneResult.substring("run-".length()).startsWith("-"),
                    "encoded id must not start with -, was: " + negOneResult);
        }

        @Test
        @DisplayName("produces stable output for the same input (pure function)")
        void isPureFunction() {
            // resolve() depends on this stability for its idempotence
            // guarantee. Two calls with the same executionId must return
            // bit-identical strings — pin this contract.
            assertEquals(RunIdAssigner.formatRunId(12345L),
                    RunIdAssigner.formatRunId(12345L));
            assertEquals(RunIdAssigner.formatRunId(Long.MAX_VALUE),
                    RunIdAssigner.formatRunId(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("produces different output for different inputs")
        void differentIdsProduceDifferentResults() {
            // Two simultaneous launches in the same IDE session get
            // distinct execution ids. The directory names must therefore
            // be distinct or the launches collide.
            assertNotEquals(RunIdAssigner.formatRunId(1L),
                    RunIdAssigner.formatRunId(2L));
        }

        @Test
        @DisplayName("output contains only directory-name-safe characters")
        void outputIsFilesystemSafe() {
            // run- + base36 = [run-][0-9a-z]+ — all characters are safe on
            // every common filesystem (case-insensitive ones included). Pin
            // this explicitly so a future encoding change must justify any
            // characters outside this set.
            String[] samples = {
                    RunIdAssigner.formatRunId(0L),
                    RunIdAssigner.formatRunId(1L),
                    RunIdAssigner.formatRunId(35L),
                    RunIdAssigner.formatRunId(36L),
                    RunIdAssigner.formatRunId(Long.MAX_VALUE),
                    RunIdAssigner.formatRunId(Long.MIN_VALUE),
                    RunIdAssigner.formatRunId(System.currentTimeMillis()),
            };
            for (String s : samples) {
                assertTrue(s.matches("run-[0-9a-z]+"),
                        "id '" + s + "' contains chars outside [run-][0-9a-z]+");
            }
        }

        @Test
        @DisplayName("encodes Long.MAX_VALUE without overflow / sign issues")
        void encodesMaxValue() {
            // Smoke: Long.MAX_VALUE = 9223372036854775807 = "1y2p0ij32e8e7" in base 36.
            assertEquals("run-1y2p0ij32e8e7",
                    RunIdAssigner.formatRunId(Long.MAX_VALUE));
        }
    }
}
