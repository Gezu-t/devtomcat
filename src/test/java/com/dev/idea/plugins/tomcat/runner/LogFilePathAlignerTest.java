package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.model.TomcatLogFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static helper in {@link LogFilePathAligner}.
 *
 * <p>The instance {@link LogFilePathAligner#align} method is exercised by the
 * launch pipeline integration tests — it touches IntelliJ's
 * {@code LogFileOptions} model and the project filesystem, so it's not a
 * pure unit. The matcher this test pins ({@code matchesStandardFilename})
 * is the gate that decides whether each log entry gets rewritten — a
 * regression in either direction has visible damage:
 * <ul>
 *   <li><b>Too eager</b> — rewrite a user's customised log path back to the
 *       plugin's directory, silently throwing away their setting.</li>
 *   <li><b>Too narrow</b> — fail to rewrite a parallel-run log entry, so
 *       the "Logs" tab silently disappears from Services.</li>
 * </ul>
 */
@DisplayName("LogFilePathAligner — static helpers")
class LogFilePathAlignerTest {

    @Nested
    @DisplayName("matchesStandardFilename(currentPath, logFile)")
    class MatchesStandardFilename {

        // --- exact-pattern (no wildcard) tests ---

        @Test
        @DisplayName("matches an exact filename with no wildcard pattern")
        void exactPattern_matchingFilename() {
            // catalina.out has a literal pattern (no wildcard).
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");

            assertTrue(LogFilePathAligner.matchesStandardFilename(
                    "/home/user/.idea/tomcat/MyApp/logs/catalina.out", lf));
        }

        @Test
        @DisplayName("does not match when filename differs from exact pattern")
        void exactPattern_differentFilename() {
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");

            assertFalse(LogFilePathAligner.matchesStandardFilename(
                    "/home/user/.idea/tomcat/MyApp/logs/localhost.log", lf));
        }

        // --- wildcard-pattern tests (the most common shape: catalina.*.log) ---

        @Test
        @DisplayName("matches a dated file against a *-wildcard pattern")
        void wildcardPattern_datedFilename_matches() {
            // Standard Tomcat file rotation: catalina.<YYYY-MM-DD>.log
            TomcatLogFile lf = new TomcatLogFile("catalina", "catalina.*.log");

            assertTrue(LogFilePathAligner.matchesStandardFilename(
                    "/home/user/.idea/tomcat/MyApp/logs/catalina.2024-01-15.log", lf));
        }

        @Test
        @DisplayName("matches when the wildcard span is empty (zero chars)")
        void wildcardPattern_emptySpan_matches() {
            // The "*" represents zero or more characters. A filename like
            // "catalina..log" must match — defends against a future Tomcat
            // rotation scheme that emits the unrotated file with an empty span.
            TomcatLogFile lf = new TomcatLogFile("catalina", "catalina.*.log");

            assertTrue(LogFilePathAligner.matchesStandardFilename(
                    "catalina..log", lf),
                    "wildcard must allow a zero-character span");
        }

        @Test
        @DisplayName("does not match a different prefix against the wildcard pattern")
        void wildcardPattern_differentPrefix_doesNotMatch() {
            TomcatLogFile lf = new TomcatLogFile("catalina", "catalina.*.log");

            assertFalse(LogFilePathAligner.matchesStandardFilename(
                    "/home/user/.idea/tomcat/MyApp/logs/localhost.2024-01-15.log", lf));
        }

        @Test
        @DisplayName("does not match a different suffix against the wildcard pattern")
        void wildcardPattern_differentSuffix_doesNotMatch() {
            TomcatLogFile lf = new TomcatLogFile("access", "localhost_access_log.*.txt");

            // Same prefix, wrong suffix — the matcher must not be permissive
            // about endings or it would rewrite log files Tomcat doesn't own.
            assertFalse(LogFilePathAligner.matchesStandardFilename(
                    "/home/user/.idea/tomcat/MyApp/logs/localhost_access_log.2024-01-15.log", lf));
        }

        @Test
        @DisplayName("does not match a filename shorter than prefix + suffix")
        void wildcardPattern_filenameTooShort_doesNotMatch() {
            // The minimum-length check matters: without it, "log" would
            // match the pattern "catalina.*.log" because both startsWith
            // and endsWith would succeed on the same tail of the string.
            TomcatLogFile lf = new TomcatLogFile("catalina", "catalina.*.log");

            assertFalse(LogFilePathAligner.matchesStandardFilename("log", lf),
                    "filename shorter than prefix+suffix must not match");
        }

        // --- directory-handling tests ---

        @Test
        @DisplayName("strips directory prefix using the platform separator")
        void platformSeparator_correctlyStripped() {
            // Path uses the host platform's separator. The matcher uses
            // File.separator, so the test must too — we're verifying the
            // matcher uses the right separator for this platform.
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");
            String path = "deep" + File.separator + "nested" + File.separator + "catalina.out";

            assertTrue(LogFilePathAligner.matchesStandardFilename(path, lf));
        }

        @Test
        @DisplayName("treats a path with no separator as a bare filename")
        void noSeparator_treatedAsFilename() {
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");

            assertTrue(LogFilePathAligner.matchesStandardFilename("catalina.out", lf),
                    "bare filename (no path) must be matched directly against pattern");
        }

        @Test
        @DisplayName("ignores the directory portion when extracting the filename")
        void directoryPortion_ignoredForMatching() {
            // The whole point of filename-based matching: directory drift
            // (sandbox vs real IDE, moved project, custom path) doesn't
            // disqualify a log entry from realignment as long as the
            // filename still matches the standard pattern.
            TomcatLogFile lf = new TomcatLogFile("catalina", "catalina.*.log");
            String suspiciousPath = File.separator + "completely" + File.separator
                    + "different" + File.separator + "place" + File.separator
                    + "catalina.2024-01-15.log";

            assertTrue(LogFilePathAligner.matchesStandardFilename(suspiciousPath, lf),
                    "matcher must accept any directory — drift-resistance is the contract");
        }

        // --- empty-input tests ---

        @Test
        @DisplayName("returns true for null currentPath (fresh-entry alignment)")
        void nullPath_returnsTrue() {
            // A fresh LogFileOptions with no path yet is still a candidate
            // for alignment — the contract is "rewrite to the runtime path".
            // Pinned because inverting this branch would silently break
            // first-launch log tabs.
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");

            assertTrue(LogFilePathAligner.matchesStandardFilename(null, lf));
        }

        @Test
        @DisplayName("returns true for empty currentPath (fresh-entry alignment)")
        void emptyPath_returnsTrue() {
            TomcatLogFile lf = new TomcatLogFile("catalina-out", "catalina.out");

            assertTrue(LogFilePathAligner.matchesStandardFilename("", lf));
        }
    }
}
