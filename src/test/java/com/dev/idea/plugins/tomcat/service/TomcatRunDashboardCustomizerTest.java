package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TomcatRunDashboardCustomizer")
class TomcatRunDashboardCustomizerTest {

    @Nested
    @DisplayName("formatDuration")
    class FormatDurationTests {

        @Test
        @DisplayName("keeps sub-second values in milliseconds")
        void milliseconds() {
            assertEquals("850ms", TomcatRunDashboardCustomizer.formatDuration(850));
        }

        @Test
        @DisplayName("formats seconds with one decimal place")
        void seconds() {
            assertEquals("12.3s", TomcatRunDashboardCustomizer.formatDuration(12_345));
        }

        @Test
        @DisplayName("formats exact minute boundaries cleanly")
        void exactMinute() {
            assertEquals("1m 0s", TomcatRunDashboardCustomizer.formatDuration(60_000));
        }

        @Test
        @DisplayName("formats multi-minute durations")
        void minutes() {
            assertEquals("1m 23s", TomcatRunDashboardCustomizer.formatDuration(83_000));
        }
    }

    @Nested
    @DisplayName("formatIssueSummary")
    class FormatIssueSummaryTests {

        @Test
        @DisplayName("formats both errors and warnings")
        void errorsAndWarnings() {
            assertEquals("2 errors · 1 warning",
                    TomcatRunDashboardCustomizer.formatIssueSummary(2, 1));
        }

        @Test
        @DisplayName("formats empty string when there are no issues")
        void emptyWhenNoIssues() {
            assertTrue(TomcatRunDashboardCustomizer.formatIssueSummary(0, 0).isEmpty());
        }
    }
}
